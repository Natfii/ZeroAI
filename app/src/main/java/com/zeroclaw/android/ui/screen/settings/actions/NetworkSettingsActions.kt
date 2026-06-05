/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.settings.actions

import androidx.compose.runtime.Stable

/**
 * Network settings area holder.
 *
 * Owns daemon host/port writes; both mark a restart as required.
 */
@Stable
internal class NetworkSettingsActions(
    private val s: SettingsActionScope,
) {
    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setHost */
    fun updateHost(host: String) {
        s.updateDaemonSetting { setHost(host) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setPort */
    fun updatePort(port: Int) {
        s.updateDaemonSetting { setPort(port) }
    }
}
