/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data.repository

import com.zeroclaw.android.model.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for [SettingsRepository] contract.
 *
 * Uses an in-memory implementation to verify round-trip persistence
 * of each settings field without requiring Android DataStore.
 */
@DisplayName("SettingsRepository")
class DataStoreSettingsRepositoryTest {
    @Test
    @DisplayName("default settings are returned initially")
    fun `default settings are returned initially`() =
        runTest {
            val repo = InMemorySettingsRepository()
            val settings = repo.settings.first()
            assertEquals(AppSettings.DEFAULT_HOST, settings.host)
            assertEquals(AppSettings.DEFAULT_PORT, settings.port)
            assertEquals(false, settings.autoStartOnBoot)
        }

    @Test
    @DisplayName("setHost persists and emits updated value")
    fun `setHost persists and emits updated value`() =
        runTest {
            val repo = InMemorySettingsRepository()
            repo.setHost("192.168.1.1")
            assertEquals("192.168.1.1", repo.settings.first().host)
        }

    @Test
    @DisplayName("setPort persists and emits updated value")
    fun `setPort persists and emits updated value`() =
        runTest {
            val repo = InMemorySettingsRepository()
            repo.setPort(9090)
            assertEquals(9090, repo.settings.first().port)
        }

    @Test
    @DisplayName("setAutoStartOnBoot persists and emits updated value")
    fun `setAutoStartOnBoot persists and emits updated value`() =
        runTest {
            val repo = InMemorySettingsRepository()
            repo.setAutoStartOnBoot(true)
            assertEquals(true, repo.settings.first().autoStartOnBoot)
        }

    @Test
    @DisplayName("multiple updates compose correctly")
    fun `multiple updates compose correctly`() =
        runTest {
            val repo = InMemorySettingsRepository()
            repo.setHost("10.0.0.1")
            repo.setPort(3000)
            repo.setAutoStartOnBoot(true)
            val settings = repo.settings.first()
            assertEquals("10.0.0.1", settings.host)
            assertEquals(3000, settings.port)
            assertEquals(true, settings.autoStartOnBoot)
        }

    @Test
    @DisplayName("setDefaultTemperature persists and emits updated value")
    fun `setDefaultTemperature persists and emits updated value`() =
        runTest {
            val repo = InMemorySettingsRepository()
            repo.setDefaultTemperature(1.5f)
            assertEquals(1.5f, repo.settings.first().defaultTemperature)
        }

    @Test
    @DisplayName("setReasoningEffort persists and emits updated value")
    fun `setReasoningEffort persists and emits updated value`() =
        runTest {
            val repo = InMemorySettingsRepository()
            repo.setReasoningEffort("high")
            assertEquals("high", repo.settings.first().reasoningEffort)
        }

    @Test
    @DisplayName("setCompactContext persists and emits updated value")
    fun `setCompactContext persists and emits updated value`() =
        runTest {
            val repo = InMemorySettingsRepository()
            repo.setCompactContext(true)
            assertEquals(true, repo.settings.first().compactContext)
        }

    @Test
    @DisplayName("setCostEnabled persists and emits updated value")
    fun `setCostEnabled persists and emits updated value`() =
        runTest {
            val repo = InMemorySettingsRepository()
            repo.setCostEnabled(true)
            assertEquals(true, repo.settings.first().costEnabled)
        }

    @Test
    @DisplayName("setDailyLimitUsd persists and emits updated value")
    fun `setDailyLimitUsd persists and emits updated value`() =
        runTest {
            val repo = InMemorySettingsRepository()
            repo.setDailyLimitUsd(25f)
            assertEquals(25f, repo.settings.first().dailyLimitUsd)
        }

    @Test
    @DisplayName("setMonthlyLimitUsd persists and emits updated value")
    fun `setMonthlyLimitUsd persists and emits updated value`() =
        runTest {
            val repo = InMemorySettingsRepository()
            repo.setMonthlyLimitUsd(200f)
            assertEquals(200f, repo.settings.first().monthlyLimitUsd)
        }

    @Test
    @DisplayName("setCostWarnAtPercent persists and emits updated value")
    fun `setCostWarnAtPercent persists and emits updated value`() =
        runTest {
            val repo = InMemorySettingsRepository()
            repo.setCostWarnAtPercent(90)
            assertEquals(90, repo.settings.first().costWarnAtPercent)
        }

    @Test
    @DisplayName("setProviderRetries persists and emits updated value")
    fun `setProviderRetries persists and emits updated value`() =
        runTest {
            val repo = InMemorySettingsRepository()
            repo.setProviderRetries(5)
            assertEquals(5, repo.settings.first().providerRetries)
        }

    @Test
    @DisplayName("setFallbackProviders persists and emits updated value")
    fun `setFallbackProviders persists and emits updated value`() =
        runTest {
            val repo = InMemorySettingsRepository()
            repo.setFallbackProviders("groq, anthropic")
            assertEquals("groq, anthropic", repo.settings.first().fallbackProviders)
        }

    @Test
    @DisplayName("setMemoryBackend persists and emits updated value")
    fun `setMemoryBackend persists and emits updated value`() =
        runTest {
            val repo = InMemorySettingsRepository()
            repo.setMemoryBackend("markdown")
            assertEquals("markdown", repo.settings.first().memoryBackend)
        }

    @Test
    @DisplayName("setMemoryAutoSave persists and emits updated value")
    fun `setMemoryAutoSave persists and emits updated value`() =
        runTest {
            val repo = InMemorySettingsRepository()
            repo.setMemoryAutoSave(false)
            assertEquals(false, repo.settings.first().memoryAutoSave)
        }

    @Test
    @DisplayName("setIdentityJson persists and emits updated value")
    fun `setIdentityJson persists and emits updated value`() =
        runTest {
            val repo = InMemorySettingsRepository()
            repo.setIdentityJson("""{"name":"TestBot"}""")
            assertEquals("""{"name":"TestBot"}""", repo.settings.first().identityJson)
        }

    @Test
    @DisplayName("twitter browse setters persist and emit updated values")
    fun `twitter browse setters persist and emit updated values`() =
        runTest {
            val repo = InMemorySettingsRepository()

            repo.setTwitterBrowseEnabled(true)
            repo.setTwitterBrowseCookieString("ct0=test; auth_token=secret")
            repo.setTwitterBrowseMaxItems(25L)
            repo.setTwitterBrowseTimeoutSecs(45L)

            val settings = repo.settings.first()
            assertEquals(true, settings.twitterBrowseEnabled)
            assertEquals("ct0=test; auth_token=secret", settings.twitterBrowseCookieString)
            assertEquals(25L, settings.twitterBrowseMaxItems)
            assertEquals(45L, settings.twitterBrowseTimeoutSecs)
        }
}

/**
 * In-memory [SettingsRepository] for testing.
 *
 * Stores settings in a [MutableStateFlow] without requiring
 * Android context or DataStore infrastructure.
 */
private class InMemorySettingsRepository : SettingsRepository {
    private val _settings = MutableStateFlow(AppSettings())
    override val settings = _settings
    override val migrationNoticePending = MutableStateFlow(false)

    override suspend fun clearMigrationNotice() { /* no-op */ }

    override suspend fun setHost(host: String) {
        _settings.update { it.copy(host = host) }
    }

    override suspend fun setPort(port: Int) {
        _settings.update { it.copy(port = port) }
    }

    override suspend fun setAutoStartOnBoot(enabled: Boolean) {
        _settings.update { it.copy(autoStartOnBoot = enabled) }
    }

    override suspend fun setDefaultProvider(provider: String) {
        _settings.update { it.copy(defaultProvider = provider) }
    }

    override suspend fun setDefaultModel(model: String) {
        _settings.update { it.copy(defaultModel = model) }
    }

    override suspend fun setDefaultTemperature(temperature: Float) {
        _settings.update { it.copy(defaultTemperature = temperature) }
    }

    override suspend fun setReasoningEffort(effort: String) {
        _settings.update { it.copy(reasoningEffort = effort) }
    }

    override suspend fun setCompactContext(enabled: Boolean) {
        _settings.update { it.copy(compactContext = enabled) }
    }

    override suspend fun setCostEnabled(enabled: Boolean) {
        _settings.update { it.copy(costEnabled = enabled) }
    }

    override suspend fun setDailyLimitUsd(limit: Float) {
        _settings.update { it.copy(dailyLimitUsd = limit) }
    }

    override suspend fun setMonthlyLimitUsd(limit: Float) {
        _settings.update { it.copy(monthlyLimitUsd = limit) }
    }

    override suspend fun setCostWarnAtPercent(percent: Int) {
        _settings.update { it.copy(costWarnAtPercent = percent) }
    }

    override suspend fun setProviderRetries(retries: Int) {
        _settings.update { it.copy(providerRetries = retries) }
    }

    override suspend fun setFallbackProviders(providers: String) {
        _settings.update { it.copy(fallbackProviders = providers) }
    }

    override suspend fun setMemoryBackend(backend: String) {
        _settings.update { it.copy(memoryBackend = backend) }
    }

    override suspend fun setMemoryAutoSave(enabled: Boolean) {
        _settings.update { it.copy(memoryAutoSave = enabled) }
    }

    override suspend fun setIdentityJson(json: String) {
        _settings.update { it.copy(identityJson = json) }
    }

    override suspend fun setAutonomyLevel(level: String) { /* no-op */ }

    override suspend fun setWorkspaceOnly(enabled: Boolean) { /* no-op */ }

    override suspend fun setAllowedCommands(commands: String) { /* no-op */ }

    override suspend fun setForbiddenPaths(paths: String) { /* no-op */ }

    override suspend fun setMaxActionsPerHour(max: Int) { /* no-op */ }

    override suspend fun setMaxCostPerDayCents(cents: Int) { /* no-op */ }

    override suspend fun setRequireApprovalMediumRisk(required: Boolean) { /* no-op */ }

    override suspend fun setBlockHighRiskCommands(blocked: Boolean) { /* no-op */ }

    override suspend fun setGatewayRequirePairing(required: Boolean) { /* no-op */ }

    override suspend fun setGatewayAllowPublicBind(allowed: Boolean) { /* no-op */ }

    override suspend fun setGatewayPairedTokens(tokens: String) { /* no-op */ }

    override suspend fun setGatewayPairRateLimit(limit: Int) { /* no-op */ }

    override suspend fun setGatewayWebhookRateLimit(limit: Int) { /* no-op */ }

    override suspend fun setGatewayIdempotencyTtl(seconds: Long) { /* no-op */ }

    override suspend fun setSchedulerEnabled(enabled: Boolean) { /* no-op */ }

    override suspend fun setSchedulerMaxTasks(max: Long) { /* no-op */ }

    override suspend fun setSchedulerMaxConcurrent(max: Long) { /* no-op */ }

    override suspend fun setHeartbeatEnabled(enabled: Boolean) { /* no-op */ }

    override suspend fun setHeartbeatIntervalMinutes(minutes: Long) { /* no-op */ }

    override suspend fun setMemoryHygieneEnabled(enabled: Boolean) { /* no-op */ }

    override suspend fun setMemoryArchiveAfterDays(days: Int) { /* no-op */ }

    override suspend fun setMemoryPurgeAfterDays(days: Int) { /* no-op */ }

    override suspend fun setMemoryEmbeddingProvider(provider: String) { /* no-op */ }

    override suspend fun setMemoryEmbeddingModel(model: String) { /* no-op */ }

    override suspend fun setMemoryVectorWeight(weight: Float) { /* no-op */ }

    override suspend fun setMemoryKeywordWeight(weight: Float) { /* no-op */ }

    override suspend fun setComposioEnabled(enabled: Boolean) { /* no-op */ }

    override suspend fun setComposioApiKey(key: String) { /* no-op */ }

    override suspend fun setComposioEntityId(entityId: String) { /* no-op */ }

    override suspend fun setSharedFolderEnabled(enabled: Boolean) { /* no-op */ }

    override suspend fun setSharedFolderUri(uri: String) { /* no-op */ }

    override suspend fun setBrowserEnabled(enabled: Boolean) { /* no-op */ }

    override suspend fun setBrowserAllowedDomains(domains: String) { /* no-op */ }

    override suspend fun setHttpRequestEnabled(enabled: Boolean) { /* no-op */ }

    override suspend fun setHttpRequestAllowedDomains(domains: String) { /* no-op */ }

    override suspend fun setWebFetchEnabled(enabled: Boolean) { /* no-op */ }

    override suspend fun setWebFetchAllowedDomains(domains: String) { /* no-op */ }

    override suspend fun setWebFetchBlockedDomains(domains: String) { /* no-op */ }

    override suspend fun setWebFetchMaxResponseSize(size: Long) { /* no-op */ }

    override suspend fun setWebFetchTimeoutSecs(secs: Long) { /* no-op */ }

    override suspend fun setWebSearchEnabled(enabled: Boolean) { /* no-op */ }

    override suspend fun setWebSearchProvider(provider: String) { /* no-op */ }

    override suspend fun setWebSearchBraveApiKey(key: String) { /* no-op */ }

    override suspend fun setWebSearchMaxResults(max: Long) { /* no-op */ }

    override suspend fun setWebSearchTimeoutSecs(secs: Long) { /* no-op */ }

    override suspend fun setWebSearchGoogleApiKey(value: String) { /* no-op */ }

    override suspend fun setWebSearchGoogleCx(value: String) { /* no-op */ }

    override fun getTwitterBrowseHandle(): String? = null

    override suspend fun setTwitterBrowseHandle(handle: String?) { /* no-op */ }

    override suspend fun setTwitterBrowseEnabled(enabled: Boolean) {
        _settings.update { it.copy(twitterBrowseEnabled = enabled) }
    }

    override suspend fun setTwitterBrowseCookieString(cookieString: String) {
        _settings.update { it.copy(twitterBrowseCookieString = cookieString) }
    }

    override suspend fun setTwitterBrowseMaxItems(max: Long) {
        _settings.update { it.copy(twitterBrowseMaxItems = max) }
    }

    override suspend fun setTwitterBrowseTimeoutSecs(secs: Long) {
        _settings.update { it.copy(twitterBrowseTimeoutSecs = secs) }
    }

    override suspend fun setMultimodalMaxImages(max: Int) { /* no-op */ }

    override suspend fun setMultimodalMaxImageSizeMb(mb: Int) { /* no-op */ }

    override suspend fun setMultimodalAllowRemoteFetch(enabled: Boolean) { /* no-op */ }

    override suspend fun setTranscriptionEnabled(enabled: Boolean) { /* no-op */ }

    override suspend fun setTranscriptionApiUrl(url: String) { /* no-op */ }

    override suspend fun setTranscriptionModel(model: String) { /* no-op */ }

    override suspend fun setTranscriptionLanguage(language: String) { /* no-op */ }

    override suspend fun setTranscriptionMaxDurationSecs(secs: Long) { /* no-op */ }

    override suspend fun setMemoryQdrantUrl(url: String) { /* no-op */ }

    override suspend fun setMemoryQdrantCollection(collection: String) { /* no-op */ }

    override suspend fun setMemoryQdrantApiKey(key: String) { /* no-op */ }

    override suspend fun setQueryClassificationEnabled(enabled: Boolean) { /* no-op */ }

    override suspend fun setSkillsOpenSkillsEnabled(enabled: Boolean) { /* no-op */ }

    override suspend fun setSkillsOpenSkillsDir(dir: String) { /* no-op */ }

    override suspend fun setSkillsPromptInjectionMode(mode: String) { /* no-op */ }

    override suspend fun setHttpRequestMaxResponseSize(size: Long) { /* no-op */ }

    override suspend fun setHttpRequestTimeoutSecs(secs: Long) { /* no-op */ }

    override suspend fun setProxyEnabled(enabled: Boolean) { /* no-op */ }

    override suspend fun setProxyHttpProxy(proxy: String) { /* no-op */ }

    override suspend fun setProxyHttpsProxy(proxy: String) { /* no-op */ }

    override suspend fun setProxyAllProxy(proxy: String) { /* no-op */ }

    override suspend fun setProxyNoProxy(noProxy: String) { /* no-op */ }

    override suspend fun setProxyScope(scope: String) { /* no-op */ }

    override suspend fun setProxyServiceSelectors(selectors: String) { /* no-op */ }

    override suspend fun setReliabilityBackoffMs(ms: Long) { /* no-op */ }

    override suspend fun setReliabilityApiKeysJson(json: String) { /* no-op */ }

    override suspend fun setLockEnabled(enabled: Boolean) { /* no-op */ }

    override suspend fun setLockTimeoutMinutes(minutes: Int) { /* no-op */ }

    override suspend fun setPinHash(hash: String) { /* no-op */ }

    override suspend fun setPluginRegistryUrl(url: String) { /* no-op */ }

    override suspend fun setPluginSyncEnabled(enabled: Boolean) { /* no-op */ }

    override suspend fun setPluginSyncIntervalHours(hours: Int) { /* no-op */ }

    override suspend fun setLastPluginSyncTimestamp(timestamp: Long) { /* no-op */ }

    override suspend fun setStripThinkingTags(enabled: Boolean) { /* no-op */ }

    override suspend fun setTheme(theme: com.zeroclaw.android.model.ThemeMode) { /* no-op */ }

    override suspend fun setGatewayBearerToken(token: String) { /* no-op */ }

    override suspend fun getGatewayBearerToken(): String = ""

    override fun getSkillInstallWarningSeen(): kotlinx.coroutines.flow.Flow<Boolean> = MutableStateFlow(false)

    override suspend fun setSkillInstallWarningSeen(seen: Boolean) { /* no-op */ }

    override suspend fun setTailscaleAwarenessEnabled(enabled: Boolean) { /* no-op */ }

    override suspend fun setTailscaleManualPeers(peers: String) { /* no-op */ }

    override suspend fun setTailscaleCachedDiscovery(discovery: String) { /* no-op */ }

    override suspend fun setTailscaleLastScanTimestamp(timestamp: Long) { /* no-op */ }
}
