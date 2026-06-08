/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.onboarding

import com.zeroclaw.android.ZeroAIApplication
import com.zeroclaw.android.data.ProviderRegistry
import com.zeroclaw.android.data.ProviderSlotRegistry
import com.zeroclaw.android.data.SlotCredentialType
import com.zeroclaw.android.data.identity.AieosDerivationEngine
import com.zeroclaw.android.data.oauth.AuthProfileStore
import com.zeroclaw.android.data.repository.OnboardingRepository
import com.zeroclaw.android.data.repository.SettingsRepository
import com.zeroclaw.android.model.AppSettings
import com.zeroclaw.android.ui.screen.onboarding.state.MemoryStepState
import com.zeroclaw.android.ui.screen.onboarding.state.SecurityStepState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Debounce window between an in-progress onboarding edit and the
 * DataStore write that persists it. Long enough to coalesce keystroke
 * bursts; short enough that backgrounding the app immediately after
 * the last edit still captures the change.
 */
private const val DRAFT_PERSIST_DEBOUNCE_MS = 750L

/**
 * Owns onboarding initialisation: serial prefill from settings + restore
 * of the saved step/draft, plus the running draft-persistence subscriber
 * that writes touched-section deltas to DataStore. Extracted from
 * [OnboardingCoordinator] so the coordinator no longer carries ~270 LOC
 * of init/persist plumbing.
 *
 * The coordinator constructs exactly one instance, calls [startInit]
 * once during its own init block, and routes step navigation through
 * [persistStep] and [isReady]. All other state flows the handler reads
 * come from the injected [OnboardingMutableStates] bundle.
 */
@Suppress("LongParameterList")
internal class OnboardingInitHandler(
    private val states: OnboardingMutableStates,
    private val provider: OnboardingProviderHandler,
    private val onboardingRepository: OnboardingRepository,
    private val settingsRepository: SettingsRepository,
    private val application: ZeroAIApplication,
    private val scope: CoroutineScope,
) {
    private val draftPersistenceGate = CompletableDeferred<OnboardingStates>()

    /**
     * Sections the user has touched at any point in this wizard session.
     * Monotonic — once a section is added it stays even if the user
     * reverts to baseline, so a previously-persisted choice is never
     * silently wiped. Seeded at init time with the sections the
     * restored draft populated.
     */
    private val sessionTouched = mutableSetOf<OnboardingSection>()

    /**
     * `true` once prefill + restore have completed and the
     * draft-persistence subscriber is live. Coordinator's step
     * navigation gates on this so a tap landing during cold launch is
     * not silently overwritten by [restoreSavedStepSuspending].
     */
    val isReady: Boolean get() = draftPersistenceGate.isCompleted

    /**
     * Kicks off the serial prefill+restore coroutine and the
     * draft-persistence subscriber. Safe to call exactly once during
     * [OnboardingCoordinator] init.
     */
    fun startInit() {
        scope.launch {
            val settings = settingsRepository.settings.first()
            prefillFromExistingIdentitySuspending(settings.identityJson)
            prefillLockFromSettingsSuspending(settings)
            prefillFromExistingSettingsSuspending(settings)
            restoreSavedStepSuspending()
            restoreSavedDraftSuspending()
            draftPersistenceGate.complete(snapshotStates())
        }
        startDraftPersistence()
    }

    /**
     * Persists the current step index to DataStore. Awaits the gate so a
     * user tap during cold-launch cannot race [restoreSavedStepSuspending]
     * and get yanked backwards.
     */
    fun persistStep() {
        scope.launch {
            draftPersistenceGate.await()
            onboardingRepository.saveStep(states.currentStep.value)
        }
    }

    private fun snapshotStates(): OnboardingStates =
        OnboardingStates(
            provider = states.provider.value,
            identity = states.identity.value,
        )

    private suspend fun restoreSavedStepSuspending() {
        val saved = onboardingRepository.savedStep.first()
        if (saved in 0 until OnboardingCoordinator.TOTAL_STEPS &&
            saved != states.currentStep.value
        ) {
            states.currentStep.value = saved
        }
    }

    private suspend fun restoreSavedDraftSuspending() {
        val draft = onboardingRepository.savedDraft.first() ?: return
        if (draft.provider != null) sessionTouched += OnboardingSection.PROVIDER
        if (draft.identity != null) sessionTouched += OnboardingSection.IDENTITY
        val updated = OnboardingDraftMapper.apply(snapshotStates(), draft)
        states.provider.value = updated.provider
        states.identity.value = updated.identity
    }

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    @Suppress("MagicNumber")
    private fun startDraftPersistence() {
        scope.launch {
            val baseline = draftPersistenceGate.await()
            combine(states.provider, states.identity) { provider, identity ->
                OnboardingStates(provider = provider, identity = identity)
            }.debounce(DRAFT_PERSIST_DEBOUNCE_MS)
                .onEach { snapshot ->
                    sessionTouched += diffSections(baseline, snapshot)
                    if (sessionTouched.isNotEmpty()) {
                        onboardingRepository.saveDraft(
                            OnboardingDraftMapper.toDraft(snapshot, sessionTouched.toSet()),
                        )
                    }
                }.collect()
        }
    }

    private fun diffSections(
        baseline: OnboardingStates,
        current: OnboardingStates,
    ): Set<OnboardingSection> {
        val touched = mutableSetOf<OnboardingSection>()
        if (baseline.provider != current.provider) touched += OnboardingSection.PROVIDER
        if (baseline.identity != current.identity) touched += OnboardingSection.IDENTITY
        return touched
    }

    private suspend fun prefillLockFromSettingsSuspending(settings: AppSettings) {
        states.useDeviceCredential.value = settings.useDeviceCredential
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun prefillFromExistingIdentitySuspending(identityJson: String) {
        try {
            if (identityJson.isNotBlank()) {
                val root = Json.parseToJsonElement(identityJson).jsonObject
                val identity = root["identity"]?.jsonObject
                val names = identity?.get("names")?.jsonObject
                val firstName = names?.get("first")?.jsonPrimitive?.content
                if (!firstName.isNullOrBlank()) {
                    states.identity.value = states.identity.value.copy(agentName = firstName)
                }
                val userName = identity?.get("user_name")?.jsonPrimitive?.content
                if (!userName.isNullOrBlank()) {
                    states.identity.value = states.identity.value.copy(userName = userName)
                }
                val tz = identity?.get("timezone")?.jsonPrimitive?.content
                if (!tz.isNullOrBlank()) {
                    states.identity.value = states.identity.value.copy(timezone = tz)
                }
                val style = identity?.get("communication_style")?.jsonPrimitive?.content
                if (!style.isNullOrBlank()) {
                    states.identity.value = states.identity.value.copy(communicationStyle = style)
                }
                try {
                    val personalityPrefill = AieosDerivationEngine.prefillFromJson(identityJson)
                    states.personality.value = personalityPrefill
                } catch (_: Exception) {
                    // Personality prefill is best-effort.
                }
            }
        } catch (_: Exception) {
            // Identity prefill is best-effort.
        }
    }

    private suspend fun prefillFromExistingSettingsSuspending(settings: AppSettings) {
        if (!provider.userChangedProvider && settings.defaultProvider.isNotBlank()) {
            val info = ProviderRegistry.findById(settings.defaultProvider)
            val canonicalId = info?.id ?: settings.defaultProvider.lowercase()
            val profile = AuthProfileStore.findStandaloneProfile(application, canonicalId)
            val resolvedSlot =
                ProviderSlotRegistry
                    .resolveSlotId(
                        providerRegistryId = canonicalId,
                        isOAuth = profile != null,
                    )?.let(ProviderSlotRegistry::findById)
            val onboardingSlot =
                resolvedSlot?.takeIf { it.routesModelRequests }
                    ?: ProviderSlotRegistry
                        .resolveSlotId(
                            providerRegistryId = canonicalId,
                            isOAuth = false,
                        )?.let(ProviderSlotRegistry::findById)
            val onboardingUsesOauth =
                onboardingSlot?.credentialType == SlotCredentialType.OAUTH
            states.provider.value =
                states.provider.value.copy(
                    slotId = onboardingSlot?.slotId.orEmpty(),
                    providerId = canonicalId,
                    baseUrl = info?.defaultBaseUrl.orEmpty(),
                    model = settings.defaultModel,
                    oauthEmail = if (onboardingUsesOauth) profile?.accountId.orEmpty() else "",
                    oauthExpiresAt = if (onboardingUsesOauth) profile?.expiresAtMs ?: 0L else 0L,
                )
        }

        states.security.value = SecurityStepState(autonomyLevel = settings.autonomyLevel)
        states.memory.value =
            MemoryStepState(
                backend = settings.memoryBackend,
                autoSave = settings.memoryAutoSave,
                embeddingProvider = settings.memoryEmbeddingProvider,
                retentionDays = settings.memoryArchiveAfterDays,
            )
    }
}
