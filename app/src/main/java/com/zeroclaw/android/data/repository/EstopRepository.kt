/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data.repository

import android.util.Log
import com.zeroclaw.ffi.engageEstop
import com.zeroclaw.ffi.getEstopStatus
import com.zeroclaw.ffi.resumeEstop
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Repository for emergency stop state, polling the FFI layer.
 *
 * Exposes [engaged] as a [StateFlow] that updates every [POLL_INTERVAL_MS]
 * milliseconds while polling is enabled.
 *
 * @param scope Coroutine scope for the polling loop.
 * @param ioDispatcher Dispatcher for blocking FFI calls.
 */
class EstopRepository(
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private var pollJob: Job? = null

    private val _engaged = MutableStateFlow(false)

    /** Whether the emergency stop is currently engaged. */
    val engaged: StateFlow<Boolean> = _engaged.asStateFlow()

    private val _engagedAtMs = MutableStateFlow<Long?>(null)

    /** Epoch milliseconds when estop was last engaged. */
    val engagedAtMs: StateFlow<Long?> = _engagedAtMs.asStateFlow()

    /**
     * Enables or disables polling from the FFI layer.
     *
     * Safe to call repeatedly; duplicate enable/disable requests are ignored.
     */
    fun setPollingEnabled(enabled: Boolean) {
        if (enabled) {
            startPolling()
        } else {
            stopPolling()
        }
    }

    /**
     * Starts polling estop status from the FFI layer.
     *
     * Safe to call multiple times; only one polling loop runs.
     */
    @Suppress("TooGenericExceptionCaught")
    fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob =
            scope.launch(ioDispatcher) {
                while (isActive) {
                    try {
                        val status = getEstopStatus()
                        _engaged.value = status.engaged
                        _engagedAtMs.value = status.engagedAtMs
                    } catch (e: Exception) {
                        Log.w(TAG, "Estop poll failed: ${e.message}")
                    }
                    delay(POLL_INTERVAL_MS)
                }
            }
    }

    /** Stops background estop polling. */
    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    /**
     * Engages the emergency stop.
     *
     * @return `true` if successfully engaged.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun engage(): Boolean =
        withContext(ioDispatcher) {
            try {
                engageEstop()
                _engaged.value = true
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to engage estop: ${e.message}")
                false
            }
        }

    /**
     * Resumes from the emergency stop.
     *
     * @return `true` if successfully resumed.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun resume(): Boolean =
        withContext(ioDispatcher) {
            try {
                resumeEstop()
                _engaged.value = false
                _engagedAtMs.value = null
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to resume estop: ${e.message}")
                false
            }
        }

    /** Constants for [EstopRepository]. */
    companion object {
        private const val TAG = "EstopRepository"
        private const val POLL_INTERVAL_MS = 2000L
    }
}
