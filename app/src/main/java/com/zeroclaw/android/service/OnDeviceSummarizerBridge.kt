/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

@file:Suppress("SwallowedException")

package com.zeroclaw.android.service

import android.content.Context
import android.os.Build
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.common.StreamingCallback
import com.google.mlkit.genai.summarization.Summarization
import com.google.mlkit.genai.summarization.SummarizationRequest
import com.google.mlkit.genai.summarization.Summarizer
import com.google.mlkit.genai.summarization.SummarizerOptions
import com.zeroclaw.android.model.OnDeviceStatus
import java.util.concurrent.ExecutionException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * Bridge for on-device text summarization via the ML Kit GenAI Summarization API.
 *
 * Wraps [Summarizer] in a coroutine-safe API dispatched to
 * [Dispatchers.Default] (CPU/TPU-bound work). All public methods are safe
 * to call from the main thread.
 *
 * On devices running below API 31, all methods return appropriate failure
 * states because the AI Core system service requires API 31+.
 *
 * **Token budget**: The ML Kit API enforces a 4,000-token input cap.
 * Auto-truncation is enabled via [SummarizerOptions.Builder.setLongInputAutoTruncationEnabled].
 *
 * **Client recreation**: The [Summarizer] client is bound to a specific
 * [SummarizerOptions.InputType] at creation time. When [isConversation]
 * changes between calls, the client is automatically recreated.
 *
 * @param context Application [Context] for ML Kit client initialization.
 * @param inferenceDispatcher Dispatcher for inference calls. Defaults to
 *   [Dispatchers.Default] since on-device inference is CPU/TPU-bound.
 */
class OnDeviceSummarizerBridge(
    private val context: Context,
    private val inferenceDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    /**
     * Lazily initialized summarizer client instance.
     *
     * Created on first use via [getOrCreateClient] and reused for subsequent
     * calls with the same input type. Released on [close] or when the input
     * type changes.
     */
    @Volatile
    private var client: Summarizer? = null

    /**
     * Tracks the input type of the current [client] so we know when to
     * recreate it.
     */
    @Volatile
    private var lastIsConversation: Boolean? = null

    /**
     * Checks the current availability of the on-device summarization feature.
     *
     * Safe to call from the main thread.
     *
     * @return Current [OnDeviceStatus] reflecting the ML Kit feature state.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun checkFeatureStatus(): OnDeviceStatus {
        if (Build.VERSION.SDK_INT < BUILD_VERSION_S) {
            return OnDeviceStatus.NotSupported
        }
        return withContext(inferenceDispatcher) {
            try {
                val c = getOrCreateClient(isConversation = false)
                val status =
                    awaitMlKitFuture(
                        c.checkFeatureStatus(),
                        inferenceDispatcher,
                    )
                mapMlKitFeatureStatus(status, "summarization")
            } catch (e: CancellationException) {
                throw e
            } catch (e: GenAiException) {
                OnDeviceStatus.Unavailable(
                    e.message ?: "Summarization is not available.",
                )
            } catch (e: Exception) {
                OnDeviceStatus.Unavailable(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Streams a summary of the given text.
     *
     * The returned [Flow] emits partial summary text as the model generates
     * it. Collect the flow to build the full summary incrementally.
     *
     * @param text The text to summarize (up to 4,000 tokens).
     * @param isConversation Whether the input is a conversation transcript.
     *   When `true`, the ML Kit client uses [SummarizerOptions.InputType.CONVERSATION];
     *   when `false`, it uses [SummarizerOptions.InputType.ARTICLE].
     * @return Cold [Flow] of summary text chunks.
     */
    fun summarize(
        text: String,
        isConversation: Boolean = false,
    ): Flow<String> =
        callbackFlow {
            require(text.length <= MAX_INPUT_CHARS) {
                "Input exceeds the $MAX_INPUT_CHARS-character limit for " +
                    "on-device summarization."
            }
            val c = getOrCreateClient(isConversation)
            val request = SummarizationRequest.builder(text).build()
            val executor = inferenceDispatcher.asExecutor()

            val callback =
                StreamingCallback { newText ->
                    trySend(newText)
                }

            val future = c.runInference(request, callback)

            future.addListener(
                {
                    try {
                        future.get()
                        close()
                    } catch (e: ExecutionException) {
                        close(
                            wrapMlKitException(
                                e.cause ?: e,
                                "On-device summarization failed.",
                            ),
                        )
                    } catch (e: java.util.concurrent.CancellationException) {
                        close(CancellationException("Summarization was cancelled."))
                    } catch (e: InterruptedException) {
                        close(CancellationException("Summarization was interrupted."))
                    }
                },
                executor,
            )

            awaitClose { future.cancel(true) }
        }.flowOn(inferenceDispatcher)

    /**
     * Releases the on-device summarizer resources.
     *
     * After calling this method, subsequent calls will re-initialize
     * the client. Safe to call multiple times.
     */
    fun close() {
        val c = client
        client = null
        lastIsConversation = null
        c?.close()
    }

    /**
     * Gets or creates the ML Kit [Summarizer] instance.
     *
     * Thread-safe via [Synchronized]. If the requested [isConversation]
     * flag differs from the cached client's input type, the old client
     * is closed and a new one is created.
     *
     * @param isConversation Whether to configure for conversation input.
     * @return The summarizer client instance.
     */
    @Synchronized
    private fun getOrCreateClient(isConversation: Boolean): Summarizer {
        val existing = client
        if (existing != null && lastIsConversation == isConversation) {
            return existing
        }
        existing?.close()

        val inputType =
            if (isConversation) {
                SummarizerOptions.InputType.CONVERSATION
            } else {
                SummarizerOptions.InputType.ARTICLE
            }

        val options =
            SummarizerOptions
                .builder(context)
                .setInputType(inputType)
                .setOutputType(SummarizerOptions.OutputType.THREE_BULLETS)
                .setLongInputAutoTruncationEnabled(true)
                .build()
        val c = Summarization.getClient(options)
        client = c
        lastIsConversation = isConversation
        return c
    }

    /** Constants for [OnDeviceSummarizerBridge]. */
    companion object {
        /**
         * Minimum Android SDK version for AI Core (Android 12, API 31).
         */
        private const val BUILD_VERSION_S = 31

        /**
         * Maximum number of characters accepted before calling ML Kit.
         *
         * Approximates the 4,000-token ML Kit input cap using a
         * conservative 1:1 character-to-token ratio.
         */
        private const val MAX_INPUT_CHARS = 4_000
    }
}
