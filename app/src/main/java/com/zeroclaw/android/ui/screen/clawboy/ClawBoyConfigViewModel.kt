/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.clawboy

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.PowerManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zeroclaw.android.ZeroAIApplication
import com.zeroclaw.ffi.ClawBoyStatus
import com.zeroclaw.ffi.clawboyGetStatus
import com.zeroclaw.ffi.clawboyPauseSession
import com.zeroclaw.ffi.clawboyResumeSession
import com.zeroclaw.ffi.clawboySetDecisionInterval
import com.zeroclaw.ffi.clawboyStopSession
import com.zeroclaw.ffi.clawboyVerifyRom
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for the ClawBoy Game Boy emulator configuration screen.
 *
 * Manages ROM selection, session lifecycle, and decision-interval tuning.
 * All FFI calls run on [Dispatchers.IO] to avoid blocking the main thread.
 */
class ClawBoyConfigViewModel(
    application: Application,
) : AndroidViewModel(application) {
    /** UI state for the ClawBoy config screen. */
    sealed interface UiState {
        /** Initial loading state. */
        data object Loading : UiState

        /** No ROM loaded; prompts the user to select one. */
        data object Empty : UiState

        /**
         * Screen content with current session and configuration.
         *
         * @property romFileName Display name of the loaded ROM file.
         * @property romVerified Whether the ROM passed SHA-1 verification.
         * @property isPlaying Whether a ClawBoy session is actively running.
         * @property isPaused Whether the current session is paused.
         * @property viewerUrl WebSocket viewer URL when a session is active.
         * @property decisionIntervalSecs AI decision interval in seconds.
         * @property playTimeSeconds Cumulative play time of the active session.
         */
        data class Content(
            val romFileName: String?,
            val romVerified: Boolean,
            val isPlaying: Boolean,
            val isPaused: Boolean,
            val viewerUrl: String?,
            val decisionIntervalSecs: Float,
            val playTimeSeconds: Long,
        ) : UiState

        /**
         * Error state with retry action.
         *
         * @property message Human-readable error description.
         * @property retry Callback to reload state.
         */
        data class Error(
            val message: String,
            val retry: () -> Unit,
        ) : UiState
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)

    /** Observable UI state for the ClawBoy config screen. */
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val app = application as ZeroAIApplication
    private var internalRomPath: String? = null

    /**
     * Receiver that pauses or resumes the ClawBoy session when battery
     * saver mode changes on the device.
     */
    private val powerSaveReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                if (intent.action != PowerManager.ACTION_POWER_SAVE_MODE_CHANGED) return
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        if (pm.isPowerSaveMode) {
                            clawboyPauseSession()
                        } else {
                            clawboyResumeSession()
                        }
                        loadState()
                    } catch (_: Exception) {
                        /** Best effort -- session may not be active. */
                    }
                }
            }
        }

    init {
        loadState()
        val filter = IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        app.registerReceiver(powerSaveReceiver, filter)
    }

    override fun onCleared() {
        super.onCleared()
        try {
            app.unregisterReceiver(powerSaveReceiver)
        } catch (_: Exception) {
            /** Already unregistered. */
        }
    }

    /** Reloads state from the ROM file and FFI session status. */
    fun loadState() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val romFile = File(app.filesDir, "clawboy/pokemon-red/rom.gb")
                val status =
                    try {
                        clawboyGetStatus()
                    } catch (_: Exception) {
                        ClawBoyStatus.Idle
                    }

                if (!romFile.exists()) {
                    _uiState.value = UiState.Empty
                    return@launch
                }

                internalRomPath = romFile.absolutePath

                try {
                    com.zeroclaw.ffi.clawboyNotifyRomReady(
                        app.filesDir.absolutePath,
                    )
                } catch (_: Exception) {
                    /** Daemon may not be running yet -- non-fatal. */
                }

                _uiState.value =
                    UiState.Content(
                        romFileName = romFile.name,
                        romVerified = true,
                        isPlaying = status is ClawBoyStatus.Playing,
                        isPaused = status is ClawBoyStatus.Paused,
                        viewerUrl =
                            (status as? ClawBoyStatus.Playing)?.viewerUrl,
                        decisionIntervalSecs = DEFAULT_DECISION_INTERVAL,
                        playTimeSeconds =
                            (status as? ClawBoyStatus.Playing)
                                ?.playTimeSeconds
                                ?.toLong()
                                ?: 0L,
                    )
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                _uiState.value =
                    UiState.Error(
                        e.message ?: "Unknown error",
                    ) { loadState() }
            }
        }
    }

    /**
     * Handles a ROM file selected by the user via the document picker.
     *
     * Reads the file, verifies the SHA-1 hash via FFI, and copies the
     * validated ROM to internal storage.
     *
     * @param uri Content URI of the selected ROM file.
     */
    fun onRomSelected(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val resolver = app.contentResolver
                val bytes =
                    resolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("Failed to read ROM file")

                val verification =
                    clawboyVerifyRom(bytes)

                if (!verification.valid) {
                    _uiState.value =
                        UiState.Error(
                            "This doesn't look like a clean Pokemon Red " +
                                "(USA/Europe) ROM. ClawBoy needs an exact " +
                                "match to read game memory correctly.",
                        ) { loadState() }
                    return@launch
                }

                val romDir = File(app.filesDir, "clawboy/pokemon-red")
                romDir.mkdirs()
                val romFile = File(romDir, "rom.gb")
                romFile.writeBytes(bytes)
                internalRomPath = romFile.absolutePath

                try {
                    com.zeroclaw.ffi.clawboyNotifyRomReady(
                        app.filesDir.absolutePath,
                    )
                } catch (_: Exception) {
                    /** Non-fatal. */
                }

                loadState()
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                _uiState.value =
                    UiState.Error(
                        e.message ?: "ROM selection failed",
                    ) { loadState() }
            }
        }
    }

    /** Stops the currently running ClawBoy session. */
    fun stopGame() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                clawboyStopSession()
                loadState()
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                _uiState.value =
                    UiState.Error(
                        e.message ?: "Failed to stop game",
                    ) { loadState() }
            }
        }
    }

    /**
     * Updates the AI decision interval.
     *
     * @param seconds New interval in seconds (clamped by the UI slider).
     */
    fun updateDecisionInterval(seconds: Float) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                clawboySetDecisionInterval(
                    (seconds * MILLIS_PER_SECOND).toLong().toULong(),
                )
            } catch (_: Exception) {
                // best effort
            }
        }
    }

    /** Deletes the loaded ROM file and resets the screen to empty state. */
    fun removeRom() {
        viewModelScope.launch(Dispatchers.IO) {
            val romFile = File(app.filesDir, "clawboy/pokemon-red/rom.gb")
            romFile.delete()
            internalRomPath = null
            _uiState.value = UiState.Empty
            try {
                com.zeroclaw.ffi.clawboyNotifyRomRemoved()
            } catch (_: Exception) {
                /** Non-fatal -- flag will be re-evaluated on next rom ready call. */
            }
        }
    }

    /** Constants for ClawBoy configuration defaults. */
    companion object {
        private const val DEFAULT_DECISION_INTERVAL = 1.5f
        private const val MILLIS_PER_SECOND = 1000
    }
}
