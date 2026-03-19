/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.settings

import com.zeroclaw.android.data.repository.SettingsRepository
import com.zeroclaw.android.model.AppSettings
import com.zeroclaw.android.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * In-memory [SettingsRepository] for unit tests.
 */
internal class TestSettingsRepository : SettingsRepository {
    private val _settings = MutableStateFlow(AppSettings())
    override val settings: Flow<AppSettings> = _settings

    override suspend fun setHost(host: String) = _settings.update { it.copy(host = host) }

    override suspend fun setPort(port: Int) = _settings.update { it.copy(port = port) }

    override suspend fun setAutoStartOnBoot(enabled: Boolean) = _settings.update { it.copy(autoStartOnBoot = enabled) }

    override suspend fun setDefaultProvider(provider: String) = _settings.update { it.copy(defaultProvider = provider) }

    override suspend fun setDefaultModel(model: String) = _settings.update { it.copy(defaultModel = model) }

    override suspend fun setDefaultTemperature(temperature: Float) = _settings.update { it.copy(defaultTemperature = temperature) }

    override suspend fun setReasoningEffort(effort: String) = _settings.update { it.copy(reasoningEffort = effort) }

    override suspend fun setCompactContext(enabled: Boolean) = _settings.update { it.copy(compactContext = enabled) }

    override suspend fun setCostEnabled(enabled: Boolean) = _settings.update { it.copy(costEnabled = enabled) }

    override suspend fun setDailyLimitUsd(limit: Float) = _settings.update { it.copy(dailyLimitUsd = limit) }

    override suspend fun setMonthlyLimitUsd(limit: Float) = _settings.update { it.copy(monthlyLimitUsd = limit) }

    override suspend fun setCostWarnAtPercent(percent: Int) = _settings.update { it.copy(costWarnAtPercent = percent) }

    override suspend fun setProviderRetries(retries: Int) = _settings.update { it.copy(providerRetries = retries) }

    override suspend fun setFallbackProviders(providers: String) = _settings.update { it.copy(fallbackProviders = providers) }

    override suspend fun setMemoryBackend(backend: String) = _settings.update { it.copy(memoryBackend = backend) }

    override suspend fun setMemoryAutoSave(enabled: Boolean) = _settings.update { it.copy(memoryAutoSave = enabled) }

    override suspend fun setIdentityJson(json: String) = _settings.update { it.copy(identityJson = json) }

    override suspend fun setAutonomyLevel(level: String) = _settings.update { it.copy(autonomyLevel = level) }

    override suspend fun setWorkspaceOnly(enabled: Boolean) = _settings.update { it.copy(workspaceOnly = enabled) }

    override suspend fun setAllowedCommands(commands: String) = _settings.update { it.copy(allowedCommands = commands) }

    override suspend fun setForbiddenPaths(paths: String) = _settings.update { it.copy(forbiddenPaths = paths) }

    override suspend fun setMaxActionsPerHour(max: Int) = _settings.update { it.copy(maxActionsPerHour = max) }

    override suspend fun setMaxCostPerDayCents(cents: Int) = _settings.update { it.copy(maxCostPerDayCents = cents) }

    override suspend fun setRequireApprovalMediumRisk(required: Boolean) = _settings.update { it.copy(requireApprovalMediumRisk = required) }

    override suspend fun setBlockHighRiskCommands(blocked: Boolean) = _settings.update { it.copy(blockHighRiskCommands = blocked) }

    override suspend fun setGatewayRequirePairing(required: Boolean) = _settings.update { it.copy(gatewayRequirePairing = required) }

    override suspend fun setGatewayAllowPublicBind(allowed: Boolean) = _settings.update { it.copy(gatewayAllowPublicBind = allowed) }

    override suspend fun setGatewayPairedTokens(tokens: String) = _settings.update { it.copy(gatewayPairedTokens = tokens) }

    override suspend fun setGatewayPairRateLimit(limit: Int) = _settings.update { it.copy(gatewayPairRateLimit = limit) }

    override suspend fun setGatewayWebhookRateLimit(limit: Int) = _settings.update { it.copy(gatewayWebhookRateLimit = limit) }

    override suspend fun setGatewayIdempotencyTtl(seconds: Long) = _settings.update { it.copy(gatewayIdempotencyTtl = seconds) }

    override suspend fun setSchedulerEnabled(enabled: Boolean) = _settings.update { it.copy(schedulerEnabled = enabled) }

    override suspend fun setSchedulerMaxTasks(max: Long) = _settings.update { it.copy(schedulerMaxTasks = max) }

    override suspend fun setSchedulerMaxConcurrent(max: Long) = _settings.update { it.copy(schedulerMaxConcurrent = max) }

    override suspend fun setHeartbeatEnabled(enabled: Boolean) = _settings.update { it.copy(heartbeatEnabled = enabled) }

    override suspend fun setHeartbeatIntervalMinutes(minutes: Long) = _settings.update { it.copy(heartbeatIntervalMinutes = minutes) }

    override suspend fun setMemoryHygieneEnabled(enabled: Boolean) = _settings.update { it.copy(memoryHygieneEnabled = enabled) }

    override suspend fun setMemoryArchiveAfterDays(days: Int) = _settings.update { it.copy(memoryArchiveAfterDays = days) }

    override suspend fun setMemoryPurgeAfterDays(days: Int) = _settings.update { it.copy(memoryPurgeAfterDays = days) }

    override suspend fun setMemoryEmbeddingProvider(provider: String) = _settings.update { it.copy(memoryEmbeddingProvider = provider) }

    override suspend fun setMemoryEmbeddingModel(model: String) = _settings.update { it.copy(memoryEmbeddingModel = model) }

    override suspend fun setMemoryVectorWeight(weight: Float) = _settings.update { it.copy(memoryVectorWeight = weight) }

    override suspend fun setMemoryKeywordWeight(weight: Float) = _settings.update { it.copy(memoryKeywordWeight = weight) }

    override suspend fun setComposioEnabled(enabled: Boolean) = _settings.update { it.copy(composioEnabled = enabled) }

    override suspend fun setComposioApiKey(key: String) = _settings.update { it.copy(composioApiKey = key) }

    override suspend fun setComposioEntityId(entityId: String) = _settings.update { it.copy(composioEntityId = entityId) }

    override suspend fun setSharedFolderEnabled(enabled: Boolean) = _settings.update { it.copy(sharedFolderEnabled = enabled) }

    override suspend fun setSharedFolderUri(uri: String) = _settings.update { it.copy(sharedFolderUri = uri) }

    override suspend fun setBrowserEnabled(enabled: Boolean) = _settings.update { it.copy(browserEnabled = enabled) }

    override suspend fun setBrowserAllowedDomains(domains: String) = _settings.update { it.copy(browserAllowedDomains = domains) }

    override suspend fun setHttpRequestEnabled(enabled: Boolean) = _settings.update { it.copy(httpRequestEnabled = enabled) }

    override suspend fun setHttpRequestAllowedDomains(domains: String) = _settings.update { it.copy(httpRequestAllowedDomains = domains) }

    override suspend fun setWebFetchEnabled(enabled: Boolean) = _settings.update { it.copy(webFetchEnabled = enabled) }

    override suspend fun setWebFetchAllowedDomains(domains: String) = _settings.update { it.copy(webFetchAllowedDomains = domains) }

    override suspend fun setWebFetchBlockedDomains(domains: String) = _settings.update { it.copy(webFetchBlockedDomains = domains) }

    override suspend fun setWebFetchMaxResponseSize(size: Long) = _settings.update { it.copy(webFetchMaxResponseSize = size) }

    override suspend fun setWebFetchTimeoutSecs(secs: Long) = _settings.update { it.copy(webFetchTimeoutSecs = secs) }

    override suspend fun setWebSearchEnabled(enabled: Boolean) = _settings.update { it.copy(webSearchEnabled = enabled) }

    override suspend fun setWebSearchProvider(provider: String) = _settings.update { it.copy(webSearchProvider = provider) }

    override suspend fun setWebSearchBraveApiKey(key: String) = _settings.update { it.copy(webSearchBraveApiKey = key) }

    override suspend fun setWebSearchMaxResults(max: Long) = _settings.update { it.copy(webSearchMaxResults = max) }

    override suspend fun setWebSearchTimeoutSecs(secs: Long) = _settings.update { it.copy(webSearchTimeoutSecs = secs) }

    override suspend fun setWebSearchGoogleApiKey(value: String) { /* no-op */ }

    override suspend fun setWebSearchGoogleCx(value: String) { /* no-op */ }

    override fun getTwitterBrowseHandle(): String? = null

    override suspend fun setTwitterBrowseHandle(handle: String?) { /* no-op */ }

    override suspend fun setTwitterBrowseEnabled(enabled: Boolean) = _settings.update { it.copy(twitterBrowseEnabled = enabled) }

    override suspend fun setTwitterBrowseCookieString(cookieString: String) = _settings.update { it.copy(twitterBrowseCookieString = cookieString) }

    override suspend fun setTwitterBrowseMaxItems(max: Long) = _settings.update { it.copy(twitterBrowseMaxItems = max) }

    override suspend fun setTwitterBrowseTimeoutSecs(secs: Long) = _settings.update { it.copy(twitterBrowseTimeoutSecs = secs) }

    override suspend fun setMemoryQdrantUrl(url: String) = _settings.update { it.copy(memoryQdrantUrl = url) }

    override suspend fun setMemoryQdrantCollection(collection: String) = _settings.update { it.copy(memoryQdrantCollection = collection) }

    override suspend fun setMemoryQdrantApiKey(key: String) = _settings.update { it.copy(memoryQdrantApiKey = key) }

    override suspend fun setQueryClassificationEnabled(enabled: Boolean) = _settings.update { it.copy(queryClassificationEnabled = enabled) }

    override suspend fun setSkillsOpenSkillsEnabled(enabled: Boolean) = _settings.update { it.copy(skillsOpenSkillsEnabled = enabled) }

    override suspend fun setSkillsOpenSkillsDir(dir: String) = _settings.update { it.copy(skillsOpenSkillsDir = dir) }

    override suspend fun setSkillsPromptInjectionMode(mode: String) = _settings.update { it.copy(skillsPromptInjectionMode = mode) }

    override suspend fun setHttpRequestMaxResponseSize(size: Long) = _settings.update { it.copy(httpRequestMaxResponseSize = size) }

    override suspend fun setHttpRequestTimeoutSecs(secs: Long) = _settings.update { it.copy(httpRequestTimeoutSecs = secs) }

    override suspend fun setProxyEnabled(enabled: Boolean) = _settings.update { it.copy(proxyEnabled = enabled) }

    override suspend fun setProxyHttpProxy(proxy: String) = _settings.update { it.copy(proxyHttpProxy = proxy) }

    override suspend fun setProxyHttpsProxy(proxy: String) = _settings.update { it.copy(proxyHttpsProxy = proxy) }

    override suspend fun setProxyAllProxy(proxy: String) = _settings.update { it.copy(proxyAllProxy = proxy) }

    override suspend fun setProxyNoProxy(noProxy: String) = _settings.update { it.copy(proxyNoProxy = noProxy) }

    override suspend fun setProxyScope(scope: String) = _settings.update { it.copy(proxyScope = scope) }

    override suspend fun setProxyServiceSelectors(selectors: String) = _settings.update { it.copy(proxyServiceSelectors = selectors) }

    override suspend fun setReliabilityBackoffMs(ms: Long) = _settings.update { it.copy(reliabilityBackoffMs = ms) }

    override suspend fun setReliabilityApiKeysJson(json: String) = _settings.update { it.copy(reliabilityApiKeysJson = json) }

    override suspend fun setLockEnabled(enabled: Boolean) = _settings.update { it.copy(lockEnabled = enabled) }

    override suspend fun setLockTimeoutMinutes(minutes: Int) = _settings.update { it.copy(lockTimeoutMinutes = minutes) }

    override suspend fun setPinHash(hash: String) = _settings.update { it.copy(pinHash = hash) }

    override suspend fun setPluginRegistryUrl(url: String) = _settings.update { it.copy(pluginRegistryUrl = url) }

    override suspend fun setPluginSyncEnabled(enabled: Boolean) = _settings.update { it.copy(pluginSyncEnabled = enabled) }

    override suspend fun setPluginSyncIntervalHours(hours: Int) = _settings.update { it.copy(pluginSyncIntervalHours = hours) }

    override suspend fun setLastPluginSyncTimestamp(timestamp: Long) = _settings.update { it.copy(lastPluginSyncTimestamp = timestamp) }

    override suspend fun setStripThinkingTags(enabled: Boolean) = _settings.update { it.copy(stripThinkingTags = enabled) }

    override suspend fun setTheme(theme: ThemeMode) = _settings.update { it.copy(theme = theme) }

    override suspend fun setGatewayBearerToken(token: String) { /* no-op */ }

    override suspend fun getGatewayBearerToken(): String = ""

    override suspend fun setTranscriptionEnabled(enabled: Boolean) = _settings.update { it.copy(transcriptionEnabled = enabled) }

    override suspend fun setTranscriptionApiUrl(url: String) = _settings.update { it.copy(transcriptionApiUrl = url) }

    override suspend fun setTranscriptionModel(model: String) = _settings.update { it.copy(transcriptionModel = model) }

    override suspend fun setTranscriptionLanguage(language: String) = _settings.update { it.copy(transcriptionLanguage = language) }

    override suspend fun setTranscriptionMaxDurationSecs(secs: Long) = _settings.update { it.copy(transcriptionMaxDurationSecs = secs) }

    override suspend fun setMultimodalMaxImages(max: Int) = _settings.update { it.copy(multimodalMaxImages = max) }

    override suspend fun setMultimodalMaxImageSizeMb(mb: Int) = _settings.update { it.copy(multimodalMaxImageSizeMb = mb) }

    override suspend fun setMultimodalAllowRemoteFetch(enabled: Boolean) = _settings.update { it.copy(multimodalAllowRemoteFetch = enabled) }

    override fun getSkillInstallWarningSeen(): Flow<Boolean> = MutableStateFlow(false)

    override suspend fun setSkillInstallWarningSeen(seen: Boolean) { /* no-op */ }

    override suspend fun setTailscaleAwarenessEnabled(enabled: Boolean) { /* no-op */ }

    override suspend fun setTailscaleManualPeers(peers: String) { /* no-op */ }

    override suspend fun setTailscaleCachedDiscovery(discovery: String) { /* no-op */ }

    override suspend fun setTailscaleLastScanTimestamp(timestamp: Long) { /* no-op */ }

    override val migrationNoticePending: Flow<Boolean> = MutableStateFlow(false)

    override suspend fun clearMigrationNotice() { /* no-op */ }
}
