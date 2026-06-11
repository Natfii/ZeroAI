/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.model

/**
 * Registry of web search provider ids accepted by the engine's `[web_search]` config.
 *
 * The engine parses `search_provider` and maps unknown values and legacy
 * aliases ("auto", "on-device", blank) to the keyless on-device meta
 * backend. [normalize] mirrors that mapping on the Kotlin side so the
 * settings UI and TOML emission agree with the daemon about what a
 * stored id means.
 */
object WebSearchProviders {
    /** Keyless on-device multi-engine meta search (engine default). */
    const val META = "meta"

    /** DuckDuckGo single-engine search, no API key required. */
    const val DUCKDUCKGO = "duckduckgo"

    /** Brave Search API, requires a subscription token. */
    const val BRAVE = "brave"

    /** Tavily Search API, requires an API key. */
    const val TAVILY = "tavily"

    /** Self-hosted SearXNG instance, requires an instance URL. */
    const val SEARXNG = "searxng"

    /** Provider ids the engine accepts verbatim. */
    val ALL: Set<String> = setOf(META, DUCKDUCKGO, BRAVE, TAVILY, SEARXNG)

    /**
     * Alias spellings the engine's provider routing recognizes, mapped to
     * their canonical ids. Kept in sync with
     * `zeroclaw-tools/src/web_search_provider_routing.rs` so ids imported
     * from external configs keep their intended provider.
     */
    private val ENGINE_ALIASES: Map<String, String> =
        mapOf(
            "default" to META,
            "auto" to META,
            "on-device" to META,
            "on_device" to META,
            "ondevice" to META,
            "ddg" to DUCKDUCKGO,
            "duck-duck-go" to DUCKDUCKGO,
            "duck_duck_go" to DUCKDUCKGO,
            "brave-search" to BRAVE,
            "brave_search" to BRAVE,
            "tavily-search" to TAVILY,
            "tavily_search" to TAVILY,
            "searx" to SEARXNG,
            "searx-ng" to SEARXNG,
            "searx_ng" to SEARXNG,
        )

    /**
     * Canonicalizes a stored provider id for TOML emission and display.
     *
     * Canonical ids pass through, engine-recognized aliases resolve to
     * their canonical id, and everything else — including legacy ids from
     * the pre-meta schema ("google", blank) — resolves to [META],
     * mirroring the engine's own unknown-provider fallback.
     *
     * @param storedId Provider id as persisted in settings.
     * @return A member of [ALL], falling back to [META].
     */
    fun normalize(storedId: String): String {
        val trimmed = storedId.trim().lowercase()
        if (trimmed in ALL) return trimmed
        return ENGINE_ALIASES[trimmed] ?: META
    }
}
