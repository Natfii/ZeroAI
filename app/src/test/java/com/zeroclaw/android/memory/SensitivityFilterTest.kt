/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.memory

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("SensitivityFilter")
class SensitivityFilterTest {
    @Test
    @DisplayName("blocks OpenAI API key")
    fun `blocks api key`() {
        assertTrue(SensitivityFilter.containsSensitive("My API key is sk-abc123def456ghi789jkl012mno345pqr678"))
    }

    @Test
    @DisplayName("blocks credit card number")
    fun `blocks credit card`() {
        assertTrue(SensitivityFilter.containsSensitive("My card is 4111-1111-1111-1111"))
    }

    @Test
    @DisplayName("passes normal text")
    fun `passes normal text`() {
        assertFalse(SensitivityFilter.containsSensitive("I prefer tabs over spaces"))
    }

    @Test
    @DisplayName("blocks password disclosure")
    fun `blocks password`() {
        assertTrue(SensitivityFilter.containsSensitive("My password is hunter2"))
    }

    @Test
    @DisplayName("blocks SSN")
    fun `blocks ssn`() {
        assertTrue(SensitivityFilter.containsSensitive("My SSN is 123-45-6789"))
    }

    @Test
    @DisplayName("passes code with key-like variable name")
    fun `passes code key name`() {
        assertFalse(SensitivityFilter.containsSensitive("Set the dictionary key to 'api_key'"))
    }
}
