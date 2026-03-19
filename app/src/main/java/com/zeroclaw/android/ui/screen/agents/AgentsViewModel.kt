/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.agents

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zeroclaw.android.ZeroAIApplication
import com.zeroclaw.android.data.ProviderRegistry
import com.zeroclaw.android.data.ProviderSlot
import com.zeroclaw.android.data.ProviderSlotRegistry
import com.zeroclaw.android.data.SlotCredentialType
import com.zeroclaw.android.data.oauth.AuthProfileStore
import com.zeroclaw.android.data.oauth.canonicalManagedProvider
import com.zeroclaw.android.model.Agent
import com.zeroclaw.android.model.ApiKey
import com.zeroclaw.ffi.FfiAuthProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * UI model for one fixed provider slot in the Agents catalog.
 *
 * @property slotId Stable slot identifier.
 * @property displayName User-facing slot label.
 * @property providerName Provider name shown under the title.
 * @property connectionSummary Current connection summary for the slot.
 * @property modelName Configured model name, or null when unset.
 * @property isEnabled Whether the slot's agent row is enabled.
 * @property requiresConnection Whether credentials are still missing.
 * @property routesModelRequests Whether this slot participates in daemon routing.
 */
data class AgentSlotItem(
    val slotId: String,
    val displayName: String,
    val providerName: String,
    val connectionSummary: String,
    val modelName: String?,
    val isEnabled: Boolean,
    val requiresConnection: Boolean,
    val routesModelRequests: Boolean,
)

/**
 * ViewModel for the fixed-slot Agents catalog.
 *
 * Builds the seven slot cards from the persisted agent rows, API-key repository,
 * and Rust-owned auth-profile store.
 *
 * @param application Application context for repository access.
 */
class AgentsViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val app = application as ZeroAIApplication
    private val repository = app.agentRepository
    private val apiKeyRepository = app.apiKeyRepository
    private val daemonBridge = app.daemonBridge

    private val _searchQuery = MutableStateFlow("")
    private val refreshSignal = MutableStateFlow(0)

    /** Current search query text. */
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val authProfiles: StateFlow<List<FfiAuthProfile>> =
        refreshSignal
            .flatMapLatest {
                flow {
                    emit(runCatching { AuthProfileStore.listStandalone(app) }.getOrDefault(emptyList()))
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    /** Fixed slot cards shown on the Agents screen. */
    val slots: StateFlow<List<AgentSlotItem>> =
        combine(repository.agents, apiKeyRepository.keys, authProfiles, _searchQuery) {
            agents,
            apiKeys,
            profiles,
            query,
            ->
            ProviderSlotRegistry
                .all()
                .map { slot ->
                    slot.toUiItem(agents = agents, apiKeys = apiKeys, profiles = profiles)
                }.filter { item ->
                    query.isBlank() ||
                        item.displayName.contains(query, ignoreCase = true) ||
                        item.providerName.contains(query, ignoreCase = true) ||
                        item.connectionSummary.contains(query, ignoreCase = true)
                }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    init {
        refreshConnections()
    }

    /**
     * Updates the search query used to filter slot cards.
     *
     * @param query New search text.
     */
    fun updateSearch(query: String) {
        _searchQuery.value = query
    }

    /**
     * Toggles the enabled state of the seeded slot row identified by [slotId].
     *
     * @param slotId Stable provider-slot row ID.
     */
    fun toggleSlot(slotId: String) {
        val slot = ProviderSlotRegistry.findById(slotId) ?: return
        if (!slot.routesModelRequests) return
        viewModelScope.launch {
            repository.toggleEnabled(slotId)
            daemonBridge.markRestartRequired()
        }
    }

    /** Reloads standalone auth-profile state after returning from slot detail flows. */
    fun refreshConnections() {
        refreshSignal.value += 1
    }

    @Suppress("CyclomaticComplexMethod", "CognitiveComplexMethod")
    private fun ProviderSlot.toUiItem(
        agents: List<Agent>,
        apiKeys: List<ApiKey>,
        profiles: List<FfiAuthProfile>,
    ): AgentSlotItem {
        val agent = agents.firstOrNull { it.slotId == slotId || it.id == slotId }
        val apiKey = matchingApiKey(apiKeys)
        val authProfile = matchingAuthProfile(profiles)
        val connected =
            when (credentialType) {
                SlotCredentialType.OAUTH -> authProfile != null
                SlotCredentialType.API_KEY -> apiKey?.key?.isNotBlank() == true
                SlotCredentialType.URL_KEY -> apiKey?.baseUrl?.isNotBlank() == true || apiKey?.key?.isNotBlank() == true
            }
        val connectionSummary =
            when (credentialType) {
                SlotCredentialType.OAUTH ->
                    if (authProfile == null) {
                        "Not connected"
                    } else {
                        "Connected"
                    }
                SlotCredentialType.API_KEY ->
                    if (apiKey?.key?.isNotBlank() == true) {
                        "API key saved"
                    } else {
                        "No API key"
                    }
                SlotCredentialType.URL_KEY ->
                    when {
                        apiKey?.baseUrl?.isNotBlank() == true -> apiKey.baseUrl
                        apiKey?.key?.isNotBlank() == true -> "Configured"
                        else -> "Not configured"
                    }
            }
        return AgentSlotItem(
            slotId = slotId,
            displayName = displayName,
            providerName = providerLabel(),
            connectionSummary = connectionSummary,
            modelName =
                agent?.modelName?.takeIf {
                    it.isNotBlank() && routesModelRequests
                },
            isEnabled = agent?.isEnabled ?: false,
            requiresConnection = !connected,
            routesModelRequests = routesModelRequests,
        )
    }

    private fun ProviderSlot.providerLabel(): String = displayName

    private fun ProviderSlot.matchingApiKey(apiKeys: List<ApiKey>): ApiKey? =
        when (credentialType) {
            SlotCredentialType.OAUTH -> {
                val managedProvider = canonicalManagedProvider(providerRegistryId) ?: return null
                apiKeys.firstOrNull { canonicalManagedProvider(it.provider) == managedProvider }
            }
            else ->
                apiKeys.firstOrNull { key ->
                    val keyProvider =
                        ProviderRegistry.findById(key.provider)?.id ?: key.provider.lowercase()
                    keyProvider == providerRegistryId.lowercase()
                }
        }

    private fun ProviderSlot.matchingAuthProfile(profiles: List<FfiAuthProfile>): FfiAuthProfile? =
        authProfileProvider?.let { provider ->
            profiles.firstOrNull { it.provider == provider }
        }

    private companion object {
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
