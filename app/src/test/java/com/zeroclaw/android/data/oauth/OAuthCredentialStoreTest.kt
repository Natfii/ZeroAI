/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.data.oauth

import com.zeroclaw.android.data.repository.InMemoryApiKeyRepository
import com.zeroclaw.android.model.ApiKey
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for [OAuthCredentialStore].
 */
@DisplayName("OAuthCredentialStore")
class OAuthCredentialStoreTest {
    @Test
    @DisplayName("OpenAI OAuth is stored under openai-codex")
    fun `OpenAI OAuth is stored under openai codex`() =
        runTest {
            val repo = InMemoryApiKeyRepository()

            OAuthCredentialStore.saveOAuthTokens(
                apiKeyRepository = repo,
                providerId = "openai",
                accessToken = "access-token",
                refreshToken = "refresh-token",
                expiresAtMs = 1234L,
            )

            val saved = repo.getByProvider("openai-codex")
            assertEquals("openai-codex", saved?.provider)
            assertEquals("access-token", saved?.key)
            assertEquals("refresh-token", saved?.refreshToken)
            assertEquals(1234L, saved?.expiresAt)
            assertNull(repo.getByProvider("openai"))
        }

    @Test
    @DisplayName("Gemini aliases reuse the existing google-gemini entry")
    fun `Gemini aliases reuse the existing google gemini entry`() =
        runTest {
            val repo = InMemoryApiKeyRepository()
            repo.save(
                ApiKey(
                    id = "existing-id",
                    provider = "google-gemini",
                    key = "old-access",
                    refreshToken = "old-refresh",
                    expiresAt = 10L,
                ),
            )

            OAuthCredentialStore.saveOAuthTokens(
                apiKeyRepository = repo,
                providerId = "gemini",
                accessToken = "new-access",
                refreshToken = "new-refresh",
                expiresAtMs = 20L,
            )

            val saved = repo.getByProvider("google-gemini")
            assertEquals("existing-id", saved?.id)
            assertEquals("new-access", saved?.key)
            assertEquals("new-refresh", saved?.refreshToken)
            assertEquals(20L, saved?.expiresAt)
        }

    @Test
    @DisplayName("removeOAuthTokens deletes the mirrored repository entry")
    fun `removeOAuthTokens deletes the mirrored repository entry`() =
        runTest {
            val repo = InMemoryApiKeyRepository()
            repo.save(
                ApiKey(
                    id = "codex-id",
                    provider = "openai-codex",
                    key = "access-token",
                    refreshToken = "refresh-token",
                    expiresAt = 1234L,
                ),
            )

            OAuthCredentialStore.removeOAuthTokens(repo, "openai")

            assertNull(repo.getByProvider("openai-codex"))
        }
}
