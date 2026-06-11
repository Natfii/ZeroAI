/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.service

import android.content.Context
import com.zeroclaw.android.model.ApiKey
import com.zeroclaw.android.model.AppSettings
import io.mockk.every
import io.mockk.mockk
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for [DaemonGlobalConfigMapper].
 *
 * Exercises the context-free core mapping with a fully populated
 * [AppSettings] so any field dropped from the mapper (the drift that
 * previously plagued the `DoctorViewModel` duplicate) fails loudly.
 */
@DisplayName("DaemonGlobalConfigMapper")
class DaemonGlobalConfigMapperTest {
    private val securityFilePaths =
        DaemonGlobalConfigMapper.SecurityFilePaths(
            estopStateFile = "/data/user/0/com.zeroclaw.android/files/estop-state.json",
            auditLogPath = "/data/user/0/com.zeroclaw.android/files/audit.log",
        )

    private val apiKey =
        ApiKey(
            id = "key-1",
            provider = "openai",
            key = "sk-stored-key",
            baseUrl = "https://api.example.com/v1",
        )

    private fun mapFullyPopulatedSettings(): GlobalTomlConfig =
        DaemonGlobalConfigMapper.toGlobalTomlConfig(
            settings = fullyPopulatedSettings(),
            apiKey = apiKey,
            apiKeyValue = "sk-resolved-key",
            hubAppContext = "## Hub apps\nTelegram is connected.",
            securityFilePaths = securityFilePaths,
        )

    @Nested
    @DisplayName("context-free toGlobalTomlConfig()")
    inner class ContextFreeMapping {
        @Test
        @DisplayName("maps provider, model, and caller-resolved key value")
        fun `provider model and key fields map`() {
            val config = mapFullyPopulatedSettings()

            assertEquals("openai", config.provider)
            assertEquals("gpt-4o-mini", config.model)
            assertEquals("sk-resolved-key", config.apiKey)
            assertEquals("https://api.example.com/v1", config.baseUrl)
            assertEquals(1.2f, config.temperature)
            assertEquals("high", config.reasoningEffort)
            assertEquals(true, config.compactContext)
        }

        @Test
        @DisplayName("maps all eight webSearch fields")
        fun `web search fields map`() {
            val config = mapFullyPopulatedSettings()

            assertEquals(true, config.webSearchEnabled)
            assertEquals("searxng", config.webSearchProvider)
            assertEquals("brave-key", config.webSearchBraveApiKey)
            assertEquals("tavily-key", config.webSearchTavilyApiKey)
            assertEquals("https://searx.example.com", config.webSearchSearxngUrl)
            assertEquals(8L, config.webSearchMaxResults)
            assertEquals(25L, config.webSearchTimeoutSecs)
            assertEquals(30L, config.webSearchRequestsPerMinute)
        }

        @Test
        @DisplayName("maps the fields the old DoctorViewModel duplicate dropped")
        fun `previously drifted fields map`() {
            val config = mapFullyPopulatedSettings()

            assertEquals(true, config.sharedFolderEnabled)
            assertEquals(securityFilePaths.estopStateFile, config.securityEstopStateFile)
            assertEquals(securityFilePaths.auditLogPath, config.securityAuditLogPath)
            assertEquals("full", config.skillsPromptInjectionMode)
            assertEquals(2_000_000L, config.httpRequestMaxResponseSize)
            assertEquals(45L, config.httpRequestTimeoutSecs)
            assertEquals(true, config.twitterBrowseEnabled)
            assertEquals(35L, config.twitterBrowseMaxItems)
            assertEquals(40L, config.twitterBrowseTimeoutSecs)
            assertEquals(8, config.multimodalMaxImages)
            assertEquals(12, config.multimodalMaxImageSizeMb)
            assertEquals(true, config.multimodalAllowRemoteFetch)
            assertEquals("## Hub apps\nTelegram is connected.", config.hubAppContext)
        }

        @Test
        @DisplayName("splits every comma-separated settings field into lists")
        fun `csv fields are split`() {
            val config = mapFullyPopulatedSettings()

            assertEquals(listOf("openrouter", "gemini"), config.fallbackProviders)
            assertEquals(listOf("git", "ls"), config.allowedCommands)
            assertEquals(listOf("/etc", "/root"), config.forbiddenPaths)
            assertEquals(listOf("tok1", "tok2"), config.gatewayPairedTokens)
            assertEquals(
                listOf("example.com", "docs.example.com"),
                config.browserAllowedDomains,
            )
            assertEquals(listOf("api.example.com"), config.httpRequestAllowedDomains)
            assertEquals(listOf("fetch.example.com"), config.webFetchAllowedDomains)
            assertEquals(listOf("blocked.example.com"), config.webFetchBlockedDomains)
            assertEquals(
                listOf("--net=none", "--private"),
                config.securitySandboxFirejailArgs,
            )
            assertEquals(listOf("localhost", "127.0.0.1"), config.proxyNoProxy)
            assertEquals(listOf("gateway", "channels"), config.proxyServiceSelectors)
        }

        @Test
        @DisplayName("maps gateway, scheduler, memory, and security scalars")
        fun `remaining scalar fields map`() {
            val config = mapFullyPopulatedSettings()

            assertEquals("0.0.0.0", config.gatewayHost)
            assertEquals(9999, config.gatewayPort)
            assertEquals(true, config.gatewayRequirePairing)
            assertEquals(600L, config.gatewayIdempotencyTtl)
            assertEquals(false, config.schedulerEnabled)
            assertEquals(16L, config.schedulerMaxTasks)
            assertEquals(45L, config.heartbeatIntervalMinutes)
            assertEquals("markdown", config.memoryBackend)
            assertEquals(0.6, config.memoryVectorWeight, FLOAT_TOLERANCE)
            assertEquals(0.4, config.memoryKeywordWeight, FLOAT_TOLERANCE)
            assertEquals(true, config.securitySandboxEnabled)
            assertEquals("landlock", config.securitySandboxBackend)
            assertEquals(true, config.securityAuditEnabled)
            assertEquals(true, config.securityEstopEnabled)
            assertEquals(false, config.securityEstopRequireOtpToResume)
            assertEquals(750L, config.reliabilityBackoffMs)
            assertEquals("""{"openai":"k"}""", config.reliabilityApiKeysJson)
        }

        @Test
        @DisplayName("null api key and null hub context map to empty and null")
        fun `null inputs map cleanly`() {
            val config =
                DaemonGlobalConfigMapper.toGlobalTomlConfig(
                    settings = AppSettings(),
                    apiKey = null,
                    apiKeyValue = "",
                    hubAppContext = null,
                    securityFilePaths = securityFilePaths,
                )

            assertEquals("", config.apiKey)
            assertEquals("", config.baseUrl)
            assertNull(config.hubAppContext)
        }
    }

    @Nested
    @DisplayName("splitCsv()")
    inner class SplitCsv {
        @Test
        @DisplayName("trims entries and drops blanks")
        fun `trims and drops blanks`() {
            assertEquals(
                listOf("alpha", "beta"),
                DaemonGlobalConfigMapper.splitCsv(" alpha , , beta ,"),
            )
        }

        @Test
        @DisplayName("empty input gives empty list")
        fun `empty input gives empty list`() {
            assertEquals(emptyList<String>(), DaemonGlobalConfigMapper.splitCsv(""))
        }
    }

    @Nested
    @DisplayName("SecurityFilePaths.fromContext()")
    inner class SecurityFilePathsFromContext {
        @Test
        @DisplayName("derives both paths under filesDir")
        fun `derives paths under filesDir`() {
            val filesDir = mockk<File>()
            every { filesDir.absolutePath } returns "/data/user/0/com.zeroclaw.android/files"
            val context = mockk<Context>()
            every { context.filesDir } returns filesDir

            val paths = DaemonGlobalConfigMapper.SecurityFilePaths.fromContext(context)

            assertEquals(
                "/data/user/0/com.zeroclaw.android/files/estop-state.json",
                paths.estopStateFile,
            )
            assertEquals(
                "/data/user/0/com.zeroclaw.android/files/audit.log",
                paths.auditLogPath,
            )
        }
    }

    @Suppress("LongMethod")
    private fun fullyPopulatedSettings(): AppSettings =
        AppSettings(
            host = "0.0.0.0",
            port = 9999,
            defaultProvider = "openai",
            defaultModel = "gpt-4o-mini",
            defaultTemperature = 1.2f,
            reasoningEffort = "high",
            compactContext = true,
            costEnabled = true,
            dailyLimitUsd = 12.5f,
            monthlyLimitUsd = 200f,
            costWarnAtPercent = 75,
            providerRetries = 4,
            fallbackProviders = "openrouter, gemini",
            memoryBackend = "markdown",
            memoryAutoSave = false,
            identityJson = """{"name":"Zero"}""",
            autonomyLevel = "full",
            workspaceOnly = false,
            allowedCommands = "git, ls",
            forbiddenPaths = "/etc, /root",
            maxActionsPerHour = 33,
            maxCostPerDayCents = 444,
            requireApprovalMediumRisk = false,
            blockHighRiskCommands = false,
            tunnelProvider = "tailscale",
            tunnelTailscaleFunnel = true,
            tunnelTailscaleHostname = "zero-phone",
            gatewayRequirePairing = true,
            gatewayAllowPublicBind = true,
            gatewayPairedTokens = "tok1, tok2",
            gatewayPairRateLimit = 7,
            gatewayWebhookRateLimit = 70,
            gatewayIdempotencyTtl = 600L,
            schedulerEnabled = false,
            schedulerMaxTasks = 16L,
            schedulerMaxConcurrent = 2L,
            heartbeatEnabled = true,
            heartbeatIntervalMinutes = 45L,
            observabilityBackend = "otel",
            observabilityOtelEndpoint = "http://collector:4318",
            observabilityOtelServiceName = "zero-test",
            memoryHygieneEnabled = false,
            memoryArchiveAfterDays = 14,
            memoryPurgeAfterDays = 60,
            memoryEmbeddingProvider = "openai",
            memoryEmbeddingModel = "text-embedding-3-large",
            memoryVectorWeight = 0.6f,
            memoryKeywordWeight = 0.4f,
            composioEnabled = true,
            composioApiKey = "composio-key",
            composioEntityId = "entity-7",
            sharedFolderEnabled = true,
            browserEnabled = true,
            browserAllowedDomains = "example.com, docs.example.com",
            httpRequestEnabled = false,
            httpRequestAllowedDomains = "api.example.com",
            httpRequestMaxResponseSize = 2_000_000L,
            httpRequestTimeoutSecs = 45L,
            webFetchEnabled = true,
            webFetchAllowedDomains = "fetch.example.com",
            webFetchBlockedDomains = "blocked.example.com",
            webFetchMaxResponseSize = 750_000L,
            webFetchTimeoutSecs = 20L,
            webSearchEnabled = true,
            webSearchProvider = "searxng",
            webSearchBraveApiKey = "brave-key",
            webSearchTavilyApiKey = "tavily-key",
            webSearchSearxngUrl = "https://searx.example.com",
            webSearchMaxResults = 8L,
            webSearchTimeoutSecs = 25L,
            webSearchRequestsPerMinute = 30L,
            twitterBrowseEnabled = true,
            twitterBrowseMaxItems = 35L,
            twitterBrowseTimeoutSecs = 40L,
            multimodalMaxImages = 8,
            multimodalMaxImageSizeMb = 12,
            multimodalAllowRemoteFetch = true,
            securitySandboxEnabled = true,
            securitySandboxBackend = "landlock",
            securitySandboxFirejailArgs = "--net=none, --private",
            securityResourcesMaxMemoryMb = 1024,
            securityResourcesMaxCpuTimeSecs = 120L,
            securityResourcesMaxSubprocesses = 20,
            securityResourcesMemoryMonitoring = false,
            securityAuditEnabled = true,
            securityEstopEnabled = true,
            securityEstopRequireOtpToResume = false,
            skillsPromptInjectionMode = "full",
            proxyEnabled = true,
            proxyHttpProxy = "http://proxy:8080",
            proxyHttpsProxy = "https://proxy:8443",
            proxyAllProxy = "socks5://proxy:1080",
            proxyNoProxy = "localhost, 127.0.0.1",
            proxyScope = "services",
            proxyServiceSelectors = "gateway, channels",
            reliabilityBackoffMs = 750L,
            reliabilityApiKeysJson = """{"openai":"k"}""",
        )

    private companion object {
        private const val FLOAT_TOLERANCE = 0.0001
    }
}
