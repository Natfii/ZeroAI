/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.component

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.zeroclaw.android.model.ProcessedImage
import com.zeroclaw.android.service.CameraBridge
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Height of the camera preview sheet content area. */
private val SHEET_HEIGHT = 480.dp

/** Size of the circular capture button. */
private val CAPTURE_BUTTON_SIZE = 64.dp

/** Size of the inner capture button circle. */
private val CAPTURE_INNER_SIZE = 56.dp

/** Size of the close and flip action buttons. */
private val ACTION_BUTTON_SIZE = 48.dp

/** Padding from the edges of the preview area. */
private val PREVIEW_PADDING = 16.dp

/** Bottom padding for the capture button. */
private val CAPTURE_BOTTOM_PADDING = 32.dp

/** Top padding for the action buttons. */
private val ACTION_TOP_PADDING = 16.dp

/** Duration in milliseconds to show the captured image before dismissing. */
private const val CAPTURE_PREVIEW_DURATION_MS = 1000L

/** Animation duration for capture button press scale effect. */
private const val SCALE_ANIMATION_DURATION_MS = 150

/** Scale factor applied to capture button when pressed. */
private const val PRESSED_SCALE = 0.85f

/** Normal scale factor for the capture button. */
private const val NORMAL_SCALE = 1f

/**
 * Internal state for the camera preview sheet.
 */
private sealed interface CameraSheetState {
    /** Camera is active, showing live preview. */
    data object Previewing : CameraSheetState

    /** A photo has been captured and is being displayed briefly. */
    data class Captured(
        /** The captured image. */
        val image: ProcessedImage,
    ) : CameraSheetState

    /** An error occurred during camera setup or capture. */
    data class Error(
        /** Human-readable error description. */
        val message: String,
    ) : CameraSheetState

    /** Camera permission has not been granted. */
    data object PermissionRequired : CameraSheetState
}

/**
 * Modal bottom sheet that provides a camera preview with capture functionality.
 *
 * Displays a live CameraX preview with controls for capturing a photo,
 * switching between front and back cameras, and dismissing the sheet.
 * After capture, the taken photo is shown briefly before the sheet
 * auto-dismisses and delivers the [ProcessedImage] via [onImageCaptured].
 *
 * Camera resources are automatically released when the sheet is dismissed
 * to conserve battery.
 *
 * @param onDismiss Callback when the sheet is dismissed without capturing.
 * @param onImageCaptured Callback with the captured [ProcessedImage] on success.
 * @param modifier Modifier applied to the [ModalBottomSheet].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongMethod", "CyclomaticComplexMethod", "CognitiveComplexMethod")
@Composable
fun CameraPreviewSheet(
    onDismiss: () -> Unit,
    onImageCaptured: (ProcessedImage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var cameraState by remember {
        mutableStateOf<CameraSheetState>(
            if (CameraBridge.hasCameraPermission(context)) {
                CameraSheetState.Previewing
            } else {
                CameraSheetState.PermissionRequired
            },
        )
    }
    var isCapturing by remember { mutableStateOf(false) }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { granted ->
            cameraState =
                if (granted) {
                    CameraSheetState.Previewing
                } else {
                    CameraSheetState.Error("Camera permission denied")
                }
        }

    if (cameraState is CameraSheetState.PermissionRequired) {
        LaunchedEffect(Unit) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    if (!CameraBridge.hasCameraHardware(context)) {
        cameraState = CameraSheetState.Error("No camera hardware available")
    }

    DisposableEffect(Unit) {
        onDispose {
            CameraBridge.unbind()
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            CameraBridge.unbind()
            onDismiss()
        },
        sheetState = sheetState,
        modifier =
            modifier.semantics {
                paneTitle = "Camera"
            },
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(SHEET_HEIGHT)
                    .semantics {
                        paneTitle = "Camera"
                        contentDescription = "Camera preview"
                    },
        ) {
            when (val state = cameraState) {
                is CameraSheetState.Previewing -> {
                    CameraPreviewContent(
                        lifecycleOwner = lifecycleOwner,
                        onError = { message ->
                            cameraState = CameraSheetState.Error(message)
                        },
                    )
                }

                is CameraSheetState.Captured -> {
                    CapturedImageContent(image = state.image)
                }

                is CameraSheetState.Error -> {
                    ErrorContent(message = state.message)
                }

                is CameraSheetState.PermissionRequired -> {
                    PermissionContent()
                }
            }

            IconButton(
                onClick = {
                    CameraBridge.unbind()
                    onDismiss()
                },
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(
                            start = PREVIEW_PADDING,
                            top = ACTION_TOP_PADDING,
                        ).size(ACTION_BUTTON_SIZE)
                        .background(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                            shape = CircleShape,
                        ).semantics {
                            contentDescription = "Close camera"
                            role = Role.Button
                        },
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }

            if (cameraState is CameraSheetState.Previewing) {
                IconButton(
                    onClick = { CameraBridge.switchCamera() },
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(
                                end = PREVIEW_PADDING,
                                top = ACTION_TOP_PADDING,
                            ).size(ACTION_BUTTON_SIZE)
                            .background(
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                                shape = CircleShape,
                            ).semantics {
                                contentDescription = "Switch camera"
                                role = Role.Button
                            },
                ) {
                    Icon(
                        imageVector = Icons.Filled.Cameraswitch,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }

                CaptureButton(
                    isCapturing = isCapturing,
                    onClick = {
                        if (isCapturing) return@CaptureButton
                        isCapturing = true
                        scope.launch {
                            val result = CameraBridge.capturePhoto(context)
                            result.fold(
                                onSuccess = { image ->
                                    cameraState =
                                        CameraSheetState.Captured(image)
                                    delay(CAPTURE_PREVIEW_DURATION_MS)
                                    CameraBridge.unbind()
                                    onImageCaptured(image)
                                },
                                onFailure = { error ->
                                    cameraState =
                                        CameraSheetState.Error(
                                            error.message
                                                ?: "Photo capture failed",
                                        )
                                    isCapturing = false
                                },
                            )
                        }
                    },
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = CAPTURE_BOTTOM_PADDING),
                )
            }
        }
    }
}

/**
 * Live camera preview content using CameraX [PreviewView].
 *
 * Binds the camera preview and image capture use cases through
 * [CameraBridge] when the view is created, using the provided
 * [lifecycleOwner] for lifecycle-aware binding.
 *
 * @param lifecycleOwner Lifecycle owner for camera binding.
 * @param onError Callback when camera binding fails.
 */
@Composable
private fun CameraPreviewContent(
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current

    AndroidView(
        factory = { ctx ->
            PreviewView(ctx).also { previewView ->
                CameraBridge.bindPreview(
                    context = ctx,
                    lifecycleOwner = lifecycleOwner,
                    previewView = previewView,
                    onError = onError,
                )
            }
        },
        modifier =
            Modifier
                .fillMaxSize()
                .semantics {
                    contentDescription = "Camera viewfinder"
                },
    )
}

/**
 * Briefly displayed content showing the captured image.
 *
 * @param image The captured [ProcessedImage] to display.
 */
@Composable
private fun CapturedImageContent(image: ProcessedImage) {
    val bytes =
        android.util.Base64.decode(
            image.base64Data,
            android.util.Base64.NO_WRAP,
        )
    val bitmap =
        remember(image) {
            android.graphics.BitmapFactory
                .decodeByteArray(
                    bytes,
                    0,
                    bytes.size,
                )?.asImageBitmap()
        }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .semantics {
                    contentDescription = "Captured photo preview"
                },
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = "Captured photo",
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * Error content displayed when camera setup or capture fails.
 *
 * @param message Human-readable error description.
 */
@Composable
private fun ErrorContent(message: String) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .semantics {
                    contentDescription = "Camera error: $message"
                },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/**
 * Content displayed while waiting for camera permission.
 */
@Composable
private fun PermissionContent() {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .semantics {
                    contentDescription = "Waiting for camera permission"
                },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Camera permission required",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Circular capture button with a scale animation on press.
 *
 * The button is rendered as a white circle with a slightly smaller
 * inner circle, providing a distinctive camera shutter appearance.
 * A press-and-release scale animation provides tactile feedback.
 *
 * @param isCapturing Whether a capture is currently in progress
 *     (disables the button).
 * @param onClick Callback when the button is tapped.
 * @param modifier Modifier applied to the outer container.
 */
@Composable
private fun CaptureButton(
    isCapturing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) PRESSED_SCALE else NORMAL_SCALE,
        animationSpec = tween(durationMillis = SCALE_ANIMATION_DURATION_MS),
        label = "capture_button_scale",
    )

    Box(
        modifier =
            modifier
                .size(CAPTURE_BUTTON_SIZE)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }.clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.3f))
                .clickable(
                    enabled = !isCapturing,
                    role = Role.Button,
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                ).semantics {
                    contentDescription =
                        if (isCapturing) {
                            "Capturing photo"
                        } else {
                            "Take photo"
                        }
                },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .size(CAPTURE_INNER_SIZE)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface),
        )
        if (isCapturing) {
            Icon(
                imageVector = Icons.Filled.PhotoCamera,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(ACTION_BUTTON_SIZE / 2),
            )
        }
    }
}
