/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for the Web Dashboard screen.
 *
 * Holds the gateway port and pairing token in-memory only.
 * Both are ephemeral and never persisted to disk.
 */
class WebDashboardViewModel(
    application: Application,
) : AndroidViewModel(application) {
    /**
     * Data needed to load the WebView.
     *
     * @property port The port the gateway HTTP server is bound to.
     * @property token Bearer token for authenticating with the gateway.
     */
    data class WebDashboardData(
        val port: Int,
        val token: String,
    )

    /** UI state for the web dashboard. */
    sealed interface UiState {
        /** Loading gateway connection info. */
        data object Loading : UiState

        /**
         * Daemon is not running or connection failed.
         *
         * @property message Human-readable error description.
         */
        data class Error(
            val message: String,
        ) : UiState

        /**
         * Ready to load the WebView.
         *
         * @property data The gateway connection data.
         */
        data class Content(
            val data: WebDashboardData,
        ) : UiState
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)

    /** Current UI state. */
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        loadDashboardInfo()
    }

    /** Attempt to connect to the gateway and obtain auth credentials. */
    fun retry() {
        _uiState.value = UiState.Loading
        loadDashboardInfo()
    }

    private fun loadDashboardInfo() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val port =
                    com.zeroclaw.ffi
                        .getGatewayPort()
                        .toInt()
                val token = com.zeroclaw.ffi.createPairingToken()
                _uiState.value =
                    UiState.Content(
                        WebDashboardData(port = port, token = token),
                    )
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                _uiState.value =
                    UiState.Error(
                        message = e.message ?: "Failed to connect to daemon",
                    )
            }
        }
    }
}
