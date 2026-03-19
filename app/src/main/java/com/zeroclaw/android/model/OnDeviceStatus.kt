/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.model

/**
 * Availability status of the on-device Gemini Nano model.
 *
 * Maps to the ML Kit GenAI Prompt API lifecycle. Used by the UI layer
 * to show status indicators and gate on-device inference features.
 */
sealed interface OnDeviceStatus {
    /**
     * Model is downloaded, prepared, and ready for inference.
     */
    data object Available : OnDeviceStatus

    /**
     * Model is available for download but not yet on device.
     *
     * The UI should offer a download action.
     */
    data object Downloadable : OnDeviceStatus

    /**
     * Model weights are currently being downloaded.
     *
     * @property bytesDownloaded Total bytes downloaded so far.
     * @property totalBytes Total download size in bytes, or -1 if unknown.
     */
    data class Downloading(
        val bytesDownloaded: Long,
        val totalBytes: Long = -1L,
    ) : OnDeviceStatus

    /**
     * Model is not available on this device.
     *
     * @property reason Human-readable explanation.
     */
    data class Unavailable(
        val reason: String,
    ) : OnDeviceStatus

    /**
     * Device does not meet the minimum API level (31) for on-device AI.
     */
    data object NotSupported : OnDeviceStatus
}
