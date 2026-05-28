/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.onboarding

import com.zeroclaw.android.data.repository.IdentitySection
import com.zeroclaw.android.data.repository.OnboardingDraft
import com.zeroclaw.android.data.repository.ProviderSection
import com.zeroclaw.android.ui.screen.onboarding.state.IdentityStepState
import com.zeroclaw.android.ui.screen.onboarding.state.ProviderStepState

/**
 * Snapshot of the only two step states that participate in draft
 * persistence: the provider step (so OAuth logins survive
 * backgrounding) and the identity step (agent name, timezone, etc.).
 * Other step states are intentionally not part of this snapshot — they
 * stay in-memory until [com.zeroclaw.android.ui.screen.onboarding.OnboardingCoordinator]
 * commits them at activation.
 */
internal data class OnboardingStates(
    val provider: ProviderStepState,
    val identity: IdentityStepState,
)

/**
 * Identifies a wizard step the user has touched. Only the steps that
 * participate in draft persistence (provider and identity) have entries
 * here.
 */
internal enum class OnboardingSection {
    /** Provider step (registry id, slot id, base URL, model). */
    PROVIDER,

    /** Identity step (agent name, user name, timezone, etc.). */
    IDENTITY,
}

/**
 * Pure conversion layer between [OnboardingStates] and [OnboardingDraft].
 * The draft uses nullable per-section sub-records so that "user has not
 * touched this step" is structurally distinct from "user explicitly chose
 * the default value".
 */
internal object OnboardingDraftMapper {
    /**
     * Builds a draft containing only the sections listed in [touched].
     * Untouched sections are persisted as `null` so the next restore
     * leaves the corresponding step state alone.
     */
    fun toDraft(
        states: OnboardingStates,
        touched: Set<OnboardingSection>,
    ): OnboardingDraft =
        OnboardingDraft(
            provider =
                if (OnboardingSection.PROVIDER in touched) {
                    ProviderSection(
                        providerId = states.provider.providerId,
                        slotId = states.provider.slotId,
                        baseUrl = states.provider.baseUrl,
                        model = states.provider.model,
                    )
                } else {
                    null
                },
            identity =
                if (OnboardingSection.IDENTITY in touched) {
                    IdentitySection(
                        agentName = states.identity.agentName,
                        userName = states.identity.userName,
                        timezone = states.identity.timezone,
                        communicationStyle = states.identity.communicationStyle,
                        identityFormat = states.identity.identityFormat,
                    )
                } else {
                    null
                },
        )

    /**
     * Layers [draft] over [states], replacing each step's state only when
     * the corresponding section is non-null.
     */
    fun apply(
        states: OnboardingStates,
        draft: OnboardingDraft,
    ): OnboardingStates =
        OnboardingStates(
            provider = draft.provider?.let { applyProvider(states.provider, it) } ?: states.provider,
            identity = draft.identity?.let { applyIdentity(states.identity, it) } ?: states.identity,
        )

    private fun applyProvider(
        current: ProviderStepState,
        section: ProviderSection,
    ): ProviderStepState =
        current.copy(
            providerId = section.providerId,
            slotId = section.slotId,
            baseUrl = section.baseUrl,
            model = section.model,
        )

    private fun applyIdentity(
        current: IdentityStepState,
        section: IdentitySection,
    ): IdentityStepState =
        current.copy(
            agentName = section.agentName,
            userName = section.userName,
            timezone = section.timezone.ifBlank { current.timezone },
            communicationStyle = section.communicationStyle,
            identityFormat = section.identityFormat,
        )
}
