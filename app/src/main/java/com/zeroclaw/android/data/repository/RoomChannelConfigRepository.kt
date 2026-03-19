/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data.repository

import android.content.SharedPreferences
import com.zeroclaw.android.data.local.dao.ConnectedChannelDao
import com.zeroclaw.android.data.local.entity.toEntity
import com.zeroclaw.android.data.local.entity.toModel
import com.zeroclaw.android.model.ChannelType
import com.zeroclaw.android.model.ConnectedChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * [ChannelConfigRepository] backed by Room for non-secret config and
 * EncryptedSharedPreferences for secret values.
 *
 * Secrets are keyed as `"channel_{channelId}_{fieldKey}"` in the encrypted
 * preferences file to avoid collisions and enable per-channel cleanup.
 *
 * @param dao Room DAO for connected channel entities.
 * @param securePrefs EncryptedSharedPreferences instance for secret storage.
 */
class RoomChannelConfigRepository(
    private val dao: ConnectedChannelDao,
    private val securePrefs: SharedPreferences,
) : ChannelConfigRepository {
    override val channels: Flow<List<ConnectedChannel>> =
        dao.observeAll().map { entities ->
            entities.mapNotNull { it.toModel() }
        }

    override suspend fun getById(id: String): ConnectedChannel? = dao.getById(id)?.toModel()

    override suspend fun existsForType(type: ChannelType): Boolean = dao.getByType(type.name) != null

    override suspend fun save(
        channel: ConnectedChannel,
        secrets: Map<String, String>,
    ) {
        if (secrets.isNotEmpty()) {
            val editor = securePrefs.edit()
            secrets.forEach { (key, value) ->
                editor.putString(secretKey(channel.id, key), value)
            }
            check(editor.commit()) {
                "Encrypted storage unavailable: unable to persist channel secrets"
            }
        }
        dao.upsert(channel.toEntity())
    }

    @Suppress("NestedBlockDepth")
    override suspend fun delete(id: String) {
        val entity = dao.getById(id)
        entity?.toModel()?.let { model ->
            deleteSecretsFor(
                id = id,
                fieldKeys =
                    model.type.fields
                        .filter { it.isSecret }
                        .map { it.key },
            )
        }
        dao.deleteById(id)
    }

    override suspend fun toggleEnabled(id: String) {
        dao.toggleEnabled(id)
    }

    override fun getSecrets(channelId: String): Map<String, String> {
        val all = securePrefs.all
        val prefix = "channel_${channelId}_"
        return all.entries
            .filter { it.key.startsWith(prefix) }
            .associate { (key, value) ->
                key.removePrefix(prefix) to (value as? String).orEmpty()
            }
    }

    override suspend fun getEnabledWithSecrets(): List<Pair<ConnectedChannel, Map<String, String>>> {
        val enabledChannels = dao.getAllEnabled().mapNotNull { it.toModel() }
        val allSecrets = securePrefs.all
        return enabledChannels.map { channel ->
            val prefix = "channel_${channel.id}_"
            val secrets =
                allSecrets.entries
                    .filter { it.key.startsWith(prefix) }
                    .associate { (key, value) ->
                        key.removePrefix(prefix) to (value as? String).orEmpty()
                    }
            val merged = channel.configValues + secrets
            channel to merged
        }
    }

    /**
     * Builds the encrypted preferences key for a secret field.
     *
     * @param channelId Channel identifier.
     * @param fieldKey Field key from the channel type spec.
     * @return Composite key string.
     */
    private fun secretKey(
        channelId: String,
        fieldKey: String,
    ): String = "channel_${channelId}_$fieldKey"

    /**
     * Removes encrypted secret entries for the given channel field keys.
     *
     * @param id Channel identifier.
     * @param fieldKeys Secret field keys to delete.
     */
    private fun deleteSecretsFor(
        id: String,
        fieldKeys: List<String>,
    ) {
        if (fieldKeys.isEmpty()) return
        val editor = securePrefs.edit()
        fieldKeys.forEach { fieldKey ->
            editor.remove(secretKey(id, fieldKey))
        }
        check(editor.commit()) {
            "Encrypted storage unavailable: unable to delete channel secrets"
        }
    }
}
