/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.service

import com.zeroclaw.android.model.Agent

/**
 * Resolved agent data ready for TOML serialization.
 *
 * The agent block in V3 is a thin reference: `model_provider` points to a
 * `[providers.models.<type>.<alias>]` entry that already carries the
 * model name + API key + URI + temperature. Inlining those fields per
 * agent forced the upstream V2→V3 migration to synthesize a copy of
 * the provider config for every agent (`agent_<alias>` entries) — N
 * agents made N duplicate providers and left `first_model_provider()`
 * picking one alphabetically. Pointing every agent at the shared
 * `<type>.default` alias eliminates that drift.
 *
 * @property name Agent name used as the TOML table key (`[agents.<name>]`).
 * @property modelProviderRef Dotted `<type>.<alias>` reference into
 *   `providers.models`. Empty string omits the field (agent will fall
 *   back to `config.first_model_provider()` at runtime).
 * @property maxDepth Maximum reasoning depth; default omits the field.
 * @property channels Dotted `<type>.<alias>` references this agent owns. The
 *   channels supervisor skips any `[channels.<type>.<alias>]` block not
 *   claimed by an enabled agent, so the primary agent must list every
 *   enabled channel or messages silently never route.
 */
data class AgentTomlEntry(
    val name: String,
    val modelProviderRef: String,
    val maxDepth: Int = Agent.DEFAULT_MAX_DEPTH,
    val channels: List<String> = emptyList(),
)

/**
 * Aggregated global configuration values for TOML generation.
 *
 * Grouping these fields into a single data class avoids exceeding the
 * detekt `LongParameterList` threshold (6 parameters).
 *
 * Upstream sections mapped (see `.claude/submodule-api-map.md`):
 * - `default_temperature`, `default_provider`, `default_model`, `api_key`
 * - `[agent]` compact_context
 * - `[gateway]` host, port, pairing, rate limits, idempotency
 * - `[memory]` backend, hygiene, embedding, recall weights
 * - `[identity]` aieos_inline
 * - `[cost]` enabled, daily/monthly limits, warn percent
 * - `[reliability]` provider_retries, fallback_providers
 * - `[autonomy]` level, workspace, commands, paths, limits
 * - `[tunnel]` provider + tailscale sub-table
 * - `[scheduler]` enabled, max_tasks, max_concurrent
 * - `[heartbeat]` enabled, interval_minutes
 * - `[observability]` backend, otel_endpoint, otel_service_name
 * - `[[model_routes]]` hint, provider, model
 * - `[composio]` enabled, api_key, entity_id
 * - `[browser]` enabled, allowed_domains
 * - `[http_request]` enabled, allowed_domains
 * - `[tty]` enabled, ssh_keepalive_secs, context_max_bytes
 *
 * @property provider Android provider ID (e.g. "openai", "ollama").
 * @property model Model name (e.g. "gpt-4o").
 * @property apiKey Secret API key value.
 * @property baseUrl Provider endpoint URL.
 * @property temperature Default inference temperature (0.0–2.0).
 * @property reasoningEffort Global reasoning-effort override. `"auto"` keeps model defaults.
 * @property compactContext Whether compact context mode is enabled.
 * @property costEnabled Whether cost limits are enforced.
 * @property dailyLimitUsd Daily spending cap in USD.
 * @property monthlyLimitUsd Monthly spending cap in USD.
 * @property costWarnAtPercent Percentage of limit at which to warn.
 * @property providerRetries Number of retries before fallback.
 * @property fallbackProviders Ordered list of fallback provider IDs.
 * @property memoryBackend Memory backend name.
 * @property memoryAutoSave Whether the memory backend auto-saves conversation context.
 * @property identityJson AIEOS v1.1 identity JSON blob.
 * @property autonomyLevel Autonomy level: "readonly", "supervised", or "full".
 * @property workspaceOnly Whether to restrict file access to workspace only.
 * @property allowedCommands Allowed shell commands list.
 * @property forbiddenPaths Forbidden filesystem paths list.
 * @property maxActionsPerHour Maximum agent actions per hour.
 * @property maxCostPerDayCents Maximum daily cost in cents.
 * @property requireApprovalMediumRisk Whether medium-risk actions require approval.
 * @property blockHighRiskCommands Whether to block high-risk commands entirely.
 * @property tunnelProvider Tunnel provider name: "none" or "tailscale".
 * @property tunnelTailscaleFunnel Whether to enable Tailscale Funnel.
 * @property tunnelTailscaleHostname Custom Tailscale hostname.
 * @property gatewayHost Gateway bind address.
 * @property gatewayPort Gateway bind port.
 * @property gatewayRequirePairing Whether gateway requires pairing tokens. Defaults to false
 *   on Android (upstream default: true) because mobile devices are typically behind NAT.
 * @property gatewayAllowPublicBind Whether to allow binding to 0.0.0.0.
 * @property gatewayPairedTokens Authorized pairing tokens list.
 * @property gatewayPairRateLimit Pairing rate limit per minute.
 * @property gatewayWebhookRateLimit Webhook rate limit per minute.
 * @property gatewayIdempotencyTtl Idempotency TTL in seconds.
 * @property schedulerEnabled Whether the task scheduler is active.
 * @property schedulerMaxTasks Maximum scheduler tasks.
 * @property schedulerMaxConcurrent Maximum concurrent task executions.
 * @property heartbeatEnabled Whether the heartbeat engine is active.
 * @property heartbeatIntervalMinutes Interval between heartbeat ticks.
 * @property observabilityBackend Observability backend name.
 * @property observabilityOtelEndpoint OpenTelemetry collector endpoint.
 * @property observabilityOtelServiceName Service name for OTel traces.
 * @property memoryHygieneEnabled Whether memory hygiene is active.
 * @property memoryArchiveAfterDays Days before memory entries are archived.
 * @property memoryPurgeAfterDays Days before archived entries are purged.
 * @property memoryEmbeddingProvider Embedding provider name.
 * @property memoryEmbeddingModel Embedding model name.
 * @property memoryVectorWeight Weight for vector similarity in recall.
 * @property memoryKeywordWeight Weight for keyword matching in recall.
 * @property composioEnabled Whether Composio tool integration is active.
 * @property composioApiKey Composio API key.
 * @property composioEntityId Composio entity identifier.
 * @property browserEnabled Whether the browser tool is enabled.
 * @property browserAllowedDomains Allowed browser domains list.
 * @property httpRequestEnabled Whether the HTTP request tool is enabled.
 * @property httpRequestAllowedDomains Allowed HTTP domains list.
 * @property httpRequestMaxResponseSize Maximum response body size in bytes for HTTP requests.
 * @property httpRequestTimeoutSecs Request timeout in seconds for HTTP requests.
 * @property multimodalMaxImages Maximum images per request.
 * @property multimodalMaxImageSizeMb Maximum image size in MB.
 * @property multimodalAllowRemoteFetch Whether to allow fetching remote image URLs.
 * @property proxyEnabled Whether proxy configuration is active.
 * @property proxyHttpProxy HTTP proxy URL.
 * @property proxyHttpsProxy HTTPS proxy URL.
 * @property proxyNoProxy List of domains that bypass the proxy.
 * @property proxyAllProxy Catch-all proxy URL applied to all protocols.
 * @property proxyScope Proxy scope: "environment", "zeroclaw" (default), or "services".
 * @property proxyServiceSelectors Service selectors for selective proxy routing.
 * @property webFetchEnabled Whether the web fetch tool is enabled.
 * @property webFetchAllowedDomains Allowed domains for web fetch requests.
 * @property webFetchBlockedDomains Blocked domains for web fetch requests.
 * @property webFetchMaxResponseSize Maximum response body size in bytes.
 * @property webFetchTimeoutSecs Timeout for web fetch requests in seconds.
 * @property webSearchEnabled Whether the web search tool is enabled.
 * @property webSearchProvider Web search provider name ("meta", "duckduckgo", "brave",
 *   "tavily", or "searxng"); legacy stored values are normalized to "meta" at emission.
 * @property webSearchBraveApiKey Brave Search API key for authenticated queries.
 * @property webSearchTavilyApiKey Tavily Search API key for authenticated queries.
 * @property webSearchSearxngUrl SearXNG instance URL for self-hosted search.
 * @property webSearchMaxResults Maximum number of search results to return.
 * @property webSearchTimeoutSecs Timeout for web search requests in seconds.
 * @property webSearchRequestsPerMinute Maximum meta searches per minute (meta backend only).
 * @property twitterBrowseEnabled Whether the Twitter/X read-only tool is enabled.
 * @property twitterBrowseMaxItems Maximum number of Twitter/X items returned per request.
 * @property twitterBrowseTimeoutSecs Timeout for Twitter/X browsing requests in seconds.
 * @property securitySandboxEnabled Whether sandboxing is enabled (null = upstream default).
 * @property securitySandboxBackend Sandbox backend name (e.g. "auto", "firejail").
 * @property securitySandboxFirejailArgs Extra arguments passed to Firejail.
 * @property securityResourcesMaxMemoryMb Maximum memory allocation in MB.
 * @property securityResourcesMaxCpuTimeSecs Maximum CPU time in seconds.
 * @property securityResourcesMaxSubprocesses Maximum number of subprocesses.
 * @property securityResourcesMemoryMonitoring Whether memory monitoring is active.
 * @property securityAuditEnabled Whether security audit logging is active.
 * @property securityAuditLogPath File path for audit log output.
 * @property securityAuditMaxSizeMb Maximum audit log file size in megabytes.
 * @property securityAuditSignEvents Whether audit events are cryptographically signed.
 * @property securityEstopEnabled Whether the emergency stop mechanism is active.
 * @property securityEstopRequireOtpToResume Whether resuming from e-stop requires OTP.
 * @property securityEstopStateFile File path for e-stop state persistence.
 * @property skillsPromptInjectionMode Skill prompt injection mode: "full" or "compact".
 * @property reliabilityBackoffMs Provider backoff duration in milliseconds.
 * @property reliabilityApiKeysJson JSON object mapping provider names to API keys.
 * @property routingSimple Provider preference order for simple/factual queries.
 * @property routingComplex Provider preference order for complex reasoning queries.
 * @property routingCreative Provider preference order for creative generation.
 * @property routingToolUse Provider preference order for tool-use queries.
 * @property hubAppContext Assembled hub-app awareness fragment injected into the system prompt.
 * @property sharedFolderEnabled Whether the shared-folder plugin is active.
 */
@Suppress("LongParameterList", "OutdatedDocumentation")
data class GlobalTomlConfig(
    val provider: String,
    val model: String,
    val apiKey: String,
    val baseUrl: String,
    val temperature: Float = DEFAULT_GLOBAL_TEMPERATURE,
    val reasoningEffort: String = REASONING_EFFORT_UNSET,
    val compactContext: Boolean = false,
    val costEnabled: Boolean = false,
    val dailyLimitUsd: Double = DEFAULT_DAILY_LIMIT,
    val monthlyLimitUsd: Double = DEFAULT_MONTHLY_LIMIT,
    val costWarnAtPercent: Int = DEFAULT_WARN_PERCENT,
    val providerRetries: Int = DEFAULT_RETRIES,
    val fallbackProviders: List<String> = emptyList(),
    val memoryBackend: String = DEFAULT_MEMORY,
    val memoryAutoSave: Boolean = true,
    val identityJson: String = "",
    val autonomyLevel: String = "supervised",
    val workspaceOnly: Boolean = true,
    val allowedCommands: List<String> = emptyList(),
    val forbiddenPaths: List<String> = emptyList(),
    val maxActionsPerHour: Int = DEFAULT_MAX_ACTIONS,
    val maxCostPerDayCents: Int = DEFAULT_MAX_COST_CENTS,
    val requireApprovalMediumRisk: Boolean = true,
    val blockHighRiskCommands: Boolean = true,
    val tunnelProvider: String = "none",
    val tunnelTailscaleFunnel: Boolean = false,
    val tunnelTailscaleHostname: String = "",
    val gatewayHost: String = "127.0.0.1",
    val gatewayPort: Int = DEFAULT_GATEWAY_PORT,
    val gatewayRequirePairing: Boolean = false,
    val gatewayAllowPublicBind: Boolean = false,
    val gatewayPairedTokens: List<String> = emptyList(),
    val gatewayPairRateLimit: Int = DEFAULT_PAIR_RATE,
    val gatewayWebhookRateLimit: Int = DEFAULT_WEBHOOK_RATE,
    val gatewayIdempotencyTtl: Long = DEFAULT_IDEMPOTENCY_TTL,
    val schedulerEnabled: Boolean = true,
    val schedulerMaxTasks: Long = DEFAULT_SCHEDULER_TASKS,
    val schedulerMaxConcurrent: Long = DEFAULT_SCHEDULER_CONCURRENT,
    val heartbeatEnabled: Boolean = false,
    val heartbeatIntervalMinutes: Long = DEFAULT_HEARTBEAT_INTERVAL,
    val observabilityBackend: String = "none",
    val observabilityOtelEndpoint: String = "",
    val observabilityOtelServiceName: String = "zeroclaw",
    val memoryHygieneEnabled: Boolean = true,
    val memoryArchiveAfterDays: Int = DEFAULT_ARCHIVE_DAYS,
    val memoryPurgeAfterDays: Int = DEFAULT_PURGE_DAYS,
    val memoryEmbeddingProvider: String = "none",
    val memoryEmbeddingModel: String = "",
    val memoryVectorWeight: Double = DEFAULT_VECTOR_WEIGHT,
    val memoryKeywordWeight: Double = DEFAULT_KEYWORD_WEIGHT,
    val composioEnabled: Boolean = false,
    val composioApiKey: String = "",
    val composioEntityId: String = "default",
    val browserEnabled: Boolean = false,
    val browserAllowedDomains: List<String> = emptyList(),
    val httpRequestEnabled: Boolean = true,
    val httpRequestAllowedDomains: List<String> = emptyList(),
    val httpRequestMaxResponseSize: Long = DEFAULT_HTTP_REQUEST_MAX_RESPONSE_SIZE,
    val httpRequestTimeoutSecs: Long = DEFAULT_HTTP_REQUEST_TIMEOUT_SECS,
    val multimodalMaxImages: Int = DEFAULT_MULTIMODAL_MAX_IMAGES,
    val multimodalMaxImageSizeMb: Int = DEFAULT_MULTIMODAL_MAX_SIZE_MB,
    val multimodalAllowRemoteFetch: Boolean = false,
    val proxyEnabled: Boolean = false,
    val proxyHttpProxy: String = "",
    val proxyHttpsProxy: String = "",
    val proxyNoProxy: List<String> = emptyList(),
    val proxyAllProxy: String = "",
    val proxyScope: String = "zeroclaw",
    val proxyServiceSelectors: List<String> = emptyList(),
    val webFetchEnabled: Boolean = false,
    val webFetchAllowedDomains: List<String> = emptyList(),
    val webFetchBlockedDomains: List<String> = emptyList(),
    val webFetchMaxResponseSize: Long = DEFAULT_WEB_FETCH_MAX_RESPONSE_SIZE,
    val webFetchTimeoutSecs: Long = DEFAULT_WEB_FETCH_TIMEOUT_SECS,
    val webSearchEnabled: Boolean = false,
    val webSearchProvider: String = "meta",
    val webSearchBraveApiKey: String = "",
    val webSearchTavilyApiKey: String = "",
    val webSearchSearxngUrl: String = "",
    val webSearchMaxResults: Long = DEFAULT_WEB_SEARCH_MAX_RESULTS,
    val webSearchTimeoutSecs: Long = DEFAULT_WEB_SEARCH_TIMEOUT_SECS,
    val webSearchRequestsPerMinute: Long = DEFAULT_WEB_SEARCH_REQUESTS_PER_MINUTE,
    val twitterBrowseEnabled: Boolean = false,
    val twitterBrowseMaxItems: Long = DEFAULT_TWITTER_BROWSE_MAX_ITEMS,
    val twitterBrowseTimeoutSecs: Long = DEFAULT_TWITTER_BROWSE_TIMEOUT_SECS,
    val securitySandboxEnabled: Boolean? = null,
    val securitySandboxBackend: String = "auto",
    val securitySandboxFirejailArgs: List<String> = emptyList(),
    val securityResourcesMaxMemoryMb: Int = DEFAULT_RESOURCES_MAX_MEMORY_MB,
    val securityResourcesMaxCpuTimeSecs: Long = DEFAULT_RESOURCES_MAX_CPU_TIME_SECS,
    val securityResourcesMaxSubprocesses: Int = DEFAULT_RESOURCES_MAX_SUBPROCESSES,
    val securityResourcesMemoryMonitoring: Boolean = true,
    val securityAuditEnabled: Boolean = true,
    val securityAuditLogPath: String = "audit.log",
    val securityAuditMaxSizeMb: Int = DEFAULT_AUDIT_MAX_SIZE_MB,
    val securityAuditSignEvents: Boolean = false,
    val securityEstopEnabled: Boolean = false,
    val securityEstopRequireOtpToResume: Boolean = true,
    val securityEstopStateFile: String = "estop-state.json",
    val skillsPromptInjectionMode: String = "full",
    val reliabilityBackoffMs: Long = DEFAULT_RELIABILITY_BACKOFF_MS,
    val reliabilityApiKeysJson: String = "{}",
    val routingSimple: List<String> = emptyList(),
    val routingComplex: List<String> = emptyList(),
    val routingCreative: List<String> = emptyList(),
    val routingToolUse: List<String> = emptyList(),
    /** @property emailImapHost IMAP server hostname. */
    val emailImapHost: String = "",
    /** @property emailImapPort IMAP server port. */
    val emailImapPort: Int = DEFAULT_IMAP_PORT,
    /** @property emailSmtpHost SMTP server hostname. */
    val emailSmtpHost: String = "",
    /** @property emailSmtpPort SMTP server port. */
    val emailSmtpPort: Int = DEFAULT_SMTP_PORT,
    /** @property emailAddress Email address for login and From header. */
    val emailAddress: String = "",
    /** @property emailPassword App-specific password for IMAP/SMTP. */
    val emailPassword: String = "",
    /** @property emailCheckTimes Cron check times in HH:MM format. */
    val emailCheckTimes: List<String> = emptyList(),
    /** @property emailTimezone IANA timezone for check times. */
    val emailTimezone: String = "",
    /** @property emailEnabled Whether email integration is active. */
    val emailEnabled: Boolean = false,
    val hubAppContext: String? = null,
    val sharedFolderEnabled: Boolean = false,
    /** @property ttyEnabled Whether the TTY terminal backend is active. */
    val ttyEnabled: Boolean = false,
    /** @property ttySshKeepaliveSecs Interval between SSH keepalive packets in seconds. */
    val ttySshKeepaliveSecs: Int = DEFAULT_TTY_SSH_KEEPALIVE_SECS,
    /** @property ttyContextMaxBytes Maximum context buffer size in bytes for TTY sessions. */
    val ttyContextMaxBytes: Int = DEFAULT_TTY_CONTEXT_MAX_BYTES,
    /**
     * Optional HTTP-client timeout (seconds) for the default model
     * provider's API calls. Maps to upstream V1's
     * `provider_timeout_secs`, which the V2 migration folds into
     * `[providers.models.<type>.default].timeout_secs`. Defaults to
     * null (omit the line; upstream falls back to 120 s). Set this
     * for slow local providers — e.g. on-device LiteRT-LM where a
     * single CPU-backend response can exceed the 120 s default.
     */
    val providerTimeoutSecs: Long? = null,
) {
    /** Constants for [GlobalTomlConfig]. */
    companion object {
        /** Default inference temperature. */
        const val DEFAULT_GLOBAL_TEMPERATURE = 0.7f

        /** Default daily cost limit in USD. */
        const val DEFAULT_DAILY_LIMIT = 10.0

        /** Default reasoning-effort behavior. */
        const val REASONING_EFFORT_UNSET = "auto"

        /** Default monthly cost limit in USD. */
        const val DEFAULT_MONTHLY_LIMIT = 100.0

        /** Default cost warning threshold percentage. */
        const val DEFAULT_WARN_PERCENT = 80

        /** Default number of provider retries. */
        const val DEFAULT_RETRIES = 2

        /** Default memory backend. */
        const val DEFAULT_MEMORY = "sqlite"

        /** Default max actions per hour (aligned with upstream AutonomyConfig default). */
        const val DEFAULT_MAX_ACTIONS = 20

        /** Default max cost per day in cents (aligned with upstream AutonomyConfig default). */
        const val DEFAULT_MAX_COST_CENTS = 500

        /** Default gateway port. */
        const val DEFAULT_GATEWAY_PORT = 0

        /** Default pair rate limit per minute. */
        const val DEFAULT_PAIR_RATE = 10

        /** Default webhook rate limit per minute. */
        const val DEFAULT_WEBHOOK_RATE = 60

        /** Default idempotency TTL in seconds. */
        const val DEFAULT_IDEMPOTENCY_TTL = 300L

        /** Default scheduler max tasks. */
        const val DEFAULT_SCHEDULER_TASKS = 64L

        /** Default scheduler max concurrent. */
        const val DEFAULT_SCHEDULER_CONCURRENT = 4L

        /** Default heartbeat interval in minutes. */
        const val DEFAULT_HEARTBEAT_INTERVAL = 30L

        /** Default memory archive threshold. */
        const val DEFAULT_ARCHIVE_DAYS = 7

        /** Default memory purge threshold. */
        const val DEFAULT_PURGE_DAYS = 30

        /** Default vector weight. */
        const val DEFAULT_VECTOR_WEIGHT = 0.7

        /** Default keyword weight. */
        const val DEFAULT_KEYWORD_WEIGHT = 0.3

        /** Default max images for multimodal. */
        const val DEFAULT_MULTIMODAL_MAX_IMAGES = 4

        /** Default max image size in MB. */
        const val DEFAULT_MULTIMODAL_MAX_SIZE_MB = 5

        /** Default web fetch max response size in bytes. */
        const val DEFAULT_WEB_FETCH_MAX_RESPONSE_SIZE = 500_000L

        /** Default web fetch timeout in seconds. */
        const val DEFAULT_WEB_FETCH_TIMEOUT_SECS = 30L

        /** Default web search max results. */
        const val DEFAULT_WEB_SEARCH_MAX_RESULTS = 5L

        /** Lowest web search max results the engine accepts. */
        const val MIN_WEB_SEARCH_MAX_RESULTS = 1L

        /** Highest web search max results the engine accepts. */
        const val MAX_WEB_SEARCH_MAX_RESULTS = 10L

        /** Default web search timeout in seconds. */
        const val DEFAULT_WEB_SEARCH_TIMEOUT_SECS = 15L

        /** Default maximum meta searches per minute. */
        const val DEFAULT_WEB_SEARCH_REQUESTS_PER_MINUTE = 10L

        /** Lowest accepted meta searches-per-minute limit (0 would mean unlimited upstream). */
        const val MIN_WEB_SEARCH_REQUESTS_PER_MINUTE = 1L

        /** Highest accepted meta searches-per-minute limit. */
        const val MAX_WEB_SEARCH_REQUESTS_PER_MINUTE = 60L

        /** Default Twitter/X browse max items. */
        const val DEFAULT_TWITTER_BROWSE_MAX_ITEMS = 20L

        /** Default Twitter/X browse timeout in seconds. */
        const val DEFAULT_TWITTER_BROWSE_TIMEOUT_SECS = 30L

        /** Default audit log max file size in MB (aligned with upstream AuditConfig default). */
        const val DEFAULT_AUDIT_MAX_SIZE_MB = 100

        /** Default resource limit: max memory in MB. */
        const val DEFAULT_RESOURCES_MAX_MEMORY_MB = 512

        /** Default resource limit: max CPU time in seconds. */
        const val DEFAULT_RESOURCES_MAX_CPU_TIME_SECS = 60L

        /** Default resource limit: max subprocesses. */
        const val DEFAULT_RESOURCES_MAX_SUBPROCESSES = 10

        /** Default IMAP port (implicit TLS). */
        const val DEFAULT_IMAP_PORT = 993

        /** Default SMTP port (implicit TLS). */
        const val DEFAULT_SMTP_PORT = 465

        /** Default reliability backoff in milliseconds. */
        const val DEFAULT_RELIABILITY_BACKOFF_MS = 500L

        /** Default HTTP request max response size in bytes (1 MB). */
        const val DEFAULT_HTTP_REQUEST_MAX_RESPONSE_SIZE = 1_000_000L

        /** Default HTTP request timeout in seconds. */
        const val DEFAULT_HTTP_REQUEST_TIMEOUT_SECS = 30L

        /** Maximum value for a Rust `u8` field (used for `warn_at_percent` clamping). */
        const val MAX_U8 = 255

        /** Default SSH keepalive interval in seconds. */
        const val DEFAULT_TTY_SSH_KEEPALIVE_SECS = 15

        /** Default TTY context buffer size in bytes. */
        const val DEFAULT_TTY_CONTEXT_MAX_BYTES = 65_536

        /** Valid upstream autonomy levels (from AutonomyLevel enum). */
        val VALID_AUTONOMY_LEVELS = setOf("readonly", "supervised", "full")

        /** Valid explicit runtime reasoning-effort values. */
        val VALID_REASONING_EFFORTS = setOf("none", "low", "medium", "high", "xhigh")
    }
}

/**
 * Peer agent entry ready for TOML serialization.
 *
 * @property ip Tailscale IP address.
 * @property hostname Peer hostname.
 * @property kind Agent type: `"zeroclaw"` or `"openclaw"`.
 * @property port Agent gateway TCP port.
 * @property alias User-configurable @mention alias.
 * @property authRequired Whether the peer requires a bearer token.
 * @property enabled Whether this peer is enabled for routing.
 */
data class PeerTomlEntry(
    val ip: String,
    val hostname: String,
    val kind: String,
    val port: Int,
    val alias: String,
    val authRequired: Boolean,
    val enabled: Boolean,
) {
    /**
     * Whether every field carries a value the upstream Rust schema would accept.
     *
     * Producers must filter on this before handing entries to
     * [ConfigTomlBuilder.buildTailscalePeersToml] -- the serializer trusts its
     * input and emits whatever it gets.
     */
    fun isValid(): Boolean =
        ip.isNotBlank() &&
            hostname.isNotBlank() &&
            kind.isNotBlank() &&
            port in MIN_TCP_PORT..MAX_TCP_PORT

    private companion object {
        private const val MIN_TCP_PORT = 1
        private const val MAX_TCP_PORT = 65535
    }
}
