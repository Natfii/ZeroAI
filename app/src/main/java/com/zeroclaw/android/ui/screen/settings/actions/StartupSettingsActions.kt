/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.settings.actions

import androidx.compose.runtime.Stable

/**
 * Startup settings area holder.
 *
 * Owns the boot auto-start flag, which deliberately does NOT mark a restart as
 * required (the running daemon does not consume it).
 */
@Stable
internal class StartupSettingsActions(
    private val s: SettingsActionScope,
) {
    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setAutoStartOnBoot */
    fun updateAutoStartOnBoot(enabled: Boolean) {
        s.updateSettingNoRestart { setAutoStartOnBoot(enabled) }
    }
}
