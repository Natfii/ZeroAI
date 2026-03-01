/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data.repository

import com.zeroclaw.android.model.AppSettings
import com.zeroclaw.android.model.ThemeMode
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for reading and writing application settings.
 *
 * Implementations must provide a [Flow] of [AppSettings] that emits
 * the current settings and any subsequent changes.
 */
@Suppress("TooManyFunctions")
interface SettingsRepository {
    /** Observable stream of the current application settings. */
    val settings: Flow<AppSettings>

    /**
     * Updates the gateway host address.
     *
     * @param host New bind address.
     */
    suspend fun setHost(host: String)

    /**
     * Updates the gateway port.
     *
     * @param port New bind port.
     */
    suspend fun setPort(port: Int)

    /**
     * Toggles the auto-start on boot setting.
     *
     * @param enabled Whether to start the daemon on boot.
     */
    suspend fun setAutoStartOnBoot(enabled: Boolean)

    /**
     * Updates the default provider for new agents.
     *
     * @param provider Provider ID (e.g. "openai", "anthropic").
     */
    suspend fun setDefaultProvider(provider: String)

    /**
     * Updates the default model for new agents.
     *
     * @param model Model name (e.g. "gpt-4o").
     */
    suspend fun setDefaultModel(model: String)

    /**
     * Updates the default inference temperature.
     *
     * @param temperature Temperature value (0.0–2.0).
     */
    suspend fun setDefaultTemperature(temperature: Float)

    /**
     * Toggles compact context mode.
     *
     * @param enabled Whether compact context is active.
     */
    suspend fun setCompactContext(enabled: Boolean)

    /**
     * Toggles cost limit enforcement.
     *
     * @param enabled Whether spending limits are enforced.
     */
    suspend fun setCostEnabled(enabled: Boolean)

    /**
     * Updates the daily cost limit.
     *
     * @param limit Maximum daily spend in USD.
     */
    suspend fun setDailyLimitUsd(limit: Float)

    /**
     * Updates the monthly cost limit.
     *
     * @param limit Maximum monthly spend in USD.
     */
    suspend fun setMonthlyLimitUsd(limit: Float)

    /**
     * Updates the cost warning threshold percentage.
     *
     * @param percent Percentage of limit at which to warn.
     */
    suspend fun setCostWarnAtPercent(percent: Int)

    /**
     * Updates the number of provider retries before fallback.
     *
     * @param retries Retry count.
     */
    suspend fun setProviderRetries(retries: Int)

    /**
     * Updates the comma-separated list of fallback providers.
     *
     * @param providers Comma-separated provider IDs.
     */
    suspend fun setFallbackProviders(providers: String)

    /**
     * Updates the memory backend.
     *
     * @param backend Backend name ("sqlite", "none", "markdown", "lucid").
     */
    suspend fun setMemoryBackend(backend: String)

    /**
     * Toggles the memory auto-save setting.
     *
     * @param enabled Whether the memory backend auto-saves conversation context.
     */
    suspend fun setMemoryAutoSave(enabled: Boolean)

    /**
     * Updates the AIEOS identity JSON blob.
     *
     * @param json AIEOS v1.1 JSON string.
     */
    suspend fun setIdentityJson(json: String)

    /**
     * Updates the autonomy level.
     *
     * @param level One of "readonly", "supervised", or "full".
     */
    suspend fun setAutonomyLevel(level: String)

    /**
     * Toggles workspace-only restriction.
     *
     * @param enabled Whether file access is restricted to the workspace.
     */
    suspend fun setWorkspaceOnly(enabled: Boolean)

    /**
     * Updates the allowed shell commands list.
     *
     * @param commands Comma-separated command names.
     */
    suspend fun setAllowedCommands(commands: String)

    /**
     * Updates the forbidden filesystem paths list.
     *
     * @param paths Comma-separated paths.
     */
    suspend fun setForbiddenPaths(paths: String)

    /**
     * Updates the maximum agent actions per hour.
     *
     * @param max Actions per hour limit.
     */
    suspend fun setMaxActionsPerHour(max: Int)

    /**
     * Updates the maximum daily cost in cents.
     *
     * @param cents Daily cost cap.
     */
    suspend fun setMaxCostPerDayCents(cents: Int)

    /**
     * Toggles approval requirement for medium-risk actions.
     *
     * @param required Whether approval is needed.
     */
    suspend fun setRequireApprovalMediumRisk(required: Boolean)

    /**
     * Toggles blocking of high-risk commands.
     *
     * @param blocked Whether high-risk commands are blocked.
     */
    suspend fun setBlockHighRiskCommands(blocked: Boolean)

    /**
     * Updates the tunnel provider.
     *
     * @param provider One of "none", "cloudflare", "tailscale", "ngrok", "custom".
     */
    suspend fun setTunnelProvider(provider: String)

    /**
     * Updates the Cloudflare tunnel token.
     *
     * @param token Authentication token.
     */
    suspend fun setTunnelCloudflareToken(token: String)

    /**
     * Toggles Tailscale Funnel.
     *
     * @param enabled Whether Funnel is active.
     */
    suspend fun setTunnelTailscaleFunnel(enabled: Boolean)

    /**
     * Updates the Tailscale hostname.
     *
     * @param hostname Custom hostname.
     */
    suspend fun setTunnelTailscaleHostname(hostname: String)

    /**
     * Updates the ngrok auth token.
     *
     * @param token Authentication token.
     */
    suspend fun setTunnelNgrokAuthToken(token: String)

    /**
     * Updates the ngrok domain.
     *
     * @param domain Custom domain.
     */
    suspend fun setTunnelNgrokDomain(domain: String)

    /**
     * Updates the custom tunnel start command.
     *
     * @param command Shell command to start the tunnel.
     */
    suspend fun setTunnelCustomCommand(command: String)

    /**
     * Updates the custom tunnel health URL.
     *
     * @param url Health check endpoint.
     */
    suspend fun setTunnelCustomHealthUrl(url: String)

    /**
     * Updates the custom tunnel URL pattern.
     *
     * @param pattern URL extraction regex.
     */
    suspend fun setTunnelCustomUrlPattern(pattern: String)

    /**
     * Toggles gateway pairing requirement.
     *
     * @param required Whether pairing tokens are enforced.
     */
    suspend fun setGatewayRequirePairing(required: Boolean)

    /**
     * Toggles public bind on the gateway.
     *
     * @param allowed Whether 0.0.0.0 binding is permitted.
     */
    suspend fun setGatewayAllowPublicBind(allowed: Boolean)

    /**
     * Updates the list of authorized pairing tokens.
     *
     * @param tokens Comma-separated token strings.
     */
    suspend fun setGatewayPairedTokens(tokens: String)

    /**
     * Updates the pairing rate limit.
     *
     * @param limit Requests per minute.
     */
    suspend fun setGatewayPairRateLimit(limit: Int)

    /**
     * Updates the webhook rate limit.
     *
     * @param limit Requests per minute.
     */
    suspend fun setGatewayWebhookRateLimit(limit: Int)

    /**
     * Updates the idempotency TTL.
     *
     * @param seconds TTL in seconds.
     */
    suspend fun setGatewayIdempotencyTtl(seconds: Int)

    /**
     * Toggles the task scheduler.
     *
     * @param enabled Whether the scheduler is active.
     */
    suspend fun setSchedulerEnabled(enabled: Boolean)

    /**
     * Updates the scheduler max tasks.
     *
     * @param max Maximum number of scheduled tasks.
     */
    suspend fun setSchedulerMaxTasks(max: Int)

    /**
     * Updates the scheduler max concurrent executions.
     *
     * @param max Maximum concurrent task count.
     */
    suspend fun setSchedulerMaxConcurrent(max: Int)

    /**
     * Toggles the heartbeat engine.
     *
     * @param enabled Whether heartbeat is active.
     */
    suspend fun setHeartbeatEnabled(enabled: Boolean)

    /**
     * Updates the heartbeat interval.
     *
     * @param minutes Interval in minutes.
     */
    suspend fun setHeartbeatIntervalMinutes(minutes: Int)

    /**
     * Updates the observability backend.
     *
     * @param backend One of "none", "log", "otel".
     */
    suspend fun setObservabilityBackend(backend: String)

    /**
     * Updates the OTel collector endpoint.
     *
     * @param endpoint URL string.
     */
    suspend fun setObservabilityOtelEndpoint(endpoint: String)

    /**
     * Updates the OTel service name.
     *
     * @param name Service identifier.
     */
    suspend fun setObservabilityOtelServiceName(name: String)

    /**
     * Updates the model routes JSON.
     *
     * @param json JSON array of route objects.
     */
    suspend fun setModelRoutesJson(json: String)

    /**
     * Toggles memory hygiene (archival and purge).
     *
     * @param enabled Whether hygiene is active.
     */
    suspend fun setMemoryHygieneEnabled(enabled: Boolean)

    /**
     * Updates the memory archive threshold.
     *
     * @param days Days before entries are archived.
     */
    suspend fun setMemoryArchiveAfterDays(days: Int)

    /**
     * Updates the memory purge threshold.
     *
     * @param days Days before archived entries are purged.
     */
    suspend fun setMemoryPurgeAfterDays(days: Int)

    /**
     * Updates the embedding provider.
     *
     * @param provider One of "none", "openai", or "custom:URL".
     */
    suspend fun setMemoryEmbeddingProvider(provider: String)

    /**
     * Updates the embedding model name.
     *
     * @param model Model identifier.
     */
    suspend fun setMemoryEmbeddingModel(model: String)

    /**
     * Updates the vector similarity weight for recall.
     *
     * @param weight Weight value (0.0–1.0).
     */
    suspend fun setMemoryVectorWeight(weight: Float)

    /**
     * Updates the keyword matching weight for recall.
     *
     * @param weight Weight value (0.0–1.0).
     */
    suspend fun setMemoryKeywordWeight(weight: Float)

    /**
     * Toggles Composio tool integration.
     *
     * @param enabled Whether Composio is active.
     */
    suspend fun setComposioEnabled(enabled: Boolean)

    /**
     * Updates the Composio API key.
     *
     * @param key API key string.
     */
    suspend fun setComposioApiKey(key: String)

    /**
     * Updates the Composio entity ID.
     *
     * @param entityId Entity identifier.
     */
    suspend fun setComposioEntityId(entityId: String)

    /**
     * Toggles the browser tool.
     *
     * @param enabled Whether the browser tool is active.
     */
    suspend fun setBrowserEnabled(enabled: Boolean)

    /**
     * Updates the browser allowed domains list.
     *
     * @param domains Comma-separated domain names.
     */
    suspend fun setBrowserAllowedDomains(domains: String)

    /**
     * Toggles the HTTP request tool.
     *
     * @param enabled Whether the HTTP request tool is active.
     */
    suspend fun setHttpRequestEnabled(enabled: Boolean)

    /**
     * Updates the HTTP request allowed domains list.
     *
     * @param domains Comma-separated domain names.
     */
    suspend fun setHttpRequestAllowedDomains(domains: String)

    /**
     * Toggles the web fetch tool.
     *
     * @param enabled Whether the web fetch tool is active.
     */
    suspend fun setWebFetchEnabled(enabled: Boolean)

    /**
     * Updates the web fetch allowed domains list.
     *
     * @param domains Comma-separated domain names.
     */
    suspend fun setWebFetchAllowedDomains(domains: String)

    /**
     * Updates the web fetch blocked domains list.
     *
     * @param domains Comma-separated domain names.
     */
    suspend fun setWebFetchBlockedDomains(domains: String)

    /**
     * Updates the web fetch maximum response size.
     *
     * @param size Maximum response body size in bytes.
     */
    suspend fun setWebFetchMaxResponseSize(size: Int)

    /**
     * Updates the web fetch timeout.
     *
     * @param secs Request timeout in seconds.
     */
    suspend fun setWebFetchTimeoutSecs(secs: Int)

    /**
     * Toggles the web search tool.
     *
     * @param enabled Whether the web search tool is active.
     */
    suspend fun setWebSearchEnabled(enabled: Boolean)

    /**
     * Updates the web search provider.
     *
     * @param provider Search provider: "duckduckgo" or "brave".
     */
    suspend fun setWebSearchProvider(provider: String)

    /**
     * Updates the Brave Search API key.
     *
     * @param key API key string.
     */
    suspend fun setWebSearchBraveApiKey(key: String)

    /**
     * Updates the web search max results.
     *
     * @param max Maximum number of search results (1-10).
     */
    suspend fun setWebSearchMaxResults(max: Int)

    /**
     * Updates the web search timeout.
     *
     * @param secs Request timeout in seconds.
     */
    suspend fun setWebSearchTimeoutSecs(secs: Int)

    /**
     * Updates the maximum number of images per multimodal request.
     *
     * @param max Maximum images (1-16).
     */
    suspend fun setMultimodalMaxImages(max: Int)

    /**
     * Updates the maximum image size for multimodal input.
     *
     * @param mb Maximum image size in MB (1-20).
     */
    suspend fun setMultimodalMaxImageSizeMb(mb: Int)

    /**
     * Toggles remote image URL fetching for multimodal/vision.
     *
     * @param enabled Whether the agent can fetch remote image URLs.
     */
    suspend fun setMultimodalAllowRemoteFetch(enabled: Boolean)

    /**
     * Updates the sandbox enabled mode.
     *
     * @param enabled Sandbox mode: "" for auto-detect, "true", or "false".
     */
    suspend fun setSecuritySandboxEnabled(enabled: String)

    /**
     * Updates the sandbox backend.
     *
     * @param backend One of "auto", "landlock", "firejail", "bubblewrap", "docker", "none".
     */
    suspend fun setSecuritySandboxBackend(backend: String)

    /**
     * Updates the extra firejail arguments.
     *
     * @param args Comma-separated extra arguments for firejail.
     */
    suspend fun setSecuritySandboxFirejailArgs(args: String)

    /**
     * Updates the maximum memory for spawned commands.
     *
     * @param mb Maximum memory in MB.
     */
    suspend fun setSecurityResourcesMaxMemoryMb(mb: Int)

    /**
     * Updates the maximum CPU time for spawned commands.
     *
     * @param secs Maximum CPU time in seconds.
     */
    suspend fun setSecurityResourcesMaxCpuTimeSecs(secs: Int)

    /**
     * Updates the maximum number of subprocesses.
     *
     * @param max Maximum subprocess count.
     */
    suspend fun setSecurityResourcesMaxSubprocesses(max: Int)

    /**
     * Toggles memory monitoring for resource limits.
     *
     * @param enabled Whether memory monitoring is active.
     */
    suspend fun setSecurityResourcesMemoryMonitoring(enabled: Boolean)

    /**
     * Toggles security audit logging.
     *
     * @param enabled Whether audit logging is active.
     */
    suspend fun setSecurityAuditEnabled(enabled: Boolean)

    /**
     * Toggles OTP gating for sensitive actions.
     *
     * @param enabled Whether OTP gating is active.
     */
    suspend fun setSecurityOtpEnabled(enabled: Boolean)

    /**
     * Updates the OTP method.
     *
     * @param method One of "totp", "pairing", or "cli-prompt".
     */
    suspend fun setSecurityOtpMethod(method: String)

    /**
     * Updates the OTP token time-to-live.
     *
     * @param secs Token TTL in seconds.
     */
    suspend fun setSecurityOtpTokenTtlSecs(secs: Int)

    /**
     * Updates the OTP cache validity duration.
     *
     * @param secs Duration in seconds a verified OTP remains valid.
     */
    suspend fun setSecurityOtpCacheValidSecs(secs: Int)

    /**
     * Updates the OTP-gated actions list.
     *
     * @param actions Comma-separated list of actions requiring OTP.
     */
    suspend fun setSecurityOtpGatedActions(actions: String)

    /**
     * Updates the OTP-gated domains list.
     *
     * @param domains Comma-separated list of domains requiring OTP.
     */
    suspend fun setSecurityOtpGatedDomains(domains: String)

    /**
     * Updates the OTP-gated domain categories list.
     *
     * @param categories Comma-separated domain categories requiring OTP.
     */
    suspend fun setSecurityOtpGatedDomainCategories(categories: String)

    /**
     * Toggles the emergency stop feature.
     *
     * @param enabled Whether the emergency stop is active.
     */
    suspend fun setSecurityEstopEnabled(enabled: Boolean)

    /**
     * Toggles OTP requirement to resume after emergency stop.
     *
     * @param required Whether OTP is required to resume.
     */
    suspend fun setSecurityEstopRequireOtpToResume(required: Boolean)

    /**
     * Updates the Qdrant vector database URL.
     *
     * @param url Qdrant endpoint URL.
     */
    suspend fun setMemoryQdrantUrl(url: String)

    /**
     * Updates the Qdrant collection name.
     *
     * @param collection Collection identifier.
     */
    suspend fun setMemoryQdrantCollection(collection: String)

    /**
     * Updates the Qdrant API key.
     *
     * @param key API key string.
     */
    suspend fun setMemoryQdrantApiKey(key: String)

    /**
     * Updates the embedding routes JSON.
     *
     * @param json JSON array of embedding route objects.
     */
    suspend fun setEmbeddingRoutesJson(json: String)

    /**
     * Toggles automatic query classification.
     *
     * @param enabled Whether query classification is active.
     */
    suspend fun setQueryClassificationEnabled(enabled: Boolean)

    /**
     * Toggles proxy configuration.
     *
     * @param enabled Whether the proxy is active.
     */
    suspend fun setProxyEnabled(enabled: Boolean)

    /**
     * Updates the HTTP proxy URL.
     *
     * @param proxy HTTP proxy URL string.
     */
    suspend fun setProxyHttpProxy(proxy: String)

    /**
     * Updates the HTTPS proxy URL.
     *
     * @param proxy HTTPS proxy URL string.
     */
    suspend fun setProxyHttpsProxy(proxy: String)

    /**
     * Updates the catch-all proxy URL.
     *
     * @param proxy Proxy URL for all protocols.
     */
    suspend fun setProxyAllProxy(proxy: String)

    /**
     * Updates the proxy bypass list.
     *
     * @param noProxy Comma-separated domains that bypass the proxy.
     */
    suspend fun setProxyNoProxy(noProxy: String)

    /**
     * Updates the proxy scope.
     *
     * @param scope One of "environment", "zeroclaw", or "services".
     */
    suspend fun setProxyScope(scope: String)

    /**
     * Updates the proxy service selectors.
     *
     * @param selectors Comma-separated service selectors for proxy routing.
     */
    suspend fun setProxyServiceSelectors(selectors: String)

    /**
     * Updates the provider retry backoff duration.
     *
     * @param ms Backoff duration in milliseconds.
     */
    suspend fun setReliabilityBackoffMs(ms: Int)

    /**
     * Updates the reliability API keys JSON.
     *
     * @param json JSON object mapping provider names to API keys.
     */
    suspend fun setReliabilityApiKeysJson(json: String)

    /**
     * Toggles the session lock gate.
     *
     * @param enabled Whether the app-wide lock is active.
     */
    suspend fun setLockEnabled(enabled: Boolean)

    /**
     * Updates the lock timeout duration.
     *
     * @param minutes Minutes of background time before re-locking.
     */
    suspend fun setLockTimeoutMinutes(minutes: Int)

    /**
     * Stores the PBKDF2 hash of the user's PIN.
     *
     * @param hash Base64-encoded salt+hash string from [PinHasher][com.zeroclaw.android.util.PinHasher].
     */
    suspend fun setPinHash(hash: String)

    /**
     * Updates the remote plugin registry URL.
     *
     * @param url Registry URL string.
     */
    suspend fun setPluginRegistryUrl(url: String)

    /**
     * Toggles automatic plugin registry syncing.
     *
     * @param enabled Whether auto-sync is active.
     */
    suspend fun setPluginSyncEnabled(enabled: Boolean)

    /**
     * Updates the plugin sync interval.
     *
     * @param hours Interval in hours.
     */
    suspend fun setPluginSyncIntervalHours(hours: Int)

    /**
     * Updates the last successful plugin sync timestamp.
     *
     * @param timestamp Unix timestamp in milliseconds.
     */
    suspend fun setLastPluginSyncTimestamp(timestamp: Long)

    /**
     * Toggles stripping of thinking tags from model responses.
     *
     * @param enabled Whether to strip thinking tags.
     */
    suspend fun setStripThinkingTags(enabled: Boolean)

    /**
     * Updates the UI theme preference.
     *
     * @param theme The desired [ThemeMode].
     */
    suspend fun setTheme(theme: ThemeMode)
}
