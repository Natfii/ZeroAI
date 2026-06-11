/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import com.zeroclaw.android.model.ComponentHealth
import com.zeroclaw.android.model.HealthDetail
import com.zeroclaw.android.model.SearchEngineHealth
import com.zeroclaw.ffi.FfiException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Bridge between the Android UI layer and the Rust structured health FFI.
 *
 * Wraps [com.zeroclaw.ffi.getHealthDetail] in a coroutine-safe suspend
 * function dispatched to [Dispatchers.IO].
 *
 * @param ioDispatcher Dispatcher for blocking FFI calls. Defaults to [Dispatchers.IO].
 */
class HealthBridge(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * Fetches structured health detail for all daemon components.
     *
     * Safe to call from the main thread; the underlying blocking FFI call is
     * dispatched to [ioDispatcher].
     *
     * @return Parsed [HealthDetail] snapshot.
     * @throws FfiException if the native layer reports an error.
     */
    @Throws(FfiException::class)
    suspend fun getHealthDetail(): HealthDetail =
        withContext(ioDispatcher) {
            val ffi = com.zeroclaw.ffi.getHealthDetail()
            HealthDetail(
                daemonRunning = ffi.daemonRunning,
                pid = ffi.pid.toLong(),
                uptimeSeconds = ffi.uptimeSeconds.toLong(),
                components =
                    ffi.components.map { c ->
                        ComponentHealth(
                            name = c.name,
                            status = c.status,
                            lastError = c.lastError,
                            restartCount = c.restartCount.toLong(),
                        )
                    },
            )
        }

    /**
     * Fetches per-engine health for the on-device meta search backend.
     *
     * Safe to call from the main thread; the underlying blocking FFI call is
     * dispatched to [ioDispatcher]. Returns one row per bundled engine, with
     * untouched engines reporting a "healthy" condition.
     *
     * @return Parsed [SearchEngineHealth] rows.
     * @throws FfiException if the native layer reports an error.
     */
    @Throws(FfiException::class)
    suspend fun getSearchEngineHealth(): List<SearchEngineHealth> =
        withContext(ioDispatcher) {
            com.zeroclaw.ffi.getSearchEngineHealth().map { engine ->
                SearchEngineHealth(
                    engineId = engine.engineId,
                    displayName = engine.displayName,
                    condition = engine.condition,
                    lastError = engine.lastError,
                    lastOkUnix = engine.lastOkUnix?.toLong(),
                    repairedAtUnix = engine.repairedAtUnix?.toLong(),
                    backoffRemainingSecs = engine.backoffRemainingSecs?.toLong(),
                )
            }
        }
}
