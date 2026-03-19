/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.service

import com.zeroclaw.android.model.Agent
import com.zeroclaw.android.model.AppSettings
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SlotAwareAgentConfigTest {
    private fun fakeAuthProfile(
        provider: String,
        accountId: String,
    ) = com.zeroclaw.ffi.FfiAuthProfile(
        id = "$provider:default",
        provider = provider,
        profileName = "default",
        kind = "oauth",
        isActive = true,
        expiresAtMs = System.currentTimeMillis() + 60_000L,
        accountId = accountId,
        scopes = "",
        metadataJson = "",
        createdAtMs = 1L,
        updatedAtMs = 1L,
    )

    @Test
    fun `orderedConfiguredAgents skips connection-only slots and sorts fixed slots before legacy rows`() {
        val agents =
            listOf(
                Agent(
                    id = "legacy-zeta",
                    name = "Zeta",
                    provider = "openai",
                    modelName = "gpt-4o",
                ),
                Agent(
                    id = "openai-api",
                    slotId = "openai-api",
                    name = "Custom OpenAI Label",
                    provider = "openai",
                    modelName = "gpt-5",
                ),
                Agent(
                    id = "gemini-api",
                    slotId = "gemini-api",
                    name = "Custom Gemini Label",
                    provider = "google-gemini",
                    modelName = "gemini-2.5-pro",
                ),
                Agent(
                    id = "legacy-alpha",
                    name = "Alpha",
                    provider = "anthropic",
                    modelName = "claude-sonnet-4-6",
                ),
            )

        val ordered = SlotAwareAgentConfig.orderedConfiguredAgents(agents)

        assertEquals(
            listOf("gemini-api", "openai-api", "legacy-alpha", "legacy-zeta"),
            ordered.map(Agent::id),
        )
        assertEquals("Gemini API", SlotAwareAgentConfig.configName(ordered.first()))
        assertEquals("gemini", SlotAwareAgentConfig.configProvider(ordered.first()))
    }

    @Test
    fun `resolveEffectiveDefaults prefers slot-backed configured agents`() {
        runTest {
            val settings =
                AppSettings(
                    defaultProvider = "openai",
                    defaultModel = "gpt-4o",
                )
            val agents =
                listOf(
                    Agent(
                        id = "legacy-openai",
                        name = "Legacy OpenAI",
                        provider = "openai",
                        modelName = "gpt-4.1",
                    ),
                    Agent(
                        id = "gemini-api",
                        slotId = "gemini-api",
                        name = "Old Label",
                        provider = "google-gemini",
                        modelName = "gemini-2.5-flash",
                    ),
                )

            val resolved =
                SlotAwareAgentConfig.resolveEffectiveDefaults(settings, agents) { true }

            assertEquals("gemini", resolved.defaultProvider)
            assertEquals("gemini-2.5-flash", resolved.defaultModel)
        }
    }

    @Test
    fun `api-key-only providers ignore standalone oauth profiles`() =
        runTest {
            val usable =
                SlotAwareAgentConfig.hasUsableProviderCredentials(
                    provider = "google-gemini",
                    apiKey = null,
                    authProfiles =
                        listOf(fakeAuthProfile("gemini", "zero@example.com")),
                )

            assertEquals(false, usable)
        }

    @Test
    fun `oauth routing providers still accept standalone auth profiles`() =
        runTest {
            val authProfiles =
                listOf(
                    fakeAuthProfile("openai-codex", "codex@example.com"),
                    fakeAuthProfile("anthropic", "claude@example.com"),
                )

            assertEquals(
                true,
                SlotAwareAgentConfig.hasUsableProviderCredentials(
                    provider = "openai",
                    apiKey = null,
                    authProfiles = authProfiles,
                ),
            )
            assertEquals(
                false,
                SlotAwareAgentConfig.hasUsableProviderCredentials(
                    provider = "anthropic",
                    apiKey = null,
                    authProfiles = authProfiles,
                ),
            )
        }

    @Test
    fun `daemon routing accepts openai and anthropic managed auth profiles`() =
        runTest {
            val authProfiles =
                listOf(
                    fakeAuthProfile("openai-codex", "codex@example.com"),
                    fakeAuthProfile("anthropic", "claude@example.com"),
                )

            assertEquals(
                true,
                SlotAwareAgentConfig.hasUsableDaemonProviderCredentials(
                    provider = "openai",
                    apiKey = null,
                    authProfiles = authProfiles,
                ),
            )
            assertEquals(
                true,
                SlotAwareAgentConfig.hasUsableDaemonProviderCredentials(
                    provider = "anthropic",
                    apiKey = null,
                    authProfiles = authProfiles,
                ),
            )
        }

    @Test
    fun `daemon routing rejects gemini managed auth without direct api key`() =
        runTest {
            val authProfiles =
                listOf(
                    fakeAuthProfile("gemini", "user@example.com"),
                )

            assertEquals(
                false,
                SlotAwareAgentConfig.hasUsableDaemonProviderCredentials(
                    provider = "google-gemini",
                    apiKey = null,
                    authProfiles = authProfiles,
                ),
            )
        }

    @Test
    fun `configProvider maps chatgpt oauth aliases back to openai`() {
        assertEquals("openai", SlotAwareAgentConfig.configProvider("openai-codex"))
        assertEquals("openai", SlotAwareAgentConfig.configProvider("chatgpt"))
        assertEquals("openai", SlotAwareAgentConfig.configProvider("codex"))
    }

    @Test
    fun `resolveEffectiveDefaults uses first enabled agent when none have usable credentials`() =
        runTest {
            val staleSettings =
                AppSettings(
                    defaultProvider = "ollama",
                    defaultModel = "ollama/llama3",
                )
            val agents =
                listOf(
                    Agent(
                        id = "ollama",
                        slotId = "ollama",
                        name = "Ollama",
                        provider = "ollama",
                        modelName = "llama3",
                        isEnabled = false,
                    ),
                    Agent(
                        id = "openai-api",
                        slotId = "openai-api",
                        name = "OpenAI",
                        provider = "openai",
                        modelName = "gpt-4o",
                        isEnabled = true,
                    ),
                )

            val resolved =
                SlotAwareAgentConfig.resolveEffectiveDefaults(staleSettings, agents) { false }

            assertEquals("openai", resolved.defaultProvider)
            assertEquals("gpt-4o", resolved.defaultModel)
        }
}
