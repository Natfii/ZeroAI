/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
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
     * Emits `true` when a DataStore migration has changed defaults that
     * the user should be informed about (e.g. newly enabled plugins).
     * Returns `false` once [clearMigrationNotice] is called.
     */
    val migrationNoticePending: Flow<Boolean>

    /**
     * Clears the pending migration notice flag.
     *
     * Call this after the UI has shown the one-time notification so it
     * is not shown again.
     */
    suspend fun clearMigrationNotice()

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
     * Updates the fallback daemon route provider.
     *
     * Used only when no enabled/configured slot can provide startup routing.
     *
     * @param provider Provider ID (e.g. "openai", "anthropic").
     */
    suspend fun setDefaultProvider(provider: String)

    /**
     * Updates the fallback daemon route model.
     *
     * Used only when [setDefaultProvider] is acting as the startup fallback.
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
     * Updates the global reasoning-effort override.
     *
     * `auto` keeps model-specific defaults. Explicit values are currently
     * consumed by OpenAI reasoning models.
     *
     * @param effort One of `auto`, `none`, `low`, `medium`, `high`, or `xhigh`.
     */
    suspend fun setReasoningEffort(effort: String)

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
    suspend fun setGatewayIdempotencyTtl(seconds: Long)

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
    suspend fun setSchedulerMaxTasks(max: Long)

    /**
     * Updates the scheduler max concurrent executions.
     *
     * @param max Maximum concurrent task count.
     */
    suspend fun setSchedulerMaxConcurrent(max: Long)

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
    suspend fun setHeartbeatIntervalMinutes(minutes: Long)

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
     * Persists whether the shared folder tool plugin is enabled.
     *
     * @param enabled Whether to enable the shared folder tools.
     */
    suspend fun setSharedFolderEnabled(enabled: Boolean)

    /**
     * Persists the SAF URI for the user-chosen shared folder.
     *
     * @param uri The content URI string from [android.content.Intent.getData].
     */
    suspend fun setSharedFolderUri(uri: String)

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
     * Sets the HTTP request maximum response body size in bytes.
     *
     * @param size Maximum response size in bytes.
     */
    suspend fun setHttpRequestMaxResponseSize(size: Long)

    /**
     * Sets the HTTP request timeout in seconds.
     *
     * @param secs Timeout in seconds.
     */
    suspend fun setHttpRequestTimeoutSecs(secs: Long)

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
    suspend fun setWebFetchMaxResponseSize(size: Long)

    /**
     * Updates the web fetch timeout.
     *
     * @param secs Request timeout in seconds.
     */
    suspend fun setWebFetchTimeoutSecs(secs: Long)

    /**
     * Toggles the web search tool.
     *
     * @param enabled Whether the web search tool is active.
     */
    suspend fun setWebSearchEnabled(enabled: Boolean)

    /**
     * Updates the web search provider.
     *
     * @param provider Search provider: "auto", "brave", or "google".
     */
    suspend fun setWebSearchProvider(provider: String)

    /**
     * Updates the Brave Search API key.
     *
     * @param key API key string.
     */
    suspend fun setWebSearchBraveApiKey(key: String)

    /**
     * Stores the Google Custom Search API key in encrypted preferences.
     *
     * @param key API key string. Empty string clears the key.
     */
    suspend fun setWebSearchGoogleApiKey(key: String)

    /**
     * Stores the Google Custom Search Engine ID.
     *
     * @param cx Custom Search Engine identifier. Empty string clears it.
     */
    suspend fun setWebSearchGoogleCx(cx: String)

    /**
     * Updates the web search max results.
     *
     * @param max Maximum number of search results (1-10).
     */
    suspend fun setWebSearchMaxResults(max: Long)

    /**
     * Updates the web search timeout.
     *
     * @param secs Request timeout in seconds.
     */
    suspend fun setWebSearchTimeoutSecs(secs: Long)

    /**
     * Returns the cached Twitter browse handle, if any.
     */
    fun getTwitterBrowseHandle(): String?

    /**
     * Caches the Twitter browse handle.
     *
     * @param handle The Twitter handle to cache, or null to clear.
     */
    suspend fun setTwitterBrowseHandle(handle: String?)

    /**
     * Toggles the Twitter/X browse tool.
     *
     * @param enabled Whether twitter browsing is active.
     */
    suspend fun setTwitterBrowseEnabled(enabled: Boolean)

    /**
     * Updates the authenticated Twitter/X cookie string.
     *
     * @param cookieString Cookie string containing `ct0` and `auth_token`.
     */
    suspend fun setTwitterBrowseCookieString(cookieString: String)

    /**
     * Updates the Twitter/X browse max items.
     *
     * @param max Maximum number of items returned per request.
     */
    suspend fun setTwitterBrowseMaxItems(max: Long)

    /**
     * Updates the Twitter/X browse timeout.
     *
     * @param secs Request timeout in seconds.
     */
    suspend fun setTwitterBrowseTimeoutSecs(secs: Long)

    /**
     * Toggles audio transcription.
     *
     * @param enabled Whether transcription is active.
     */
    suspend fun setTranscriptionEnabled(enabled: Boolean)

    /**
     * Updates the transcription API endpoint URL.
     *
     * @param url API endpoint URL string.
     */
    suspend fun setTranscriptionApiUrl(url: String)

    /**
     * Updates the transcription model name.
     *
     * @param model Model identifier (e.g. "whisper-large-v3-turbo").
     */
    suspend fun setTranscriptionModel(model: String)

    /**
     * Updates the ISO language code hint for transcription.
     *
     * @param language ISO 639-1 language code (e.g. "en", "es").
     */
    suspend fun setTranscriptionLanguage(language: String)

    /**
     * Updates the maximum audio duration for transcription.
     *
     * @param secs Maximum duration in seconds.
     */
    suspend fun setTranscriptionMaxDurationSecs(secs: Long)

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
     * Toggles automatic query classification.
     *
     * @param enabled Whether query classification is active.
     */
    suspend fun setQueryClassificationEnabled(enabled: Boolean)

    /**
     * Toggles the open-skills community repository.
     *
     * @param enabled Whether open-skills is enabled.
     */
    suspend fun setSkillsOpenSkillsEnabled(enabled: Boolean)

    /**
     * Sets the custom open-skills directory path.
     *
     * @param dir Directory path or empty for default.
     */
    suspend fun setSkillsOpenSkillsDir(dir: String)

    /**
     * Sets the skill prompt injection mode.
     *
     * @param mode Injection mode: "full" or "compact".
     */
    suspend fun setSkillsPromptInjectionMode(mode: String)

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
    suspend fun setReliabilityBackoffMs(ms: Long)

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
     * Stores the raw gateway bearer token for authenticated requests.
     *
     * The token is stored in EncryptedSharedPreferences so it is
     * available for the app's own gateway calls without re-derivation.
     *
     * @param token The bearer token string.
     */
    suspend fun setGatewayBearerToken(token: String)

    /**
     * Retrieves the raw gateway bearer token.
     *
     * @return The stored bearer token, or an empty string if none exists.
     */
    suspend fun getGatewayBearerToken(): String

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

    /**
     * Emits whether the one-time skill install warning dialog has been acknowledged.
     *
     * Defaults to `false` until the user taps "I understand" in the dialog.
     * Collect with `collectAsStateWithLifecycle` and treat `false` as "show dialog".
     */
    fun getSkillInstallWarningSeen(): Flow<Boolean>

    /**
     * Persists whether the skill install warning dialog has been acknowledged.
     *
     * @param seen `true` after the user confirms the warning dialog.
     */
    suspend fun setSkillInstallWarningSeen(seen: Boolean)

    /** Persists the Tailscale agent awareness toggle. */
    suspend fun setTailscaleAwarenessEnabled(enabled: Boolean)

    /** Persists the JSON list of manually added Tailscale peer IPs. */
    suspend fun setTailscaleManualPeers(peers: String)

    /** Persists the JSON-serialized Tailscale discovery cache. */
    suspend fun setTailscaleCachedDiscovery(discovery: String)

    /** Persists the timestamp of the last Tailscale service scan. */
    suspend fun setTailscaleLastScanTimestamp(timestamp: Long)
}
