package com.zeroclaw.android.model

/**
 * Registry of official built-in plugins.
 *
 * These plugins are always present in the database and cannot be
 * uninstalled, only enabled or disabled. Their configuration is stored
 * in [AppSettings] via DataStore rather than the plugin's generic
 * configFields map.
 *
 * Each ID maps to a specific TOML config section that
 * [com.zeroclaw.android.service.ConfigTomlBuilder] emits when the
 * plugin is enabled.
 */
object OfficialPlugins {
    /**
     * Web search tool (on-device meta, DuckDuckGo, Brave, Tavily, or SearXNG).
     * Maps to `[web_search]`.
     */
    const val WEB_SEARCH = "official-web-search"

    /** Web page content fetcher. Maps to `[web_fetch]`. */
    const val WEB_FETCH = "official-web-fetch"

    /** HTTP request tool for external APIs. Maps to `[http_request]`. */
    const val HTTP_REQUEST = "official-http-request"

    /** Shared folder tool for user-accessible file exchange. Maps to `[shared_folder]`. */
    const val SHARED_FOLDER = "official-shared-folder"

    /** Composio third-party tool integration. Maps to `[composio]`. */
    const val COMPOSIO = "official-composio"

    /** Image and vision multimodal processing. Maps to `[multimodal]`. */
    const val VISION = "official-vision"

    /** Set of all official plugin IDs. */
    val ALL: Set<String> =
        setOf(
            WEB_SEARCH,
            WEB_FETCH,
            HTTP_REQUEST,
            SHARED_FOLDER,
            COMPOSIO,
            VISION,
        )

    /** Returns true if the given [pluginId] is an official built-in plugin. */
    fun isOfficial(pluginId: String): Boolean = pluginId in ALL
}
