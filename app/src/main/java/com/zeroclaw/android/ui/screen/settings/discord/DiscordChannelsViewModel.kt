/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.settings.discord

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zeroclaw.android.ZeroAIApplication
import com.zeroclaw.android.data.DiscordDmLinkStore
import com.zeroclaw.android.data.discord.PendingDiscordOpsStore
import com.zeroclaw.android.data.local.discord.DiscordChannelConfigEntity
import com.zeroclaw.android.data.validation.ChannelValidator
import com.zeroclaw.android.data.validation.ValidationResult
import com.zeroclaw.android.model.ChannelType
import com.zeroclaw.android.model.ConnectedChannel
import com.zeroclaw.ffi.discordConfigureChannel
import com.zeroclaw.ffi.discordFetchBotGuilds
import com.zeroclaw.ffi.discordFetchGuildChannels
import com.zeroclaw.ffi.discordRemoveChannel
import com.zeroclaw.ffi.discordValidateUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UI state for the Discord channels management screen.
 *
 * @property dmUser Linked DM user info (username displayed).
 * @property channels Currently configured archive channels.
 * @property isLoading Whether a background operation is in progress.
 * @property error Error message to display, or null.
 */
data class DiscordChannelsUiState(
    val dmUser: String? = null,
    val channels: List<DiscordChannelConfigEntity> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

/**
 * Represents a guild channel fetched from the Discord API for the picker.
 *
 * @property id Discord channel snowflake ID.
 * @property name Human-readable channel name.
 * @property type Numeric Discord channel type (0 = text, 2 = voice, etc.).
 */
data class GuildChannel(
    val id: String,
    val name: String,
    val type: Int,
)

/**
 * Represents a Discord guild (server) the bot is a member of.
 *
 * @property id Guild snowflake ID.
 * @property name Human-readable guild name.
 * @property icon Guild icon hash, or null if no icon is set.
 */
data class DiscordGuild(
    val id: String,
    val name: String,
    val icon: String? = null,
)

/**
 * Available backfill depth options for a new archive channel.
 *
 * @property label Human-readable display label.
 * @property value TOML value persisted in the channel config.
 */
enum class BackfillDepth(
    val label: String,
    val value: String,
) {
    /** No historical backfill. */
    NONE("None", "none"),

    /** Backfill the last 3 days. */
    THREE_DAYS("3 days", "3d"),

    /** Backfill the last 7 days. */
    SEVEN_DAYS("7 days", "7d"),

    /** Backfill the last 30 days. */
    THIRTY_DAYS("30 days", "30d"),

    /** Backfill the last 90 days. */
    NINETY_DAYS("90 days", "90d"),

    /** Backfill all available history. */
    ALL("All history", "all"),
}

/**
 * Token validation lifecycle state.
 */
sealed interface TokenValidationState {
    /** No validation in progress. */
    data object Idle : TokenValidationState

    /** Validation HTTP request in flight. */
    data object Validating : TokenValidationState

    /**
     * Token validated successfully.
     *
     * @property botName Display name of the authenticated bot.
     */
    data class Success(
        val botName: String,
    ) : TokenValidationState

    /**
     * Token validation failed.
     *
     * @property message Human-readable error description.
     */
    data class Error(
        val message: String,
    ) : TokenValidationState
}

/**
 * ViewModel for the Discord archive channel management screen.
 *
 * Loads channel configuration from the Rust-managed archive database and
 * bridges Discord management actions to the existing UniFFI exports.
 *
 * @param application Application context for accessing databases and repositories.
 */
@Suppress("TooGenericExceptionCaught")
class DiscordChannelsViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val app = application as ZeroAIApplication
    private val channelConfigRepository = app.channelConfigRepository

    private val _uiState = MutableStateFlow(DiscordChannelsUiState())

    /** Observable UI state for the Discord channels screen. */
    val uiState: StateFlow<DiscordChannelsUiState> = _uiState.asStateFlow()

    private val _guildChannels = MutableStateFlow<List<GuildChannel>>(emptyList())

    /** Guild channels fetched from the Discord API for the picker bottom sheet. */
    val guildChannels: StateFlow<List<GuildChannel>> = _guildChannels.asStateFlow()

    private val _botGuilds = MutableStateFlow<List<DiscordGuild>>(emptyList())

    /** Guilds the bot is a member of, fetched from the Discord API. */
    val botGuilds: StateFlow<List<DiscordGuild>> = _botGuilds.asStateFlow()

    private val _tokenValidation =
        MutableStateFlow<TokenValidationState>(TokenValidationState.Idle)

    /** Observable token validation state. */
    val tokenValidation: StateFlow<TokenValidationState> = _tokenValidation.asStateFlow()

    init {
        loadChannels()
    }

    /**
     * Loads archive channel configurations from the Room database.
     *
     * Falls back gracefully when the archive database does not exist yet
     * (the Rust daemon has not created it).
     */
    fun loadChannels() {
        viewModelScope.launch(Dispatchers.IO) {
            loadChannelsInternal()
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun loadChannelsInternal() {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        val linkedDmUser =
            try {
                DiscordDmLinkStore.read(app)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read DM link", e)
                null
            }
        try {
            val db = app.openDiscordArchive()
            if (db == null) {
                _uiState.value =
                    _uiState.value.copy(
                        dmUser = linkedDmUser?.username,
                        channels = emptyList(),
                        isLoading = false,
                    )
                return
            }
            val configs = db.messageDao().getAllChannelConfigs()
            _uiState.value =
                _uiState.value.copy(
                    dmUser = linkedDmUser?.username,
                    channels = configs,
                    isLoading = false,
                )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load channel configs", e)
            _uiState.value =
                _uiState.value.copy(
                    dmUser = linkedDmUser?.username,
                    channels = emptyList(),
                    isLoading = false,
                    error = "Failed to load channels",
                )
        }
    }

    /**
     * Adds a new archive channel via FFI and reloads the list.
     *
     * When the daemon is not running, the operation is queued in
     * [PendingDiscordOpsStore] and replayed on the next daemon start.
     *
     * @param channelId Discord channel snowflake ID.
     * @param guildId Discord guild snowflake ID.
     * @param name Human-readable channel name.
     * @param backfillDepth Backfill depth configuration value.
     * @param flagRestart Whether to flag a daemon restart after success.
     */
    @Suppress("TooGenericExceptionCaught")
    fun addChannel(
        channelId: String,
        guildId: String,
        name: String,
        backfillDepth: String,
        flagRestart: Boolean = false,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                discordConfigureChannel(channelId, guildId, name, backfillDepth)
            } catch (e: com.zeroclaw.ffi.FfiException.StateException) {
                Log.w(TAG, "Daemon offline, queuing add: ${e.message}")
                PendingDiscordOpsStore.enqueueAdd(
                    app,
                    channelId,
                    guildId,
                    name,
                    backfillDepth,
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add channel", e)
                _uiState.value =
                    _uiState.value.copy(
                        isLoading = false,
                        error = uiError("Unable to add channel", e),
                    )
                return@launch
            }
            val newChannel =
                DiscordChannelConfigEntity(
                    channelId = channelId,
                    guildId = guildId,
                    channelName = name,
                    backfillDepth = backfillDepth,
                    enabled = 1,
                )
            _uiState.value =
                _uiState.value.copy(
                    channels = _uiState.value.channels + newChannel,
                    isLoading = false,
                )
            if (flagRestart) {
                (app as? ZeroAIApplication)?.daemonBridge?.markRestartRequired()
            }
        }
    }

    /**
     * Removes an archive channel via FFI and reloads the list.
     *
     * When the daemon is not running, the operation is queued in
     * [PendingDiscordOpsStore] and replayed on the next daemon start.
     *
     * @param channelId Discord channel snowflake ID to remove.
     * @param flagRestart Whether to flag a daemon restart after success.
     */
    @Suppress("TooGenericExceptionCaught")
    fun removeChannel(
        channelId: String,
        flagRestart: Boolean = false,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                discordRemoveChannel(channelId)
            } catch (e: com.zeroclaw.ffi.FfiException.StateException) {
                Log.w(TAG, "Daemon offline, queuing remove: ${e.message}")
                PendingDiscordOpsStore.enqueueRemove(app, channelId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove channel", e)
                _uiState.value =
                    _uiState.value.copy(
                        isLoading = false,
                        error = uiError("Unable to remove channel", e),
                    )
                return@launch
            }
            _uiState.value =
                _uiState.value.copy(
                    channels =
                        _uiState.value.channels.filter {
                            it.channelId != channelId
                        },
                    isLoading = false,
                )
            if (flagRestart) {
                (app as? ZeroAIApplication)?.daemonBridge?.markRestartRequired()
            }
        }
    }

    /**
     * Links a DM user by validating via FFI, then persisting the link.
     *
     * @param botToken Discord bot token for API calls.
     * @param userId Discord user snowflake ID to link.
     */
    @Suppress("TooGenericExceptionCaught")
    fun linkDmUser(
        botToken: String,
        userId: String,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val validatedUser = discordValidateUser(botToken, userId.trim())
                DiscordDmLinkStore.save(
                    app = app,
                    userId = validatedUser.id,
                    username = validatedUser.username,
                    avatarUrl = validatedUser.avatarUrl,
                )
                try {
                    DiscordDmLinkStore.syncRuntime(app)
                } catch (e: com.zeroclaw.ffi.FfiException.StateException) {
                    Log.w(TAG, "Daemon not running, DM link will sync on next start")
                }
                _uiState.value =
                    _uiState.value.copy(
                        dmUser = validatedUser.username,
                        isLoading = false,
                    )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to link DM user", e)
                _uiState.value =
                    _uiState.value.copy(
                        isLoading = false,
                        error = uiError("Unable to link DM user", e, "add a Discord bot first"),
                    )
            }
        }
    }

    /**
     * Removes the persisted Discord DM link and clears the native runtime state.
     */
    @Suppress("TooGenericExceptionCaught")
    fun unlinkDmUser() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                DiscordDmLinkStore.clear(app)
                try {
                    DiscordDmLinkStore.syncRuntime(app)
                } catch (e: com.zeroclaw.ffi.FfiException.StateException) {
                    Log.w(TAG, "Daemon not running, DM link will sync on next start")
                }
                _uiState.value =
                    _uiState.value.copy(
                        dmUser = null,
                        isLoading = false,
                    )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unlink DM user", e)
                _uiState.value =
                    _uiState.value.copy(
                        isLoading = false,
                        error = uiError("Unable to unlink DM user", e, "add a Discord bot first"),
                    )
            }
        }
    }

    /**
     * Fetches guild text channels from the Discord API for the channel picker.
     *
     * @param botToken Discord bot token for API calls.
     * @param guildId Discord guild snowflake ID.
     */
    @Suppress("TooGenericExceptionCaught")
    fun fetchGuildChannels(
        botToken: String,
        guildId: String,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                _guildChannels.value =
                    discordFetchGuildChannels(botToken, guildId)
                        .map { channel ->
                            GuildChannel(
                                id = channel.id,
                                name = channel.name,
                                type = channel.channelType,
                            )
                        }.sortedBy { it.name.lowercase() }
                _uiState.value = _uiState.value.copy(isLoading = false)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch guild channels", e)
                _uiState.value =
                    _uiState.value.copy(
                        isLoading = false,
                        error = uiError("Unable to fetch guild channels", e),
                    )
            }
        }
    }

    /**
     * Fetches the guilds the bot is a member of from the Discord API.
     *
     * Results are emitted to [botGuilds] for the guild picker bottom sheet.
     *
     * @param botToken Discord bot token for API calls.
     */
    @Suppress("TooGenericExceptionCaught")
    fun fetchBotGuilds(botToken: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                _botGuilds.value =
                    discordFetchBotGuilds(botToken)
                        .map { guild ->
                            DiscordGuild(
                                id = guild.id,
                                name = guild.name,
                                icon = guild.icon,
                            )
                        }.sortedBy { it.name.lowercase() }
                _uiState.value = _uiState.value.copy(isLoading = false)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch bot guilds", e)
                _uiState.value =
                    _uiState.value.copy(
                        isLoading = false,
                        error = uiError("Unable to fetch guilds", e),
                    )
            }
        }
    }

    /**
     * Resolves the bot token from the existing Discord channel configuration.
     *
     * Guild ID is no longer required here because the archive screen
     * discovers guilds via `GET /users/@me/guilds` instead.
     *
     * @return The bot token, or null if no Discord channel is configured.
     */
    suspend fun resolveDiscordBotToken(): String? {
        val channelsWithSecrets = channelConfigRepository.getEnabledWithSecrets()
        val discord =
            channelsWithSecrets.firstOrNull { (channel, _) ->
                channel.type == ChannelType.DISCORD
            } ?: return null

        val (_, mergedValues) = discord
        val botToken = mergedValues["bot_token"]

        return if (botToken.isNullOrBlank()) null else botToken
    }

    /**
     * Validates a Discord bot token against the Discord REST API.
     *
     * Updates [tokenValidation] through the Idle -> Validating -> Success/Error
     * lifecycle. Does not persist the token -- call [saveBotToken] after success.
     *
     * @param token The bot token to validate.
     */
    fun validateBotToken(token: String) {
        if (token.isBlank()) {
            _tokenValidation.value = TokenValidationState.Error("Token cannot be empty")
            return
        }
        _tokenValidation.value = TokenValidationState.Validating
        viewModelScope.launch(Dispatchers.IO) {
            val result =
                ChannelValidator.validate(
                    ChannelType.DISCORD,
                    mapOf("bot_token" to token),
                )
            _tokenValidation.value =
                when (result) {
                    is ValidationResult.Success ->
                        TokenValidationState.Success(result.details)
                    is ValidationResult.Failure ->
                        TokenValidationState.Error(result.message)
                    is ValidationResult.Offline ->
                        TokenValidationState.Error(result.message)
                    else -> TokenValidationState.Error("Unexpected validation state")
                }
        }
    }

    /**
     * Persists the validated bot token via [channelConfigRepository].
     *
     * Creates a new Discord [ConnectedChannel] if none exists, or updates
     * the existing one. If [flagRestart] is `true`, marks the daemon for
     * restart so the dashboard shows a nudge.
     *
     * @param token The validated bot token.
     * @param flagRestart Whether to flag a daemon restart.
     */
    @Suppress("TooGenericExceptionCaught")
    fun saveBotToken(
        token: String,
        flagRestart: Boolean,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val existing =
                    channelConfigRepository
                        .getEnabledWithSecrets()
                        .firstOrNull { (ch, _) -> ch.type == ChannelType.DISCORD }

                val channel =
                    existing?.first
                        ?: ConnectedChannel(
                            id =
                                java.util.UUID
                                    .randomUUID()
                                    .toString(),
                            type = ChannelType.DISCORD,
                            isEnabled = true,
                            configValues = emptyMap(),
                        )
                channelConfigRepository.save(channel, mapOf("bot_token" to token))

                if (flagRestart) {
                    (app as? ZeroAIApplication)?.daemonBridge?.markRestartRequired()
                }
                loadChannelsInternal()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save bot token", e)
                _uiState.value =
                    _uiState.value.copy(
                        error = "Failed to save bot token",
                    )
            }
        }
    }

    /** Clears the current error message. */
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    /** Constants for [DiscordChannelsViewModel]. */
    companion object {
        private const val TAG = "DiscordChannelsVM"
    }

    private fun uiError(
        fallback: String,
        exception: Exception,
        illegalStateMessage: String = "start the daemon first",
    ): String =
        when (exception) {
            is IllegalArgumentException -> "$fallback: invalid input"
            is IllegalStateException -> "$fallback: $illegalStateMessage"
            else -> fallback
        }
}
