/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.service

import android.util.Log
import com.zeroclaw.android.data.MemoryBackendCatalog
import com.zeroclaw.android.model.Agent
import com.zeroclaw.android.model.ChannelType
import com.zeroclaw.android.model.ConnectedChannel
import com.zeroclaw.android.model.FieldInputType

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
            val topLevelProviderType = stripColonUrl(resolvedProvider)
            val trimmedBaseUrl = config.baseUrl.trim()

            if (topLevelProviderType.isNotBlank()) {
                appendLine("default_provider = ${tomlString(topLevelProviderType)}")
            }

            val effectiveKey =
                config.apiKey.ifBlank {
                    if (needsPlaceholderKey(resolvedProvider)) PLACEHOLDER_API_KEY else ""
                }

            // Emit pure V1-style globals. The upstream migration chain
            // (zeroclaw-config/src/migration.rs) folds each into the V3
            // alias entry `[providers.models.<type>.default]`:
            //   default_model → model
            //   api_key       → api_key
            //   api_url       → base_url (V2) → uri (V3)
            // Producing the V3 alias entry from a single source avoids the
            // mixed-shape failure mode where emitting both flat globals
            // and a `[providers.models.<type>.default]` block left V1's
            // fold writing top-level keys onto the same `custom` table
            // that already had a `default` alias — which V3 rejects
            // because models.<type> must be a pure HashMap of aliases.
            if (config.model.isNotBlank()) {
                appendLine("default_model = ${tomlString(config.model)}")
            }
            if (effectiveKey.isNotBlank()) {
                appendLine("api_key = ${tomlString(effectiveKey)}")
            }
            if (trimmedBaseUrl.isNotBlank() &&
                topLevelProviderType in MODEL_PROVIDER_OVERRIDE_TYPES
            ) {
                appendLine("api_url = ${tomlString(trimmedBaseUrl)}")
            }
            config.providerTimeoutSecs?.let { timeout ->
                // V1 top-level key folded by the V2 migration into the
                // synthesised `[providers.models.<type>.default]`
                // alias entry as `timeout_secs`. Lets callers raise the
                // HTTP client timeout for slow local providers (CPU
                // inference of large local models routinely exceeds
                // upstream's 120 s default).
                appendLine("provider_timeout_secs = ${timeout.coerceAtLeast(0L)}")
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
            appendMultimodalSection(config)
            appendProxySection(config)
            appendWebFetchSection(config)
            appendWebSearchSection(config)
            // Twitter/X browse + [email] migrated to FeatureContributor.
            // See app/src/main/java/com/zeroclaw/android/service/features/.
            appendSecuritySandboxSection(config)
            appendSecurityResourcesSection(config)
            appendSecurityAuditSection(config)
            appendSecurityEstopSection(config)
            appendSkillsSection(config)
            appendTtySection(config)

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
        val validBackend = MemoryBackendCatalog.validate(config.memoryBackend)
        if (validBackend != config.memoryBackend) {
            Log.w(
                "ConfigTomlBuilder",
                "Unknown memory backend '${config.memoryBackend}', falling back to '$validBackend'",
            )
        }
        appendLine()
        appendLine("[memory]")
        appendLine("backend = ${tomlString(validBackend)}")
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
     * Appends the `[risk_profiles.default]` and `[runtime_profiles.default]`
     * sections that the runtime tool gate reads to authorize tool execution.
     *
     * `SecurityPolicy::for_agent` resolves authorization through these default
     * profiles, and every agent points at them via [buildAgentsToml], so the
     * user's autonomy level plus the command allow-list and rate/cost limits
     * mirrored here are what actually gate tool execution.
     *
     * @param config Configuration to read autonomy values from.
     */
    private fun StringBuilder.appendAutonomySection(config: GlobalTomlConfig) {
        val level = config.autonomyLevel
        require(level in GlobalTomlConfig.VALID_AUTONOMY_LEVELS) {
            "Invalid autonomy level '$level': must be one of ${GlobalTomlConfig.VALID_AUTONOMY_LEVELS}"
        }
        val cmdList =
            if (config.allowedCommands.isEmpty()) {
                "[]"
            } else {
                "[${config.allowedCommands.joinToString(", ") { tomlString(it) }}]"
            }
        val pathList =
            if (config.forbiddenPaths.isEmpty()) {
                "[]"
            } else {
                "[${config.forbiddenPaths.joinToString(", ") { tomlString(it) }}]"
            }
        appendLine()
        appendLine("[risk_profiles.default]")
        appendLine("level = ${tomlString(level)}")
        appendLine("workspace_only = ${config.workspaceOnly}")
        appendLine("allowed_commands = $cmdList")
        appendLine("forbidden_paths = $pathList")
        appendLine("require_approval_for_medium_risk = ${config.requireApprovalMediumRisk}")
        appendLine("block_high_risk_commands = ${config.blockHighRiskCommands}")
        appendLine()
        appendLine("[runtime_profiles.default]")
        appendLine("max_actions_per_hour = ${config.maxActionsPerHour.coerceAtLeast(0)}")
        appendLine("max_cost_per_day_cents = ${config.maxCostPerDayCents.coerceAtLeast(0)}")
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
                // Upstream defaults `enabled = false` so a half-pasted
                // block can't accidentally bring a channel live. We pass
                // only enabled channels through this builder, so emit
                // explicitly — otherwise `collect_configured_channels`
                // silently skips every block and `start_channels` exits
                // with `channels.is_empty()`.
                appendLine("enabled = true")
                for (spec in channel.type.fields) {
                    val value = values[spec.key].orEmpty()
                    if (value.isBlank() && !spec.isRequired) continue
                    appendTomlField(spec.key, value, spec.inputType)
                }
                if (channel.type.usesProgressiveStreaming) {
                    appendChatChannelStreamingDefaults()
                }
                if (channel.type == ChannelType.DISCORD) {
                    if (!discordGuildId.isNullOrBlank()) {
                        appendLine("guild_id = \"$discordGuildId\"")
                    }
                    // Small models (e.g. Gemma 4-e2b) over-reach for
                    // `send_message_to_peer` when @-mentioned in a server
                    // channel — they treat replying-to-the-mentioner as a
                    // cross-channel DM op instead of "just output text".
                    // The tool then trips the medium-risk approval gate,
                    // which dumps a plaintext "APPROVAL REQUIRED [id]"
                    // block into the channel where the @mention came from
                    // (Discord has no native button/embed render for the
                    // approval flow). Hiding the tool from this channel's
                    // spec forces the LLM down the direct in-channel reply
                    // path, which renders as a normal message.
                    appendLine("""excluded_tools = ["send_message_to_peer"]""")
                }
            }

            // Pre-author a Discord peer_group with wildcard `external_peers`
            // so `is_user_allowed` accepts every author. Telegram already
            // works because the Hub UI exposes an "Allowed Users" field that
            // V2 migration folds into `[peer_groups.telegram_default]`.
            // Discord has no equivalent field — and emitting
            // `allowed_users = ["*"]` doesn't help because V2's fold
            // (schema/v2.rs:1074) explicitly drops `"*"` entries with the
            // comment "a peer group can't express 'anyone'". Operator-
            // authored peer_groups pass through the migration verbatim
            // (v2.rs:1081-1084), so the wildcard reaches the runtime
            // intact this way. `guild_id` (above) already restricts the
            // bot to one server, so accepting any author there matches
            // how Discord bots normally behave.
            val hasDiscord = channelsWithSecrets.any { it.first.type == ChannelType.DISCORD }
            if (hasDiscord) {
                appendLine()
                appendLine("[peer_groups.discord_default]")
                appendLine("""channel = "discord.default"""")
                appendLine("""external_peers = ["*"]""")
            }
        }
    }

    /**
     * Emits the chat-channel streaming defaults shared across every
     * interactive chat surface (Telegram, Discord, any future
     * progressively-streamed channel).
     *
     * `stream_mode = "partial"` switches the channel from "post one
     * final message" to "post a draft and edit it as deltas arrive."
     * The draft path also filters reasoning preambles (the model's
     * `<think>...` chain-of-thought) instead of dumping them into the
     * channel, which is the actual UX win — without this, small models
     * leak their planning steps into Discord/Telegram messages.
     *
     * Hoisted out so adding a third chat channel doesn't require a
     * third copy of these three lines. Long-term these belong as
     * `#[serde(default = …)]` on the upstream channel structs, but
     * keeping them in Kotlin lets us roll out per-channel changes
     * without an upstream patch.
     */
    private fun StringBuilder.appendChatChannelStreamingDefaults() {
        appendLine("""stream_mode = "partial"""")
        appendLine("draft_update_interval_ms = 1000")
        appendLine("interrupt_on_new_message = true")
    }

    /**
     * Builds `[agents.<name>]` TOML sections.
     *
     * V3 agents are references: `model_provider = "<type>.<alias>"` points
     * to a `[providers.models.<type>.<alias>]` entry that already holds
     * the model name + API key + URI + temperature. We do **not** inline
     * those fields per agent — doing so forces the upstream V2→V3
     * migration to synthesize a duplicate provider entry (`agent_<alias>`)
     * for every agent. Pointing all agents at the shared `<type>.default`
     * alias (populated by V1 globals folding) keeps `providers.models` to
     * one entry per provider type, which is what `first_model_provider()`
     * and routing-by-alias both expect.
     *
     * `system_prompt` is also intentionally omitted: V3's
     * `AliasedAgentConfig` has no such field — it was silently dropped by
     * the deserializer. Agent identity should live in
     * `[agents.<alias>.identity]` or the global `[identity]` block instead.
     *
     * @param agents Resolved agent entries to serialize.
     * @return TOML string with one `[agents.<name>]` section per entry,
     *   or empty if [agents] is empty.
     */
    fun buildAgentsToml(agents: List<AgentTomlEntry>): String {
        if (agents.isEmpty()) return ""
        return buildString {
            for (entry in agents) {
                appendLine()
                appendLine("[agents.${tomlKey(entry.name)}]")
                // Point every agent at the shared default risk/runtime profiles
                // so SecurityPolicy::for_agent resolves and the autonomy gate is
                // actually enforced (an empty risk_profile silently disables it).
                appendLine("risk_profile = ${tomlString("default")}")
                appendLine("runtime_profile = ${tomlString("default")}")
                if (entry.modelProviderRef.isNotBlank()) {
                    appendLine("model_provider = ${tomlString(entry.modelProviderRef)}")
                }
                if (entry.channels.isNotEmpty()) {
                    val list = entry.channels.joinToString(", ") { tomlString(it) }
                    appendLine("channels = [$list]")
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

        // Legacy V1 colon-URL form. Upstream's V2 migration in
        // schema/v2.rs splits this into `[providers.models.<type>.<alias>]
        // uri = "..."` for per-agent blocks. The global `default_provider`
        // path does NOT split colon-URLs the same way -- callers that need
        // a working global default also need an explicit
        // `[model_providers.<type>.default] uri = "..."` block emitted
        // alongside (see `MODEL_PROVIDER_OVERRIDE_TYPES` and the gate in
        // `build()`.

        if (provider == "custom-anthropic" && trimmedUrl.isNotEmpty()) {
            return "anthropic-custom:$trimmedUrl"
        }

        if (provider in OPENAI_COMPATIBLE_SELF_HOSTED && trimmedUrl.isNotEmpty()) {
            return "custom:$trimmedUrl"
        }

        if (provider == "ollama" && trimmedUrl.isNotEmpty() && trimmedUrl != OLLAMA_DEFAULT_URL) {
            return "custom:$trimmedUrl"
        }

        return provider
    }

    /**
     * Strips the `:URL` suffix from a legacy colon-URL provider name to
     * recover just the upstream type. Used when emitting top-level fields
     * that V2 expects to carry only the type (the URL goes into a separate
     * `[model_providers.<type>.<alias>]` table).
     */
    internal fun stripColonUrl(resolvedProvider: String): String =
        when {
            resolvedProvider.startsWith("anthropic-custom:") -> "custom"
            resolvedProvider.startsWith("custom:") -> "custom"
            else -> resolvedProvider
        }

    /**
     * Upstream V2 provider type names that accept a `uri` override.
     * Used to gate the top-level `api_url` emission so a self-hosted
     * provider (`custom:URL` / `ollama` with a non-default URL / etc.)
     * gets the URI into the V1-globals fold and thence into the
     * synthesized `[providers.models.<type>.default]` entry.
     */
    private val MODEL_PROVIDER_OVERRIDE_TYPES =
        setOf("custom", "lmstudio", "ollama", "llamacpp")

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
    internal fun needsPlaceholderKey(resolvedProvider: String): Boolean =
        resolvedProvider in PROVIDERS_NEEDING_PLACEHOLDER_KEY ||
            resolvedProvider.startsWith("custom:")

    /**
     * Upstream V2 self-hosted provider types whose factories accept any
     * non-empty `api_key`. The OpenAiCompatibleProvider implementation
     * still requires the field to be non-null, so the Android side
     * injects a placeholder when the user hasn't configured one.
     */
    private val PROVIDERS_NEEDING_PLACEHOLDER_KEY =
        setOf("ollama", "lmstudio", "llamacpp", "custom")

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
                require(peer.isValid()) { "invalid peer entry passed to serializer: $peer" }
                appendLine("[[tailscale_peers.entries]]")
                appendLine("ip = ${tomlString(peer.ip)}")
                appendLine("hostname = ${tomlString(peer.hostname)}")
                appendLine("kind = ${tomlString(peer.kind)}")
                appendLine("port = ${peer.port}")
                appendLine("alias = ${tomlString(peer.alias)}")
                appendLine("auth_required = ${peer.authRequired}")
                appendLine("enabled = ${peer.enabled}")
                appendLine()
            }
        }
    }
}
