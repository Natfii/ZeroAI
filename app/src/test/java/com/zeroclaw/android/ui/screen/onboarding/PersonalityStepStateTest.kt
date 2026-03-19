/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.onboarding

import com.zeroclaw.android.ui.screen.onboarding.state.PersonalityArchetype
import com.zeroclaw.android.ui.screen.onboarding.state.PersonalityStepState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for [PersonalityStepState] and [PersonalityArchetype].
 */
class PersonalityStepStateTest {
    @Test
    @DisplayName("PersonalityStepState defaults to sensible values")
    fun `PersonalityStepState defaults`() {
        val state = PersonalityStepState()
        assertEquals("", state.agentName)
        assertEquals("", state.role)
        assertEquals(null, state.archetype)
        assertEquals("balanced", state.formality)
        assertEquals("normal", state.verbosity)
        assertEquals(emptyList<String>(), state.catchphrases)
        assertEquals(emptyList<String>(), state.forbiddenWords)
        assertEquals(emptySet<String>(), state.interests)
        assertEquals(0, state.currentSubStep)
        assertFalse(state.skipped)
    }

    @Test
    @DisplayName("All 8 archetypes are defined")
    fun `all archetypes exist`() {
        assertEquals(8, PersonalityArchetype.entries.size)
    }

    @Test
    @DisplayName("isMinimallyComplete requires name and archetype")
    fun `isMinimallyComplete requires name and archetype`() {
        val empty = PersonalityStepState()
        assertFalse(empty.isMinimallyComplete)

        val nameOnly = empty.copy(agentName = "Zero")
        assertFalse(nameOnly.isMinimallyComplete)

        val archetypeOnly = empty.copy(archetype = PersonalityArchetype.CHILL_COMPANION)
        assertFalse(archetypeOnly.isMinimallyComplete)

        val complete = nameOnly.copy(archetype = PersonalityArchetype.CHILL_COMPANION)
        assertTrue(complete.isMinimallyComplete)
    }
}
