/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.service.ondevice

import android.app.ActivityManager
import android.content.Context
import android.os.Environment
import android.os.StatFs
import com.zeroclaw.android.model.LiteRtModel

/**
 * Safety margin added on top of model file + working-memory estimates
 * so the daemon, OS, and other foreground state still have headroom.
 *
 * 1.5 GB matches the Tensor G5 / 16 GB Pixel envelope after subtracting
 * system + foreground app baseline. Smaller margins risked LMK kills
 * during inference on devices with multiple background services.
 */
private const val SAFETY_MARGIN_BYTES = 1_500_000_000L

/**
 * Extra cushion to absorb OEM-specific memory pressure (Samsung
 * Battery Guardian, Xiaomi MIUI killer, etc.) on top of the safety
 * margin. Conservative; tunable per finding from real-device traces.
 */
private const val OEM_CUSHION_BYTES = 512_000_000L

/**
 * Extra disk headroom required beyond the raw `.litertlm` file size.
 * Covers the WorkManager partial-download `.tmp` file and OS scratch
 * space during the verify/rename step.
 */
private const val DOWNLOAD_DISK_PAD_BYTES = 256_000_000L

/**
 * Probes whether a given on-device LiteRT-LM variant can safely be
 * downloaded and run on the current device.
 *
 * Used by the picker UI to gate the Download button per variant so
 * users on devices that can't host Phi-4-mini's ~8.7 GB peak don't
 * waste 3.9 GB of bandwidth on a model that will OOM at load time.
 *
 * @param context Application context for [ActivityManager] +
 *   [StatFs] lookups.
 */
class OnDeviceRamGate(
    private val context: Context,
) {
    /**
     * Snapshot of the current RAM gate result.
     *
     * @property ramOk Whether the current free RAM exceeds the model's
     *   working-memory estimate plus safety + OEM cushion.
     * @property storageOk Whether the data-files partition has enough
     *   free disk to host the model download.
     * @property availMemBytes Snapshot of the OS-reported available RAM.
     * @property lowMemory Whether the OS reports it is in a
     *   low-memory state right now.
     */
    data class Result(
        val ramOk: Boolean,
        val storageOk: Boolean,
        val availMemBytes: Long,
        val lowMemory: Boolean,
    )

    /**
     * Computes the gate result for [model] at the moment this is called.
     *
     * Safe to call from the main thread; the underlying queries are
     * non-blocking. Re-call when the user comes back to the picker so
     * the gate reflects current memory pressure.
     *
     * @param model The LiteRT-LM variant to test against.
     * @return [Result] describing whether the download + run is safe
     *   right now.
     */
    fun evaluate(model: LiteRtModel): Result {
        val activityManager =
            context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        val requiredRam =
            model.workingMemoryBytes + SAFETY_MARGIN_BYTES + OEM_CUSHION_BYTES
        val ramOk =
            !memoryInfo.lowMemory &&
                memoryInfo.availMem >= requiredRam &&
                memoryInfo.availMem >= memoryInfo.threshold * 2L
        val freeBytes = freeDataPartitionBytes()
        val storageOk = freeBytes >= model.fileBytes + DOWNLOAD_DISK_PAD_BYTES
        return Result(
            ramOk = ramOk,
            storageOk = storageOk,
            availMemBytes = memoryInfo.availMem,
            lowMemory = memoryInfo.lowMemory,
        )
    }

    /**
     * Returns the number of bytes currently free on the data partition
     * where downloaded models live. Falls back to `0` when the query
     * itself throws (e.g. SAF-only volumes), so the caller's storage
     * gate fails closed.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun freeDataPartitionBytes(): Long =
        try {
            val target = context.filesDir ?: Environment.getDataDirectory()
            val stat = StatFs(target.absolutePath)
            stat.availableBytes
        } catch (_: Exception) {
            0L
        }
}
