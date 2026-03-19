/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.request.crossfade
import com.zeroclaw.android.data.SecurePrefsProvider
import com.zeroclaw.android.data.StorageHealth
import com.zeroclaw.android.data.email.EmailConfigRepository
import com.zeroclaw.android.data.local.ZeroAIDatabase
import com.zeroclaw.android.data.local.discord.DiscordArchiveDatabase
import com.zeroclaw.android.data.oauth.AuthProfileStore
import com.zeroclaw.android.data.oauth.AuthProfileWriter
import com.zeroclaw.android.data.oauth.repairManagedProviderState
import com.zeroclaw.android.data.repository.ActivityRepository
import com.zeroclaw.android.data.repository.AgentRepository
import com.zeroclaw.android.data.repository.ApiKeyRepository
import com.zeroclaw.android.data.repository.ChannelConfigRepository
import com.zeroclaw.android.data.repository.DataStoreOnboardingRepository
import com.zeroclaw.android.data.repository.DataStoreSettingsRepository
import com.zeroclaw.android.data.repository.EncryptedApiKeyRepository
import com.zeroclaw.android.data.repository.EstopRepository
import com.zeroclaw.android.data.repository.InMemoryApiKeyRepository
import com.zeroclaw.android.data.repository.LogRepository
import com.zeroclaw.android.data.repository.OnboardingRepository
import com.zeroclaw.android.data.repository.PluginRepository
import com.zeroclaw.android.data.repository.RoomActivityRepository
import com.zeroclaw.android.data.repository.RoomAgentRepository
import com.zeroclaw.android.data.repository.RoomChannelConfigRepository
import com.zeroclaw.android.data.repository.RoomLogRepository
import com.zeroclaw.android.data.repository.RoomPluginRepository
import com.zeroclaw.android.data.repository.RoomTerminalEntryRepository
import com.zeroclaw.android.data.repository.SettingsRepository
import com.zeroclaw.android.data.repository.TerminalEntryRepository
import com.zeroclaw.android.model.RefreshCommand
import com.zeroclaw.android.model.ServiceState
import com.zeroclaw.android.service.CostBridge
import com.zeroclaw.android.service.CredentialBridge
import com.zeroclaw.android.service.CronBridge
import com.zeroclaw.android.service.DaemonServiceBridge
import com.zeroclaw.android.service.EventBridge
import com.zeroclaw.android.service.HealthBridge
import com.zeroclaw.android.service.MemoryBridge
import com.zeroclaw.android.service.PluginSyncWorker
import com.zeroclaw.android.service.SkillsBridge
import com.zeroclaw.android.service.ToolsBridge
import com.zeroclaw.android.service.VisionBridge
import com.zeroclaw.android.util.SessionLockManager
import com.zeroclaw.ffi.getVersion
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient

/**
 * Application subclass that initialises the native ZeroAI library and
 * shared service components.
 *
 * The native library is loaded once during process creation so that every
 * component can call FFI functions without additional setup. Shared
 * singletons are created here and available for the lifetime of the process.
 *
 * Persistent data is stored in a Room database ([ZeroAIDatabase]) that
 * survives process restarts. Settings and API keys remain in DataStore
 * and EncryptedSharedPreferences respectively.
 */
class ZeroAIApplication :
    Application(),
    SingletonImageLoader.Factory {
    /**
     * Shared bridge between the Android service layer and the Rust FFI.
     *
     * Initialised in [onCreate] and available for the lifetime of the process.
     * Access from [ZeroAIDaemonService][com.zeroclaw.android.service.ZeroAIDaemonService]
     * and [DaemonViewModel][com.zeroclaw.android.viewmodel.DaemonViewModel].
     */
    lateinit var daemonBridge: DaemonServiceBridge
        private set

    /** Room database instance for agents, plugins, logs, and activity events. */
    lateinit var database: ZeroAIDatabase
        private set

    /** Application settings repository backed by Jetpack DataStore. */
    lateinit var settingsRepository: SettingsRepository
        private set

    /** API key repository backed by EncryptedSharedPreferences. */
    lateinit var apiKeyRepository: ApiKeyRepository
        private set

    /** Log repository backed by Room with automatic pruning. */
    lateinit var logRepository: LogRepository
        private set

    /** Activity feed repository backed by Room with automatic pruning. */
    lateinit var activityRepository: ActivityRepository
        private set

    /** Onboarding state repository backed by Jetpack DataStore. */
    lateinit var onboardingRepository: OnboardingRepository
        private set

    /** Agent repository backed by Room. */
    lateinit var agentRepository: AgentRepository
        private set

    /** Plugin repository backed by Room. */
    lateinit var pluginRepository: PluginRepository
        private set

    /** Channel configuration repository backed by Room + EncryptedSharedPreferences. */
    lateinit var channelConfigRepository: ChannelConfigRepository
        private set

    /** Email configuration repository backed by Room + EncryptedSharedPreferences. */
    lateinit var emailConfigRepository: EmailConfigRepository
        private set

    /** Terminal REPL entry repository backed by Room. */
    lateinit var terminalEntryRepository: TerminalEntryRepository
        private set

    /** Emergency stop state repository. */
    lateinit var estopRepository: EstopRepository
        private set

    /** Bridge for structured health detail FFI calls. */
    lateinit var healthBridge: HealthBridge
        private set

    /** Bridge for cost-tracking FFI calls. */
    lateinit var costBridge: CostBridge
        private set

    /** Bridge for daemon event callbacks from the native layer. */
    lateinit var eventBridge: EventBridge
        private set

    /** Bridge for cron job CRUD FFI calls. */
    lateinit var cronBridge: CronBridge
        private set

    /** Bridge for skills browsing and management FFI calls. */
    lateinit var skillsBridge: SkillsBridge
        private set

    /** Bridge for tools inventory browsing FFI calls. */
    lateinit var toolsBridge: ToolsBridge
        private set

    /** Bridge for memory browsing and management FFI calls. */
    lateinit var memoryBridge: MemoryBridge
        private set

    /** Bridge for direct-to-provider multimodal vision API calls. */
    val visionBridge: VisionBridge by lazy { VisionBridge() }

    /**
     * Read-only Room database for the Discord message archive.
     *
     * Null until [openDiscordArchive] is called and the database file exists.
     * This is a separate, unencrypted database written by the Rust daemon.
     */
    var discordArchiveDb: DiscordArchiveDatabase? = null
        private set

    /** App-wide session lock manager observing the process lifecycle. */
    lateinit var sessionLockManager: SessionLockManager
        private set

    /**
     * Event bus for triggering immediate data refresh across ViewModels.
     *
     * The terminal REPL emits commands here after mutating operations
     * (cron add, skill install, etc.) so that the Dashboard and other
     * screens update without waiting for the next poll cycle.
     */
    val refreshCommands: MutableSharedFlow<RefreshCommand> =
        MutableSharedFlow(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    /**
     * Shared [OkHttpClient] for all HTTP callers within the app.
     *
     * Uses a bounded connection pool to prevent thread and socket leaks.
     * Callers should reference this instance rather than creating their own.
     * Cleaned up in [onTerminate].
     */
    val sharedHttpClient: OkHttpClient by lazy {
        OkHttpClient
            .Builder()
            .connectionPool(
                ConnectionPool(
                    MAX_IDLE_CONNECTIONS,
                    KEEP_ALIVE_DURATION_SECONDS.toLong(),
                    TimeUnit.SECONDS,
                ),
            ).connectTimeout(HTTP_CONNECT_TIMEOUT_SECONDS.toLong(), TimeUnit.SECONDS)
            .readTimeout(HTTP_READ_TIMEOUT_SECONDS.toLong(), TimeUnit.SECONDS)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        System.loadLibrary("sqlcipher")
        System.loadLibrary("zeroclaw")
        com.zeroclaw.ffi.initLogging()
        verifyCrateVersion()

        @Suppress("InjectDispatcher")
        val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
        val ioScope = CoroutineScope(SupervisorJob() + ioDispatcher)

        daemonBridge = DaemonServiceBridge(filesDir.absolutePath)
        database = ZeroAIDatabase.build(this, ioScope)
        settingsRepository = DataStoreSettingsRepository(this)
        apiKeyRepository = createApiKeyRepository(ioScope)
        logRepository = RoomLogRepository(database.logEntryDao(), ioScope)
        activityRepository = RoomActivityRepository(database.activityEventDao(), ioScope)
        onboardingRepository = DataStoreOnboardingRepository(this)
        agentRepository = RoomAgentRepository(database.agentDao())
        pluginRepository = RoomPluginRepository(database.pluginDao())
        channelConfigRepository = createChannelConfigRepository()
        emailConfigRepository = createEmailConfigRepository()
        terminalEntryRepository =
            RoomTerminalEntryRepository(database.terminalEntryDao(), ioScope)
        estopRepository = EstopRepository(scope = ioScope)
        healthBridge = HealthBridge()
        costBridge = CostBridge()
        cronBridge = CronBridge()
        skillsBridge = SkillsBridge()
        toolsBridge = ToolsBridge()
        memoryBridge = MemoryBridge()
        eventBridge = EventBridge(activityRepository, ioScope)
        daemonBridge.eventBridge = eventBridge
        daemonBridge.credentialBridge = CredentialBridge(apiKeyRepository)

        seedProviderSlots(ioScope)

        sessionLockManager = SessionLockManager(settingsRepository.settings, ioScope)
        ProcessLifecycleOwner.get().lifecycle.addObserver(sessionLockManager)

        syncDaemonState(ioScope)
        observeForegroundSync(ioScope)
        bindEstopPolling(ioScope)
        reconcileOAuthState(ioScope)
        schedulePluginSyncIfEnabled(ioScope)
    }

    /**
     * Checks that the loaded native library version matches the app version.
     *
     * A mismatch indicates a partial update left a stale `.so` file. This
     * is logged as a warning rather than a crash so the app remains usable,
     * but the mismatch may cause unexpected FFI behaviour.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun verifyCrateVersion() {
        try {
            val crateVersion = getVersion()
            val appVersion = BuildConfig.VERSION_NAME
            if (crateVersion != appVersion) {
                Log.w(
                    TAG,
                    "Crate/app version mismatch: native=$crateVersion, app=$appVersion",
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to verify crate version: ${e.message}")
        }
    }

    /**
     * Probes the Rust FFI layer to detect whether the daemon is already running.
     *
     * This handles the case where the foreground service kept the daemon alive
     * across a process death (via [START_STICKY]) but the newly created
     * [DaemonServiceBridge] defaults to [ServiceState.STOPPED]. Without this
     * probe, the UI would show the daemon as offline and attempts to start it
     * would fail with "daemon already running".
     *
     * @param scope Background scope for the non-blocking probe.
     */
    private fun syncDaemonState(scope: CoroutineScope) {
        scope.launch {
            daemonBridge.syncState()
        }
    }

    /**
     * Re-syncs the bridge with the actual Rust daemon state every time the
     * app returns to the foreground.
     *
     * After process death the foreground service may have restarted the
     * daemon via [START_STICKY] while the [DaemonServiceBridge] still holds
     * a stale [ServiceState]. Probing the FFI on each foreground transition
     * corrects the discrepancy so the UI never shows a stale "Shutting
     * down" or "Stopped" badge.
     *
     * @param scope Background scope for the non-blocking probe.
     */
    private fun observeForegroundSync(scope: CoroutineScope) {
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    scope.launch { daemonBridge.syncState() }
                }
            },
        )
    }

    /**
     * Ensures the fixed provider-slot seed rows exist in the agent table.
     *
     * This preserves existing rows while guaranteeing the future slot-based
     * Agents UI has stable records to bind against.
     *
     * @param scope Background scope for the insertion coroutine.
     */
    private fun seedProviderSlots(scope: CoroutineScope) {
        scope.launch {
            runCatching { agentRepository.ensureProviderSlots() }
                .onFailure { error ->
                    Log.e(TAG, "Provider slot seeding failed: ${error.message}")
                }
        }
    }

    /**
     * Reconciles legacy Kotlin-side OAuth token copies with the Rust-owned
     * auth-profile store.
     *
     * Older builds duplicated OAuth access/refresh tokens inside the Android
     * API-key repository. The Rust auth-profile store is now the single
     * durable token owner, so this pass migrates any remaining token copies
     * into auth profiles when possible and then clears the Kotlin-side
     * duplicates. It also normalizes stale `openai` OAuth entries to
     * `openai-codex`.
     *
     * @param scope Background scope for the migration coroutine.
     */
    @Suppress(
        "LongMethod",
        "CyclomaticComplexMethod",
        "CognitiveComplexMethod",
        "ComplexCondition",
        "TooGenericExceptionCaught",
    )
    private fun reconcileOAuthState(scope: CoroutineScope) {
        scope.launch {
            try {
                val storedProfiles =
                    AuthProfileStore
                        .listStandalone(this@ZeroAIApplication)
                        .map { it.provider }
                        .toMutableSet()
                val allKeys = apiKeyRepository.keys.first()
                val oauthKeys =
                    allKeys.filter { key ->
                        key.refreshToken.isNotEmpty() ||
                            (key.provider == STALE_OAUTH_PROVIDER && key.key.isBlank())
                    }
                if (oauthKeys.isNotEmpty()) {
                    for (staleKey in oauthKeys) {
                        val normalizedProvider =
                            when (staleKey.provider) {
                                STALE_OAUTH_PROVIDER -> CODEX_PROVIDER
                                else -> staleKey.provider
                            }
                        val authProfileProvider =
                            AuthProfileStore.authProfileProviderFor(normalizedProvider)

                        if (
                            authProfileProvider != null &&
                            authProfileProvider !in storedProfiles &&
                            staleKey.key.isNotBlank() &&
                            staleKey.refreshToken.isNotBlank()
                        ) {
                            when (authProfileProvider) {
                                "openai-codex" ->
                                    AuthProfileWriter.writeCodexProfile(
                                        context = this@ZeroAIApplication,
                                        accessToken = staleKey.key,
                                        refreshToken = staleKey.refreshToken,
                                        expiresAtMs = staleKey.expiresAt.takeIf { it > 0L },
                                    )
                                "anthropic" ->
                                    AuthProfileWriter.writeAnthropicProfile(
                                        context = this@ZeroAIApplication,
                                        accessToken = staleKey.key,
                                        refreshToken = staleKey.refreshToken,
                                        expiresAtMs = staleKey.expiresAt.takeIf { it > 0L },
                                    )
                            }
                            storedProfiles += authProfileProvider
                        }

                        apiKeyRepository.save(
                            staleKey.copy(
                                provider = normalizedProvider,
                                key = if (authProfileProvider != null) "" else staleKey.key,
                                refreshToken = "",
                                expiresAt = 0L,
                            ),
                        )
                    }

                    val currentSettings = settingsRepository.settings.first()
                    if (currentSettings.defaultProvider == STALE_OAUTH_PROVIDER) {
                        settingsRepository.setDefaultProvider(CODEX_PROVIDER)
                    }
                }

                repairManagedProviderState(
                    context = this@ZeroAIApplication,
                    keyRepository = apiKeyRepository,
                    settingsRepository = settingsRepository,
                    agentRepository = agentRepository,
                )

                Log.i(
                    TAG,
                    "Reconciled ${oauthKeys.size} legacy OAuth key entr${if (oauthKeys.size == 1) "y" else "ies"}",
                )
            } catch (e: Exception) {
                Log.e(TAG, "OAuth reconciliation failed: ${e.message}")
            }
        }
    }

    /**
     * Observes the plugin sync setting and schedules/cancels the
     * periodic sync worker accordingly.
     *
     * @param scope Background scope for observing settings.
     */
    private fun schedulePluginSyncIfEnabled(scope: CoroutineScope) {
        scope.launch {
            val workManager = WorkManager.getInstance(this@ZeroAIApplication)
            settingsRepository.settings
                .map { settings ->
                    settings.pluginSyncEnabled to settings.pluginSyncIntervalHours
                }.distinctUntilChanged()
                .collect { (pluginSyncEnabled, pluginSyncIntervalHours) ->
                    if (pluginSyncEnabled) {
                        val constraints =
                            Constraints
                                .Builder()
                                .setRequiredNetworkType(NetworkType.CONNECTED)
                                .build()
                        val request =
                            PeriodicWorkRequestBuilder<PluginSyncWorker>(
                                pluginSyncIntervalHours.toLong(),
                                TimeUnit.HOURS,
                            ).setConstraints(constraints)
                                .build()
                        workManager.enqueueUniquePeriodicWork(
                            PluginSyncWorker.WORK_NAME,
                            ExistingPeriodicWorkPolicy.UPDATE,
                            request,
                        )
                    } else {
                        workManager.cancelUniqueWork(PluginSyncWorker.WORK_NAME)
                    }
                }
        }
    }

    /**
     * Starts or stops e-stop polling to match daemon runtime state.
     *
     * @param scope Background scope for observing daemon lifecycle changes.
     */
    private fun bindEstopPolling(scope: CoroutineScope) {
        scope.launch {
            daemonBridge.serviceState
                .map { it == ServiceState.RUNNING }
                .distinctUntilChanged()
                .collect { enabled ->
                    estopRepository.setPollingEnabled(enabled)
                }
        }
    }

    /**
     * Creates the API key repository with a safety net around keystore access.
     *
     * If [EncryptedApiKeyRepository] construction itself throws (e.g. due to
     * a completely broken keystore), falls back to an [InMemoryApiKeyRepository]
     * so the app can still launch. The initial key load is deferred to
     * [ioScope] to avoid blocking Application.onCreate on slow keystore
     * operations.
     *
     * @param ioScope Background scope for deferred key loading.
     * @return An [ApiKeyRepository] instance.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun createApiKeyRepository(ioScope: CoroutineScope): ApiKeyRepository =
        try {
            val repo = EncryptedApiKeyRepository(context = this, ioScope = ioScope)
            when (repo.storageHealth) {
                is StorageHealth.Healthy ->
                    Log.i(TAG, "API key storage: healthy")
                is StorageHealth.Recovered ->
                    Log.w(TAG, "API key storage: recovered from corruption (keys lost)")
                is StorageHealth.Degraded ->
                    Log.w(TAG, "API key storage: degraded (in-memory only)")
            }
            repo
        } catch (e: Exception) {
            Log.e(TAG, "API key storage init failed, using in-memory fallback", e)
            InMemoryApiKeyRepository()
        }

    /**
     * Creates the channel configuration repository with encrypted secret storage.
     *
     * Uses a separate EncryptedSharedPreferences file (`zeroclaw_channel_secrets`)
     * from the API key storage to isolate channel secrets.
     *
     * @return A [ChannelConfigRepository] instance.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun createChannelConfigRepository(): ChannelConfigRepository {
        val (prefs, health) = SecurePrefsProvider.create(this, CHANNEL_SECRETS_PREFS)
        when (health) {
            is StorageHealth.Healthy ->
                Log.i(TAG, "Channel secret storage: healthy")
            is StorageHealth.Recovered ->
                Log.w(TAG, "Channel secret storage: recovered from corruption")
            is StorageHealth.Degraded ->
                Log.w(TAG, "Channel secret storage: degraded (in-memory only)")
        }
        return RoomChannelConfigRepository(database.connectedChannelDao(), prefs)
    }

    /**
     * Creates the email configuration repository with encrypted password storage.
     *
     * Uses a separate EncryptedSharedPreferences file (`zeroclaw_email_secrets`)
     * from the API key and channel secret stores to isolate email credentials.
     *
     * @return An [EmailConfigRepository] instance.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun createEmailConfigRepository(): EmailConfigRepository {
        val (prefs, health) = SecurePrefsProvider.create(this, EMAIL_SECRETS_PREFS)
        when (health) {
            is StorageHealth.Healthy ->
                Log.i(TAG, "Email secret storage: healthy")
            is StorageHealth.Recovered ->
                Log.w(TAG, "Email secret storage: recovered from corruption")
            is StorageHealth.Degraded ->
                Log.w(TAG, "Email secret storage: degraded (in-memory only)")
        }
        return EmailConfigRepository(database.emailConfigDao(), prefs)
    }

    override fun newImageLoader(context: Context): ImageLoader =
        ImageLoader
            .Builder(context)
            .crossfade(true)
            .memoryCache {
                MemoryCache
                    .Builder()
                    .maxSizePercent(context, MEMORY_CACHE_PERCENT)
                    .build()
            }.diskCache {
                DiskCache
                    .Builder()
                    .directory(context.cacheDir.resolve("image_cache"))
                    .maxSizeBytes(DISK_CACHE_MAX_BYTES)
                    .build()
            }.build()

    /**
     * Shuts down the shared [OkHttpClient] connection pool and dispatcher.
     *
     * Called when the application process is terminating. Releases thread
     * pools and idle connections to prevent resource leaks.
     */
    override fun onTerminate() {
        sharedHttpClient.connectionPool.evictAll()
        sharedHttpClient.dispatcher.executorService.shutdown()
        super.onTerminate()
    }

    /**
     * Opens the Discord archive database if the file exists.
     *
     * Call this when the Discord settings UI is first opened. Returns the
     * cached instance on subsequent calls. Returns null if the daemon has
     * not yet created the archive file.
     *
     * @return The [DiscordArchiveDatabase] instance, or null if unavailable.
     */
    fun openDiscordArchive(): DiscordArchiveDatabase? {
        if (discordArchiveDb != null) return discordArchiveDb
        val dbFile = File(filesDir, "memory/discord_archive.db")
        discordArchiveDb = DiscordArchiveDatabase.openIfExists(this, dbFile)
        return discordArchiveDb
    }

    /**
     * Returns the guild ID of the first enabled Discord channel config, or null.
     *
     * Reads from the Rust-managed `discord_archive.db` via Room. Returns null
     * if the archive does not exist yet (first run) or has no enabled channels.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun discordGuildId(): String? =
        try {
            openDiscordArchive()
                ?.messageDao()
                ?.getAllChannelConfigs()
                ?.firstOrNull { it.enabled == 1 }
                ?.guildId
        } catch (e: Exception) {
            Log.w(TAG, "Could not read Discord guild_id: ${e.message}")
            null
        }

    /** Constants for [ZeroAIApplication]. */
    companion object {
        private const val TAG = "ZeroAIApp"
        private const val CHANNEL_SECRETS_PREFS = "zeroclaw_channel_secrets"
        private const val EMAIL_SECRETS_PREFS = "zeroclaw_email_secrets"
        private const val STALE_OAUTH_PROVIDER = "openai"
        private const val CODEX_PROVIDER = "openai-codex"
        private const val MEMORY_CACHE_PERCENT = 0.15
        private const val DISK_CACHE_MAX_BYTES = 64L * 1024 * 1024
        private const val MAX_IDLE_CONNECTIONS = 5
        private const val KEEP_ALIVE_DURATION_SECONDS = 30
        private const val HTTP_CONNECT_TIMEOUT_SECONDS = 10
        private const val HTTP_READ_TIMEOUT_SECONDS = 15
    }
}
