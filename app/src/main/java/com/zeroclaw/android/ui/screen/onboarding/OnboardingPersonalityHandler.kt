/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.onboarding

import com.zeroclaw.android.ui.screen.onboarding.state.IdentityStepState
import com.zeroclaw.android.ui.screen.onboarding.state.PersonalityArchetype
import com.zeroclaw.android.ui.screen.onboarding.state.PersonalityStepState
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Owns the identity-step and personality-step setters that the
 * onboarding wizard exposes. Extracted from [OnboardingCoordinator] so
 * the coordinator no longer carries ~120 LOC of straight-line state
 * mutations.
 *
 * All mutations are synchronous — no coroutine scope needed. The
 * coordinator passes its own state flow refs; this handler does not
 * own them.
 *
 * @property identityState The shared identity step state flow.
 * @property personalityState The shared personality step state flow.
 */
@Suppress("OutdatedDocumentation")
internal class OnboardingPersonalityHandler(
    private val identityState: MutableStateFlow<IdentityStepState>,
    private val personalityState: MutableStateFlow<PersonalityStepState>,
) {
    /** Sets the agent name on the identity-step state. */
    fun setAgentName(name: String) {
        identityState.value = identityState.value.copy(agentName = name)
    }

    /** Sets the human user's display name. */
    fun setUserName(name: String) {
        identityState.value = identityState.value.copy(userName = name)
    }

    /** Sets the IANA timezone (e.g. `"America/New_York"`). */
    fun setTimezone(tz: String) {
        identityState.value = identityState.value.copy(timezone = tz)
    }

    /** Sets the preferred communication style / tone description. */
    fun setCommunicationStyle(style: String) {
        identityState.value = identityState.value.copy(communicationStyle = style)
    }

    /** Sets the identity-format flavour (`"openclaw"` or `"aieos"`). */
    fun setIdentityFormat(format: String) {
        identityState.value = identityState.value.copy(identityFormat = format)
    }

    /**
     * Updates the agent name on BOTH the personality and identity step
     * states. The personality step's input is the canonical surface for
     * the agent's display name once the user reaches it; identity stays
     * in sync so the activation summary and saved JSON agree.
     */
    fun setPersonalityAgentName(name: String) {
        personalityState.value = personalityState.value.copy(agentName = name)
        identityState.value = identityState.value.copy(agentName = name)
    }

    /** Updates the agent role in the personality builder state. */
    fun setPersonalityRole(role: String) {
        personalityState.value = personalityState.value.copy(role = role)
    }

    /** Updates the selected personality archetype. */
    fun setPersonalityArchetype(archetype: PersonalityArchetype) {
        personalityState.value = personalityState.value.copy(archetype = archetype)
    }

    /** Updates the communication formality level. */
    fun setPersonalityFormality(formality: String) {
        personalityState.value = personalityState.value.copy(formality = formality)
    }

    /** Updates the communication verbosity level. */
    fun setPersonalityVerbosity(verbosity: String) {
        personalityState.value = personalityState.value.copy(verbosity = verbosity)
    }

    /** Updates the agent's catchphrases list. */
    fun setPersonalityCatchphrases(phrases: List<String>) {
        personalityState.value = personalityState.value.copy(catchphrases = phrases)
    }

    /** Updates the agent's forbidden-words list. */
    fun setPersonalityForbiddenWords(words: List<String>) {
        personalityState.value = personalityState.value.copy(forbiddenWords = words)
    }

    /** Toggles an interest topic in or out of the selected set. */
    fun togglePersonalityInterest(topic: String) {
        val current = personalityState.value.interests
        val updated = if (topic in current) current - topic else current + topic
        personalityState.value = personalityState.value.copy(interests = updated)
    }

    /** Advances to the next personality sub-screen. */
    fun advancePersonalitySubStep() {
        personalityState.value =
            personalityState.value.copy(
                currentSubStep = personalityState.value.currentSubStep + 1,
            )
    }

    /** Returns to the previous personality sub-screen, clamped at zero. */
    fun retreatPersonalitySubStep() {
        personalityState.value =
            personalityState.value.copy(
                currentSubStep = maxOf(0, personalityState.value.currentSubStep - 1),
            )
    }

    /**
     * Skips personality setup, filling in "Sick Zero" fallback defaults
     * on both state flows so subsequent steps see a coherent name.
     */
    fun skipPersonality() {
        personalityState.value =
            PersonalityStepState(
                agentName = "Sick Zero",
                archetype = PersonalityArchetype.CHILL_COMPANION,
                skipped = true,
            )
        identityState.value = identityState.value.copy(agentName = "Sick Zero")
    }
}
