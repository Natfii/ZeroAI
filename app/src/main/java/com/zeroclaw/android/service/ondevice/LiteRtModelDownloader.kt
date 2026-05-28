/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.service.ondevice

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.zeroclaw.android.model.LiteRtModel
import com.zeroclaw.android.worker.LiteRtDownloadWorker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Thin wrapper around [WorkManager] for LiteRT-LM model downloads.
 *
 * Owns the unique-work naming + constraints (UNMETERED-only) so the
 * ViewModel only deals with model identity, not WorkManager
 * primitives. Cancel is a regular `cancelUniqueWork` — the worker's
 * own loop honors cancellation between buffer reads.
 *
 * @param context Application context for the [WorkManager] singleton.
 */
class LiteRtModelDownloader(
    private val context: Context,
) {
    private val workManager: WorkManager = WorkManager.getInstance(context)

    /**
     * Enqueues a download for [model] under a unique work name so
     * tapping the Download button twice doesn't fire two workers.
     * Uses [ExistingWorkPolicy.KEEP] — if a fetch is already in
     * flight, the second tap is a no-op.
     *
     * @param model Variant to fetch.
     */
    fun start(model: LiteRtModel) {
        val constraints =
            Constraints
                .Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .build()
        val request =
            OneTimeWorkRequestBuilder<LiteRtDownloadWorker>()
                .setInputData(workDataOf(LiteRtDownloadWorker.KEY_MODEL_ID to model.id))
                .setConstraints(constraints)
                .build()
        workManager.enqueueUniqueWork(
            LiteRtDownloadWorker.uniqueWorkName(model.id),
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * Cancels any in-flight download for [model]. The worker's
     * loop notices its coroutine context flipped to cancelled and
     * exits between buffer reads, leaving the partial `.tmp` file
     * on disk for a future resume-capable iteration.
     *
     * @param model Variant whose download to cancel.
     */
    fun cancel(model: LiteRtModel) {
        workManager.cancelUniqueWork(LiteRtDownloadWorker.uniqueWorkName(model.id))
    }

    /**
     * Returns the current [WorkInfo] for [model]'s unique download
     * work as a flow, or `null` emissions when no work is enqueued.
     * The ViewModel maps these emissions onto [LiteRtModelStatus]
     * variants for the picker.
     *
     * @param model Variant whose work to observe.
     */
    fun observe(model: LiteRtModel): Flow<WorkInfo?> =
        workManager
            .getWorkInfosForUniqueWorkFlow(LiteRtDownloadWorker.uniqueWorkName(model.id))
            .map { it.firstOrNull() }
}
