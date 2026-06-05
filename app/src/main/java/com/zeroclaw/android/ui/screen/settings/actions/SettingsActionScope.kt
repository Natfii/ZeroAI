/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.settings.actions

import androidx.compose.runtime.Stable
import com.zeroclaw.android.data.repository.AgentRepository
import com.zeroclaw.android.data.repository.OnboardingRepository
import com.zeroclaw.android.data.repository.SettingsRepository
import com.zeroclaw.android.service.DaemonServiceBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Minimum scheduler/heartbeat limit; the daemon rejects 0. */
internal const val MIN_DAEMON_LIMIT = 1L

/** Subscription keep-alive window for collected flows. */
internal const val STOP_TIMEOUT_MS = 5_000L

/**
 * Shared collaborator bundle and write seams for settings area holders.
 *
 * Holds the single set of repositories, the daemon bridge, and the coroutine
 * scope that every settings area holder shares. The two write seams
 * ([updateDaemonSetting] and [updateSettingNoRestart]) centralize the
 * restart-required distribution so each area holder simply chooses the correct
 * seam per setting, preserving the original ViewModel behavior exactly.
 *
 * @property repository Persistent settings store the seams write through.
 * @property daemonBridge Bridge used to flag that a daemon restart is required.
 * @property onboardingRepository Onboarding state store used by lifecycle resets.
 * @property agentRepository Source of configured agents for fallback-route options.
 * @property scope Coroutine scope (the owning ViewModel's scope) for launches and flows.
 */
@Stable
internal class SettingsActionScope(
    internal val repository: SettingsRepository,
    internal val daemonBridge: DaemonServiceBridge,
    internal val onboardingRepository: OnboardingRepository,
    internal val agentRepository: AgentRepository,
    internal val scope: CoroutineScope,
) {
    /**
     * Updates a daemon-affecting setting and marks a restart as required
     * if the daemon is currently running.
     *
     * @param block Suspend mutation applied to the [SettingsRepository] receiver.
     */
    fun updateDaemonSetting(block: suspend SettingsRepository.() -> Unit) {
        scope.launch {
            repository.block()
            daemonBridge.markRestartRequired()
        }
    }

    /**
     * Updates a setting with no live daemon effect, deliberately NOT marking a
     * restart as required (unlike [updateDaemonSetting]).
     *
     * Used for app-local or boot-time settings (for example the boot
     * auto-start flag) that the running daemon does not consume, so changing
     * them must not surface a restart prompt.
     *
     * @param block Suspend mutation applied to the [SettingsRepository] receiver.
     */
    fun updateSettingNoRestart(block: suspend SettingsRepository.() -> Unit) {
        scope.launch { repository.block() }
    }

    /**
     * Clamps a daemon limit to at least [MIN_DAEMON_LIMIT].
     *
     * The value is coerced because the engine/daemon rejects a value of 0.
     *
     * @param value Raw limit supplied by the UI.
     * @return The value coerced to at least [MIN_DAEMON_LIMIT].
     */
    fun clampDaemon(value: Long): Long = value.coerceAtLeast(MIN_DAEMON_LIMIT)
}
