/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data

import com.zeroclaw.android.data.oauth.AnthropicOAuthManager
import com.zeroclaw.android.data.oauth.OpenAiOAuthManager
import com.zeroclaw.android.model.ModelListFormat
import com.zeroclaw.android.model.ProviderAuthType
import com.zeroclaw.android.model.ProviderCategory
import com.zeroclaw.android.model.ProviderInfo

/**
 * Kotlin-side registry of AI providers supported by ZeroAI.
 *
 * Source of truth: `zeroclaw/src/providers/mod.rs` (factory function, lines 183-303).
 * This registry mirrors the upstream provider list so the UI can present structured
 * dropdowns instead of free-text fields. When the upstream submodule is updated
 * (via `upstream-sync.yml`), review this registry for new providers.
 */
object ProviderRegistry {
    /** Google Favicon API icon size in pixels. */
    private const val FAVICON_SIZE = 128

    /** All known providers ordered by category then display name. */
    val allProviders: List<ProviderInfo> =
        buildList {
            addAll(primaryProviders())
        }

    private val byId: Map<String, ProviderInfo> by lazy {
        buildMap {
            allProviders.forEach { provider ->
                put(provider.id, provider)
                provider.aliases.forEach { alias -> put(alias, provider) }
            }
        }
    }

    /**
     * Looks up a provider by its canonical ID or any of its aliases.
     *
     * @param id Provider identifier to search for (case-insensitive).
     * @return The matching [ProviderInfo] or null if unknown.
     */
    fun findById(id: String): ProviderInfo? = byId[id.lowercase()]

    /**
     * Returns all providers grouped by [ProviderCategory].
     *
     * @return Map from category to providers in that category.
     */
    fun allByCategory(): Map<ProviderCategory, List<ProviderInfo>> = allProviders.groupBy { it.category }

    /**
     * Builds a Google Favicon API URL for the given domain.
     *
     * @param domain Domain to fetch the favicon for.
     * @return URL string pointing to the favicon at [FAVICON_SIZE] pixels.
     */
    private fun faviconUrl(domain: String): String =
        "https://t3.gstatic.com/faviconV2?client=SOCIAL&type=FAVICON" +
            "&fallback_opts=TYPE,SIZE,URL&url=https://$domain&size=$FAVICON_SIZE"

    @Suppress("LongMethod")
    private fun primaryProviders(): List<ProviderInfo> =
        listOf(
            ProviderInfo(
                id = "openai",
                displayName = "OpenAI",
                authType = ProviderAuthType.API_KEY_OR_OAUTH,
                aliases = listOf("openai-codex", "openai_codex", "chatgpt", "codex"),
                suggestedModels =
                    listOf(
                        "gpt-5.5",
                        "gpt-5.4",
                        "gpt-5.4-mini",
                        "gpt-5.4-nano",
                        "gpt-5.3-codex",
                        "gpt-4.1",
                    ),
                category = ProviderCategory.PRIMARY,
                iconUrl = faviconUrl("openai.com"),
                modelListUrl = "https://api.openai.com/v1/models",
                modelListFormat = ModelListFormat.OPENAI_COMPATIBLE,
                keyCreationUrl = "https://platform.openai.com/api-keys",
                keyPrefix = "sk-",
                keyPrefixHint = "Keys start with sk- (usually sk-proj-...)",
                helpText =
                    "Direct API keys come from the OpenAI developer platform. " +
                        "API usage may require a funded API account.",
                oauthClientId = OpenAiOAuthManager.CLIENT_ID,
            ),
            ProviderInfo(
                id = "anthropic",
                displayName = "Anthropic",
                authType = ProviderAuthType.API_KEY_OR_OAUTH,
                suggestedModels =
                    listOf(
                        "claude-opus-4-7",
                        "claude-sonnet-4-6",
                        "claude-haiku-4-5-20251001",
                        "claude-opus-4-6",
                        "claude-sonnet-4-5-20250929",
                    ),
                category = ProviderCategory.PRIMARY,
                iconUrl = faviconUrl("anthropic.com"),
                modelListUrl = "https://api.anthropic.com/v1/models",
                modelListFormat = ModelListFormat.ANTHROPIC,
                keyCreationUrl = "https://console.anthropic.com/settings/keys",
                keyPrefix = "sk-ant-",
                keyPrefixHint = "Keys start with sk-ant-",
                helpText = "Accepts API keys or OAuth tokens (sk-ant-oat01-...)",
                oauthClientId = AnthropicOAuthManager.CLIENT_ID,
            ),
            ProviderInfo(
                id = "google-gemini",
                displayName = "Google Gemini",
                authType = ProviderAuthType.API_KEY_ONLY,
                suggestedModels =
                    listOf(
                        "gemini-3.1-pro-preview",
                        "gemini-3.5-flash",
                        "gemini-3.1-flash-lite",
                        "gemini-2.5-pro",
                        "gemini-2.5-flash",
                        "gemini-2.5-flash-lite",
                    ),
                aliases = listOf("google", "gemini"),
                category = ProviderCategory.PRIMARY,
                iconUrl = faviconUrl("ai.google.dev"),
                modelListUrl = "https://generativelanguage.googleapis.com/v1beta/models",
                modelListFormat = ModelListFormat.GOOGLE_GEMINI,
                keyCreationUrl = "https://aistudio.google.com/apikey",
                keyPrefix = "AIza",
                keyPrefixHint = "Keys start with AIza",
                helpText = "Use an AI Studio API key for Gemini models.",
            ),
            ProviderInfo(
                id = "openrouter",
                displayName = "OpenRouter",
                authType = ProviderAuthType.API_KEY_ONLY,
                suggestedModels =
                    listOf(
                        "openai/gpt-5.5",
                        "anthropic/claude-opus-4.7",
                        "google/gemini-3.5-flash",
                        "anthropic/claude-sonnet-4.6",
                        "deepseek/deepseek-v4-pro",
                        "qwen/qwen3.7-max",
                    ),
                category = ProviderCategory.PRIMARY,
                iconUrl = faviconUrl("openrouter.ai"),
                modelListUrl = "https://openrouter.ai/api/v1/models",
                modelListFormat = ModelListFormat.OPENROUTER,
                keyCreationUrl = "https://openrouter.ai/settings/keys",
                keyPrefix = "sk-or-v1-",
                keyPrefixHint = "Keys start with sk-or-v1-",
                helpText =
                    "OpenRouter routes requests to 300+ models from OpenAI, Anthropic, " +
                        "Google, Meta, and more through a single API key.",
            ),
            // xAI, DeepSeek, Qwen tiles removed 2026-05-25: backend providers
            // were dropped during the upstream-zeroclaw re-absorption. Users
            // who want these now go through OpenRouter (which proxies to all
            // three) or wait for the upstream provider crate to add them
            // back as first-class tiles.
            ProviderInfo(
                id = "ollama",
                displayName = "Ollama",
                authType = ProviderAuthType.URL_ONLY,
                defaultBaseUrl = "http://localhost:11434",
                suggestedModels =
                    listOf(
                        "llama3.3",
                        "deepseek-r1",
                        "qwen3",
                        "gemma3",
                        "phi4",
                        "mistral",
                    ),
                category = ProviderCategory.PRIMARY,
                iconUrl = faviconUrl("ollama.com"),
                modelListUrl = "http://localhost:11434/api/tags",
                modelListFormat = ModelListFormat.OLLAMA,
                helpText = "URL is optional \u2014 defaults to localhost:11434",
            ),
        )
}
