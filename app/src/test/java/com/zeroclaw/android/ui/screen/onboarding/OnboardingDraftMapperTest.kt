/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.onboarding

import com.zeroclaw.android.data.repository.IdentitySection
import com.zeroclaw.android.data.repository.OnboardingDraft
import com.zeroclaw.android.data.repository.ProviderSection
import com.zeroclaw.android.ui.screen.onboarding.state.IdentityStepState
import com.zeroclaw.android.ui.screen.onboarding.state.ProviderStepState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Verifies the load-bearing contract of [OnboardingDraftMapper]:
 * untouched sections are persisted as `null`, an empty-section draft
 * is restored as identity, and a round-trip preserves the persisted
 * fields exactly.
 */
@DisplayName("OnboardingDraftMapper")
class OnboardingDraftMapperTest {
    private val baselineStates =
        OnboardingStates(
            provider = ProviderStepState(),
            identity = IdentityStepState(),
        )

    @Test
    @DisplayName("toDraft with empty touched set yields an all-null draft")
    fun `toDraft empty touched is all null`() {
        val draft = OnboardingDraftMapper.toDraft(baselineStates, emptySet())
        assertNull(draft.provider)
        assertNull(draft.identity)
    }

    @Test
    @DisplayName("apply of all-null draft is the identity on states")
    fun `apply of empty draft is identity`() {
        val empty = OnboardingDraft()
        val result = OnboardingDraftMapper.apply(baselineStates, empty)
        assertEquals(baselineStates, result)
    }

    @Test
    @DisplayName("toDraft then apply restores provider and identity fields exactly")
    fun `round trip preserves provider and identity`() {
        val original =
            OnboardingStates(
                provider =
                    ProviderStepState(
                        slotId = "anthropic-api",
                        providerId = "anthropic",
                        baseUrl = "https://api.anthropic.com",
                        model = "claude-sonnet-4",
                    ),
                identity =
                    IdentityStepState(
                        agentName = "Zero",
                        userName = "Natali",
                        timezone = "America/Los_Angeles",
                        communicationStyle = "concise",
                        identityFormat = "aieos",
                    ),
            )
        val draft =
            OnboardingDraftMapper.toDraft(
                original,
                setOf(OnboardingSection.PROVIDER, OnboardingSection.IDENTITY),
            )
        assertNotNull(draft.provider)
        assertNotNull(draft.identity)

        val restored = OnboardingDraftMapper.apply(baselineStates, draft)
        assertEquals(original.provider, restored.provider)
        assertEquals(original.identity, restored.identity)
    }

    @Test
    @DisplayName("apply leaves untouched section state alone")
    fun `apply preserves untouched section`() {
        val current =
            baselineStates.copy(
                identity = IdentityStepState(agentName = "Already Set"),
            )
        val providerOnly =
            OnboardingDraft(
                provider = ProviderSection(providerId = "openai", model = "gpt-4o"),
            )
        val result = OnboardingDraftMapper.apply(current, providerOnly)
        assertEquals("openai", result.provider.providerId)
        assertEquals("gpt-4o", result.provider.model)
        assertEquals("Already Set", result.identity.agentName)
    }

    @Test
    @DisplayName("apply identity falls back to current timezone when section timezone is blank")
    fun `apply identity falls back to current timezone when blank`() {
        val current =
            baselineStates.copy(
                identity = IdentityStepState(timezone = "America/New_York"),
            )
        val identityWithBlankTz =
            OnboardingDraft(
                identity = IdentitySection(agentName = "Zero", timezone = ""),
            )
        val result = OnboardingDraftMapper.apply(current, identityWithBlankTz)
        assertEquals("America/New_York", result.identity.timezone)
        assertEquals("Zero", result.identity.agentName)
    }
}
