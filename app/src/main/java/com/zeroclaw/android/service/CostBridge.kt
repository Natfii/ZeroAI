/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import com.zeroclaw.android.model.CostSummary
import com.zeroclaw.ffi.FfiException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Bridge between the Android UI layer and the Rust cost-tracking FFI.
 *
 * Wraps [com.zeroclaw.ffi.getCostSummary] in a coroutine-safe suspend
 * function dispatched to [Dispatchers.IO].
 *
 * @param ioDispatcher Dispatcher for blocking FFI calls. Defaults to [Dispatchers.IO].
 */
class CostBridge(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * Fetches the aggregated cost summary for the current daemon session.
     *
     * Safe to call from the main thread; the underlying blocking FFI call is
     * dispatched to [ioDispatcher].
     *
     * @return Parsed [CostSummary] snapshot.
     * @throws FfiException if the native layer reports an error.
     */
    @Throws(FfiException::class)
    suspend fun getCostSummary(): CostSummary =
        withContext(ioDispatcher) {
            val ffi = com.zeroclaw.ffi.getCostSummary()
            CostSummary(
                sessionCostUsd = ffi.sessionCostUsd,
                dailyCostUsd = ffi.dailyCostUsd,
                monthlyCostUsd = ffi.monthlyCostUsd,
                totalTokens = ffi.totalTokens.toLong(),
                requestCount = ffi.requestCount.toInt(),
                modelBreakdownJson = ffi.modelBreakdownJson,
            )
        }
}
