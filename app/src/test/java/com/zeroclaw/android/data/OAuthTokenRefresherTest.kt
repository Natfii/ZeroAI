/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for [OAuthTokenRefresher] companion-object helpers.
 */
@DisplayName("OAuthTokenRefresher")
class OAuthTokenRefresherTest {
    @Test
    @DisplayName("refreshUrlForProvider returns Anthropic URL for anthropic provider")
    fun `refreshUrlForProvider returns anthropic url for anthropic`() {
        assertEquals(
            "https://claude.ai/api/oauth/token",
            OAuthTokenRefresher.refreshUrlForProvider("anthropic"),
        )
    }

    @Test
    @DisplayName("refreshUrlForProvider returns OpenAI URL for openai provider")
    fun `refreshUrlForProvider returns openai url for openai`() {
        assertEquals(
            "https://auth.openai.com/oauth/token",
            OAuthTokenRefresher.refreshUrlForProvider("openai"),
        )
    }

    @Test
    @DisplayName("refreshUrlForProvider throws for unknown providers")
    fun `refreshUrlForProvider throws for unknown providers`() {
        assertThrows(IllegalArgumentException::class.java) {
            OAuthTokenRefresher.refreshUrlForProvider("gemini")
        }
    }
}
