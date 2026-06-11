/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zeroclaw.android.ZeroAIApplication
import com.zeroclaw.android.data.ProviderSlotRegistry
import com.zeroclaw.android.model.Agent
import com.zeroclaw.android.model.AppSettings
import com.zeroclaw.android.service.SlotAwareAgentConfig
import com.zeroclaw.android.ui.screen.settings.actions.AutonomySettingsActions
import com.zeroclaw.android.ui.screen.settings.actions.CostSettingsActions
import com.zeroclaw.android.ui.screen.settings.actions.InferenceSettingsActions
import com.zeroclaw.android.ui.screen.settings.actions.LifecycleSettingsActions
import com.zeroclaw.android.ui.screen.settings.actions.MemorySettingsActions
import com.zeroclaw.android.ui.screen.settings.actions.NetworkSettingsActions
import com.zeroclaw.android.ui.screen.settings.actions.OutputSettingsActions
import com.zeroclaw.android.ui.screen.settings.actions.ProxySettingsActions
import com.zeroclaw.android.ui.screen.settings.actions.ReliabilitySettingsActions
import com.zeroclaw.android.ui.screen.settings.actions.STOP_TIMEOUT_MS
import com.zeroclaw.android.ui.screen.settings.actions.SchedulerSettingsActions
import com.zeroclaw.android.ui.screen.settings.actions.SettingsActionScope
import com.zeroclaw.android.ui.screen.settings.actions.StartupSettingsActions
import com.zeroclaw.android.ui.screen.settings.actions.ToolsSettingsActions
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

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
 * This is a thin facade: each public updater forwards to one of the
 * per-area holders (under the `actions` package), which share a single
 * [SettingsActionScope]. Forwarding preserves the original method signatures
 * and behavior exactly, including the restart-required distribution.
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

    private val actionScope =
        SettingsActionScope(
            repository = repository,
            daemonBridge = daemonBridge,
            onboardingRepository = onboardingRepository,
            agentRepository = agentRepository,
            scope = viewModelScope,
        )

    private val network = NetworkSettingsActions(actionScope)
    private val startup = StartupSettingsActions(actionScope)
    private val inference = InferenceSettingsActions(actionScope, settings)
    private val cost = CostSettingsActions(actionScope)
    private val reliability = ReliabilitySettingsActions(actionScope)
    private val memory = MemorySettingsActions(actionScope)
    private val autonomy = AutonomySettingsActions(actionScope)
    private val scheduler = SchedulerSettingsActions(actionScope)
    private val tools = ToolsSettingsActions(actionScope)
    private val proxy = ProxySettingsActions(actionScope)
    private val output = OutputSettingsActions(actionScope)
    private val lifecycle = LifecycleSettingsActions(actionScope)

    /** Configured slot-backed fallback route choices for service settings. */
    internal val fallbackRouteOptions: StateFlow<List<FallbackRouteOption>>
        get() = inference.fallbackRouteOptions

    /** Current fallback route selection ID used by the service settings UI. */
    internal val selectedFallbackRouteId: StateFlow<String>
        get() = inference.selectedFallbackRouteId

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setHost */
    fun updateHost(host: String) = network.updateHost(host)

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setPort */
    fun updatePort(port: Int) = network.updatePort(port)

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setAutoStartOnBoot */
    fun updateAutoStartOnBoot(enabled: Boolean) = startup.updateAutoStartOnBoot(enabled)

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setDefaultProvider */
    fun updateDefaultProvider(provider: String) = inference.updateDefaultProvider(provider)

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setDefaultModel */
    fun updateDefaultModel(model: String) = inference.updateDefaultModel(model)

    /**
     * Selects a slot-backed fallback route for daemon startup defaults.
     *
     * The stored settings continue to use provider/model strings; this
     * helper simply writes the pair represented by the selected option.
     *
     * @param optionId ID returned by [fallbackRouteOptions].
     */
    fun selectFallbackRouteOption(optionId: String) = inference.selectFallbackRouteOption(optionId)

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setDefaultTemperature */
    fun updateDefaultTemperature(temperature: Float) = inference.updateDefaultTemperature(temperature)

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setReasoningEffort */
    fun updateReasoningEffort(effort: String) = inference.updateReasoningEffort(effort)

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setCompactContext */
    fun updateCompactContext(enabled: Boolean) = inference.updateCompactContext(enabled)

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setCostEnabled */
    fun updateCostEnabled(enabled: Boolean) = cost.updateCostEnabled(enabled)

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setDailyLimitUsd */
    fun updateDailyLimitUsd(limit: Float) = cost.updateDailyLimitUsd(limit)

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setMonthlyLimitUsd */
    fun updateMonthlyLimitUsd(limit: Float) = cost.updateMonthlyLimitUsd(limit)

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setCostWarnAtPercent */
    fun updateCostWarnAtPercent(percent: Int) = cost.updateCostWarnAtPercent(percent)

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setProviderRetries */
    fun updateProviderRetries(retries: Int) = reliability.updateProviderRetries(retries)

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setFallbackProviders */
    fun updateFallbackProviders(providers: String) = reliability.updateFallbackProviders(providers)

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setMemoryBackend */
    fun updateMemoryBackend(backend: String) = memory.updateMemoryBackend(backend)

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setMemoryAutoSave */
    fun updateMemoryAutoSave(enabled: Boolean) = memory.updateMemoryAutoSave(enabled)

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setAutonomyLevel */
    fun updateAutonomyLevel(level: String) = autonomy.updateAutonomyLevel(level)

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setWorkspaceOnly */
    fun updateWorkspaceOnly(enabled: Boolean) = autonomy.updateWorkspaceOnly(enabled)

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setAllowedCommands */
    fun updateAllowedCommands(commands: String) = autonomy.updateAllowedCommands(commands)

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setForbiddenPaths */
    fun updateForbiddenPaths(paths: String) = autonomy.updateForbiddenPaths(paths)

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setMaxActionsPerHour */
    fun updateMaxActionsPerHour(max: Int) = autonomy.updateMaxActionsPerHour(max)

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setMaxCostPerDayCents */
    fun updateMaxCostPerDayCents(cents: Int) = autonomy.updateMaxCostPerDayCents(cents)

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setRequireApprovalMediumRisk */
    fun updateRequireApprovalMediumRisk(required: Boolean) = autonomy.updateRequireApprovalMediumRisk(required)

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setBlockHighRiskCommands */
    fun updateBlockHighRiskCommands(blocked: Boolean) = autonomy.updateBlockHighRiskCommands(blocked)

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setSchedulerEnabled */
    fun updateSchedulerEnabled(enabled: Boolean) = scheduler.updateSchedulerEnabled(enabled)

    /**
     * Updates the scheduler max-tasks limit, clamped to at least 1.
     *
     * The value is coerced because the engine/daemon rejects a value of 0.
     *
     * @see com.zeroclaw.android.data.repository.SettingsRepository.setSchedulerMaxTasks
     */
    fun updateSchedulerMaxTasks(max: Long) = scheduler.updateSchedulerMaxTasks(max)

    /**
     * Updates the scheduler max-concurrent limit, clamped to at least 1.
     *
     * The value is coerced because the engine/daemon rejects a value of 0.
     *
     * @see com.zeroclaw.android.data.repository.SettingsRepository.setSchedulerMaxConcurrent
     */
    fun updateSchedulerMaxConcurrent(max: Long) = scheduler.updateSchedulerMaxConcurrent(max)

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setHeartbeatEnabled */
    fun updateHeartbeatEnabled(enabled: Boolean) = scheduler.updateHeartbeatEnabled(enabled)

    /**
     * Updates the heartbeat tick interval in minutes, clamped to at least 1.
     *
     * The value is coerced because the engine/daemon rejects a value of 0.
     *
     * @see com.zeroclaw.android.data.repository.SettingsRepository.setHeartbeatIntervalMinutes
     */
    fun updateHeartbeatIntervalMinutes(minutes: Long) = scheduler.updateHeartbeatIntervalMinutes(minutes)

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setMemoryHygieneEnabled */
    fun updateMemoryHygieneEnabled(enabled: Boolean) = memory.updateMemoryHygieneEnabled(enabled)

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setMemoryArchiveAfterDays */
    fun updateMemoryArchiveAfterDays(days: Int) = memory.updateMemoryArchiveAfterDays(days)

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setMemoryPurgeAfterDays */
    fun updateMemoryPurgeAfterDays(days: Int) = memory.updateMemoryPurgeAfterDays(days)

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setMemoryEmbeddingProvider */
    fun updateMemoryEmbeddingProvider(provider: String) = memory.updateMemoryEmbeddingProvider(provider)

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setMemoryEmbeddingModel */
    fun updateMemoryEmbeddingModel(model: String) = memory.updateMemoryEmbeddingModel(model)

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setMemoryVectorWeight */
    fun updateMemoryVectorWeight(weight: Float) = memory.updateMemoryVectorWeight(weight)

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setMemoryKeywordWeight */
    fun updateMemoryKeywordWeight(weight: Float) = memory.updateMemoryKeywordWeight(weight)

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setComposioEnabled */
    fun updateComposioEnabled(enabled: Boolean) = tools.updateComposioEnabled(enabled)

    /** Persists shared folder enabled state and restarts the daemon. */
    fun updateSharedFolderEnabled(enabled: Boolean) = tools.updateSharedFolderEnabled(enabled)

    /** Persists the shared folder SAF URI and restarts the daemon. */
    fun updateSharedFolderUri(uri: String) = tools.updateSharedFolderUri(uri)

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setComposioApiKey */
    fun updateComposioApiKey(key: String) = tools.updateComposioApiKey(key)

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setComposioEntityId */
    fun updateComposioEntityId(entityId: String) = tools.updateComposioEntityId(entityId)

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setBrowserEnabled */
    fun updateBrowserEnabled(enabled: Boolean) = tools.updateBrowserEnabled(enabled)

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setBrowserAllowedDomains */
    fun updateBrowserAllowedDomains(domains: String) = tools.updateBrowserAllowedDomains(domains)

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setHttpRequestEnabled */
    fun updateHttpRequestEnabled(enabled: Boolean) = tools.updateHttpRequestEnabled(enabled)

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setHttpRequestAllowedDomains */
    fun updateHttpRequestAllowedDomains(domains: String) = tools.updateHttpRequestAllowedDomains(domains)

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setHttpRequestMaxResponseSize */
    fun updateHttpRequestMaxResponseSize(size: Long) = tools.updateHttpRequestMaxResponseSize(size)

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setHttpRequestTimeoutSecs */
    fun updateHttpRequestTimeoutSecs(secs: Long) = tools.updateHttpRequestTimeoutSecs(secs)

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setWebFetchEnabled */
    fun updateWebFetchEnabled(enabled: Boolean) = tools.updateWebFetchEnabled(enabled)

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setWebFetchAllowedDomains */
    fun updateWebFetchAllowedDomains(domains: String) = tools.updateWebFetchAllowedDomains(domains)

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setWebFetchBlockedDomains */
    fun updateWebFetchBlockedDomains(domains: String) = tools.updateWebFetchBlockedDomains(domains)

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setWebFetchMaxResponseSize */
    fun updateWebFetchMaxResponseSize(size: Long) = tools.updateWebFetchMaxResponseSize(size)

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setWebFetchTimeoutSecs */
    fun updateWebFetchTimeoutSecs(secs: Long) = tools.updateWebFetchTimeoutSecs(secs)

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setWebSearchEnabled */
    fun updateWebSearchEnabled(enabled: Boolean) = tools.updateWebSearchEnabled(enabled)

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setWebSearchProvider */
    fun updateWebSearchProvider(provider: String) = tools.updateWebSearchProvider(provider)

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setWebSearchBraveApiKey */
    fun updateWebSearchBraveApiKey(key: String) = tools.updateWebSearchBraveApiKey(key)

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setWebSearchTavilyApiKey */
    fun updateWebSearchTavilyApiKey(key: String) = tools.updateWebSearchTavilyApiKey(key)

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setWebSearchSearxngUrl */
    fun updateWebSearchSearxngUrl(url: String) = tools.updateWebSearchSearxngUrl(url)

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setWebSearchMaxResults */
    fun updateWebSearchMaxResults(max: Long) = tools.updateWebSearchMaxResults(max)

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setWebSearchTimeoutSecs */
    fun updateWebSearchTimeoutSecs(secs: Long) = tools.updateWebSearchTimeoutSecs(secs)

    /**
     * Updates the meta search rate limit, clamped into 1..60.
     *
     * The value is coerced because the engine treats 0 as unlimited,
     * which the app never intends.
     *
     * @see com.zeroclaw.android.data.repository.SettingsRepository.setWebSearchRequestsPerMinute
     */
    fun updateWebSearchRequestsPerMinute(requestsPerMinute: Long) = tools.updateWebSearchRequestsPerMinute(requestsPerMinute)

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setTwitterBrowseEnabled */
    fun updateTwitterBrowseEnabled(enabled: Boolean) = tools.updateTwitterBrowseEnabled(enabled)

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setTwitterBrowseMaxItems */
    fun updateTwitterBrowseMaxItems(max: Long) = tools.updateTwitterBrowseMaxItems(max)

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setTwitterBrowseTimeoutSecs */
    fun updateTwitterBrowseTimeoutSecs(secs: Long) = tools.updateTwitterBrowseTimeoutSecs(secs)

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setMultimodalMaxImages */
    fun updateMultimodalMaxImages(max: Int) = tools.updateMultimodalMaxImages(max)

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setMultimodalMaxImageSizeMb */
    fun updateMultimodalMaxImageSizeMb(mb: Int) = tools.updateMultimodalMaxImageSizeMb(mb)

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setMultimodalAllowRemoteFetch */
    fun updateMultimodalAllowRemoteFetch(enabled: Boolean) = tools.updateMultimodalAllowRemoteFetch(enabled)

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setProxyEnabled */
    fun updateProxyEnabled(enabled: Boolean) = proxy.updateProxyEnabled(enabled)

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setProxyHttpProxy */
    fun updateProxyHttpProxy(proxy: String) = this.proxy.updateProxyHttpProxy(proxy)

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setProxyHttpsProxy */
    fun updateProxyHttpsProxy(proxy: String) = this.proxy.updateProxyHttpsProxy(proxy)

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setProxyAllProxy */
    fun updateProxyAllProxy(proxy: String) = this.proxy.updateProxyAllProxy(proxy)

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setProxyNoProxy */
    fun updateProxyNoProxy(noProxy: String) = proxy.updateProxyNoProxy(noProxy)

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setProxyScope */
    fun updateProxyScope(scope: String) = proxy.updateProxyScope(scope)

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setProxyServiceSelectors */
    fun updateProxyServiceSelectors(selectors: String) = proxy.updateProxyServiceSelectors(selectors)

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setReliabilityBackoffMs */
    fun updateReliabilityBackoffMs(ms: Long) = reliability.updateReliabilityBackoffMs(ms)

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setReliabilityApiKeysJson */
    fun updateReliabilityApiKeysJson(json: String) = reliability.updateReliabilityApiKeysJson(json)

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setStripThinkingTags */
    fun updateStripThinkingTags(enabled: Boolean) = output.updateStripThinkingTags(enabled)

    /**
     * Updates the enabled state of an official plugin in [AppSettings].
     *
     * Dispatches to the correct setting based on the official plugin
     * constant. Vision has no enable toggle (always active), so toggling
     * it is a no-op.
     *
     * @param pluginId One of the official plugin constant IDs.
     * @param enabled New enabled state.
     */
    fun updateOfficialPluginEnabled(
        pluginId: String,
        enabled: Boolean,
    ) = tools.updateOfficialPluginEnabled(pluginId, enabled)

    /**
     * Resets onboarding completion state so the setup wizard is shown again.
     *
     * Clears the AIEOS identity JSON so the wizard generates a fresh
     * identity document. Existing API keys and other settings are preserved.
     */
    fun resetOnboarding() = lifecycle.resetOnboarding()
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
