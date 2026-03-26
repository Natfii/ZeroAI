/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.service

import com.zeroclaw.android.model.Agent
import com.zeroclaw.android.model.ChannelType
import com.zeroclaw.android.model.ConnectedChannel
import com.zeroclaw.android.model.FieldInputType

/**
 * Resolved agent data ready for TOML serialization.
 *
 * All provider/URL resolution is performed before constructing this class
 * so that [ConfigTomlBuilder.buildAgentsToml] only needs to emit values.
 *
 * Upstream `[agents.<name>]` supports `temperature` (`Option<f64>`) and
 * `max_depth` (`u32`) — see `.claude/submodule-api-map.md` lines 235–236.
 *
 * @property name Agent name used as the TOML table key (`[agents.<name>]`).
 * @property provider Resolved upstream factory name (e.g. `"custom:http://host/v1"`).
 * @property model Model identifier (e.g. `"google/gemma-3-12b"`).
 * @property apiKey Decrypted API key value; blank if the provider needs none.
 * @property systemPrompt Agent system prompt; blank if not configured.
 * @property temperature Per-agent temperature override; null omits the field.
 * @property maxDepth Maximum reasoning depth; default omits the field.
 */
data class AgentTomlEntry(
    val name: String,
    val provider: String,
    val model: String,
    val apiKey: String = "",
    val systemPrompt: String = "",
    val temperature: Float? = null,
    val maxDepth: Int = Agent.DEFAULT_MAX_DEPTH,
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
 * @property transcriptionEnabled Whether audio transcription is active.
 * @property transcriptionApiUrl Transcription API endpoint URL.
 * @property transcriptionModel Transcription model name.
 * @property transcriptionLanguage ISO language code hint for transcription.
 * @property transcriptionMaxDurationSecs Maximum audio duration in seconds.
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
 * @property webSearchProvider Web search provider name ("auto", "brave", or "google").
 * @property webSearchBraveApiKey Brave Search API key for authenticated queries.
 * @property webSearchGoogleApiKey Google Custom Search API key for authenticated queries.
 * @property webSearchGoogleCx Google Custom Search Engine ID.
 * @property webSearchMaxResults Maximum number of search results to return.
 * @property webSearchTimeoutSecs Timeout for web search requests in seconds.
 * @property twitterBrowseEnabled Whether the Twitter/X browse tool is enabled.
 * @property twitterBrowseCookieString Authenticated cookie string for Twitter/X browsing.
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
 * @property memoryQdrantUrl Qdrant vector database connection URL.
 * @property memoryQdrantCollection Qdrant collection name for memory storage.
 * @property memoryQdrantApiKey Qdrant API key for authenticated access.
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
@Suppress("LongParameterList")
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
    val transcriptionEnabled: Boolean = false,
    val transcriptionApiUrl: String = DEFAULT_TRANSCRIPTION_API_URL,
    val transcriptionModel: String = DEFAULT_TRANSCRIPTION_MODEL,
    val transcriptionLanguage: String = "",
    val transcriptionMaxDurationSecs: Long = DEFAULT_TRANSCRIPTION_MAX_DURATION,
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
    val webSearchProvider: String = "auto",
    val webSearchBraveApiKey: String = "",
    val webSearchGoogleApiKey: String = "",
    val webSearchGoogleCx: String = "",
    val webSearchMaxResults: Long = DEFAULT_WEB_SEARCH_MAX_RESULTS,
    val webSearchTimeoutSecs: Long = DEFAULT_WEB_SEARCH_TIMEOUT_SECS,
    val twitterBrowseEnabled: Boolean = false,
    val twitterBrowseCookieString: String = "",
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
    val memoryQdrantUrl: String = "",
    val memoryQdrantCollection: String = "zeroclaw_memories",
    val memoryQdrantApiKey: String = "",
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

        /** Default transcription API URL (Groq Whisper). */
        const val DEFAULT_TRANSCRIPTION_API_URL =
            "https://api.groq.com/openai/v1/audio/transcriptions"

        /** Default transcription model. */
        const val DEFAULT_TRANSCRIPTION_MODEL = "whisper-large-v3-turbo"

        /** Default max transcription duration in seconds. */
        const val DEFAULT_TRANSCRIPTION_MAX_DURATION = 120L

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

        /** Default web search timeout in seconds. */
        const val DEFAULT_WEB_SEARCH_TIMEOUT_SECS = 15L

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
)

/**
 * Builds a valid TOML configuration string for the ZeroAI daemon.
 *
 * The upstream [Config][zeroclaw::config::Config] struct requires at minimum
 * a `default_temperature` field. This builder constructs a TOML document from
 * the user's stored settings and API key, resolving Android provider IDs to
 * the upstream Rust factory conventions.
 *
 * Upstream provider name conventions (from `create_provider(name, api_key)`):
 * - Standard cloud: `"openai"`, `"anthropic"`, etc. (hardcoded endpoints)
 * - Ollama default: `"ollama"` (hardcoded to `http://localhost:11434`)
 * - Custom OpenAI-compatible: `"custom:http://host/v1"` (URL in name)
 * - Custom Anthropic-compatible: `"anthropic-custom:http://host"` (URL in name)
 */
@Suppress("TooManyFunctions", "LargeClass")
object ConfigTomlBuilder {
    /**
     * Placeholder API key injected for self-hosted providers (LM Studio,
     * vLLM, LocalAI, Ollama) that don't require authentication.
     *
     * The upstream [OpenAiCompatibleProvider] unconditionally requires
     * `api_key` to be `Some(...)` and will error before sending any HTTP
     * request if it is `None`. Local servers ignore the resulting
     * `Authorization: Bearer not-needed` header.
     */
    private const val PLACEHOLDER_API_KEY = "not-needed"

    /** Default Ollama endpoint used by the upstream Rust factory. */
    private const val OLLAMA_DEFAULT_URL = "http://localhost:11434"

    /** Android provider IDs that map to `custom:URL` in the TOML. */
    private val OPENAI_COMPATIBLE_SELF_HOSTED =
        setOf(
            "custom-openai",
        )

    /**
     * Builds a TOML configuration string from the given parameters.
     *
     * Fields with blank values are omitted from the output. The
     * `default_temperature` field is always present because the
     * upstream parser requires it.
     *
     * @param provider Android provider ID (e.g. "openai", "ollama").
     * @param model Model name (e.g. "gpt-4o").
     * @param apiKey Secret API key value (may be blank for local providers).
     * @param baseUrl Provider endpoint URL (may be blank for cloud providers).
     * @return A valid TOML configuration string.
     */
    fun build(
        provider: String,
        model: String,
        apiKey: String,
        baseUrl: String,
    ): String =
        build(
            GlobalTomlConfig(
                provider = provider,
                model = model,
                apiKey = apiKey,
                baseUrl = baseUrl,
            ),
        )

    /**
     * Builds a complete TOML configuration string from a [GlobalTomlConfig].
     *
     * Emits all upstream-supported sections conditionally based on the
     * config values. Sections with only default values are omitted to
     * keep the TOML output minimal.
     *
     * @param config Aggregated global configuration values.
     * @return A valid TOML configuration string.
     */
    @Suppress("CognitiveComplexMethod", "LongMethod")
    fun build(config: GlobalTomlConfig): String =
        buildString {
            appendLine("default_temperature = ${config.temperature}")

            val resolvedProvider = resolveProvider(config.provider, config.baseUrl)
            if (resolvedProvider.isNotBlank()) {
                appendLine("default_provider = ${tomlString(resolvedProvider)}")
            }

            if (config.model.isNotBlank()) {
                appendLine("default_model = ${tomlString(config.model)}")
            }

            val effectiveKey =
                config.apiKey.ifBlank {
                    if (needsPlaceholderKey(resolvedProvider)) PLACEHOLDER_API_KEY else ""
                }
            if (effectiveKey.isNotBlank()) {
                appendLine("api_key = ${tomlString(effectiveKey)}")
            }

            if (config.compactContext) {
                appendLine()
                appendLine("[agent]")
                appendLine("compact_context = true")
            }

            appendRuntimeSection(config)

            appendGatewaySection(config)
            appendMemorySection(config)

            if (config.identityJson.isNotBlank()) {
                appendLine()
                appendLine("[identity]")
                appendLine("format = \"aieos\"")
                appendLine("aieos_inline = ${tomlString(config.identityJson)}")
            }

            if (config.costEnabled) {
                appendLine()
                appendLine("[cost]")
                appendLine("enabled = true")
                appendLine("daily_limit_usd = ${config.dailyLimitUsd}")
                appendLine("monthly_limit_usd = ${config.monthlyLimitUsd}")
                appendLine("warn_at_percent = ${config.costWarnAtPercent.coerceIn(0, GlobalTomlConfig.MAX_U8)}")
            }

            appendReliabilitySection(config)
            appendRoutingSection(config)
            appendAutonomySection(config)
            appendTunnelSection(config)
            appendSchedulerSection(config)
            appendHeartbeatSection(config)
            appendObservabilitySection(config)
            appendComposioSection(config)
            appendSharedFolderSection(config)
            appendBrowserSection(config)
            appendHttpRequestSection(config)
            appendTranscriptionSection(config)
            appendMultimodalSection(config)
            appendProxySection(config)
            appendWebFetchSection(config)
            appendWebSearchSection(config)
            appendTwitterBrowseSection(config)
            appendSecuritySandboxSection(config)
            appendSecurityResourcesSection(config)
            appendSecurityAuditSection(config)
            appendSecurityEstopSection(config)
            appendMemoryQdrantSection(config)
            appendSkillsSection(config)
            appendTtySection(config)

            if (config.emailEnabled && config.emailAddress.isNotBlank()) {
                appendLine()
                appendLine("[email]")
                appendLine("enabled = true")
                appendLine("imap_host = \"${config.emailImapHost}\"")
                appendLine("imap_port = ${config.emailImapPort}")
                appendLine("smtp_host = \"${config.emailSmtpHost}\"")
                appendLine("smtp_port = ${config.emailSmtpPort}")
                appendLine("address = \"${config.emailAddress}\"")
                appendLine("password = \"${config.emailPassword}\"")
                if (config.emailCheckTimes.isNotEmpty()) {
                    val times = config.emailCheckTimes.joinToString(", ") { "\"$it\"" }
                    appendLine("check_times = [$times]")
                }
                if (config.emailTimezone.isNotBlank()) {
                    appendLine("timezone = \"${config.emailTimezone}\"")
                }
            }

            config.hubAppContext?.let { ctx ->
                appendLine()
                appendLine("[system_prompt]")
                appendLine("hub_app_context = '''")
                appendLine(ctx)
                appendLine("'''")
            }
        }

    /**
     * Appends the `[runtime]` TOML section when an explicit reasoning effort is selected.
     *
     * Upstream field: `reasoning_effort`.
     *
     * @param config Configuration to read runtime values from.
     */
    private fun StringBuilder.appendRuntimeSection(config: GlobalTomlConfig) {
        val normalizedEffort = config.reasoningEffort.trim().lowercase()
        if (
            normalizedEffort.isEmpty() ||
            normalizedEffort == GlobalTomlConfig.REASONING_EFFORT_UNSET ||
            normalizedEffort !in GlobalTomlConfig.VALID_REASONING_EFFORTS
        ) {
            return
        }

        appendLine()
        appendLine("[runtime]")
        appendLine("reasoning_effort = ${tomlString(normalizedEffort)}")
    }

    /**
     * Appends the `[reliability]` TOML section when non-default values exist.
     *
     * @param config Configuration to read reliability values from.
     */
    private fun StringBuilder.appendReliabilitySection(config: GlobalTomlConfig) {
        val hasCustomRetries =
            config.providerRetries != GlobalTomlConfig.DEFAULT_RETRIES
        val hasFallbacks = config.fallbackProviders.isNotEmpty()
        val hasCustomBackoff =
            config.reliabilityBackoffMs != GlobalTomlConfig.DEFAULT_RELIABILITY_BACKOFF_MS
        val hasApiKeys = config.reliabilityApiKeysJson != "{}"
        val hasAnyReliability = hasCustomRetries || hasFallbacks || hasCustomBackoff || hasApiKeys
        if (!hasAnyReliability) return

        appendLine()
        appendLine("[reliability]")
        if (hasCustomRetries) {
            appendLine("provider_retries = ${config.providerRetries.coerceAtLeast(0)}")
        }
        if (hasFallbacks) {
            val list =
                config.fallbackProviders
                    .joinToString(", ") { tomlString(it) }
            appendLine("fallback_providers = [$list]")
        }
        if (hasCustomBackoff) {
            appendLine("provider_backoff_ms = ${config.reliabilityBackoffMs.coerceAtLeast(0L)}")
        }
        appendReliabilityApiKeys(config.reliabilityApiKeysJson)
    }

    /**
     * Appends the `[routing]` TOML section when any tier has configured providers.
     *
     * Upstream fields: `simple`, `complex`, `creative`, `tool_use` — each a
     * `Vec<String>` of provider names in preference order.
     *
     * @param config Configuration to read routing values from.
     */
    private fun StringBuilder.appendRoutingSection(config: GlobalTomlConfig) {
        val hasSimple = config.routingSimple.isNotEmpty()
        val hasComplex = config.routingComplex.isNotEmpty()
        val hasCreative = config.routingCreative.isNotEmpty()
        val hasToolUse = config.routingToolUse.isNotEmpty()
        val hasAnyRouting = hasSimple || hasComplex || hasCreative || hasToolUse
        if (!hasAnyRouting) return

        appendLine()
        appendLine("[routing]")
        if (hasSimple) {
            val list = config.routingSimple.joinToString(", ") { tomlString(it) }
            appendLine("simple = [$list]")
        }
        if (hasComplex) {
            val list = config.routingComplex.joinToString(", ") { tomlString(it) }
            appendLine("complex = [$list]")
        }
        if (hasCreative) {
            val list = config.routingCreative.joinToString(", ") { tomlString(it) }
            appendLine("creative = [$list]")
        }
        if (hasToolUse) {
            val list = config.routingToolUse.joinToString(", ") { tomlString(it) }
            appendLine("tool_use = [$list]")
        }
    }

    /**
     * Parses the reliability API keys JSON and appends the flat array.
     *
     * Upstream `api_keys` is `Vec<String>` — a flat list of keys for
     * round-robin rotation, not a provider-keyed map.
     *
     * @param json JSON object string mapping provider names to API keys.
     */
    private fun StringBuilder.appendReliabilityApiKeys(json: String) {
        if (json == "{}") return
        try {
            val keysObj = org.json.JSONObject(json)
            val keys = mutableListOf<String>()
            val iter = keysObj.keys()
            while (iter.hasNext()) {
                val key = keysObj.getString(iter.next())
                if (key.isNotBlank()) keys.add(key)
            }
            if (keys.isNotEmpty()) {
                val list = keys.joinToString(", ") { tomlString(it) }
                appendLine("api_keys = [$list]")
            }
        } catch (_: org.json.JSONException) {
            // Ignore malformed JSON
        }
    }

    /**
     * Appends the `[gateway]` TOML section with all gateway-related fields.
     *
     * Upstream fields: host, port, require_pairing, allow_public_bind,
     * paired_tokens, pair_rate_limit_per_minute, webhook_rate_limit_per_minute,
     * idempotency_ttl_secs (see `.claude/submodule-api-map.md` lines 349-358).
     *
     * @param config Configuration to read gateway values from.
     */
    private fun StringBuilder.appendGatewaySection(config: GlobalTomlConfig) {
        appendLine()
        appendLine("[gateway]")
        appendLine("host = ${tomlString(config.gatewayHost)}")
        appendLine("port = ${config.gatewayPort.coerceAtLeast(0)}")
        appendLine("require_pairing = ${config.gatewayRequirePairing}")
        appendLine("allow_public_bind = ${config.gatewayAllowPublicBind}")
        if (config.gatewayPairedTokens.isNotEmpty()) {
            val list = config.gatewayPairedTokens.joinToString(", ") { tomlString(it) }
            appendLine("paired_tokens = [$list]")
        }
        appendLine("pair_rate_limit_per_minute = ${config.gatewayPairRateLimit.coerceAtLeast(0)}")
        appendLine("webhook_rate_limit_per_minute = ${config.gatewayWebhookRateLimit.coerceAtLeast(0)}")
        appendLine("idempotency_ttl_secs = ${config.gatewayIdempotencyTtl.coerceAtLeast(0L)}")
    }

    /**
     * Appends the `[memory]` TOML section with backend and hygiene fields.
     *
     * Upstream fields: backend, auto_save, hygiene_enabled, archive_after_days,
     * purge_after_days, embedding_provider, embedding_model, vector_weight,
     * keyword_weight (see `.claude/submodule-api-map.md` lines 314-327).
     *
     * @param config Configuration to read memory values from.
     */
    private fun StringBuilder.appendMemorySection(config: GlobalTomlConfig) {
        appendLine()
        appendLine("[memory]")
        appendLine("backend = ${tomlString(config.memoryBackend)}")
        appendLine("auto_save = ${config.memoryAutoSave}")
        appendLine("hygiene_enabled = ${config.memoryHygieneEnabled}")
        appendLine("archive_after_days = ${config.memoryArchiveAfterDays.coerceAtLeast(0)}")
        appendLine("purge_after_days = ${config.memoryPurgeAfterDays.coerceAtLeast(0)}")
        if (config.memoryEmbeddingProvider != "none") {
            appendLine("embedding_provider = ${tomlString(config.memoryEmbeddingProvider)}")
            if (config.memoryEmbeddingModel.isNotBlank()) {
                appendLine("embedding_model = ${tomlString(config.memoryEmbeddingModel)}")
            }
        }
        appendLine("vector_weight = ${config.memoryVectorWeight}")
        appendLine("keyword_weight = ${config.memoryKeywordWeight}")
    }

    /**
     * Appends the `[autonomy]` TOML section.
     *
     * Upstream fields: level, workspace_only, allowed_commands, forbidden_paths,
     * max_actions_per_hour, max_cost_per_day_cents, require_approval_for_medium_risk,
     * block_high_risk_commands (see `.claude/submodule-api-map.md` lines 258-266).
     *
     * @param config Configuration to read autonomy values from.
     */
    private fun StringBuilder.appendAutonomySection(config: GlobalTomlConfig) {
        val level = config.autonomyLevel
        require(level in GlobalTomlConfig.VALID_AUTONOMY_LEVELS) {
            "Invalid autonomy level '$level': must be one of ${GlobalTomlConfig.VALID_AUTONOMY_LEVELS}"
        }
        appendLine()
        appendLine("[autonomy]")
        appendLine("level = ${tomlString(level)}")
        appendLine("workspace_only = ${config.workspaceOnly}")
        val cmdList =
            if (config.allowedCommands.isEmpty()) {
                "[]"
            } else {
                "[${config.allowedCommands.joinToString(", ") { tomlString(it) }}]"
            }
        appendLine("allowed_commands = $cmdList")
        val pathList =
            if (config.forbiddenPaths.isEmpty()) {
                "[]"
            } else {
                "[${config.forbiddenPaths.joinToString(", ") { tomlString(it) }}]"
            }
        appendLine("forbidden_paths = $pathList")
        appendLine("max_actions_per_hour = ${config.maxActionsPerHour.coerceAtLeast(0)}")
        appendLine("max_cost_per_day_cents = ${config.maxCostPerDayCents.coerceAtLeast(0)}")
        appendLine("require_approval_for_medium_risk = ${config.requireApprovalMediumRisk}")
        appendLine("block_high_risk_commands = ${config.blockHighRiskCommands}")
    }

    /**
     * Appends the `[tunnel]` TOML section when Tailscale is configured.
     *
     * Upstream fields: provider, tailscale.funnel/hostname.
     *
     * @param config Configuration to read tunnel values from.
     */
    private fun StringBuilder.appendTunnelSection(config: GlobalTomlConfig) {
        if (config.tunnelProvider != "tailscale") return
        appendLine()
        appendLine("[tunnel]")
        appendLine("provider = ${tomlString(config.tunnelProvider)}")
        appendLine("[tunnel.tailscale]")
        appendLine("funnel = ${config.tunnelTailscaleFunnel}")
        if (config.tunnelTailscaleHostname.isNotBlank()) {
            appendLine("hostname = ${tomlString(config.tunnelTailscaleHostname)}")
        }
    }

    /**
     * Appends the `[scheduler]` TOML section.
     *
     * Upstream fields: enabled, max_tasks, max_concurrent
     * (see `.claude/submodule-api-map.md` lines 299-303).
     *
     * @param config Configuration to read scheduler values from.
     */
    private fun StringBuilder.appendSchedulerSection(config: GlobalTomlConfig) {
        appendLine()
        appendLine("[scheduler]")
        appendLine("enabled = ${config.schedulerEnabled}")
        appendLine("max_tasks = ${config.schedulerMaxTasks.coerceAtLeast(0L)}")
        appendLine("max_concurrent = ${config.schedulerMaxConcurrent.coerceAtLeast(0L)}")
    }

    /**
     * Appends the `[heartbeat]` TOML section.
     *
     * Upstream fields: enabled, interval_minutes
     * (see `.claude/submodule-api-map.md` lines 306-310).
     *
     * @param config Configuration to read heartbeat values from.
     */
    private fun StringBuilder.appendHeartbeatSection(config: GlobalTomlConfig) {
        appendLine()
        appendLine("[heartbeat]")
        appendLine("enabled = ${config.heartbeatEnabled}")
        appendLine("interval_minutes = ${config.heartbeatIntervalMinutes.coerceAtLeast(0L)}")
    }

    /**
     * Appends the `[observability]` TOML section.
     *
     * Upstream fields: backend, otel_endpoint, otel_service_name
     * (see `.claude/submodule-api-map.md` lines 250-253).
     *
     * @param config Configuration to read observability values from.
     */
    private fun StringBuilder.appendObservabilitySection(config: GlobalTomlConfig) {
        appendLine()
        appendLine("[observability]")
        appendLine("backend = ${tomlString(config.observabilityBackend)}")
        if (config.observabilityBackend == "otel") {
            if (config.observabilityOtelEndpoint.isNotBlank()) {
                appendLine("otel_endpoint = ${tomlString(config.observabilityOtelEndpoint)}")
            }
            appendLine("otel_service_name = ${tomlString(config.observabilityOtelServiceName)}")
        }
    }

    /**
     * Appends the `[composio]` TOML section when Composio is enabled.
     *
     * Upstream fields: enabled, api_key, entity_id
     * (see `.claude/submodule-api-map.md` lines 363-367).
     *
     * @param config Configuration to read Composio values from.
     */
    private fun StringBuilder.appendComposioSection(config: GlobalTomlConfig) {
        if (!config.composioEnabled) return
        appendLine()
        appendLine("[composio]")
        appendLine("enabled = true")
        if (config.composioApiKey.isNotBlank()) {
            appendLine("api_key = ${tomlString(config.composioApiKey)}")
        }
        appendLine("entity_id = ${tomlString(config.composioEntityId)}")
    }

    /**
     * Emits the `[shared_folder]` TOML section.
     *
     * Contains only an `enabled` flag. Actual file I/O is performed in
     * Kotlin via SAF; the TOML section signals the Rust tool registry
     * to register the shim tools.
     */
    private fun StringBuilder.appendSharedFolderSection(config: GlobalTomlConfig) {
        if (!config.sharedFolderEnabled) return
        appendLine()
        appendLine("[shared_folder]")
        appendLine("enabled = true")
    }

    /**
     * Appends the `[browser]` TOML section when the browser tool is enabled.
     *
     * Upstream fields: enabled, allowed_domains
     * (see `.claude/submodule-api-map.md` lines 377-379).
     *
     * @param config Configuration to read browser values from.
     */
    private fun StringBuilder.appendBrowserSection(config: GlobalTomlConfig) {
        if (!config.browserEnabled) return
        appendLine()
        appendLine("[browser]")
        appendLine("enabled = true")
        if (config.browserAllowedDomains.isNotEmpty()) {
            val list = config.browserAllowedDomains.joinToString(", ") { tomlString(it) }
            appendLine("allowed_domains = [$list]")
        }
    }

    /**
     * Appends the `[http_request]` TOML section.
     *
     * Always emits the section so the Rust layer always sees an explicit enabled flag.
     * With an empty [allowed_domains] list the tool rejects all requests safely; skills
     * auto-populate domains at runtime.
     *
     * Upstream fields: enabled, allowed_domains, max_response_size, timeout_secs.
     *
     * @param config Configuration to read HTTP request values from.
     */
    private fun StringBuilder.appendHttpRequestSection(config: GlobalTomlConfig) {
        appendLine()
        appendLine("[http_request]")
        appendLine("enabled = ${config.httpRequestEnabled}")
        if (config.httpRequestAllowedDomains.isNotEmpty()) {
            val list = config.httpRequestAllowedDomains.joinToString(", ") { tomlString(it) }
            appendLine("allowed_domains = [$list]")
        }
        if (config.httpRequestMaxResponseSize != GlobalTomlConfig.DEFAULT_HTTP_REQUEST_MAX_RESPONSE_SIZE) {
            appendLine("max_response_size = ${config.httpRequestMaxResponseSize.coerceAtLeast(0L)}")
        }
        if (config.httpRequestTimeoutSecs != GlobalTomlConfig.DEFAULT_HTTP_REQUEST_TIMEOUT_SECS) {
            appendLine("timeout_secs = ${config.httpRequestTimeoutSecs.coerceAtLeast(0L)}")
        }
    }

    /**
     * Appends the `[transcription]` TOML section when transcription is enabled.
     *
     * Upstream fields: enabled, api_url, model, language, max_duration_secs.
     *
     * @param config Configuration to read transcription values from.
     */
    private fun StringBuilder.appendTranscriptionSection(config: GlobalTomlConfig) {
        if (!config.transcriptionEnabled) return
        appendLine()
        appendLine("[transcription]")
        appendLine("enabled = true")
        appendLine("api_url = ${tomlString(config.transcriptionApiUrl)}")
        appendLine("model = ${tomlString(config.transcriptionModel)}")
        if (config.transcriptionLanguage.isNotBlank()) {
            appendLine("language = ${tomlString(config.transcriptionLanguage)}")
        }
        appendLine("max_duration_secs = ${config.transcriptionMaxDurationSecs.coerceAtLeast(0L)}")
    }

    /**
     * Appends the `[multimodal]` TOML section when non-default values exist.
     *
     * Upstream fields: max_images, max_image_size_mb, allow_remote_fetch.
     *
     * @param config Configuration to read multimodal values from.
     */
    private fun StringBuilder.appendMultimodalSection(config: GlobalTomlConfig) {
        val hasNonDefault =
            config.multimodalMaxImages != GlobalTomlConfig.DEFAULT_MULTIMODAL_MAX_IMAGES ||
                config.multimodalMaxImageSizeMb != GlobalTomlConfig.DEFAULT_MULTIMODAL_MAX_SIZE_MB ||
                config.multimodalAllowRemoteFetch
        if (!hasNonDefault) return
        appendLine()
        appendLine("[multimodal]")
        appendLine("max_images = ${config.multimodalMaxImages.coerceAtLeast(0)}")
        appendLine("max_image_size_mb = ${config.multimodalMaxImageSizeMb.coerceAtLeast(0)}")
        appendLine("allow_remote_fetch = ${config.multimodalAllowRemoteFetch}")
    }

    /**
     * Appends the `[proxy]` TOML section when proxy is enabled.
     *
     * Upstream fields: enabled, http_proxy, https_proxy, no_proxy,
     * all_proxy, scope, services.
     *
     * @param config Configuration to read proxy values from.
     */
    private fun StringBuilder.appendProxySection(config: GlobalTomlConfig) {
        if (!config.proxyEnabled) return
        appendLine()
        appendLine("[proxy]")
        appendLine("enabled = true")
        if (config.proxyHttpProxy.isNotBlank()) {
            appendLine("http_proxy = ${tomlString(config.proxyHttpProxy)}")
        }
        if (config.proxyHttpsProxy.isNotBlank()) {
            appendLine("https_proxy = ${tomlString(config.proxyHttpsProxy)}")
        }
        if (config.proxyNoProxy.isNotEmpty()) {
            val list = config.proxyNoProxy.joinToString(", ") { tomlString(it) }
            appendLine("no_proxy = [$list]")
        }
        if (config.proxyAllProxy.isNotBlank()) {
            appendLine("all_proxy = ${tomlString(config.proxyAllProxy)}")
        }
        if (config.proxyScope != "zeroclaw") {
            appendLine("scope = ${tomlString(config.proxyScope)}")
        }
        if (config.proxyServiceSelectors.isNotEmpty()) {
            val list = config.proxyServiceSelectors.joinToString(", ") { tomlString(it) }
            appendLine("services = [$list]")
        }
    }

    /**
     * Appends the `[web_fetch]` TOML section when web fetch is enabled.
     *
     * Upstream fields: enabled, allowed_domains, blocked_domains,
     * max_response_size, timeout_secs.
     *
     * The upstream daemon's struct-level default for `allowed_domains` is
     * `["*"]` (all public hosts), but `#[serde(default)]` on the field
     * yields an empty vec when the section is present without the key.
     * Empty allowlist = deny all, so we must always emit the field.
     *
     * @param config Configuration to read web fetch values from.
     */
    private fun StringBuilder.appendWebFetchSection(config: GlobalTomlConfig) {
        if (!config.webFetchEnabled) return
        appendLine()
        appendLine("[web_fetch]")
        appendLine("enabled = true")
        if (config.webFetchAllowedDomains.isNotEmpty()) {
            val list = config.webFetchAllowedDomains.joinToString(", ") { tomlString(it) }
            appendLine("allowed_domains = [$list]")
        } else {
            appendLine("""allowed_domains = ["*"]""")
        }
        if (config.webFetchBlockedDomains.isNotEmpty()) {
            val list = config.webFetchBlockedDomains.joinToString(", ") { tomlString(it) }
            appendLine("blocked_domains = [$list]")
        }
        if (config.webFetchMaxResponseSize != GlobalTomlConfig.DEFAULT_WEB_FETCH_MAX_RESPONSE_SIZE) {
            appendLine("max_response_size = ${config.webFetchMaxResponseSize.coerceAtLeast(0L)}")
        }
        if (config.webFetchTimeoutSecs != GlobalTomlConfig.DEFAULT_WEB_FETCH_TIMEOUT_SECS) {
            appendLine("timeout_secs = ${config.webFetchTimeoutSecs.coerceAtLeast(0L)}")
        }
    }

    /**
     * Appends the `[web_search]` TOML section when web search is enabled.
     *
     * Upstream fields: enabled, provider, brave_api_key, google_api_key,
     * google_cx, max_results, timeout_secs.
     *
     * @param config Configuration to read web search values from.
     */
    private fun StringBuilder.appendWebSearchSection(config: GlobalTomlConfig) {
        if (!config.webSearchEnabled) return
        appendLine()
        appendLine("[web_search]")
        appendLine("enabled = true")
        appendLine("provider = ${tomlString(config.webSearchProvider)}")
        if (config.webSearchBraveApiKey.isNotBlank()) {
            appendLine("brave_api_key = ${tomlString(config.webSearchBraveApiKey)}")
        }
        if (config.webSearchGoogleApiKey.isNotBlank()) {
            appendLine("google_api_key = ${tomlString(config.webSearchGoogleApiKey)}")
        }
        if (config.webSearchGoogleCx.isNotBlank()) {
            appendLine("google_cx = ${tomlString(config.webSearchGoogleCx)}")
        }
        if (config.webSearchMaxResults != GlobalTomlConfig.DEFAULT_WEB_SEARCH_MAX_RESULTS) {
            appendLine("max_results = ${config.webSearchMaxResults.coerceAtLeast(0L)}")
        }
        if (config.webSearchTimeoutSecs != GlobalTomlConfig.DEFAULT_WEB_SEARCH_TIMEOUT_SECS) {
            appendLine("timeout_secs = ${config.webSearchTimeoutSecs.coerceAtLeast(0L)}")
        }
    }

    /**
     * Appends the `[twitter_browse]` TOML section when Twitter/X browsing is enabled.
     *
     * Upstream fields: enabled, cookie_string, max_items, timeout_secs.
     *
     * @param config Configuration to read Twitter/X browse values from.
     */
    private fun StringBuilder.appendTwitterBrowseSection(config: GlobalTomlConfig) {
        if (!config.twitterBrowseEnabled) return
        appendLine()
        appendLine("[twitter_browse]")
        appendLine("enabled = true")
        if (config.twitterBrowseCookieString.isNotBlank()) {
            appendLine("cookie_string = ${tomlString(config.twitterBrowseCookieString)}")
        }
        if (config.twitterBrowseMaxItems != GlobalTomlConfig.DEFAULT_TWITTER_BROWSE_MAX_ITEMS) {
            appendLine("max_items = ${config.twitterBrowseMaxItems.coerceAtLeast(0L)}")
        }
        if (config.twitterBrowseTimeoutSecs != GlobalTomlConfig.DEFAULT_TWITTER_BROWSE_TIMEOUT_SECS) {
            appendLine("timeout_secs = ${config.twitterBrowseTimeoutSecs.coerceAtLeast(0L)}")
        }
    }

    /**
     * Appends the `[security.sandbox]` TOML section when non-default values exist.
     *
     * Upstream fields: enabled, backend, firejail_args.
     *
     * @param config Configuration to read sandbox values from.
     */
    private fun StringBuilder.appendSecuritySandboxSection(config: GlobalTomlConfig) {
        val hasEnabled = config.securitySandboxEnabled != null
        val hasBackend = config.securitySandboxBackend != "auto"
        val hasArgs = config.securitySandboxFirejailArgs.isNotEmpty()
        if (!hasEnabled && !hasBackend && !hasArgs) return

        appendLine()
        appendLine("[security.sandbox]")
        if (hasEnabled) {
            appendLine("enabled = ${config.securitySandboxEnabled}")
        }
        if (hasBackend) {
            appendLine("backend = ${tomlString(config.securitySandboxBackend)}")
        }
        if (hasArgs) {
            val list = config.securitySandboxFirejailArgs.joinToString(", ") { tomlString(it) }
            appendLine("firejail_args = [$list]")
        }
    }

    /**
     * Appends the `[security.resources]` TOML section when non-default values exist.
     *
     * Upstream fields: max_memory_mb, max_cpu_time_seconds, max_subprocesses,
     * memory_monitoring.
     *
     * @param config Configuration to read resource limit values from.
     */
    private fun StringBuilder.appendSecurityResourcesSection(config: GlobalTomlConfig) {
        val hasCustomMemory =
            config.securityResourcesMaxMemoryMb != GlobalTomlConfig.DEFAULT_RESOURCES_MAX_MEMORY_MB
        val hasCustomCpu =
            config.securityResourcesMaxCpuTimeSecs != GlobalTomlConfig.DEFAULT_RESOURCES_MAX_CPU_TIME_SECS
        val hasCustomSubproc =
            config.securityResourcesMaxSubprocesses != GlobalTomlConfig.DEFAULT_RESOURCES_MAX_SUBPROCESSES
        val hasCustomMonitoring = !config.securityResourcesMemoryMonitoring
        val hasAnyCustomResource =
            hasCustomMemory || hasCustomCpu || hasCustomSubproc || hasCustomMonitoring
        if (!hasAnyCustomResource) return

        appendLine()
        appendLine("[security.resources]")
        appendLine("max_memory_mb = ${config.securityResourcesMaxMemoryMb.coerceAtLeast(0)}")
        appendLine("max_cpu_time_seconds = ${config.securityResourcesMaxCpuTimeSecs.coerceAtLeast(0L)}")
        appendLine("max_subprocesses = ${config.securityResourcesMaxSubprocesses.coerceAtLeast(0)}")
        appendLine("memory_monitoring = ${config.securityResourcesMemoryMonitoring}")
    }

    /**
     * Appends the `[security.audit]` TOML section.
     *
     * Always emits the full section so that `log_path`, `max_size_mb`, and
     * `sign_events` are explicitly set rather than relying on upstream
     * defaults (which assume `~` home-directory expansion unavailable on
     * Android).
     *
     * Upstream fields: enabled, log_path, max_size_mb, sign_events.
     *
     * @param config Configuration to read audit values from.
     */
    private fun StringBuilder.appendSecurityAuditSection(config: GlobalTomlConfig) {
        appendLine()
        appendLine("[security.audit]")
        appendLine("enabled = ${config.securityAuditEnabled}")
        appendLine("log_path = ${tomlString(config.securityAuditLogPath)}")
        appendLine("max_size_mb = ${config.securityAuditMaxSizeMb.coerceAtLeast(0)}")
        appendLine("sign_events = ${config.securityAuditSignEvents}")
    }

    /**
     * Appends the `[security.estop]` TOML section when emergency stop is enabled.
     *
     * The `state_file` field is always emitted because upstream `EstopConfig`
     * uses `deny_unknown_fields` and defaults to `~/.zeroclaw/estop-state.json`,
     * which won't resolve on Android (Rust's `std::fs` does not expand `~`).
     * The Android service sets this to an absolute path under `filesDir`.
     *
     * Upstream fields: enabled, state_file, require_otp_to_resume.
     *
     * @param config Configuration to read e-stop values from.
     */
    private fun StringBuilder.appendSecurityEstopSection(config: GlobalTomlConfig) {
        if (!config.securityEstopEnabled) return

        appendLine()
        appendLine("[security.estop]")
        appendLine("enabled = true")
        appendLine("state_file = ${tomlString(config.securityEstopStateFile)}")
        appendLine("require_otp_to_resume = ${config.securityEstopRequireOtpToResume}")
    }

    /**
     * Appends the `[memory.qdrant]` TOML section when Qdrant is configured.
     *
     * Upstream fields: url, collection, api_key.
     *
     * @param config Configuration to read Qdrant memory values from.
     */
    private fun StringBuilder.appendMemoryQdrantSection(config: GlobalTomlConfig) {
        if (config.memoryQdrantUrl.isBlank() && config.memoryQdrantApiKey.isBlank()) return
        appendLine()
        appendLine("[memory.qdrant]")
        if (config.memoryQdrantUrl.isNotBlank()) {
            appendLine("url = ${tomlString(config.memoryQdrantUrl)}")
        }
        appendLine("collection = ${tomlString(config.memoryQdrantCollection)}")
        if (config.memoryQdrantApiKey.isNotBlank()) {
            appendLine("api_key = ${tomlString(config.memoryQdrantApiKey)}")
        }
    }

    /**
     * Appends the `[skills]` TOML section when non-default values exist.
     *
     * Upstream fields: prompt_injection_mode.
     *
     * @param config Configuration to read skills values from.
     */
    private fun StringBuilder.appendSkillsSection(config: GlobalTomlConfig) {
        val hasNonDefault = config.skillsPromptInjectionMode != "full"
        if (!hasNonDefault) return

        appendLine()
        appendLine("[skills]")
        if (config.skillsPromptInjectionMode != "full") {
            appendLine(
                "prompt_injection_mode = ${tomlString(config.skillsPromptInjectionMode)}",
            )
        }
    }

    /**
     * Appends the `[tty]` TOML section for the terminal backend.
     *
     * Emits `enabled`, `ssh_keepalive_secs`, and `context_max_bytes` fields.
     * The section is always emitted so the Rust daemon can read the TTY
     * configuration regardless of whether it is currently active.
     *
     * @param config Configuration to read TTY values from.
     */
    private fun StringBuilder.appendTtySection(config: GlobalTomlConfig) {
        appendLine()
        appendLine("[tty]")
        appendLine("enabled = ${config.ttyEnabled}")
        appendLine("ssh_keepalive_secs = ${config.ttySshKeepaliveSecs.coerceAtLeast(0)}")
        appendLine("context_max_bytes = ${config.ttyContextMaxBytes.coerceAtLeast(0)}")
    }

    /**
     * Builds the `[channels_config]` TOML section from enabled channels.
     *
     * The CLI channel is disabled (`cli = false`) because the Android app
     * uses the FFI bridge for direct messaging instead of stdin/stdout.
     *
     * @param channelsWithSecrets List of pairs: (channel, all config values including secrets).
     * @param discordGuildId Optional guild snowflake ID to emit as `guild_id` in the
     *   Discord section. When null or blank the field is omitted and the Rust guild
     *   filter will reject all server messages.
     * @return TOML string for the channels_config section, or empty if no channels.
     */
    fun buildChannelsToml(
        channelsWithSecrets: List<Pair<ConnectedChannel, Map<String, String>>>,
        discordGuildId: String? = null,
    ): String {
        if (channelsWithSecrets.isEmpty()) return ""
        return buildString {
            appendLine()
            appendLine("[channels_config]")
            appendLine("cli = false")

            for ((channel, values) in channelsWithSecrets) {
                appendLine()
                appendLine("[channels_config.${channel.type.tomlKey}]")
                for (spec in channel.type.fields) {
                    val value = values[spec.key].orEmpty()
                    if (value.isBlank() && !spec.isRequired) continue
                    appendTomlField(spec.key, value, spec.inputType)
                }
                if (channel.type == ChannelType.TELEGRAM) {
                    appendLine("stream_mode = \"partial\"")
                    appendLine("draft_update_interval_ms = 1000")
                    appendLine("interrupt_on_new_message = true")
                }
                if (channel.type == ChannelType.DISCORD && !discordGuildId.isNullOrBlank()) {
                    appendLine("guild_id = \"$discordGuildId\"")
                }
            }
        }
    }

    /**
     * Builds `[agents.<name>]` TOML sections for per-agent provider configuration.
     *
     * The upstream [DelegateAgentConfig] struct supports `provider`, `model`,
     * `system_prompt`, and `api_key` fields per agent. Only non-blank optional
     * fields are emitted.
     *
     * @param agents Resolved agent entries to serialize.
     * @return TOML string with one `[agents.<name>]` section per entry,
     *   or empty if [agents] is empty.
     */
    @Suppress("CognitiveComplexMethod")
    fun buildAgentsToml(agents: List<AgentTomlEntry>): String {
        if (agents.isEmpty()) return ""
        return buildString {
            for (entry in agents) {
                appendLine()
                appendLine("[agents.${tomlKey(entry.name)}]")
                appendLine("provider = ${tomlString(entry.provider)}")
                appendLine("model = ${tomlString(entry.model)}")
                if (entry.systemPrompt.isNotBlank()) {
                    appendLine("system_prompt = ${tomlString(entry.systemPrompt)}")
                }
                val effectiveKey =
                    entry.apiKey.ifBlank {
                        if (needsPlaceholderKey(entry.provider)) PLACEHOLDER_API_KEY else ""
                    }
                if (effectiveKey.isNotBlank()) {
                    appendLine("api_key = ${tomlString(effectiveKey)}")
                }
                if (entry.temperature != null) {
                    appendLine("temperature = ${entry.temperature}")
                }
                if (entry.maxDepth != Agent.DEFAULT_MAX_DEPTH) {
                    appendLine("max_depth = ${entry.maxDepth.coerceAtLeast(0)}")
                }
            }
        }
    }

    /**
     * Appends a single TOML field with the appropriate value format.
     *
     * @param key TOML field key.
     * @param value Raw string value from the UI.
     * @param inputType Field input type determining the TOML format.
     */
    private fun StringBuilder.appendTomlField(
        key: String,
        value: String,
        inputType: FieldInputType,
    ) {
        when (inputType) {
            FieldInputType.NUMBER -> appendLine("$key = ${value.ifBlank { "0" }}")
            FieldInputType.BOOLEAN -> appendLine("$key = ${value.lowercase()}")
            FieldInputType.LIST -> {
                val items =
                    value
                        .split(",")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .joinToString(", ") { tomlString(it) }
                appendLine("$key = [$items]")
            }
            else -> appendLine("$key = ${tomlString(value)}")
        }
    }

    /**
     * Maps an Android provider ID and optional base URL to the upstream
     * Rust factory provider name.
     *
     * @param provider Android provider ID.
     * @param baseUrl Optional endpoint URL.
     * @return The resolved provider string for the TOML, or blank if
     *   [provider] is blank.
     */
    internal fun resolveProvider(
        provider: String,
        baseUrl: String,
    ): String {
        if (provider.isBlank()) return ""

        val trimmedUrl = baseUrl.trim()

        if (provider == "custom-anthropic" && trimmedUrl.isNotEmpty()) {
            return "anthropic-custom:$trimmedUrl"
        }

        if (provider in OPENAI_COMPATIBLE_SELF_HOSTED && trimmedUrl.isNotEmpty()) {
            return "custom:$trimmedUrl"
        }

        if (provider == "ollama" && trimmedUrl.isNotEmpty() && trimmedUrl != OLLAMA_DEFAULT_URL) {
            return "custom:$trimmedUrl"
        }

        val chinaSensitiveProviders =
            setOf(
                "deepseek",
                "qwen",
                "qwen-cn",
                "qwen-us",
                "dashscope",
                "dashscope-cn",
                "dashscope-us",
            )
        require(
            provider !in chinaSensitiveProviders ||
                trimmedUrl.isEmpty() ||
                trimmedUrl.startsWith("https://"),
        ) {
            "Base URL for $provider must use HTTPS to protect API credentials"
        }

        return provider
    }

    /**
     * Returns true if the resolved provider requires a placeholder API key.
     *
     * The upstream [OpenAiCompatibleProvider] unconditionally demands
     * `api_key` to be non-null. Self-hosted servers (LM Studio, vLLM,
     * LocalAI, Ollama) don't need authentication, but the provider
     * factory still needs *some* value to avoid a "key not set" error.
     *
     * @param resolvedProvider The resolved TOML provider string.
     * @return True if [PLACEHOLDER_API_KEY] should be injected.
     */
    internal fun needsPlaceholderKey(resolvedProvider: String): Boolean = resolvedProvider.startsWith("custom:") || resolvedProvider == "ollama"

    /**
     * Formats a value as a quoted TOML key.
     *
     * Bare keys may only contain ASCII letters, digits, dashes, and underscores.
     * Keys containing any other characters (spaces, dots, etc.) must be quoted.
     *
     * @param key Raw key value.
     * @return The key suitable for use in a TOML table header or dotted key.
     */
    private fun tomlKey(key: String): String {
        val isBareKey =
            key.isNotEmpty() && key.all { it.isLetterOrDigit() || it == '-' || it == '_' }
        return if (isBareKey) key else tomlString(key)
    }

    internal fun tomlString(value: String): String =
        buildString {
            append('"')
            for (ch in value) {
                when {
                    ch == '\\' -> append("\\\\")
                    ch == '"' -> append("\\\"")
                    ch == '\n' -> append("\\n")
                    ch == '\r' -> append("\\r")
                    ch == '\t' -> append("\\t")
                    ch == '\b' -> append("\\b")
                    ch == '\u000C' -> append("\\f")
                    ch.code in CONTROL_RANGE_START..CONTROL_RANGE_END ||
                        ch.code == DELETE_CHAR -> {
                        append("\\u")
                        append(
                            ch.code
                                .toString(HEX_RADIX)
                                .padStart(UNICODE_PAD_LENGTH, '0'),
                        )
                    }
                    else -> append(ch)
                }
            }
            append('"')
        }

    /** Radix for hexadecimal encoding. */
    private const val HEX_RADIX = 16

    /** Pad length for Unicode escape sequences. */
    private const val UNICODE_PAD_LENGTH = 4

    /** Start of the C0 control character range. */
    private const val CONTROL_RANGE_START = 0x00

    /** End of the C0 control character range. */
    private const val CONTROL_RANGE_END = 0x1F

    /** ASCII DEL character code. */
    private const val DELETE_CHAR = 0x7F

    /** Maximum TCP port number. */
    private const val MAX_TCP_PORT = 65535

    /**
     * Builds the TOML representation of tailscale peer agent entries.
     *
     * Emits only `[[tailscale_peers.entries]]` blocks without a bare
     * `[tailscale_peers]` header. Returns empty string when list is empty.
     *
     * @param peers List of peer configurations to serialize.
     * @return TOML string fragment, or empty string if no peers.
     */
    fun buildTailscalePeersToml(peers: List<PeerTomlEntry>): String {
        if (peers.isEmpty()) return ""

        return buildString {
            for (peer in peers) {
                appendLine("[[tailscale_peers.entries]]")
                appendLine("ip = \"${peer.ip}\"")
                appendLine("hostname = \"${peer.hostname}\"")
                appendLine("kind = \"${peer.kind}\"")
                appendLine("port = ${peer.port.coerceIn(0, MAX_TCP_PORT)}")
                appendLine("alias = \"${peer.alias}\"")
                appendLine("auth_required = ${peer.authRequired}")
                appendLine("enabled = ${peer.enabled}")
                appendLine()
            }
        }
    }
}
