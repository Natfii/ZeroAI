/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.service

import android.os.Build
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.zeroclaw.android.model.OnDeviceStatus
import java.io.IOException
import java.util.Collections
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Bridge for on-device Gemini Nano inference via the ML Kit GenAI Prompt API.
 *
 * Wraps [GenerativeModel] in a coroutine-safe API dispatched to
 * [Dispatchers.Default] (CPU/TPU-bound work). All public methods are safe
 * to call from the main thread.
 *
 * On devices running below API 31, all methods return appropriate failure
 * states because the AI Core system service requires API 31+.
 *
 * **Token budget**: The ML Kit API enforces a 4,000-token input cap and a
 * 256-token output cap. Use [buildBudgetedPrompt] to construct prompts that
 * fit within the budget, or [countTokens] to validate manually.
 *
 * **Foreground-only**: Inference is blocked when the app is not the top
 * foreground activity. The bridge does not enforce this itself; callers
 * should only invoke inference from interactive UI paths.
 *
 * @param inferenceDispatcher Dispatcher for inference calls. Defaults to
 *   [Dispatchers.Default] since on-device inference is CPU/TPU-bound.
 */
class OnDeviceInferenceBridge(
    private val inferenceDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    /**
     * Lazily initialized generative model instance.
     *
     * Created on first use via [getOrCreateModel] and reused for subsequent
     * calls. Released on [close].
     */
    @Volatile
    private var model: GenerativeModel? = null

    /**
     * Tracks user and model messages for the current chat session.
     *
     * Each entry is a [ChatTurn] containing the role ("user" or "model")
     * and the message text. Cleared on [resetChat] or [close].
     *
     * Thread-safe via [Collections.synchronizedList] for simple
     * reads/writes. Compound operations (conditional remove, history
     * recording) are guarded by [chatMutex].
     */
    private val chatHistory: MutableList<ChatTurn> =
        Collections.synchronizedList(mutableListOf())

    /**
     * Guards compound operations on [chatHistory] that require
     * atomicity (e.g., conditional removeLast, add-after-check).
     */
    private val chatMutex = Mutex()

    /**
     * Checks the current availability of the on-device model.
     *
     * Safe to call from the main thread.
     *
     * @return Current [OnDeviceStatus] reflecting the ML Kit feature state.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun checkModelStatus(): OnDeviceStatus {
        if (Build.VERSION.SDK_INT < BUILD_VERSION_S) {
            return OnDeviceStatus.NotSupported
        }
        return withContext(inferenceDispatcher) {
            try {
                val m = getOrCreateModel()
                val status = m.checkStatus()
                when (status) {
                    FeatureStatus.AVAILABLE -> OnDeviceStatus.Available
                    FeatureStatus.DOWNLOADABLE -> OnDeviceStatus.Downloadable
                    FeatureStatus.DOWNLOADING -> OnDeviceStatus.Downloading(-1L)
                    else ->
                        OnDeviceStatus.Unavailable(
                            "On-device AI is not available on this device.",
                        )
                }
            } catch (e: GenAiException) {
                OnDeviceStatus.Unavailable(describeError(e))
            } catch (e: Exception) {
                OnDeviceStatus.Unavailable(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Starts downloading the model, emitting status updates as a [Flow].
     *
     * Collect the returned flow to observe download progress. The flow
     * completes with [OnDeviceStatus.Available] on success or
     * [OnDeviceStatus.Unavailable] on failure.
     *
     * @return Cold [Flow] of [OnDeviceStatus] updates.
     * @throws UnsupportedOperationException if the device is below API 31.
     */
    @Suppress("TooGenericExceptionCaught")
    fun downloadModel(): Flow<OnDeviceStatus> =
        flow {
            if (Build.VERSION.SDK_INT < BUILD_VERSION_S) {
                emit(OnDeviceStatus.NotSupported)
                return@flow
            }
            val m =
                try {
                    getOrCreateModel()
                } catch (e: GenAiException) {
                    emit(OnDeviceStatus.Unavailable(describeError(e)))
                    return@flow
                } catch (e: Exception) {
                    emit(
                        OnDeviceStatus.Unavailable(
                            e.message ?: "Failed to initialize model",
                        ),
                    )
                    return@flow
                }
            var totalBytes = -1L
            m.download().collect { status ->
                when (status) {
                    is DownloadStatus.DownloadStarted -> {
                        totalBytes = status.bytesToDownload
                        emit(OnDeviceStatus.Downloading(0L, totalBytes))
                    }
                    is DownloadStatus.DownloadProgress ->
                        emit(
                            OnDeviceStatus.Downloading(
                                status.totalBytesDownloaded,
                                totalBytes,
                            ),
                        )
                    DownloadStatus.DownloadCompleted ->
                        emit(OnDeviceStatus.Available)
                    is DownloadStatus.DownloadFailed ->
                        emit(
                            OnDeviceStatus.Unavailable(
                                status.e.message ?: "Download failed",
                            ),
                        )
                }
            }
        }.flowOn(inferenceDispatcher)

    /**
     * Streams text generation, yielding text chunks as they arrive.
     *
     * The returned [Flow] emits partial text strings from the model.
     * Collect it to build the full response incrementally.
     *
     * @param prompt The complete prompt to send (use [buildBudgetedPrompt]
     *   to construct one that fits within the token budget).
     * @return Cold [Flow] of text chunks.
     */
    fun generateStream(prompt: String): Flow<String> =
        flow {
            val m = getOrCreateModel()
            m.generateContentStream(prompt).collect { chunk ->
                chunk.candidates.firstOrNull()?.text?.let { text ->
                    if (text.isNotEmpty()) emit(text)
                }
            }
        }.catch { e ->
            throw wrapException(e)
        }.flowOn(inferenceDispatcher)

    /**
     * Generates a text response from the on-device model (non-streaming).
     *
     * Safe to call from the main thread; inference is dispatched to
     * [inferenceDispatcher].
     *
     * @param prompt The text prompt to send to the model.
     * @return [Result.success] with the generated text, or [Result.failure]
     *   with a descriptive exception.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun generateText(prompt: String): Result<String> {
        if (Build.VERSION.SDK_INT < BUILD_VERSION_S) {
            return Result.failure(
                UnsupportedOperationException(
                    "On-device AI requires Android 12 (API 31) or higher.",
                ),
            )
        }
        return withContext(inferenceDispatcher) {
            try {
                val m = getOrCreateModel()
                val response = m.generateContent(prompt)
                val text = response.candidates.firstOrNull()?.text
                if (!text.isNullOrBlank()) {
                    Result.success(text)
                } else {
                    Result.failure(
                        IllegalStateException("Model returned an empty response."),
                    )
                }
            } catch (e: Exception) {
                Result.failure(wrapException(e))
            }
        }
    }

    /**
     * Counts the number of tokens in the given text.
     *
     * Useful for checking whether a prompt fits within the model's
     * input token budget before sending it.
     *
     * @param text The text to tokenize.
     * @return [Result.success] with the token count, or [Result.failure].
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun countTokens(text: String): Result<Int> =
        withContext(inferenceDispatcher) {
            try {
                val m = getOrCreateModel()
                val request = generateContentRequest(TextPart(text)) {}
                val response = m.countTokens(request)
                Result.success(response.totalTokens)
            } catch (e: Exception) {
                Result.failure(wrapException(e))
            }
        }

    /**
     * Warms up the model for faster first inference.
     *
     * Call this after the model status is [OnDeviceStatus.Available] to
     * reduce latency on the first [generateText] or [generateStream] call.
     *
     * @throws GenAiException if warmup fails.
     */
    suspend fun warmup() {
        if (Build.VERSION.SDK_INT < BUILD_VERSION_S) return
        withContext(inferenceDispatcher) {
            val m = getOrCreateModel()
            m.warmup()
        }
    }

    /**
     * Builds a prompt that fits within the on-device model's token budget.
     *
     * Prepends a concise task prefix, includes as many recent context
     * messages as will fit (newest kept, oldest truncated), and appends
     * the user's query. Uses character-based estimation (~3.5 chars/token)
     * for speed; no ML Kit calls are made.
     *
     * @param userQuery The user's raw query text.
     * @param recentContext Recent conversation messages, oldest first.
     * @return [Result.success] with the budgeted prompt string, or
     *   [Result.failure] if the query alone exceeds the budget.
     */
    fun buildBudgetedPrompt(
        userQuery: String,
        recentContext: List<String> = emptyList(),
    ): Result<String> {
        val charBudget = (TOKEN_BUDGET * CHARS_PER_TOKEN).toInt()
        val queryWithPrefix = TASK_PREFIX + userQuery

        if (queryWithPrefix.length > charBudget) {
            return Result.failure(
                IllegalArgumentException(
                    "Query exceeds on-device inference capacity.",
                ),
            )
        }

        if (recentContext.isEmpty()) {
            return Result.success(queryWithPrefix)
        }

        val included = mutableListOf<String>()
        for (msg in recentContext.asReversed()) {
            val candidate =
                TASK_PREFIX +
                    (listOf(msg) + included).joinToString("\n") +
                    "\n\n" + userQuery
            if (candidate.length > charBudget) break
            included.add(0, msg)
        }

        val prompt =
            if (included.isEmpty()) {
                queryWithPrefix
            } else {
                TASK_PREFIX + included.joinToString("\n") + "\n\n" + userQuery
            }

        return Result.success(prompt)
    }

    /**
     * Sends a message through the multi-turn chat session, streaming the
     * response as partial text chunks.
     *
     * Maintains conversation context by formatting the full [chatHistory]
     * into a single prompt with turn markers. The ML Kit Prompt API does
     * not provide a native chat abstraction, so history is managed
     * manually and serialized into the prompt text.
     *
     * Both the user message and the assembled model response are appended
     * to [chatHistory] once the stream completes. If the stream errors
     * partway through, the partial model output is discarded and the
     * user turn is removed from history to avoid poisoning future context.
     *
     * Safe to call from the main thread; inference is dispatched to
     * [inferenceDispatcher].
     *
     * @param message The user's message text.
     * @return Cold [Flow] of text chunks forming the model's reply.
     */
    @Suppress("TooGenericExceptionCaught")
    fun sendChatMessage(message: String): Flow<String> =
        flow {
            val m = getOrCreateModel()
            val userTurn = ChatTurn(ROLE_USER, message)
            chatMutex.withLock { addTurnCapped(userTurn) }

            val prompt = chatMutex.withLock { formatChatPrompt(chatHistory) }
            val modelChunks = StringBuilder()

            m.generateContentStream(prompt).collect { chunk ->
                chunk.candidates.firstOrNull()?.text?.let { text ->
                    if (text.isNotEmpty()) {
                        modelChunks.append(text)
                        emit(text)
                    }
                }
            }

            val modelResponse =
                modelChunks.toString().ifBlank {
                    chatMutex.withLock {
                        if (chatHistory.isNotEmpty() &&
                            chatHistory.last().role == ROLE_USER
                        ) {
                            chatHistory.removeLast()
                        }
                    }
                    error("Model returned an empty response.")
                }
            chatMutex.withLock {
                addTurnCapped(ChatTurn(ROLE_MODEL, modelResponse))
            }
        }.catch { e ->
            chatMutex.withLock {
                if (chatHistory.isNotEmpty() &&
                    chatHistory.last().role == ROLE_USER
                ) {
                    chatHistory.removeLast()
                }
            }
            throw wrapException(e)
        }.flowOn(inferenceDispatcher)

    /**
     * Sends a message through the multi-turn chat session (non-streaming).
     *
     * Like [sendChatMessage] but returns the complete response at once
     * wrapped in a [Result]. Both the user message and the model response
     * are appended to [chatHistory].
     *
     * Safe to call from the main thread; inference is dispatched to
     * [inferenceDispatcher].
     *
     * @param message The user's message text.
     * @return [Result.success] with the full response text, or
     *   [Result.failure] with a descriptive exception.
     */
    @Suppress("TooGenericExceptionCaught", "CognitiveComplexMethod")
    suspend fun sendChatMessageBlocking(message: String): Result<String> {
        if (Build.VERSION.SDK_INT < BUILD_VERSION_S) {
            return Result.failure(
                UnsupportedOperationException(
                    "On-device AI requires Android 12 (API 31) or higher.",
                ),
            )
        }
        return withContext(inferenceDispatcher) {
            try {
                val m = getOrCreateModel()
                val userTurn = ChatTurn(ROLE_USER, message)
                chatMutex.withLock { addTurnCapped(userTurn) }

                val prompt =
                    chatMutex.withLock {
                        formatChatPrompt(chatHistory)
                    }
                val response = m.generateContent(prompt)
                val text = response.candidates.firstOrNull()?.text

                if (!text.isNullOrBlank()) {
                    chatMutex.withLock {
                        addTurnCapped(ChatTurn(ROLE_MODEL, text))
                    }
                    Result.success(text)
                } else {
                    chatMutex.withLock {
                        if (chatHistory.isNotEmpty() &&
                            chatHistory.last().role == ROLE_USER
                        ) {
                            chatHistory.removeLast()
                        }
                    }
                    Result.failure(
                        IllegalStateException(
                            "Model returned an empty response.",
                        ),
                    )
                }
            } catch (e: Exception) {
                chatMutex.withLock {
                    if (chatHistory.isNotEmpty() &&
                        chatHistory.last().role == ROLE_USER
                    ) {
                        chatHistory.removeLast()
                    }
                }
                Result.failure(wrapException(e))
            }
        }
    }

    /**
     * Resets the multi-turn chat session.
     *
     * Clears [chatHistory] so the next [sendChatMessage] or
     * [sendChatMessageBlocking] call starts a fresh conversation.
     */
    fun resetChat() {
        synchronized(chatHistory) { chatHistory.clear() }
    }

    /**
     * Read-only snapshot of the current conversation history.
     *
     * Returns a list of [ChatTurn] entries in chronological order.
     * Modifications to the returned list do not affect the internal state.
     */
    val conversationHistory: List<ChatTurn>
        get() = synchronized(chatHistory) { chatHistory.toList() }

    /**
     * Number of turns (user + model messages) in the current chat session.
     */
    val chatTurnCount: Int
        get() = chatHistory.size

    /**
     * Whether a multi-turn chat session is currently active.
     *
     * Returns `true` if [chatHistory] is non-empty.
     */
    val isChatActive: Boolean
        get() = chatHistory.isNotEmpty()

    /**
     * Releases the on-device model resources and resets the chat session.
     *
     * After calling this method, subsequent calls will re-initialize
     * the model. Safe to call multiple times.
     */
    fun close() {
        resetChat()
        val m = model
        model = null
        m?.close()
    }

    /**
     * Appends a turn to [chatHistory], evicting the oldest entry when
     * the history exceeds [MAX_HISTORY_SIZE].
     *
     * Must be called while holding [chatMutex].
     *
     * @param turn The [ChatTurn] to append.
     */
    private fun addTurnCapped(turn: ChatTurn) {
        if (chatHistory.size >= MAX_HISTORY_SIZE) {
            chatHistory.removeFirst()
        }
        chatHistory.add(turn)
    }

    /**
     * Gets or creates the ML Kit [GenerativeModel] instance.
     *
     * Thread-safe via [Synchronized]. Returns the cached instance or
     * creates a new one via [Generation.getClient].
     *
     * @return The model instance.
     */
    @Synchronized
    private fun getOrCreateModel(): GenerativeModel {
        model?.let { return it }
        val m = Generation.getClient()
        model = m
        return m
    }

    /**
     * Wraps a throwable in a descriptive exception for the caller.
     *
     * Maps [GenAiException] error codes to user-friendly messages.
     *
     * @param e The original throwable.
     * @return A wrapped exception with a descriptive message.
     */
    private fun wrapException(e: Throwable): Exception {
        if (e is GenAiException) {
            val message = describeError(e)
            return when (e.errorCode) {
                GenAiException.ErrorCode.REQUEST_TOO_LARGE,
                GenAiException.ErrorCode.REQUEST_TOO_SMALL,
                GenAiException.ErrorCode.REQUEST_PROCESSING_ERROR,
                GenAiException.ErrorCode.INVALID_INPUT_IMAGE,
                ->
                    IllegalArgumentException(message, e)

                GenAiException.ErrorCode.NOT_AVAILABLE,
                GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE,
                GenAiException.ErrorCode.AICORE_INCOMPATIBLE,
                ->
                    UnsupportedOperationException(message, e)

                GenAiException.ErrorCode.NOT_ENOUGH_DISK_SPACE ->
                    IOException(message, e)

                else -> IllegalStateException(message, e)
            }
        }
        return if (e is Exception) e else RuntimeException(e)
    }

    /**
     * Returns a user-facing description for a [GenAiException] error code.
     *
     * @param e The [GenAiException] to describe.
     * @return A concise, user-readable error message.
     */
    @Suppress("CyclomaticComplexMethod")
    private fun describeError(e: GenAiException): String =
        when (e.errorCode) {
            GenAiException.ErrorCode.BUSY ->
                "On-device model is busy. Try again shortly."
            GenAiException.ErrorCode.CANCELLED ->
                "Request was cancelled."
            GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE ->
                "A system update is required for on-device AI."
            GenAiException.ErrorCode.NOT_AVAILABLE ->
                "On-device AI is not available on this device."
            GenAiException.ErrorCode.NOT_ENOUGH_DISK_SPACE ->
                "Not enough storage space for the AI model."
            GenAiException.ErrorCode.REQUEST_PROCESSING_ERROR ->
                "Request did not pass safety checks."
            GenAiException.ErrorCode.REQUEST_TOO_LARGE ->
                "Input exceeds the on-device token limit (4,000 tokens)."
            GenAiException.ErrorCode.RESPONSE_GENERATION_ERROR ->
                "Model could not generate a response."
            GenAiException.ErrorCode.RESPONSE_PROCESSING_ERROR ->
                "Generated response did not pass safety checks."
            GenAiException.ErrorCode.REQUEST_TOO_SMALL ->
                "Input is too short for on-device inference."
            GenAiException.ErrorCode.AICORE_INCOMPATIBLE ->
                "AI Core is not installed or is outdated."
            GenAiException.ErrorCode.INVALID_INPUT_IMAGE ->
                "Invalid image format."
            GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED ->
                "Daily battery quota for on-device AI exceeded."
            GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED ->
                "On-device AI requires the app to be in the foreground."
            GenAiException.ErrorCode.CACHE_PROCESSING_ERROR ->
                "Cache processing error."
            else ->
                e.message ?: "On-device inference failed."
        }

    /**
     * Formats the conversation history into a single prompt string.
     *
     * Uses simple turn markers (`User:` / `Model:`) to delineate
     * messages. The model sees the full history and is expected to
     * continue the conversation. Oldest turns are truncated if the
     * total exceeds the character budget.
     *
     * @param history The list of [ChatTurn] entries to format.
     * @return A single prompt string with all turns concatenated.
     */
    private fun formatChatPrompt(history: List<ChatTurn>): String {
        val charBudget = (TOKEN_BUDGET * CHARS_PER_TOKEN).toInt()
        val prefix = CHAT_SYSTEM_PREFIX

        val included = mutableListOf<String>()
        for (turn in history.asReversed()) {
            val line = "${turn.role}: ${turn.message}"
            val candidate =
                prefix +
                    (listOf(line) + included).joinToString("\n") +
                    "\n${ROLE_MODEL}:"
            if (candidate.length > charBudget) break
            included.add(0, line)
        }

        return if (included.isEmpty()) {
            val last = history.last()
            val roleLabel = "${last.role}: "
            val suffix = "\n${ROLE_MODEL}:"
            val available =
                charBudget - prefix.length -
                    roleLabel.length - suffix.length
            val truncatedMessage =
                if (last.message.length > available) {
                    last.message.take(available.coerceAtLeast(0))
                } else {
                    last.message
                }
            prefix + roleLabel + truncatedMessage + suffix
        } else {
            prefix + included.joinToString("\n") + "\n${ROLE_MODEL}:"
        }
    }

    /** Constants for [OnDeviceInferenceBridge]. */
    companion object {
        /**
         * Minimum Android SDK version for AI Core (Android 12, API 31).
         */
        private const val BUILD_VERSION_S = 31

        /**
         * Maximum number of [ChatTurn] entries retained in memory.
         *
         * When this limit is exceeded, the oldest turn is evicted.
         */
        private const val MAX_HISTORY_SIZE = 100

        /**
         * Usable input token budget after reserving for output and safety.
         *
         * Calculated as: 4,000 (ML Kit cap) - 256 (output) - 144 (safety).
         */
        private const val TOKEN_BUDGET = 3600

        /**
         * Approximate characters per token for English text.
         *
         * Conservative estimate; actual ratio varies by content.
         */
        private const val CHARS_PER_TOKEN = 3.5f

        /**
         * Minimal task prefix prepended to all on-device prompts.
         *
         * Kept short (~10 tokens) to maximize budget for user content.
         */
        private const val TASK_PREFIX = "Answer concisely.\n\n"

        /**
         * System prefix for multi-turn chat prompts.
         *
         * Provides minimal instruction to the model about the
         * conversation format. Kept short to preserve token budget.
         */
        private const val CHAT_SYSTEM_PREFIX =
            "Continue this conversation. Answer concisely.\n\n"

        /** Role label for user turns in formatted chat prompts. */
        private const val ROLE_USER = "User"

        /** Role label for model turns in formatted chat prompts. */
        private const val ROLE_MODEL = "Model"
    }
}

/**
 * A single turn in a multi-turn chat conversation.
 *
 * @property role The speaker role, either "User" or "Model".
 * @property message The text content of this turn.
 */
data class ChatTurn(
    val role: String,
    val message: String,
)
