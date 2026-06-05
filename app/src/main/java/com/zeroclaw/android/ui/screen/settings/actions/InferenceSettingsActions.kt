/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.settings.actions

import androidx.compose.runtime.Stable
import com.zeroclaw.android.model.AppSettings
import com.zeroclaw.android.ui.screen.settings.FallbackRouteOption
import com.zeroclaw.android.ui.screen.settings.MANUAL_FALLBACK_ROUTE_ID
import com.zeroclaw.android.ui.screen.settings.buildFallbackRouteOptions
import com.zeroclaw.android.ui.screen.settings.resolveFallbackRouteOptionId
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Inference settings area holder.
 *
 * Owns daemon-affecting defaults (provider, model, temperature, reasoning
 * effort, compact context) and the slot-backed fallback-route selection
 * [StateFlow]s used by the service settings UI.
 */
@Stable
internal class InferenceSettingsActions(
    private val s: SettingsActionScope,
    private val settings: StateFlow<AppSettings>,
) {
    /** Configured slot-backed fallback route choices for service settings. */
    val fallbackRouteOptions: StateFlow<List<FallbackRouteOption>> =
        s.agentRepository.agents
            .map(::buildFallbackRouteOptions)
            .stateIn(
                scope = s.scope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = emptyList(),
            )

    /** Current fallback route selection ID used by the service settings UI. */
    val selectedFallbackRouteId: StateFlow<String> =
        combine(settings, fallbackRouteOptions) { currentSettings, options ->
            resolveFallbackRouteOptionId(currentSettings, options)
        }.stateIn(
            scope = s.scope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = MANUAL_FALLBACK_ROUTE_ID,
        )

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setDefaultProvider */
    fun updateDefaultProvider(provider: String) {
        s.updateDaemonSetting { setDefaultProvider(provider) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setDefaultModel */
    fun updateDefaultModel(model: String) {
        s.updateDaemonSetting { setDefaultModel(model) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setDefaultTemperature */
    fun updateDefaultTemperature(temperature: Float) {
        s.updateDaemonSetting { setDefaultTemperature(temperature) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setReasoningEffort */
    fun updateReasoningEffort(effort: String) {
        s.updateDaemonSetting { setReasoningEffort(effort) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setCompactContext */
    fun updateCompactContext(enabled: Boolean) {
        s.updateDaemonSetting { setCompactContext(enabled) }
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
        s.updateDaemonSetting {
            setDefaultProvider(option.provider)
            setDefaultModel(option.model)
        }
    }
}
