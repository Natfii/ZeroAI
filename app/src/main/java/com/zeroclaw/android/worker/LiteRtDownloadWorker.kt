/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.zeroclaw.android.R
import com.zeroclaw.android.model.LiteRtModel
import com.zeroclaw.android.model.LiteRtModelCatalog
import com.zeroclaw.android.service.ondevice.LiteRtModelStore
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

/** Buffer size used while streaming the HTTP body to disk. */
private const val DOWNLOAD_BUFFER_BYTES = 64 * 1024

/**
 * Minimum bytes between progress reports. Keeps `setProgress` cheap
 * — emitting on every 64 KiB read would saturate the WorkManager
 * data channel and stutter the UI for no useful signal gain.
 */
private const val PROGRESS_REPORT_INTERVAL_BYTES = 1_000_000L

/** Connect timeout for the HF HTTPS request. */
private const val CONNECT_TIMEOUT_SECONDS = 30L

/**
 * Read timeout once the stream is established. Kept short so the
 * Cancel button feels responsive: when the user cancels mid-stall,
 * the in-flight `read` aborts within at most this many seconds
 * even if the OkHttp [Call.cancel] hook hasn't fired yet.
 */
private const val READ_TIMEOUT_SECONDS = 15L

/** Foreground notification ID — unique enough vs daemon's IDs. */
private const val NOTIFICATION_ID = 4242

/**
 * Downloads a LiteRT-LM `.litertlm` model from Hugging Face to the
 * app's private files directory.
 *
 * Lifecycle:
 *  1. Wipe any stale `.litertlm` / `.ready` for the same id (we're
 *     restarting from scratch — Slice 2 does not support resume).
 *  2. Stream the HTTP body into `<id>.litertlm.tmp` with periodic
 *     progress reports.
 *  3. `rename(2)` the tmp file to the target — atomic on the same FS.
 *  4. Write the `.ready` sentinel last. This is the fsync-forcing
 *     step that [LiteRtModelStore.isDownloaded] gates on.
 *
 * Cancellation flips the worker's coroutine context cancelled; the
 * read loop checks `isActive` between buffers and aborts cleanly,
 * leaving the partial `.tmp` for a future resume-capable version.
 *
 * Network constraint (UNMETERED) is set by [LiteRtModelDownloader]
 * before enqueue, so this worker never starts on cellular.
 *
 * @param context Application context provided by WorkManager.
 * @param params Worker parameters (input data, run-attempt info).
 */
class LiteRtDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    /**
     * Foreground notification info shown while the download runs.
     * Marked as `DATA_SYNC` — single-pass model fetch, bounded by
     * the model size, well under the 6-hour per 24-hour cap.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        ensureNotificationChannel(applicationContext)
        val modelId = inputData.getString(KEY_MODEL_ID).orEmpty()
        val model = LiteRtModelCatalog.findById(modelId)
        val title =
            if (model != null) {
                "Downloading ${model.displayName}"
            } else {
                "Downloading on-device model"
            }
        return ForegroundInfo(
            NOTIFICATION_ID,
            buildNotification(title),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    /**
     * Resolves the requested variant, performs the download, and
     * commits the sentinel. Failures fall through to [Result.failure]
     * with a user-facing message in the output data.
     */
    @Suppress("TooGenericExceptionCaught")
    override suspend fun doWork(): Result {
        val modelId =
            inputData.getString(KEY_MODEL_ID)
                ?: return failed("Missing model id in worker input")
        val model =
            LiteRtModelCatalog.findById(modelId)
                ?: return failed("Unknown model id: $modelId")
        setForeground(getForegroundInfo())

        val store = LiteRtModelStore(applicationContext)
        val tmpFile = store.tmpFilePath(model)
        val targetFile = store.modelFileForWrite(model)
        val sentinelFile = store.readySentinelPath(model)
        // Clear any stale prior commit so a partial earlier success
        // can't pose as "Ready" once this fresh attempt fails.
        sentinelFile.delete()
        targetFile.delete()

        return try {
            downloadToTmp(model, tmpFile)
            if (!tmpFile.renameTo(targetFile)) {
                throw IOException("Atomic rename failed for ${model.id}")
            }
            writeAndSyncSentinel(sentinelFile)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed(e.message ?: "Download failed")
        }
    }

    /**
     * Writes the readiness sentinel and forces it to disk before
     * returning. `RandomAccessFile.getFD().sync()` flushes the file
     * contents through to storage so a post-rename power cut can't
     * leave the sentinel as a half-written ghost.
     *
     * Note: this does not fsync the *directory entry* — the rename
     * itself is atomic on the same filesystem (`rename(2)`), but its
     * durability still depends on the kernel's eventual writeback.
     * For Slice 2 we accept that a hard power cut between the
     * rename and the next directory writeback may require the user
     * to re-download. [LiteRtModelStore.isDownloaded] gates on the
     * sentinel's presence so partial-rename ghosts stay invisible.
     */
    private fun writeAndSyncSentinel(sentinel: File) {
        RandomAccessFile(sentinel, "rw").use { raf ->
            raf.write(System.currentTimeMillis().toString().toByteArray())
            raf.fd.sync()
        }
    }

    /**
     * Streams the HTTP body for [model] into [tmp], emitting a
     * `setProgress` update every [PROGRESS_REPORT_INTERVAL_BYTES] so
     * the picker can render a smooth bar without burning the
     * WorkManager data channel on per-buffer chatter.
     *
     * Cancellation: we register an `invokeOnCompletion` hook against
     * the active job so the OkHttp [Call] is closed as soon as the
     * worker's coroutine cancels. Without that hook,
     * `InputStream.read` blocks until the read timeout fires, making
     * the Cancel button look unresponsive for up to that timeout.
     */
    @Suppress("CognitiveComplexMethod", "NestedBlockDepth")
    private suspend fun downloadToTmp(
        model: LiteRtModel,
        tmp: File,
    ) {
        val client =
            OkHttpClient
                .Builder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
        val request = Request.Builder().url(model.downloadUrl).build()
        val call: Call = client.newCall(request)
        val cancelHandle =
            currentCoroutineContext()[kotlinx.coroutines.Job]?.invokeOnCompletion {
                if (!call.isCanceled()) call.cancel()
            }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code} from ${model.downloadUrl}")
                }
                val body = response.body ?: throw IOException("Empty response body")
                val totalBytes = body.contentLength().takeIf { it > 0L } ?: model.fileBytes
                setProgress(workDataOf(KEY_BYTES to 0L, KEY_TOTAL to totalBytes))
                body.byteStream().use { source ->
                    BufferedOutputStream(FileOutputStream(tmp)).use { sink ->
                        val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                        var written = 0L
                        var lastReport = 0L
                        while (currentCoroutineContext().isActive) {
                            val read = source.read(buffer)
                            if (read < 0) break
                            sink.write(buffer, 0, read)
                            written += read
                            if (written - lastReport >= PROGRESS_REPORT_INTERVAL_BYTES) {
                                setProgress(
                                    workDataOf(KEY_BYTES to written, KEY_TOTAL to totalBytes),
                                )
                                lastReport = written
                            }
                        }
                        sink.flush()
                    }
                }
            }
        } finally {
            cancelHandle?.dispose()
        }
    }

    /**
     * Returns a [Result.failure] carrying [reason] under [KEY_ERROR]
     * so the ViewModel can surface the message in the model card.
     */
    private fun failed(reason: String): Result = Result.failure(workDataOf(KEY_ERROR to reason))

    /**
     * Builds the foreground notification shown for the duration of
     * the download. Uses the project's monochrome `ic_notification`
     * drawable rather than the launcher foreground PNG — status-bar
     * icons must be alpha-only or they render as a white blob.
     */
    private fun buildNotification(title: String): Notification =
        NotificationCompat
            .Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText("Downloading model weights…")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    /**
     * Lazily registers the notification channel the foreground
     * notification posts to, once per process. WorkManager may call
     * `getForegroundInfo` multiple times during a worker's lifetime
     * (initial promotion plus optional refreshes); the
     * [channelCreated] flag avoids re-issuing the create-channel IPC
     * each time. `IMPORTANCE_LOW` keeps the notification quiet.
     */
    private fun ensureNotificationChannel(ctx: Context) {
        if (!channelCreated.compareAndSet(false, true)) return
        val manager =
            ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                "On-device model downloads",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Progress for LiteRT-LM model downloads." }
        manager.createNotificationChannel(channel)
    }

    /** Constants for [LiteRtDownloadWorker]. */
    companion object {
        /** WorkData key for the requested [LiteRtModel.id]. */
        const val KEY_MODEL_ID: String = "model_id"

        /** WorkData key for the bytes-downloaded progress value. */
        const val KEY_BYTES: String = "bytes"

        /** WorkData key for the total-bytes progress value. */
        const val KEY_TOTAL: String = "total"

        /** WorkData key for the user-facing failure reason. */
        const val KEY_ERROR: String = "error"

        /** Notification channel id used for the download foreground notif. */
        const val CHANNEL_ID: String = "litertlm_download"

        /**
         * Returns the unique work name for [modelId] so the enqueue
         * helper can dedupe concurrent triggers and observers can
         * watch the right slot.
         */
        fun uniqueWorkName(modelId: String): String = "litertlm_download_$modelId"

        /** Process-wide flag for one-time notification channel creation. */
        private val channelCreated: AtomicBoolean = AtomicBoolean(false)
    }
}
