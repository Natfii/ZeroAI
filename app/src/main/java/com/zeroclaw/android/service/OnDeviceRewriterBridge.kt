/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

@file:Suppress("SwallowedException")

package com.zeroclaw.android.service

import android.content.Context
import android.os.Build
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.common.StreamingCallback
import com.google.mlkit.genai.rewriting.Rewriter
import com.google.mlkit.genai.rewriting.RewriterOptions
import com.google.mlkit.genai.rewriting.Rewriting
import com.google.mlkit.genai.rewriting.RewritingRequest
import com.zeroclaw.android.model.OnDeviceStatus
import com.zeroclaw.android.ui.screen.terminal.RewriteStyle
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
 * Bridge for on-device text rewriting via the ML Kit GenAI Rewriting API.
 *
 * Wraps [Rewriter] in a coroutine-safe API dispatched to
 * [Dispatchers.Default] (CPU/TPU-bound work). All public methods are safe
 * to call from the main thread.
 *
 * On devices running below API 31, all methods return appropriate failure
 * states because the AI Core system service requires API 31+.
 *
 * **Token budget**: The ML Kit API enforces a 256-token input cap.
 *
 * **Client recreation**: The [Rewriter] client is bound to a specific
 * [RewriterOptions.OutputType] at creation time. When the [RewriteStyle]
 * changes between calls, the client is automatically recreated.
 *
 * @param context Application [Context] for ML Kit client initialization.
 * @param inferenceDispatcher Dispatcher for inference calls. Defaults to
 *   [Dispatchers.Default] since on-device inference is CPU/TPU-bound.
 */
class OnDeviceRewriterBridge(
    private val context: Context,
    private val inferenceDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    /**
     * Lazily initialized rewriter client instance.
     *
     * Created on first use via [getOrCreateClient] and reused for subsequent
     * calls with the same output type. Released on [close] or when the
     * output type changes.
     */
    @Volatile
    private var client: Rewriter? = null

    /**
     * Tracks the output type of the current [client] so we know when to
     * recreate it.
     */
    @Volatile
    private var lastStyle: RewriteStyle? = null

    /**
     * Checks the current availability of the on-device rewriting feature.
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
                val c = getOrCreateClient(RewriteStyle.REPHRASE)
                val status =
                    awaitMlKitFuture(
                        c.checkFeatureStatus(),
                        inferenceDispatcher,
                    )
                mapMlKitFeatureStatus(status, "rewriting")
            } catch (e: CancellationException) {
                throw e
            } catch (e: GenAiException) {
                OnDeviceStatus.Unavailable(
                    e.message ?: "Rewriting is not available.",
                )
            } catch (e: Exception) {
                OnDeviceStatus.Unavailable(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Streams a rewritten version of the given text.
     *
     * The returned [Flow] emits partial rewritten text as the model
     * generates it. Collect the flow to build the full rewrite
     * incrementally.
     *
     * @param text The text to rewrite (up to 256 tokens).
     * @param style The rewriting style to apply.
     * @return Cold [Flow] of rewritten text chunks.
     */
    fun rewrite(
        text: String,
        style: RewriteStyle,
    ): Flow<String> =
        callbackFlow {
            val c = getOrCreateClient(style)
            val request = RewritingRequest.builder(text).build()
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
                                "On-device rewriting failed.",
                            ),
                        )
                    } catch (e: java.util.concurrent.CancellationException) {
                        close(CancellationException("Rewriting was cancelled."))
                    } catch (e: InterruptedException) {
                        close(CancellationException("Rewriting was interrupted."))
                    }
                },
                executor,
            )

            awaitClose { future.cancel(true) }
        }.flowOn(inferenceDispatcher)

    /**
     * Releases the on-device rewriter resources.
     *
     * After calling this method, subsequent calls will re-initialize
     * the client. Safe to call multiple times.
     */
    fun close() {
        val c = client
        client = null
        lastStyle = null
        c?.close()
    }

    /**
     * Gets or creates the ML Kit [Rewriter] instance.
     *
     * Thread-safe via [Synchronized]. If the requested [style] differs
     * from the cached client's output type, the old client is closed
     * and a new one is created.
     *
     * @param style The rewriting style to configure.
     * @return The rewriter client instance.
     */
    @Synchronized
    private fun getOrCreateClient(style: RewriteStyle): Rewriter {
        val existing = client
        if (existing != null && lastStyle == style) {
            return existing
        }
        existing?.close()

        val outputType = mapStyle(style)
        val options =
            RewriterOptions
                .builder(context)
                .setOutputType(outputType)
                .build()
        val c = Rewriting.getClient(options)
        client = c
        lastStyle = style
        return c
    }

    /**
     * Maps a [RewriteStyle] enum value to the corresponding ML Kit
     * [RewriterOptions.OutputType] integer constant.
     *
     * @param style The application-level rewrite style.
     * @return The ML Kit output type constant.
     */
    private fun mapStyle(style: RewriteStyle): Int =
        when (style) {
            RewriteStyle.ELABORATE -> RewriterOptions.OutputType.ELABORATE
            RewriteStyle.EMOJIFY -> RewriterOptions.OutputType.EMOJIFY
            RewriteStyle.SHORTEN -> RewriterOptions.OutputType.SHORTEN
            RewriteStyle.FRIENDLY -> RewriterOptions.OutputType.FRIENDLY
            RewriteStyle.PROFESSIONAL -> RewriterOptions.OutputType.PROFESSIONAL
            RewriteStyle.REPHRASE -> RewriterOptions.OutputType.REPHRASE
        }

    /** Constants for [OnDeviceRewriterBridge]. */
    companion object {
        /**
         * Minimum Android SDK version for AI Core (Android 12, API 31).
         */
        private const val BUILD_VERSION_S = 31
    }
}
