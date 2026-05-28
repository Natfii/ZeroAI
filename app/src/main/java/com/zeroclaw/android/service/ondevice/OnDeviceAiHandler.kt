/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.service.ondevice

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.zeroclaw.android.model.OnDeviceAiMachine
import com.zeroclaw.android.model.OnDeviceAiUiState
import com.zeroclaw.android.model.OnDeviceStatus
import com.zeroclaw.android.service.OnDeviceReleaseTrack
import com.zeroclaw.android.service.downloadOnDeviceModel
import com.zeroclaw.android.service.isAiCoreInstalled
import com.zeroclaw.android.service.resolveOnDeviceModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Minimum SDK at which AI Core is supported.
 *
 * Mirrors the same constant inside `NanoFallback.kt`; kept here as a
 * local copy because the handler needs to differentiate "device too
 * old" (`NotSupported`) from "AI Core just missing" (`NeedsAiCore`).
 */
private const val ON_DEVICE_MIN_SDK = 31

/**
 * Play Store deep link to the AI Core app.
 *
 * Tapping this lands the user on the install/update page for the
 * system service that hosts Gemini Nano. The standard `play.google.com`
 * URL resolves on devices both with and without the Play Store app.
 */
private const val AI_CORE_PLAY_URL =
    "https://play.google.com/store/apps/details?id=com.google.android.aicore"

/**
 * Google Group invitation for the AI Core Developer Preview opt-in.
 *
 * Per the AI Core Developer Preview docs, enrollment is a two-step
 * out-of-band flow: join this group, then become a Play Store tester.
 * The handler can only deep-link here; it cannot toggle enrollment.
 */
private const val PREVIEW_ENROLLMENT_URL =
    "https://groups.google.com/g/aicore-experimental"

/**
 * Coordinates the on-device AI onboarding step.
 *
 * Owns the [OnDeviceAiUiState] machine: status checks, download flow,
 * preview-track toggle, and outbound intents for AI Core install /
 * Developer Preview enrollment. Mirrors the established
 * `OnboardingChannelHandler` shape — handler owns the mutation logic,
 * the coordinator just exposes the state flow and delegates calls.
 *
 * Stale-write protection: each launched job captures the
 * [OnDeviceAiUiState.previewTrackSelected] value it was started under
 * and refuses to commit a result whose track no longer matches the
 * live state. Cancellation is cooperative in coroutines, so this guard
 * is what actually prevents a slow stable probe from clobbering a
 * fresh preview probe.
 *
 * @param state Mutable holder of the current step state.
 * @param scope Coroutine scope used for status/download work
 *   (typically the coordinator's [androidx.lifecycle.viewModelScope]).
 * @param context Application [Context] for AI Core package probes and
 *   Play Store / Google Group intents.
 */
internal class OnDeviceAiHandler(
    private val state: MutableStateFlow<OnDeviceAiUiState>,
    private val scope: CoroutineScope,
    private val context: Context,
) {
    /** Active download job, cancelled on track toggle or re-check. */
    private var downloadJob: Job? = null

    /** Active status-probe job, cancelled when a new probe starts. */
    private var probeJob: Job? = null

    /**
     * Refreshes the on-device AI state for the currently selected
     * track. Cancels any in-flight download or probe so a stale
     * coroutine cannot overwrite the new result.
     *
     * Composes well with `LaunchedEffect(Unit) { handler.refresh() }`
     * from the step composable.
     */
    fun refresh() {
        downloadJob?.cancel()
        downloadJob = null
        probeJob?.cancel()
        val preview = state.value.previewTrackSelected
        state.update { it.copy(machine = OnDeviceAiMachine.Checking) }
        probeJob =
            scope.launch {
                val resolved = resolveCurrentMachine(preview)
                commitIfTrackStillMatches(preview, resolved)
            }
    }

    /**
     * Switches the AI Core release channel the step is asking about.
     *
     * No-op if [usePreview] matches the currently selected track.
     * Otherwise flips the preference and re-runs [refresh] so the UI
     * shows the right state for the new track.
     *
     * @param usePreview `true` to target the AI Core Developer Preview
     *   track (Gemma 4 E4B), `false` for the stable Gemini Nano track.
     */
    fun setUsePreview(usePreview: Boolean) {
        if (state.value.previewTrackSelected == usePreview) return
        state.update {
            it.copy(
                previewTrackSelected = usePreview,
                machine = OnDeviceAiMachine.Checking,
            )
        }
        refresh()
    }

    /**
     * Triggers the AI Core model download for the current track. The
     * state flow walks through [OnDeviceAiMachine.Downloading] entries
     * as bytes arrive, then settles on [OnDeviceAiMachine.Ready] or
     * [OnDeviceAiMachine.SetupPending] depending on the outcome.
     *
     * Safe to call again while a download is in flight — the existing
     * job is cancelled and a new one starts from zero.
     */
    fun startDownload() {
        downloadJob?.cancel()
        val preview = state.value.previewTrackSelected
        val track = preview.asReleaseTrack()
        state.update {
            it.copy(
                machine =
                    OnDeviceAiMachine.Downloading(
                        bytesDownloaded = 0L,
                        totalBytes = -1L,
                    ),
            )
        }
        downloadJob =
            scope.launch {
                downloadOnDeviceModel(track = track).collect { status ->
                    val machine = mapStatusToMachine(status, preview, track)
                    commitIfTrackStillMatches(preview, machine)
                }
            }
    }

    /**
     * Opens the AI Core Play Store listing so the user can install or
     * update the system app. Silently swallows resolution failures
     * (e.g. an Android Auto / TV form factor with no browser) — the UI
     * is responsible for falling back to a manual instruction.
     */
    fun openAiCoreInstall() {
        launchIntent(AI_CORE_PLAY_URL)
    }

    /**
     * Opens the AI Core Developer Preview enrollment page. Users must
     * join the Google Group and become a Play Store tester before the
     * preview track returns anything other than `UNAVAILABLE`.
     */
    fun openPreviewEnrollment() {
        launchIntent(PREVIEW_ENROLLMENT_URL)
    }

    /**
     * Resolves the appropriate machine state given the current device
     * and track. Encapsulates the "AI Core present?" probe so callers
     * do not branch on it.
     */
    private suspend fun resolveCurrentMachine(previewSelected: Boolean): OnDeviceAiMachine {
        if (Build.VERSION.SDK_INT < ON_DEVICE_MIN_SDK) return OnDeviceAiMachine.NotSupported
        if (!isAiCoreInstalled(context)) return OnDeviceAiMachine.NeedsAiCore
        val track = previewSelected.asReleaseTrack()
        val resolution = resolveOnDeviceModel(track = track)
        return mapResolutionToMachine(resolution, previewSelected, track)
    }

    /**
     * Converts a one-shot [com.zeroclaw.android.service.OnDeviceResolution]
     * into the matching [OnDeviceAiMachine] variant. When the model is
     * `Available`, this carries the variant name + token limit straight
     * through from the resolution (no extra IPC).
     */
    private fun mapResolutionToMachine(
        resolution: com.zeroclaw.android.service.OnDeviceResolution,
        previewSelected: Boolean,
        track: OnDeviceReleaseTrack,
    ): OnDeviceAiMachine =
        when (val status = resolution.status) {
            OnDeviceStatus.Available ->
                OnDeviceAiMachine.Ready(
                    modelName = resolution.modelName.ifBlank { DEFAULT_MODEL_NAME },
                    tokenLimit = resolution.tokenLimit,
                )
            else -> mapStatusToMachine(status, previewSelected, track)
        }

    /**
     * Maps a download-flow [OnDeviceStatus] event onto the matching
     * [OnDeviceAiMachine] variant. Used by the streaming download
     * collector, where the variant name + token limit aren't carried
     * by the event stream itself.
     */
    private fun mapStatusToMachine(
        status: OnDeviceStatus,
        previewSelected: Boolean,
        track: OnDeviceReleaseTrack,
    ): OnDeviceAiMachine =
        when (status) {
            OnDeviceStatus.Available ->
                OnDeviceAiMachine
                    .Ready(modelName = DEFAULT_MODEL_NAME, tokenLimit = -1)
                    .also { scheduleAvailableProbe(previewSelected, track) }
            OnDeviceStatus.Downloadable -> OnDeviceAiMachine.Downloadable
            is OnDeviceStatus.Downloading ->
                OnDeviceAiMachine.Downloading(
                    bytesDownloaded = status.bytesDownloaded,
                    totalBytes = status.totalBytes,
                )
            is OnDeviceStatus.Unavailable -> OnDeviceAiMachine.SetupPending(status.reason)
            OnDeviceStatus.NotSupported -> OnDeviceAiMachine.NotSupported
        }

    /**
     * Re-runs a single resolution after a streaming download settles
     * on `Available`, so the UI can show the resolved variant name and
     * token limit (which the download stream itself never reports).
     */
    private fun scheduleAvailableProbe(
        previewSelected: Boolean,
        track: OnDeviceReleaseTrack,
    ) {
        scope.launch {
            val resolution = resolveOnDeviceModel(track = track)
            if (resolution.status is OnDeviceStatus.Available) {
                commitIfTrackStillMatches(
                    previewSelected,
                    OnDeviceAiMachine.Ready(
                        modelName = resolution.modelName.ifBlank { DEFAULT_MODEL_NAME },
                        tokenLimit = resolution.tokenLimit,
                    ),
                )
            }
        }
    }

    /**
     * Commits a freshly-computed machine state only when the live
     * track preference still matches the track the result was
     * computed under. Discards stale writes from cancelled-but-still-
     * in-flight probes, closing the race window left by cooperative
     * coroutine cancellation.
     */
    private fun commitIfTrackStillMatches(
        previewSelected: Boolean,
        machine: OnDeviceAiMachine,
    ) {
        state.update { current ->
            if (current.previewTrackSelected == previewSelected) {
                current.copy(machine = machine)
            } else {
                current
            }
        }
    }

    /**
     * Launches a `VIEW` intent for the given URL with NEW_TASK so the
     * application-context startActivity call lands cleanly.
     */
    @Suppress("SwallowedException")
    private fun launchIntent(url: String) {
        val intent =
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            // Caller should have a copy-fallback in the UI; the handler
            // intentionally degrades quietly rather than crash a
            // foreground onboarding flow.
        }
    }

    /** Convenience: maps the preview-toggle boolean to the SDK enum. */
    private fun Boolean.asReleaseTrack(): OnDeviceReleaseTrack = if (this) OnDeviceReleaseTrack.Preview else OnDeviceReleaseTrack.Stable

    /** Constants for the on-device AI handler. */
    companion object {
        /** Fallback label shown when ML Kit returns an empty model name. */
        private const val DEFAULT_MODEL_NAME = "Gemini Nano"
    }
}
