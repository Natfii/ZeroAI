/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.settings.actions

import androidx.compose.runtime.Stable

/**
 * Scheduler settings area holder.
 *
 * Owns daemon-affecting scheduler/heartbeat toggles and limits. The max-tasks,
 * max-concurrent, and heartbeat-interval limits are clamped to at least
 * [MIN_DAEMON_LIMIT] because the engine/daemon rejects a value of 0; all mark a
 * restart as required.
 */
@Stable
internal class SchedulerSettingsActions(
    private val s: SettingsActionScope,
) {
    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setSchedulerEnabled */
    fun updateSchedulerEnabled(enabled: Boolean) {
        s.updateDaemonSetting { setSchedulerEnabled(enabled) }
    }

    /**
     * Updates the scheduler max-tasks limit, clamped to at least 1.
     *
     * The value is coerced to [MIN_DAEMON_LIMIT] because the engine/daemon
     * rejects a value of 0.
     *
     * @see com.zeroclaw.android.data.repository.SettingsRepository.setSchedulerMaxTasks
     */
    fun updateSchedulerMaxTasks(max: Long) {
        val clamped = s.clampDaemon(max)
        s.updateDaemonSetting { setSchedulerMaxTasks(clamped) }
    }

    /**
     * Updates the scheduler max-concurrent limit, clamped to at least 1.
     *
     * The value is coerced to [MIN_DAEMON_LIMIT] because the engine/daemon
     * rejects a value of 0.
     *
     * @see com.zeroclaw.android.data.repository.SettingsRepository.setSchedulerMaxConcurrent
     */
    fun updateSchedulerMaxConcurrent(max: Long) {
        val clamped = s.clampDaemon(max)
        s.updateDaemonSetting { setSchedulerMaxConcurrent(clamped) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setHeartbeatEnabled */
    fun updateHeartbeatEnabled(enabled: Boolean) {
        s.updateDaemonSetting { setHeartbeatEnabled(enabled) }
    }

    /**
     * Updates the heartbeat tick interval in minutes, clamped to at least 1.
     *
     * The value is coerced to [MIN_DAEMON_LIMIT] because the engine/daemon
     * rejects a value of 0.
     *
     * @see com.zeroclaw.android.data.repository.SettingsRepository.setHeartbeatIntervalMinutes
     */
    fun updateHeartbeatIntervalMinutes(minutes: Long) {
        val clamped = s.clampDaemon(minutes)
        s.updateDaemonSetting { setHeartbeatIntervalMinutes(clamped) }
    }
}
