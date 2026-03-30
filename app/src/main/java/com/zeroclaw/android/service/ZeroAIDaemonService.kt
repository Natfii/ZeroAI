/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.zeroclaw.android.BuildConfig
import com.zeroclaw.android.ZeroAIApplication
import com.zeroclaw.android.data.DiscordDmLinkStore
import com.zeroclaw.android.data.discord.PendingDiscordOpsStore
import com.zeroclaw.android.data.oauth.AuthProfileStore
import com.zeroclaw.android.data.repository.ActivityRepository
import com.zeroclaw.android.data.repository.AgentRepository
import com.zeroclaw.android.data.repository.ApiKeyRepository
import com.zeroclaw.android.data.repository.ChannelConfigRepository
import com.zeroclaw.android.data.repository.LogRepository
import com.zeroclaw.android.data.repository.SettingsRepository
import com.zeroclaw.android.data.saf.SharedFolderCallbackHandler
import com.zeroclaw.android.model.ActivityType
import com.zeroclaw.android.model.ApiKey
import com.zeroclaw.android.model.AppSettings
import com.zeroclaw.android.model.LogSeverity
import com.zeroclaw.android.model.MemoryConflict
import com.zeroclaw.android.model.MemoryHealthResult
import com.zeroclaw.android.model.ServiceState
import com.zeroclaw.android.util.LogSanitizer
import com.zeroclaw.ffi.FfiAuthProfile
import com.zeroclaw.ffi.FfiException
import com.zeroclaw.ffi.clawboyStopSession
import com.zeroclaw.ffi.listPersistedSessions
import com.zeroclaw.ffi.registerSharedFolderHandler
import com.zeroclaw.ffi.restoreSessionState
import com.zeroclaw.ffi.saveSessionState
import com.zeroclaw.ffi.setNanoAvailable
import com.zeroclaw.ffi.unregisterSharedFolderHandler
import com.zeroclaw.ffi.validateConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Always-on foreground service that manages the ZeroAI daemon lifecycle.
 *
 * Uses the `specialUse` foreground service type and [START_STICKY] to
 * ensure the daemon remains running across process restarts. Includes
 * an adaptive status polling loop that feeds [DaemonServiceBridge.lastStatus]
 * (5 s when the screen is on, 60 s when off), network connectivity
 * monitoring via [NetworkMonitor], and a partial wake lock with a
 * 3-minute safety timeout held only during startup (Rust FFI init and
 * tokio runtime boot). The foreground notification keeps the process alive
 * after startup, so the wake lock is released once
 * [DaemonServiceBridge.start] returns successfully.
 *
 * The service communicates with the Rust native layer exclusively through
 * the shared [DaemonServiceBridge] obtained from [ZeroAIApplication].
 *
 * After a successful start the daemon configuration is persisted via
 * [DaemonPersistence]. When the system restarts the service after process
 * death ([START_STICKY] with a null intent), the persisted configuration
 * is restored and the daemon is restarted automatically.
 *
 * Startup failures are retried with exponential backoff via [RetryPolicy].
 * After all retries are exhausted the service transitions to
 * [ServiceState.ERROR], detaches the foreground notification (keeping it
 * visible as an error indicator), and stops itself to avoid a zombie
 * service.
 *
 * Lifecycle control is performed via [Intent] actions:
 * - [ACTION_START] to start the daemon and enter the foreground.
 * - [ACTION_STOP] to stop the daemon and remove the foreground notification.
 * - [ACTION_RETRY] to reset the retry counter and attempt startup again.
 * - [ACTION_OAUTH_HOLD] to hold the foreground during OAuth flows.
 */
@Suppress("TooManyFunctions", "LargeClass")
class ZeroAIDaemonService : Service() {
    @Suppress("InjectDispatcher")
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var bridge: DaemonServiceBridge
    private lateinit var notificationManager: DaemonNotificationManager
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var persistence: DaemonPersistence
    private lateinit var logRepository: LogRepository
    private lateinit var activityRepository: ActivityRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var apiKeyRepository: ApiKeyRepository
    private lateinit var channelConfigRepository: ChannelConfigRepository
    private lateinit var agentRepository: AgentRepository
    private val retryPolicy = RetryPolicy()

    private var statusPollJob: Job? = null
    private var startJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile private var isScreenOn: Boolean = true
    private var screenReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        val app = application as ZeroAIApplication
        bridge = app.daemonBridge
        logRepository = app.logRepository
        activityRepository = app.activityRepository
        settingsRepository = app.settingsRepository
        apiKeyRepository = app.apiKeyRepository
        channelConfigRepository = app.channelConfigRepository
        agentRepository = app.agentRepository
        bridge.initWebRenderer(applicationContext)
        notificationManager = DaemonNotificationManager(this)
        networkMonitor = NetworkMonitor(this)
        persistence = DaemonPersistence(this)

        notificationManager.createChannel()
        networkMonitor.register()
        registerScreenReceiver()
        observeServiceState()
        observeNetworkState()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_START -> handleStartFromSettings()
            ACTION_STOP -> handleStop()
            ACTION_RETRY -> handleRetry()
            ACTION_OAUTH_HOLD -> handleOAuthHold()
            null -> handleStickyRestart()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Called when the user swipes the app from the recents screen.
     *
     * [START_STICKY] ensures the system will eventually restart the service
     * if killed, but some OEM manufacturers (Xiaomi, Samsung, Huawei, Oppo,
     * Vivo) aggressively terminate services after task removal without
     * honouring [START_STICKY]. As a fallback, an [AlarmManager] alarm is
     * scheduled [RESTART_DELAY_MS] (5 seconds) in the future to restart the
     * service via [setExactAndAllowWhileIdle], which fires even in Doze mode.
     *
     * The alarm is only scheduled when the daemon is currently
     * [ServiceState.RUNNING] to avoid restarting a service the user
     * explicitly stopped.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (bridge.serviceState.value == ServiceState.RUNNING) {
            saveActiveSessions()
            scheduleRestartAlarm()
        }
    }

    /**
     * Schedules an [AlarmManager] alarm to restart this service after a
     * short delay.
     *
     * Uses [AlarmManager.setExactAndAllowWhileIdle] so the alarm fires even
     * when the device is in Doze mode. The [PendingIntent] targets this
     * service without an explicit action, triggering the [START_STICKY] null
     * intent path in [onStartCommand] which restores the daemon from
     * persisted configuration.
     */
    private fun scheduleRestartAlarm() {
        val restartIntent =
            Intent(applicationContext, ZeroAIDaemonService::class.java)
        val pendingIntent =
            PendingIntent.getService(
                applicationContext,
                RESTART_REQUEST_CODE,
                restartIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT,
            )
        val alarmManager =
            getSystemService(ALARM_SERVICE) as AlarmManager
        val triggerAt = SystemClock.elapsedRealtime() + RESTART_DELAY_MS
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S ||
            alarmManager.canScheduleExactAlarms()
        ) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAt,
                pendingIntent,
            )
        } else {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAt,
                pendingIntent,
            )
        }
        if (BuildConfig.DEBUG) {
            Log.i(TAG, "Scheduled AlarmManager restart fallback in ${RESTART_DELAY_MS}ms")
        }
    }

    override fun onDestroy() {
        saveActiveSessions()
        releaseWakeLock()
        unregisterScreenReceiver()
        networkMonitor.unregister()
        serviceScope.cancel()
        super.onDestroy()
    }

    /**
     * Reads the current user settings and API key, builds a TOML config,
     * then enters the foreground and starts the daemon.
     *
     * Settings are read inside a coroutine because the repositories are
     * flow-based. The foreground notification is posted immediately so the
     * system does not kill the service while waiting for I/O.
     */
    @Suppress("LongMethod", "CognitiveComplexMethod")
    private fun handleStartFromSettings() {
        val notification =
            notificationManager.buildNotification(ServiceState.STARTING)
        startForegroundCompat(
            DaemonNotificationManager.NOTIFICATION_ID,
            notification,
        )
        acquireWakeLock()

        serviceScope.launch(ioDispatcher) {
            val settings = settingsRepository.settings.first()
            val effectiveSettings = resolveEffectiveDefaults(settings)
            val mergedSettings = mergeUpstreamPairedTokens(effectiveSettings)
            val seededSettings = preSeedGatewayTokenIfNeeded(mergedSettings)
            val apiKey = apiKeyRepository.getByProviderFresh(seededSettings.defaultProvider)
            val authProfiles = loadStandaloneAuthProfiles()

            val emailConfig =
                try {
                    val ec = (application as ZeroAIApplication).emailConfigRepository.observe().first()
                    Log.i(TAG, "Email config loaded: enabled=${ec.isEnabled}, address=${ec.address.take(3)}***, imap=${ec.imapHost}")
                    ec
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load email config", e)
                    null
                }
            val hubAppContext =
                listOfNotNull(
                    twitterAwarenessFragment(seededSettings),
                    emailAwarenessFragment(emailConfig),
                    googleMessagesAwarenessFragment(),
                    clawBoyAwarenessFragment(),
                    tailscaleAwarenessFragment(seededSettings),
                ).joinToString("\n").ifBlank { null }

            val globalConfig =
                buildGlobalTomlConfig(seededSettings, apiKey, authProfiles)
                    .copy(
                        hubAppContext = hubAppContext,
                        emailImapHost = emailConfig?.imapHost.orEmpty(),
                        emailImapPort = emailConfig?.imapPort ?: 993,
                        emailSmtpHost = emailConfig?.smtpHost.orEmpty(),
                        emailSmtpPort = emailConfig?.smtpPort ?: 465,
                        emailAddress = emailConfig?.address.orEmpty(),
                        emailPassword = emailConfig?.password.orEmpty(),
                        emailCheckTimes = emailConfig?.checkTimes.orEmpty(),
                        emailEnabled = emailConfig?.isEnabled == true,
                    )

            if (!validateProviderKeyOrStop(globalConfig)) return@launch

            val baseToml = ConfigTomlBuilder.build(globalConfig)
            Log.i(TAG, "TOML has [email]: ${baseToml.contains("[email]")}")
            Log.i(TAG, "Provider=${globalConfig.provider} Model=${globalConfig.model} BaseUrl=${globalConfig.baseUrl} ApiKey=${if (globalConfig.apiKey.isNotBlank()) "present" else "EMPTY"}")
            val enabledChannels = channelConfigRepository.getEnabledWithSecrets()
            val channelNames = enabledChannels.map { it.first.type.displayName }
            if (channelNames.isNotEmpty()) {
                Log.i(TAG, "Channels included in config: $channelNames")
                logRepository.append(
                    LogSeverity.INFO,
                    TAG,
                    "Starting with channels: ${channelNames.joinToString()}",
                )
            } else {
                Log.w(TAG, "No enabled channels found — daemon will start without channels")
                logRepository.append(
                    LogSeverity.WARN,
                    TAG,
                    "No enabled channels found",
                )
            }
            val discordGuildId =
                (application as? ZeroAIApplication)?.discordGuildId()
            val channelsToml =
                ConfigTomlBuilder.buildChannelsToml(enabledChannels, discordGuildId)
            val agentsToml = buildAgentsToml()
            val peersToml = buildPeersToml()
            val configToml = baseToml + channelsToml + agentsToml + peersToml

            if (!validateConfigOrStop(configToml)) return@launch

            val conflict = bridge.detectMemoryConflict(seededSettings.memoryBackend)
            if (conflict is MemoryConflict.StaleData) {
                val shouldDelete = bridge.awaitConflictResolution(conflict)
                if (shouldDelete) {
                    bridge.cleanupStaleMemory(conflict)
                    logRepository.append(
                        LogSeverity.INFO,
                        TAG,
                        "Cleaned up ${conflict.staleFileCount} stale " +
                            "${conflict.staleBackend} memory files",
                    )
                }
            }

            retryPolicy.reset()
            val validPort =
                if (settings.port in VALID_PORT_RANGE) {
                    settings.port
                } else {
                    AppSettings.DEFAULT_PORT
                }
            attemptStart(
                configToml = configToml,
                host = settings.host,
                port = validPort.toUShort(),
                memoryBackend = seededSettings.memoryBackend,
            )
        }
    }

    /**
     * Validates the assembled TOML config before daemon startup.
     *
     * Calls [validateConfig] and logs / surfaces any validation errors,
     * then tears down the foreground service if validation fails.
     *
     * @param configToml The full TOML configuration string.
     * @return `true` if the config is valid and startup should proceed,
     *   `false` if validation failed (caller should `return@launch`).
     */
    private suspend fun validateConfigOrStop(configToml: String): Boolean {
        try {
            val validationResult = validateConfig(configToml)
            if (validationResult.isNotEmpty()) {
                val safeMsg = LogSanitizer.sanitizeLogMessage(validationResult)
                Log.e(TAG, "Config validation failed: $safeMsg")
                logRepository.append(LogSeverity.ERROR, TAG, "Config validation failed: $safeMsg")
                activityRepository.record(ActivityType.DAEMON_ERROR, "Config validation failed: $safeMsg")
                notificationManager.updateNotification(ServiceState.ERROR, errorDetail = safeMsg)
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
                return false
            }
        } catch (e: FfiException) {
            val safeMsg = LogSanitizer.sanitizeLogMessage(e.message ?: "Unknown error")
            Log.e(TAG, "Config validation threw: $safeMsg")
            logRepository.append(LogSeverity.ERROR, TAG, "Config validation error: $safeMsg")
            activityRepository.record(ActivityType.DAEMON_ERROR, "Config validation error: $safeMsg")
            notificationManager.updateNotification(ServiceState.ERROR, errorDetail = safeMsg)
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
            return false
        }
        return true
    }

    /**
     * Validates that the provider has an API key when one is required.
     *
     * Self-hosted providers (Ollama, LM Studio, vLLM, LocalAI with a
     * custom base URL) do not need a real key and receive a placeholder.
     * All other providers require the user to configure an API key in
     * Settings before the daemon can start. When validation fails, an
     * error is logged, the notification is updated, and the service
     * stops itself.
     *
     * @param config The assembled [GlobalTomlConfig] with provider and key.
     * @return `true` if the provider has credentials (or doesn't need them),
     *   `false` if startup should be aborted (caller should `return@launch`).
     */
    private suspend fun validateProviderKeyOrStop(config: GlobalTomlConfig): Boolean {
        if (config.provider.isBlank() || config.apiKey.isNotBlank()) return true

        val resolved =
            ConfigTomlBuilder.resolveProvider(
                config.provider,
                config.baseUrl,
            )
        if (ConfigTomlBuilder.needsPlaceholderKey(resolved)) return true
        val authProfiles = loadStandaloneAuthProfiles()
        if (
            hasUsableDaemonProviderCredentials(
                config.provider,
                apiKey = null,
                authProfiles = authProfiles,
            )
        ) {
            return true
        }

        val msg =
            unsupportedManagedAuthMessage(
                provider = config.provider,
                authProfiles = authProfiles,
            ) ?: "No API key found for provider " +
                "'${config.provider}'. Configure one in " +
                "Settings \u2192 API Keys before starting."
        Log.e(TAG, "Startup blocked: $msg")
        logRepository.append(LogSeverity.ERROR, TAG, msg)
        activityRepository.record(ActivityType.DAEMON_ERROR, msg)
        notificationManager.updateNotification(
            ServiceState.ERROR,
            errorDetail = msg,
        )
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
        return false
    }

    /**
     * Derives effective default provider and model from the agent list.
     *
     * The first enabled agent with a non-blank provider and model name
     * overrides the DataStore values in [settings]. This ensures that
     * edits or deletions in the Connections tab take effect immediately
     * on the next daemon start, without requiring the DataStore
     * `defaultProvider` / `defaultModel` keys to be kept in sync with
     * every agent mutation.
     *
     * @param settings Current application settings (may have stale defaults).
     * @return A copy of [settings] with provider and model overridden by the
     *   primary agent, or unchanged if no qualifying agent exists.
     */
    private suspend fun resolveEffectiveDefaults(settings: AppSettings): AppSettings {
        val authProfiles = loadStandaloneAuthProfiles()
        val agents = agentRepository.agents.first()
        val ordered = SlotAwareAgentConfig.orderedConfiguredAgents(agents)
        Log.i(TAG, "Slot agents total=${agents.size} enabled/configured=${ordered.size}")
        for (agent in agents) {
            Log.i(TAG, "  Agent[${agent.id}]: enabled=${agent.isEnabled} provider='${agent.provider}' model='${agent.modelName}' slotId='${agent.slotId}'")
        }
        return SlotAwareAgentConfig.resolveEffectiveDefaults(settings, agents) { agent ->
            val key = apiKeyRepository.getByProviderFresh(agent.provider)
            SlotAwareAgentConfig.hasUsableDaemonProviderCredentials(
                provider = agent.provider,
                apiKey = key,
                authProfiles = authProfiles,
            )
        }
    }

    /**
     * Converts [AppSettings] and resolved API key into a [GlobalTomlConfig].
     *
     * Comma-separated string fields in [AppSettings] are split into lists
     * for [GlobalTomlConfig] properties that expect `List<String>`.
     *
     * @param settings Current application settings.
     * @param apiKey Resolved API key for the default provider, or null.
     * @param authProfiles Standalone FFI auth profiles for OAuth-backed providers.
     * @return A fully populated [GlobalTomlConfig].
     */
    @Suppress("LongMethod")
    private fun buildGlobalTomlConfig(
        settings: AppSettings,
        apiKey: ApiKey?,
        authProfiles: List<FfiAuthProfile> = emptyList(),
    ): GlobalTomlConfig {
        val directKey = apiKey?.key.orEmpty()
        val effectiveApiKey =
            if (directKey.isNotBlank()) {
                directKey
            } else {
                resolveOAuthAccessToken(settings.defaultProvider, authProfiles)
            }
        return GlobalTomlConfig(
            provider = SlotAwareAgentConfig.configProvider(settings.defaultProvider),
            model = settings.defaultModel,
            apiKey = effectiveApiKey,
            baseUrl = apiKey?.baseUrl.orEmpty(),
            temperature = settings.defaultTemperature,
            reasoningEffort = settings.reasoningEffort,
            compactContext = settings.compactContext,
            costEnabled = settings.costEnabled,
            dailyLimitUsd = settings.dailyLimitUsd.toDouble(),
            monthlyLimitUsd = settings.monthlyLimitUsd.toDouble(),
            costWarnAtPercent = settings.costWarnAtPercent,
            providerRetries = settings.providerRetries,
            fallbackProviders = splitCsv(settings.fallbackProviders),
            memoryBackend = settings.memoryBackend,
            memoryAutoSave = settings.memoryAutoSave,
            identityJson = settings.identityJson,
            autonomyLevel = settings.autonomyLevel,
            workspaceOnly = settings.workspaceOnly,
            allowedCommands = splitCsv(settings.allowedCommands),
            forbiddenPaths = splitCsv(settings.forbiddenPaths),
            maxActionsPerHour = settings.maxActionsPerHour,
            maxCostPerDayCents = settings.maxCostPerDayCents,
            requireApprovalMediumRisk = settings.requireApprovalMediumRisk,
            blockHighRiskCommands = settings.blockHighRiskCommands,
            tunnelProvider = settings.tunnelProvider,
            tunnelTailscaleFunnel = settings.tunnelTailscaleFunnel,
            tunnelTailscaleHostname = settings.tunnelTailscaleHostname,
            gatewayHost = settings.host,
            gatewayPort = settings.port,
            gatewayRequirePairing = settings.gatewayRequirePairing,
            gatewayAllowPublicBind = settings.gatewayAllowPublicBind,
            gatewayPairedTokens = splitCsv(settings.gatewayPairedTokens),
            gatewayPairRateLimit = settings.gatewayPairRateLimit,
            gatewayWebhookRateLimit = settings.gatewayWebhookRateLimit,
            gatewayIdempotencyTtl = settings.gatewayIdempotencyTtl,
            schedulerEnabled = settings.schedulerEnabled,
            schedulerMaxTasks = settings.schedulerMaxTasks,
            schedulerMaxConcurrent = settings.schedulerMaxConcurrent,
            heartbeatEnabled = settings.heartbeatEnabled,
            heartbeatIntervalMinutes = settings.heartbeatIntervalMinutes,
            observabilityBackend = settings.observabilityBackend,
            observabilityOtelEndpoint = settings.observabilityOtelEndpoint,
            observabilityOtelServiceName = settings.observabilityOtelServiceName,
            memoryHygieneEnabled = settings.memoryHygieneEnabled,
            memoryArchiveAfterDays = settings.memoryArchiveAfterDays,
            memoryPurgeAfterDays = settings.memoryPurgeAfterDays,
            memoryEmbeddingProvider = settings.memoryEmbeddingProvider,
            memoryEmbeddingModel = settings.memoryEmbeddingModel,
            memoryVectorWeight = settings.memoryVectorWeight.toDouble(),
            memoryKeywordWeight = settings.memoryKeywordWeight.toDouble(),
            composioEnabled = settings.composioEnabled,
            composioApiKey = settings.composioApiKey,
            composioEntityId = settings.composioEntityId,
            sharedFolderEnabled = settings.sharedFolderEnabled,
            browserEnabled = settings.browserEnabled,
            browserAllowedDomains = splitCsv(settings.browserAllowedDomains),
            httpRequestEnabled = settings.httpRequestEnabled,
            httpRequestAllowedDomains = splitCsv(settings.httpRequestAllowedDomains),
            httpRequestMaxResponseSize = settings.httpRequestMaxResponseSize,
            httpRequestTimeoutSecs = settings.httpRequestTimeoutSecs,
            webFetchEnabled = settings.webFetchEnabled,
            webFetchAllowedDomains = splitCsv(settings.webFetchAllowedDomains),
            webFetchBlockedDomains = splitCsv(settings.webFetchBlockedDomains),
            webFetchMaxResponseSize = settings.webFetchMaxResponseSize,
            webFetchTimeoutSecs = settings.webFetchTimeoutSecs,
            webSearchEnabled = settings.webSearchEnabled,
            webSearchProvider = settings.webSearchProvider,
            webSearchBraveApiKey = settings.webSearchBraveApiKey,
            webSearchMaxResults = settings.webSearchMaxResults,
            webSearchTimeoutSecs = settings.webSearchTimeoutSecs,
            twitterBrowseEnabled = settings.twitterBrowseEnabled,
            twitterBrowseCookieString = settings.twitterBrowseCookieString,
            twitterBrowseMaxItems = settings.twitterBrowseMaxItems,
            twitterBrowseTimeoutSecs = settings.twitterBrowseTimeoutSecs,
            transcriptionEnabled = settings.transcriptionEnabled,
            transcriptionApiUrl = settings.transcriptionApiUrl,
            transcriptionModel = settings.transcriptionModel,
            transcriptionLanguage = settings.transcriptionLanguage,
            transcriptionMaxDurationSecs = settings.transcriptionMaxDurationSecs,
            multimodalMaxImages = settings.multimodalMaxImages,
            multimodalMaxImageSizeMb = settings.multimodalMaxImageSizeMb,
            multimodalAllowRemoteFetch = settings.multimodalAllowRemoteFetch,
            securitySandboxEnabled = settings.securitySandboxEnabled,
            securitySandboxBackend = settings.securitySandboxBackend,
            securitySandboxFirejailArgs = splitCsv(settings.securitySandboxFirejailArgs),
            securityResourcesMaxMemoryMb = settings.securityResourcesMaxMemoryMb,
            securityResourcesMaxCpuTimeSecs = settings.securityResourcesMaxCpuTimeSecs,
            securityResourcesMaxSubprocesses = settings.securityResourcesMaxSubprocesses,
            securityResourcesMemoryMonitoring = settings.securityResourcesMemoryMonitoring,
            securityAuditEnabled = settings.securityAuditEnabled,
            securityEstopEnabled = settings.securityEstopEnabled,
            securityEstopRequireOtpToResume = settings.securityEstopRequireOtpToResume,
            securityEstopStateFile = "${filesDir.absolutePath}/estop-state.json",
            securityAuditLogPath = "${filesDir.absolutePath}/audit.log",
            skillsPromptInjectionMode = settings.skillsPromptInjectionMode,
            proxyEnabled = settings.proxyEnabled,
            proxyHttpProxy = settings.proxyHttpProxy,
            proxyHttpsProxy = settings.proxyHttpsProxy,
            proxyAllProxy = settings.proxyAllProxy,
            proxyNoProxy = splitCsv(settings.proxyNoProxy),
            proxyScope = settings.proxyScope,
            proxyServiceSelectors = splitCsv(settings.proxyServiceSelectors),
            reliabilityBackoffMs = settings.reliabilityBackoffMs,
            reliabilityApiKeysJson = settings.reliabilityApiKeysJson,
        )
    }

    /**
     * Resolves an OAuth access token for [provider] from the Rust auth-profile store.
     *
     * Used as a fallback when no direct API key is stored. Anthropic tokens
     * (`sk-ant-oat01-...`) are long-lived; OpenAI tokens are short-lived JWTs
     * that are transparently refreshed by the Rust auth service before being
     * returned. Both are injected into the TOML `api_key` field and sent as
     * `Authorization: Bearer` by the upstream provider.
     *
     * Token material never leaves the encrypted Rust store except transiently
     * for the TOML config string.
     *
     * @return The access token string, or empty if no matching profile.
     */
    private fun resolveOAuthAccessToken(
        provider: String,
        @Suppress("UNUSED_PARAMETER") authProfiles: List<FfiAuthProfile>,
    ): String =
        when (provider) {
            "anthropic" ->
                try {
                    com.zeroclaw.ffi
                        .getAnthropicAccessTokenStandalone(
                            dataDir = filesDir.absolutePath,
                        ).orEmpty()
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    Log.w(TAG, "Failed to resolve Anthropic OAuth token: ${e.message}")
                    ""
                }
            "openai" ->
                try {
                    com.zeroclaw.ffi
                        .getOpenaiAccessTokenStandalone(
                            dataDir = filesDir.absolutePath,
                        ).orEmpty()
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    Log.w(TAG, "Failed to resolve OpenAI OAuth token: ${e.message}")
                    ""
                }
            else -> ""
        }

    /**
     * Resolves all enabled agents into [AgentTomlEntry] instances and builds
     * the `[agents.<name>]` TOML sections.
     *
     * For each enabled agent, the provider ID is resolved to an upstream
     * factory name and the corresponding API key is fetched (with OAuth
     * refresh if needed). Agents without a provider or model are skipped.
     *
     * @return TOML string with per-agent sections, or empty if no agents qualify.
     */
    private suspend fun buildAgentsToml(): String {
        val authProfiles = loadStandaloneAuthProfiles()
        val allAgents = agentRepository.agents.first()
        val entries =
            SlotAwareAgentConfig
                .orderedConfiguredAgents(allAgents)
                .map { agent ->
                    val agentKey = apiKeyRepository.getByProviderFresh(agent.provider)
                    if (
                        !SlotAwareAgentConfig.hasUsableDaemonProviderCredentials(
                            provider = agent.provider,
                            apiKey = agentKey,
                            authProfiles = authProfiles,
                        )
                    ) {
                        return@map null
                    }
                    AgentTomlEntry(
                        name = SlotAwareAgentConfig.configName(agent),
                        provider =
                            ConfigTomlBuilder.resolveProvider(
                                SlotAwareAgentConfig.configProvider(agent),
                                agentKey?.baseUrl.orEmpty(),
                            ),
                        model = agent.modelName,
                        apiKey = agentKey?.key.orEmpty(),
                        systemPrompt = agent.systemPrompt,
                        temperature = agent.temperature,
                        maxDepth = agent.maxDepth,
                    )
                }.filterNotNull()
        return ConfigTomlBuilder.buildAgentsToml(entries)
    }

    /**
     * Builds the Tailscale peer agents TOML section from cached discovery state.
     *
     * Reads the peer list from cached settings and converts enabled
     * agent peers (zeroclaw/openclaw kinds) into [PeerTomlEntry] instances.
     *
     * @return TOML string fragment for `[[tailscale_peers.entries]]`, or empty.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun buildPeersToml(): String {
        val settings = settingsRepository.settings.first()
        val cachedJson = settings.tailscaleCachedDiscovery
        if (cachedJson.isBlank()) return ""

        val peers =
            try {
                kotlinx.serialization.json.Json.decodeFromString<
                    List<com.zeroclaw.android.model.CachedTailscalePeer>,
                >(cachedJson)
            } catch (_: Exception) {
                return ""
            }

        val rawEntries =
            peers.flatMap { peer ->
                peer.services
                    .filter { svc ->
                        com.zeroclaw.android.tailscale
                            .isAgentKind(svc.kind)
                    }.map { svc ->
                        Triple(
                            peer,
                            svc,
                            com.zeroclaw.android.tailscale
                                .normalizeKind(svc.kind),
                        )
                    }
            }
        val defaults =
            com.zeroclaw.android.tailscale.PeerMessageRouter.resolveAliasConflicts(
                rawEntries.map { it.third },
            )
        val aliasPrefs = peerAliasPrefs()
        val entries =
            rawEntries.mapIndexed { i, (peer, svc, _) ->
                val savedAlias =
                    aliasPrefs.getString(
                        peerAliasKey(peer.ip, svc.port),
                        null,
                    )
                PeerTomlEntry(
                    ip = peer.ip,
                    hostname = peer.hostname,
                    kind = svc.kind,
                    port = svc.port,
                    alias = savedAlias ?: defaults[i],
                    authRequired = svc.authRequired,
                    enabled = true,
                )
            }

        return ConfigTomlBuilder.buildTailscalePeersToml(entries)
    }

    /**
     * Opens the encrypted preferences file for peer alias storage.
     *
     * @return [android.content.SharedPreferences] instance.
     */
    private fun peerAliasPrefs(): android.content.SharedPreferences {
        val masterKey =
            androidx.security.crypto.MasterKey
                .Builder(this)
                .setKeyScheme(
                    androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM,
                ).build()
        return androidx.security.crypto.EncryptedSharedPreferences.create(
            this,
            "tailscale_peer_tokens",
            masterKey,
            androidx.security.crypto.EncryptedSharedPreferences
                .PrefKeyEncryptionScheme.AES256_SIV,
            androidx.security.crypto.EncryptedSharedPreferences
                .PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /**
     * Generates the encrypted preferences key for a peer alias.
     *
     * @param ip Peer IP address.
     * @param port Peer gateway port.
     * @return Formatted key string.
     */
    private fun peerAliasKey(
        ip: String,
        port: Int,
    ): String {
        val sanitizedIp = ip.replace(Regex("[^a-fA-F0-9.:]"), "")
        return "tailscale_alias_${sanitizedIp}_$port"
    }

    private fun hasUsableDaemonProviderCredentials(
        provider: String,
        apiKey: ApiKey?,
        authProfiles: List<FfiAuthProfile>,
    ): Boolean =
        SlotAwareAgentConfig.hasUsableDaemonProviderCredentials(
            provider = provider,
            apiKey = apiKey,
            authProfiles = authProfiles,
        )

    private fun unsupportedManagedAuthMessage(
        provider: String,
        authProfiles: List<FfiAuthProfile>,
    ): String? =
        when (SlotAwareAgentConfig.connectedManagedAuthDisplayLabel(provider, authProfiles)) {
            "ChatGPT" ->
                "ChatGPT login is connected, but live daemon routing still needs a direct " +
                    "OpenAI API key. Use the OpenAI API slot for now."
            else -> null
        }

    private fun loadStandaloneAuthProfiles(): List<FfiAuthProfile> =
        runCatching {
            AuthProfileStore.listStandalone(this)
        }.getOrElse { emptyList() }

    /**
     * Generates and persists a gateway bearer token if pairing is required
     * and no tokens exist.
     *
     * The token hash is stored in [AppSettings.gatewayPairedTokens] so it
     * appears in the TOML `[gateway].paired_tokens` array, which upstream
     * defines as `Vec<String>` in [GatewayConfig]. The raw token is stored
     * separately for the app's own authenticated requests.
     *
     * Aligned with upstream `zeroclaw/src/config/schema.rs` (v0.1.7):
     * - `paired_tokens: Vec<String>` (line 777)
     * - `require_pairing: bool` (line 771, default `true`)
     *
     * @param settings Current settings, possibly with empty paired tokens.
     * @return Settings with the token hash merged if pre-seeding occurred,
     *   or unchanged if pairing is not required or tokens already exist.
     */
    private suspend fun preSeedGatewayTokenIfNeeded(settings: AppSettings): AppSettings {
        if (!settings.gatewayRequirePairing) return settings
        if (settings.gatewayPairedTokens.isNotBlank()) return settings

        val token = generateBearerToken()
        val hash = sha256Hex(token)

        settingsRepository.setGatewayPairedTokens(hash)
        settingsRepository.setGatewayBearerToken(token)

        if (BuildConfig.DEBUG) {
            Log.i(TAG, "Pre-seeded gateway bearer token for pairing")
        }
        return settings.copy(gatewayPairedTokens = hash)
    }

    /**
     * Generates a ZeroAI-style bearer token with 256-bit entropy.
     *
     * @return A `zc_`-prefixed hex token string.
     */
    private fun generateBearerToken(): String {
        val bytes = ByteArray(BEARER_TOKEN_BYTES)
        java.security.SecureRandom().nextBytes(bytes)
        return "zc_" + bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Returns the SHA-256 hex digest of the given [input] string.
     *
     * @param input The string to hash.
     * @return Lowercase hex-encoded SHA-256 hash.
     */
    private fun sha256Hex(input: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * Reads paired token hashes from upstream config.toml and merges
     * them into [AppSettings.gatewayPairedTokens].
     *
     * Prevents token wipe when [ConfigTomlBuilder] rebuilds TOML from
     * scratch. Tokens from the upstream file that are not already present
     * in settings are appended to the comma-separated list.
     *
     * The upstream `[gateway].paired_tokens` field is defined as
     * `Vec<String>` in `GatewayConfig` (schema.rs line 777).
     *
     * @param settings Current settings.
     * @return Settings with merged tokens, or unchanged if no upstream file.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun mergeUpstreamPairedTokens(settings: AppSettings): AppSettings {
        val rawTokens =
            try {
                val configFile = java.io.File(filesDir, "config.toml")
                if (!configFile.exists()) return settings

                val tomlText = configFile.readText()
                val tokenRegex = Regex("""paired_tokens\s*=\s*\[([^\]]*)]""")
                val matchResult = tokenRegex.find(tomlText)
                matchResult
                    ?.groupValues
                    ?.get(1)
                    ?.split(",")
                    ?.map { it.trim().removeSurrounding("\"") }
                    ?.filter { it.isNotBlank() }
                    .orEmpty()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to merge upstream paired tokens: ${e.message}")
                return settings
            }

        if (rawTokens.isEmpty()) return settings

        val existing =
            settings.gatewayPairedTokens
                .split(",")
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .toSet()

        val merged = (existing + rawTokens).toSet()
        if (merged == existing) return settings

        val csv = merged.joinToString(",")
        settingsRepository.setGatewayPairedTokens(csv)
        return settings.copy(gatewayPairedTokens = csv)
    }

    private fun handleStop() {
        unregisterSharedFolderHandler()
        startJob?.cancel()
        statusPollJob?.cancel()
        retryPolicy.reset()
        persistence.recordStopped()
        serviceScope.launch {
            try {
                bridge.stop()
                activityRepository.record(
                    ActivityType.DAEMON_STOPPED,
                    "Daemon stopped by user",
                )
            } catch (e: FfiException) {
                val safeMsg = LogSanitizer.sanitizeLogMessage(e.message ?: "Unknown error")
                Log.w(TAG, "Daemon stop failed: $safeMsg")
                logRepository.append(LogSeverity.ERROR, TAG, "Stop failed: $safeMsg")
                activityRepository.record(
                    ActivityType.DAEMON_ERROR,
                    "Stop failed: $safeMsg",
                )
            } finally {
                try {
                    val sessionDir = filesDir.resolve("sessions")
                    if (sessionDir.exists()) {
                        sessionDir.deleteRecursively()
                    }
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    Log.w(TAG, "Session cleanup failed", e)
                }
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    /**
     * Resets the retry counter and reattempts startup by re-reading
     * current settings and refreshing OAuth tokens if needed.
     */
    private fun handleRetry() {
        retryPolicy.reset()
        handleStartFromSettings()
    }

    /**
     * Holds the foreground service during an OAuth authentication flow.
     *
     * Posts a minimal foreground notification to prevent the Android
     * cached-app freezer (Android 12+) from freezing the process while
     * the user is authenticating in an external Custom Tab or browser.
     * Without this hold, the loopback OAuth callback server would be
     * frozen and unable to receive the redirect.
     *
     * A safety timeout of [OAUTH_HOLD_TIMEOUT_MS] (120 seconds) ensures
     * the service does not remain in foreground indefinitely if the OAuth
     * flow is abandoned.
     */
    private fun handleOAuthHold() {
        if (bridge.serviceState.value == ServiceState.RUNNING) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Daemon already running, OAuth hold is a no-op")
            }
            return
        }
        val notification =
            notificationManager.buildNotification(ServiceState.STARTING)
        startForegroundCompat(
            DaemonNotificationManager.NOTIFICATION_ID,
            notification,
        )
        serviceScope.launch {
            delay(OAUTH_HOLD_TIMEOUT_MS)
            if (bridge.serviceState.value != ServiceState.RUNNING) {
                Log.w(TAG, "OAuth hold timed out after ${OAUTH_HOLD_TIMEOUT_MS}ms")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    /**
     * Handles a [START_STICKY] restart where the system delivers a null
     * intent after process death.
     *
     * On Android 12+ the system requires [startForeground] within a few
     * seconds of [onStartCommand], even if the service intends to stop
     * immediately. This method posts a minimal foreground notification
     * before checking whether the daemon was previously running. If it
     * was not running, the notification is removed and the service stops
     * itself. Otherwise, the daemon configuration is rebuilt fresh from
     * current settings rather than restored from the stale persisted TOML,
     * so any key rotation or deletion that happened since the last start
     * is reflected immediately.
     */
    private fun handleStickyRestart() {
        val notification =
            notificationManager.buildNotification(ServiceState.STARTING)
        startForegroundCompat(
            DaemonNotificationManager.NOTIFICATION_ID,
            notification,
        )

        if (!persistence.wasRunning()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        if (BuildConfig.DEBUG) {
            Log.i(TAG, "Rebuilding daemon config after process death")
        }
        activityRepository.record(
            ActivityType.DAEMON_STARTED,
            "Daemon restored after process death",
        )
        handleStartFromSettings()
    }

    /**
     * Launches a coroutine that tries to start the daemon, retrying
     * with exponential backoff on failure until [RetryPolicy] is
     * exhausted.
     *
     * The retry loop is wrapped in a `try/finally` so that the wake
     * lock is released even if the coroutine is cancelled mid-retry.
     * When retries are exhausted the service records itself as stopped,
     * detaches the foreground notification (keeping it visible as an
     * error indicator), and calls [stopSelf] to avoid a zombie service.
     */
    @Suppress("CognitiveComplexMethod", "TooGenericExceptionCaught")
    private fun attemptStart(
        configToml: String,
        host: String,
        port: UShort,
        memoryBackend: String = "none",
    ) {
        startJob?.cancel()
        startJob =
            serviceScope.launch {
                try {
                    while (true) {
                        try {
                            try {
                                DiscordDmLinkStore.syncRuntime(application as ZeroAIApplication)
                            } catch (e: Exception) {
                                Log.w(TAG, "Unable to sync Discord DM link before start: ${e.message}")
                            }
                            bridge.start(
                                configToml = configToml,
                                host = host,
                                port = port,
                            )
                            releaseWakeLock()
                            persistence.recordRunning(configToml, host, port)
                            retryPolicy.reset()
                            activityRepository.record(
                                ActivityType.DAEMON_STARTED,
                                "Daemon started on $host:$port",
                            )
                            startStatusPolling()
                            runMemoryHealthCheck(memoryBackend)
                            restorePersistedSessions()
                            initMessageClassifier()
                            try {
                                val curSettings = settingsRepository.settings.first()
                                if (curSettings.sharedFolderEnabled) {
                                    val handler =
                                        SharedFolderCallbackHandler(
                                            context = applicationContext,
                                            getFolderUri = {
                                                runBlocking {
                                                    settingsRepository.settings.first().sharedFolderUri
                                                }
                                            },
                                        )
                                    registerSharedFolderHandler(handler)
                                }
                            } catch (
                                @Suppress("TooGenericExceptionCaught") e: Exception,
                            ) {
                                Log.w(TAG, "SharedFolder handler registration failed: ${e.message}")
                            }
                            try {
                                PendingDiscordOpsStore.drain(
                                    application as ZeroAIApplication,
                                )
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to drain pending Discord ops: ${e.message}")
                            }
                            return@launch
                        } catch (e: FfiException) {
                            val errorMsg =
                                LogSanitizer.sanitizeLogMessage(e.message ?: "Unknown error")
                            val delayMs = retryPolicy.nextDelay()
                            if (delayMs != null) {
                                Log.w(
                                    TAG,
                                    "Daemon start failed: $errorMsg, retrying in ${delayMs}ms",
                                )
                                logRepository.append(
                                    LogSeverity.WARN,
                                    TAG,
                                    "Start failed: $errorMsg (retrying)",
                                )
                                delay(delayMs)
                            } else {
                                handleStartupExhausted(errorMsg)
                                return@launch
                            }
                        }
                    }
                } finally {
                    releaseWakeLock()
                }
            }
    }

    /**
     * Handles the case where all startup retry attempts have been exhausted.
     *
     * Logs the final error, updates the notification to [ServiceState.ERROR],
     * clears the persisted "was running" flag to prevent infinite restart
     * loops, releases the wake lock, and stops the service while keeping
     * the error notification visible for the user.
     *
     * @param errorMsg Description of the last startup failure.
     */
    private fun handleStartupExhausted(errorMsg: String) {
        Log.e(TAG, "Daemon start failed after max retries: $errorMsg")
        logRepository.append(LogSeverity.ERROR, TAG, "Start failed: $errorMsg")
        activityRepository.record(
            ActivityType.DAEMON_ERROR,
            "Start failed: $errorMsg",
        )
        notificationManager.updateNotification(
            ServiceState.ERROR,
            errorDetail = errorMsg,
        )
        persistence.recordStopped()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    /**
     * Starts a coroutine that periodically polls the daemon for status.
     *
     * The poll interval adapts to screen state: [POLL_INTERVAL_FOREGROUND_MS]
     * (5 s) when the screen is on, [POLL_INTERVAL_BACKGROUND_MS] (60 s) when
     * the screen is off. This reduces CPU wake-ups and battery drain during
     * Doze and screen-off periods while still providing responsive UI updates
     * when the user is actively viewing the app.
     */
    private fun startStatusPolling() {
        statusPollJob?.cancel()
        statusPollJob =
            serviceScope.launch {
                while (true) {
                    val interval =
                        if (isScreenOn) {
                            POLL_INTERVAL_FOREGROUND_MS
                        } else {
                            POLL_INTERVAL_BACKGROUND_MS
                        }
                    delay(interval)
                    try {
                        val status = bridge.pollStatus()
                        if (!status.running) {
                            val detail = "Daemon stopped unexpectedly"
                            Log.w(TAG, detail)
                            logRepository.append(LogSeverity.WARN, TAG, detail)
                            notificationManager.updateNotification(ServiceState.STOPPED)
                            persistence.recordStopped()
                            releaseWakeLock()
                            stopForeground(STOP_FOREGROUND_DETACH)
                            stopSelf()
                            return@launch
                        }
                    } catch (e: FfiException) {
                        val safeMsg =
                            LogSanitizer.sanitizeLogMessage(e.message ?: "Unknown error")
                        Log.w(TAG, "Status poll failed: $safeMsg")
                        logRepository.append(
                            LogSeverity.WARN,
                            TAG,
                            "Status poll failed: $safeMsg",
                        )
                        bridge.reportRuntimeFailure(safeMsg)
                        notificationManager.updateNotification(
                            ServiceState.ERROR,
                            errorDetail = safeMsg,
                        )
                    }
                }
            }
    }

    /**
     * Runs the memory backend health probe after a successful daemon start.
     *
     * If the probe fails, a warning is surfaced to the UI through the bridge
     * and logged. Called once per daemon start attempt.
     *
     * @param memoryBackend The configured memory backend identifier.
     */
    private fun runMemoryHealthCheck(memoryBackend: String) {
        val healthResult = bridge.checkMemoryHealth(memoryBackend)
        if (healthResult is MemoryHealthResult.Unhealthy) {
            bridge.setMemoryHealthWarning(healthResult.reason)
            logRepository.append(
                LogSeverity.WARN,
                TAG,
                "Memory health check failed: ${healthResult.reason}",
            )
        }
    }

    /**
     * Persists active session state to app-private storage.
     *
     * Best-effort -- failures are logged but do not crash the service.
     * Called from [onTaskRemoved] and [onDestroy] to survive process death.
     */
    private fun saveActiveSessions() {
        try {
            clawboyStopSession()
        } catch (_: Exception) {
            /** ClawBoy session may not be active -- ignore. */
        }
        try {
            val sessionDir = filesDir.resolve("sessions").absolutePath
            saveSessionState("main", "$sessionDir/main.json")
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            Log.w(TAG, "Session save failed", e)
        }
    }

    /**
     * Restores persisted sessions after a [START_STICKY] restart.
     *
     * Sessions are saved in [saveActiveSessions] during [onTaskRemoved]
     * or [onDestroy], and restored here when the service restarts.
     */
    private fun restorePersistedSessions() {
        try {
            val sessionDir = filesDir.resolve("sessions").absolutePath
            val persisted = listPersistedSessions(sessionDir)
            for (sessionId in persisted) {
                val path = "$sessionDir/$sessionId.json"
                val restored = restoreSessionState(sessionId, path)
                if (restored) {
                    Log.i(TAG, "Restored session: $sessionId")
                }
            }
            if (persisted.isNotEmpty()) {
                Log.i(TAG, "Restored ${persisted.size} session(s)")
            }
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            Log.w(TAG, "Session restore failed", e)
        }
    }

    private fun observeServiceState() {
        serviceScope.launch {
            bridge.serviceState.collect { state ->
                if (state != ServiceState.RUNNING) {
                    statusPollJob?.cancel()
                    statusPollJob = null
                }
                notificationManager.updateNotification(
                    state,
                    errorDetail = if (state == ServiceState.ERROR) bridge.lastError.value else null,
                )
            }
        }
    }

    /**
     * Logs network connectivity changes while the daemon is running.
     *
     * The daemon operates on localhost so connectivity loss does not
     * require pausing or stopping it. This log aids debugging when
     * outbound channel components fail during network gaps.
     */
    private fun observeNetworkState() {
        serviceScope.launch {
            networkMonitor.isConnected.collect { connected ->
                if (bridge.serviceState.value == ServiceState.RUNNING) {
                    if (!connected) {
                        Log.w(TAG, "Network connectivity lost while daemon running")
                        activityRepository.record(
                            ActivityType.NETWORK_CHANGE,
                            "Network connectivity lost",
                        )
                    } else {
                        activityRepository.record(
                            ActivityType.NETWORK_CHANGE,
                            "Network connectivity restored",
                        )
                    }
                }
            }
        }
    }

    /**
     * Registers a [BroadcastReceiver] for [Intent.ACTION_SCREEN_ON] and
     * [Intent.ACTION_SCREEN_OFF] to toggle [isScreenOn].
     *
     * The flag is read by [startStatusPolling] to choose between the
     * foreground and background poll intervals.
     */
    @Suppress("UnspecifiedRegisterReceiverFlag")
    private fun registerScreenReceiver() {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context?,
                    intent: Intent?,
                ) {
                    isScreenOn =
                        intent?.action != Intent.ACTION_SCREEN_OFF
                }
            }
        val filter =
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
        screenReceiver = receiver
    }

    /**
     * Unregisters the screen state [BroadcastReceiver] if it was
     * previously registered.
     */
    private fun unregisterScreenReceiver() {
        screenReceiver?.let { unregisterReceiver(it) }
        screenReceiver = null
    }

    /**
     * Acquires a partial wake lock for the startup phase.
     *
     * If a previous wake lock reference exists but is no longer held
     * (e.g. from a timed expiry or prior release), it is discarded and
     * a fresh lock is acquired. The lock is acquired with a
     * [WAKE_LOCK_TIMEOUT_MS] (3-minute) safety timeout so that the CPU
     * is released even if the startup coroutine is cancelled before
     * [releaseWakeLock] is called.
     */
    private fun acquireWakeLock() {
        wakeLock?.let { existing ->
            if (existing.isHeld) return
            wakeLock = null
        }
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock =
            powerManager
                .newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    WAKE_LOCK_TAG,
                ).apply {
                    acquire(WAKE_LOCK_TIMEOUT_MS)
                }
    }

    /**
     * Releases the partial wake lock if it is currently held and clears
     * the reference.
     *
     * Safe to call multiple times; subsequent calls are no-ops when
     * [wakeLock] is already null or released.
     */
    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) lock.release()
        }
        wakeLock = null
    }

    /**
     * Enters the foreground with a notification, specifying the
     * `specialUse` service type on API 29+ where the 3-argument
     * [startForeground] overload exists.
     *
     * On API 28 and below, the 2-argument overload is used instead
     * because [ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE] and
     * the typed `startForeground(int, Notification, int)` method were
     * not yet available.
     *
     * @param notificationId Stable notification ID.
     * @param notification Foreground notification to display.
     */
    private fun startForegroundCompat(
        notificationId: Int,
        notification: android.app.Notification,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(notificationId, notification)
        }
    }

    /**
     * Initializes the [MessageClassifier] on the bridge after daemon startup.
     *
     * Checks Gemini Nano model availability and creates a [NanoClassifier]
     * if the model is ready. Uses [ProcessLifecycleOwner] to detect whether
     * the app is in the foreground (required by AICore for on-device inference).
     */
    private suspend fun initMessageClassifier() {
        val nanoClassifier =
            try {
                val nanoBridge = OnDeviceInferenceBridge()
                if (nanoBridge.checkModelStatus() is com.zeroclaw.android.model.OnDeviceStatus.Available) {
                    nanoBridge.warmup()
                    setNanoAvailable(true)
                    NanoClassifier(nanoBridge)
                } else {
                    setNanoAvailable(false)
                    null
                }
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                Log.w(TAG, "Nano model unavailable: ${e.message}")
                setNanoAvailable(false)
                null
            }

        bridge.initClassifier(
            nanoClassifier = nanoClassifier,
            isForeground = {
                ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(
                    Lifecycle.State.RESUMED,
                )
            },
        )
        Log.i(TAG, "Message classifier initialized (nano=${nanoClassifier != null})")
    }

    /**
     * Builds the Twitter awareness fragment when connected and enabled.
     *
     * @param settings Current app settings snapshot.
     * @return Fragment string or null if Twitter is not active.
     */
    private fun twitterAwarenessFragment(settings: AppSettings): String? {
        if (!settings.twitterBrowseEnabled) return null
        if (settings.twitterBrowseCookieString.isBlank()) return null
        return "- Twitter/X: Connected. Use twitter_browse to search tweets and timelines."
    }

    /**
     * Builds the email awareness fragment when configured and enabled.
     *
     * @param emailConfig Email configuration from repository, or null.
     * @return Fragment string or null if email is not active.
     */
    private fun emailAwarenessFragment(
        emailConfig: com.zeroclaw.android.data.email.EmailConfigState?,
    ): String? {
        if (emailConfig == null || !emailConfig.isEnabled) return null
        if (emailConfig.address.isBlank()) return null
        return "- Email: Connected as ${emailConfig.address}. Use email tools to check, read, search, and compose email."
    }

    /**
     * Builds the Google Messages awareness fragment when paired with allowlisted conversations.
     *
     * @return Fragment string or null if not connected or no allowlisted conversations.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun googleMessagesAwarenessFragment(): String? {
        val status =
            try {
                com.zeroclaw.ffi.messagesBridgeGetStatus()
            } catch (_: Exception) {
                return null
            }
        if (status !is com.zeroclaw.ffi.FfiBridgeStatus.Connected) return null
        val conversations =
            try {
                com.zeroclaw.ffi.messagesBridgeListConversations()
            } catch (_: Exception) {
                return null
            }
        val allowed = conversations.filter { it.agentAllowed }
        if (allowed.isEmpty()) return null
        val names =
            allowed.joinToString(", ") {
                it.displayName
                    .replace("\"", "'")
                    .replace("\n", " ")
                    .trim()
            }
        return "- Google Messages: Paired via RCS/SMS. Allowlisted conversations: $names. Use read_messages to read transcripts."
    }

    /**
     * Builds the ClawBoy awareness fragment. Always present since the emulator is installed.
     *
     * @return Static ClawBoy fragment.
     */
    private fun clawBoyAwarenessFragment(): String = "- ClawBoy: Game Boy emulator available. Say 'play pokemon' in chat to start a game."

    /**
     * Builds the Tailscale awareness fragment when enabled and services are cached.
     *
     * @param settings Current app settings snapshot.
     * @return Fragment string or null if awareness is disabled or no healthy services.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun tailscaleAwarenessFragment(settings: AppSettings): String? {
        if (!settings.tailscaleAwarenessEnabled) return null
        if (settings.tailscaleCachedDiscovery.isBlank()) return null
        val peers =
            try {
                kotlinx.serialization.json.Json.decodeFromString<
                    List<com.zeroclaw.android.model.CachedTailscalePeer>,
                >(settings.tailscaleCachedDiscovery)
            } catch (_: Exception) {
                return null
            }
        val peersWithServices =
            peers.filter { it.services.any { svc -> svc.healthy } }
        if (peersWithServices.isEmpty()) return null
        val fragment =
            buildString {
                append("- Tailscale: Connected to tailnet.")
                peersWithServices.forEach { peer ->
                    val name = peer.hostname.ifEmpty { peer.ip }
                    append(" Peer \"$name\" (${peer.ip}) has:")
                    peer.services.filter { it.healthy }.forEach { svc ->
                        val label =
                            when (svc.kind) {
                                "ollama" -> "Ollama"
                                "lm_studio" -> "LM Studio"
                                "vllm" -> "vLLM"
                                "local_ai" -> "LocalAI"
                                "zeroclaw" -> "zeroclaw daemon"
                                else -> svc.kind
                            }
                        val ver =
                            if (svc.version != null) {
                                " (${svc.version})"
                            } else {
                                ""
                            }
                        append(" $label$ver on port ${svc.port};")
                    }
                }
            }.trimEnd(';').plus(".")
        return fragment
    }

    /** Constants for [ZeroAIDaemonService]. */
    companion object {
        /** Intent action to start the daemon and enter the foreground. */
        const val ACTION_START = "com.zeroclaw.android.action.START_DAEMON"

        /** Intent action to stop the daemon and leave the foreground. */
        const val ACTION_STOP = "com.zeroclaw.android.action.STOP_DAEMON"

        /** Intent action to retry daemon startup after a failure. */
        const val ACTION_RETRY = "com.zeroclaw.android.action.RETRY_DAEMON"

        /**
         * Intent action to hold the foreground service during OAuth flows.
         *
         * Starts a lightweight foreground notification to prevent the
         * Android cached-app freezer (Android 12+) from freezing the
         * process while the user is authenticating in a Custom Tab.
         * The hold has a [OAUTH_HOLD_TIMEOUT_MS] safety timeout.
         */
        const val ACTION_OAUTH_HOLD = "com.zeroclaw.android.action.OAUTH_HOLD"

        private const val TAG = "ZeroAIDaemonService"
        private const val POLL_INTERVAL_FOREGROUND_MS = 5_000L
        private const val POLL_INTERVAL_BACKGROUND_MS = 60_000L
        private const val WAKE_LOCK_TAG = "zeroclaw:daemon"
        private const val WAKE_LOCK_TIMEOUT_MS = 180_000L
        private const val RESTART_DELAY_MS = 5_000L
        private const val RESTART_REQUEST_CODE = 42
        private const val OAUTH_HOLD_TIMEOUT_MS = 120_000L
        private const val BEARER_TOKEN_BYTES = 32
        private val VALID_PORT_RANGE = 1..65535
    }
}

/**
 * Splits a comma-separated string into a trimmed, non-blank list.
 *
 * @param csv Comma-separated string (may be blank).
 * @return List of trimmed non-blank tokens; empty list if [csv] is blank.
 */
private fun splitCsv(csv: String): List<String> = csv.split(",").map { it.trim() }.filter { it.isNotEmpty() }
