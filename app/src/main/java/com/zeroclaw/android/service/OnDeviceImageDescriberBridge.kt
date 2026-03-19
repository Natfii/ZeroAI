/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

@file:Suppress("SwallowedException")

package com.zeroclaw.android.service

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.common.StreamingCallback
import com.google.mlkit.genai.imagedescription.ImageDescriber
import com.google.mlkit.genai.imagedescription.ImageDescriberOptions
import com.google.mlkit.genai.imagedescription.ImageDescription
import com.google.mlkit.genai.imagedescription.ImageDescriptionRequest
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
 * Bridge for on-device image description via the ML Kit GenAI Image Description API.
 *
 * Wraps [ImageDescriber] in a coroutine-safe API dispatched to
 * [Dispatchers.Default] (CPU/TPU-bound work). All public methods are safe
 * to call from the main thread.
 *
 * On devices running below API 31, all methods return appropriate failure
 * states because the AI Core system service requires API 31+.
 *
 * @param context Application [Context] for ML Kit client initialization.
 * @param inferenceDispatcher Dispatcher for inference calls. Defaults to
 *   [Dispatchers.Default] since on-device inference is CPU/TPU-bound.
 */
class OnDeviceImageDescriberBridge(
    private val context: Context,
    private val inferenceDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    /**
     * Lazily initialized image describer client instance.
     *
     * Created on first use via [getOrCreateClient] and reused for
     * subsequent calls. Released on [close].
     */
    @Volatile
    private var client: ImageDescriber? = null

    /**
     * Checks the current availability of the on-device image description feature.
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
                val c = getOrCreateClient()
                val status =
                    awaitMlKitFuture(
                        c.checkFeatureStatus(),
                        inferenceDispatcher,
                    )
                mapMlKitFeatureStatus(status, "image description")
            } catch (e: CancellationException) {
                throw e
            } catch (e: GenAiException) {
                OnDeviceStatus.Unavailable(
                    e.message ?: "Image description is not available.",
                )
            } catch (e: Exception) {
                OnDeviceStatus.Unavailable(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Streams a text description of the given bitmap image.
     *
     * The returned [Flow] emits partial description text as the model
     * generates it. Collect the flow to build the full description
     * incrementally.
     *
     * @param bitmap The image to describe.
     * @return Cold [Flow] of description text chunks.
     */
    fun describe(bitmap: Bitmap): Flow<String> =
        callbackFlow {
            val c = getOrCreateClient()
            val request = ImageDescriptionRequest.builder(bitmap).build()
            val executor = inferenceDispatcher.asExecutor()

            val callback =
                StreamingCallback { outputText ->
                    trySend(outputText)
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
                                "On-device image description failed.",
                            ),
                        )
                    } catch (e: java.util.concurrent.CancellationException) {
                        close(CancellationException("Image description was cancelled."))
                    } catch (e: InterruptedException) {
                        close(CancellationException("Image description was interrupted."))
                    }
                },
                executor,
            )

            awaitClose { future.cancel(true) }
        }.flowOn(inferenceDispatcher)

    /**
     * Releases the on-device image describer resources.
     *
     * After calling this method, subsequent calls will re-initialize
     * the client. Safe to call multiple times.
     */
    fun close() {
        val c = client
        client = null
        c?.close()
    }

    /**
     * Gets or creates the ML Kit [ImageDescriber] instance.
     *
     * Thread-safe via [Synchronized]. Returns the cached instance or
     * creates a new one via [ImageDescription.getClient].
     *
     * @return The image describer client instance.
     */
    @Synchronized
    private fun getOrCreateClient(): ImageDescriber {
        client?.let { return it }
        val options = ImageDescriberOptions.builder(context).build()
        val c = ImageDescription.getClient(options)
        client = c
        return c
    }

    /** Constants for [OnDeviceImageDescriberBridge]. */
    companion object {
        /**
         * Minimum Android SDK version for AI Core (Android 12, API 31).
         */
        private const val BUILD_VERSION_S = 31
    }
}
