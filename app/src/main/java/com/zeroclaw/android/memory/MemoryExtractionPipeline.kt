/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.memory

import com.zeroclaw.android.service.MemoryBridge
import com.zeroclaw.android.service.MemoryMetadata

/**
 * Orchestrates the memory write path.
 *
 * Three gates, in order of cost:
 * 1. [HeuristicExtractor] (~50μs, every message)
 * 2. [SensitivityFilter] (blocks PII/secrets)
 * 3. Storage via [MemoryBridge.storeWithMetadata]
 *
 * Respects power states: in Critical mode, only explicit
 * user memory_store commands are processed.
 *
 * Phase 2 (not yet implemented): messages that pass the interestingness gate
 * but yield zero stored facts will be flagged for LLM extraction.
 *
 * @param memoryBridge FFI bridge for storing facts.
 * @param powerStateProvider Returns current power state.
 */
class MemoryExtractionPipeline(
    private val memoryBridge: MemoryBridge,
    private val powerStateProvider: () -> PowerState,
) {
    /**
     * Power state for battery-aware memory operations.
     */
    enum class PowerState {
        /** Charging or >50%: all operations enabled. */
        FULL,

        /** 20-50%: heuristic extraction only. */
        CONSERVE,

        /** <20% or power save mode: memory read-only. */
        CRITICAL,
    }

    /**
     * Processes a user message through the extraction pipeline.
     *
     * Runs Gate 1 (heuristic extraction) then Gate 2 (sensitivity filter) then
     * Gate 3 (FFI storage) for each extracted fact. Returns early with zero stored
     * facts when [PowerState.CRITICAL] is active.
     *
     * Safe to call from the main thread; each [MemoryBridge.storeWithMetadata]
     * call is dispatched to [kotlinx.coroutines.Dispatchers.IO] internally.
     * Throws [kotlinx.coroutines.CancellationException] only if the coroutine scope
     * is cancelled; all other exceptions from the FFI or extraction layer are caught
     * and silently skipped to avoid disrupting the calling message flow.
     *
     * @param userMessage Raw user message text.
     * @param sessionId Current session identifier, reserved for Phase 2 scoped storage.
     * @return Number of facts extracted and stored.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    suspend fun process(
        userMessage: String,
        @Suppress("UnusedParameter") sessionId: String,
    ): Int {
        val powerState = powerStateProvider()
        if (powerState == PowerState.CRITICAL) return 0

        // Gate 1: Heuristic extraction
        val extracted =
            try {
                HeuristicExtractor.extract(userMessage)
            } catch (e: Exception) {
                emptyList()
            }

        var storedCount = 0

        for (fact in extracted) {
            // Gate 2: Sensitivity filter — skip if contains PII/secrets
            if (SensitivityFilter.containsSensitive(fact.content)) {
                continue
            }

            // Gate 3: Store via FFI — failure of one fact must not stop others
            val stored =
                try {
                    memoryBridge.storeWithMetadata(
                        key = fact.key,
                        metadata =
                            MemoryMetadata(
                                content = fact.content,
                                category = fact.category,
                                confidence = fact.confidence,
                                source = "heuristic",
                                tags = fact.tags,
                                decayHalfLifeDays = HEURISTIC_DECAY_HALF_LIFE_DAYS,
                            ),
                    )
                    true
                } catch (e: Exception) {
                    false
                }
            if (stored) storedCount++
        }

        // Phase 2 hook: flag message for LLM extraction when nothing was captured
        val needsLlmExtraction =
            storedCount == 0 &&
                InterestingnessFilter.isInteresting(userMessage, heuristicCaptured = false)
        if (needsLlmExtraction) {
            // Phase 2 will implement the actual flagging mechanism
        }

        return storedCount
    }

    private companion object {
        /** Ebbinghaus decay half-life in days for heuristic-extracted core facts. */
        const val HEURISTIC_DECAY_HALF_LIFE_DAYS: UInt = 365u
    }
}
