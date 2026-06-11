/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.settings.actions

import androidx.compose.runtime.Stable
import com.zeroclaw.android.model.OfficialPlugins
import com.zeroclaw.android.service.GlobalTomlConfig

/**
 * Tools settings area holder.
 *
 * Owns daemon-affecting tool plugin configuration: Composio, shared folder,
 * browser, HTTP request, web fetch, web search, Twitter browse, and multimodal
 * limits. Also owns the official-plugin enable dispatcher. All writes mark a
 * restart as required.
 */
@Suppress("TooManyFunctions")
@Stable
internal class ToolsSettingsActions(
    private val s: SettingsActionScope,
) {
    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setComposioEnabled */
    fun updateComposioEnabled(enabled: Boolean) {
        s.updateDaemonSetting { setComposioEnabled(enabled) }
    }

    /** Persists shared folder enabled state and restarts the daemon. */
    fun updateSharedFolderEnabled(enabled: Boolean) {
        s.updateDaemonSetting { setSharedFolderEnabled(enabled) }
    }

    /** Persists the shared folder SAF URI and restarts the daemon. */
    fun updateSharedFolderUri(uri: String) {
        s.updateDaemonSetting { setSharedFolderUri(uri) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setComposioApiKey */
    fun updateComposioApiKey(key: String) {
        s.updateDaemonSetting { setComposioApiKey(key) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setComposioEntityId */
    fun updateComposioEntityId(entityId: String) {
        s.updateDaemonSetting { setComposioEntityId(entityId) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setBrowserEnabled */
    fun updateBrowserEnabled(enabled: Boolean) {
        s.updateDaemonSetting { setBrowserEnabled(enabled) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setBrowserAllowedDomains */
    fun updateBrowserAllowedDomains(domains: String) {
        s.updateDaemonSetting { setBrowserAllowedDomains(domains) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setHttpRequestEnabled */
    fun updateHttpRequestEnabled(enabled: Boolean) {
        s.updateDaemonSetting { setHttpRequestEnabled(enabled) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setHttpRequestAllowedDomains */
    fun updateHttpRequestAllowedDomains(domains: String) {
        s.updateDaemonSetting { setHttpRequestAllowedDomains(domains) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setHttpRequestMaxResponseSize */
    fun updateHttpRequestMaxResponseSize(size: Long) {
        s.updateDaemonSetting { setHttpRequestMaxResponseSize(size) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setHttpRequestTimeoutSecs */
    fun updateHttpRequestTimeoutSecs(secs: Long) {
        s.updateDaemonSetting { setHttpRequestTimeoutSecs(secs) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setWebFetchEnabled */
    fun updateWebFetchEnabled(enabled: Boolean) {
        s.updateDaemonSetting { setWebFetchEnabled(enabled) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setWebFetchAllowedDomains */
    fun updateWebFetchAllowedDomains(domains: String) {
        s.updateDaemonSetting { setWebFetchAllowedDomains(domains) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setWebFetchBlockedDomains */
    fun updateWebFetchBlockedDomains(domains: String) {
        s.updateDaemonSetting { setWebFetchBlockedDomains(domains) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setWebFetchMaxResponseSize */
    fun updateWebFetchMaxResponseSize(size: Long) {
        s.updateDaemonSetting { setWebFetchMaxResponseSize(size) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setWebFetchTimeoutSecs */
    fun updateWebFetchTimeoutSecs(secs: Long) {
        s.updateDaemonSetting { setWebFetchTimeoutSecs(secs) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setWebSearchEnabled */
    fun updateWebSearchEnabled(enabled: Boolean) {
        s.updateDaemonSetting { setWebSearchEnabled(enabled) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setWebSearchProvider */
    fun updateWebSearchProvider(provider: String) {
        s.updateDaemonSetting { setWebSearchProvider(provider) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setWebSearchBraveApiKey */
    fun updateWebSearchBraveApiKey(key: String) {
        s.updateDaemonSetting { setWebSearchBraveApiKey(key) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setWebSearchTavilyApiKey */
    fun updateWebSearchTavilyApiKey(key: String) {
        s.updateDaemonSetting { setWebSearchTavilyApiKey(key) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setWebSearchSearxngUrl */
    fun updateWebSearchSearxngUrl(url: String) {
        s.updateDaemonSetting { setWebSearchSearxngUrl(url) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setWebSearchMaxResults */
    fun updateWebSearchMaxResults(max: Long) {
        s.updateDaemonSetting { setWebSearchMaxResults(max) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setWebSearchTimeoutSecs */
    fun updateWebSearchTimeoutSecs(secs: Long) {
        s.updateDaemonSetting { setWebSearchTimeoutSecs(secs) }
    }

    /**
     * Updates the meta search rate limit, clamped to the accepted range.
     *
     * The value is coerced into 1..60 because the engine treats 0 as
     * unlimited, which the app never intends.
     *
     * @see com.zeroclaw.android.data.repository.SettingsRepository.setWebSearchRequestsPerMinute
     */
    fun updateWebSearchRequestsPerMinute(requestsPerMinute: Long) {
        val clamped =
            requestsPerMinute.coerceIn(
                GlobalTomlConfig.MIN_WEB_SEARCH_REQUESTS_PER_MINUTE,
                GlobalTomlConfig.MAX_WEB_SEARCH_REQUESTS_PER_MINUTE,
            )
        s.updateDaemonSetting { setWebSearchRequestsPerMinute(clamped) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setTwitterBrowseEnabled */
    fun updateTwitterBrowseEnabled(enabled: Boolean) {
        s.updateDaemonSetting { setTwitterBrowseEnabled(enabled) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setTwitterBrowseMaxItems */
    fun updateTwitterBrowseMaxItems(max: Long) {
        s.updateDaemonSetting { setTwitterBrowseMaxItems(max) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setTwitterBrowseTimeoutSecs */
    fun updateTwitterBrowseTimeoutSecs(secs: Long) {
        s.updateDaemonSetting { setTwitterBrowseTimeoutSecs(secs) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setMultimodalMaxImages */
    fun updateMultimodalMaxImages(max: Int) {
        s.updateDaemonSetting { setMultimodalMaxImages(max) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setMultimodalMaxImageSizeMb */
    fun updateMultimodalMaxImageSizeMb(mb: Int) {
        s.updateDaemonSetting { setMultimodalMaxImageSizeMb(mb) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setMultimodalAllowRemoteFetch */
    fun updateMultimodalAllowRemoteFetch(enabled: Boolean) {
        s.updateDaemonSetting { setMultimodalAllowRemoteFetch(enabled) }
    }

    /**
     * Updates the enabled state of an official plugin in app settings.
     *
     * Dispatches to the correct setting based on the [OfficialPlugins]
     * constant. Vision has no enable toggle (always active), so toggling
     * it is a no-op.
     *
     * @param pluginId One of the [OfficialPlugins] constant IDs.
     * @param enabled New enabled state.
     */
    fun updateOfficialPluginEnabled(
        pluginId: String,
        enabled: Boolean,
    ) {
        when (pluginId) {
            OfficialPlugins.WEB_SEARCH -> updateWebSearchEnabled(enabled)
            OfficialPlugins.WEB_FETCH -> updateWebFetchEnabled(enabled)
            OfficialPlugins.HTTP_REQUEST -> updateHttpRequestEnabled(enabled)
            OfficialPlugins.COMPOSIO -> updateComposioEnabled(enabled)
            OfficialPlugins.SHARED_FOLDER -> updateSharedFolderEnabled(enabled)
            else -> {}
        }
    }
}
