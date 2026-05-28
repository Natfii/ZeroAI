/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.plugins

import com.zeroclaw.android.data.repository.SettingsRepository
import com.zeroclaw.android.model.OfficialPlugins

/**
 * Mirrors official plugin toggles between Room plugin state and [SettingsRepository].
 *
 * The Plugins UI stores enablement in Room for presentation, while daemon TOML is
 * generated from [SettingsRepository]. Official plugins must therefore keep both
 * stores synchronized.
 */
object OfficialPluginSettingsSync {
    /**
     * Writes the enabled state of an official plugin into [settingsRepository].
     *
     * Unknown or non-official plugin identifiers are ignored.
     *
     * @param settingsRepository Settings repository that backs daemon TOML generation.
     * @param pluginId Unique plugin identifier.
     * @param enabled Whether the plugin is enabled.
     */
    suspend fun syncPluginEnabledState(
        settingsRepository: SettingsRepository,
        pluginId: String,
        enabled: Boolean,
    ) {
        when (pluginId) {
            OfficialPlugins.WEB_SEARCH -> settingsRepository.setWebSearchEnabled(enabled)
            OfficialPlugins.WEB_FETCH -> settingsRepository.setWebFetchEnabled(enabled)
            OfficialPlugins.HTTP_REQUEST -> settingsRepository.setHttpRequestEnabled(enabled)
            OfficialPlugins.COMPOSIO -> settingsRepository.setComposioEnabled(enabled)
            OfficialPlugins.SHARED_FOLDER -> settingsRepository.setSharedFolderEnabled(enabled)
        }
    }
}
