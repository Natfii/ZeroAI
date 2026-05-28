/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data.repository

import android.util.Log
import com.zeroclaw.android.data.local.SeedData
import com.zeroclaw.android.data.local.dao.PluginDao
import com.zeroclaw.android.data.local.entity.PluginEntity
import com.zeroclaw.android.data.local.entity.toModel
import com.zeroclaw.android.model.AppSettings
import com.zeroclaw.android.model.OfficialPlugins
import com.zeroclaw.android.model.Plugin
import com.zeroclaw.android.model.RemotePlugin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Room-backed [PluginRepository] implementation.
 *
 * Delegates all persistence operations to [PluginDao] and maps between
 * entity and domain model layers. Config field updates merge the new
 * key into the existing JSON map.
 *
 * @param dao The data access object for plugin operations.
 */
class RoomPluginRepository(
    private val dao: PluginDao,
) : PluginRepository {
    private val json = Json { ignoreUnknownKeys = true }

    override val plugins: Flow<List<Plugin>> =
        dao.observeAll().map { entities -> entities.map { it.toModel() } }

    override suspend fun getById(id: String): Plugin? = dao.getById(id)?.toModel()

    override fun observeById(id: String): Flow<Plugin?> = dao.observeById(id).map { it?.toModel() }

    override suspend fun install(id: String) {
        dao.setInstalled(id)
    }

    override suspend fun uninstall(id: String) {
        require(!OfficialPlugins.isOfficial(id)) {
            "Official plugins cannot be uninstalled: $id"
        }
        dao.uninstall(id)
    }

    override suspend fun toggleEnabled(id: String) {
        dao.toggleEnabled(id)
    }

    /**
     * Updates a single configuration key for a plugin.
     *
     * **Security note**: this stores values in the Room database as plaintext JSON.
     * Secret values (API keys, tokens) must be stored in [EncryptedSharedPreferences]
     * via the [SettingsRepository] instead.
     */
    override suspend fun updateConfig(
        pluginId: String,
        key: String,
        value: String,
    ) {
        val entity = dao.getById(pluginId) ?: return
        val currentConfig: Map<String, String> =
            try {
                json.decodeFromString<Map<String, String>>(entity.configJson)
            } catch (e: SerializationException) {
                Log.w(TAG, "Corrupted config JSON for $pluginId, skipping update", e)
                return
            }
        val updatedConfig = currentConfig + (key to value)
        dao.updateConfigJson(pluginId, json.encodeToString(updatedConfig))
    }

    override suspend fun reconcileOfficialPlugins(settings: AppSettings) {
        val validIds = OfficialPlugins.ALL.toList()
        val existingIds = dao.getExistingIds(validIds).toSet()
        val missing =
            SeedData
                .seedPlugins()
                .filter { it.id in OfficialPlugins.ALL && it.id !in existingIds }
        val enabledStates =
            mapOf(
                OfficialPlugins.WEB_SEARCH to settings.webSearchEnabled,
                OfficialPlugins.WEB_FETCH to settings.webFetchEnabled,
                OfficialPlugins.HTTP_REQUEST to settings.httpRequestEnabled,
                OfficialPlugins.COMPOSIO to settings.composioEnabled,
                OfficialPlugins.VISION to true,
                OfficialPlugins.SHARED_FOLDER to settings.sharedFolderEnabled,
            )
        dao.reconcileOfficialRows(validIds, missing, enabledStates)
    }

    /** Constants for [RoomPluginRepository]. */
    companion object {
        private const val TAG = "PluginRepo"
    }

    override suspend fun mergeRemotePlugins(remotePlugins: List<RemotePlugin>) {
        if (remotePlugins.isEmpty()) return
        val existingIds = dao.getExistingIds(remotePlugins.map { it.id }).toSet()
        val (updates, inserts) = remotePlugins.partition { it.id in existingIds }

        dao.insertAllIgnoreConflicts(
            inserts.map { remote ->
                PluginEntity(
                    id = remote.id,
                    name = remote.name,
                    description = remote.description,
                    version = remote.version,
                    author = remote.author,
                    category = remote.category,
                    isInstalled = false,
                    isEnabled = false,
                    configJson = "{}",
                    remoteVersion = remote.version,
                )
            },
        )

        for (remote in updates) {
            dao.updateMetadata(
                id = remote.id,
                name = remote.name,
                description = remote.description,
                version = remote.version,
                author = remote.author,
                category = remote.category,
                remoteVersion = remote.version,
            )
        }
    }
}
