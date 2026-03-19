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

class PersonalityCoordinatorTest {
    @Test
    @DisplayName("PersonalityStepState defaults to sub-step 0")
    fun `initial sub-step is zero`() {
        val state = PersonalityStepState()
        assertEquals(0, state.currentSubStep)
    }

    @Test
    @DisplayName("Skip sets skipped flag and fills Sick Zero defaults")
    fun `skip produces sick zero`() {
        val skipped =
            PersonalityStepState(
                agentName = "Sick Zero",
                archetype = PersonalityArchetype.CHILL_COMPANION,
                skipped = true,
            )
        assertTrue(skipped.skipped)
        assertEquals("Sick Zero", skipped.agentName)
        assertEquals(PersonalityArchetype.CHILL_COMPANION, skipped.archetype)
    }

    @Test
    @DisplayName("Completing screens 1+2 makes state minimally complete")
    fun `screens 1 and 2 make minimally complete`() {
        val state =
            PersonalityStepState(
                agentName = "MyAgent",
                role = "companion",
                archetype = PersonalityArchetype.WISE_MENTOR,
            )
        assertTrue(state.isMinimallyComplete)
    }

    @Test
    @DisplayName("Screens 3-5 using defaults do NOT invalidate minimal completion")
    fun `default screens 3 to 5 still complete`() {
        val state =
            PersonalityStepState(
                agentName = "MyAgent",
                archetype = PersonalityArchetype.HYPE_BEAST,
            )
        assertTrue(state.isMinimallyComplete)
        assertFalse(state.skipped)
    }

    @Test
    @DisplayName("Sub-step constants are defined correctly")
    fun `sub-step constants`() {
        assertEquals(5, OnboardingCoordinator.PERSONALITY_SUB_STEPS)
        assertEquals(0, OnboardingCoordinator.PERSONALITY_NAME_ROLE)
        assertEquals(1, OnboardingCoordinator.PERSONALITY_ARCHETYPE)
        assertEquals(2, OnboardingCoordinator.PERSONALITY_COMMUNICATION)
        assertEquals(3, OnboardingCoordinator.PERSONALITY_CATCHPHRASES)
        assertEquals(4, OnboardingCoordinator.PERSONALITY_INTERESTS)
    }
}
