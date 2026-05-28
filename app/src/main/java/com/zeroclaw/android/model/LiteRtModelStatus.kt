/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.model

/**
 * Lifecycle state for a specific LiteRT-LM model variant on this device.
 *
 * Drives the picker UI: each variant card switches between
 * "Download", "Downloading…", "Ready", and error states based on the
 * value here. Distinct from the AgentRepository's `isEnabled` flag,
 * which tracks whether the on-device agent row is the active route.
 *
 * @property model The [LiteRtModel] this status refers to.
 */
@Suppress("OutdatedDocumentation")
sealed interface LiteRtModelStatus {
    /** The model variant this status refers to. */
    val model: LiteRtModel

    /**
     * No `.litertlm` file is present on disk. Picker offers a Download
     * button — gated on a RAM/storage check so the user doesn't kick
     * off a 3.66 GB fetch that won't fit at runtime.
     *
     * @property model Variant.
     * @property ramOk Whether the device currently has enough free RAM
     *   to run this variant (per
     *   [com.zeroclaw.android.service.ondevice.OnDeviceRamGate]).
     * @property storageOk Whether the device has enough free disk to
     *   accept the download.
     */
    data class NotDownloaded(
        override val model: LiteRtModel,
        val ramOk: Boolean,
        val storageOk: Boolean,
    ) : LiteRtModelStatus

    /**
     * A download is in progress for this variant.
     *
     * @property model Variant.
     * @property bytesDownloaded Bytes received so far.
     * @property totalBytes Expected total. Falls back to [model] file
     *   size when the server hasn't sent a Content-Length yet.
     */
    data class Downloading(
        override val model: LiteRtModel,
        val bytesDownloaded: Long,
        val totalBytes: Long,
    ) : LiteRtModelStatus

    /**
     * `.litertlm` file is on disk and ready. The daemon will load it
     * the next time it starts, provided the on-device agent row is
     * the active one.
     *
     * @property model Variant.
     */
    data class Ready(
        override val model: LiteRtModel,
    ) : LiteRtModelStatus

    /**
     * Last download attempt failed. Picker offers Retry.
     *
     * @property model Variant.
     * @property reason User-facing failure message.
     */
    data class Failed(
        override val model: LiteRtModel,
        val reason: String,
    ) : LiteRtModelStatus
}
