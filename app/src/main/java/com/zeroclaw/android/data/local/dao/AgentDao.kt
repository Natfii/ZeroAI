/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.zeroclaw.android.data.local.entity.AgentEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data access object for agent CRUD operations.
 */
@Dao
interface AgentDao {
    /**
     * Observes all agents ordered by fixed slot position, then by name.
     *
     * @return A [Flow] emitting the current list of agents on every change.
     */
    @Query(
        """
        SELECT * FROM agents
        ORDER BY CASE slot_id
            WHEN 'gemini-api' THEN 0
            WHEN 'openai-api' THEN 1
            WHEN 'chatgpt' THEN 2
            WHEN 'anthropic-api' THEN 3
            WHEN 'claude-code' THEN 4
            WHEN 'ollama' THEN 5
            ELSE 999
        END ASC,
        name COLLATE NOCASE ASC
        """,
    )
    fun observeAll(): Flow<List<AgentEntity>>

    /**
     * Returns the agent with the given [id], or null if not found.
     *
     * @param id Unique agent identifier.
     * @return The matching [AgentEntity] or null.
     */
    @Query("SELECT * FROM agents WHERE id = :id")
    suspend fun getById(id: String): AgentEntity?

    /**
     * Inserts missing agents and ignores any that already exist.
     *
     * @param entities Agent entities to insert.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entities: List<AgentEntity>)

    /**
     * Inserts or updates an agent.
     *
     * @param entity The agent entity to upsert.
     */
    @Upsert
    suspend fun upsert(entity: AgentEntity)

    /**
     * Deletes the agent with the given [id].
     *
     * @param id Unique agent identifier.
     */
    @Query("DELETE FROM agents WHERE id = :id")
    suspend fun deleteById(id: String)

    /**
     * Toggles the enabled state of the agent with the given [id].
     *
     * @param id Unique agent identifier.
     */
    @Query("UPDATE agents SET is_enabled = NOT is_enabled WHERE id = :id")
    suspend fun toggleEnabled(id: String)

    /**
     * Sets the explicit enabled state for the agent with the given [id].
     *
     * @param id Unique agent identifier.
     * @param enabled Target enabled value.
     */
    @Query("UPDATE agents SET is_enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(
        id: String,
        enabled: Boolean,
    )

    /**
     * Disables every currently-enabled agent row except the one identified
     * by [keepId]. The `is_enabled = 1` guard avoids re-writing already-
     * disabled rows, which would otherwise force [observeAll] to emit a
     * spurious update.
     *
     * @param keepId Agent that should retain its current state.
     */
    @Query("UPDATE agents SET is_enabled = 0 WHERE id != :keepId AND is_enabled = 1")
    suspend fun disableAllExcept(keepId: String)

    /**
     * Flips the enabled state of the agent identified by [id] in a single
     * transaction that includes the current-state read.
     *
     * - If the row is currently enabled, it is disabled and no other rows
     *   are touched.
     * - If the row is currently disabled, every other row is disabled and
     *   this row is enabled — the "one agent at a time" invariant the
     *   Agents tab relies on now that cascade routing is gone.
     *
     * Performing the read inside the same transaction closes the race
     * window that a separate `getById` + `setEnabled` pair would leave
     * open under concurrent toggles on different rows.
     *
     * @param id Agent whose state to flip.
     */
    @Transaction
    suspend fun toggleExclusive(id: String) {
        val current = getById(id) ?: return
        if (current.isEnabled) {
            setEnabled(id, enabled = false)
        } else {
            disableAllExcept(id)
            setEnabled(id, enabled = true)
        }
    }
}
