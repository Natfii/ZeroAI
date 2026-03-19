/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

@file:Suppress("SwallowedException")

package com.zeroclaw.android.service

import com.google.common.util.concurrent.ListenableFuture
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.zeroclaw.android.model.OnDeviceStatus
import java.io.IOException
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Awaits the result of a Guava [ListenableFuture] as a suspend function.
 *
 * Converts the callback-based [ListenableFuture] to a coroutine-friendly
 * suspend call using [suspendCancellableCoroutine]. Cancellation of the
 * coroutine propagates to the underlying future.
 *
 * @param T The result type.
 * @param future The future to await.
 * @param dispatcher The [CoroutineDispatcher] whose executor runs the
 *   future's completion listener.
 * @return The resolved value.
 */
internal suspend fun <T> awaitMlKitFuture(
    future: ListenableFuture<T>,
    dispatcher: CoroutineDispatcher,
): T =
    suspendCancellableCoroutine { cont ->
        future.addListener(
            {
                try {
                    cont.resume(future.get())
                } catch (e: ExecutionException) {
                    cont.resumeWithException(e.cause ?: e)
                } catch (e: java.util.concurrent.CancellationException) {
                    cont.cancel()
                } catch (e: InterruptedException) {
                    cont.cancel()
                }
            },
            dispatcher.asExecutor(),
        )
        cont.invokeOnCancellation { future.cancel(true) }
    }

/**
 * Maps a [FeatureStatus] integer constant to [OnDeviceStatus].
 *
 * @param status The feature status constant from ML Kit.
 * @param featureName Human-readable feature name used in the unavailable
 *   message (e.g., "summarization", "proofreading").
 * @return Corresponding [OnDeviceStatus].
 */
internal fun mapMlKitFeatureStatus(
    status: Int,
    featureName: String,
): OnDeviceStatus =
    when (status) {
        FeatureStatus.AVAILABLE -> OnDeviceStatus.Available
        FeatureStatus.DOWNLOADABLE -> OnDeviceStatus.Downloadable
        FeatureStatus.DOWNLOADING -> OnDeviceStatus.Downloading(-1L)
        else ->
            OnDeviceStatus.Unavailable(
                "On-device $featureName is not available on this device.",
            )
    }

/**
 * Wraps a throwable from an ML Kit operation in a descriptive exception.
 *
 * Maps [GenAiException] error codes to semantically appropriate exception
 * types following the same pattern as [OnDeviceInferenceBridge]:
 * - [GenAiException.ErrorCode.REQUEST_TOO_LARGE],
 *   [GenAiException.ErrorCode.REQUEST_TOO_SMALL],
 *   [GenAiException.ErrorCode.REQUEST_PROCESSING_ERROR],
 *   [GenAiException.ErrorCode.INVALID_INPUT_IMAGE]
 *   -> [IllegalArgumentException]
 * - [GenAiException.ErrorCode.NOT_AVAILABLE],
 *   [GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE],
 *   [GenAiException.ErrorCode.AICORE_INCOMPATIBLE]
 *   -> [UnsupportedOperationException]
 * - [GenAiException.ErrorCode.NOT_ENOUGH_DISK_SPACE] -> [IOException]
 * - All other codes -> [IllegalStateException]
 *
 * Non-[GenAiException] throwables are returned as-is when they are
 * already [Exception] instances, or wrapped in [RuntimeException].
 *
 * @param e The original throwable.
 * @param fallbackMessage Message used when the exception has no message.
 * @return A wrapped exception with a descriptive message.
 */
internal fun wrapMlKitException(
    e: Throwable,
    fallbackMessage: String = "On-device inference failed.",
): Exception {
    if (e is GenAiException) {
        val message = e.message ?: fallbackMessage
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
