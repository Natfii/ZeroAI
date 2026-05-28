/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.service.ondevice

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.zeroclaw.android.model.LiteRtModel
import com.zeroclaw.android.model.LiteRtModelCatalog
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Tracks the user's selected on-device LiteRT-LM variant + whether
 * the corresponding `.litertlm` file is present on disk.
 *
 * Persists the selection via DataStore so it survives process death
 * and onboarding re-runs. Backed by [Context.filesDir]: model files
 * land at `filesDir/models/<modelId>.litertlm`. The downloader is
 * expected to atomically rename a `.tmp` file into place once a
 * download completes successfully.
 */
private val Context.liteRtDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "litert_model",
)

/** Preference key storing the user-selected variant id. */
private val SELECTED_MODEL_KEY = stringPreferencesKey("selected_model_id")

/**
 * Subdirectory under `filesDir` that holds downloaded `.litertlm`
 * files. Created lazily by [LiteRtModelStore.modelFile].
 */
private const val MODELS_SUBDIR = "models"

/** File extension used for downloaded LiteRT-LM model artifacts. */
private const val LITERTLM_EXTENSION = ".litertlm"

/**
 * Suffix for the readiness sentinel companion file. Written after the
 * atomic `.tmp` → `.litertlm` rename completes; its presence indicates
 * the download passed the rename-gap and is safe to load.
 */
private const val READY_SENTINEL_SUFFIX = ".ready"

/** Suffix used for the partial-download file during a fetch. */
private const val TMP_SUFFIX = ".tmp"

/**
 * Storage gateway for the on-device large model setup screen.
 *
 * Exposes:
 *  - the currently selected variant (defaults to [LiteRtModelCatalog.Gemma4E2B])
 *  - whether the selected variant's `.litertlm` file exists on disk
 *  - mutation methods used by the ViewModel + downloader
 *
 * Kept stateless beyond the DataStore handle so multiple callers can
 * share a single instance without leasing logic; both
 * `OnDeviceLargeModelViewModel` and the future
 * `LiteRtDownloadWorker` should construct one against the application
 * context.
 *
 * @param context Application context for DataStore + filesDir access.
 */
class LiteRtModelStore(
    private val context: Context,
) {
    /**
     * Flow of the currently selected [LiteRtModel]. Falls back to
     * [LiteRtModelCatalog.Gemma4E2B] when no selection is persisted
     * (fresh installs land on the recommended default).
     */
    val selectedModel: Flow<LiteRtModel> =
        context.liteRtDataStore.data.map { prefs ->
            val id = prefs[SELECTED_MODEL_KEY]
            id?.let(LiteRtModelCatalog::findById) ?: LiteRtModelCatalog.Gemma4E2B
        }

    /**
     * Persists [model] as the user's selected variant. Idempotent:
     * writing the same id twice produces only one DataStore edit.
     *
     * @param model The variant the user just picked.
     */
    suspend fun setSelectedModel(model: LiteRtModel) {
        context.liteRtDataStore.edit { prefs ->
            prefs[SELECTED_MODEL_KEY] = model.id
        }
    }

    /**
     * Returns the `File` the downloader should populate for [model],
     * creating the parent `filesDir/models/` directory on demand.
     * Use this from write paths (download, verify, rename). Read
     * paths should use [modelFilePath] so a status query doesn't
     * leak an empty directory on first install.
     *
     * @param model Variant whose model file to locate.
     * @return Writeable path; may not yet exist.
     */
    fun modelFileForWrite(model: LiteRtModel): File {
        val dir = File(context.filesDir, MODELS_SUBDIR)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "${model.id}$LITERTLM_EXTENSION")
    }

    /**
     * Returns the would-be path for [model] without creating any
     * directories. Safe to call from any read path, including the
     * picker's per-refresh status probe.
     *
     * @param model Variant whose model file to locate.
     * @return Path under `filesDir/models/`. Parent may not exist.
     */
    fun modelFilePath(model: LiteRtModel): File = File(File(context.filesDir, MODELS_SUBDIR), "${model.id}$LITERTLM_EXTENSION")

    /**
     * Returns `true` when [model]'s `.litertlm` file is on disk AND
     * its readiness sentinel exists.
     *
     * The downloader writes `<id>.litertlm.tmp` → `rename(2)`s it to
     * `<id>.litertlm` → writes `<id>.litertlm.ready` last. The
     * sentinel is the final fsync-forcing write, so its presence
     * guarantees the rename committed and the binary is durable.
     * A power loss between the rename and the sentinel write leaves
     * the model file orphaned but unmarked, and [isDownloaded]
     * returns `false` until the user re-downloads.
     *
     * @param model Variant to check.
     */
    fun isDownloaded(model: LiteRtModel): Boolean {
        val file = modelFilePath(model)
        val sentinel = readySentinelPath(model)
        return file.exists() && file.length() > 0L && sentinel.exists()
    }

    /**
     * Returns the path of the readiness sentinel companion file.
     * The downloader writes this AFTER the atomic rename so its
     * presence is what [isDownloaded] gates on.
     *
     * @param model Variant whose sentinel to locate.
     */
    fun readySentinelPath(model: LiteRtModel): File =
        File(
            File(context.filesDir, MODELS_SUBDIR),
            "${model.id}$LITERTLM_EXTENSION$READY_SENTINEL_SUFFIX",
        )

    /**
     * Returns the path of the in-progress `.tmp` file the downloader
     * writes before rename. Read paths shouldn't normally consult
     * this, but the delete flow needs to wipe it alongside the
     * primary file + sentinel.
     *
     * @param model Variant whose tmp file to locate.
     */
    fun tmpFilePath(model: LiteRtModel): File =
        File(
            File(context.filesDir, MODELS_SUBDIR),
            "${model.id}$LITERTLM_EXTENSION$TMP_SUFFIX",
        )

    /**
     * Outcome of a [deleteModel] call.
     *
     * @property removedFiles Paths the call actually deleted.
     * @property orphanedFiles Paths the call tried but failed to
     *   delete (e.g. the daemon currently has the model `mmap`'d).
     *   The caller should surface this so the user understands why
     *   their freed-space estimate is off.
     */
    data class DeleteResult(
        val removedFiles: List<File>,
        val orphanedFiles: List<File>,
    )

    /**
     * Deletes every artifact associated with [model] — the primary
     * `.litertlm`, the readiness sentinel, and any leftover `.tmp`
     * from an interrupted download. Safe to call when no files
     * exist; each missing path is silently skipped. Files whose
     * `delete()` returns `false` (e.g. an `mmap`-pinned model held
     * by the running daemon) are reported back as
     * [DeleteResult.orphanedFiles] so callers can warn the user
     * instead of silently leaving phantoms.
     *
     * @param model Variant to delete from disk.
     * @return [DeleteResult] describing the removed and orphaned paths.
     */
    fun deleteModel(model: LiteRtModel): DeleteResult {
        val removed = mutableListOf<File>()
        val orphaned = mutableListOf<File>()
        val targets = listOf(modelFilePath(model), readySentinelPath(model), tmpFilePath(model))
        for (target in targets) {
            if (!target.exists()) continue
            if (target.delete()) removed += target else orphaned += target
        }
        return DeleteResult(removedFiles = removed, orphanedFiles = orphaned)
    }
}
