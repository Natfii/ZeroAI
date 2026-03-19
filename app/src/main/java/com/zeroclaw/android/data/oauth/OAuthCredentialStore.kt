/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.data.oauth

import com.zeroclaw.android.data.ProviderRegistry
import com.zeroclaw.android.data.repository.ApiKeyRepository
import com.zeroclaw.android.model.ApiKey
import com.zeroclaw.android.model.KeyStatus
import java.util.UUID

/**
 * Mirrors OAuth provider logins into [ApiKeyRepository].
 *
 * The Android daemon startup path reads credentials from [ApiKeyRepository],
 * while the Rust auth layer also reads `auth-profiles.json`. Successful OAuth
 * connect and disconnect flows must update both stores together to avoid drift.
 */
object OAuthCredentialStore {
    /**
     * Resolves the API-key repository provider identifier for an OAuth-capable
     * provider.
     *
     * OpenAI OAuth sessions are stored under `openai-codex`, while Gemini
     * aliases are normalized to the canonical `google-gemini` provider.
     *
     * @param providerId Canonical provider ID or alias.
     * @return Provider ID used by [ApiKeyRepository].
     */
    fun apiKeyProviderId(providerId: String): String {
        val canonicalProvider = ProviderRegistry.findById(providerId)?.id ?: providerId.lowercase()
        return when (canonicalProvider) {
            "openai", "openai-codex" -> "openai-codex"
            "gemini", "google", "google-gemini" -> "google-gemini"
            else -> canonicalProvider
        }
    }

    /**
     * Saves or updates the OAuth-backed credential entry for [providerId].
     *
     * @param apiKeyRepository Credential repository used by daemon startup.
     * @param providerId Canonical provider ID or alias.
     * @param accessToken Current OAuth access token.
     * @param refreshToken OAuth refresh token.
     * @param expiresAtMs Epoch milliseconds when the access token expires.
     */
    suspend fun saveOAuthTokens(
        apiKeyRepository: ApiKeyRepository,
        providerId: String,
        accessToken: String,
        refreshToken: String,
        expiresAtMs: Long,
    ) {
        val repositoryProvider = apiKeyProviderId(providerId)
        val existing = apiKeyRepository.getByProvider(repositoryProvider)
        apiKeyRepository.save(
            ApiKey(
                id = existing?.id ?: UUID.randomUUID().toString(),
                provider = repositoryProvider,
                key = accessToken,
                baseUrl = existing?.baseUrl.orEmpty(),
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                status = KeyStatus.ACTIVE,
                refreshToken = refreshToken,
                expiresAt = expiresAtMs,
            ),
        )
    }

    /**
     * Removes the OAuth-backed credential entry for [providerId].
     *
     * @param apiKeyRepository Credential repository used by daemon startup.
     * @param providerId Canonical provider ID or alias.
     */
    suspend fun removeOAuthTokens(
        apiKeyRepository: ApiKeyRepository,
        providerId: String,
    ) {
        val repositoryProvider = apiKeyProviderId(providerId)
        apiKeyRepository.getByProvider(repositoryProvider)?.let { apiKeyRepository.delete(it.id) }
    }
}
