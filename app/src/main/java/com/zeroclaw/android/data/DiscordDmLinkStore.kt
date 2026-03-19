/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.data

import com.zeroclaw.android.ZeroAIApplication
import com.zeroclaw.android.data.repository.ChannelConfigRepository
import com.zeroclaw.android.model.ChannelType
import com.zeroclaw.android.model.ConnectedChannel
import com.zeroclaw.ffi.discordLinkDmUser
import com.zeroclaw.ffi.discordUnlinkDmUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Persisted Discord DM-link metadata owned by the Kotlin channel store.
 *
 * @property userId Discord user snowflake ID linked for DM routing.
 * @property username Human-readable username shown in the UI.
 * @property avatarUrl Optional avatar URL cached from validation time.
 */
data class PersistedDiscordDmLink(
    val userId: String,
    val username: String,
    val avatarUrl: String?,
)

/**
 * Persists and replays the Discord DM-link selection.
 *
 * The Rust daemon only keeps the linked DM user in process memory, so the
 * Android layer stores the durable source of truth alongside the existing
 * Discord connected-channel row and replays it into UniFFI whenever needed.
 */
object DiscordDmLinkStore {
    private const val KEY_DM_USER_ID = "dm_user_id"
    private const val KEY_DM_USERNAME = "dm_username"
    private const val KEY_DM_AVATAR_URL = "dm_avatar_url"

    /**
     * Reads the persisted Discord DM link from channel storage.
     *
     * @param app Application instance providing repository access.
     * @return Persisted DM-link metadata, or null when no link exists.
     */
    suspend fun read(app: ZeroAIApplication): PersistedDiscordDmLink? =
        withContext(Dispatchers.IO) {
            read(app.channelConfigRepository)
        }

    /**
     * Saves the linked Discord DM user in the connected-channel store.
     *
     * Existing non-secret Discord channel config is preserved. Secret values
     * are read back and re-saved unchanged through the channel repository.
     *
     * @param app Application instance providing repository access.
     * @param userId Discord user snowflake ID.
     * @param username Display username for the settings UI.
     * @param avatarUrl Optional avatar URL returned by validation.
     * @throws IllegalStateException when no Discord channel is configured.
     */
    suspend fun save(
        app: ZeroAIApplication,
        userId: String,
        username: String,
        avatarUrl: String?,
    ) = withContext(Dispatchers.IO) {
        updateDiscordChannel(app.channelConfigRepository) { channel ->
            val updatedConfig =
                channel.configValues
                    .plus(KEY_DM_USER_ID to userId)
                    .plus(KEY_DM_USERNAME to username.ifBlank { userId })

            channel.copy(
                configValues =
                    if (avatarUrl.isNullOrBlank()) {
                        updatedConfig - KEY_DM_AVATAR_URL
                    } else {
                        updatedConfig + (KEY_DM_AVATAR_URL to avatarUrl)
                    },
            )
        }
    }

    /**
     * Clears any persisted Discord DM link from channel storage.
     *
     * @param app Application instance providing repository access.
     */
    suspend fun clear(app: ZeroAIApplication) =
        withContext(Dispatchers.IO) {
            val repository = app.channelConfigRepository
            val channel = findDiscordChannel(repository) ?: return@withContext
            val secrets = repository.getSecrets(channel.id)
            repository.save(
                channel.copy(
                    configValues =
                        channel.configValues -
                            setOf(KEY_DM_USER_ID, KEY_DM_USERNAME, KEY_DM_AVATAR_URL),
                ),
                secrets,
            )
        }

    /**
     * Synchronizes the persisted DM link into the native Discord runtime state.
     *
     * When no persisted link exists, the native runtime is explicitly cleared
     * so stale in-process state cannot survive a previous session.
     *
     * @param app Application instance providing repository access.
     */
    suspend fun syncRuntime(app: ZeroAIApplication) =
        withContext(Dispatchers.IO) {
            val linkedUser = read(app.channelConfigRepository)
            if (linkedUser == null) {
                discordUnlinkDmUser()
            } else {
                discordLinkDmUser(linkedUser.userId)
            }
        }

    private suspend fun read(repository: ChannelConfigRepository): PersistedDiscordDmLink? {
        val channel = findDiscordChannel(repository) ?: return null
        val userId = channel.configValues[KEY_DM_USER_ID]?.takeIf { it.isNotBlank() } ?: return null
        return PersistedDiscordDmLink(
            userId = userId,
            username = channel.configValues[KEY_DM_USERNAME]?.takeIf { it.isNotBlank() } ?: userId,
            avatarUrl = channel.configValues[KEY_DM_AVATAR_URL]?.takeIf { it.isNotBlank() },
        )
    }

    private suspend fun updateDiscordChannel(
        repository: ChannelConfigRepository,
        transform: (ConnectedChannel) -> ConnectedChannel,
    ) {
        val channel =
            checkNotNull(findDiscordChannel(repository)) {
                "Discord channel is not configured"
            }
        val secrets = repository.getSecrets(channel.id)
        repository.save(transform(channel), secrets)
    }

    private suspend fun findDiscordChannel(repository: ChannelConfigRepository): ConnectedChannel? =
        repository.channels.first().firstOrNull { channel ->
            channel.type == ChannelType.DISCORD
        }
}
