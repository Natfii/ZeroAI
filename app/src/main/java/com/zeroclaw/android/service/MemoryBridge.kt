// Copyright 2026 @Natfii, MIT License

package com.zeroclaw.android.service

import com.zeroclaw.android.model.MemoryEntry
import com.zeroclaw.ffi.FfiException
import com.zeroclaw.ffi.FfiMaintenanceReport
import com.zeroclaw.ffi.FfiMemoryEntry
import com.zeroclaw.ffi.FfiMemoryEntryScored
import com.zeroclaw.ffi.FfiWorkingContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Bridge between the Android UI layer and the Rust memory browsing FFI.
 *
 * Wraps the memory-related UniFFI-generated functions in coroutine-safe
 * suspend functions dispatched to [Dispatchers.IO].
 *
 * @param ioDispatcher Dispatcher for blocking FFI calls. Defaults to [Dispatchers.IO].
 */
class MemoryBridge(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * Lists memory entries, optionally filtered by category and/or session.
     *
     * Safe to call from the main thread; the underlying blocking FFI call is
     * dispatched to [ioDispatcher].
     *
     * @param category Optional category filter (e.g. "core", "daily", "conversation").
     * @param limit Maximum number of entries to return.
     * @param sessionId Optional session ID to scope results to a specific session.
     * @return List of [MemoryEntry] instances.
     * @throws FfiException if the native layer reports an error.
     */
    @Throws(FfiException::class)
    suspend fun listMemories(
        category: String? = null,
        limit: UInt = DEFAULT_LIMIT,
        sessionId: String? = null,
    ): List<MemoryEntry> =
        withContext(ioDispatcher) {
            com.zeroclaw.ffi
                .listMemories(category, limit, sessionId)
                .map { it.toModel() }
        }

    /**
     * Searches memory entries by keyword query, optionally scoped to a session.
     *
     * Safe to call from the main thread; the underlying blocking FFI call is
     * dispatched to [ioDispatcher].
     *
     * @param query Search keyword.
     * @param limit Maximum number of results to return.
     * @param sessionId Optional session ID to scope results to a specific session.
     * @return List of [MemoryEntry] instances ranked by relevance.
     * @throws FfiException if the native layer reports an error.
     */
    @Throws(FfiException::class)
    suspend fun recallMemory(
        query: String,
        limit: UInt = DEFAULT_LIMIT,
        sessionId: String? = null,
    ): List<MemoryEntry> =
        withContext(ioDispatcher) {
            com.zeroclaw.ffi
                .recallMemory(query, limit, sessionId)
                .map { it.toModel() }
        }

    /**
     * Deletes a memory entry by key.
     *
     * Safe to call from the main thread; the underlying blocking FFI call is
     * dispatched to [ioDispatcher].
     *
     * @param key The key of the memory entry to delete.
     * @return `true` if the entry was found and deleted, `false` otherwise.
     * @throws FfiException if the native layer reports an error.
     */
    @Throws(FfiException::class)
    suspend fun forgetMemory(key: String): Boolean =
        withContext(ioDispatcher) {
            com.zeroclaw.ffi.forgetMemory(key)
        }

    /**
     * Returns the total number of memory entries.
     *
     * Safe to call from the main thread; the underlying blocking FFI call is
     * dispatched to [ioDispatcher].
     *
     * @return Total count of memory entries.
     * @throws FfiException if the native layer reports an error.
     */
    @Throws(FfiException::class)
    suspend fun memoryCount(): UInt =
        withContext(ioDispatcher) {
            com.zeroclaw.ffi.memoryCount()
        }

    /**
     * Stores a memory with full MemCore metadata.
     *
     * Safe to call from the main thread; the underlying blocking FFI call is
     * dispatched to [ioDispatcher].
     *
     * @param key Unique fact key.
     * @param metadata Full MemCore metadata for the entry.
     * @throws FfiException if the native layer reports an error.
     */
    @Throws(FfiException::class)
    suspend fun storeWithMetadata(
        key: String,
        metadata: MemoryMetadata,
    ): Unit =
        withContext(ioDispatcher) {
            // FFI name resolved by UniFFI from Rust `store_memory_with_metadata`
            com.zeroclaw.ffi.storeMemoryWithMetadata(
                key,
                metadata.content,
                metadata.category,
                metadata.confidence,
                metadata.source,
                metadata.tags,
                metadata.decayHalfLifeDays,
            )
        }

    /**
     * Recalls memories with three-factor scored ranking.
     *
     * Safe to call from the main thread; the underlying blocking FFI call is
     * dispatched to [ioDispatcher].
     *
     * @param query Search query text.
     * @param limit Maximum results to return.
     * @param sessionId Optional session ID for scoped recall.
     * @return Scored memory entries, sorted by combined score.
     * @throws FfiException if the native layer reports an error.
     */
    @Throws(FfiException::class)
    suspend fun recallScored(
        query: String,
        limit: UInt = DEFAULT_LIMIT,
        sessionId: String? = null,
    ): List<FfiMemoryEntryScored> =
        withContext(ioDispatcher) {
            // FFI name resolved by UniFFI from Rust `recall_memory_scored`
            com.zeroclaw.ffi.recallMemoryScored(query, limit, sessionId)
        }

    /**
     * Assembles working context for system prompt injection.
     *
     * Safe to call from the main thread; the underlying blocking FFI call is
     * dispatched to [ioDispatcher].
     *
     * @param message Current user message for semantic recall.
     * @param sessionId Current session identifier.
     * @param tokenBudget Total token budget for all context blocks.
     * @return Assembled working context with identity, recall, and episodic blocks.
     * @throws FfiException if the native layer reports an error.
     */
    @Throws(FfiException::class)
    suspend fun assembleContext(
        message: String,
        sessionId: String,
        tokenBudget: UInt,
    ): FfiWorkingContext =
        withContext(ioDispatcher) {
            // FFI name resolved by UniFFI from Rust `assemble_context`
            com.zeroclaw.ffi.assembleContext(message, sessionId, tokenBudget)
        }

    /**
     * Runs daily memory maintenance (pruning + merging).
     *
     * Safe to call from the main thread; the underlying blocking FFI call is
     * dispatched to [ioDispatcher].
     *
     * @return Report with pruned and merged counts.
     * @throws FfiException if the native layer reports an error.
     */
    @Throws(FfiException::class)
    suspend fun runMaintenance(): FfiMaintenanceReport =
        withContext(ioDispatcher) {
            // FFI name resolved by UniFFI from Rust `run_memory_maintenance`
            com.zeroclaw.ffi.runMemoryMaintenance()
        }

    /** Constants for [MemoryBridge]. */
    companion object {
        /** Default maximum number of memory entries to retrieve. */
        private const val DEFAULT_LIMIT_INT = 100

        /** Default limit as [UInt] for FFI calls. */
        val DEFAULT_LIMIT: UInt = DEFAULT_LIMIT_INT.toUInt()
    }
}

/**
 * Converts an FFI memory entry record to the domain model.
 *
 * @receiver FFI-generated [FfiMemoryEntry] record from the native layer.
 * @return Domain [MemoryEntry] model with identical field values.
 */
private fun FfiMemoryEntry.toModel(): MemoryEntry =
    MemoryEntry(
        id = id,
        key = key,
        content = content,
        category = category,
        timestamp = timestamp,
        score = score,
    )

/**
 * Full MemCore metadata for storing a memory entry.
 *
 * Groups the seven metadata fields required by [MemoryBridge.storeWithMetadata]
 * to stay within the six-parameter limit enforced by detekt.
 *
 * @property content Fact content text.
 * @property category Memory category — `"core"`, `"daily"`, `"conversation"`, or custom.
 * @property confidence Extraction confidence in `[0.0, 1.0]`.
 * @property source Origin: `"heuristic"`, `"llm"`, `"agent"`, or `"user"`.
 * @property tags Comma-separated tags describing the entry.
 * @property decayHalfLifeDays Ebbinghaus half-life in days for memory decay.
 */
data class MemoryMetadata(
    val content: String,
    val category: String,
    val confidence: Double,
    val source: String,
    val tags: String,
    val decayHalfLifeDays: UInt,
)
