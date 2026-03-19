/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.service

import android.content.Context
import android.os.Build
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.proofreading.Proofreader
import com.google.mlkit.genai.proofreading.ProofreaderOptions
import com.google.mlkit.genai.proofreading.Proofreading
import com.google.mlkit.genai.proofreading.ProofreadingRequest
import com.zeroclaw.android.model.OnDeviceStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Bridge for on-device text proofreading via the ML Kit GenAI Proofreading API.
 *
 * Wraps [Proofreader] in a coroutine-safe API dispatched to
 * [Dispatchers.Default] (CPU/TPU-bound work). All public methods are safe
 * to call from the main thread.
 *
 * On devices running below API 31, all methods return appropriate failure
 * states because the AI Core system service requires API 31+.
 *
 * **Token budget**: The ML Kit API enforces a 256-token input cap.
 *
 * **Client recreation**: The [Proofreader] client is bound to a specific
 * [ProofreaderOptions.InputType] at creation time. When [isVoiceInput]
 * changes between calls, the client is automatically recreated.
 *
 * @param context Application [Context] for ML Kit client initialization.
 * @param inferenceDispatcher Dispatcher for inference calls. Defaults to
 *   [Dispatchers.Default] since on-device inference is CPU/TPU-bound.
 */
class OnDeviceProofreaderBridge(
    private val context: Context,
    private val inferenceDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    /**
     * Lazily initialized proofreader client instance.
     *
     * Created on first use via [getOrCreateClient] and reused for subsequent
     * calls with the same input type. Released on [close] or when the input
     * type changes.
     */
    @Volatile
    private var client: Proofreader? = null

    /**
     * Tracks the input type of the current [client] so we know when to
     * recreate it.
     */
    @Volatile
    private var lastIsVoiceInput: Boolean? = null

    /**
     * Checks the current availability of the on-device proofreading feature.
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
                val c = getOrCreateClient(isVoiceInput = false)
                val status =
                    awaitMlKitFuture(
                        c.checkFeatureStatus(),
                        inferenceDispatcher,
                    )
                mapMlKitFeatureStatus(status, "proofreading")
            } catch (e: CancellationException) {
                throw e
            } catch (e: GenAiException) {
                OnDeviceStatus.Unavailable(
                    e.message ?: "Proofreading is not available.",
                )
            } catch (e: Exception) {
                OnDeviceStatus.Unavailable(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Proofreads the given text and returns the top suggestion.
     *
     * ML Kit may return multiple [com.google.mlkit.genai.proofreading.ProofreadingSuggestion]
     * instances sorted by descending confidence; this method returns only
     * the first (highest-confidence) result.
     *
     * @param text The text to proofread (up to 256 tokens).
     * @param isVoiceInput Whether the text came from voice input.
     *   When `true`, the ML Kit client uses [ProofreaderOptions.InputType.VOICE];
     *   when `false`, it uses [ProofreaderOptions.InputType.KEYBOARD].
     * @return [Result.success] with the proofread text, or [Result.failure]
     *   with a descriptive exception.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun proofread(
        text: String,
        isVoiceInput: Boolean = false,
    ): Result<String> {
        if (Build.VERSION.SDK_INT < BUILD_VERSION_S) {
            return Result.failure(
                UnsupportedOperationException(
                    "On-device AI requires Android 12 (API 31) or higher.",
                ),
            )
        }
        return withContext(inferenceDispatcher) {
            try {
                require(text.length <= MAX_INPUT_CHARS) {
                    "Input exceeds the $MAX_INPUT_CHARS-character " +
                        "limit for on-device proofreading."
                }
                val c = getOrCreateClient(isVoiceInput)
                val request = ProofreadingRequest.builder(text).build()
                val response =
                    awaitMlKitFuture(
                        c.runInference(request),
                        inferenceDispatcher,
                    )

                val suggestions = response.results
                if (suggestions.isNotEmpty()) {
                    Result.success(suggestions.first().text)
                } else {
                    Result.failure(
                        IllegalStateException(
                            "Proofreader returned no suggestions.",
                        ),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(
                    wrapMlKitException(
                        e,
                        "On-device proofreading failed.",
                    ),
                )
            }
        }
    }

    /**
     * Releases the on-device proofreader resources.
     *
     * After calling this method, subsequent calls will re-initialize
     * the client. Safe to call multiple times.
     */
    fun close() {
        val c = client
        client = null
        lastIsVoiceInput = null
        c?.close()
    }

    /**
     * Gets or creates the ML Kit [Proofreader] instance.
     *
     * Thread-safe via [Synchronized]. If the requested [isVoiceInput]
     * flag differs from the cached client's input type, the old client
     * is closed and a new one is created.
     *
     * @param isVoiceInput Whether to configure for voice input.
     * @return The proofreader client instance.
     */
    @Synchronized
    private fun getOrCreateClient(isVoiceInput: Boolean): Proofreader {
        val existing = client
        if (existing != null && lastIsVoiceInput == isVoiceInput) {
            return existing
        }
        existing?.close()

        val inputType =
            if (isVoiceInput) {
                ProofreaderOptions.InputType.VOICE
            } else {
                ProofreaderOptions.InputType.KEYBOARD
            }

        val options =
            ProofreaderOptions
                .builder(context)
                .setInputType(inputType)
                .build()
        val c = Proofreading.getClient(options)
        client = c
        lastIsVoiceInput = isVoiceInput
        return c
    }

    /** Constants for [OnDeviceProofreaderBridge]. */
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
