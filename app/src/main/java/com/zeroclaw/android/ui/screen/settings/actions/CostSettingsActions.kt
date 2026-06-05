/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.settings.actions

import androidx.compose.runtime.Stable

/**
 * Cost settings area holder.
 *
 * Owns daemon-affecting cost tracking, daily/monthly limits, and warn
 * threshold; all mark a restart as required.
 */
@Stable
internal class CostSettingsActions(
    private val s: SettingsActionScope,
) {
    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setCostEnabled */
    fun updateCostEnabled(enabled: Boolean) {
        s.updateDaemonSetting { setCostEnabled(enabled) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setDailyLimitUsd */
    fun updateDailyLimitUsd(limit: Float) {
        s.updateDaemonSetting { setDailyLimitUsd(limit) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setMonthlyLimitUsd */
    fun updateMonthlyLimitUsd(limit: Float) {
        s.updateDaemonSetting { setMonthlyLimitUsd(limit) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setCostWarnAtPercent */
    fun updateCostWarnAtPercent(percent: Int) {
        s.updateDaemonSetting { setCostWarnAtPercent(percent) }
    }
}
