/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

@file:Suppress("TooManyFunctions")

package com.zeroclaw.android.ui.screen.onboarding

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zeroclaw.android.ZeroAIApplication
import com.zeroclaw.android.model.ChannelType
import com.zeroclaw.android.model.OnDeviceAiUiState
import com.zeroclaw.android.service.ondevice.OnDeviceAiHandler
import com.zeroclaw.android.ui.component.setup.ConfigSummary
import com.zeroclaw.android.ui.screen.onboarding.state.ActivationStepState
import com.zeroclaw.android.ui.screen.onboarding.state.ChannelSelectionState
import com.zeroclaw.android.ui.screen.onboarding.state.ChannelSubFlowState
import com.zeroclaw.android.ui.screen.onboarding.state.IdentityStepState
import com.zeroclaw.android.ui.screen.onboarding.state.MemoryStepState
import com.zeroclaw.android.ui.screen.onboarding.state.PersonalityArchetype
import com.zeroclaw.android.ui.screen.onboarding.state.PersonalityStepState
import com.zeroclaw.android.ui.screen.onboarding.state.ProviderStepState
import com.zeroclaw.android.ui.screen.onboarding.state.SecurityStepState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Sharing timeout for derived [StateFlow] instances.
 *
 * Keeps the upstream subscription alive for 5 seconds after the last
 * subscriber disconnects, avoiding unnecessary recomputation when the
 * UI is briefly detached (e.g. during configuration changes).
 */
private const val SHARING_TIMEOUT_MS = 5000L

/**
 * Coordinator ViewModel for the 8-step onboarding wizard.
 *
 * Each step has its own typed state class exposed as a [StateFlow]. The
 * coordinator manages step navigation, validation, model fetching, and
 * the final completion flow that persists all configuration. OAuth login
 * machinery is owned by [OnboardingOAuthHandler]; draft persistence by
 * [OnboardingDraftMapper] plus the gate/baseline plumbing below.
 *
 * @param application Application context for accessing repositories.
 */
class OnboardingCoordinator(
    application: Application,
) : AndroidViewModel(application) {
    private val app = application as ZeroAIApplication
    private val onboardingRepository = app.onboardingRepository
    private val apiKeyRepository = app.apiKeyRepository
    private val settingsRepository = app.settingsRepository
    private val agentRepository = app.agentRepository
    private val channelConfigRepository = app.channelConfigRepository

    /**
     * Bundle of every mutable wizard-step state flow, owned by this
     * coordinator and read/mutated by the handler classes. See
     * [OnboardingMutableStates] for the per-step inventory.
     */
    private val states = OnboardingMutableStates()

    /** Zero-based index of the current onboarding step. */
    val currentStep: StateFlow<Int> = states.currentStep.asStateFlow()

    /** Total number of steps in the wizard. */
    val totalSteps: Int = TOTAL_STEPS

    /** Step index and total constants for onboarding navigation. */
    companion object {
        /** Step index: runtime permissions. */
        const val STEP_PERMISSIONS = 0

        /** Step index: welcome screen. */
        const val STEP_WELCOME = 1

        /** Step index: provider and API key configuration. */
        const val STEP_PROVIDER = 2

        /** Step index: on-device AI (AICore / Gemini Nano) setup. */
        const val STEP_ON_DEVICE_AI = 3

        /** Step index: channel selection and sub-flow configuration. */
        const val STEP_CHANNELS = 4

        /** Step index: security/autonomy level. */
        const val STEP_SECURITY = 5

        /** Step index: memory backend configuration. */
        const val STEP_MEMORY = 6

        /** Step index: agent identity configuration. */
        const val STEP_IDENTITY = 7

        /** Step index: activation and completion. */
        const val STEP_ACTIVATION = 8

        /** Total number of onboarding steps. */
        const val TOTAL_STEPS = 9

        /** Total number of personality sub-screens. */
        const val PERSONALITY_SUB_STEPS = 5

        /** Personality sub-step: name and role. */
        const val PERSONALITY_NAME_ROLE = 0

        /** Personality sub-step: archetype selection. */
        const val PERSONALITY_ARCHETYPE = 1

        /** Personality sub-step: communication style. */
        const val PERSONALITY_COMMUNICATION = 2

        /** Personality sub-step: catchphrases and flavor. */
        const val PERSONALITY_CATCHPHRASES = 3

        /** Personality sub-step: interests. */
        const val PERSONALITY_INTERESTS = 4
    }

    /** PBKDF2 hash of the PIN set during onboarding. */
    val pinHash: StateFlow<String> = states.pinHash.asStateFlow()

    /** Whether the session lock is enabled. */
    val lockEnabled: StateFlow<Boolean> = states.lockEnabled.asStateFlow()

    /** Observable state for the provider configuration step. */
    val providerState: StateFlow<ProviderStepState> = states.provider.asStateFlow()

    // Provider handler owns the provider-step setters + debounced live
    // model fetch + credential validation. Holds the userChangedProvider
    // flag the settings-prefill consults to avoid clobbering user choice.
    private val provider =
        OnboardingProviderHandler(
            providerState = states.provider,
            scope = viewModelScope,
            application = app,
        )

    // OAuth handler owns its 3 sheet StateFlows internally; coordinator
    // re-exposes them by delegation. No other coordinator code touches
    // anthropic-sheet state, so encapsulation is unambiguous.
    private val oauth =
        OnboardingOAuthHandler(
            providerState = states.provider,
            apiKeyRepository = apiKeyRepository,
            agentRepository = agentRepository,
            settingsRepository = settingsRepository,
            scope = viewModelScope,
            application = app,
        )

    /** Whether the Anthropic code paste-back sheet is visible. */
    val anthropicSheetVisible: StateFlow<Boolean> by oauth::anthropicSheetVisible

    /** Whether the Anthropic code exchange is in progress. */
    val anthropicSheetLoading: StateFlow<Boolean> by oauth::anthropicSheetLoading

    /** Error message to display in the Anthropic paste-back sheet. */
    val anthropicSheetError: StateFlow<String?> by oauth::anthropicSheetError

    /** Observable state for channel type selection. */
    val channelSelectionState: StateFlow<ChannelSelectionState> =
        states.channelSelection.asStateFlow()

    /** Observable map of per-channel sub-flow states. */
    val channelSubFlowStates: StateFlow<Map<ChannelType, ChannelSubFlowState>> =
        states.channelSubFlows.asStateFlow()

    // Channel state stays owned by the coordinator (declared above) because
    // configSummary and saveAllConfiguredChannels both read it; the handler
    // receives the MutableStateFlow refs and only mutates through them.
    private val channels =
        OnboardingChannelHandler(
            selectionState = states.channelSelection,
            subFlowStates = states.channelSubFlows,
            scope = viewModelScope,
        )

    /** Observable state for the on-device AI setup step. */
    val onDeviceAiState: StateFlow<OnDeviceAiUiState> = states.onDeviceAi.asStateFlow()

    // Handler owns the AICore/ML Kit state machine, intents, and the
    // active download job. Coordinator only delegates user actions.
    private val onDeviceAi =
        OnDeviceAiHandler(
            state = states.onDeviceAi,
            scope = viewModelScope,
            context = app,
        )

    /** Observable state for security/autonomy configuration. */
    val securityState: StateFlow<SecurityStepState> = states.security.asStateFlow()

    /** Observable state for memory backend configuration. */
    val memoryState: StateFlow<MemoryStepState> = states.memory.asStateFlow()

    /** Observable state for identity configuration. */
    val identityState: StateFlow<IdentityStepState> = states.identity.asStateFlow()

    /** State for the personality builder sub-flow within the identity step. */
    val personalityState: StateFlow<PersonalityStepState> =
        states.personality.asStateFlow()

    // Identity + personality state stay owned by the coordinator because
    // configSummary, the draft mapper, and the completion handler all
    // read them; the personality handler only mutates through the refs.
    private val personality =
        OnboardingPersonalityHandler(
            identityState = states.identity,
            personalityState = states.personality,
        )

    /** Observable state for the activation/completion step. */
    val activationState: StateFlow<ActivationStepState> = states.activation.asStateFlow()

    // Completion handler reads every step state and the four primary
    // repositories; it owns the ~285-LOC commit-everything flow that
    // runs at Finish. Activation state is the only mutation it makes.
    private val completion =
        OnboardingCompletionHandler(
            providerState = states.provider,
            channelSelectionState = states.channelSelection,
            channelSubFlowStates = states.channelSubFlows,
            securityState = states.security,
            memoryState = states.memory,
            identityState = states.identity,
            personalityState = states.personality,
            pinHash = states.pinHash,
            lockEnabled = states.lockEnabled,
            activationState = states.activation,
            apiKeyRepository = apiKeyRepository,
            agentRepository = agentRepository,
            channelConfigRepository = channelConfigRepository,
            settingsRepository = settingsRepository,
            application = app,
        )

    /**
     * Derived configuration summary combining all step states.
     *
     * The summary is recomputed whenever any contributing state changes and
     * is displayed on the activation step as a read-only overview.
     */
    @Suppress("SpreadOperator", "MagicNumber")
    val configSummary: StateFlow<ConfigSummary> =
        combine(
            states.provider,
            states.channelSelection,
            states.security,
            states.memory,
            states.identity,
        ) { states ->
            val provider = states[0] as ProviderStepState
            val channels = states[1] as ChannelSelectionState
            val security = states[2] as SecurityStepState
            val memory = states[3] as MemoryStepState
            val identity = states[4] as IdentityStepState
            ConfigSummary(
                provider = provider.providerId,
                model = provider.model,
                autonomy = security.autonomyLevel,
                memoryBackend = memory.backend,
                autoSave = memory.autoSave,
                channels = channels.selectedTypes.map { it.displayName },
                identityFormat = identity.identityFormat,
                agentName = identity.agentName,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(SHARING_TIMEOUT_MS),
            ConfigSummary(),
        )

    // Init handler owns the prefill+restore sequence, draft-persistence
    // subscriber, and step-persistence gate. Coordinator only delegates.
    private val bootstrap =
        OnboardingInitHandler(
            states = states,
            provider = provider,
            onboardingRepository = onboardingRepository,
            settingsRepository = settingsRepository,
            application = app,
            scope = viewModelScope,
        )

    init {
        bootstrap.startInit()
    }

    /**
     * Advances to the next step if not at the last step. Ignored until
     * [OnboardingInitHandler.isReady] so a tap landing during cold launch
     * is not silently overwritten by the step-restore inside `startInit`.
     */
    fun nextStep() {
        if (!bootstrap.isReady) return
        if (states.currentStep.value < TOTAL_STEPS - 1) {
            states.currentStep.value++
            bootstrap.persistStep()
        }
    }

    /**
     * Returns to the previous step if not at the first step. Ignored until
     * [OnboardingInitHandler.isReady] (see [nextStep] for the reason).
     */
    fun previousStep() {
        if (!bootstrap.isReady) return
        if (states.currentStep.value > 0) {
            states.currentStep.value--
            bootstrap.persistStep()
        }
    }

    /**
     * Stores the PBKDF2 hash of the PIN configured during onboarding.
     *
     * Also enables the session lock when a non-empty hash is provided.
     *
     * @param hash Base64-encoded salt+hash string.
     */
    fun setPinHash(hash: String) {
        states.pinHash.value = hash
        states.lockEnabled.value = hash.isNotEmpty()
    }

    /**
     * Toggles the session lock enabled state.
     *
     * @param enabled Whether the lock is active.
     */
    fun setLockEnabled(enabled: Boolean) {
        states.lockEnabled.value = enabled
    }

    /** @see OnboardingProviderHandler.setProvider */
    fun setProvider(id: String) = provider.setProvider(id)

    /** @see OnboardingProviderHandler.setProviderSlot */
    fun setProviderSlot(slotId: String) = provider.setProviderSlot(slotId)

    /** @see OnboardingProviderHandler.setProviderVariant */
    fun setProviderVariant(variantId: String) = provider.setProviderVariant(variantId)

    /** @see OnboardingProviderHandler.setApiKey */
    fun setApiKey(key: String) = provider.setApiKey(key)

    /** @see OnboardingProviderHandler.setBaseUrl */
    fun setBaseUrl(url: String) = provider.setBaseUrl(url)

    /** @see OnboardingProviderHandler.setModel */
    fun setModel(model: String) = provider.setModel(model)

    /**
     * Launches the OAuth 2.0 PKCE login flow for the currently selected provider.
     * Delegates to [OnboardingOAuthHandler.startOAuthLogin].
     *
     * @param context Activity or application context used to launch the
     *   Chrome Custom Tab.
     */
    fun startOAuthLogin(context: Context) = oauth.startOAuthLogin(context)

    /**
     * Submits a pasted Anthropic authorization code for token exchange.
     * Delegates to [OnboardingOAuthHandler.submitAnthropicCode].
     *
     * @param code Cleaned authorization code from the paste-back sheet.
     */
    fun submitAnthropicCode(code: String) = oauth.submitAnthropicCode(code)

    /** Dismisses the Anthropic paste-back sheet and clears related state. */
    fun dismissAnthropicSheet() = oauth.dismissAnthropicSheet()

    /** Clears all OAuth-related fields from the provider state. */
    fun disconnectOAuth() = oauth.disconnectOAuth()

    /** @see OnboardingProviderHandler.validateProvider */
    fun validateProvider() = provider.validateProvider()

    /** @see OnboardingChannelHandler.toggleChannelSelection */
    fun toggleChannelSelection(type: ChannelType) = channels.toggleChannelSelection(type)

    /** @see OnboardingChannelHandler.startChannelSubFlow */
    fun startChannelSubFlow(type: ChannelType) = channels.startChannelSubFlow(type)

    /** @see OnboardingChannelHandler.exitChannelSubFlow */
    fun exitChannelSubFlow() = channels.exitChannelSubFlow()

    /** @see OnboardingChannelHandler.setChannelField */
    fun setChannelField(
        type: ChannelType,
        key: String,
        value: String,
    ) = channels.setChannelField(type, key, value)

    /** @see OnboardingChannelHandler.nextChannelSubStep */
    fun nextChannelSubStep(type: ChannelType) = channels.nextChannelSubStep(type)

    /** @see OnboardingChannelHandler.previousChannelSubStep */
    fun previousChannelSubStep(type: ChannelType) = channels.previousChannelSubStep(type)

    /** @see OnboardingChannelHandler.validateChannel */
    fun validateChannel(type: ChannelType) = channels.validateChannel(type)

    /** @see OnDeviceAiHandler.refresh */
    fun refreshOnDeviceAi() = onDeviceAi.refresh()

    /** @see OnDeviceAiHandler.setUsePreview */
    fun setOnDeviceUsePreview(usePreview: Boolean) = onDeviceAi.setUsePreview(usePreview)

    /** @see OnDeviceAiHandler.startDownload */
    fun startOnDeviceDownload() = onDeviceAi.startDownload()

    /** @see OnDeviceAiHandler.openAiCoreInstall */
    fun openAiCoreInstall() = onDeviceAi.openAiCoreInstall()

    /** @see OnDeviceAiHandler.openPreviewEnrollment */
    fun openOnDevicePreviewEnrollment() = onDeviceAi.openPreviewEnrollment()

    /**
     * Sets the autonomy level.
     *
     * @param level One of "supervised", "constrained", or "unconstrained".
     */
    fun setAutonomyLevel(level: String) {
        states.security.value = states.security.value.copy(autonomyLevel = level)
    }

    /**
     * Sets the memory backend.
     *
     * In-memory only until [OnboardingCompletionHandler.complete]
     * persists to settings; a mid-wizard back-out discards the choice,
     * matching the lossy semantics already used by channel selections
     * and personality.
     *
     * @param backend One of "sqlite", "none", "markdown", or "lucid".
     */
    fun setMemoryBackend(backend: String) {
        states.memory.value = states.memory.value.copy(backend = backend)
    }

    /**
     * Toggles the memory auto-save setting (in-memory until activation).
     *
     * @param enabled Whether auto-save is active.
     */
    fun setAutoSave(enabled: Boolean) {
        states.memory.value = states.memory.value.copy(autoSave = enabled)
    }

    /**
     * Sets the embedding provider (in-memory until activation).
     *
     * @param provider One of "none", "openai", or "custom:URL".
     */
    fun setEmbeddingProvider(provider: String) {
        states.memory.value = states.memory.value.copy(embeddingProvider = provider)
    }

    /**
     * Sets the memory retention period (in-memory until activation).
     *
     * @param days Number of days before memory entries are archived.
     */
    fun setRetentionDays(days: Int) {
        states.memory.value = states.memory.value.copy(retentionDays = days)
    }

    /** @see OnboardingPersonalityHandler.setAgentName */
    fun setAgentName(name: String) = personality.setAgentName(name)

    /** @see OnboardingPersonalityHandler.setUserName */
    fun setUserName(name: String) = personality.setUserName(name)

    /** @see OnboardingPersonalityHandler.setTimezone */
    fun setTimezone(tz: String) = personality.setTimezone(tz)

    /** @see OnboardingPersonalityHandler.setCommunicationStyle */
    fun setCommunicationStyle(style: String) = personality.setCommunicationStyle(style)

    /** @see OnboardingPersonalityHandler.setIdentityFormat */
    fun setIdentityFormat(format: String) = personality.setIdentityFormat(format)

    /** @see OnboardingPersonalityHandler.setPersonalityAgentName */
    fun setPersonalityAgentName(name: String) = personality.setPersonalityAgentName(name)

    /** @see OnboardingPersonalityHandler.setPersonalityRole */
    fun setPersonalityRole(role: String) = personality.setPersonalityRole(role)

    /** @see OnboardingPersonalityHandler.setPersonalityArchetype */
    fun setPersonalityArchetype(archetype: PersonalityArchetype) = personality.setPersonalityArchetype(archetype)

    /** @see OnboardingPersonalityHandler.setPersonalityFormality */
    fun setPersonalityFormality(formality: String) = personality.setPersonalityFormality(formality)

    /** @see OnboardingPersonalityHandler.setPersonalityVerbosity */
    fun setPersonalityVerbosity(verbosity: String) = personality.setPersonalityVerbosity(verbosity)

    /** @see OnboardingPersonalityHandler.setPersonalityCatchphrases */
    fun setPersonalityCatchphrases(phrases: List<String>) = personality.setPersonalityCatchphrases(phrases)

    /** @see OnboardingPersonalityHandler.setPersonalityForbiddenWords */
    fun setPersonalityForbiddenWords(words: List<String>) = personality.setPersonalityForbiddenWords(words)

    /** @see OnboardingPersonalityHandler.togglePersonalityInterest */
    fun togglePersonalityInterest(topic: String) = personality.togglePersonalityInterest(topic)

    /** @see OnboardingPersonalityHandler.advancePersonalitySubStep */
    fun advancePersonalitySubStep() = personality.advancePersonalitySubStep()

    /** @see OnboardingPersonalityHandler.retreatPersonalitySubStep */
    fun retreatPersonalitySubStep() = personality.retreatPersonalitySubStep()

    /** @see OnboardingPersonalityHandler.skipPersonality */
    fun skipPersonality() = personality.skipPersonality()

    /**
     * Clears the pending completion error after it has been shown to the user.
     */
    fun dismissCompleteError() {
        states.activation.value = states.activation.value.copy(completeError = null)
    }

    /**
     * Launches the onboarding completion flow.
     *
     * Guards against double-tap by checking [ActivationStepState.isCompleting].
     * Sets [ActivationStepState.isCompleting] to true for the duration so the
     * UI can disable the button and show a spinner.
     *
     * @param onDone Callback invoked after all data has been persisted.
     */
    fun complete(onDone: () -> Unit) {
        if (states.activation.value.isCompleting) return
        viewModelScope.launch {
            states.activation.value = states.activation.value.copy(isCompleting = true)
            try {
                completion.complete(onDone)
            } finally {
                states.activation.value = states.activation.value.copy(isCompleting = false)
            }
        }
    }
}
