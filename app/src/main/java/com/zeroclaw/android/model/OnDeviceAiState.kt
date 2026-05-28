/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.model

/**
 * UI state for the on-device AI onboarding step.
 *
 * Decomposed into a user-preference field ([previewTrackSelected]) and
 * a derived ML Kit result ([machine]) so the toggle and the state
 * machine evolve independently. Earlier shapes folded the preference
 * onto every variant which forced [machine] mutations to thread the
 * preference through `mapStatusToUi`-style helpers and created
 * non-sensical states like a `NotSupported` variant pretending to
 * carry a track selection.
 *
 * @property previewTrackSelected Whether the user wants the AI Core
 *   Developer Preview track (Gemma 4 E4B / 128K). User-driven.
 * @property machine Current outcome of the latest availability or
 *   download query. ML Kit-driven.
 */
data class OnDeviceAiUiState(
    val previewTrackSelected: Boolean = false,
    val machine: OnDeviceAiMachine = OnDeviceAiMachine.Checking,
)

/**
 * Outcome of the latest ML Kit / AICore probe for the on-device AI
 * step. Pure result, never carries user preference state — that lives
 * on [OnDeviceAiUiState].
 */
sealed interface OnDeviceAiMachine {
    /** Initial state while the first availability query is in flight. */
    data object Checking : OnDeviceAiMachine

    /**
     * Model is downloaded and ready to use on the chosen track.
     *
     * @property modelName Concrete variant identifier (e.g. `gemma-nano-v3`).
     * @property tokenLimit Maximum input tokens the variant accepts, or `-1`
     *   when ML Kit declined to report a number.
     */
    data class Ready(
        val modelName: String,
        val tokenLimit: Int,
    ) : OnDeviceAiMachine

    /** AICore can fetch the model but hasn't yet. Surfaces a Download button. */
    data object Downloadable : OnDeviceAiMachine

    /**
     * Download is in progress. Drives the progress UI.
     *
     * @property bytesDownloaded Bytes received so far. Negative when unknown.
     * @property totalBytes Total expected size. Negative when unknown.
     */
    data class Downloading(
        val bytesDownloaded: Long,
        val totalBytes: Long,
    ) : OnDeviceAiMachine

    /** Device is on API ≥ 31 but the AICore system app is missing. */
    data object NeedsAiCore : OnDeviceAiMachine

    /**
     * AICore is installed but config hasn't synced yet, or the
     * requested preview model is gated server-side (user not enrolled).
     *
     * @property reason Human-readable explanation supplied by ML Kit.
     */
    data class SetupPending(
        val reason: String,
    ) : OnDeviceAiMachine

    /**
     * Device doesn't meet the minimum API level (31) or hardware
     * requirements for on-device AI.
     */
    data object NotSupported : OnDeviceAiMachine
}
