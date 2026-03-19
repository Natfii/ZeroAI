/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.data.identity

import com.zeroclaw.android.model.CheckStatus
import com.zeroclaw.android.service.DoctorValidator
import com.zeroclaw.android.ui.screen.onboarding.state.PersonalityArchetype
import com.zeroclaw.android.ui.screen.onboarding.state.PersonalityStepState
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/** Integration tests verifying the full AIEOS personality pipeline. */
class AieosFullFlowTest {
    @ParameterizedTest
    @EnumSource(PersonalityArchetype::class)
    @DisplayName("Derive then doctor check passes for every archetype")
    fun `derive then doctor check passes`(archetype: PersonalityArchetype) {
        val state =
            PersonalityStepState(
                agentName = "Agent",
                archetype = archetype,
            )
        val json = AieosDerivationEngine.derive(state)
        val check = DoctorValidator.checkIdentityHealth(json)
        assertEquals(
            CheckStatus.PASS,
            check.status,
            "Archetype $archetype should produce a passing doctor check",
        )
    }

    @Test
    @DisplayName("Skip fallback triggers doctor WARN not FAIL")
    fun `skip fallback doctor warns`() {
        val json = AieosDerivationEngine.deriveSkipFallback()
        val check = DoctorValidator.checkIdentityHealth(json)
        assertEquals(CheckStatus.WARN, check.status)
    }

    @Test
    @DisplayName("Derive then prefill then re-derive produces consistent JSON")
    fun `derive prefill re-derive round trip`() {
        val original =
            PersonalityStepState(
                agentName = "RoundTrip",
                role = "rival",
                archetype = PersonalityArchetype.SNARKY_SIDEKICK,
                formality = "casual",
                verbosity = "terse",
                catchphrases = listOf("git gud"),
                forbiddenWords = listOf("please"),
                interests = setOf("Gaming", "Memes & Internet Culture"),
            )

        val json1 = AieosDerivationEngine.derive(original)
        val restored = AieosDerivationEngine.prefillFromJson(json1)
        val json2 = AieosDerivationEngine.derive(restored)

        val obj1 = JSONObject(json1)
        val obj2 = JSONObject(json2)
        assertEquals(
            obj1.getJSONObject("psychology").getString("mbti"),
            obj2.getJSONObject("psychology").getString("mbti"),
        )
        assertEquals(
            obj1.getBoolean("doctor_needed"),
            obj2.getBoolean("doctor_needed"),
        )
        assertEquals(
            obj1.getString("aieos_version"),
            obj2.getString("aieos_version"),
        )
    }

    @Test
    @DisplayName("Completing screens 1+2 only clears doctor flag")
    fun `screens 1 and 2 only clears doctor`() {
        val state =
            PersonalityStepState(
                agentName = "Minimal",
                archetype = PersonalityArchetype.COZY_CARETAKER,
            )
        val json = AieosDerivationEngine.derive(state)
        assertFalse(JSONObject(json).getBoolean("doctor_needed"))
        assertEquals(CheckStatus.PASS, DoctorValidator.checkIdentityHealth(json).status)
    }

    @Test
    @DisplayName("Schema version survives round-trip")
    fun `schema version round trip`() {
        val state =
            PersonalityStepState(
                agentName = "Test",
                archetype = PersonalityArchetype.WISE_MENTOR,
            )
        val json = AieosDerivationEngine.derive(state)
        assertEquals("1.1", JSONObject(json).getString("aieos_version"))

        val restored = AieosDerivationEngine.prefillFromJson(json)
        val json2 = AieosDerivationEngine.derive(restored)
        assertEquals("1.1", JSONObject(json2).getString("aieos_version"))
    }

    @Test
    @DisplayName("Doctor check on re-derived JSON still passes")
    fun `re-derived json passes doctor`() {
        val original =
            PersonalityStepState(
                agentName = "Bounce",
                archetype = PersonalityArchetype.NAVI,
                interests = setOf("Gaming", "Anime & Manga"),
            )
        val json1 = AieosDerivationEngine.derive(original)
        val restored = AieosDerivationEngine.prefillFromJson(json1)
        val json2 = AieosDerivationEngine.derive(restored)

        assertEquals(CheckStatus.PASS, DoctorValidator.checkIdentityHealth(json2).status)
    }
}
