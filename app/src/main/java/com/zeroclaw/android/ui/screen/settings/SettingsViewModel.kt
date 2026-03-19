/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zeroclaw.android.ZeroAIApplication
import com.zeroclaw.android.data.ProviderSlotRegistry
import com.zeroclaw.android.data.repository.SettingsRepository
import com.zeroclaw.android.model.Agent
import com.zeroclaw.android.model.AppSettings
import com.zeroclaw.android.model.OfficialPlugins
import com.zeroclaw.android.model.ThemeMode
import com.zeroclaw.android.service.SlotAwareAgentConfig
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Sentinel ID for the manual fallback route entry in settings UI. */
internal const val MANUAL_FALLBACK_ROUTE_ID = "manual"

/**
 * Slot-backed fallback route option exposed to the service config UI.
 *
 * @property id Stable selection ID derived from [provider] and [model].
 * @property label User-facing source label, usually a slot display name.
 * @property provider Canonical daemon-facing provider ID.
 * @property model Fallback model name paired with [provider].
 */
internal data class FallbackRouteOption(
    val id: String,
    val label: String,
    val provider: String,
    val model: String,
)

/**
 * ViewModel for the settings screen hierarchy.
 *
 * Exposes the current [AppSettings] as a [StateFlow] and provides
 * methods for updating individual settings via the repository.
 *
 * @param application Application context for accessing the settings repository.
 */
@Suppress("TooManyFunctions")
class SettingsViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val repository = (application as ZeroAIApplication).settingsRepository
    private val onboardingRepository = (application as ZeroAIApplication).onboardingRepository
    private val daemonBridge = (application as ZeroAIApplication).daemonBridge
    private val agentRepository = (application as ZeroAIApplication).agentRepository

    /** Current application settings, collected as state. */
    val settings: StateFlow<AppSettings> =
        repository.settings.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = AppSettings(),
        )

    /** Whether a daemon restart is required to apply settings changes. */
    val restartRequired: StateFlow<Boolean> = daemonBridge.restartRequired

    /** Configured slot-backed fallback route choices for service settings. */
    internal val fallbackRouteOptions: StateFlow<List<FallbackRouteOption>> =
        agentRepository.agents
            .map(::buildFallbackRouteOptions)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = emptyList(),
            )

    /** Current fallback route selection ID used by the service settings UI. */
    internal val selectedFallbackRouteId: StateFlow<String> =
        combine(settings, fallbackRouteOptions) { currentSettings, options ->
            resolveFallbackRouteOptionId(currentSettings, options)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = MANUAL_FALLBACK_ROUTE_ID,
        )

    /**
     * Updates a daemon-affecting setting and marks a restart as required
     * if the daemon is currently running.
     */
    private fun updateDaemonSetting(block: suspend SettingsRepository.() -> Unit) {
        viewModelScope.launch {
            repository.block()
            daemonBridge.markRestartRequired()
        }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setHost */
    fun updateHost(host: String) {
        updateDaemonSetting { setHost(host) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setPort */
    fun updatePort(port: Int) {
        updateDaemonSetting { setPort(port) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setAutoStartOnBoot */
    fun updateAutoStartOnBoot(enabled: Boolean) {
        viewModelScope.launch { repository.setAutoStartOnBoot(enabled) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setDefaultProvider */
    fun updateDefaultProvider(provider: String) {
        updateDaemonSetting { setDefaultProvider(provider) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setDefaultModel */
    fun updateDefaultModel(model: String) {
        updateDaemonSetting { setDefaultModel(model) }
    }

    /**
     * Selects a slot-backed fallback route for daemon startup defaults.
     *
     * The stored settings continue to use provider/model strings; this
     * helper simply writes the pair represented by the selected option.
     *
     * @param optionId ID returned by [fallbackRouteOptions].
     */
    fun selectFallbackRouteOption(optionId: String) {
        val option = fallbackRouteOptions.value.firstOrNull { it.id == optionId } ?: return
        updateDaemonSetting {
            setDefaultProvider(option.provider)
            setDefaultModel(option.model)
        }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setDefaultTemperature */
    fun updateDefaultTemperature(temperature: Float) {
        updateDaemonSetting { setDefaultTemperature(temperature) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setReasoningEffort */
    fun updateReasoningEffort(effort: String) {
        updateDaemonSetting { setReasoningEffort(effort) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setCompactContext */
    fun updateCompactContext(enabled: Boolean) {
        updateDaemonSetting { setCompactContext(enabled) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setCostEnabled */
    fun updateCostEnabled(enabled: Boolean) {
        updateDaemonSetting { setCostEnabled(enabled) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setDailyLimitUsd */
    fun updateDailyLimitUsd(limit: Float) {
        updateDaemonSetting { setDailyLimitUsd(limit) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setMonthlyLimitUsd */
    fun updateMonthlyLimitUsd(limit: Float) {
        updateDaemonSetting { setMonthlyLimitUsd(limit) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setCostWarnAtPercent */
    fun updateCostWarnAtPercent(percent: Int) {
        updateDaemonSetting { setCostWarnAtPercent(percent) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setProviderRetries */
    fun updateProviderRetries(retries: Int) {
        updateDaemonSetting { setProviderRetries(retries) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setFallbackProviders */
    fun updateFallbackProviders(providers: String) {
        updateDaemonSetting { setFallbackProviders(providers) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setMemoryBackend */
    fun updateMemoryBackend(backend: String) {
        updateDaemonSetting { setMemoryBackend(backend) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setMemoryAutoSave */
    fun updateMemoryAutoSave(enabled: Boolean) {
        updateDaemonSetting { setMemoryAutoSave(enabled) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setAutonomyLevel */
    fun updateAutonomyLevel(level: String) {
        updateDaemonSetting { setAutonomyLevel(level) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setWorkspaceOnly */
    fun updateWorkspaceOnly(enabled: Boolean) {
        updateDaemonSetting { setWorkspaceOnly(enabled) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setAllowedCommands */
    fun updateAllowedCommands(commands: String) {
        updateDaemonSetting { setAllowedCommands(commands) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setForbiddenPaths */
    fun updateForbiddenPaths(paths: String) {
        updateDaemonSetting { setForbiddenPaths(paths) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setMaxActionsPerHour */
    fun updateMaxActionsPerHour(max: Int) {
        updateDaemonSetting { setMaxActionsPerHour(max) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setMaxCostPerDayCents */
    fun updateMaxCostPerDayCents(cents: Int) {
        updateDaemonSetting { setMaxCostPerDayCents(cents) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setRequireApprovalMediumRisk */
    fun updateRequireApprovalMediumRisk(required: Boolean) {
        updateDaemonSetting { setRequireApprovalMediumRisk(required) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setBlockHighRiskCommands */
    fun updateBlockHighRiskCommands(blocked: Boolean) {
        updateDaemonSetting { setBlockHighRiskCommands(blocked) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setSchedulerEnabled */
    fun updateSchedulerEnabled(enabled: Boolean) {
        updateDaemonSetting { setSchedulerEnabled(enabled) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setSchedulerMaxTasks */
    fun updateSchedulerMaxTasks(max: Long) {
        updateDaemonSetting { setSchedulerMaxTasks(max) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setSchedulerMaxConcurrent */
    fun updateSchedulerMaxConcurrent(max: Long) {
        updateDaemonSetting { setSchedulerMaxConcurrent(max) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setHeartbeatEnabled */
    fun updateHeartbeatEnabled(enabled: Boolean) {
        updateDaemonSetting { setHeartbeatEnabled(enabled) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setHeartbeatIntervalMinutes */
    fun updateHeartbeatIntervalMinutes(minutes: Long) {
        updateDaemonSetting { setHeartbeatIntervalMinutes(minutes) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setMemoryHygieneEnabled */
    fun updateMemoryHygieneEnabled(enabled: Boolean) {
        updateDaemonSetting { setMemoryHygieneEnabled(enabled) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setMemoryArchiveAfterDays */
    fun updateMemoryArchiveAfterDays(days: Int) {
        updateDaemonSetting { setMemoryArchiveAfterDays(days) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setMemoryPurgeAfterDays */
    fun updateMemoryPurgeAfterDays(days: Int) {
        updateDaemonSetting { setMemoryPurgeAfterDays(days) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setMemoryEmbeddingProvider */
    fun updateMemoryEmbeddingProvider(provider: String) {
        updateDaemonSetting { setMemoryEmbeddingProvider(provider) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setMemoryEmbeddingModel */
    fun updateMemoryEmbeddingModel(model: String) {
        updateDaemonSetting { setMemoryEmbeddingModel(model) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setMemoryVectorWeight */
    fun updateMemoryVectorWeight(weight: Float) {
        updateDaemonSetting { setMemoryVectorWeight(weight) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setMemoryKeywordWeight */
    fun updateMemoryKeywordWeight(weight: Float) {
        updateDaemonSetting { setMemoryKeywordWeight(weight) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setComposioEnabled */
    fun updateComposioEnabled(enabled: Boolean) {
        updateDaemonSetting { setComposioEnabled(enabled) }
    }

    /** Persists shared folder enabled state and restarts the daemon. */
    fun updateSharedFolderEnabled(enabled: Boolean) {
        updateDaemonSetting { setSharedFolderEnabled(enabled) }
    }

    /** Persists the shared folder SAF URI and restarts the daemon. */
    fun updateSharedFolderUri(uri: String) {
        updateDaemonSetting { setSharedFolderUri(uri) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setComposioApiKey */
    fun updateComposioApiKey(key: String) {
        updateDaemonSetting { setComposioApiKey(key) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setComposioEntityId */
    fun updateComposioEntityId(entityId: String) {
        updateDaemonSetting { setComposioEntityId(entityId) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setBrowserEnabled */
    fun updateBrowserEnabled(enabled: Boolean) {
        updateDaemonSetting { setBrowserEnabled(enabled) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setBrowserAllowedDomains */
    fun updateBrowserAllowedDomains(domains: String) {
        updateDaemonSetting { setBrowserAllowedDomains(domains) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setHttpRequestEnabled */
    fun updateHttpRequestEnabled(enabled: Boolean) {
        updateDaemonSetting { setHttpRequestEnabled(enabled) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setHttpRequestAllowedDomains */
    fun updateHttpRequestAllowedDomains(domains: String) {
        updateDaemonSetting { setHttpRequestAllowedDomains(domains) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setHttpRequestMaxResponseSize */
    fun updateHttpRequestMaxResponseSize(size: Long) {
        updateDaemonSetting { setHttpRequestMaxResponseSize(size) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setHttpRequestTimeoutSecs */
    fun updateHttpRequestTimeoutSecs(secs: Long) {
        updateDaemonSetting { setHttpRequestTimeoutSecs(secs) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setWebFetchEnabled */
    fun updateWebFetchEnabled(enabled: Boolean) {
        updateDaemonSetting { setWebFetchEnabled(enabled) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setWebFetchAllowedDomains */
    fun updateWebFetchAllowedDomains(domains: String) {
        updateDaemonSetting { setWebFetchAllowedDomains(domains) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setWebFetchBlockedDomains */
    fun updateWebFetchBlockedDomains(domains: String) {
        updateDaemonSetting { setWebFetchBlockedDomains(domains) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setWebFetchMaxResponseSize */
    fun updateWebFetchMaxResponseSize(size: Long) {
        updateDaemonSetting { setWebFetchMaxResponseSize(size) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setWebFetchTimeoutSecs */
    fun updateWebFetchTimeoutSecs(secs: Long) {
        updateDaemonSetting { setWebFetchTimeoutSecs(secs) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setWebSearchEnabled */
    fun updateWebSearchEnabled(enabled: Boolean) {
        updateDaemonSetting { setWebSearchEnabled(enabled) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setWebSearchProvider */
    fun updateWebSearchProvider(provider: String) {
        updateDaemonSetting { setWebSearchProvider(provider) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setWebSearchBraveApiKey */
    fun updateWebSearchBraveApiKey(key: String) {
        updateDaemonSetting { setWebSearchBraveApiKey(key) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setWebSearchGoogleApiKey */
    fun updateWebSearchGoogleApiKey(key: String) {
        updateDaemonSetting { setWebSearchGoogleApiKey(key) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setWebSearchGoogleCx */
    fun updateWebSearchGoogleCx(cx: String) {
        updateDaemonSetting { setWebSearchGoogleCx(cx) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setWebSearchMaxResults */
    fun updateWebSearchMaxResults(max: Long) {
        updateDaemonSetting { setWebSearchMaxResults(max) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setWebSearchTimeoutSecs */
    fun updateWebSearchTimeoutSecs(secs: Long) {
        updateDaemonSetting { setWebSearchTimeoutSecs(secs) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setTwitterBrowseEnabled */
    fun updateTwitterBrowseEnabled(enabled: Boolean) {
        updateDaemonSetting { setTwitterBrowseEnabled(enabled) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setTwitterBrowseCookieString */
    fun updateTwitterBrowseCookieString(cookieString: String) {
        updateDaemonSetting { setTwitterBrowseCookieString(cookieString) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setTwitterBrowseMaxItems */
    fun updateTwitterBrowseMaxItems(max: Long) {
        updateDaemonSetting { setTwitterBrowseMaxItems(max) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setTwitterBrowseTimeoutSecs */
    fun updateTwitterBrowseTimeoutSecs(secs: Long) {
        updateDaemonSetting { setTwitterBrowseTimeoutSecs(secs) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setTranscriptionEnabled */
    fun updateTranscriptionEnabled(enabled: Boolean) {
        updateDaemonSetting { setTranscriptionEnabled(enabled) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setTranscriptionApiUrl */
    fun updateTranscriptionApiUrl(url: String) {
        updateDaemonSetting { setTranscriptionApiUrl(url) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setTranscriptionModel */
    fun updateTranscriptionModel(model: String) {
        updateDaemonSetting { setTranscriptionModel(model) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setTranscriptionLanguage */
    fun updateTranscriptionLanguage(language: String) {
        updateDaemonSetting { setTranscriptionLanguage(language) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setTranscriptionMaxDurationSecs */
    fun updateTranscriptionMaxDurationSecs(secs: Long) {
        updateDaemonSetting { setTranscriptionMaxDurationSecs(secs) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setMultimodalMaxImages */
    fun updateMultimodalMaxImages(max: Int) {
        updateDaemonSetting { setMultimodalMaxImages(max) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setMultimodalMaxImageSizeMb */
    fun updateMultimodalMaxImageSizeMb(mb: Int) {
        updateDaemonSetting { setMultimodalMaxImageSizeMb(mb) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setMultimodalAllowRemoteFetch */
    fun updateMultimodalAllowRemoteFetch(enabled: Boolean) {
        updateDaemonSetting { setMultimodalAllowRemoteFetch(enabled) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setMemoryQdrantUrl */
    fun updateMemoryQdrantUrl(url: String) {
        updateDaemonSetting { setMemoryQdrantUrl(url) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setMemoryQdrantCollection */
    fun updateMemoryQdrantCollection(collection: String) {
        updateDaemonSetting { setMemoryQdrantCollection(collection) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setMemoryQdrantApiKey */
    fun updateMemoryQdrantApiKey(key: String) {
        updateDaemonSetting { setMemoryQdrantApiKey(key) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setQueryClassificationEnabled */
    fun updateQueryClassificationEnabled(enabled: Boolean) {
        updateDaemonSetting { setQueryClassificationEnabled(enabled) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setProxyEnabled */
    fun updateProxyEnabled(enabled: Boolean) {
        updateDaemonSetting { setProxyEnabled(enabled) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setProxyHttpProxy */
    fun updateProxyHttpProxy(proxy: String) {
        updateDaemonSetting { setProxyHttpProxy(proxy) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setProxyHttpsProxy */
    fun updateProxyHttpsProxy(proxy: String) {
        updateDaemonSetting { setProxyHttpsProxy(proxy) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setProxyAllProxy */
    fun updateProxyAllProxy(proxy: String) {
        updateDaemonSetting { setProxyAllProxy(proxy) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setProxyNoProxy */
    fun updateProxyNoProxy(noProxy: String) {
        updateDaemonSetting { setProxyNoProxy(noProxy) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setProxyScope */
    fun updateProxyScope(scope: String) {
        updateDaemonSetting { setProxyScope(scope) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setProxyServiceSelectors */
    fun updateProxyServiceSelectors(selectors: String) {
        updateDaemonSetting { setProxyServiceSelectors(selectors) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setReliabilityBackoffMs */
    fun updateReliabilityBackoffMs(ms: Long) {
        updateDaemonSetting { setReliabilityBackoffMs(ms) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setReliabilityApiKeysJson */
    fun updateReliabilityApiKeysJson(json: String) {
        updateDaemonSetting { setReliabilityApiKeysJson(json) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setStripThinkingTags */
    fun updateStripThinkingTags(enabled: Boolean) {
        viewModelScope.launch { repository.setStripThinkingTags(enabled) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setTheme */
    fun updateTheme(theme: ThemeMode) {
        viewModelScope.launch { repository.setTheme(theme) }
    }

    /**
     * Updates the enabled state of an official plugin in [AppSettings].
     *
     * Dispatches to the correct setting based on the [OfficialPlugins]
     * constant. Vision has no enable toggle (always active), so toggling
     * it is a no-op.
     *
     * @param pluginId One of the [OfficialPlugins] constant IDs.
     * @param enabled New enabled state.
     */
    fun updateOfficialPluginEnabled(
        pluginId: String,
        enabled: Boolean,
    ) {
        when (pluginId) {
            OfficialPlugins.WEB_SEARCH -> updateWebSearchEnabled(enabled)
            OfficialPlugins.WEB_FETCH -> updateWebFetchEnabled(enabled)
            OfficialPlugins.HTTP_REQUEST -> updateHttpRequestEnabled(enabled)
            OfficialPlugins.COMPOSIO -> updateComposioEnabled(enabled)
            OfficialPlugins.TRANSCRIPTION -> updateTranscriptionEnabled(enabled)
            OfficialPlugins.QUERY_CLASSIFICATION -> updateQueryClassificationEnabled(enabled)
            OfficialPlugins.SHARED_FOLDER -> updateSharedFolderEnabled(enabled)
            else -> {}
        }
    }

    /**
     * Resets onboarding completion state so the setup wizard is shown again.
     *
     * Clears the AIEOS identity JSON so the wizard generates a fresh
     * identity document. Existing API keys and other settings are preserved.
     */
    fun resetOnboarding() {
        viewModelScope.launch {
            repository.setIdentityJson("")
            daemonBridge.markRestartRequired()
            onboardingRepository.reset()
        }
    }

    /** Constants for [SettingsViewModel]. */
    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}

internal fun buildFallbackRouteOptions(agents: List<Agent>): List<FallbackRouteOption> =
    SlotAwareAgentConfig
        .orderedConfiguredAgents(agents)
        .map { agent ->
            val provider = SlotAwareAgentConfig.configProvider(agent)
            val label =
                ProviderSlotRegistry
                    .findById(agent.slotId.takeIf { it.isNotBlank() } ?: agent.id)
                    ?.displayName
                    ?: SlotAwareAgentConfig.configName(agent)
            FallbackRouteOption(
                id = fallbackRouteOptionId(provider = provider, model = agent.modelName),
                label = label,
                provider = provider,
                model = agent.modelName,
            )
        }.distinctBy { option ->
            option.id
        }

internal fun resolveFallbackRouteOptionId(
    settings: AppSettings,
    options: List<FallbackRouteOption>,
): String {
    val provider = SlotAwareAgentConfig.configProvider(settings.defaultProvider)
    val model = settings.defaultModel.trim()
    if (provider.isBlank() || model.isBlank()) {
        return MANUAL_FALLBACK_ROUTE_ID
    }
    return options
        .firstOrNull { option ->
            option.provider == provider && option.model == model
        }?.id ?: MANUAL_FALLBACK_ROUTE_ID
}

private fun fallbackRouteOptionId(
    provider: String,
    model: String,
): String = "route:${provider.lowercase()}:${model.lowercase()}"
