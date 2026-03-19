/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.service

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.zeroclaw.android.model.ProcessedImage
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Bridge that manages CameraX lifecycle for photo capture and preview.
 *
 * Provides suspend-based photo capture that downscales, JPEG-compresses,
 * and base64-encodes the result into a [ProcessedImage] suitable for
 * vision model APIs. Camera operations run on the main thread as required
 * by CameraX, while image processing runs on [Dispatchers.Default].
 *
 * Handles permission checks, missing camera hardware, and camera-in-use
 * errors gracefully by returning [Result.failure] with descriptive messages.
 *
 * Thread safety: all CameraX binding and unbinding must occur on the main
 * thread. Image processing after capture is dispatched to [Dispatchers.Default].
 */
object CameraBridge {
    /** Maximum pixel dimension on the longest edge after scaling. */
    private const val MAX_DIMENSION = 1024

    /** JPEG compression quality for captured photos. */
    private const val JPEG_QUALITY = 85

    /** MIME type for all captured output images. */
    private const val OUTPUT_MIME = "image/jpeg"

    /** Display name used for camera-captured images. */
    private const val CAPTURE_DISPLAY_NAME = "camera_capture"

    /** URI placeholder for camera-captured images (no content URI). */
    private const val CAPTURE_URI = "camera://capture"

    /** Currently selected camera lens facing direction. */
    @Volatile
    private var lensFacing: Int = CameraSelector.LENS_FACING_BACK

    /** Active [ImageCapture] use case, or null when camera is unbound. */
    private var imageCapture: ImageCapture? = null

    /** Active [ProcessCameraProvider], or null when camera is unbound. */
    private var cameraProvider: ProcessCameraProvider? = null

    /** Active [Preview] use case, or null when camera is unbound. */
    private var preview: Preview? = null

    /** The [PreviewView] currently bound, or null when camera is unbound. */
    private var boundPreviewView: PreviewView? = null

    /** The [LifecycleOwner] currently bound, or null when camera is unbound. */
    private var boundLifecycleOwner: LifecycleOwner? = null

    /**
     * Captures a photo using CameraX [ImageCapture].
     *
     * The captured image is downscaled to a maximum of [MAX_DIMENSION]
     * pixels on the longest edge, JPEG-compressed at quality [JPEG_QUALITY],
     * and base64-encoded. The camera must be bound via [bindPreview] before
     * calling this method.
     *
     * Safe to call from the main thread. Image processing after capture
     * runs on [Dispatchers.Default].
     *
     * @param context Android context for camera permission checks and
     *     executor access.
     * @return [Result.success] with the [ProcessedImage] on success,
     *     or [Result.failure] if the camera is unavailable, permission
     *     is not granted, or capture fails.
     */
    suspend fun capturePhoto(context: Context): Result<ProcessedImage> {
        if (!hasCameraPermission(context)) {
            return Result.failure(
                IllegalStateException("Camera permission not granted"),
            )
        }
        if (!hasCameraHardware(context)) {
            return Result.failure(
                UnsupportedOperationException("No camera hardware available"),
            )
        }
        val capture =
            imageCapture
                ?: return Result.failure(
                    IllegalStateException(
                        "Camera not bound. Call bindPreview first.",
                    ),
                )

        val imageProxy =
            captureImageProxy(context, capture)
                .getOrElse { return Result.failure(it) }

        return try {
            val processed =
                withContext(Dispatchers.Default) {
                    processImageProxy(imageProxy)
                }
            if (processed != null) {
                Result.success(processed)
            } else {
                Result.failure(
                    IllegalStateException("Failed to decode captured image"),
                )
            }
        } finally {
            imageProxy.close()
        }
    }

    /**
     * Binds a camera preview to the given [PreviewView] and [LifecycleOwner].
     *
     * Sets up both [Preview] and [ImageCapture] use cases. The camera
     * preview is displayed in the provided [PreviewView]. If the camera
     * is already bound, it is unbound first.
     *
     * Must be called from the main thread.
     *
     * @param context Android context for obtaining the camera provider.
     * @param lifecycleOwner Lifecycle owner for camera binding.
     * @param previewView The [PreviewView] to render the camera feed into.
     * @param onError Optional callback invoked if camera binding fails.
     */
    @Suppress("TooGenericExceptionCaught")
    fun bindPreview(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        onError: ((String) -> Unit)? = null,
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener(
            {
                try {
                    val provider = cameraProviderFuture.get()
                    provider.unbindAll()

                    val previewUseCase =
                        Preview
                            .Builder()
                            .build()
                            .also {
                                it.surfaceProvider = previewView.surfaceProvider
                            }

                    val captureUseCase =
                        ImageCapture
                            .Builder()
                            .setCaptureMode(
                                ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY,
                            ).build()

                    val selector =
                        CameraSelector
                            .Builder()
                            .requireLensFacing(lensFacing)
                            .build()

                    provider.bindToLifecycle(
                        lifecycleOwner,
                        selector,
                        previewUseCase,
                        captureUseCase,
                    )

                    cameraProvider = provider
                    preview = previewUseCase
                    imageCapture = captureUseCase
                    boundPreviewView = previewView
                    boundLifecycleOwner = lifecycleOwner
                } catch (e: Exception) {
                    onError?.invoke(
                        e.message ?: "Camera initialization failed",
                    )
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    /**
     * Unbinds all camera use cases and releases resources.
     *
     * Safe to call even when the camera is not bound. Must be called
     * from the main thread.
     */
    fun unbind() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        preview = null
        imageCapture = null
        boundPreviewView = null
        boundLifecycleOwner = null
    }

    /**
     * Toggles between front and back camera.
     *
     * If the camera is currently bound, it is re-bound with the new
     * lens facing direction. If the camera is not bound, only the
     * lens facing preference is updated for the next bind.
     *
     * Must be called from the main thread.
     */
    fun switchCamera() {
        lensFacing =
            if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }

        val owner = boundLifecycleOwner
        val view = boundPreviewView
        val context = view?.context
        if (owner != null && view != null && context != null) {
            bindPreview(context, owner, view)
        }
    }

    /**
     * Checks whether camera permission has been granted.
     *
     * @param context Android context for permission checking.
     * @return True if [android.Manifest.permission.CAMERA] is granted.
     */
    fun hasCameraPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * Checks whether the device has camera hardware.
     *
     * @param context Android context for feature checking.
     * @return True if the device has at least one camera.
     */
    fun hasCameraHardware(context: Context): Boolean =
        context.packageManager.hasSystemFeature(
            PackageManager.FEATURE_CAMERA_ANY,
        )

    /**
     * Captures an [ImageProxy] from the [ImageCapture] use case.
     *
     * Wraps the callback-based CameraX API in a suspending coroutine.
     *
     * @param context Android context for executor access.
     * @param capture The active [ImageCapture] use case.
     * @return [Result.success] with the captured [ImageProxy], or
     *     [Result.failure] on capture error.
     */
    private suspend fun captureImageProxy(
        context: Context,
        capture: ImageCapture,
    ): Result<ImageProxy> =
        suspendCancellableCoroutine { continuation ->
            capture.takePicture(
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        continuation.resume(Result.success(image))
                    }

                    override fun onError(exception: ImageCaptureException) {
                        continuation.resume(Result.failure(exception))
                    }
                },
            )
        }

    /**
     * Processes an [ImageProxy] into a [ProcessedImage].
     *
     * Decodes the JPEG buffer from the proxy, downscales if needed,
     * re-compresses at [JPEG_QUALITY], and base64-encodes the result.
     *
     * Must be called on a background thread (image processing is
     * CPU-intensive).
     *
     * @param imageProxy The captured image proxy.
     * @return [ProcessedImage] on success, or null if decoding fails.
     */
    private fun processImageProxy(imageProxy: ImageProxy): ProcessedImage? {
        val buffer = imageProxy.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        val original =
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: return null

        val scaled = scaleIfNeeded(original)
        if (scaled !== original) {
            original.recycle()
        }

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

        scaled.recycle()
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
}
