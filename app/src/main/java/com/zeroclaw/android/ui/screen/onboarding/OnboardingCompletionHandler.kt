/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.onboarding

import com.zeroclaw.android.ZeroAIApplication
import com.zeroclaw.android.data.ProviderRegistry
import com.zeroclaw.android.data.ProviderSlotRegistry
import com.zeroclaw.android.data.identity.AieosDerivationEngine
import com.zeroclaw.android.data.remote.ModelFetcher
import com.zeroclaw.android.data.repository.AgentRepository
import com.zeroclaw.android.data.repository.ApiKeyRepository
import com.zeroclaw.android.data.repository.ChannelConfigRepository
import com.zeroclaw.android.data.repository.SettingsRepository
import com.zeroclaw.android.model.Agent
import com.zeroclaw.android.model.ApiKey
import com.zeroclaw.android.model.ChannelType
import com.zeroclaw.android.model.ConnectedChannel
import com.zeroclaw.android.model.ModelListFormat
import com.zeroclaw.android.ui.screen.onboarding.state.ActivationStepState
import com.zeroclaw.android.ui.screen.onboarding.state.ChannelSelectionState
import com.zeroclaw.android.ui.screen.onboarding.state.ChannelSubFlowState
import com.zeroclaw.android.ui.screen.onboarding.state.IdentityStepState
import com.zeroclaw.android.ui.screen.onboarding.state.MemoryStepState
import com.zeroclaw.android.ui.screen.onboarding.state.PersonalityStepState
import com.zeroclaw.android.ui.screen.onboarding.state.ProviderStepState
import com.zeroclaw.android.ui.screen.onboarding.state.SecurityStepState
import java.util.TimeZone
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/**
 * Owns the activation-step completion flow that persists every
 * onboarding selection at Finish time. Extracted from
 * [OnboardingCoordinator] so the coordinator no longer carries ~285
 * LOC of completion plumbing.
 *
 * Reads from the step state flows, mutates only [activationState] (to
 * surface a credential-validation error before commit), and writes to
 * the api-key / agent / channel / settings repositories via injected
 * references. Mid-wizard back-out leaves all repositories untouched
 * because nothing is persisted until [complete] runs.
 */
@Suppress("LongParameterList")
internal class OnboardingCompletionHandler(
    private val providerState: StateFlow<ProviderStepState>,
    private val channelSelectionState: StateFlow<ChannelSelectionState>,
    private val channelSubFlowStates: StateFlow<Map<ChannelType, ChannelSubFlowState>>,
    private val securityState: StateFlow<SecurityStepState>,
    private val memoryState: StateFlow<MemoryStepState>,
    private val identityState: StateFlow<IdentityStepState>,
    private val personalityState: StateFlow<PersonalityStepState>,
    private val useDeviceCredential: StateFlow<Boolean>,
    private val activationState: MutableStateFlow<ActivationStepState>,
    private val apiKeyRepository: ApiKeyRepository,
    private val agentRepository: AgentRepository,
    private val channelConfigRepository: ChannelConfigRepository,
    private val settingsRepository: SettingsRepository,
    private val application: ZeroAIApplication,
) {
    /**
     * Persists all onboarding configuration and marks onboarding complete.
     *
     * Steps:
     * 1. Provider auth probing (HTTP 401/403 detection)
     * 2. API key saving
     * 3. Agent saving (create or update)
     * 4. Channel saving for all selected channels with filled required fields
     * 5. Identity JSON writing with expanded fields
     * 6. Workspace scaffolding with user name, timezone, communication style
     * 7. Default provider/model persistence
     * 8. Autonomy level persistence
     * 9. Memory configuration persistence
     * 10. Device-credential lock persistence
     * 11. [onDone] callback (caller marks onboarding complete + navigates)
     */
    @Suppress("CognitiveComplexMethod", "CyclomaticComplexMethod", "LongMethod")
    suspend fun complete(onDone: () -> Unit) {
        val provider = providerState.value.providerId
        val key = providerState.value.apiKey
        val model = providerState.value.model
        val url = providerState.value.baseUrl
        val identity = identityState.value
        val name = identity.agentName

        val isOAuthSession = providerState.value.oauthEmail.isNotEmpty()
        if (!isOAuthSession && authErrorForProvider(provider, key, url)) {
            activationState.value =
                activationState.value.copy(
                    completeError =
                        "Invalid API key — verify your credentials before " +
                            "starting the daemon",
                )
            return
        }

        val hasCredentials = key.isNotBlank() || url.isNotBlank() || isOAuthSession
        if (provider.isNotBlank() && hasCredentials) {
            saveProviderApiKey(provider, key, url)
        }

        if (name.isNotBlank() && provider.isNotBlank()) {
            persistAgentRecord(provider, name, model, isOAuthSession)
        }

        saveAllConfiguredChannels()
        ensureIdentity(identity)
        scaffoldWorkspaceIfNeeded(identity)

        if (provider.isNotBlank()) settingsRepository.setDefaultProvider(provider)
        if (model.isNotBlank()) settingsRepository.setDefaultModel(model)

        saveAutonomyLevel()
        saveMemoryConfig()

        settingsRepository.setUseDeviceCredential(useDeviceCredential.value)

        onDone()
    }

    private suspend fun persistAgentRecord(
        provider: String,
        name: String,
        model: String,
        isOAuthSession: Boolean,
    ) {
        val canonicalId = ProviderRegistry.findById(provider)?.id ?: provider.lowercase()
        val slotId =
            providerState.value.slotId.ifBlank {
                ProviderSlotRegistry.resolveSlotId(canonicalId, isOAuthSession).orEmpty()
            }
        val existing =
            agentRepository.agents.first().firstOrNull { agent ->
                when {
                    slotId.isNotBlank() -> agent.slotId == slotId || agent.id == slotId
                    else -> {
                        val agentCanonical =
                            ProviderRegistry.findById(agent.provider)?.id
                                ?: agent.provider.lowercase()
                        agentCanonical == canonicalId
                    }
                }
            }
        if (existing != null) {
            agentRepository.save(
                existing.copy(
                    slotId = slotId.ifBlank { existing.slotId },
                    name = name,
                    provider = provider,
                    modelName = model.ifBlank { "default" },
                    isEnabled = true,
                ),
            )
        } else {
            agentRepository.save(
                Agent(
                    id = slotId.ifBlank { UUID.randomUUID().toString() },
                    slotId = slotId,
                    name = name,
                    provider = provider,
                    modelName = model.ifBlank { "default" },
                ),
            )
        }
    }

    private suspend fun saveProviderApiKey(
        provider: String,
        key: String,
        url: String,
    ) {
        val existingKey = apiKeyRepository.getByProvider(provider)
        val ps = providerState.value
        apiKeyRepository.save(
            ApiKey(
                id = existingKey?.id ?: UUID.randomUUID().toString(),
                provider = provider,
                key = key,
                baseUrl = url,
                refreshToken = "",
                expiresAt = if (ps.oauthEmail.isNotBlank()) 0L else ps.oauthExpiresAt,
            ),
        )
    }

    private suspend fun saveAllConfiguredChannels() {
        val subFlows = channelSubFlowStates.value
        channelSelectionState.value.selectedTypes
            .filter { type -> isChannelReadyToSave(type, subFlows[type]) }
            .forEach { type ->
                saveChannel(type, subFlows.getValue(type))
            }
    }

    private fun isChannelReadyToSave(
        type: ChannelType,
        subFlow: ChannelSubFlowState?,
    ): Boolean {
        if (subFlow == null) return false
        val fields = subFlow.fieldValues
        return type.fields
            .filter { it.isRequired }
            .all { fields[it.key]?.isNotBlank() == true }
    }

    private suspend fun saveChannel(
        type: ChannelType,
        subFlow: ChannelSubFlowState,
    ) {
        val fields = subFlow.fieldValues
        val secretKeys =
            type.fields
                .filter { it.isSecret }
                .map { it.key }
                .toSet()
        val (secretEntries, nonSecretEntries) =
            fields.entries.partition { it.key in secretKeys }
        val secrets = secretEntries.associate { it.toPair() }
        val nonSecrets = nonSecretEntries.associate { it.toPair() }
        val channel =
            ConnectedChannel(
                id = UUID.randomUUID().toString(),
                type = type,
                configValues = nonSecrets,
            )
        channelConfigRepository.save(channel, secrets)
    }

    private suspend fun ensureIdentity(identity: IdentityStepState) {
        val personality = personalityState.value
        val resolvedName = identity.agentName.ifBlank { personality.agentName }
        val json =
            if (personality.skipped || !personality.isMinimallyComplete) {
                AieosDerivationEngine.deriveSkipFallback(
                    agentName = resolvedName.ifBlank { "Sick Zero" },
                )
            } else {
                AieosDerivationEngine.derive(
                    personality.copy(
                        agentName = personality.agentName.ifBlank { identity.agentName },
                    ),
                )
            }
        settingsRepository.setIdentityJson(json)
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun scaffoldWorkspaceIfNeeded(identity: IdentityStepState) {
        try {
            application.daemonBridge.ensureWorkspace(
                agentName = identity.agentName,
                userName = identity.userName.ifBlank { "User" },
                timezone = identity.timezone.ifBlank { TimeZone.getDefault().id },
                communicationStyle = identity.communicationStyle,
            )
        } catch (_: Exception) {
            // Workspace scaffolding is best-effort; the daemon can start without it.
        }
    }

    private suspend fun saveAutonomyLevel() {
        settingsRepository.setAutonomyLevel(securityState.value.autonomyLevel)
    }

    private suspend fun saveMemoryConfig() {
        val memory = memoryState.value
        settingsRepository.setMemoryBackend(memory.backend)
        settingsRepository.setMemoryAutoSave(memory.autoSave)
        if (memory.embeddingProvider.isNotBlank()) {
            settingsRepository.setMemoryEmbeddingProvider(memory.embeddingProvider)
        }
        settingsRepository.setMemoryArchiveAfterDays(memory.retentionDays)
    }

    /**
     * Returns true when the provider credentials are definitively rejected
     * (HTTP 401/403). Performs a lightweight model-list probe; non-auth
     * failures (network, 5xx, etc.) return false so offline use and transient
     * provider issues do not block onboarding.
     */
    private suspend fun authErrorForProvider(
        providerId: String,
        key: String,
        url: String,
    ): Boolean {
        if (providerId.isBlank() || (key.isBlank() && url.isBlank())) return false
        val providerInfo = ProviderRegistry.findById(providerId) ?: return false
        if (providerInfo.modelListFormat == ModelListFormat.NONE) return false
        val probeResult = ModelFetcher.fetchModels(providerInfo, key, url)
        val failure = probeResult.exceptionOrNull() ?: return false
        val msg = failure.message ?: ""
        return "HTTP 401" in msg || "HTTP 403" in msg
    }
}
