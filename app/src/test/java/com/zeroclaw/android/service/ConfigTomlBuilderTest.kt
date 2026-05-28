/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import com.zeroclaw.android.model.Agent
import com.zeroclaw.android.model.ChannelType
import com.zeroclaw.android.model.ConnectedChannel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for [ConfigTomlBuilder].
 *
 * Verifies TOML generation for cloud providers, self-hosted endpoints,
 * Ollama variants, Anthropic-compatible endpoints, and edge cases.
 */
@DisplayName("ConfigTomlBuilder")
class ConfigTomlBuilderTest {
    @Nested
    @DisplayName("build()")
    inner class Build {
        @Test
        @DisplayName("cloud provider produces TOML with provider, key, and temperature")
        fun `cloud provider produces correct TOML`() {
            val toml =
                ConfigTomlBuilder.build(
                    provider = "openai",
                    model = "gpt-4o",
                    apiKey = "sk-test-key-123",
                    baseUrl = "",
                )

            assertTrue(toml.contains("default_temperature = 0.7"))
            assertTrue(toml.contains("""default_provider = "openai""""))
            assertTrue(toml.contains("""default_model = "gpt-4o""""))
            assertTrue(toml.contains("""api_key = "sk-test-key-123""""))
        }

        @Test
        @DisplayName("Ollama with default URL uses plain ollama provider")
        fun `ollama default uses plain provider`() {
            val toml =
                ConfigTomlBuilder.build(
                    provider = "ollama",
                    model = "llama3",
                    apiKey = "",
                    baseUrl = "",
                )

            assertTrue(toml.contains("""default_provider = "ollama""""))
            assertFalse(toml.contains("custom:"))
        }

        @Test
        @DisplayName("Ollama with default localhost URL uses plain ollama provider")
        fun `ollama with default localhost URL uses plain provider`() {
            val toml =
                ConfigTomlBuilder.build(
                    provider = "ollama",
                    model = "llama3",
                    apiKey = "",
                    baseUrl = "http://localhost:11434",
                )

            assertTrue(toml.contains("""default_provider = "ollama""""))
            assertFalse(toml.contains("custom:"))
        }

        @Test
        @DisplayName("Ollama with custom URL emits V1 form for upstream migration")
        fun `ollama with custom URL emits V1 form`() {
            val toml =
                ConfigTomlBuilder.build(
                    provider = "ollama",
                    model = "mistral",
                    apiKey = "",
                    baseUrl = "http://192.168.1.100:11434/v1",
                )

            assertTrue(toml.contains("""default_provider = "custom""""))
            assertTrue(toml.contains("""default_model = "mistral""""))
            assertTrue(toml.contains("""api_url = "http://192.168.1.100:11434/v1""""))
            assertFalse(toml.contains("[providers.models."))
        }

        @Test
        @DisplayName("empty provider and model omits those fields")
        fun `empty provider and model omits fields`() {
            val toml =
                ConfigTomlBuilder.build(
                    provider = "",
                    model = "",
                    apiKey = "",
                    baseUrl = "",
                )

            assertTrue(toml.contains("default_temperature = 0.7"))
            assertFalse(toml.contains("default_provider"))
            assertFalse(toml.contains("default_model"))
            assertFalse(toml.contains("api_key"))
        }

        @Test
        @DisplayName("special characters in API key are escaped")
        fun `special characters in api key are escaped`() {
            val toml =
                ConfigTomlBuilder.build(
                    provider = "openai",
                    model = "gpt-4o",
                    apiKey = "sk-key\"with\\special\nnewline",
                    baseUrl = "",
                )

            assertTrue(toml.contains("""api_key = "sk-key\"with\\special\nnewline""""))
            assertFalse(toml.contains("\n\""))
        }

        @Test
        @DisplayName("temperature is always present")
        fun `temperature is always present`() {
            val toml =
                ConfigTomlBuilder.build(
                    provider = "",
                    model = "",
                    apiKey = "",
                    baseUrl = "",
                )

            assertTrue(toml.startsWith("default_temperature = 0.7"))
        }
    }

    @Nested
    @DisplayName("build(GlobalTomlConfig)")
    inner class BuildGlobalConfig {
        @Test
        @DisplayName("custom temperature is emitted")
        fun `custom temperature is emitted`() {
            val toml =
                ConfigTomlBuilder.build(
                    GlobalTomlConfig(
                        provider = "openai",
                        model = "gpt-4o",
                        apiKey = "sk-test",
                        baseUrl = "",
                        temperature = 1.2f,
                    ),
                )
            assertTrue(toml.contains("default_temperature = 1.2"))
            assertFalse(toml.contains("default_temperature = 0.7"))
        }

        @Test
        @DisplayName("auto reasoning effort omits runtime section")
        fun `auto reasoning effort omits runtime section`() {
            val toml =
                ConfigTomlBuilder.build(
                    GlobalTomlConfig(
                        provider = "openai",
                        model = "gpt-5.4",
                        apiKey = "sk-test",
                        baseUrl = "",
                    ),
                )

            assertFalse(toml.contains("[runtime]"))
            assertFalse(toml.contains("reasoning_effort"))
        }

        @Test
        @DisplayName("explicit reasoning effort emits runtime section")
        fun `explicit reasoning effort emits runtime section`() {
            val toml =
                ConfigTomlBuilder.build(
                    GlobalTomlConfig(
                        provider = "openai",
                        model = "gpt-5.4",
                        apiKey = "sk-test",
                        baseUrl = "",
                        reasoningEffort = "xhigh",
                    ),
                )

            assertTrue(toml.contains("[runtime]"))
            assertTrue(toml.contains("reasoning_effort = \"xhigh\""))
        }

        @Test
        @DisplayName("compact context enabled emits agent section")
        fun `compact context enabled emits agent section`() {
            val toml =
                ConfigTomlBuilder.build(
                    GlobalTomlConfig(
                        provider = "",
                        model = "",
                        apiKey = "",
                        baseUrl = "",
                        compactContext = true,
                    ),
                )
            assertTrue(toml.contains("[agent]"))
            assertTrue(toml.contains("compact_context = true"))
        }

        @Test
        @DisplayName("compact context disabled omits agent section")
        fun `compact context disabled omits agent section`() {
            val toml =
                ConfigTomlBuilder.build(
                    GlobalTomlConfig(
                        provider = "",
                        model = "",
                        apiKey = "",
                        baseUrl = "",
                        compactContext = false,
                    ),
                )
            assertFalse(toml.contains("[agent]"))
        }

        @Test
        @DisplayName("cost enabled emits cost section")
        fun `cost enabled emits cost section`() {
            val toml =
                ConfigTomlBuilder.build(
                    GlobalTomlConfig(
                        provider = "",
                        model = "",
                        apiKey = "",
                        baseUrl = "",
                        costEnabled = true,
                        dailyLimitUsd = 5.0,
                        monthlyLimitUsd = 50.0,
                        costWarnAtPercent = 75,
                    ),
                )
            assertTrue(toml.contains("[cost]"))
            assertTrue(toml.contains("enabled = true"))
            assertTrue(toml.contains("daily_limit_usd = 5.0"))
            assertTrue(toml.contains("monthly_limit_usd = 50.0"))
            assertTrue(toml.contains("warn_at_percent = 75"))
        }

        @Test
        @DisplayName("cost disabled omits cost section")
        fun `cost disabled omits cost section`() {
            val toml =
                ConfigTomlBuilder.build(
                    GlobalTomlConfig(
                        provider = "",
                        model = "",
                        apiKey = "",
                        baseUrl = "",
                        costEnabled = false,
                    ),
                )
            assertFalse(toml.contains("[cost]"))
        }

        @Test
        @DisplayName("identity JSON emits identity section")
        fun `identity JSON emits identity section`() {
            val json = """{"name":"TestBot"}"""
            val toml =
                ConfigTomlBuilder.build(
                    GlobalTomlConfig(
                        provider = "",
                        model = "",
                        apiKey = "",
                        baseUrl = "",
                        identityJson = json,
                    ),
                )
            assertTrue(toml.contains("[identity]"))
            assertTrue(toml.contains("""format = "aieos""""))
            assertTrue(toml.contains("aieos_inline"))
        }

        @Test
        @DisplayName("blank identity JSON omits identity section")
        fun `blank identity JSON omits identity section`() {
            val toml =
                ConfigTomlBuilder.build(
                    GlobalTomlConfig(
                        provider = "",
                        model = "",
                        apiKey = "",
                        baseUrl = "",
                        identityJson = "",
                    ),
                )
            assertFalse(toml.contains("[identity]"))
        }

        @Test
        @DisplayName("memory backend is always emitted")
        fun `memory backend is always emitted`() {
            val toml =
                ConfigTomlBuilder.build(
                    GlobalTomlConfig(
                        provider = "",
                        model = "",
                        apiKey = "",
                        baseUrl = "",
                        memoryBackend = "lucid",
                    ),
                )
            assertTrue(toml.contains("[memory]"))
            assertTrue(toml.contains("""backend = "lucid""""))
        }

        @Test
        @DisplayName("memory auto_save defaults to true")
        fun `memory auto_save defaults to true`() {
            val toml =
                ConfigTomlBuilder.build(
                    GlobalTomlConfig(
                        provider = "",
                        model = "",
                        apiKey = "",
                        baseUrl = "",
                    ),
                )
            assertTrue(toml.contains("auto_save = true"))
        }

        @Test
        @DisplayName("memory auto_save false is emitted")
        fun `memory auto_save false is emitted`() {
            val toml =
                ConfigTomlBuilder.build(
                    GlobalTomlConfig(
                        provider = "",
                        model = "",
                        apiKey = "",
                        baseUrl = "",
                        memoryAutoSave = false,
                    ),
                )
            assertTrue(toml.contains("auto_save = false"))
        }

        @Test
        @DisplayName("non-default retries emit reliability section")
        fun `non-default retries emit reliability section`() {
            val toml =
                ConfigTomlBuilder.build(
                    GlobalTomlConfig(
                        provider = "",
                        model = "",
                        apiKey = "",
                        baseUrl = "",
                        providerRetries = 5,
                    ),
                )
            assertTrue(toml.contains("[reliability]"))
            assertTrue(toml.contains("provider_retries = 5"))
        }

        @Test
        @DisplayName("fallback providers emit reliability section")
        fun `fallback providers emit reliability section`() {
            val toml =
                ConfigTomlBuilder.build(
                    GlobalTomlConfig(
                        provider = "",
                        model = "",
                        apiKey = "",
                        baseUrl = "",
                        fallbackProviders = listOf("groq", "anthropic"),
                    ),
                )
            assertTrue(toml.contains("[reliability]"))
            assertTrue(toml.contains("""fallback_providers = ["groq", "anthropic"]"""))
        }

        @Test
        @DisplayName("default retries and no fallbacks omit reliability section")
        fun `default values omit reliability section`() {
            val toml =
                ConfigTomlBuilder.build(
                    GlobalTomlConfig(
                        provider = "",
                        model = "",
                        apiKey = "",
                        baseUrl = "",
                    ),
                )
            assertFalse(toml.contains("[reliability]"))
        }

        @Test
        @DisplayName("transcription is no longer emitted by ConfigTomlBuilder.build()")
        fun `transcription not emitted by ConfigTomlBuilder build`() {
            // Transcription UI + storage were removed in the Hub cleanup;
            // ConfigTomlBuilder no longer carries `[transcription]`.
            val toml =
                ConfigTomlBuilder.build(
                    GlobalTomlConfig(
                        provider = "",
                        model = "",
                        apiKey = "",
                        baseUrl = "",
                    ),
                )
            assertFalse(toml.contains("[transcription]"))
        }

        @Test
        @DisplayName("non-default multimodal emits multimodal section")
        fun `non-default multimodal emits multimodal section`() {
            val toml =
                ConfigTomlBuilder.build(
                    GlobalTomlConfig(
                        provider = "",
                        model = "",
                        apiKey = "",
                        baseUrl = "",
                        multimodalMaxImages = 8,
                        multimodalAllowRemoteFetch = true,
                    ),
                )
            assertTrue(toml.contains("[multimodal]"))
            assertTrue(toml.contains("max_images = 8"))
            assertTrue(toml.contains("allow_remote_fetch = true"))
        }

        @Test
        @DisplayName("default multimodal omits multimodal section")
        fun `default multimodal omits multimodal section`() {
            val toml =
                ConfigTomlBuilder.build(
                    GlobalTomlConfig(
                        provider = "",
                        model = "",
                        apiKey = "",
                        baseUrl = "",
                    ),
                )
            assertFalse(toml.contains("[multimodal]"))
        }

        @Test
        @DisplayName("proxy enabled emits proxy section")
        fun `proxy enabled emits proxy section`() {
            val toml =
                ConfigTomlBuilder.build(
                    GlobalTomlConfig(
                        provider = "",
                        model = "",
                        apiKey = "",
                        baseUrl = "",
                        proxyEnabled = true,
                        proxyHttpProxy = "http://proxy:8080",
                        proxyNoProxy = listOf("localhost", "127.0.0.1"),
                    ),
                )
            assertTrue(toml.contains("[proxy]"))
            assertTrue(toml.contains("enabled = true"))
            assertTrue(toml.contains("""http_proxy = "http://proxy:8080""""))
            assertTrue(toml.contains(""""localhost""""))
            assertTrue(toml.contains(""""127.0.0.1""""))
        }

        @Test
        @DisplayName("proxy disabled omits proxy section")
        fun `proxy disabled omits proxy section`() {
            val toml =
                ConfigTomlBuilder.build(
                    GlobalTomlConfig(
                        provider = "",
                        model = "",
                        apiKey = "",
                        baseUrl = "",
                        proxyEnabled = false,
                    ),
                )
            assertFalse(toml.contains("[proxy]"))
        }

        @Test
        @DisplayName("web fetch section emits enabled, domains, max size, and timeout")
        fun `web fetch section emits enabled, domains, max size, and timeout`() {
            val toml =
                ConfigTomlBuilder.build(
                    GlobalTomlConfig(
                        provider = "",
                        model = "",
                        apiKey = "",
                        baseUrl = "",
                        webFetchEnabled = true,
                        webFetchAllowedDomains = listOf("example.com", "api.test.io"),
                        webFetchMaxResponseSize = 250_000L,
                        webFetchTimeoutSecs = 15L,
                    ),
                )
            assertTrue(toml.contains("[web_fetch]"))
            assertTrue(toml.contains("enabled = true"))
            assertTrue(toml.contains("allowed_domains"))
            assertTrue(toml.contains(""""example.com""""))
            assertTrue(toml.contains(""""api.test.io""""))
            assertTrue(toml.contains("max_response_size = 250000"))
            assertTrue(toml.contains("timeout_secs = 15"))
        }

        @Test
        @DisplayName("web fetch with empty domains emits wildcard allowlist")
        fun `web fetch with empty domains emits wildcard allowlist`() {
            val toml =
                ConfigTomlBuilder.build(
                    GlobalTomlConfig(
                        provider = "",
                        model = "",
                        apiKey = "",
                        baseUrl = "",
                        webFetchEnabled = true,
                    ),
                )
            assertTrue(toml.contains("[web_fetch]"))
            assertTrue(toml.contains("enabled = true"))
            assertTrue(toml.contains("""allowed_domains = ["*"]"""))
        }

        @Test
        @DisplayName("web fetch disabled omits web_fetch section")
        fun `web fetch disabled omits web_fetch section`() {
            val toml =
                ConfigTomlBuilder.build(
                    GlobalTomlConfig(
                        provider = "",
                        model = "",
                        apiKey = "",
                        baseUrl = "",
                        webFetchEnabled = false,
                    ),
                )
            assertFalse(toml.contains("[web_fetch]"))
        }

        @Test
        @DisplayName("http request section emits enabled, domains, max size, and timeout")
        fun `http request section emits enabled, domains, max size, and timeout`() {
            val toml =
                ConfigTomlBuilder.build(
                    GlobalTomlConfig(
                        provider = "",
                        model = "",
                        apiKey = "",
                        baseUrl = "",
                        httpRequestEnabled = true,
                        httpRequestAllowedDomains = listOf("api.example.com"),
                        httpRequestMaxResponseSize = 2_000_000L,
                        httpRequestTimeoutSecs = 60L,
                    ),
                )
            assertTrue(toml.contains("[http_request]"))
            assertTrue(toml.contains("enabled = true"))
            assertTrue(toml.contains("allowed_domains"))
            assertTrue(toml.contains(""""api.example.com""""))
            assertTrue(toml.contains("max_response_size = 2000000"))
            assertTrue(toml.contains("timeout_secs = 60"))
        }

        @Test
        @DisplayName("http request disabled omits http_request section")
        fun `http request disabled omits http_request section`() {
            val toml =
                ConfigTomlBuilder.build(
                    GlobalTomlConfig(
                        provider = "",
                        model = "",
                        apiKey = "",
                        baseUrl = "",
                        httpRequestEnabled = false,
                    ),
                )
            assertTrue(toml.contains("[http_request]"))
            assertTrue(toml.contains("enabled = false"))
        }

        @Test
        @DisplayName("twitter browse section is no longer emitted by ConfigTomlBuilder.build()")
        fun `twitter browse not emitted by ConfigTomlBuilder build`() {
            // Migrated to `features/TwitterContributor.kt`: the
            // `[twitter_browse]` TOML section is now produced by the
            // FeatureContributor pipeline at daemon-start, not by
            // ConfigTomlBuilder.build(). This test pins the behaviour
            // shift so the old emitter never silently reappears.
            val toml =
                ConfigTomlBuilder.build(
                    GlobalTomlConfig(
                        provider = "",
                        model = "",
                        apiKey = "",
                        baseUrl = "",
                        twitterBrowseEnabled = true,
                        twitterBrowseMaxItems = 25L,
                        twitterBrowseTimeoutSecs = 45L,
                    ),
                )

            assertFalse(toml.contains("[twitter_browse]"))
        }

        @Test
        @DisplayName("skills section emits only prompt_injection_mode")
        fun `skills section emits only prompt injection mode`() {
            val toml =
                ConfigTomlBuilder.build(
                    GlobalTomlConfig(
                        provider = "",
                        model = "",
                        apiKey = "",
                        baseUrl = "",
                        skillsPromptInjectionMode = "compact",
                    ),
                )
            assertTrue(toml.contains("[skills]"))
            assertTrue(toml.contains("""prompt_injection_mode = "compact""""))
        }

        @Test
        @DisplayName("skills section omitted when all defaults")
        fun `skills section omitted when all defaults`() {
            val toml =
                ConfigTomlBuilder.build(
                    GlobalTomlConfig(
                        provider = "",
                        model = "",
                        apiKey = "",
                        baseUrl = "",
                        skillsPromptInjectionMode = "full",
                    ),
                )
            assertFalse(toml.contains("[skills]"))
        }

        @Test
        @DisplayName("autonomy section omits non_cli_excluded_tools (upstream default)")
        fun `autonomy section omits non cli excluded tools`() {
            val toml =
                ConfigTomlBuilder.build(
                    GlobalTomlConfig(
                        provider = "",
                        model = "",
                        apiKey = "",
                        baseUrl = "",
                    ),
                )
            assertFalse(toml.contains("non_cli_excluded_tools"))
        }
    }

    @Nested
    @DisplayName("buildAgentsToml()")
    inner class BuildAgentsToml {
        @Test
        @DisplayName("empty list returns empty string")
        fun `empty list returns empty string`() {
            assertEquals("", ConfigTomlBuilder.buildAgentsToml(emptyList()))
        }

        @Test
        @DisplayName("agent emits a model_provider reference, not inline brain")
        fun `agent emits model_provider reference`() {
            val entries =
                listOf(
                    AgentTomlEntry(
                        name = "researcher",
                        modelProviderRef = "custom.default",
                    ),
                )
            val toml = ConfigTomlBuilder.buildAgentsToml(entries)

            assertTrue(toml.contains("[agents.researcher]"))
            assertTrue(toml.contains("""model_provider = "custom.default""""))
            // No inline brain — these all live on the referenced provider
            // entry. Anchor each check to the start of a line so the
            // substring of "model_provider = " doesn't false-positive
            // for "provider = ".
            assertFalse(Regex("""^\s*provider\s*=""", RegexOption.MULTILINE).containsMatchIn(toml))
            assertFalse(Regex("""^\s*model\s*=""", RegexOption.MULTILINE).containsMatchIn(toml))
            assertFalse(toml.contains("api_key"))
            assertFalse(toml.contains("system_prompt"))
            assertFalse(toml.contains("temperature"))
        }

        @Test
        @DisplayName("blank modelProviderRef omits the field entirely")
        fun `blank modelProviderRef omits the field`() {
            val entries =
                listOf(AgentTomlEntry(name = "fallback", modelProviderRef = ""))
            val toml = ConfigTomlBuilder.buildAgentsToml(entries)
            assertTrue(toml.contains("[agents.fallback]"))
            assertFalse(toml.contains("model_provider"))
        }

        @Test
        @DisplayName("multiple agents produce separate sections")
        fun `multiple agents produce separate sections`() {
            val entries =
                listOf(
                    AgentTomlEntry(name = "agent_a", modelProviderRef = "openai.default"),
                    AgentTomlEntry(name = "agent_b", modelProviderRef = "anthropic.default"),
                )
            val toml = ConfigTomlBuilder.buildAgentsToml(entries)

            assertTrue(toml.contains("[agents.agent_a]"))
            assertTrue(toml.contains("[agents.agent_b]"))
            assertTrue(toml.contains("""model_provider = "openai.default""""))
            assertTrue(toml.contains("""model_provider = "anthropic.default""""))
        }

        @Test
        @DisplayName("agent name with spaces is quoted in table header")
        fun `agent name with spaces is quoted in table header`() {
            val entries =
                listOf(
                    AgentTomlEntry(name = "My Agent", modelProviderRef = "custom.default"),
                )
            val toml = ConfigTomlBuilder.buildAgentsToml(entries)

            assertTrue(toml.contains("""[agents."My Agent"]"""))
            assertFalse(toml.contains("[agents.My Agent]"))
        }

        @Test
        @DisplayName("agent name without special characters is bare key")
        fun `agent name without special characters is bare key`() {
            val entries =
                listOf(
                    AgentTomlEntry(name = "my-agent_1", modelProviderRef = "openai.default"),
                )
            val toml = ConfigTomlBuilder.buildAgentsToml(entries)
            assertTrue(toml.contains("[agents.my-agent_1]"))
        }

        @Test
        @DisplayName("agent with non-default maxDepth emits max_depth field")
        fun `agent with non-default maxDepth emits max_depth field`() {
            val entries =
                listOf(
                    AgentTomlEntry(
                        name = "deep",
                        modelProviderRef = "openai.default",
                        maxDepth = 7,
                    ),
                )
            val toml = ConfigTomlBuilder.buildAgentsToml(entries)
            assertTrue(toml.contains("max_depth = 7"))
        }

        @Test
        @DisplayName("agent with default maxDepth omits max_depth field")
        fun `agent with default maxDepth omits max_depth field`() {
            val entries =
                listOf(
                    AgentTomlEntry(
                        name = "shallow",
                        modelProviderRef = "openai.default",
                        maxDepth = Agent.DEFAULT_MAX_DEPTH,
                    ),
                )
            val toml = ConfigTomlBuilder.buildAgentsToml(entries)
            assertFalse(toml.contains("max_depth"))
        }

        @Test
        @DisplayName("channels list emits as quoted dotted refs")
        fun `channels list emits as quoted dotted refs`() {
            val entries =
                listOf(
                    AgentTomlEntry(
                        name = "primary",
                        modelProviderRef = "custom.default",
                        channels = listOf("telegram.default", "discord.default"),
                    ),
                )
            val toml = ConfigTomlBuilder.buildAgentsToml(entries)
            assertTrue(
                toml.contains(
                    """channels = ["telegram.default", "discord.default"]""",
                ),
            )
        }
    }

    @Nested
    @DisplayName("resolveProvider()")
    inner class ResolveProvider {
        @Test
        @DisplayName("blank provider returns blank")
        fun `blank provider returns blank`() {
            assertEquals("", ConfigTomlBuilder.resolveProvider("", ""))
            assertEquals("", ConfigTomlBuilder.resolveProvider("  ", ""))
        }

        @Test
        @DisplayName("cloud provider passes through unchanged")
        fun `cloud provider passes through`() {
            assertEquals("openai", ConfigTomlBuilder.resolveProvider("openai", ""))
            assertEquals("anthropic", ConfigTomlBuilder.resolveProvider("anthropic", ""))
            assertEquals("openrouter", ConfigTomlBuilder.resolveProvider("openrouter", ""))
        }

        @Test
        @DisplayName("custom-openai with URL resolves to legacy custom:URL form")
        fun `custom-openai with URL resolves to legacy form`() {
            assertEquals(
                "custom:http://my-server:9090/v1",
                ConfigTomlBuilder.resolveProvider("custom-openai", "http://my-server:9090/v1"),
            )
        }

        @Test
        @DisplayName("custom-openai without URL passes through")
        fun `custom-openai without URL passes through`() {
            assertEquals("custom-openai", ConfigTomlBuilder.resolveProvider("custom-openai", ""))
        }
    }

    @Nested
    @DisplayName("buildChannelsToml()")
    inner class BuildChannelsToml {
        @Test
        @DisplayName("empty list returns empty string")
        fun `empty list returns empty string`() {
            assertEquals("", ConfigTomlBuilder.buildChannelsToml(emptyList()))
        }

        @Test
        @DisplayName("Telegram channel emits correct TOML section")
        fun `telegram channel emits correct TOML`() {
            val channel = ConnectedChannel(id = "1", type = ChannelType.TELEGRAM)
            val values =
                mapOf(
                    "bot_token" to "123456:ABC-DEF",
                    "allowed_users" to "user1, user2",
                )
            val toml = ConfigTomlBuilder.buildChannelsToml(listOf(channel to values))

            assertTrue(toml.contains("[channels_config.telegram]"))
            assertTrue(toml.contains("""bot_token = "123456:ABC-DEF""""))
            assertTrue(toml.contains(""""user1""""))
            assertTrue(toml.contains(""""user2""""))
        }

        @Test
        @DisplayName("Discord channel emits correct TOML section")
        fun `discord channel emits correct TOML`() {
            val channel = ConnectedChannel(id = "2", type = ChannelType.DISCORD)
            val values = mapOf("bot_token" to "discord-token-123")
            val toml =
                ConfigTomlBuilder.buildChannelsToml(
                    listOf(channel to values),
                    discordGuildId = "guild123",
                )

            assertTrue(toml.contains("[channels_config.discord]"))
            assertTrue(toml.contains("""bot_token = "discord-token-123""""))
            assertTrue(toml.contains("""guild_id = "guild123""""))
        }

        @Test
        @DisplayName("all channel types have unique toml keys")
        fun `all channel types have unique toml keys`() {
            val keys = ChannelType.entries.map { it.tomlKey }
            assertEquals(keys.size, keys.toSet().size)
        }

        @Test
        @DisplayName("all channel types have non-empty display names")
        fun `all channel types have non-empty display names`() {
            for (type in ChannelType.entries) {
                assertTrue(type.displayName.isNotBlank(), "${type.name} has blank displayName")
            }
        }
    }
}
