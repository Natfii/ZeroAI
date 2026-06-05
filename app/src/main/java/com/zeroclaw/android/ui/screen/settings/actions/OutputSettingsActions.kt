/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.settings.actions

import androidx.compose.runtime.Stable

/**
 * Output settings area holder.
 *
 * Owns the strip-thinking-tags flag, which deliberately does NOT mark a restart
 * as required (the running daemon does not consume it).
 */
@Stable
internal class OutputSettingsActions(
    private val s: SettingsActionScope,
) {
    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setStripThinkingTags */
    fun updateStripThinkingTags(enabled: Boolean) {
        s.updateSettingNoRestart { setStripThinkingTags(enabled) }
    }
}
