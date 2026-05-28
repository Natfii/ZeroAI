/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.agents

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.zeroclaw.android.ZeroAIApplication
import com.zeroclaw.android.data.OnDeviceLargeAgent
import com.zeroclaw.android.model.LiteRtModel
import com.zeroclaw.android.model.LiteRtModelCatalog
import com.zeroclaw.android.model.LiteRtModelStatus
import com.zeroclaw.android.service.ondevice.LiteRtModelDownloader
import com.zeroclaw.android.service.ondevice.LiteRtModelStore
import com.zeroclaw.android.service.ondevice.OnDeviceInferenceState
import com.zeroclaw.android.service.ondevice.OnDeviceRamGate
import com.zeroclaw.android.worker.LiteRtDownloadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Sharing window matching [AgentsViewModel] for consistency. */
private const val STOP_TIMEOUT_MS = 5000L

/**
 * Aggregated UI state for the on-device large model setup screen.
 *
 * Each catalog variant gets its own [LiteRtModelStatus] entry so the
 * picker can render Download / Ready / Downloading per row without
 * the screen re-resolving the per-card state at composition time.
 *
 * @property statuses Per-variant status keyed by [LiteRtModel.id], in
 *   [LiteRtModelCatalog.all] order so the picker stays stable.
 * @property selectedModelId Variant the user has selected as the
 *   active on-device LLM (loaded by the daemon at next start).
 */
data class OnDeviceLargeUiState(
    val statuses: List<LiteRtModelStatus>,
    val selectedModelId: String,
)

/**
 * ViewModel for the on-device large model setup screen.
 *
 * Composes:
 *  - a static catalog ([LiteRtModelCatalog]) of E2B / E4B / Phi-4-mini
 *  - per-variant download state from [LiteRtModelDownloader] (WorkManager)
 *  - per-variant on-disk readiness via [LiteRtModelStore]
 *  - device RAM/storage gating via [OnDeviceRamGate]
 *  - the persisted selection + the agent row's enabled flag
 *
 * into a single [OnDeviceLargeUiState] flow consumed by the picker.
 *
 * @param application Application context for repository + store access.
 */
class OnDeviceLargeModelViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val app = application as ZeroAIApplication
    private val agentRepository = app.agentRepository
    private val modelStore = LiteRtModelStore(app)
    private val ramGate = OnDeviceRamGate(app)
    private val downloader = LiteRtModelDownloader(app)

    /** Initial selection used until the persisted choice flows in. */
    private val initialSelectedId = LiteRtModelCatalog.Gemma4E2B.id

    /** Refresh trigger so the picker re-evaluates the RAM gate on resume. */
    private val refresh = MutableStateFlow(0)

    /** Last persisted selection, mirrored locally so [uiState] can stay synchronous. */
    private val selectedId = MutableStateFlow(initialSelectedId)

    /**
     * Per-variant status + selected id. The download-info flow drives
     * `Downloading`; the file probe drives `Ready`; the RAM gate drives
     * `NotDownloaded.ramOk`.
     *
     * Probe-throttling: filesystem + ActivityManager work runs only
     * when the [refresh] counter advances (initial composition,
     * lifecycle resume, post-action triggers). Between refreshes
     * the cached snapshot is overlaid with live WorkInfo to flip a
     * row into `Downloading` / `Failed` without re-syscalling on
     * every progress tick. All probes run on [Dispatchers.IO].
     */
    @Suppress("SpreadOperator")
    val uiState: StateFlow<OnDeviceLargeUiState> =
        combine(
            selectedId,
            refresh.map { probeAllVariants() }.flowOn(Dispatchers.IO),
            *LiteRtModelCatalog.all.map { downloader.observe(it) }.toTypedArray(),
        ) { values ->
            val sel = values[0] as String

            @Suppress("UNCHECKED_CAST")
            val probed = values[1] as Map<String, LiteRtModelStatus>
            val workInfos: List<WorkInfo?> =
                LiteRtModelCatalog.all.indices.map { idx -> values[idx + 2] as WorkInfo? }
            OnDeviceLargeUiState(
                statuses =
                    LiteRtModelCatalog.all.mapIndexed { idx, variant ->
                        overlayWorkInfo(variant, probed.getValue(variant.id), workInfos[idx])
                    },
                selectedModelId = sel,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            OnDeviceLargeUiState(
                statuses = initialProbedStatuses(),
                selectedModelId = initialSelectedId,
            ),
        )

    /** Whether the on-device large-model agent row is currently active. */
    val isEnabled: StateFlow<Boolean> =
        agentRepository
            .isAgentEnabled(OnDeviceLargeAgent.ID)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    /**
     * Live engine state from the application-scoped inference
     * manager. Drives the "Loaded in daemon" surface on the picker
     * so users can see whether the daemon has the model warm.
     */
    val inferenceState: StateFlow<OnDeviceInferenceState> =
        app.onDeviceInferenceManager.state
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                OnDeviceInferenceState.Idle,
            )

    init {
        viewModelScope.launch {
            modelStore.selectedModel.collect { selectedId.value = it.id }
        }
        // After any download settles to a terminal state, bump the
        // refresh counter so probeAllVariants re-scans the filesystem.
        // Without this the per-variant card stays stuck on the stale
        // "NotDownloaded" snapshot even after WorkManager succeeds —
        // overlayWorkInfo only flips RUNNING/ENQUEUED/FAILED into
        // visible UI, terminal SUCCEEDED falls through to the cache.
        LiteRtModelCatalog.all.forEach { variant ->
            viewModelScope.launch {
                downloader
                    .observe(variant)
                    .map { it?.state }
                    .distinctUntilChanged()
                    .filter { state ->
                        state == WorkInfo.State.SUCCEEDED ||
                            state == WorkInfo.State.CANCELLED
                    }.collect { refresh.value += 1 }
            }
        }
    }

    /** Re-evaluates per-variant status against the current device state. */
    fun refreshState() {
        refresh.value += 1
    }

    /**
     * Persists [variant] as the user's selected on-device model. The
     * daemon picks this up at next start (once Slice 3 wires the load).
     */
    fun selectModel(variant: LiteRtModel) {
        viewModelScope.launch {
            modelStore.setSelectedModel(variant)
            refresh.value += 1
        }
    }

    /**
     * Flips the on-device agent row's enabled state. Mutual exclusion
     * in `AgentDao.toggleExclusive` ensures every other agent row is
     * disabled when this turns on, and a fresh slot toggle elsewhere
     * disables this row.
     */
    fun toggleEnabled() {
        viewModelScope.launch {
            agentRepository.toggleEnabled(OnDeviceLargeAgent.ID)
        }
    }

    /** Enqueues a unique download for [variant] (no-op if one is in flight). */
    fun startDownload(variant: LiteRtModel) {
        downloader.start(variant)
    }

    /** Cancels any in-flight download for [variant]. */
    fun cancelDownload(variant: LiteRtModel) {
        downloader.cancel(variant)
    }

    /**
     * Deletes the on-disk artifacts for [variant] (`.litertlm`, the
     * readiness sentinel, and any leftover `.tmp`). After deletion
     * the picker drops back to `NotDownloaded` for that row. Files
     * that couldn't be deleted (e.g. a daemon-held `mmap` on the
     * model) are returned in [LiteRtModelStore.DeleteResult.orphanedFiles]
     * — Slice 3 will surface those to the user once the daemon
     * lifecycle hook lands; for now the next refresh re-probes and
     * shows `Ready` again if the file is still present.
     */
    fun deleteModel(variant: LiteRtModel) {
        viewModelScope.launch {
            modelStore.deleteModel(variant)
            refresh.value += 1
        }
    }

    /**
     * Pre-resolves every catalog variant's filesystem + RAM-gate
     * snapshot. Called on each [refresh] tick (initial composition,
     * lifecycle resume, post-action triggers) and the result is
     * cached for the next combine cycle — keeps progress emissions
     * from re-syscalling.
     */
    private fun probeAllVariants(): Map<String, LiteRtModelStatus> =
        LiteRtModelCatalog.all.associate { variant ->
            variant.id to probeOne(variant)
        }

    /**
     * Synchronous variant probe used to seed the initial
     * `stateIn` value so the picker has full content on first
     * composition instead of an empty placeholder. The three
     * filesystem checks are sub-millisecond.
     */
    private fun initialProbedStatuses(): List<LiteRtModelStatus> = LiteRtModelCatalog.all.map { variant -> probeOne(variant) }

    /**
     * Single-variant probe combining the on-disk sentinel check with
     * the device RAM/storage gate.
     */
    private fun probeOne(variant: LiteRtModel): LiteRtModelStatus {
        if (modelStore.isDownloaded(variant)) {
            return LiteRtModelStatus.Ready(variant)
        }
        val gate = ramGate.evaluate(variant)
        return LiteRtModelStatus.NotDownloaded(
            model = variant,
            ramOk = gate.ramOk,
            storageOk = gate.storageOk,
        )
    }

    /**
     * Overlays the most recent [WorkInfo] onto the cached probe for
     * [variant]. Active or enqueued work flips the row into
     * `Downloading`; failed work surfaces the error message; any
     * other terminal state (succeeded/cancelled/blocked) falls
     * through to whatever the last probe reported, which the next
     * [refresh] tick will recompute.
     */
    private fun overlayWorkInfo(
        variant: LiteRtModel,
        probed: LiteRtModelStatus,
        workInfo: WorkInfo?,
    ): LiteRtModelStatus {
        if (workInfo?.state == WorkInfo.State.RUNNING ||
            workInfo?.state == WorkInfo.State.ENQUEUED
        ) {
            val bytes = workInfo.progress.getLong(LiteRtDownloadWorker.KEY_BYTES, 0L)
            val total = workInfo.progress.getLong(LiteRtDownloadWorker.KEY_TOTAL, -1L)
            return LiteRtModelStatus.Downloading(
                model = variant,
                bytesDownloaded = bytes,
                totalBytes = total,
            )
        }
        if (workInfo?.state == WorkInfo.State.FAILED) {
            val reason =
                workInfo.outputData.getString(LiteRtDownloadWorker.KEY_ERROR)
                    ?: "Download failed"
            return LiteRtModelStatus.Failed(model = variant, reason = reason)
        }
        return probed
    }

    /**
     * Unused helper kept for future direct-Flow consumers (e.g. a
     * Settings screen that wants raw download state). Hidden from
     * the public API for now via internal visibility.
     */
    @Suppress("unused")
    internal fun observeDownload(variant: LiteRtModel): Flow<WorkInfo?> = downloader.observe(variant)
}
