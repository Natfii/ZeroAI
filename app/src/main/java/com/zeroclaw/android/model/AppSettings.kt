/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.model

/**
 * Persistent application settings covering all upstream TOML configuration sections.
 *
 * @property host Gateway bind address.
 * @property port Gateway bind port.
 * @property autoStartOnBoot Android-only. Controls boot receiver, not passed to daemon TOML.
 * @property defaultProvider Fallback daemon route provider when no enabled/configured slot
 *   supplies startup credentials (e.g. "openai").
 * @property defaultModel Fallback daemon route model paired with [defaultProvider] when
 *   slot-based startup routing is unavailable.
 * @property defaultTemperature Global inference temperature (0.0–2.0).
 * @property reasoningEffort Global reasoning-effort override. `"auto"` keeps model-specific
 *   defaults; explicit levels are currently used by OpenAI reasoning models.
 * @property compactContext Whether to enable compact context mode upstream.
 * @property costEnabled Whether spending limits are enforced.
 * @property dailyLimitUsd Maximum daily spend in USD.
 * @property monthlyLimitUsd Maximum monthly spend in USD.
 * @property costWarnAtPercent Percentage of limit at which to warn.
 * @property providerRetries Number of retries before falling back.
 * @property fallbackProviders Comma-separated list of fallback provider IDs.
 * @property memoryBackend Memory backend name ("sqlite", "none", "markdown", "lucid").
 * @property memoryAutoSave Whether the memory backend auto-saves conversation context.
 * @property identityJson AIEOS v1.1 identity JSON blob.
 * @property autonomyLevel Autonomy level: "readonly", "supervised", or "full".
 * @property workspaceOnly Whether to restrict file access to the workspace directory.
 * @property allowedCommands Comma-separated list of allowed shell commands.
 * @property forbiddenPaths Comma-separated list of forbidden filesystem paths.
 * @property maxActionsPerHour Maximum agent actions per hour.
 * @property maxCostPerDayCents Maximum daily cost in cents.
 * @property requireApprovalMediumRisk Whether medium-risk actions require user approval.
 * @property blockHighRiskCommands Whether to block high-risk shell commands entirely.
 * @property tunnelProvider Tunnel provider: "none" or "tailscale".
 * @property tunnelTailscaleFunnel Whether to enable Tailscale Funnel.
 * @property tunnelTailscaleHostname Custom Tailscale hostname.
 * @property gatewayRequirePairing Whether gateway requires pairing tokens. Defaults to false
 *   on Android (upstream defaults to true) because mobile devices are typically behind NAT and
 *   the gateway is only reachable via an explicit tunnel, reducing the risk of unauthorized access.
 * @property gatewayAllowPublicBind Whether to allow binding to 0.0.0.0.
 * @property gatewayPairedTokens Comma-separated list of authorized pairing tokens.
 * @property gatewayPairRateLimit Pairing rate limit per minute.
 * @property gatewayWebhookRateLimit Webhook rate limit per minute.
 * @property gatewayIdempotencyTtl Idempotency TTL in seconds.
 * @property schedulerEnabled Whether the task scheduler is active.
 * @property schedulerMaxTasks Maximum concurrent scheduler tasks.
 * @property schedulerMaxConcurrent Maximum concurrent task executions.
 * @property heartbeatEnabled Whether the heartbeat engine is active.
 * @property heartbeatIntervalMinutes Interval between heartbeat ticks.
 * @property observabilityBackend Observability backend: "none", "log", "otel".
 * @property observabilityOtelEndpoint OpenTelemetry collector endpoint.
 * @property observabilityOtelServiceName Service name for OTel traces.
 * @property modelRoutesJson JSON array of model route objects.
 * @property memoryHygieneEnabled Whether memory hygiene (archival/purge) is active.
 * @property memoryArchiveAfterDays Days before memory entries are archived.
 * @property memoryPurgeAfterDays Days before archived entries are purged.
 * @property memoryEmbeddingProvider Embedding provider: "none", "openai", or "custom:URL".
 * @property memoryEmbeddingModel Embedding model name.
 * @property memoryVectorWeight Weight for vector similarity in recall (0.0–1.0).
 * @property memoryKeywordWeight Weight for keyword matching in recall (0.0–1.0).
 * @property composioEnabled Whether Composio tool integration is active.
 * @property composioApiKey Composio API key.
 * @property composioEntityId Composio entity identifier.
 * @property sharedFolderEnabled Whether the shared folder tool plugin is enabled.
 * @property sharedFolderUri Persisted SAF URI for the user-chosen shared folder, or empty if not configured.
 * @property browserEnabled Whether the browser tool is enabled.
 * @property browserAllowedDomains Comma-separated list of allowed browser domains.
 * @property httpRequestEnabled Whether the HTTP request tool is enabled.
 * @property httpRequestAllowedDomains Comma-separated list of allowed HTTP domains.
 * @property httpRequestMaxResponseSize Maximum response body size in bytes for HTTP requests.
 * @property httpRequestTimeoutSecs Request timeout in seconds for HTTP requests.
 * @property webFetchEnabled Whether the web fetch tool is active.
 * @property webFetchAllowedDomains Comma-separated allowed domains for web fetch.
 * @property webFetchBlockedDomains Comma-separated blocked domains for web fetch.
 * @property webFetchMaxResponseSize Maximum response body size in bytes.
 * @property webFetchTimeoutSecs Request timeout in seconds for web fetch.
 * @property webSearchEnabled Whether the web search tool is active.
 * @property webSearchProvider Search provider: "auto", "brave", or "google".
 * @property webSearchBraveApiKey Brave Search API key (required when provider is "brave").
 * @property webSearchGoogleApiKey Google Custom Search API key (required when provider is "google").
 * @property webSearchGoogleCx Google Custom Search Engine ID (required when provider is "google").
 * @property webSearchMaxResults Maximum number of search results (1-10).
 * @property webSearchTimeoutSecs Request timeout in seconds for web search.
 * @property twitterBrowseEnabled Whether the Twitter/X browse tool is active.
 * @property twitterBrowseCookieString Cookie string containing authenticated `ct0` and `auth_token`.
 * @property twitterBrowseMaxItems Maximum timeline or search items returned per request (1-50).
 * @property twitterBrowseTimeoutSecs Request timeout in seconds for Twitter/X browsing.
 * @property transcriptionEnabled Whether audio transcription is active.
 * @property transcriptionApiUrl Transcription API endpoint URL.
 * @property transcriptionModel Transcription model name.
 * @property transcriptionLanguage ISO language code hint for transcription.
 * @property transcriptionMaxDurationSecs Maximum audio duration in seconds.
 * @property multimodalMaxImages Maximum images allowed per multimodal request (1-16).
 * @property multimodalMaxImageSizeMb Maximum image size in MB for multimodal input (1-20).
 * @property multimodalAllowRemoteFetch Whether the agent can fetch remote image URLs for vision.
 * @property securitySandboxEnabled Sandbox mode: null for auto-detect, true, or false.
 * @property securitySandboxBackend Sandbox backend: "auto", "landlock", "firejail", "bubblewrap", "docker", "none".
 * @property securitySandboxFirejailArgs Comma-separated extra arguments for firejail.
 * @property securityResourcesMaxMemoryMb Maximum memory in MB for spawned commands.
 * @property securityResourcesMaxCpuTimeSecs Maximum CPU time in seconds for spawned commands.
 * @property securityResourcesMaxSubprocesses Maximum number of subprocesses.
 * @property securityResourcesMemoryMonitoring Whether memory monitoring is active.
 * @property securityAuditEnabled Whether security audit logging is active.
 * @property securityOtpEnabled Whether OTP gating for sensitive actions is active.
 * @property securityOtpMethod OTP method: "totp", "pairing", or "cli-prompt".
 * @property securityOtpTokenTtlSecs OTP token time-to-live in seconds.
 * @property securityOtpCacheValidSecs Duration in seconds a verified OTP remains valid.
 * @property securityOtpGatedActions Comma-separated list of actions requiring OTP.
 * @property securityOtpGatedDomains Comma-separated list of domains requiring OTP.
 * @property securityOtpGatedDomainCategories Comma-separated domain categories requiring OTP.
 * @property securityEstopEnabled Whether the emergency stop feature is active.
 * @property securityEstopRequireOtpToResume Whether OTP is required to resume after e-stop.
 * @property memoryQdrantUrl Qdrant vector database URL.
 * @property memoryQdrantCollection Qdrant collection name.
 * @property memoryQdrantApiKey Qdrant API key.
 * @property embeddingRoutesJson JSON array of embedding route objects.
 * @property queryClassificationEnabled Whether automatic query classification is active.
 * @property skillsOpenSkillsEnabled Whether the open-skills community repository is enabled.
 * @property skillsOpenSkillsDir Custom directory for open-skills repository. Empty uses default.
 * @property skillsPromptInjectionMode Skill prompt injection mode: "full" or "compact".
 * @property proxyEnabled Whether proxy configuration is active.
 * @property proxyHttpProxy HTTP proxy URL.
 * @property proxyHttpsProxy HTTPS proxy URL.
 * @property proxyAllProxy Catch-all proxy URL for all protocols.
 * @property proxyNoProxy Comma-separated list of domains that bypass the proxy.
 * @property proxyScope Proxy scope: "environment", "zeroclaw", or "services".
 * @property proxyServiceSelectors Comma-separated service selectors for proxy routing.
 * @property reliabilityBackoffMs Provider retry backoff duration in milliseconds.
 * @property reliabilityApiKeysJson JSON object mapping provider names to API keys.
 * @property lockEnabled Android-only. Whether the session lock gate is active.
 * @property lockTimeoutMinutes Android-only. Minutes of background time before re-locking.
 * @property pinHash Android-only. PBKDF2 hash of the user's PIN (Base64-encoded salt+hash).
 * @property pluginRegistryUrl Android-only. Plugin registry preference, not passed to daemon TOML.
 * @property pluginSyncEnabled Android-only. Plugin registry preference, not passed to daemon TOML.
 * @property pluginSyncIntervalHours Android-only. Plugin registry preference, not passed to daemon TOML.
 * @property lastPluginSyncTimestamp Android-only. Plugin registry preference, not passed to daemon TOML.
 * @property stripThinkingTags Android-only. Client-side display filter, not passed to daemon TOML.
 * @property theme Android-only. UI theme preference, not passed to daemon TOML.
 * @property tailscaleAwarenessEnabled Whether Tailscale agent awareness is enabled.
 * @property tailscaleManualPeers JSON-serialized list of manually added peer IPs/hostnames.
 * @property tailscaleCachedDiscovery JSON-serialized last probe results (list of [CachedTailscalePeer]).
 * @property tailscaleLastScanTimestamp Unix timestamp (millis) of the last successful service scan.
 */
@Suppress("LongParameterList")
data class AppSettings(
    val host: String = DEFAULT_HOST,
    val port: Int = DEFAULT_PORT,
    val autoStartOnBoot: Boolean = false,
    val defaultProvider: String = "",
    val defaultModel: String = "",
    val defaultTemperature: Float = DEFAULT_TEMPERATURE,
    val reasoningEffort: String = DEFAULT_REASONING_EFFORT,
    val compactContext: Boolean = false,
    val costEnabled: Boolean = false,
    val dailyLimitUsd: Float = DEFAULT_DAILY_LIMIT_USD,
    val monthlyLimitUsd: Float = DEFAULT_MONTHLY_LIMIT_USD,
    val costWarnAtPercent: Int = DEFAULT_COST_WARN_PERCENT,
    val providerRetries: Int = DEFAULT_PROVIDER_RETRIES,
    val fallbackProviders: String = "",
    val memoryBackend: String = DEFAULT_MEMORY_BACKEND,
    val memoryAutoSave: Boolean = true,
    val identityJson: String = "",
    val autonomyLevel: String = DEFAULT_AUTONOMY_LEVEL,
    val workspaceOnly: Boolean = true,
    val allowedCommands: String = DEFAULT_ALLOWED_COMMANDS,
    val forbiddenPaths: String = DEFAULT_FORBIDDEN_PATHS,
    val maxActionsPerHour: Int = DEFAULT_MAX_ACTIONS_PER_HOUR,
    val maxCostPerDayCents: Int = DEFAULT_MAX_COST_PER_DAY_CENTS,
    val requireApprovalMediumRisk: Boolean = true,
    val blockHighRiskCommands: Boolean = true,
    val tunnelProvider: String = "none",
    val tunnelTailscaleFunnel: Boolean = false,
    val tunnelTailscaleHostname: String = "",
    val gatewayRequirePairing: Boolean = false,
    val gatewayAllowPublicBind: Boolean = false,
    val gatewayPairedTokens: String = "",
    val gatewayPairRateLimit: Int = DEFAULT_PAIR_RATE_LIMIT,
    val gatewayWebhookRateLimit: Int = DEFAULT_WEBHOOK_RATE_LIMIT,
    val gatewayIdempotencyTtl: Long = DEFAULT_IDEMPOTENCY_TTL,
    val schedulerEnabled: Boolean = true,
    val schedulerMaxTasks: Long = DEFAULT_SCHEDULER_MAX_TASKS,
    val schedulerMaxConcurrent: Long = DEFAULT_SCHEDULER_MAX_CONCURRENT,
    val heartbeatEnabled: Boolean = false,
    val heartbeatIntervalMinutes: Long = DEFAULT_HEARTBEAT_INTERVAL,
    val observabilityBackend: String = "none",
    val observabilityOtelEndpoint: String = DEFAULT_OTEL_ENDPOINT,
    val observabilityOtelServiceName: String = DEFAULT_OTEL_SERVICE_NAME,
    val modelRoutesJson: String = "[]",
    val memoryHygieneEnabled: Boolean = true,
    val memoryArchiveAfterDays: Int = DEFAULT_ARCHIVE_DAYS,
    val memoryPurgeAfterDays: Int = DEFAULT_PURGE_DAYS,
    val memoryEmbeddingProvider: String = "none",
    val memoryEmbeddingModel: String = DEFAULT_EMBEDDING_MODEL,
    val memoryVectorWeight: Float = DEFAULT_VECTOR_WEIGHT,
    val memoryKeywordWeight: Float = DEFAULT_KEYWORD_WEIGHT,
    val composioEnabled: Boolean = false,
    val composioApiKey: String = "",
    val composioEntityId: String = "default",
    /** Whether the shared folder tool plugin is enabled. */
    val sharedFolderEnabled: Boolean = false,
    /** Persisted SAF URI for the user-chosen shared folder, or empty if not configured. */
    val sharedFolderUri: String = "",
    val browserEnabled: Boolean = false,
    val browserAllowedDomains: String = "",
    val httpRequestEnabled: Boolean = true,
    val httpRequestAllowedDomains: String = "",
    val httpRequestMaxResponseSize: Long = DEFAULT_HTTP_REQUEST_MAX_RESPONSE_SIZE,
    val httpRequestTimeoutSecs: Long = DEFAULT_HTTP_REQUEST_TIMEOUT_SECS,
    val webFetchEnabled: Boolean = false,
    val webFetchAllowedDomains: String = "",
    val webFetchBlockedDomains: String = "",
    val webFetchMaxResponseSize: Long = DEFAULT_WEB_FETCH_MAX_RESPONSE_SIZE,
    val webFetchTimeoutSecs: Long = DEFAULT_WEB_FETCH_TIMEOUT_SECS,
    val webSearchEnabled: Boolean = false,
    val webSearchProvider: String = DEFAULT_WEB_SEARCH_PROVIDER,
    val webSearchBraveApiKey: String = "",
    val webSearchGoogleApiKey: String = "",
    val webSearchGoogleCx: String = "",
    val webSearchMaxResults: Long = DEFAULT_WEB_SEARCH_MAX_RESULTS,
    val webSearchTimeoutSecs: Long = DEFAULT_WEB_SEARCH_TIMEOUT_SECS,
    val twitterBrowseEnabled: Boolean = false,
    val twitterBrowseCookieString: String = "",
    val twitterBrowseMaxItems: Long = DEFAULT_TWITTER_BROWSE_MAX_ITEMS,
    val twitterBrowseTimeoutSecs: Long = DEFAULT_TWITTER_BROWSE_TIMEOUT_SECS,
    val transcriptionEnabled: Boolean = false,
    val transcriptionApiUrl: String = DEFAULT_TRANSCRIPTION_API_URL,
    val transcriptionModel: String = DEFAULT_TRANSCRIPTION_MODEL,
    val transcriptionLanguage: String = "",
    val transcriptionMaxDurationSecs: Long = DEFAULT_TRANSCRIPTION_MAX_DURATION_SECS,
    val multimodalMaxImages: Int = DEFAULT_MULTIMODAL_MAX_IMAGES,
    val multimodalMaxImageSizeMb: Int = DEFAULT_MULTIMODAL_MAX_IMAGE_SIZE_MB,
    val multimodalAllowRemoteFetch: Boolean = false,
    val securitySandboxEnabled: Boolean? = null,
    val securitySandboxBackend: String = DEFAULT_SANDBOX_BACKEND,
    val securitySandboxFirejailArgs: String = "",
    val securityResourcesMaxMemoryMb: Int = DEFAULT_RESOURCES_MAX_MEMORY_MB,
    val securityResourcesMaxCpuTimeSecs: Long = DEFAULT_RESOURCES_MAX_CPU_TIME_SECS,
    val securityResourcesMaxSubprocesses: Int = DEFAULT_RESOURCES_MAX_SUBPROCESSES,
    val securityResourcesMemoryMonitoring: Boolean = true,
    val securityAuditEnabled: Boolean = false,
    val securityOtpEnabled: Boolean = false,
    val securityOtpMethod: String = DEFAULT_OTP_METHOD,
    val securityOtpTokenTtlSecs: Int = DEFAULT_OTP_TOKEN_TTL_SECS,
    val securityOtpCacheValidSecs: Int = DEFAULT_OTP_CACHE_VALID_SECS,
    val securityOtpGatedActions: String = DEFAULT_OTP_GATED_ACTIONS,
    val securityOtpGatedDomains: String = "",
    val securityOtpGatedDomainCategories: String = "",
    val securityEstopEnabled: Boolean = false,
    val securityEstopRequireOtpToResume: Boolean = true,
    val memoryQdrantUrl: String = "",
    val memoryQdrantCollection: String = DEFAULT_QDRANT_COLLECTION,
    val memoryQdrantApiKey: String = "",
    val embeddingRoutesJson: String = "[]",
    val queryClassificationEnabled: Boolean = false,
    val skillsOpenSkillsEnabled: Boolean = false,
    val skillsOpenSkillsDir: String = "",
    val skillsPromptInjectionMode: String = "full",
    val proxyEnabled: Boolean = false,
    val proxyHttpProxy: String = "",
    val proxyHttpsProxy: String = "",
    val proxyAllProxy: String = "",
    val proxyNoProxy: String = "",
    val proxyScope: String = DEFAULT_PROXY_SCOPE,
    val proxyServiceSelectors: String = "",
    val reliabilityBackoffMs: Long = DEFAULT_RELIABILITY_BACKOFF_MS,
    val reliabilityApiKeysJson: String = "{}",
    val lockEnabled: Boolean = false,
    val lockTimeoutMinutes: Int = DEFAULT_LOCK_TIMEOUT,
    val pinHash: String = "",
    val pluginRegistryUrl: String = DEFAULT_PLUGIN_REGISTRY_URL,
    val pluginSyncEnabled: Boolean = false,
    val pluginSyncIntervalHours: Int = DEFAULT_PLUGIN_SYNC_INTERVAL,
    val lastPluginSyncTimestamp: Long = 0L,
    val stripThinkingTags: Boolean = false,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    /** Whether Tailscale agent awareness is enabled. */
    val tailscaleAwarenessEnabled: Boolean = false,
    /** JSON-serialized list of manually added peer IPs/hostnames. */
    val tailscaleManualPeers: String = "",
    /** JSON-serialized last probe results (list of [CachedTailscalePeer]). */
    val tailscaleCachedDiscovery: String = "",
    /** Unix timestamp (millis) of the last successful service scan. */
    val tailscaleLastScanTimestamp: Long = 0L,
) {
    /**
     * Returns a string representation with secret fields redacted.
     *
     * Prevents accidental leakage of API keys, tokens, and PIN hashes
     * in log output or crash reports.
     */
    override fun toString(): String =
        "AppSettings(provider=$defaultProvider, model=$defaultModel, host=$host, port=$port, " +
            "secrets=REDACTED)"

    /** Constants for [AppSettings]. */
    companion object {
        /** Default gateway bind address. */
        const val DEFAULT_HOST = "127.0.0.1"

        /** Default gateway bind port. */
        const val DEFAULT_PORT = 42617

        /** Default inference temperature. */
        const val DEFAULT_TEMPERATURE = 0.7f

        /** Default reasoning-effort behavior. */
        const val DEFAULT_REASONING_EFFORT = "auto"

        /** Default daily cost limit in USD. */
        const val DEFAULT_DAILY_LIMIT_USD = 10f

        /** Default monthly cost limit in USD. */
        const val DEFAULT_MONTHLY_LIMIT_USD = 100f

        /** Default percentage of cost limit at which to warn. */
        const val DEFAULT_COST_WARN_PERCENT = 80

        /** Default number of provider retries. */
        const val DEFAULT_PROVIDER_RETRIES = 2

        /** Default memory backend. */
        const val DEFAULT_MEMORY_BACKEND = "sqlite"

        /** Default autonomy level. */
        const val DEFAULT_AUTONOMY_LEVEL = "supervised"

        /** Default allowed shell commands (comma-separated). */
        const val DEFAULT_ALLOWED_COMMANDS =
            "git,npm,cargo,ls,cat,grep,find,echo,pwd,wc,head,tail"

        /** Default forbidden filesystem paths (comma-separated). */
        const val DEFAULT_FORBIDDEN_PATHS =
            "/etc,/root,~/.ssh,~/.gnupg,~/.aws,~/.config"

        /** Default max agent actions per hour (aligned with upstream AutonomyConfig). */
        const val DEFAULT_MAX_ACTIONS_PER_HOUR = 20

        /** Default max cost per day in cents (aligned with upstream AutonomyConfig). */
        const val DEFAULT_MAX_COST_PER_DAY_CENTS = 500

        /** Default gateway pairing rate limit per minute. */
        const val DEFAULT_PAIR_RATE_LIMIT = 10

        /** Default gateway webhook rate limit per minute. */
        const val DEFAULT_WEBHOOK_RATE_LIMIT = 60

        /** Default idempotency TTL in seconds. */
        const val DEFAULT_IDEMPOTENCY_TTL = 300L

        /** Default scheduler max tasks. */
        const val DEFAULT_SCHEDULER_MAX_TASKS = 64L

        /** Default scheduler max concurrent executions. */
        const val DEFAULT_SCHEDULER_MAX_CONCURRENT = 4L

        /** Default heartbeat interval in minutes. */
        const val DEFAULT_HEARTBEAT_INTERVAL = 30L

        /** Default OpenTelemetry endpoint. */
        const val DEFAULT_OTEL_ENDPOINT = "http://localhost:4318"

        /** Default OTel service name. */
        const val DEFAULT_OTEL_SERVICE_NAME = "zeroclaw"

        /** Default memory archive threshold in days. */
        const val DEFAULT_ARCHIVE_DAYS = 7

        /** Default memory purge threshold in days. */
        const val DEFAULT_PURGE_DAYS = 30

        /** Default embedding model name. */
        const val DEFAULT_EMBEDDING_MODEL = "text-embedding-3-small"

        /** Default vector weight for memory recall. */
        const val DEFAULT_VECTOR_WEIGHT = 0.7f

        /** Default keyword weight for memory recall. */
        const val DEFAULT_KEYWORD_WEIGHT = 0.3f

        /** Default plugin registry URL. */
        const val DEFAULT_PLUGIN_REGISTRY_URL = "https://registry.zeroclaw.dev/plugins.json"

        /** Default plugin sync interval in hours. */
        const val DEFAULT_PLUGIN_SYNC_INTERVAL = 24

        /** Default lock timeout in minutes. */
        const val DEFAULT_LOCK_TIMEOUT = 15

        /** Default HTTP request max response size in bytes (1 MB). */
        const val DEFAULT_HTTP_REQUEST_MAX_RESPONSE_SIZE = 1_000_000L

        /** Default HTTP request timeout in seconds. */
        const val DEFAULT_HTTP_REQUEST_TIMEOUT_SECS = 30L

        /** Default web fetch max response size in bytes (500 KB). */
        const val DEFAULT_WEB_FETCH_MAX_RESPONSE_SIZE = 500_000L

        /** Default web fetch timeout in seconds. */
        const val DEFAULT_WEB_FETCH_TIMEOUT_SECS = 30L

        /** Default web search provider. */
        const val DEFAULT_WEB_SEARCH_PROVIDER = "auto"

        /** Default web search max results. */
        const val DEFAULT_WEB_SEARCH_MAX_RESULTS = 5L

        /** Default web search timeout in seconds. */
        const val DEFAULT_WEB_SEARCH_TIMEOUT_SECS = 15L

        /** Default Twitter/X browse max items. */
        const val DEFAULT_TWITTER_BROWSE_MAX_ITEMS = 20L

        /** Default Twitter/X browse timeout in seconds. */
        const val DEFAULT_TWITTER_BROWSE_TIMEOUT_SECS = 30L

        /** Default transcription API URL (Groq Whisper). */
        const val DEFAULT_TRANSCRIPTION_API_URL =
            "https://api.groq.com/openai/v1/audio/transcriptions"

        /** Default transcription model. */
        const val DEFAULT_TRANSCRIPTION_MODEL = "whisper-large-v3-turbo"

        /** Default max transcription duration in seconds. */
        const val DEFAULT_TRANSCRIPTION_MAX_DURATION_SECS = 120L

        /** Default max images per multimodal request. */
        const val DEFAULT_MULTIMODAL_MAX_IMAGES = 4

        /** Default max image size in MB for multimodal input. */
        const val DEFAULT_MULTIMODAL_MAX_IMAGE_SIZE_MB = 5

        /** Default sandbox backend. */
        const val DEFAULT_SANDBOX_BACKEND = "auto"

        /** Default resource limit: max memory in MB. */
        const val DEFAULT_RESOURCES_MAX_MEMORY_MB = 512

        /** Default resource limit: max CPU time in seconds. */
        const val DEFAULT_RESOURCES_MAX_CPU_TIME_SECS = 60L

        /** Default resource limit: max subprocesses. */
        const val DEFAULT_RESOURCES_MAX_SUBPROCESSES = 10

        /** Default OTP method. */
        const val DEFAULT_OTP_METHOD = "totp"

        /** Default OTP token TTL in seconds. */
        const val DEFAULT_OTP_TOKEN_TTL_SECS = 30

        /** Default OTP cache validity in seconds. */
        const val DEFAULT_OTP_CACHE_VALID_SECS = 300

        /** Default OTP-gated actions (comma-separated). */
        const val DEFAULT_OTP_GATED_ACTIONS =
            "shell,file_write,browser_open,browser,memory_forget"

        /** Default Qdrant collection name. */
        const val DEFAULT_QDRANT_COLLECTION = "zeroclaw_memories"

        /** Default proxy scope. */
        const val DEFAULT_PROXY_SCOPE = "zeroclaw"

        /** Default reliability backoff duration in milliseconds. */
        const val DEFAULT_RELIABILITY_BACKOFF_MS = 500L
    }
}

/**
 * Determines which colour scheme variant the app uses.
 *
 * Used by the theme layer in [ZeroAITheme][com.zeroclaw.android.ui.theme.ZeroAITheme]
 * to select between system-default, forced-light, or forced-dark rendering.
 */
enum class ThemeMode {
    /** Follow the device system theme. */
    SYSTEM,

    /** Always use the light colour scheme. */
    LIGHT,

    /** Always use the dark colour scheme. */
    DARK,
}
