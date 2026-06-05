/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.settings.actions

import androidx.compose.runtime.Stable

/**
 * Autonomy settings area holder.
 *
 * Owns daemon-affecting autonomy level, workspace confinement, command
 * allow/forbid lists, rate/cost caps, and risk-approval flags; all mark a
 * restart as required.
 */
@Stable
internal class AutonomySettingsActions(
    private val s: SettingsActionScope,
) {
    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setAutonomyLevel */
    fun updateAutonomyLevel(level: String) {
        s.updateDaemonSetting { setAutonomyLevel(level) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setWorkspaceOnly */
    fun updateWorkspaceOnly(enabled: Boolean) {
        s.updateDaemonSetting { setWorkspaceOnly(enabled) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setAllowedCommands */
    fun updateAllowedCommands(commands: String) {
        s.updateDaemonSetting { setAllowedCommands(commands) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setForbiddenPaths */
    fun updateForbiddenPaths(paths: String) {
        s.updateDaemonSetting { setForbiddenPaths(paths) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setMaxActionsPerHour */
    fun updateMaxActionsPerHour(max: Int) {
        s.updateDaemonSetting { setMaxActionsPerHour(max) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setMaxCostPerDayCents */
    fun updateMaxCostPerDayCents(cents: Int) {
        s.updateDaemonSetting { setMaxCostPerDayCents(cents) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setRequireApprovalMediumRisk */
    fun updateRequireApprovalMediumRisk(required: Boolean) {
        s.updateDaemonSetting { setRequireApprovalMediumRisk(required) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setBlockHighRiskCommands */
    fun updateBlockHighRiskCommands(blocked: Boolean) {
        s.updateDaemonSetting { setBlockHighRiskCommands(blocked) }
    }
}
