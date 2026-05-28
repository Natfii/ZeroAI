/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.service.ondevice

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.ToolProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion

/**
 * Wraps the LiteRT-LM [Engine] + [com.google.ai.edge.litertlm.Conversation]
 * surface in a small Kotlin-friendly API used by the daemon-scoped
 * inference manager.
 *
 * Lifecycle:
 *  - [load] opens the engine against a downloaded `.litertlm` file.
 *    The first call blocks the calling coroutine for ~2-6 s on
 *    Tensor-G5-class hardware while LiteRT-LM mmaps the weights and
 *    initialises the GPU delegate.
 *  - [send] streams response text as deltas. Subsequent calls share
 *    the underlying [com.google.ai.edge.litertlm.Conversation]'s
 *    turn history, so the engine sees a multi-turn dialog.
 *  - [resetConversation] tears down and re-creates the conversation
 *    without unloading the engine (cheap — engine stays warm).
 *  - [unload] closes everything. Safe to call from any thread; idem-
 *    potent. Daemon stop hooks call this so the model releases its
 *    GPU memory before the foreground service exits.
 *
 * Single-instance per process: the engine is heavyweight and the
 * underlying model can't serve concurrent prompts. The owning
 * [OnDeviceInferenceManager] serialises calls.
 */
class LiteRtGemmaInference {
    @Volatile
    private var engine: Engine? = null

    @Volatile
    private var conversation: com.google.ai.edge.litertlm.Conversation? = null

    /**
     * Sticky cancellation flag for in-flight loads. Set by
     * [requestCancelLoad] so a Stop tap during the multi-second
     * `Engine.initialize()` JNI call results in an immediate unload
     * the moment the JNI returns, instead of leaving the engine
     * briefly Loaded before the reconciler tears it down.
     *
     * The JNI call itself cannot be interrupted — LiteRT-LM exposes
     * no cancellation hook — so the best we can do is honour the
     * request on the next observable boundary.
     */
    @Volatile
    private var loadCancelled: Boolean = false

    /** Whether [load] has been called and the engine is ready. */
    val isLoaded: Boolean
        get() = engine?.isInitialized() == true

    /**
     * Initialises the LiteRT-LM engine against the model file at
     * [modelPath].
     *
     * Re-calling [load] before [unload] tears down the previous
     * engine first, so callers can switch variants in place.
     *
     * @param modelPath Absolute path to the downloaded `.litertlm` file.
     * @param backend Backend to attempt. Defaults to GPU.
     * @param cacheDir Optional writable directory for GPU shader /
     *   kernel cache. Strongly recommended for the GPU backend: the
     *   native code uses this to persist OpenCL kernel binaries and
     *   without it the cold-compile path can fail on some devices.
     * @param maxNumTokens Maximum token budget for a single inference
     *   pass. Bounds engine working memory and per-request runtime.
     * @return [Result.success] when the engine is initialised, or
     *   [Result.failure] carrying the underlying exception.
     */
    @Suppress("TooGenericExceptionCaught")
    fun load(
        modelPath: String,
        backend: Backend = Backend.GPU(),
        cacheDir: String? = null,
        maxNumTokens: Int = DEFAULT_MAX_TOKENS,
    ): Result<Unit> {
        unload()
        loadCancelled = false
        return try {
            val cfg =
                EngineConfig(
                    modelPath = modelPath,
                    backend = backend,
                    visionBackend = null,
                    audioBackend = null,
                    maxNumTokens = maxNumTokens,
                    maxNumImages = null,
                    cacheDir = cacheDir,
                )
            engine = Engine(cfg).also { it.initialize() }
            // Intentionally do NOT create a persistent conversation
            // here: LiteRT-LM enforces "only one conversation per
            // engine", so a long-lived one would block every
            // per-request `createStatelessConversation` the HTTP
            // server needs. Callers acquire short-lived conversations
            // via [createStatelessConversation] or the legacy
            // chat helpers below — all of which close immediately.
            conversation = null
            if (loadCancelled) {
                // A cancel request landed during the JNI initialise
                // call. Honour it now by tearing the engine back down
                // — the caller will observe an empty success-but-
                // immediately-unloaded transition and the reconciler
                // will leave state at Idle.
                unload()
                Result.failure(LoadCancelledException())
            } else {
                Result.success(Unit)
            }
        } catch (e: Throwable) {
            unload()
            Result.failure(e)
        }
    }

    /**
     * Requests cancellation of an in-flight [load]. The underlying
     * `Engine.initialize()` JNI call cannot be interrupted, so this
     * does not shorten the load itself — it sets a flag that [load]
     * inspects immediately after the JNI returns and triggers an
     * unload before the caller observes a Loaded state.
     */
    fun requestCancelLoad() {
        loadCancelled = true
    }

    /**
     * Thrown by [load] when [requestCancelLoad] was invoked during
     * the JNI initialise call. Lets callers distinguish a genuine
     * engine init failure from a user-cancelled load (so the UI can
     * suppress the "load failed" Failed state in the latter case).
     */
    class LoadCancelledException : RuntimeException("Engine load cancelled by user before completion")

    /**
     * Streams a response from a fresh, short-lived conversation
     * against the loaded engine.
     *
     * Each call opens a one-shot `Conversation` (LiteRT-LM only
     * allows one alive at a time), drains its message stream as
     * text deltas, and closes it on completion. Used by the
     * Terminal daemon-off fallback path — when the daemon is up,
     * Terminal routes through it instead so tools and history live
     * in the agent loop.
     *
     * Returns an empty flow when the engine isn't loaded.
     *
     * @param prompt User message to send.
     * @return Cold [Flow] of token-delta strings. Completes when
     *   the model finishes generating.
     */
    fun send(prompt: String): Flow<String> {
        val live = engine ?: return flow { /* no engine — empty stream */ }
        return flow {
            val conv = live.createConversation(ConversationConfig())
            this@LiteRtGemmaInference.conversation = conv
            try {
                conv
                    .sendMessageAsync(prompt, emptyMap<String, Any>())
                    .collect { msg ->
                        val text = extractText(msg)
                        if (text.isNotEmpty()) emit(text)
                    }
            } finally {
                this@LiteRtGemmaInference.conversation = null
                try {
                    conv.close()
                } catch (_: Throwable) {
                    // best-effort close
                }
            }
        }.onCompletion { cause ->
            if (cause is CancellationException) {
                cancelInFlight()
            }
        }
    }

    /**
     * Cancels any in-flight [send] on the active conversation.
     * Causes the Flow returned by [send] to terminate.
     */
    fun cancelInFlight() {
        conversation?.cancelProcess()
    }

    /**
     * Creates a fresh, stateless [Conversation] against the loaded
     * engine. The caller owns the lifecycle — `close()` it when the
     * single request completes. Used by the HTTP server to handle
     * OpenAI-shaped requests, which carry their own message history
     * + tool definitions per call rather than relying on
     * server-retained state.
     *
     * Returns `null` when the engine isn't loaded.
     *
     * @param initialMessages Conversation history to prefill, in
     *   chronological order. Roles must already be mapped to the
     *   LiteRT-LM `Role` enum (`USER` / `MODEL` / `SYSTEM` / `TOOL`).
     * @param tools Tool providers the model may call. When non-empty,
     *   `automaticToolCalling` is left disabled so the caller
     *   surfaces `Message.toolCalls` back to its HTTP client instead
     *   of executing them in-process.
     */
    fun createStatelessConversation(
        initialMessages: List<Message> = emptyList(),
        tools: List<ToolProvider> = emptyList(),
    ): Conversation? {
        val live = engine ?: return null
        return live.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(""),
                initialMessages = initialMessages,
                tools = tools,
                // Disable in-process tool execution. The default is
                // true, which makes LiteRT-LM intercept emitted tool
                // calls and dispatch them through our DaemonProxyTool
                // — which deliberately throws. The exception gets
                // swallowed, an error stub gets injected as the
                // "tool result", LiteRT-LM re-prefills, and the
                // model's next decode pass produces empty output.
                // Symptom: `provider responded tool_calls=0 text_len=0`
                // with two RunPrefillAsync cycles per request.
                //
                // With automaticToolCalling=false, LiteRT-LM returns
                // the tool calls on the Message and our HTTP server
                // emits them as `OpenAiResponseEvent.ToolCallsEmitted`
                // for the daemon's agent loop to dispatch through
                // the real Tool trait registry.
                automaticToolCalling = false,
            ),
        )
    }

    /**
     * Re-creates the [com.google.ai.edge.litertlm.Conversation] so
     * the next [send] starts a fresh dialog with no turn history.
     * Engine stays warm — cheap operation.
     */
    @Suppress("TooGenericExceptionCaught")
    fun resetConversation() {
        val live = engine ?: return
        try {
            conversation?.close()
        } catch (_: Throwable) {
            // Best-effort close; we're recreating anyway.
        }
        conversation = live.createConversation(ConversationConfig())
    }

    /**
     * Releases the model's GPU memory and tears down the LiteRT-LM
     * engine. Safe to call repeatedly. After unload, [isLoaded] is
     * false and [send] will fail until the next [load].
     */
    @Suppress("TooGenericExceptionCaught")
    fun unload() {
        try {
            conversation?.close()
        } catch (_: Throwable) {
            // Best-effort close; the engine close that follows will
            // still release native resources.
        }
        conversation = null
        try {
            engine?.close()
        } catch (_: Throwable) {
            // Best-effort close; nothing meaningful to do on failure.
        }
        engine = null
    }

    /**
     * Walks a streaming response [Message] and concatenates every
     * [Content.Text] part it carries. Non-text parts (image / audio
     * / tool-response) are ignored — the on-device large model is a
     * pure text dialog in this slice.
     */
    private fun extractText(message: Message): String {
        val builder = StringBuilder()
        for (part in message.contents.contents) {
            if (part is Content.Text) builder.append(part.text)
        }
        return builder.toString()
    }

    /** Constants for [LiteRtGemmaInference]. */
    companion object {
        /**
         * Default max input/output token cap passed to LiteRT-LM's
         * `EngineConfig.maxNumTokens`. The SDK falls back to 4096
         * when `null` is supplied — too small for any modern agent
         * loop that includes memory + system prompts + tool defs.
         *
         * 32K matches the Gemma 4 E2B / E4B family's native context
         * window per the `litert-community` model cards. Larger
         * variants (e.g. Phi-4-mini at 128K) should pass an explicit
         * `maxNumTokens` override.
         */
        const val DEFAULT_MAX_TOKENS: Int = 32_000
    }
}
