/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.onboarding

import com.zeroclaw.android.data.identity.AieosDerivationEngine
import com.zeroclaw.android.ui.screen.onboarding.state.PersonalityArchetype
import com.zeroclaw.android.ui.screen.onboarding.state.PersonalityStepState
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * Dedicated round-trip tests for [AieosDerivationEngine.prefillFromJson].
 *
 * Ensures that deriving an AIEOS identity JSON and then prefilling a
 * [PersonalityStepState] from that JSON preserves all user-configured fields,
 * covering re-onboarding and edit-personality scenarios.
 */
class PersonalityPrefillTest {
    @Test
    @DisplayName("Round-trip: derive then prefill preserves all fields")
    fun `round trip preserves fields`() {
        val original =
            PersonalityStepState(
                agentName = "Zephyr",
                role = "mentor",
                archetype = PersonalityArchetype.DARK_ACADEMIC,
                formality = "formal",
                verbosity = "verbose",
                catchphrases = listOf("Indeed"),
                forbiddenWords = listOf("bruh"),
                interests = setOf("Philosophy", "History", "Science"),
            )

        val json = AieosDerivationEngine.derive(original)
        val restored = AieosDerivationEngine.prefillFromJson(json)

        assertEquals(original.agentName, restored.agentName)
        assertEquals(original.role, restored.role)
        assertEquals(original.archetype, restored.archetype)
        assertEquals(original.formality, restored.formality)
        assertEquals(original.verbosity, restored.verbosity)
        assertEquals(original.catchphrases, restored.catchphrases)
        assertEquals(original.forbiddenWords, restored.forbiddenWords)
        assertEquals(original.interests, restored.interests)
        assertFalse(restored.skipped)
    }

    @ParameterizedTest
    @EnumSource(PersonalityArchetype::class)
    @DisplayName("Round-trip preserves archetype for all types")
    fun `round trip preserves archetype`(archetype: PersonalityArchetype) {
        val original =
            PersonalityStepState(
                agentName = "Test",
                archetype = archetype,
            )
        val json = AieosDerivationEngine.derive(original)
        val restored = AieosDerivationEngine.prefillFromJson(json)
        assertEquals(archetype, restored.archetype)
    }

    @Test
    @DisplayName("Prefill from skip fallback restores Sick Zero")
    fun `prefill from skip fallback`() {
        val json = AieosDerivationEngine.deriveSkipFallback()
        val restored = AieosDerivationEngine.prefillFromJson(json)

        assertEquals("Sick Zero", restored.agentName)
        assertEquals(PersonalityArchetype.CHILL_COMPANION, restored.archetype)
    }

    @Test
    @DisplayName("Prefill from legacy minimal JSON uses defaults")
    fun `prefill from legacy json`() {
        val legacyJson =
            JSONObject()
                .apply {
                    put(
                        "identity",
                        JSONObject().apply {
                            put("names", JSONObject().put("first", "OldAgent"))
                        },
                    )
                }.toString()

        val restored = AieosDerivationEngine.prefillFromJson(legacyJson)
        assertEquals("OldAgent", restored.agentName)
        assertNull(restored.archetype)
        assertEquals("balanced", restored.formality)
        assertEquals("normal", restored.verbosity)
    }

    @Test
    @DisplayName("Round-trip preserves empty catchphrases and forbidden words")
    fun `round trip preserves empty lists`() {
        val original =
            PersonalityStepState(
                agentName = "Test",
                archetype = PersonalityArchetype.STOIC_OPERATOR,
                catchphrases = emptyList(),
                forbiddenWords = emptyList(),
            )
        val json = AieosDerivationEngine.derive(original)
        val restored = AieosDerivationEngine.prefillFromJson(json)
        assertEquals(emptyList<String>(), restored.catchphrases)
        assertEquals(emptyList<String>(), restored.forbiddenWords)
    }

    @Test
    @DisplayName("Round-trip preserves interests as a set")
    fun `round trip preserves interests`() {
        val topics = setOf("Gaming", "Memes & Internet Culture", "D&D & Tabletop")
        val original =
            PersonalityStepState(
                agentName = "Test",
                archetype = PersonalityArchetype.NAVI,
                interests = topics,
            )
        val json = AieosDerivationEngine.derive(original)
        val restored = AieosDerivationEngine.prefillFromJson(json)
        assertEquals(topics, restored.interests)
    }

    @Test
    @DisplayName("currentSubStep resets to 0 after prefill")
    fun `prefill resets sub-step`() {
        val original =
            PersonalityStepState(
                agentName = "Test",
                archetype = PersonalityArchetype.HYPE_BEAST,
                currentSubStep = 3,
            )
        val json = AieosDerivationEngine.derive(original)
        val restored = AieosDerivationEngine.prefillFromJson(json)
        assertEquals(0, restored.currentSubStep)
    }
}
