/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.worker

import android.content.Context
import android.os.PowerManager
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.zeroclaw.android.ZeroAIApplication

/**
 * Daily background worker that runs Ebbinghaus decay pruning and Jaccard
 * fact merging on the memory store.
 *
 * Delegates to [com.zeroclaw.android.service.MemoryBridge.runMaintenance],
 * which calls the Rust `run_memory_maintenance` FFI function. The work is
 * constrained so it only executes when the battery is not low, and is
 * deferred to the next cycle when the device is in power-save mode.
 *
 * Schedule this worker once at application startup via
 * [androidx.work.WorkManager.enqueueUniquePeriodicWork] using [WORK_NAME].
 *
 * @param context Application context provided by [WorkManager].
 * @param params Worker parameters including constraints and run attempt info.
 */
class MemoryMaintenanceWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    /**
     * Executes the memory maintenance task.
     *
     * Checks for power-save mode first and returns [Result.retry] to defer
     * the run rather than waste battery. On success, logs the pruned and
     * merged counts from the [com.zeroclaw.ffi.FfiMaintenanceReport]. Any
     * exception from the FFI layer results in [Result.retry] so WorkManager
     * will attempt the task again on the next scheduled window.
     *
     * Safe to call from any thread; the FFI call is dispatched to
     * [kotlinx.coroutines.Dispatchers.IO] inside [MemoryBridge][com.zeroclaw.android.service.MemoryBridge].
     *
     * @return [Result.success] when maintenance completes, [Result.retry] when
     *   the device is in power-save mode or an FFI error occurs.
     */
    @Suppress("TooGenericExceptionCaught")
    override suspend fun doWork(): Result {
        val powerManager =
            applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (powerManager?.isPowerSaveMode == true) {
            Log.d(TAG, "Power-save mode active — deferring memory maintenance")
            return Result.retry()
        }

        val app = applicationContext as? ZeroAIApplication ?: return Result.retry()

        return try {
            val report = app.memoryBridge.runMaintenance()
            Log.i(
                TAG,
                "Memory maintenance complete: pruned=${report.prunedCount}, merged=${report.mergedCount}",
            )
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Memory maintenance failed, will retry: ${e.message}")
            Result.retry()
        }
    }

    /** Constants for [MemoryMaintenanceWorker]. */
    companion object {
        private const val TAG = "MemoryMaintenance"

        /** Unique work name for WorkManager scheduling. */
        const val WORK_NAME = "memcore_maintenance"
    }
}
