/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.data.identity

import com.zeroclaw.android.ui.screen.onboarding.state.PersonalityArchetype
import com.zeroclaw.android.ui.screen.onboarding.state.PersonalityStepState
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * Unit tests for [AieosDerivationEngine].
 *
 * Validates that all archetype presets produce well-formed AIEOS v1.1 JSON,
 * that round-tripping through [AieosDerivationEngine.prefillFromJson] preserves
 * state, and that the skip fallback behaves correctly.
 */
class AieosDerivationEngineTest {
    private val validMbtiPattern = Regex("^[IE][SN][TF][JP]$")

    @ParameterizedTest(name = "archetype {0} produces valid AIEOS JSON")
    @EnumSource(PersonalityArchetype::class)
    @DisplayName("All 8 archetypes produce valid JSON with correct structure")
    fun `all archetypes produce valid JSON`(archetype: PersonalityArchetype) {
        val state =
            PersonalityStepState(
                agentName = "TestBot",
                archetype = archetype,
            )
        val json = AieosDerivationEngine.derive(state)
        val root = JSONObject(json)

        assertEquals("1.1", root.getString("aieos_version"))
        assertFalse(root.getBoolean("doctor_needed"))

        val identity = root.getJSONObject("identity")
        assertNotNull(identity.getJSONObject("names").getString("first"))

        val psychology = root.getJSONObject("psychology")
        val mbti = psychology.getString("mbti")
        assertTrue(
            validMbtiPattern.matches(mbti),
            "MBTI '$mbti' must be 4 chars with correct letter positions",
        )

        val ocean = psychology.getJSONObject("ocean")
        for (trait in listOf(
            "openness",
            "conscientiousness",
            "extraversion",
            "agreeableness",
            "neuroticism",
        )) {
            val value = ocean.getDouble(trait)
            assertTrue(
                value in 0.0..1.0,
                "$trait=$value must be between 0.0 and 1.0",
            )
        }

        val neuralMatrix = psychology.getJSONObject("neural_matrix")
        assertTrue(neuralMatrix.length() > 0, "neural_matrix must not be empty")

        val moralCompass = psychology.getJSONArray("moral_compass")
        assertTrue(moralCompass.length() > 0, "moral_compass must not be empty")

        assertNotNull(root.getJSONObject("linguistics"))
        assertNotNull(root.getJSONObject("motivations"))
        assertNotNull(root.getJSONObject("capabilities"))
        assertNotNull(root.getJSONObject("physicality"))
        assertNotNull(root.getJSONObject("history"))
        assertNotNull(root.getJSONObject("interests"))

        val metadata = root.getJSONObject("_metadata")
        assertEquals(archetype.name, metadata.getString("archetype"))
    }

    @Test
    @DisplayName("Doctor flag is false when state is minimally complete")
    fun `doctor flag false when minimally complete`() {
        val state =
            PersonalityStepState(
                agentName = "Zero",
                archetype = PersonalityArchetype.WISE_MENTOR,
            )
        assertTrue(state.isMinimallyComplete)

        val root = JSONObject(AieosDerivationEngine.derive(state))
        assertFalse(root.getBoolean("doctor_needed"))
    }

    @Test
    @DisplayName("Doctor flag is true when state is NOT minimally complete")
    fun `doctor flag true when not minimally complete`() {
        val state =
            PersonalityStepState(
                agentName = "",
                archetype = null,
            )
        assertFalse(state.isMinimallyComplete)

        val root = JSONObject(AieosDerivationEngine.derive(state))
        assertTrue(root.getBoolean("doctor_needed"))
    }

    @Test
    @DisplayName("Skip fallback produces Sick Zero with doctor_needed=true")
    fun `skip fallback produces Sick Zero`() {
        val json = AieosDerivationEngine.deriveSkipFallback()
        val root = JSONObject(json)

        assertEquals(
            "Sick Zero",
            root
                .getJSONObject("identity")
                .getJSONObject("names")
                .getString("first"),
        )
        assertTrue(root.getBoolean("doctor_needed"))
        assertEquals("ISFP", root.getJSONObject("psychology").getString("mbti"))
        assertEquals("1.1", root.getString("aieos_version"))
    }

    @Test
    @DisplayName("Formality and verbosity pass through to linguistics")
    fun `formality and verbosity in linguistics`() {
        val state =
            PersonalityStepState(
                agentName = "Formal Bot",
                archetype = PersonalityArchetype.STOIC_OPERATOR,
                formality = "formal",
                verbosity = "terse",
            )
        val root = JSONObject(AieosDerivationEngine.derive(state))
        val linguistics = root.getJSONObject("linguistics")

        assertEquals("formal", linguistics.getString("formality"))

        val metadata = root.getJSONObject("_metadata")
        assertEquals("formal", metadata.getString("formality"))
        assertEquals("terse", metadata.getString("verbosity"))
    }

    @Test
    @DisplayName("Catchphrases and forbidden words pass through as arrays")
    fun `catchphrases and forbidden words pass through`() {
        val state =
            PersonalityStepState(
                agentName = "Catchy",
                archetype = PersonalityArchetype.HYPE_BEAST,
                catchphrases = listOf("Let's gooo!", "You got this!"),
                forbiddenWords = listOf("impossible", "can't"),
            )
        val root = JSONObject(AieosDerivationEngine.derive(state))
        val linguistics = root.getJSONObject("linguistics")

        val catchphrases = linguistics.getJSONArray("catchphrases")
        assertEquals(2, catchphrases.length())
        assertEquals("Let's gooo!", catchphrases.getString(0))
        assertEquals("You got this!", catchphrases.getString(1))

        val forbidden = linguistics.getJSONArray("forbidden_words")
        assertEquals(2, forbidden.length())
        assertEquals("impossible", forbidden.getString(0))
        assertEquals("can't", forbidden.getString(1))
    }

    @Test
    @DisplayName("History backstory is auto-generated and non-empty")
    fun `history backstory auto-generated and non-empty`() {
        val state =
            PersonalityStepState(
                agentName = "Athena",
                archetype = PersonalityArchetype.DARK_ACADEMIC,
                interests = setOf("Philosophy", "History"),
            )
        val root = JSONObject(AieosDerivationEngine.derive(state))
        val history = root.getJSONObject("history")
        val story = history.getString("origin_story")

        assertTrue(story.isNotEmpty(), "origin_story must not be empty")
        assertTrue(story.contains("Athena"), "origin_story should mention the agent name")
    }

    @Test
    @DisplayName("AIEOS version is always 1.1")
    fun `aieos version present`() {
        val state =
            PersonalityStepState(
                agentName = "VersionCheck",
                archetype = PersonalityArchetype.NAVI,
            )
        val root = JSONObject(AieosDerivationEngine.derive(state))
        assertEquals("1.1", root.getString("aieos_version"))
    }

    @Test
    @DisplayName("Prefill round-trip preserves original state fields")
    fun `prefill round trip`() {
        val original =
            PersonalityStepState(
                agentName = "RoundTrip",
                role = "assistant",
                archetype = PersonalityArchetype.COZY_CARETAKER,
                formality = "casual",
                verbosity = "verbose",
                catchphrases = listOf("There there"),
                forbiddenWords = listOf("hate"),
                interests = setOf("Cooking", "Gardening"),
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
    }

    @Test
    @DisplayName("Prefill from legacy JSON without _metadata returns null archetype")
    fun `prefill from legacy JSON`() {
        val legacyJson =
            JSONObject()
                .apply {
                    put("aieos_version", "1.1")
                    put(
                        "identity",
                        JSONObject().apply {
                            put(
                                "names",
                                JSONObject().apply {
                                    put("first", "OldBot")
                                },
                            )
                        },
                    )
                    put(
                        "linguistics",
                        JSONObject().apply {
                            put("formality", "balanced")
                            put("catchphrases", org.json.JSONArray())
                            put("forbidden_words", org.json.JSONArray())
                        },
                    )
                }.toString()

        val state = AieosDerivationEngine.prefillFromJson(legacyJson)

        assertEquals("OldBot", state.agentName)
        assertNull(state.archetype)
        assertEquals("balanced", state.formality)
    }

    @Test
    @DisplayName("Prefill from skip fallback recovers Sick Zero state")
    fun `prefill from skip fallback`() {
        val json = AieosDerivationEngine.deriveSkipFallback()
        val state = AieosDerivationEngine.prefillFromJson(json)

        assertEquals("Sick Zero", state.agentName)
        assertEquals(PersonalityArchetype.CHILL_COMPANION, state.archetype)
        assertTrue(state.skipped)
    }

    @Test
    @DisplayName("Interests appear in the interests section")
    fun `interests pass through`() {
        val state =
            PersonalityStepState(
                agentName = "Hobbyist",
                archetype = PersonalityArchetype.CHILL_COMPANION,
                interests = setOf("Programming", "Music", "Art"),
            )
        val root = JSONObject(AieosDerivationEngine.derive(state))
        val interests = root.getJSONObject("interests")
        val hobbies = interests.getJSONArray("hobbies")

        assertEquals(3, hobbies.length())
        val hobbyList = (0 until hobbies.length()).map { hobbies.getString(it) }
        assertTrue(hobbyList.containsAll(setOf("Programming", "Music", "Art")))
    }
}
