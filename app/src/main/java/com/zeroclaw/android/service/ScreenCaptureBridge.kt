/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

@file:Suppress("SwallowedException")

package com.zeroclaw.android.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.activity.result.ActivityResultLauncher
import com.zeroclaw.android.model.ProcessedImage
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Manages [MediaProjection] lifecycle for screen capture.
 *
 * Provides a suspend-based capture API that acquires a single frame from
 * the device display, downscales and JPEG-compresses it, and returns a
 * [ProcessedImage] suitable for vision model APIs.
 *
 * On Android 14+ (API 34), each [MediaProjection] use requires fresh user
 * consent. This bridge re-requests permission automatically on those
 * versions rather than reusing a stale token.
 *
 * Thread safety: the [ImageReader] callback is dispatched on a dedicated
 * [HandlerThread]. All public mutable state is exposed through [StateFlow].
 * Callers must invoke [release] when screen capture is no longer needed to
 * stop the projection and avoid battery drain.
 *
 * @param applicationContext Application-level context for system service access.
 */
class ScreenCaptureBridge(
    private val applicationContext: Context,
) {
    /**
     * Whether the bridge currently holds a valid [MediaProjection] token.
     *
     * On API 34+ this is cleared after each capture because re-consent is
     * required. On older versions the token remains valid until [release]
     * is called.
     */
    private val _hasPermission = MutableStateFlow(false)

    /** Whether the bridge holds an active [MediaProjection] token. */
    val hasPermission: StateFlow<Boolean> = _hasPermission.asStateFlow()

    /**
     * The result code returned by the system consent dialog.
     *
     * Stored alongside [permissionData] so we can create a new
     * [MediaProjection] on each capture (required on API 34+).
     */
    @Volatile
    private var permissionResultCode: Int = Activity.RESULT_CANCELED

    /**
     * The [Intent] data returned by the system consent dialog.
     *
     * Contains the opaque token used by [MediaProjectionManager.getMediaProjection].
     */
    @Volatile
    private var permissionData: Intent? = null

    /** Active [MediaProjection] instance, or null when not projecting. */
    @Volatile
    private var mediaProjection: MediaProjection? = null

    /** Dedicated [HandlerThread] for [ImageReader] callbacks. */
    private var handlerThread: HandlerThread? = null

    /** [Handler] bound to the [handlerThread]'s looper. */
    private var handler: Handler? = null

    /**
     * Launches the system screen capture consent dialog.
     *
     * The caller must register the [launcher] with
     * [ActivityResultContracts.StartActivityForResult][androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult]
     * and forward the result to [handlePermissionResult].
     *
     * @param launcher An [ActivityResultLauncher] that starts the system
     *     consent activity and delivers the result.
     */
    fun requestPermission(launcher: ActivityResultLauncher<Intent>) {
        val projectionManager =
            applicationContext.getSystemService(
                Context.MEDIA_PROJECTION_SERVICE,
            ) as MediaProjectionManager
        launcher.launch(projectionManager.createScreenCaptureIntent())
    }

    /**
     * Processes the result from the system screen capture consent dialog.
     *
     * Stores the result code and data intent for later use when creating
     * a [MediaProjection]. Updates [hasPermission] based on whether the
     * user granted consent.
     *
     * @param resultCode The result code from the consent activity
     *     ([Activity.RESULT_OK] on success).
     * @param data The [Intent] containing the projection token, or null
     *     on denial.
     */
    fun handlePermissionResult(
        resultCode: Int,
        data: Intent?,
    ) {
        if (resultCode == Activity.RESULT_OK && data != null) {
            permissionResultCode = resultCode
            permissionData = Intent(data)
            _hasPermission.value = true
        } else {
            permissionResultCode = Activity.RESULT_CANCELED
            permissionData = null
            _hasPermission.value = false
        }
    }

    /**
     * Captures the current screen content as a [ProcessedImage].
     *
     * Creates a [MediaProjection] from the stored consent token, sets up
     * a [VirtualDisplay] backed by an [ImageReader] surface, captures one
     * frame, then tears down the virtual display and image reader. On
     * API versions below 34 the [MediaProjection] is kept alive for reuse;
     * on API 34+ it is released and permission is reset because re-consent
     * is required.
     *
     * The captured frame is downscaled to [MAX_DIMENSION] pixels on its
     * longest edge and JPEG-compressed at quality [JPEG_QUALITY].
     *
     * On API 31+ (Android 12), windows with `FLAG_SECURE` render as
     * black in the captured frame. This is an OS-level restriction and
     * cannot be bypassed. The resulting image may therefore contain
     * blacked-out regions for protected windows.
     *
     * The [MediaProjection] token is consumed after each capture and
     * cannot be reused. A fresh consent dialog is required for the next
     * capture.
     *
     * @param context Context for accessing display metrics.
     * @return [Result.success] with the processed screenshot, or
     *     [Result.failure] on permission, projection, or capture errors.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun captureScreen(context: Context): Result<ProcessedImage> {
        val data = permissionData
        if (permissionResultCode != Activity.RESULT_OK || data == null) {
            return Result.failure(
                IllegalStateException("Screen capture permission not granted"),
            )
        }

        val metrics = resolveDisplayMetrics(context)
        val projection =
            getOrCreateProjection(data)
                ?: return Result.failure(
                    IllegalStateException("Failed to create MediaProjection"),
                )

        return try {
            val image = captureFrame(projection, metrics)
            val bitmap = imageToBitmap(image)
            image.close()

            if (bitmap == null) {
                Result.failure(
                    IllegalStateException("Failed to decode captured frame"),
                )
            } else {
                val processed = processBitmap(bitmap)
                bitmap.recycle()
                Result.success(processed)
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            releaseProjection()
        }
    }

    /**
     * Stops the [MediaProjection] and releases all resources.
     *
     * Safe to call even when no projection is active. After this call,
     * [hasPermission] will be `false` and a new consent dialog must be
     * shown before capturing again.
     */
    fun release() {
        releaseProjection()
        stopHandlerThread()
    }

    /**
     * Returns or creates a [MediaProjection] from the stored consent data.
     *
     * On API 34+ a fresh projection is always created because the system
     * invalidates the token after each use.
     *
     * @param data The intent data from the consent dialog.
     * @return A [MediaProjection], or null if creation fails.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun getOrCreateProjection(data: Intent): MediaProjection? {
        if (Build.VERSION.SDK_INT >= BUILD_VERSION_RECONSENT) {
            releaseProjection()
        }
        mediaProjection?.let { return it }

        return try {
            val manager =
                applicationContext.getSystemService(
                    Context.MEDIA_PROJECTION_SERVICE,
                ) as MediaProjectionManager
            val projection =
                manager.getMediaProjection(
                    permissionResultCode,
                    data,
                )
            mediaProjection = projection
            projection
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Captures a single frame from the given [MediaProjection].
     *
     * Sets up an [ImageReader] and [VirtualDisplay], waits for one frame
     * via a suspending coroutine, then tears both down. The [ImageReader]
     * callback is dispatched on the dedicated [handlerThread].
     *
     * @param projection Active media projection.
     * @param metrics Display dimensions and density.
     * @return The captured [Image].
     */
    private suspend fun captureFrame(
        projection: MediaProjection,
        metrics: CaptureMetrics,
    ): Image {
        ensureHandlerThread()

        val imageReader =
            ImageReader.newInstance(
                metrics.width,
                metrics.height,
                PixelFormat.RGBA_8888,
                IMAGE_READER_MAX_IMAGES,
            )

        val virtualDisplay =
            projection.createVirtualDisplay(
                VIRTUAL_DISPLAY_NAME,
                metrics.width,
                metrics.height,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface,
                null,
                handler,
            )

        return try {
            acquireImage(imageReader)
        } finally {
            virtualDisplay.release()
            imageReader.close()
        }
    }

    /**
     * Suspends until an [Image] is available from the [ImageReader].
     *
     * Registers an [ImageReader.OnImageAvailableListener] on the dedicated
     * handler thread and resumes the coroutine when a frame arrives. If
     * the coroutine is cancelled, the listener is cleaned up.
     *
     * @param imageReader The image reader to acquire from.
     * @return The acquired [Image].
     */
    private suspend fun acquireImage(
        imageReader: ImageReader,
    ): Image =
        suspendCancellableCoroutine { continuation ->
            imageReader.setOnImageAvailableListener(
                { reader ->
                    val image = reader.acquireLatestImage()
                    if (image != null && continuation.isActive) {
                        continuation.resume(image)
                    }
                },
                handler,
            )

            continuation.invokeOnCancellation {
                imageReader.setOnImageAvailableListener(null, null)
            }
        }

    /**
     * Converts an [Image] in RGBA_8888 format to a [Bitmap].
     *
     * Handles the row stride padding that [ImageReader] may insert
     * between pixel rows. The source [Image] is not closed by this
     * method; the caller is responsible for closing it.
     *
     * @param image The RGBA_8888 image from the image reader.
     * @return A [Bitmap] copy of the image, or null if the planes are empty.
     */
    private fun imageToBitmap(image: Image): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width

        val bitmapWidth = image.width + rowPadding / pixelStride
        val bitmap =
            Bitmap.createBitmap(
                bitmapWidth,
                image.height,
                Bitmap.Config.ARGB_8888,
            )
        bitmap.copyPixelsFromBuffer(buffer)

        return if (bitmapWidth != image.width) {
            val cropped =
                Bitmap.createBitmap(
                    bitmap,
                    0,
                    0,
                    image.width,
                    image.height,
                )
            bitmap.recycle()
            cropped
        } else {
            bitmap
        }
    }

    /**
     * Downscales and JPEG-compresses a [Bitmap] into a [ProcessedImage].
     *
     * The bitmap is scaled proportionally so that the longest edge does
     * not exceed [MAX_DIMENSION] pixels. The result is base64-encoded for
     * transmission across the FFI boundary.
     *
     * @param bitmap The source bitmap to process.
     * @return A [ProcessedImage] containing the compressed screenshot.
     */
    private fun processBitmap(bitmap: Bitmap): ProcessedImage {
        val scaled = scaleIfNeeded(bitmap)
        val shouldRecycleScaled = scaled !== bitmap

        val outputStream = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)
        val compressedBytes = outputStream.toByteArray()
        val base64 = Base64.encodeToString(compressedBytes, Base64.NO_WRAP)

        val result =
            ProcessedImage(
                base64Data = base64,
                mimeType = OUTPUT_MIME,
                width = scaled.width,
                height = scaled.height,
                originalUri = CAPTURE_URI,
                displayName = CAPTURE_DISPLAY_NAME,
            )

        if (shouldRecycleScaled) {
            scaled.recycle()
        }
        return result
    }

    /**
     * Scales a bitmap proportionally if its longest edge exceeds
     * [MAX_DIMENSION].
     *
     * Returns the original bitmap unmodified if no scaling is needed.
     *
     * @param bitmap The source bitmap to potentially downscale.
     * @return The scaled bitmap, or the original if already within bounds.
     */
    private fun scaleIfNeeded(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= MAX_DIMENSION) return bitmap

        val scale = MAX_DIMENSION.toFloat() / longest
        val newWidth = (bitmap.width * scale).toInt()
        val newHeight = (bitmap.height * scale).toInt()
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    /**
     * Resolves the current display dimensions and density.
     *
     * On API 30+ uses the [WindowMetrics][android.view.WindowMetrics]
     * API. Falls back to the deprecated [DisplayMetrics] path on
     * API 28-29.
     *
     * @param context Context for accessing the window manager service.
     * @return The resolved [CaptureMetrics].
     */
    private fun resolveDisplayMetrics(context: Context): CaptureMetrics {
        val windowManager =
            context.getSystemService(
                Context.WINDOW_SERVICE,
            ) as WindowManager

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds: Rect = windowManager.currentWindowMetrics.bounds
            val densityDpi = context.resources.displayMetrics.densityDpi
            CaptureMetrics(
                width = bounds.width(),
                height = bounds.height(),
                densityDpi = densityDpi,
            )
        } else {
            @Suppress("Deprecation")
            val metrics =
                DisplayMetrics().also {
                    windowManager.defaultDisplay.getRealMetrics(it)
                }
            CaptureMetrics(
                width = metrics.widthPixels,
                height = metrics.heightPixels,
                densityDpi = metrics.densityDpi,
            )
        }
    }

    /**
     * Ensures the dedicated [HandlerThread] is running.
     *
     * Creates and starts the thread if it does not already exist. The
     * thread is used for [ImageReader] callbacks to avoid blocking the
     * main thread.
     */
    private fun ensureHandlerThread() {
        if (handlerThread?.isAlive == true) return
        val thread = HandlerThread(HANDLER_THREAD_NAME).apply { start() }
        handlerThread = thread
        handler = Handler(thread.looper)
    }

    /**
     * Stops the [MediaProjection] and clears the stored reference.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun releaseProjection() {
        try {
            mediaProjection?.stop()
        } catch (_: Exception) {
            // projection may already be stopped
        }
        mediaProjection = null
        _hasPermission.value = false
    }

    /**
     * Stops the dedicated [HandlerThread] and clears references.
     */
    private fun stopHandlerThread() {
        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
    }

    /**
     * Display dimensions and density for virtual display creation.
     *
     * @property width Screen width in pixels.
     * @property height Screen height in pixels.
     * @property densityDpi Screen density in DPI.
     */
    private data class CaptureMetrics(
        val width: Int,
        val height: Int,
        val densityDpi: Int,
    )

    /** Constants for [ScreenCaptureBridge]. */
    companion object {
        /** Maximum pixel dimension on the longest edge after scaling. */
        private const val MAX_DIMENSION = 1920

        /** JPEG compression quality for captured screenshots. */
        private const val JPEG_QUALITY = 80

        /** MIME type for all captured output images. */
        private const val OUTPUT_MIME = "image/jpeg"

        /** Display name used for screen-captured images. */
        private const val CAPTURE_DISPLAY_NAME = "screenshot"

        /** URI placeholder for screen-captured images. */
        private const val CAPTURE_URI = "screen://capture"

        /** Name for the virtual display created during capture. */
        private const val VIRTUAL_DISPLAY_NAME = "ZeroAIScreenCapture"

        /** Name for the dedicated handler thread. */
        private const val HANDLER_THREAD_NAME = "ScreenCaptureHandler"

        /** Maximum images buffered by the [ImageReader]. */
        private const val IMAGE_READER_MAX_IMAGES = 2

        /**
         * API level at which [MediaProjection] requires re-consent for
         * each use (Android 14).
         */
        private const val BUILD_VERSION_RECONSENT = 34
    }
}
