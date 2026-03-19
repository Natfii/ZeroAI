/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.twitter

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zeroclaw.android.ZeroAIApplication
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * ViewModel for the Twitter/X browse tool configuration screen.
 *
 * Reads connection state from settings repository (cookie presence, enabled state,
 * max items, timeout) and exposes it as [UiState]. Config changes write to DataStore
 * and hot-update the live daemon via FFI.
 */
class TwitterConfigViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val app = application as ZeroAIApplication
    private val settingsRepository = app.settingsRepository

    /** UI state for the Twitter config screen. */
    sealed interface UiState {
        /** Initial loading state. */
        data object Loading : UiState

        /** No data available (convention compliance; not expected in practice). */
        data object Empty : UiState

        /** Screen content with current configuration. */
        data class Content(
            /** Whether a Twitter account is currently connected. */
            val connected: Boolean,
            /** The connected Twitter handle, if available. */
            val handle: String?,
            /** Whether Twitter browsing is enabled. */
            val enabled: Boolean,
            /** Maximum items to fetch per request. */
            val maxItems: Int,
            /** Request timeout in seconds. */
            val timeoutSecs: Int,
        ) : UiState

        /** Error state with retry action. */
        data class Error(
            /** Human-readable error description. */
            val message: String,
            /** Callback to retry the failed operation. */
            val retry: () -> Unit,
        ) : UiState
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)

    /** Observable UI state for the Twitter config screen. */
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        loadState()
    }

    private fun loadState() {
        viewModelScope.launch {
            try {
                val settings = settingsRepository.settings.first()
                val hasCookie = settings.twitterBrowseCookieString.isNotBlank()
                val handle = settingsRepository.getTwitterBrowseHandle()
                _uiState.value =
                    UiState.Content(
                        connected = hasCookie,
                        handle = handle,
                        enabled = settings.twitterBrowseEnabled,
                        maxItems = settings.twitterBrowseMaxItems.toInt(),
                        timeoutSecs = settings.twitterBrowseTimeoutSecs.toInt(),
                    )
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                _uiState.value =
                    UiState.Error(
                        message = "Failed to load Twitter configuration: ${e.message}",
                        retry = ::loadState,
                    )
            }
        }
    }

    /**
     * Called after successful WebView login with extracted cookies.
     *
     * @param cookieString The full cookie string containing ct0 and auth_token.
     */
    fun onCookiesExtracted(cookieString: String) {
        viewModelScope.launch {
            try {
                settingsRepository.setTwitterBrowseCookieString(cookieString)
                try {
                    com.zeroclaw.ffi.setTwitterBrowseCookie(cookieString)
                } catch (_: Exception) {
                }
                val user =
                    try {
                        com.zeroclaw.ffi.verifyTwitterConnection(cookieString)
                    } catch (_: Exception) {
                        null
                    }
                val handle = user?.handle
                if (handle != null) {
                    settingsRepository.setTwitterBrowseHandle(handle)
                }
                loadState()
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                _uiState.value =
                    UiState.Error(
                        message = "Failed to save Twitter cookies: ${e.message}",
                        retry = ::loadState,
                    )
            }
        }
    }

    /** Disconnects the Twitter account by clearing cookies and resetting state. */
    fun disconnect() {
        viewModelScope.launch {
            settingsRepository.setTwitterBrowseCookieString("")
            settingsRepository.setTwitterBrowseHandle(null)
            settingsRepository.setTwitterBrowseEnabled(false)
            try {
                com.zeroclaw.ffi.clearTwitterBrowseCookie()
            } catch (_: Exception) {
            }
            loadState()
        }
    }

    /** Toggles the twitter browse tool enabled state. */
    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setTwitterBrowseEnabled(enabled)
            pushConfigToDaemon()
            loadState()
        }
    }

    /** Updates the maximum items per browse request. */
    fun setMaxItems(maxItems: Int) {
        viewModelScope.launch {
            settingsRepository.setTwitterBrowseMaxItems(maxItems.toLong())
            pushConfigToDaemon()
            loadState()
        }
    }

    /** Updates the request timeout in seconds. */
    fun setTimeoutSecs(timeoutSecs: Int) {
        viewModelScope.launch {
            settingsRepository.setTwitterBrowseTimeoutSecs(timeoutSecs.toLong())
            pushConfigToDaemon()
            loadState()
        }
    }

    private suspend fun pushConfigToDaemon() {
        try {
            val settings = settingsRepository.settings.first()
            com.zeroclaw.ffi.updateTwitterBrowseConfig(
                settings.twitterBrowseEnabled,
                settings.twitterBrowseMaxItems.toInt().toUInt(),
                settings.twitterBrowseTimeoutSecs.toInt().toUInt(),
            )
        } catch (_: Exception) {
        }
    }
}
