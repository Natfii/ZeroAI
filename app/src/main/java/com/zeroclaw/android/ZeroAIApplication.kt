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
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
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
import com.zeroclaw.android.data.ssh.EncryptedSshKeyStore
import com.zeroclaw.android.data.ssh.SshAgentInitializer
import com.zeroclaw.android.data.ssh.SshDataStore
import com.zeroclaw.android.model.CachedTailscalePeer
import com.zeroclaw.android.model.RefreshCommand
import com.zeroclaw.android.model.ServiceState
import com.zeroclaw.android.service.CapabilityApprovalNotifier
import com.zeroclaw.android.service.CapabilityGrantsBridge
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
import com.zeroclaw.android.tailscale.PeerMessageRouter
import com.zeroclaw.android.tailscale.PeerRouteEntry
import com.zeroclaw.android.tailscale.isAgentKind
import com.zeroclaw.android.tailscale.normalizeKind
import com.zeroclaw.android.util.SessionLockManager
import com.zeroclaw.android.worker.MemoryMaintenanceWorker
import com.zeroclaw.ffi.getVersion
import java.io.File
import java.util.concurrent.TimeUnit
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
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
 *
 * ## Two-tier init policy
 *
 * To keep [onCreate] off the main-thread critical path, properties use one
 * of two declaration styles:
 *
 *  - **`lateinit var ... private set`** — cheap eager init touched by
 *    [onCreate] directly. Used for the plain FFI bridges (HealthBridge,
 *    CostBridge, CronBridge, SkillsBridge, ToolsBridge, MemoryBridge,
 *    CapabilityGrantsBridge), the DataStore-backed repositories
 *    (settingsRepository, onboardingRepository), the e-stop repo, and
 *    main-thread observers (sessionLockManager).
 *  - **`val ... by lazy { }`** — expensive init deferred until first
 *    off-main access. Used for anything that opens the Android Keystore,
 *    SQLCipher database, EncryptedSharedPreferences, or makes synchronous
 *    NotificationManager / JNI calls (database, apiKeyRepository,
 *    channelConfigRepository, emailConfigRepository, sshDataStore,
 *    logRepository, activityRepository, agentRepository, pluginRepository,
 *    terminalEntryRepository, capabilityApprovalNotifier, visionBridge,
 *    sharedHttpClient, ioScope).
 *
 * The remaining `lateinit var`s assigned inside an [ioScope] launch (eg.
 * eventBridge) are nullable on the receiving side (DaemonServiceBridge
 * uses `EventBridge?` with safe-call register sites) so the temporary
 * uninitialised window between launch dispatch and execution cannot crash
 * the daemon path. Any new property whose constructor opens a keystore,
 * database, or notification channel MUST be `by lazy`.
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

    /**
     * Room database instance for agents, plugins, logs, and activity events.
     *
     * Initialised on first access via [ZeroAIDatabase.build], which builds
     * the SQLCipher passphrase + opens the encrypted DB. Lazy so the
     * keystore + PBKDF2 cost is paid off [onCreate]'s critical path when
     * the consumers (DAO-backed repos, EventBridge) also defer their
     * first access to a background dispatcher.
     */
    val database: ZeroAIDatabase by lazy { ZeroAIDatabase.build(this, ioScope) }

    /** Application settings repository backed by Jetpack DataStore. */
    lateinit var settingsRepository: SettingsRepository
        private set

    /**
     * API key repository backed by EncryptedSharedPreferences.
     *
     * Initialised on first access (off-main when called from a ViewModel
     * coroutine) to keep the keystore-backed prefs open out of
     * [onCreate]'s critical path.
     */
    val apiKeyRepository: ApiKeyRepository by lazy { createApiKeyRepository(ioScope) }

    /**
     * Log repository backed by Room with automatic pruning.
     *
     * Lazy so the first DAO access (and the underlying SQLCipher open it
     * triggers) is deferred to a background dispatcher.
     */
    val logRepository: LogRepository by lazy {
        RoomLogRepository(database.logEntryDao(), ioScope)
    }

    /**
     * Activity feed repository backed by Room with automatic pruning.
     *
     * Lazy so first DAO access is deferred to a background dispatcher.
     */
    val activityRepository: ActivityRepository by lazy {
        RoomActivityRepository(database.activityEventDao(), ioScope)
    }

    /** Onboarding state repository backed by Jetpack DataStore. */
    lateinit var onboardingRepository: OnboardingRepository
        private set

    /**
     * Agent repository backed by Room.
     *
     * Lazy so first DAO access is deferred to a background dispatcher.
     */
    val agentRepository: AgentRepository by lazy {
        RoomAgentRepository(database.agentDao())
    }

    /**
     * Plugin repository backed by Room.
     *
     * Lazy so first DAO access is deferred to a background dispatcher.
     */
    val pluginRepository: PluginRepository by lazy {
        RoomPluginRepository(database.pluginDao())
    }

    /**
     * Channel configuration repository backed by Room + EncryptedSharedPreferences.
     *
     * Initialised on first access to keep the keystore-backed prefs open
     * out of [onCreate]'s critical path.
     */
    val channelConfigRepository: ChannelConfigRepository by lazy {
        createChannelConfigRepository()
    }

    /**
     * Email configuration repository backed by Room + EncryptedSharedPreferences.
     *
     * Initialised on first access to keep the keystore-backed prefs open
     * out of [onCreate]'s critical path.
     */
    val emailConfigRepository: EmailConfigRepository by lazy {
        createEmailConfigRepository()
    }

    /**
     * Terminal REPL entry repository backed by Room.
     *
     * Lazy so first DAO access is deferred to a background dispatcher.
     */
    val terminalEntryRepository: TerminalEntryRepository by lazy {
        RoomTerminalEntryRepository(database.terminalEntryDao(), ioScope)
    }

    /**
     * Encrypted DataStore for SSH key metadata.
     *
     * Initialised on first access to keep the keystore-backed open out of
     * [onCreate]'s critical path.
     */
    val sshDataStore: SshDataStore by lazy { SshDataStore(this) }

    /**
     * Encrypted-at-rest store for SSH private keys.
     *
     * Holds each key's OpenSSH private PEM as an AES-256-GCM blob in the
     * consolidated secure prefs file. Lazy so the keystore open is deferred
     * off [onCreate]'s critical path. Private bytes are decrypted only when
     * handed to the in-app ssh-agent over the FFI.
     */
    val sshKeyStore: EncryptedSshKeyStore by lazy { EncryptedSshKeyStore(context = this) }

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

    /** Bridge for capability grant listing and revocation FFI calls. */
    lateinit var capabilityGrantsBridge: CapabilityGrantsBridge
        private set

    /**
     * Notifier for capability approval requests shown as Android notifications.
     *
     * Lazy so the synchronous NotificationManager.createNotificationChannel
     * IPC is deferred off [onCreate]'s critical path.
     */
    val capabilityApprovalNotifier: CapabilityApprovalNotifier by lazy {
        CapabilityApprovalNotifier(this)
    }

    /** Bridge for direct-to-provider multimodal vision API calls. */
    val visionBridge: VisionBridge by lazy { VisionBridge() }

    /**
     * Application-scoped coordinator for the on-device large LLM
     * engine. Watches the agent enabled flag + downloaded model
     * file + daemon state; loads / unloads the LiteRT-LM engine
     * accordingly. Wired up once in [onCreate] via
     * [com.zeroclaw.android.service.ondevice.OnDeviceInferenceManager.attach].
     */
    val onDeviceInferenceManager:
        com.zeroclaw.android.service.ondevice.OnDeviceInferenceManager by lazy {
            com.zeroclaw.android.service.ondevice.OnDeviceInferenceManager(
                context = this,
                agentRepository = agentRepository,
                scope = ioScope,
            )
        }

    /**
     * Read-only Room database for the Discord message archive.
     *
     * Null until [openDiscordArchive] is called and the database file exists.
     * This is a separate, unencrypted database written by the Rust daemon.
     */
    var discordArchiveDb: DiscordArchiveDatabase? = null
        private set

    /**
     * Background coroutine scope for I/O work spawned from [onCreate] and
     * the lazy-initialised repositories that follow.
     *
     * Promoted to a class-level property so `by lazy { }` delegates that
     * need a scope (encrypted-prefs repos, DataStore repos) can reference
     * it without having to receive it as a parameter.
     */
    @Suppress("InjectDispatcher")
    val ioScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

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

    /**
     * Records the bundled `ssh` client configuration (PATH/HOME plus the
     * `ssh` symlink to libssh.so) for the in-app shell.
     *
     * Called synchronously from [onCreate] so the configuration exists before
     * any terminal session can spawn a shell. The filesystem work is one
     * directory plus one symlink, so it is safe to run on the main thread at
     * startup.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun configureBundledSsh() {
        try {
            com.zeroclaw.ffi.ttyConfigureShell(
                applicationInfo.nativeLibraryDir,
                filesDir.absolutePath,
            )
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Bundled ssh configure failed: ${e.message}")
        }
    }

    @Suppress("LongMethod", "TooGenericExceptionCaught")
    override fun onCreate() {
        super.onCreate()
        System.loadLibrary("sqlcipher")
        System.loadLibrary("ghostty_vt")
        System.loadLibrary("zeroclaw")
        com.zeroclaw.ffi.initLogging()
        verifyCrateVersion()
        configureBundledSsh()

        daemonBridge = DaemonServiceBridge(filesDir.absolutePath)
        settingsRepository = DataStoreSettingsRepository(this)
        onboardingRepository = DataStoreOnboardingRepository(this)

        // Warm heavy lazies in a single coroutine, in dependency order,
        // so parallel `ioScope.launch` blocks that touch SQLCipher /
        // EncryptedSharedPreferences-backed repos can `join()` instead
        // of racing on `SynchronizedLazyImpl` mutexes. The Job is
        // explicitly awaited by every downstream coroutine below that
        // touches a warmed lazy.
        val warmupJob =
            ioScope.launch {
                @Suppress("UNUSED_EXPRESSION")
                database
                @Suppress("UNUSED_EXPRESSION")
                apiKeyRepository
                @Suppress("UNUSED_EXPRESSION")
                logRepository
                @Suppress("UNUSED_EXPRESSION")
                activityRepository
            }

        estopRepository = EstopRepository(scope = ioScope)
        healthBridge = HealthBridge()
        costBridge = CostBridge()
        cronBridge = CronBridge()
        skillsBridge = SkillsBridge()
        toolsBridge = ToolsBridge()
        memoryBridge = MemoryBridge()
        capabilityGrantsBridge = CapabilityGrantsBridge(filesDir.absolutePath)
        ioScope.launch {
            warmupJob.join()
            eventBridge =
                EventBridge(
                    activityRepository = activityRepository,
                    scope = ioScope,
                    getPeers = { buildPeerRoutes() },
                    getPeerToken = { ip, port -> readPeerToken(ip, port) },
                    notifier = capabilityApprovalNotifier,
                )
            daemonBridge.eventBridge = eventBridge
            daemonBridge.credentialBridge = CredentialBridge(apiKeyRepository)
        }

        seedProviderSlots(ioScope)
        startSshAgent(ioScope)
        onDeviceInferenceManager.attach()

        sessionLockManager = SessionLockManager(settingsRepository.settings, ioScope)
        ProcessLifecycleOwner.get().lifecycle.addObserver(sessionLockManager)

        syncDaemonState(ioScope)
        observeForegroundSync(ioScope)
        bindEstopPolling(ioScope)
        reconcileOAuthState(ioScope)
        schedulePluginSyncIfEnabled(ioScope)
        scheduleMemoryMaintenance()
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
     * Brings the in-process ssh-agent up for the session.
     *
     * Migrates any legacy plaintext keys into the encrypted store, starts the
     * agent on `filesDir/ssh/agent.sock`, and loads every encrypted key into
     * it so terminal shells and the bundled `ssh` client can authenticate via
     * `SSH_AUTH_SOCK`. Runs off the main thread; the agent-start in Rust is
     * idempotent so a transient failure simply leaves keys unloaded until the
     * next launch rather than crashing.
     *
     * @param scope Background scope for the agent bring-up coroutine.
     */
    private fun startSshAgent(scope: CoroutineScope) {
        scope.launch {
            SshAgentInitializer(
                sshDir = File(filesDir, "ssh"),
                keyStore = sshKeyStore,
                dataStore = sshDataStore,
            ).initialize()
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
     * Enqueues the daily [MemoryMaintenanceWorker] with WorkManager.
     *
     * Uses [ExistingPeriodicWorkPolicy.KEEP] so that an already-scheduled
     * run is not reset on every app launch. Constrained to run only when the
     * battery is not low; power-save deferral is handled inside the worker.
     */
    private fun scheduleMemoryMaintenance() {
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            MemoryMaintenanceWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<MemoryMaintenanceWorker>(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints
                        .Builder()
                        .setRequiresBatteryNotLow(true)
                        .build(),
                ).build(),
        )
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

    /**
     * Returns enabled peer route entries derived from cached tailscale discovery data.
     *
     * Reads the JSON-serialized peer cache from [AppSettings.tailscaleCachedDiscovery]
     * and maps each agent service (zeroclaw or openclaw) to a [PeerRouteEntry].
     * Returns an empty list when no peers are cached or the setting is blank.
     *
     * Safe to call from a background thread. Uses [runBlocking] to read the
     * settings [kotlinx.coroutines.flow.Flow] synchronously.
     *
     * @return List of peer routes available for @alias routing.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun buildPeerRoutes(): List<PeerRouteEntry> {
        val cached =
            try {
                runBlocking { settingsRepository.settings.first() }.tailscaleCachedDiscovery
            } catch (_: Exception) {
                return emptyList()
            }
        if (cached.isBlank()) return emptyList()
        return try {
            val peers = Json.decodeFromString<List<CachedTailscalePeer>>(cached)
            val raw =
                peers.flatMap { peer ->
                    peer.services
                        .filter { svc -> isAgentKind(svc.kind) }
                        .map { svc ->
                            PeerRouteEntry(
                                alias = normalizeKind(svc.kind),
                                ip = peer.ip,
                                port = svc.port,
                                kind = normalizeKind(svc.kind),
                            )
                        }
                }
            val defaults =
                PeerMessageRouter.resolveAliasConflicts(raw.map { it.alias })
            val masterKey =
                MasterKey
                    .Builder(this)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
            val prefs =
                EncryptedSharedPreferences.create(
                    this,
                    PEER_TOKEN_PREFS,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            raw.mapIndexed { i, entry ->
                val sanitizedIp =
                    entry.ip.replace(Regex("[^a-fA-F0-9.:]"), "")
                val savedAlias =
                    prefs.getString(
                        "tailscale_alias_${sanitizedIp}_${entry.port}",
                        null,
                    )
                entry.copy(alias = savedAlias ?: defaults[i])
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Retrieves a stored bearer token for the given peer from encrypted preferences.
     *
     * Uses the same storage key convention as
     * [com.zeroclaw.android.ui.screen.tailscale.TailscaleConfigViewModel].
     * Returns `null` when no token has been saved for this peer or if
     * the keystore is unavailable.
     *
     * Safe to call from a background thread.
     *
     * @param ip Tailscale IP of the peer.
     * @param port Gateway port of the peer service.
     * @return Stored bearer token, or `null` if absent.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun readPeerToken(
        ip: String,
        port: Int,
    ): String? =
        try {
            val masterKey =
                MasterKey
                    .Builder(this)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
            val prefs =
                EncryptedSharedPreferences.create(
                    this,
                    PEER_TOKEN_PREFS,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            val sanitizedIp = ip.replace(Regex("[^a-fA-F0-9.:]"), "")
            prefs.getString("tailscale_peer_${sanitizedIp}_$port", null)
        } catch (_: Exception) {
            null
        }

    /** Constants for [ZeroAIApplication]. */
    companion object {
        private const val TAG = "ZeroAIApp"
        private const val CHANNEL_SECRETS_PREFS = "zeroclaw_channel_secrets"
        private const val EMAIL_SECRETS_PREFS = "zeroclaw_email_secrets"
        private const val PEER_TOKEN_PREFS = "tailscale_peer_tokens"
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
