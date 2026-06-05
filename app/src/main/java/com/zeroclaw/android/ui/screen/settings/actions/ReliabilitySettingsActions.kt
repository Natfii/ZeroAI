/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.settings.actions

import androidx.compose.runtime.Stable

/**
 * Reliability settings area holder.
 *
 * Owns daemon-affecting provider retries, fallback providers, backoff, and the
 * reliability API-keys JSON; all mark a restart as required.
 */
@Stable
internal class ReliabilitySettingsActions(
    private val s: SettingsActionScope,
) {
    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setProviderRetries */
    fun updateProviderRetries(retries: Int) {
        s.updateDaemonSetting { setProviderRetries(retries) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setFallbackProviders */
    fun updateFallbackProviders(providers: String) {
        s.updateDaemonSetting { setFallbackProviders(providers) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setReliabilityBackoffMs */
    fun updateReliabilityBackoffMs(ms: Long) {
        s.updateDaemonSetting { setReliabilityBackoffMs(ms) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setReliabilityApiKeysJson */
    fun updateReliabilityApiKeysJson(json: String) {
        s.updateDaemonSetting { setReliabilityApiKeysJson(json) }
    }
}
