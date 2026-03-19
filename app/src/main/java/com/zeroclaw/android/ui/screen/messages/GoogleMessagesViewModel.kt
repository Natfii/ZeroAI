/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.messages

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zeroclaw.android.ZeroAIApplication
import com.zeroclaw.ffi.FfiBridgeStatus
import com.zeroclaw.ffi.FfiBridgedConversation
import com.zeroclaw.ffi.messagesBridgeDisconnect
import com.zeroclaw.ffi.messagesBridgeDisconnectAndClear
import com.zeroclaw.ffi.messagesBridgeGetStatus
import com.zeroclaw.ffi.messagesBridgeListConversations
import com.zeroclaw.ffi.messagesBridgeSetAllowed
import com.zeroclaw.ffi.messagesBridgeStartPairing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * ViewModel for the Google Messages pairing and allowlist screen.
 *
 * Manages the three-phase flow: warning disclosure, QR pairing, and
 * conversation allowlist management. Polls bridge status during the
 * pairing phase and transitions to the allowlist once connected.
 *
 * @param application The application context for accessing files directory.
 */
class GoogleMessagesViewModel(
    application: Application,
) : AndroidViewModel(application) {
    /**
     * Sealed interface representing the screen state.
     *
     * Follows the project convention of typed UI states with
     * [Loading], [Warning], [Pairing], [Allowlist], and [Error] variants.
     */
    sealed interface UiState {
        /** Initial loading state while checking bridge status. */
        data object Loading : UiState

        /** Warning disclosure shown before pairing begins. */
        data object Warning : UiState

        /**
         * QR pairing in progress.
         *
         * @property qrPageUrl Local HTTP URL serving the QR code page.
         */
        data class Pairing(
            val qrPageUrl: String,
        ) : UiState

        /**
         * Paired and managing the conversation allowlist.
         *
         * @property conversations All bridged conversations from the store.
         */
        data class Allowlist(
            val conversations: List<FfiBridgedConversation>,
        ) : UiState

        /**
         * Error state with a descriptive message.
         *
         * @property message Human-readable error description.
         */
        data class Error(
            val message: String,
        ) : UiState
    }

    private val app = application as ZeroAIApplication

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)

    /** Observable screen state. */
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var pollingJob: Job? = null

    init {
        checkCurrentStatus()
    }

    /**
     * Checks the current bridge status and sets the appropriate initial state.
     *
     * Called during initialization and after disconnect operations to
     * determine whether to show the warning, pairing, or allowlist screen.
     */
    private fun checkCurrentStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                when (val status = messagesBridgeGetStatus()) {
                    is FfiBridgeStatus.Connected,
                    is FfiBridgeStatus.PhoneNotResponding,
                    is FfiBridgeStatus.Reconnecting,
                    -> {
                        refreshConversations()
                    }
                    is FfiBridgeStatus.AwaitingPairing -> {
                        if (status.qrPageUrl.isBlank()) {
                            messagesBridgeDisconnect()
                            _uiState.value = UiState.Warning
                        } else {
                            _uiState.value =
                                UiState.Pairing(
                                    qrPageUrl = status.qrPageUrl,
                                )
                            startPolling()
                        }
                    }
                    is FfiBridgeStatus.Unpaired -> {
                        _uiState.value = UiState.Warning
                    }
                }
            } catch (e: Exception) {
                _uiState.value = UiState.Warning
            }
        }
    }

    /**
     * Initiates the QR code pairing flow.
     *
     * Calls the FFI to start pairing, transitions the UI to the [UiState.Pairing]
     * state, and begins polling for connection status changes.
     */
    fun startPairing() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val qrPageUrl =
                    messagesBridgeStartPairing(
                        app.filesDir.absolutePath,
                    )
                _uiState.value = UiState.Pairing(qrPageUrl = qrPageUrl)
                startPolling()
            } catch (e: Exception) {
                _uiState.value =
                    UiState.Error(
                        message = "Failed to start pairing: ${e.message}",
                    )
            }
        }
    }

    /**
     * Sets whether the AI agent is allowed to read a specific conversation.
     *
     * @param conversationId Google internal conversation identifier.
     * @param allowed Whether the agent may read this conversation.
     * @param windowStartMs Optional epoch millis cutoff; null means all history.
     */
    fun setAllowed(
        conversationId: String,
        allowed: Boolean,
        windowStartMs: Long?,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                messagesBridgeSetAllowed(conversationId, allowed, windowStartMs)
                refreshConversations()
            } catch (e: Exception) {
                _uiState.value =
                    UiState.Error(
                        message = "Failed to update allowlist: ${e.message}",
                    )
            }
        }
    }

    /**
     * Disconnects the bridge while preserving conversation data.
     *
     * Stops the long-poll listener and returns to the warning state.
     */
    fun disconnect() {
        stopPolling()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                messagesBridgeDisconnect()
            } catch (_: Exception) {
                // best effort
            }
            _uiState.value = UiState.Warning
        }
    }

    /**
     * Disconnects the bridge and wipes all stored conversation data.
     *
     * Stops the listener, clears the SQLite store, and returns to
     * the warning state.
     */
    fun disconnectAndClear() {
        stopPolling()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                messagesBridgeDisconnectAndClear()
            } catch (_: Exception) {
                // best effort
            }
            _uiState.value = UiState.Warning
        }
    }

    /**
     * Refreshes the conversation list from the message store.
     *
     * Called after pairing completes and after allowlist changes to
     * keep the UI in sync with the Rust store.
     */
    fun refreshConversations() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val conversations = messagesBridgeListConversations()
                _uiState.value = UiState.Allowlist(conversations = conversations)
            } catch (e: Exception) {
                _uiState.value =
                    UiState.Error(
                        message = "Failed to load conversations: ${e.message}",
                    )
            }
        }
    }

    /**
     * Starts polling the bridge status every 2 seconds during pairing.
     *
     * Once connected, continues polling the conversation list until
     * data arrives (or [SYNC_WAIT_MS] elapses), then stops.
     */
    private fun startPolling() {
        stopPolling()
        pollingJob =
            viewModelScope.launch(Dispatchers.IO) {
                awaitConnection()
                awaitConversations()
            }
    }

    /**
     * Polls bridge status until it transitions out of [FfiBridgeStatus.AwaitingPairing].
     */
    private suspend fun awaitConnection() {
        while (currentCoroutineContext().isActive) {
            delay(POLL_INTERVAL_MS)
            val status = runCatching { messagesBridgeGetStatus() }.getOrNull() ?: continue
            when (status) {
                is FfiBridgeStatus.Connected,
                is FfiBridgeStatus.PhoneNotResponding,
                is FfiBridgeStatus.Reconnecting,
                -> return
                is FfiBridgeStatus.Unpaired -> {
                    _uiState.value = UiState.Warning
                    throw kotlinx.coroutines.CancellationException()
                }
                is FfiBridgeStatus.AwaitingPairing -> { /* keep waiting */ }
            }
        }
    }

    /**
     * Polls the conversation list until entries arrive or a timeout elapses.
     */
    private suspend fun awaitConversations() {
        val deadline = System.currentTimeMillis() + SYNC_WAIT_MS
        while (currentCoroutineContext().isActive) {
            val conversations = runCatching { messagesBridgeListConversations() }.getOrNull()
            _uiState.value = UiState.Allowlist(conversations = conversations.orEmpty())
            if (!conversations.isNullOrEmpty() || System.currentTimeMillis() > deadline) return
            delay(POLL_INTERVAL_MS)
        }
    }

    /** Cancels the pairing status polling job if active. */
    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    override fun onCleared() {
        super.onCleared()
        stopPolling()
    }

    private companion object {
        /** Status poll interval in milliseconds during the pairing phase. */
        const val POLL_INTERVAL_MS = 2_000L

        /** Maximum time to wait for conversations after Connected, in milliseconds. */
        const val SYNC_WAIT_MS = 15_000L
    }
}
