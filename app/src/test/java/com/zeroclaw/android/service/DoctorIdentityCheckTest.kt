/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.service

import com.zeroclaw.android.model.CheckStatus
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for [DoctorValidator.checkIdentityHealth].
 *
 * Validates the AIEOS identity JSON health check logic including
 * blank input, corrupt JSON, missing name, doctor flag, and valid identity.
 */
class DoctorIdentityCheckTest {
    @Test
    @DisplayName("Valid identity without doctor flag returns PASS")
    fun `valid identity passes`() {
        val json =
            JSONObject()
                .apply {
                    put("aieos_version", "1.1")
                    put("doctor_needed", false)
                    put(
                        "identity",
                        JSONObject().apply {
                            put("names", JSONObject().put("first", "Zero"))
                        },
                    )
                    put("psychology", JSONObject().put("mbti", "ENFP"))
                }.toString()

        val check = DoctorValidator.checkIdentityHealth(json)
        assertEquals(CheckStatus.PASS, check.status)
        assertEquals("config-identity", check.id)
    }

    @Test
    @DisplayName("Identity with doctor_needed=true returns WARN")
    fun `doctor flag triggers warn`() {
        val json =
            JSONObject()
                .apply {
                    put("aieos_version", "1.1")
                    put("doctor_needed", true)
                    put(
                        "identity",
                        JSONObject().apply {
                            put("names", JSONObject().put("first", "Sick Zero"))
                        },
                    )
                }.toString()

        val check = DoctorValidator.checkIdentityHealth(json)
        assertEquals(CheckStatus.WARN, check.status)
        assertTrue(check.detail.contains("incomplete", ignoreCase = true))
    }

    @Test
    @DisplayName("Empty identity JSON returns FAIL")
    fun `empty json fails`() {
        val check = DoctorValidator.checkIdentityHealth("")
        assertEquals(CheckStatus.FAIL, check.status)
    }

    @Test
    @DisplayName("Corrupt identity JSON returns FAIL")
    fun `corrupt json fails`() {
        val check = DoctorValidator.checkIdentityHealth("{not valid json")
        assertEquals(CheckStatus.FAIL, check.status)
    }

    @Test
    @DisplayName("Missing identity.names.first returns FAIL")
    fun `missing name fails`() {
        val json =
            JSONObject()
                .apply {
                    put("aieos_version", "1.1")
                    put("doctor_needed", false)
                    put("identity", JSONObject())
                }.toString()

        val check = DoctorValidator.checkIdentityHealth(json)
        assertEquals(CheckStatus.FAIL, check.status)
    }

    @Test
    @DisplayName("Complete identity without doctor flag does NOT false-positive")
    fun `no false positive on complete identity`() {
        val json =
            JSONObject()
                .apply {
                    put("aieos_version", "1.1")
                    put("doctor_needed", false)
                    put(
                        "identity",
                        JSONObject().apply {
                            put("names", JSONObject().put("first", "MyAgent"))
                            put("role", "companion")
                        },
                    )
                    put(
                        "psychology",
                        JSONObject().apply {
                            put("mbti", "INFJ")
                            put(
                                "ocean",
                                JSONObject().apply {
                                    put("openness", 0.8)
                                    put("conscientiousness", 0.7)
                                    put("extraversion", 0.4)
                                    put("agreeableness", 0.9)
                                    put("neuroticism", 0.3)
                                },
                            )
                        },
                    )
                    put("linguistics", JSONObject().put("formality", "balanced"))
                    put("motivations", JSONObject().put("core_drive", "guide others"))
                }.toString()

        val check = DoctorValidator.checkIdentityHealth(json)
        assertEquals(CheckStatus.PASS, check.status, "Complete identity should PASS, not trigger doctor")
    }
}
