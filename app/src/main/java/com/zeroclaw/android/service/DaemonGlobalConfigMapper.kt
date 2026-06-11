/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.service

import android.content.Context
import com.zeroclaw.android.model.ApiKey
import com.zeroclaw.android.model.AppSettings

/**
 * Pure mapping from [AppSettings] + resolved API key → [GlobalTomlConfig].
 *
 * Previously this logic lived in near-duplicate copies — one inside
 * [ZeroAIDaemonService] (canonical, complete) and drifted clones in
 * `ApiKeysViewModel` and `DoctorViewModel` (missing fields like
 * `sharedFolderEnabled`, `twitterBrowse*`, `multimodal*`,
 * `skillsPromptInjectionMode`, and the security file paths). Drift meant a
 * hot-reload, a doctor check, or a fresh cold start could each see a
 * *different* daemon config. Centralising removes that hazard.
 *
 * The full field mapping lives in the context-free overload so JVM unit
 * tests and ViewModels can exercise it directly; the [Context] overload is
 * a thin wrapper that derives [SecurityFilePaths] from `filesDir`.
 */
object DaemonGlobalConfigMapper {
    /**
     * Filesystem locations of the security artefacts the daemon persists.
     *
     * Bundled into one value type so the context-free
     * [toGlobalTomlConfig] overload stays within the detekt parameter
     * threshold and callers cannot supply one path without the other.
     *
     * @property estopStateFile Absolute path of the emergency-stop state file.
     * @property auditLogPath Absolute path of the security audit log.
     */
    data class SecurityFilePaths(
        val estopStateFile: String,
        val auditLogPath: String,
    ) {
        /** Factory helpers for [SecurityFilePaths]. */
        companion object {
            /**
             * Derives the canonical daemon paths under the app-private files directory.
             *
             * @param context Any context; only `filesDir` is read.
             * @return Paths matching what [ZeroAIDaemonService] hands the engine.
             */
            fun fromContext(context: Context): SecurityFilePaths {
                val filesDir = context.filesDir.absolutePath
                return SecurityFilePaths(
                    estopStateFile = "$filesDir/estop-state.json",
                    auditLogPath = "$filesDir/audit.log",
                )
            }
        }
    }

    /**
     * Build a [GlobalTomlConfig] from the active state.
     *
     * @param context Application context, used for `filesDir`-relative paths.
     * @param settings Effective application settings (already resolved against agents).
     * @param apiKey Resolved default-provider API key, or null.
     * @param apiKeyValue Plain-text key value (may differ from `apiKey.key`
     *   for OAuth bearer paths — caller resolves).
     * @param hubAppContext Awareness fragments accumulated by feature contributors.
     */
    fun toGlobalTomlConfig(
        context: Context,
        settings: AppSettings,
        apiKey: ApiKey?,
        apiKeyValue: String,
        hubAppContext: String?,
    ): GlobalTomlConfig =
        toGlobalTomlConfig(
            settings = settings,
            apiKey = apiKey,
            apiKeyValue = apiKeyValue,
            hubAppContext = hubAppContext,
            securityFilePaths = SecurityFilePaths.fromContext(context),
        )

    /**
     * Context-free core mapping — the single source of truth for every
     * settings-driven [GlobalTomlConfig] field.
     *
     * @param settings Effective application settings (already resolved against agents).
     * @param apiKey Resolved default-provider API key, or null.
     * @param apiKeyValue Plain-text key value (may differ from `apiKey.key`
     *   for OAuth bearer paths — caller resolves).
     * @param hubAppContext Awareness fragments accumulated by feature contributors.
     * @param securityFilePaths Locations for the e-stop state file and audit log.
     */
    @Suppress("LongMethod")
    fun toGlobalTomlConfig(
        settings: AppSettings,
        apiKey: ApiKey?,
        apiKeyValue: String,
        hubAppContext: String?,
        securityFilePaths: SecurityFilePaths,
    ): GlobalTomlConfig =
        GlobalTomlConfig(
            provider = SlotAwareAgentConfig.configProvider(settings.defaultProvider),
            model = settings.defaultModel,
            apiKey = apiKeyValue,
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
            webSearchTavilyApiKey = settings.webSearchTavilyApiKey,
            webSearchSearxngUrl = settings.webSearchSearxngUrl,
            webSearchMaxResults = settings.webSearchMaxResults,
            webSearchTimeoutSecs = settings.webSearchTimeoutSecs,
            webSearchRequestsPerMinute = settings.webSearchRequestsPerMinute,
            twitterBrowseEnabled = settings.twitterBrowseEnabled,
            twitterBrowseMaxItems = settings.twitterBrowseMaxItems,
            twitterBrowseTimeoutSecs = settings.twitterBrowseTimeoutSecs,
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
            securityEstopStateFile = securityFilePaths.estopStateFile,
            securityAuditLogPath = securityFilePaths.auditLogPath,
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
            hubAppContext = hubAppContext,
        )

    /**
     * Splits a comma-separated string into trimmed non-empty entries.
     *
     * Centralised here so the same parser is used by every caller that
     * builds a [GlobalTomlConfig] — drift between `splitCsv`
     * implementations was a real source of silent settings divergence.
     */
    fun splitCsv(csv: String): List<String> = csv.split(",").map { it.trim() }.filter { it.isNotEmpty() }
}
