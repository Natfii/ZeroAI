/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.settings.apikeys

import com.zeroclaw.android.data.repository.InMemoryApiKeyRepository
import com.zeroclaw.android.model.ApiKey
import com.zeroclaw.android.ui.screen.settings.TestSettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for the key-deletion side-effect logic extracted from [ApiKeysViewModel].
 *
 * Tests the function that clears the fallback daemon route when the backing
 * provider credentials are removed.
 */
@DisplayName("ApiKeysViewModel key-deletion logic")
class ApiKeysViewModelLogicTest {
    @Test
    @DisplayName("clears defaultProvider when its key is deleted and no keys remain")
    fun `clears defaultProvider when its key is deleted and no keys remain`() =
        runTest {
            val keyRepo = InMemoryApiKeyRepository()
            val settingsRepo = TestSettingsRepository()
            val anthropicKey = ApiKey(id = "1", provider = "anthropic", key = "anthropic_connection_not_real")
            keyRepo.save(anthropicKey)
            settingsRepo.setDefaultProvider("anthropic")
            settingsRepo.setDefaultModel("claude-sonnet-4-6")

            keyRepo.delete("1")

            clearDefaultProviderIfNeeded(
                deletedKey = anthropicKey,
                settingsRepo = settingsRepo,
            )

            val settings = settingsRepo.settings.first()
            assertEquals("", settings.defaultProvider)
            assertEquals("", settings.defaultModel)
        }

    @Test
    @DisplayName("clears fallback route instead of guessing from remaining keys")
    fun `clears fallback route instead of guessing from remaining keys`() =
        runTest {
            val keyRepo = InMemoryApiKeyRepository()
            val settingsRepo = TestSettingsRepository()
            val anthropicKey = ApiKey(id = "1", provider = "anthropic", key = "anthropic_connection_not_real")
            val openaiKey = ApiKey(id = "2", provider = "openai", key = "openai_connection_not_real")
            keyRepo.save(anthropicKey)
            keyRepo.save(openaiKey)
            settingsRepo.setDefaultProvider("anthropic")
            settingsRepo.setDefaultModel("claude-sonnet-4-6")

            keyRepo.delete("1")

            clearDefaultProviderIfNeeded(
                deletedKey = anthropicKey,
                settingsRepo = settingsRepo,
            )

            val settings = settingsRepo.settings.first()
            assertEquals("", settings.defaultProvider)
            assertEquals("", settings.defaultModel)
        }

    @Test
    @DisplayName("does not touch defaultProvider when deleted key is not the default")
    fun `does not touch defaultProvider when deleted key is not the default`() =
        runTest {
            val keyRepo = InMemoryApiKeyRepository()
            val settingsRepo = TestSettingsRepository()
            val anthropicKey = ApiKey(id = "1", provider = "anthropic", key = "anthropic_connection_not_real")
            val openaiKey = ApiKey(id = "2", provider = "openai", key = "openai_connection_not_real")
            keyRepo.save(anthropicKey)
            keyRepo.save(openaiKey)
            settingsRepo.setDefaultProvider("anthropic")
            settingsRepo.setDefaultModel("claude-sonnet-4-6")

            clearDefaultProviderIfNeeded(
                deletedKey = openaiKey,
                settingsRepo = settingsRepo,
            )

            val settings = settingsRepo.settings.first()
            assertEquals("anthropic", settings.defaultProvider)
            assertEquals("claude-sonnet-4-6", settings.defaultModel)
        }
}
