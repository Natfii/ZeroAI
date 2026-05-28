/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.twitter

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zeroclaw.android.ZeroAIApplication
import com.zeroclaw.android.model.AppSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val TAG = "TwitterConfigVM"

/**
 * ViewModel for the Twitter/X read tool configuration screen.
 *
 * The tool is anonymous read-only against X's public syndication endpoint,
 * so there is no auth state — just an enable flag and two tunables. Settings
 * stream directly from DataStore; the screen renders a spinner while `null`,
 * content otherwise. No sealed UiState — there are no failure modes.
 */
class TwitterConfigViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val settingsRepository = (application as ZeroAIApplication).settingsRepository

    /** Live settings stream. `null` while DataStore is still loading the first emission. */
    val settings: StateFlow<AppSettings?> =
        settingsRepository.settings
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /** Toggles the twitter read tool enabled state. */
    fun setEnabled(enabled: Boolean) = update { settingsRepository.setTwitterBrowseEnabled(enabled) }

    /** Updates the maximum tweets per read request. */
    fun setMaxItems(maxItems: Int) = update { settingsRepository.setTwitterBrowseMaxItems(maxItems.toLong()) }

    /** Updates the request timeout in seconds. */
    fun setTimeoutSecs(timeoutSecs: Int) = update { settingsRepository.setTwitterBrowseTimeoutSecs(timeoutSecs.toLong()) }

    private fun update(block: suspend () -> Unit) {
        viewModelScope.launch {
            block()
            pushConfigToDaemon()
        }
    }

    private suspend fun pushConfigToDaemon() {
        val current = settingsRepository.settings.first()
        try {
            com.zeroclaw.ffi.updateTwitterBrowseConfig(
                current.twitterBrowseEnabled,
                current.twitterBrowseMaxItems.toInt().toUInt(),
                current.twitterBrowseTimeoutSecs.toInt().toUInt(),
            )
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            // Daemon may not be running — settings are still saved. Log so
            // a real FFI/typing error doesn't slip past silently.
            Log.w(TAG, "updateTwitterBrowseConfig failed (daemon offline?): ${e.message}")
        }
    }

    /** Constants for [TwitterConfigViewModel]. */
    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
