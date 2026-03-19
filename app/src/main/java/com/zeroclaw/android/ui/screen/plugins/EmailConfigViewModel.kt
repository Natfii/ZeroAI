/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.plugins

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zeroclaw.android.ZeroAIApplication
import com.zeroclaw.android.data.email.EmailConfigState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for the email integration configuration screen.
 *
 * Reads the persisted [EmailConfigState] from the repository and exposes
 * it as an observable [StateFlow]. Save and test operations delegate to
 * the repository for Room/encrypted-prefs persistence, then forward the
 * config JSON to the Rust FFI layer on [Dispatchers.IO].
 */
class EmailConfigViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val app = application as ZeroAIApplication
    private val repository = app.emailConfigRepository

    /** Observable email configuration state from the repository. */
    val config: StateFlow<EmailConfigState> =
        repository
            .observe()
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
                EmailConfigState(),
            )

    private val _testResult = MutableStateFlow<String?>(null)

    /** Result message from the most recent connection test, or null. */
    val testResult: StateFlow<String?> = _testResult.asStateFlow()

    private val _isSaving = MutableStateFlow(false)

    /** Whether a save operation is currently in progress. */
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    /**
     * Persists the configuration and pushes it to the Rust daemon.
     *
     * Saves to Room + EncryptedSharedPreferences, then calls
     * `configureEmail` FFI on a background dispatcher.
     *
     * @param state The complete configuration snapshot to save.
     */
    fun save(state: EmailConfigState) {
        viewModelScope.launch {
            _isSaving.value = true
            try {
                repository.save(state)
                val json = repository.toConfigJson(state)
                withContext(Dispatchers.IO) {
                    com.zeroclaw.ffi.configureEmail(json)
                }
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                _testResult.value = "Save failed: ${e.message}"
            } finally {
                _isSaving.value = false
            }
        }
    }

    /**
     * Tests the IMAP/SMTP connection using the provided configuration.
     *
     * Calls `testEmailConnection` FFI on a background dispatcher and
     * updates [testResult] with the outcome.
     *
     * @param state The configuration to test against.
     */
    fun testConnection(state: EmailConfigState) {
        viewModelScope.launch {
            _testResult.value = "Testing..."
            try {
                val json = repository.toConfigJson(state)
                val result =
                    withContext(Dispatchers.IO) {
                        com.zeroclaw.ffi.testEmailConnection(json)
                    }
                _testResult.value = result
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                _testResult.value = "Connection failed: ${e.message}"
            }
        }
    }

    /** Constants for [EmailConfigViewModel]. */
    private companion object {
        const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
    }
}
