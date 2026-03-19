/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.setup

/**
 * Status of an individual step in the daemon setup flow.
 *
 * Each step transitions through [Pending] -> [Running] -> [Success] or [Failed].
 * The sealed hierarchy lets UI code exhaustively pattern-match on every possible
 * state when rendering step indicators.
 */
sealed interface SetupStepStatus {
    /** Step has not yet started. */
    data object Pending : SetupStepStatus

    /** Step is currently executing. */
    data object Running : SetupStepStatus

    /** Step completed successfully. */
    data object Success : SetupStepStatus

    /**
     * Step failed with an error.
     *
     * @property error Human-readable description of the failure.
     * @property canRetry Whether the user can retry this step. Defaults to `true`.
     */
    data class Failed(
        val error: String,
        val canRetry: Boolean = true,
    ) : SetupStepStatus
}

/**
 * Aggregate progress across all daemon setup steps.
 *
 * The four core steps ([configValidation], [workspaceScaffold], [daemonStart],
 * [daemonHealth]) execute sequentially, while [channels] tracks per-channel
 * health checks that run in parallel after the daemon comes online.
 *
 * @property configValidation Status of TOML configuration validation.
 * @property workspaceScaffold Status of workspace directory scaffolding.
 * @property daemonStart Status of the daemon process launch.
 * @property daemonHealth Status of the post-launch health check.
 * @property channels Per-channel health status keyed by the channel's TOML name.
 * @property purgedChannels TOML keys of channels that were disabled during setup
 *   because they failed to start. These channels do not block [isComplete].
 */
data class SetupProgress(
    val configValidation: SetupStepStatus = SetupStepStatus.Pending,
    val workspaceScaffold: SetupStepStatus = SetupStepStatus.Pending,
    val daemonStart: SetupStepStatus = SetupStepStatus.Pending,
    val daemonHealth: SetupStepStatus = SetupStepStatus.Pending,
    val channels: Map<String, SetupStepStatus> = emptyMap(),
    val purgedChannels: List<String> = emptyList(),
) {
    /**
     * Whether the entire setup flow has resolved.
     *
     * Returns `true` when every core step and every non-purged channel entry
     * has reached a terminal status ([SetupStepStatus.Success] or
     * [SetupStepStatus.Failed]). Purged channels are excluded from the check
     * because they have already been disabled and the daemon was restarted
     * without them.
     */
    val isComplete: Boolean
        get() {
            val coreResolved =
                listOf(
                    configValidation,
                    workspaceScaffold,
                    daemonStart,
                    daemonHealth,
                ).all { it is SetupStepStatus.Success || it is SetupStepStatus.Failed }

            val activeChannels =
                channels.filterKeys { it !in purgedChannels }
            val channelsResolved =
                activeChannels.values.all {
                    it is SetupStepStatus.Success || it is SetupStepStatus.Failed
                }

            return coreResolved && channelsResolved
        }

    /**
     * Whether the daemon is confirmed online and healthy.
     *
     * Returns `true` only when [daemonHealth] is [SetupStepStatus.Success].
     */
    val daemonOnline: Boolean
        get() = daemonHealth is SetupStepStatus.Success
}
