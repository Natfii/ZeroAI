/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.settings.doctor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zeroclaw.android.ZeroAIApplication
import com.zeroclaw.android.data.oauth.AuthProfileStore
import com.zeroclaw.android.model.AppSettings
import com.zeroclaw.android.model.DiagnosticCheck
import com.zeroclaw.android.model.DoctorSummary
import com.zeroclaw.android.service.ConfigTomlBuilder
import com.zeroclaw.android.service.DaemonGlobalConfigMapper
import com.zeroclaw.android.service.DoctorValidator
import com.zeroclaw.android.service.SlotAwareAgentConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * ViewModel for the ZeroAI Doctor diagnostics screen.
 *
 * Orchestrates sequential execution of diagnostic check categories
 * and provides incremental UI updates as each category completes.
 *
 * @param application Application context for accessing repositories.
 */
class DoctorViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val app = application as ZeroAIApplication

    private val validator =
        DoctorValidator(
            context = application,
            agentRepository = app.agentRepository,
            apiKeyRepository = app.apiKeyRepository,
        )

    private val _checks = MutableStateFlow<List<DiagnosticCheck>>(emptyList())

    /** All diagnostic check results, incrementally populated as categories complete. */
    val checks: StateFlow<List<DiagnosticCheck>> = _checks.asStateFlow()

    private val _isRunning = MutableStateFlow(false)

    /** Whether diagnostic checks are currently executing. */
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _summary = MutableStateFlow<DoctorSummary?>(null)

    /** Aggregated check summary, available after all checks complete. */
    val summary: StateFlow<DoctorSummary?> = _summary.asStateFlow()

    /**
     * Runs all diagnostic check categories sequentially.
     *
     * Each category's results are appended to [checks] as they complete,
     * providing incremental UI updates. The [summary] is computed after
     * all categories finish.
     *
     * Safe to call multiple times; resets state on each invocation.
     */
    fun runAllChecks() {
        if (_isRunning.value) return
        viewModelScope.launch {
            _isRunning.value = true
            _checks.value = emptyList()
            _summary.value = null

            val accumulated = mutableListOf<DiagnosticCheck>()
            val agents = app.agentRepository.agents.first()

            val configChecks = validator.runConfigChecks(preloadedAgents = agents)
            accumulated.addAll(configChecks)
            _checks.value = accumulated.toList()

            val identityJson =
                app.settingsRepository.settings
                    .first()
                    .identityJson
            val identityCheck =
                DoctorValidator.checkIdentityHealth(identityJson)
            accumulated.add(identityCheck)
            _checks.value = accumulated.toList()

            val apiKeyChecks = validator.runApiKeyChecks(preloadedAgents = agents)
            accumulated.addAll(apiKeyChecks)
            _checks.value = accumulated.toList()

            val connectivityChecks = validator.runConnectivityChecks()
            accumulated.addAll(connectivityChecks)
            _checks.value = accumulated.toList()

            val daemonChecks = validator.runDaemonHealthChecks()
            accumulated.addAll(daemonChecks)
            _checks.value = accumulated.toList()

            val channelChecks =
                validator.runChannelChecks(
                    configToml = buildCurrentToml(),
                    dataDir = app.filesDir.absolutePath,
                )
            accumulated.addAll(channelChecks)
            _checks.value = accumulated.toList()

            val traceChecks = validator.runTraceChecks()
            accumulated.addAll(traceChecks)
            _checks.value = accumulated.toList()

            val systemChecks = validator.runSystemChecks()
            accumulated.addAll(systemChecks)
            _checks.value = accumulated.toList()

            _summary.value = DoctorSummary.from(accumulated)
            _isRunning.value = false
        }
    }

    /**
     * Builds the full TOML config string from current settings, agents,
     * and channel configurations.
     *
     * Delegates the global section to [DaemonGlobalConfigMapper] — the
     * single source of truth shared with
     * [ZeroAIDaemonService][com.zeroclaw.android.service.ZeroAIDaemonService]
     * — so the FFI doctor check sees the same config the daemon would use.
     *
     * @return A valid TOML configuration string.
     */
    private suspend fun buildCurrentToml(): String {
        val settings = app.settingsRepository.settings.first()
        val effectiveSettings = resolveEffectiveDefaults(settings)
        val apiKey =
            app.apiKeyRepository.getByProviderFresh(
                effectiveSettings.defaultProvider,
            )
        val globalConfig =
            DaemonGlobalConfigMapper.toGlobalTomlConfig(
                context = app,
                settings = effectiveSettings,
                apiKey = apiKey,
                apiKeyValue = apiKey?.key.orEmpty(),
                hubAppContext = null,
            )
        val baseToml = ConfigTomlBuilder.build(globalConfig)
        val channelsToml =
            ConfigTomlBuilder.buildChannelsToml(
                app.channelConfigRepository.getEnabledWithSecrets(),
                app.discordGuildId(),
            )
        val agentsToml = buildAgentsToml()
        return baseToml + channelsToml + agentsToml
    }

    /**
     * Derives effective default provider and model from the agent list.
     *
     * The first enabled agent with a non-blank provider and model name
     * overrides the DataStore values in [settings]. Mirrors the logic in
     * [ZeroAIDaemonService][com.zeroclaw.android.service.ZeroAIDaemonService].
     *
     * @param settings Current application settings (may have stale defaults).
     * @return A copy of [settings] with provider and model overridden by the
     *   primary agent, or unchanged if no qualifying agent exists.
     */
    private suspend fun resolveEffectiveDefaults(
        settings: AppSettings,
    ): AppSettings {
        val agents = app.agentRepository.agents.first()
        val authProfiles = AuthProfileStore.listStandalone(app)
        return SlotAwareAgentConfig.resolveEffectiveDefaults(settings, agents) { agent ->
            val key = app.apiKeyRepository.getByProvider(agent.provider)
            SlotAwareAgentConfig.hasUsableProviderCredentials(
                provider = agent.provider,
                apiKey = key,
                authProfiles = authProfiles,
            )
        }
    }

    /** Delegates to [AgentTomlAssembler] — the single source of truth. */
    private suspend fun buildAgentsToml(): String =
        com.zeroclaw.android.service.AgentTomlAssembler
            .assemble(app)
}
