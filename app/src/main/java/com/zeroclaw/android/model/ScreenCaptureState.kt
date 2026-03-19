/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.model

/**
 * Represents the current state of the screen capture subsystem.
 *
 * Used by the terminal screen to determine whether to prompt for
 * [MediaProjection][android.media.projection.MediaProjection] consent,
 * proceed with capture, or display an error.
 */
sealed interface ScreenCaptureState {
    /**
     * The user has not yet granted screen capture consent.
     *
     * The UI should present the system consent dialog via
     * [MediaProjectionManager.createScreenCaptureIntent][android.media.projection.MediaProjectionManager.createScreenCaptureIntent].
     */
    data object NoPermission : ScreenCaptureState

    /**
     * Screen capture consent has been granted and the bridge is
     * ready to capture.
     */
    data object Ready : ScreenCaptureState

    /**
     * A screen capture operation is currently in progress.
     */
    data object Capturing : ScreenCaptureState

    /**
     * The most recent screen capture attempt failed.
     *
     * @property message Human-readable description of the failure.
     */
    data class Error(
        val message: String,
    ) : ScreenCaptureState
}
