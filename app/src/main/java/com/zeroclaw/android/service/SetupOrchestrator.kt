/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import android.content.Context
import android.content.Intent
import android.util.Log
import com.zeroclaw.android.BuildConfig
import com.zeroclaw.android.ZeroAIApplication
import com.zeroclaw.android.data.DiscordDmLinkStore
import com.zeroclaw.android.ui.screen.setup.SetupProgress
import com.zeroclaw.android.ui.screen.setup.SetupStepStatus
import com.zeroclaw.ffi.FfiException
import com.zeroclaw.ffi.validateConfig
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Drives the daemon setup pipeline with polling-based health verification.
 *
 * Orchestrates the sequential steps of validating configuration, scaffolding
 * the workspace, starting the foreground service and daemon, and verifying
 * that the daemon and its channels become healthy. Each step's status is
 * published to [progress] so the UI can render real-time feedback.
 *
 * Two entry points are provided:
 * - [runFullSetup] for initial configuration including workspace scaffolding.
 * - [runHotReload] for restarting the daemon after settings changes without
 *   re-scaffolding.
 *
 * Both methods use exponential-backoff polling to wait for the daemon and
 * channels to report healthy status.
 *
 * @param daemonBridge Bridge for daemon lifecycle operations.
 * @param healthBridge Bridge for structured health detail queries.
 */
class SetupOrchestrator(
    private val daemonBridge: DaemonServiceBridge,
    private val healthBridge: HealthBridge,
) {
    private val _progress = MutableStateFlow(SetupProgress())

    /** Observable progress across all setup steps. */
    val progress: StateFlow<SetupProgress> = _progress.asStateFlow()

    /**
     * Executes the full daemon setup pipeline.
     *
     * Runs each step sequentially: config validation, workspace scaffolding,
     * daemon start, daemon health polling, and channel health polling. If any
     * step fails, subsequent steps are skipped and progress reflects the
     * failure point.
     *
     * When [onDisableChannel] and [onRebuildToml] are provided, channel
     * failures trigger a resilient recovery path: failed channels are disabled
     * in Room, the TOML is rebuilt without them, and the daemon is restarted
     * so that healthy channels can still operate.
     *
     * Safe to call from the main thread; blocking FFI calls are dispatched
     * internally to [Dispatchers.IO].
     *
     * @param context Application or activity context for starting the
     *   foreground service.
     * @param configToml Complete TOML configuration string for the daemon.
     * @param agentName Name for the AI agent identity file.
     * @param userName Name of the human user for identity files.
     * @param timezone IANA timezone ID for identity files.
     * @param communicationStyle Preferred communication tone for identity files.
     * @param expectedChannels List of channel names expected to become healthy.
     * @param host Gateway bind address.
     * @param port Gateway bind port.
     * @param onDisableChannel Callback to disable a failed channel in Room by
     *   its TOML key. When null, failed channels are only marked as timed out.
     * @param onRebuildToml Callback that rebuilds the TOML config from current
     *   Room state (after channels have been disabled). When null, no restart
     *   is attempted after channel failures.
     * @throws CancellationException if the calling coroutine is cancelled.
     */
    @Suppress("LongParameterList", "TooGenericExceptionCaught")
    suspend fun runFullSetup(
        context: Context,
        configToml: String,
        agentName: String,
        userName: String,
        timezone: String,
        communicationStyle: String,
        expectedChannels: List<String>,
        host: String = "127.0.0.1",
        port: UShort,
        onDisableChannel: (suspend (channelTomlKey: String) -> Unit)? = null,
        onRebuildToml: (suspend () -> String)? = null,
    ) {
        _progress.value =
            SetupProgress(
                channels = expectedChannels.associateWith { SetupStepStatus.Pending },
            )

        if (!stepValidateConfig(configToml)) return
        if (!stepScaffoldWorkspace(agentName, userName, timezone, communicationStyle)) return
        stopDaemonIfRunning()
        if (!stepStartDaemon(context, configToml, host, port)) return
        if (!stepAwaitDaemonHealth()) return
        stepAwaitChannelsResilient(
            expectedChannels = expectedChannels,
            context = context,
            host = host,
            port = port,
            onDisableChannel = onDisableChannel,
            onRebuildToml = onRebuildToml,
        )
    }

    /**
     * Restarts the daemon with updated configuration without re-scaffolding.
     *
     * Marks config validation and workspace scaffolding as already successful,
     * stops the current daemon (if running), then starts the daemon and awaits
     * health. Use this after settings changes that do not affect workspace
     * identity files.
     *
     * Safe to call from the main thread; blocking FFI calls are dispatched
     * internally to [Dispatchers.IO].
     *
     * @param context Application or activity context for starting the
     *   foreground service.
     * @param configToml Updated TOML configuration string.
     * @param expectedChannels List of channel names expected to become healthy.
     * @param host Gateway bind address.
     * @param port Gateway bind port.
     * @throws CancellationException if the calling coroutine is cancelled.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun runHotReload(
        context: Context,
        configToml: String,
        expectedChannels: List<String>,
        host: String = "127.0.0.1",
        port: UShort,
    ) {
        _progress.value =
            SetupProgress(
                configValidation = SetupStepStatus.Success,
                workspaceScaffold = SetupStepStatus.Success,
                channels = expectedChannels.associateWith { SetupStepStatus.Pending },
            )

        try {
            daemonBridge.stop()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Stop before hot-reload failed (non-fatal): ${e.message}")
        }

        if (!stepStartDaemon(context, configToml, host, port)) return
        if (!stepAwaitDaemonHealth()) return
        stepAwaitChannelsResilient(
            expectedChannels = expectedChannels,
            context = context,
            host = host,
            port = port,
            onDisableChannel = null,
            onRebuildToml = null,
        )
    }

    /**
     * Resets progress to its initial empty state.
     *
     * Call this before starting a new setup attempt so the UI shows all
     * steps as [SetupStepStatus.Pending].
     */
    fun reset() {
        _progress.value = SetupProgress()
    }

    /**
     * Stops the daemon if it is currently running.
     *
     * Called before starting a fresh daemon to avoid a "daemon already
     * running" error when the user re-runs the setup wizard from settings.
     * Failures are logged but not propagated since the daemon may not be
     * running at all.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun stopDaemonIfRunning() {
        try {
            val status = daemonBridge.pollStatus()
            if (status.running) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Daemon already running, stopping before fresh setup")
                }
                daemonBridge.stop()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Pre-setup daemon stop check failed (non-fatal): ${e.message}")
        }
    }

    /**
     * Validates the TOML configuration string via FFI.
     *
     * @return `true` if validation passed, `false` on failure.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun stepValidateConfig(configToml: String): Boolean {
        _progress.value =
            _progress.value.copy(
                configValidation = SetupStepStatus.Running,
            )

        return try {
            val result = withContext(Dispatchers.IO) { validateConfig(configToml) }
            if (result.isEmpty()) {
                _progress.value =
                    _progress.value.copy(
                        configValidation = SetupStepStatus.Success,
                    )
                true
            } else {
                _progress.value =
                    _progress.value.copy(
                        configValidation = SetupStepStatus.Failed(error = result),
                    )
                false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = e.message ?: "Config validation failed"
            Log.e(TAG, "Config validation error: $msg")
            _progress.value =
                _progress.value.copy(
                    configValidation = SetupStepStatus.Failed(error = msg),
                )
            false
        }
    }

    /**
     * Scaffolds the workspace directory with identity template files.
     *
     * @return `true` if scaffolding succeeded, `false` on failure.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun stepScaffoldWorkspace(
        agentName: String,
        userName: String,
        timezone: String,
        communicationStyle: String,
    ): Boolean {
        _progress.value =
            _progress.value.copy(
                workspaceScaffold = SetupStepStatus.Running,
            )

        return try {
            daemonBridge.ensureWorkspace(agentName, userName, timezone, communicationStyle)
            _progress.value =
                _progress.value.copy(
                    workspaceScaffold = SetupStepStatus.Success,
                )
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = e.message ?: "Workspace scaffolding failed"
            Log.e(TAG, "Workspace scaffold error: $msg")
            _progress.value =
                _progress.value.copy(
                    workspaceScaffold = SetupStepStatus.Failed(error = msg),
                )
            false
        }
    }

    /**
     * Starts the foreground service and daemon.
     *
     * Sends a [startForegroundService] intent first so the system does not
     * kill the process, then calls the bridge to start the native daemon.
     *
     * @return `true` if the daemon started, `false` on failure.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun stepStartDaemon(
        context: Context,
        configToml: String,
        host: String,
        port: UShort,
    ): Boolean {
        _progress.value =
            _progress.value.copy(
                daemonStart = SetupStepStatus.Running,
            )

        return try {
            val intent = Intent(context, ZeroAIDaemonService::class.java)
            context.startForegroundService(intent)
            try {
                DiscordDmLinkStore.syncRuntime(context.applicationContext as ZeroAIApplication)
            } catch (e: Exception) {
                Log.w(TAG, "Unable to sync Discord DM link before start: ${e.message}")
            }
            daemonBridge.start(configToml, host, port)
            _progress.value =
                _progress.value.copy(
                    daemonStart = SetupStepStatus.Success,
                )
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = e.message ?: "Daemon start failed"
            Log.e(TAG, "Daemon start error: $msg")
            _progress.value =
                _progress.value.copy(
                    daemonStart = SetupStepStatus.Failed(error = msg),
                )
            false
        }
    }

    /**
     * Polls daemon status until it reports as running.
     *
     * Uses [pollWithBackoff] with a [DAEMON_HEALTH_TIMEOUT_MS] timeout.
     *
     * @return `true` if the daemon became healthy, `false` on timeout.
     */
    private suspend fun stepAwaitDaemonHealth(): Boolean {
        _progress.value =
            _progress.value.copy(
                daemonHealth = SetupStepStatus.Running,
            )

        val healthy =
            pollWithBackoff(
                timeoutMs = DAEMON_HEALTH_TIMEOUT_MS,
                label = "daemon health",
            ) {
                try {
                    val status = daemonBridge.pollStatus()
                    status.running
                } catch (e: FfiException) {
                    Log.w(TAG, "Health poll attempt failed: ${e.message}")
                    false
                }
            }

        if (healthy) {
            _progress.value =
                _progress.value.copy(
                    daemonHealth = SetupStepStatus.Success,
                )
        } else {
            _progress.value =
                _progress.value.copy(
                    daemonHealth =
                        SetupStepStatus.Failed(
                            error = "Daemon did not become healthy within ${DAEMON_HEALTH_TIMEOUT_MS / MILLIS_PER_SECOND}s",
                        ),
                )
        }

        return healthy
    }

    /**
     * Awaits channel health with optional resilient recovery.
     *
     * First polls channels with the normal timeout. If all channels resolve
     * to [SetupStepStatus.Success], the method returns immediately. If
     * channels fail or timeout and the recovery callbacks are provided, the
     * method disables the failed channels via [onDisableChannel], rebuilds
     * the TOML via [onRebuildToml], restarts the daemon, and re-polls only
     * the surviving channels.
     *
     * When the callbacks are null, falls back to marking timed-out channels
     * as [SetupStepStatus.Failed] without attempting recovery.
     *
     * @param expectedChannels Channel names to wait for.
     * @param context Application context for restarting the foreground service.
     * @param host Gateway bind address for daemon restart.
     * @param port Gateway bind port for daemon restart.
     * @param onDisableChannel Callback to disable a channel in Room by TOML key.
     * @param onRebuildToml Callback to rebuild TOML from current Room state.
     */
    @Suppress("TooGenericExceptionCaught", "LongParameterList", "LongMethod")
    private suspend fun stepAwaitChannelsResilient(
        expectedChannels: List<String>,
        context: Context,
        host: String,
        port: UShort,
        onDisableChannel: (suspend (channelTomlKey: String) -> Unit)?,
        onRebuildToml: (suspend () -> String)?,
    ) {
        if (expectedChannels.isEmpty()) return

        _progress.value =
            _progress.value.copy(
                channels = expectedChannels.associateWith { SetupStepStatus.Running },
            )

        val resolved = pollChannelsWithTimeout(expectedChannels)
        if (resolved) return

        val allSuccess =
            _progress.value.channels.values
                .all { it is SetupStepStatus.Success }
        if (allSuccess) return

        if (onDisableChannel == null || onRebuildToml == null) {
            markChannelsTimedOut()
            return
        }

        purgeAndRestartForFailedChannels(
            expectedChannels = expectedChannels,
            context = context,
            host = host,
            port = port,
            onDisableChannel = onDisableChannel,
            onRebuildToml = onRebuildToml,
        )
    }

    /**
     * Polls channel health with the standard timeout.
     *
     * @param expectedChannels Channel names to check.
     * @return `true` if all channels resolved before timeout, `false` otherwise.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun pollChannelsWithTimeout(
        expectedChannels: List<String>,
    ): Boolean =
        pollWithBackoff(
            timeoutMs = CHANNEL_HEALTH_TIMEOUT_MS,
            label = "channel health",
        ) {
            try {
                pollChannelHealth(expectedChannels)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Channel health poll failed: ${e.message}")
                false
            }
        }

    /**
     * Disables failed channels, rebuilds TOML, restarts the daemon, and
     * re-polls the surviving channels.
     *
     * Any channel whose current status is not [SetupStepStatus.Success] is
     * considered failed. Each failed channel is disabled via [onDisableChannel],
     * its status is set to [SetupStepStatus.Failed], and it is added to the
     * [SetupProgress.purgedChannels] list. If surviving channels remain, the
     * daemon is restarted with a rebuilt TOML and the survivors are re-polled.
     *
     * @param expectedChannels Original list of channel names.
     * @param context Application context for the foreground service restart.
     * @param host Gateway bind address for daemon restart.
     * @param port Gateway bind port for daemon restart.
     * @param onDisableChannel Callback to disable a channel in Room.
     * @param onRebuildToml Callback to rebuild TOML from Room state.
     */
    @Suppress("LongParameterList")
    private suspend fun purgeAndRestartForFailedChannels(
        expectedChannels: List<String>,
        context: Context,
        host: String,
        port: UShort,
        onDisableChannel: suspend (channelTomlKey: String) -> Unit,
        onRebuildToml: suspend () -> String,
    ) {
        val currentChannels = _progress.value.channels
        val failed =
            expectedChannels.filter { key ->
                currentChannels[key] !is SetupStepStatus.Success
            }
        val surviving = expectedChannels - failed.toSet()

        disableFailedChannels(failed, onDisableChannel)
        updateProgressAfterPurge(currentChannels, failed, surviving)

        Log.w(TAG, "Purged ${failed.size} failed channel(s): $failed")

        if (surviving.isEmpty()) {
            Log.w(TAG, "All channels purged; daemon running without channels")
            return
        }

        restartDaemonWithSurvivors(context, host, port, surviving, onRebuildToml)
    }

    /**
     * Calls [onDisableChannel] for each failed channel TOML key.
     *
     * Failures to disable individual channels are logged but do not abort
     * the overall purge operation.
     *
     * @param failed TOML keys of channels that failed to start.
     * @param onDisableChannel Callback to disable a single channel in Room.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun disableFailedChannels(
        failed: List<String>,
        onDisableChannel: suspend (channelTomlKey: String) -> Unit,
    ) {
        for (key in failed) {
            try {
                onDisableChannel(key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to disable channel $key: ${e.message}")
            }
        }
    }

    /**
     * Updates [_progress] to reflect purged and surviving channels.
     *
     * Failed channels are marked with [SetupStepStatus.Failed] and surviving
     * channels are reset to [SetupStepStatus.Running] for the re-poll phase.
     *
     * @param currentChannels Snapshot of channel statuses before purging.
     * @param failed TOML keys of channels that failed.
     * @param surviving TOML keys of channels that remain active.
     */
    private fun updateProgressAfterPurge(
        currentChannels: Map<String, SetupStepStatus>,
        failed: List<String>,
        surviving: List<String>,
    ) {
        val updatedChannels =
            currentChannels.toMutableMap().apply {
                for (key in failed) {
                    put(
                        key,
                        SetupStepStatus.Failed(
                            error = "Disabled: channel failed to start",
                            canRetry = false,
                        ),
                    )
                }
                for (key in surviving) {
                    put(key, SetupStepStatus.Running)
                }
            }
        _progress.value =
            _progress.value.copy(
                channels = updatedChannels,
                purgedChannels = failed,
            )
    }

    /**
     * Rebuilds TOML, restarts the daemon, and re-polls surviving channels.
     *
     * If the daemon restart or re-poll fails, remaining channels are marked
     * as timed out rather than crashing the setup pipeline.
     *
     * @param context Application context for starting the foreground service.
     * @param host Gateway bind address.
     * @param port Gateway bind port.
     * @param surviving TOML keys of channels to re-poll after restart.
     * @param onRebuildToml Callback to rebuild TOML from Room state.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun restartDaemonWithSurvivors(
        context: Context,
        host: String,
        port: UShort,
        surviving: List<String>,
        onRebuildToml: suspend () -> String,
    ) {
        try {
            val newToml = onRebuildToml()
            stopDaemonIfRunning()
            if (!stepStartDaemon(context, newToml, host, port)) return
            if (!stepAwaitDaemonHealth()) return

            val reResolved = pollChannelsWithTimeout(surviving)
            if (!reResolved) {
                markChannelsTimedOut()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Daemon restart after channel purge failed: ${e.message}")
            markChannelsTimedOut()
        }
    }

    /**
     * Polls the health bridge for channel status and updates progress.
     *
     * Queries the structured health detail and checks the "channels"
     * component status. Updates [_progress] with per-channel results.
     *
     * @param expectedChannels Channel names to check.
     * @return `true` if channels resolved to a terminal state (ok or error),
     *   `false` if still pending.
     */
    private suspend fun pollChannelHealth(expectedChannels: List<String>): Boolean {
        val detail = healthBridge.getHealthDetail()
        val channelsComponent = detail.components.find { it.name == "channels" }

        return when (channelsComponent?.status) {
            "ok" -> {
                _progress.value =
                    _progress.value.copy(
                        channels = expectedChannels.associateWith { SetupStepStatus.Success },
                    )
                true
            }
            "error" -> {
                val errorMsg =
                    channelsComponent.lastError ?: "Channel component reported error"
                _progress.value =
                    _progress.value.copy(
                        channels =
                            expectedChannels.associateWith {
                                SetupStepStatus.Failed(error = errorMsg)
                            },
                    )
                true
            }
            else -> false
        }
    }

    /**
     * Marks any still-pending channels as failed due to timeout.
     *
     * Reads current channel statuses from [_progress] and transitions
     * any that are still [SetupStepStatus.Running] or [SetupStepStatus.Pending]
     * to [SetupStepStatus.Failed].
     */
    private fun markChannelsTimedOut() {
        val timeoutMsg =
            "Channels did not become healthy within ${CHANNEL_HEALTH_TIMEOUT_MS / MILLIS_PER_SECOND}s"
        val currentChannels = _progress.value.channels
        _progress.value =
            _progress.value.copy(
                channels =
                    currentChannels.mapValues { (_, status) ->
                        if (status is SetupStepStatus.Running || status is SetupStepStatus.Pending) {
                            SetupStepStatus.Failed(error = timeoutMsg)
                        } else {
                            status
                        }
                    },
            )
    }

    /**
     * Polls a condition with exponential backoff until it returns `true` or
     * the timeout elapses.
     *
     * Starts with [INITIAL_BACKOFF_MS] and doubles the delay each iteration,
     * capping at [MAX_BACKOFF_MS]. The entire polling loop is bounded by
     * [timeoutMs] using [withTimeoutOrNull].
     *
     * @param timeoutMs Hard deadline in milliseconds.
     * @param label Descriptive label for log messages.
     * @param check Suspend function that returns `true` when the condition is met.
     * @return `true` if [check] returned `true` before timeout, `false` otherwise.
     * @throws CancellationException if the calling coroutine is cancelled.
     */
    private suspend fun pollWithBackoff(
        timeoutMs: Long,
        label: String,
        check: suspend () -> Boolean,
    ): Boolean {
        var backoffMs = INITIAL_BACKOFF_MS
        return withTimeoutOrNull(timeoutMs) {
            while (true) {
                if (check()) return@withTimeoutOrNull true
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Polling $label, next attempt in ${backoffMs}ms")
                }
                delay(backoffMs)
                backoffMs =
                    (backoffMs * BACKOFF_MULTIPLIER)
                        .toLong()
                        .coerceAtMost(MAX_BACKOFF_MS)
            }
            @Suppress("UNREACHABLE_CODE")
            false
        } ?: false
    }

    /** Constants for [SetupOrchestrator]. */
    companion object {
        private const val TAG = "SetupOrchestrator"
        private const val INITIAL_BACKOFF_MS = 500L
        private const val BACKOFF_MULTIPLIER = 2.0
        private const val MAX_BACKOFF_MS = 5_000L
        private const val DAEMON_HEALTH_TIMEOUT_MS = 30_000L
        private const val CHANNEL_HEALTH_TIMEOUT_MS = 60_000L
        private const val MILLIS_PER_SECOND = 1_000L
    }
}
