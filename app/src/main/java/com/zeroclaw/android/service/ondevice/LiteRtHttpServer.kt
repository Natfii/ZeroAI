/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.service.ondevice

import android.util.Log
import java.io.BufferedOutputStream
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

/**
 * Loopback-only HTTP server that exposes the loaded LiteRT-LM
 * engine as an OpenAI-compatible `/v1/chat/completions` endpoint.
 *
 * Lifecycle:
 *  - [OnDeviceInferenceManager] starts the server when the engine
 *    transitions to `Loaded`, stops it when the engine unloads.
 *  - Bound to `127.0.0.1` only — never reachable from another
 *    device. The "phone as LLM engine" network-expose feature is
 *    deferred until we design the auth + UX for it.
 *
 * Concurrency: single inference at a time enforced by the manager's
 * mutex, so even if a second HTTP client connects mid-generation
 * its request blocks on the mutex until the first finishes. Fine
 * for our scale.
 *
 * Hand-rolled on raw [ServerSocket] rather than Ktor / Netty — for
 * a single-endpoint loopback server the extra ~3 MB of Ktor deps
 * isn't worth it, and we'd never reach for the framework's
 * routing / DI / TLS features here.
 *
 * Body reading is intentionally byte-oriented (`InputStream` →
 * `ByteArrayOutputStream`) rather than character-oriented
 * (`BufferedReader`) because `Content-Length` is in bytes and any
 * `Reader`-based approach silently corrupts multi-byte UTF-8
 * content (emoji, accented chars, smart quotes).
 *
 * @param port TCP port to bind. Defaults to `11434` so it lines up
 *   with the Ollama OpenAI-compat port — easier for users to point
 *   existing tooling at our endpoint.
 * @param serveCompletion Callback that streams the response for the
 *   user message's text. Returns a cold [Flow] that the handler will
 *   collect to produce the response body. Implementation is owned
 *   by [OnDeviceInferenceManager] so the server doesn't carry an
 *   engine reference directly.
 * @param modelName Function returning the human-readable variant
 *   name to echo in response envelopes (e.g. `"gemma-4-e2b-it"`).
 *   Lambda-shaped so the server reflects the *current* loaded
 *   model even after a swap.
 * @param requiredAuthToken Per-request supplier of the bearer token
 *   that inbound requests must present. See the property KDoc below.
 */
class LiteRtHttpServer(
    private val port: Int = DEFAULT_PORT,
    private val serveCompletion: (OpenAiChatRequest) -> Flow<OpenAiResponseEvent>,
    private val modelName: () -> String,
    /**
     * Supplies the bearer token every inbound request must
     * present in its `Authorization` header. Required — no default
     * — because a default of `{ "" }` would silently fail open if
     * a future caller forgot to inject it, and "secure-when-called-
     * correctly" is the wrong posture for an unauthenticated
     * loopback endpoint accessible by any app on the device.
     *
     * The lambda is evaluated per request, so the manager can
     * rotate the token without re-constructing the server.
     */
    private val requiredAuthToken: () -> String,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val supervisor = SupervisorJob()

    /**
     * Dedicated cached thread pool for per-connection handlers.
     *
     * Why not `Dispatchers.IO`: each handler calls
     * `runBlocking { serveCompletion(...).collect { ... } }`, which
     * pins the executing thread for the full token-by-token
     * generation (often 10-30 s). Sharing the platform IO pool would
     * starve every Room query / SAF read / OkHttp call in the rest of
     * the app whenever the daemon fans out a few parallel channel
     * messages.
     *
     * Cached pool because connection count is bursty and short-lived;
     * threads expire after 60 s idle. Named so leaks show up clearly
     * in thread dumps.
     */
    private val handlerExecutor =
        Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "LiteRtHttpHandler-${handlerThreadCounter.getAndIncrement()}")
                .apply { isDaemon = true }
        }
    private val handlerDispatcher = handlerExecutor.asCoroutineDispatcher()
    private val scope = CoroutineScope(handlerDispatcher + supervisor)
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    /** Whether the accept loop is currently running. */
    val isRunning: Boolean
        get() = acceptJob?.isActive == true

    /**
     * Starts the server. Idempotent — calling [start] while already
     * running is a no-op. Binds synchronously so subsequent
     * connection attempts from the daemon don't race the bind step.
     */
    fun start() {
        if (isRunning) return
        val socket =
            ServerSocket(port, BACKLOG, InetAddress.getByName(LOOPBACK_ADDRESS))
        serverSocket = socket
        Log.i(TAG, "Listening on $LOOPBACK_ADDRESS:$port")
        acceptJob =
            scope.launch {
                while (isActive && !socket.isClosed) {
                    val client =
                        try {
                            socket.accept()
                        } catch (
                            @Suppress("TooGenericExceptionCaught") e: Throwable,
                        ) {
                            if (!socket.isClosed) {
                                Log.w(TAG, "Accept failed: ${e.message}")
                            }
                            null
                        } ?: break
                    launch { handleConnection(client) }
                }
            }
    }

    /**
     * Stops accepting new connections and closes the listening
     * socket. In-flight per-connection coroutines complete on their
     * own; cancellation propagates through the supervisor scope
     * only when [shutdown] is called.
     */
    fun stop() {
        val socket = serverSocket ?: return
        serverSocket = null
        try {
            socket.close()
        } catch (_: Throwable) {
            // Best-effort close; socket may already be in a closed state.
        }
        acceptJob?.cancel()
        acceptJob = null
        Log.i(TAG, "Stopped")
    }

    /**
     * Tears down the entire server scope, including in-flight
     * connection handlers (their writes to the now-closed socket
     * will throw and abort cleanly). Safe to call from the
     * manager's [OnDeviceInferenceManager.shutdown].
     */
    fun shutdown() {
        stop()
        scope.cancel()
        // Release the cached thread pool so its idle threads don't
        // outlive the manager. Without this, repeated daemon
        // restarts during dev accumulate "LiteRtHttpHandler-*" threads
        // until the next process death.
        handlerDispatcher.close()
        handlerExecutor.shutdown()
    }

    /**
     * Per-connection handler. Reads HTTP request line + headers as
     * raw bytes (so the body bytes remain in the stream untouched),
     * then reads exactly `Content-Length` bytes for the body. POST
     * to `/v1/chat/completions` is the only handled path; everything
     * else 404s.
     */
    @Suppress("TooGenericExceptionCaught", "CognitiveComplexMethod")
    private fun handleConnection(client: Socket) {
        try {
            client.use { socket ->
                val input = socket.getInputStream()
                val writer =
                    BufferedWriter(
                        OutputStreamWriter(
                            BufferedOutputStream(socket.getOutputStream()),
                            StandardCharsets.UTF_8,
                        ),
                    )
                val rawHeaders = readRawHeaders(input) ?: return@use
                val (requestLine, headers) = parseHeaderBlock(rawHeaders)
                val parts = requestLine.split(' ')
                if (parts.size < 2) {
                    sendStatus(writer, HTTP_BAD_REQUEST, "Bad Request")
                    return@use
                }
                val method = parts[0]
                val path = parts[1]
                // Bearer-token auth on the loopback endpoint.
                // Android's `127.0.0.1` isn't isolated per-app —
                // any app with INTERNET permission can reach this
                // socket. Without a check, a co-installed app
                // could pump prompts through the engine (battery /
                // GPU / RAM DoS at minimum, tool-call SSRF at
                // worst). Token is generated per process by
                // `OnDeviceInferenceManager`, emitted to the
                // daemon's TOML as `api_key`, and arrives back
                // here as `Authorization: Bearer ...`.
                val expected = requiredAuthToken()
                if (expected.isEmpty()) {
                    // Defensive: should never happen because the
                    // injected supplier returns a per-process
                    // secret initialised before the server is
                    // constructed. If it does, fail closed.
                    Log.w(TAG, "Rejecting request: server has no auth token configured")
                    sendStatus(writer, HTTP_SERVICE_UNAVAILABLE, "Service Unavailable")
                    return@use
                }
                val presented =
                    headers["authorization"]
                        ?.removePrefix("Bearer ")
                        ?.removePrefix("bearer ")
                        .orEmpty()
                if (!constantTimeEquals(presented, expected)) {
                    Log.w(TAG, "Rejecting request: missing or wrong bearer token")
                    sendStatus(writer, HTTP_UNAUTHORIZED, "Unauthorized")
                    return@use
                }
                if (headers["transfer-encoding"]?.lowercase()?.contains("chunked") == true) {
                    // Chunked decoding isn't implemented; reject with the
                    // HTTP-spec-correct status so the client knows to retry
                    // with a Content-Length-terminated body.
                    sendStatus(writer, HTTP_LENGTH_REQUIRED, "Length Required")
                    return@use
                }
                val bodyLength = headers["content-length"]?.toIntOrNull() ?: 0
                if (bodyLength < 0 || bodyLength > MAX_BODY_BYTES) {
                    // Cap on Content-Length so a malformed (or malicious
                    // local-process) request can't trigger an
                    // unbounded ByteArray allocation. Android's
                    // loopback isn't isolated per-app — any app with
                    // INTERNET permission can reach 127.0.0.1:11434
                    // and would otherwise be able to OOM the
                    // inference service. 8 MB is well above the
                    // largest realistic legitimate body (system
                    // prompt + tool definitions + tool results +
                    // user message rarely exceeds 200 KB).
                    sendStatus(writer, HTTP_PAYLOAD_TOO_LARGE, "Payload Too Large")
                    return@use
                }
                val body = readBodyBytes(input, bodyLength)
                if (method.equals("POST", ignoreCase = true) &&
                    path.startsWith(CHAT_COMPLETIONS_PATH)
                ) {
                    respondToChatCompletions(writer, body)
                } else {
                    sendStatus(writer, HTTP_NOT_FOUND, "Not Found")
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Connection handler failed: ${e.message}")
        }
    }

    /**
     * Constant-time string comparison for the bearer-token check.
     * Always walks the full length of the expected token so a
     * timing attacker can't infer a shared prefix from response
     * latency. Returns false when lengths differ — the early-exit
     * here is intentional and not timing-sensitive (length is
     * public information).
     */
    private fun constantTimeEquals(
        a: String,
        b: String,
    ): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].code xor b[i].code)
        }
        return diff == 0
    }

    /**
     * Reads bytes from [input] until the `\r\n\r\n` header
     * terminator. Returns `null` on premature EOF.
     */
    private fun readRawHeaders(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        var lastFour = 0
        while (true) {
            val byte = input.read()
            if (byte < 0) return null
            buffer.write(byte)
            // Sliding 4-byte window in the low 32 bits of an Int.
            // The mask used to be `-1 ushr 0` which evaluates to
            // 0xFFFFFFFF — a no-op AND on a 32-bit Int. Since each
            // shift-left by 8 only loses the top byte (which is the
            // 5-byte-old data we don't care about), the mask is
            // unnecessary, and we drop the AND. The byte from
            // `InputStream.read()` is guaranteed 0..255, so no
            // sign-extension risk.
            lastFour = (lastFour shl HEADER_BYTE_SHIFT) or byte
            if (lastFour == CRLF_CRLF_INT) break
            if (buffer.size() > MAX_HEADER_BYTES) return null
        }
        // Drop the trailing CRLFCRLF so the parser doesn't see empty lines.
        val raw = buffer.toByteArray()
        return String(raw, 0, raw.size - HEADER_TERMINATOR_BYTES, StandardCharsets.UTF_8)
    }

    /**
     * Parses a CRLF-delimited header block into the request line +
     * a lowercased-name header map.
     */
    private fun parseHeaderBlock(rawHeaders: String): Pair<String, Map<String, String>> {
        val lines = rawHeaders.split("\r\n")
        val requestLine = lines.firstOrNull().orEmpty()
        val headers = mutableMapOf<String, String>()
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()
            headers[name] = value
        }
        return requestLine to headers
    }

    /**
     * Reads exactly [length] bytes from [input], returning the
     * UTF-8 decoded string. Short-reads return whatever bytes
     * arrived before EOF.
     */
    private fun readBodyBytes(
        input: InputStream,
        length: Int,
    ): String {
        if (length <= 0) return ""
        val buffer = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(buffer, read, length - read)
            if (n < 0) break
            read += n
        }
        return String(buffer, 0, read, StandardCharsets.UTF_8)
    }

    /**
     * Parses the body as an [OpenAiChatRequest] and runs it through
     * [serveCompletion]. The event flow drives either streaming SSE
     * output (`stream=true`) or a single non-streaming envelope.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun respondToChatCompletions(
        writer: BufferedWriter,
        body: String,
    ) {
        val request =
            try {
                json.decodeFromString(OpenAiChatRequest.serializer(), body)
            } catch (e: Throwable) {
                sendStatus(writer, HTTP_BAD_REQUEST, "Bad Request: ${e.message}")
                return
            }
        if (request.messages.none { it.role == "user" }) {
            sendStatus(writer, HTTP_BAD_REQUEST, "Bad Request: missing user message")
            return
        }
        val responseModel = request.model ?: modelName()
        if (request.stream) {
            streamChatResponse(writer, request, responseModel)
        } else {
            sendChatResponse(writer, request, responseModel)
        }
    }

    /**
     * Drains the completion flow into a single response envelope.
     * If the model emitted text, returns assistant content with
     * `finish_reason: "stop"`. If the model emitted tool calls,
     * returns the `tool_calls` array with `finish_reason: "tool_calls"`
     * so the daemon's agent loop dispatches them. Failed events
     * surface as a 500 with a human-readable reason.
     */
    private fun sendChatResponse(
        writer: BufferedWriter,
        request: OpenAiChatRequest,
        responseModel: String,
    ) {
        val builder = StringBuilder()
        var toolCalls: List<OpenAiToolCall>? = null
        var failure: String? = null
        runBlocking {
            serveCompletion(request).collect { event ->
                when (event) {
                    is OpenAiResponseEvent.TextDelta -> builder.append(event.text)
                    is OpenAiResponseEvent.ToolCallsEmitted -> toolCalls = event.toolCalls
                    is OpenAiResponseEvent.Failed -> failure = event.reason
                }
            }
        }
        if (failure != null) {
            sendStatus(writer, HTTP_INTERNAL_ERROR, "Internal: ${failure ?: ""}")
            return
        }
        val capturedToolCalls = toolCalls
        val choice =
            if (capturedToolCalls != null) {
                OpenAiChatChoice(
                    index = 0,
                    message = assistantToolCallsMessage(capturedToolCalls),
                    finishReason = "tool_calls",
                )
            } else {
                OpenAiChatChoice(
                    index = 0,
                    message = assistantTextMessage(builder.toString()),
                    finishReason = "stop",
                )
            }
        val payload =
            OpenAiChatResponse(
                id = "chatcmpl-${UUID.randomUUID()}",
                `object` = "chat.completion",
                created = System.currentTimeMillis() / MILLIS_PER_SECOND,
                model = responseModel,
                choices = listOf(choice),
            )
        val jsonText = json.encodeToString(OpenAiChatResponse.serializer(), payload)
        val bytes = jsonText.toByteArray(StandardCharsets.UTF_8)
        writer.write("HTTP/1.1 200 OK\r\n")
        writer.write("Content-Type: application/json; charset=utf-8\r\n")
        writer.write("Content-Length: ${bytes.size}\r\n")
        writer.write("Connection: close\r\n\r\n")
        writer.write(jsonText)
        writer.flush()
    }

    /**
     * Streams the engine response as SSE `text/event-stream` chunks,
     * matching OpenAI's streaming format. Text deltas land as
     * incremental `delta.content` updates; a tool-calls event ends
     * the stream with a final `tool_calls` delta + `finish_reason:
     * "tool_calls"`.
     */
    @Suppress("LongMethod")
    private fun streamChatResponse(
        writer: BufferedWriter,
        request: OpenAiChatRequest,
        responseModel: String,
    ) {
        val id = "chatcmpl-${UUID.randomUUID()}"
        val created = System.currentTimeMillis() / MILLIS_PER_SECOND
        writer.write("HTTP/1.1 200 OK\r\n")
        writer.write("Content-Type: text/event-stream; charset=utf-8\r\n")
        writer.write("Cache-Control: no-cache\r\n")
        writer.write("Connection: close\r\n\r\n")
        writer.flush()
        runBlocking {
            serveCompletion(request).collect { event ->
                val envelope =
                    when (event) {
                        is OpenAiResponseEvent.TextDelta ->
                            OpenAiChatResponse(
                                id = id,
                                `object` = "chat.completion.chunk",
                                created = created,
                                model = responseModel,
                                choices =
                                    listOf(
                                        OpenAiChatChoice(
                                            index = 0,
                                            delta = assistantTextMessage(event.text),
                                            finishReason = null,
                                        ),
                                    ),
                            )
                        is OpenAiResponseEvent.ToolCallsEmitted ->
                            OpenAiChatResponse(
                                id = id,
                                `object` = "chat.completion.chunk",
                                created = created,
                                model = responseModel,
                                choices =
                                    listOf(
                                        OpenAiChatChoice(
                                            index = 0,
                                            delta = assistantToolCallsMessage(event.toolCalls),
                                            finishReason = "tool_calls",
                                        ),
                                    ),
                            )
                        is OpenAiResponseEvent.Failed ->
                            OpenAiChatResponse(
                                id = id,
                                `object` = "chat.completion.chunk",
                                created = created,
                                model = responseModel,
                                choices =
                                    listOf(
                                        OpenAiChatChoice(
                                            index = 0,
                                            delta = assistantTextMessage(""),
                                            finishReason = "error",
                                        ),
                                    ),
                            )
                    }
                writer.write("data: ")
                writer.write(json.encodeToString(OpenAiChatResponse.serializer(), envelope))
                writer.write("\n\n")
                writer.flush()
            }
        }
        writer.write("data: [DONE]\n\n")
        writer.flush()
    }

    /**
     * Writes a minimal HTTP status response with no body.
     *
     * The reason phrase is sanitized: CR/LF/tab characters are
     * replaced with spaces and the result is truncated. Without
     * this, an upstream `kotlinx.serialization` parse error (which
     * naturally contains newlines like "Unexpected JSON token at
     * offset 123:\nUse 'ignoreUnknownKeys = true'") would break the
     * HTTP-status-line framing and clients like reqwest/hyper would
     * report "invalid HTTP header parsed" instead of the actual
     * bad-request reason.
     */
    private fun sendStatus(
        writer: BufferedWriter,
        code: Int,
        message: String,
    ) {
        val safe =
            message
                .replace(Regex("[\\r\\n\\t]"), " ")
                .take(MAX_STATUS_REASON_LEN)
        writer.write("HTTP/1.1 $code $safe\r\n")
        writer.write("Content-Length: 0\r\n")
        writer.write("Connection: close\r\n\r\n")
        writer.flush()
    }

    /** Constants for the loopback OpenAI-compat server. */
    companion object {
        /**
         * Default TCP port. Matches the Ollama OpenAI-compat port
         * so users can repoint existing tooling at the on-device
         * endpoint without code changes.
         */
        const val DEFAULT_PORT: Int = 11434

        /** Loopback address — never accept off-device connections. */
        const val LOOPBACK_ADDRESS: String = "127.0.0.1"

        /** Logcat tag for server messages. */
        private const val TAG: String = "LiteRtHttpServer"

        /** OS accept queue backlog. */
        private const val BACKLOG: Int = 8

        /** Path prefix for the OpenAI chat-completions endpoint. */
        private const val CHAT_COMPLETIONS_PATH: String = "/v1/chat/completions"

        /** Milliseconds per second for Unix-time conversion. */
        private const val MILLIS_PER_SECOND: Long = 1000L

        /** Bit shift used by the sliding-window header terminator detector. */
        private const val HEADER_BYTE_SHIFT: Int = 8

        /**
         * `\r\n\r\n` packed into a single int for cheap comparison
         * against the sliding window of the last 4 bytes seen.
         */
        private const val CRLF_CRLF_INT: Int =
            (0x0D shl 24) or (0x0A shl 16) or (0x0D shl 8) or 0x0A

        /** Length in bytes of the `\r\n\r\n` terminator. */
        private const val HEADER_TERMINATOR_BYTES: Int = 4

        /**
         * Safety cap on the header block size — anything beyond is
         * almost certainly a malformed request worth rejecting.
         */
        private const val MAX_HEADER_BYTES: Int = 16 * 1024

        /**
         * Cap on the request body size. Without this, a malicious or
         * malfunctioning local-process caller can send `Content-Length:
         * 100000000` and force an immediate 100 MB `ByteArray` allocation
         * — trivial DoS against the inference service. 8 MB sits well
         * above the largest legitimate body the daemon emits (system
         * prompt + tool defs + tool results + user message).
         */
        private const val MAX_BODY_BYTES: Int = 8 * 1024 * 1024

        /**
         * Cap on the HTTP status reason phrase length, after CR/LF
         * sanitization. Keeps even pathological exception messages
         * inside a single, well-formed status line.
         */
        private const val MAX_STATUS_REASON_LEN: Int = 200

        /** HTTP 400 Bad Request status code. */
        private const val HTTP_BAD_REQUEST: Int = 400

        /** HTTP 401 Unauthorized status code. */
        private const val HTTP_UNAUTHORIZED: Int = 401

        /** HTTP 404 Not Found status code. */
        private const val HTTP_NOT_FOUND: Int = 404

        /** HTTP 411 Length Required status code. */
        private const val HTTP_LENGTH_REQUIRED: Int = 411

        /** HTTP 413 Payload Too Large status code. */
        private const val HTTP_PAYLOAD_TOO_LARGE: Int = 413

        /** HTTP 500 Internal Server Error status code. */
        private const val HTTP_INTERNAL_ERROR: Int = 500

        /** HTTP 503 Service Unavailable status code. */
        private const val HTTP_SERVICE_UNAVAILABLE: Int = 503

        /**
         * Monotonic counter for naming handler threads. Static so
         * multiple server instances during the same process lifetime
         * (rare — only during dev hot-reload) don't share a name.
         */
        private val handlerThreadCounter = AtomicInteger(0)
    }
}
