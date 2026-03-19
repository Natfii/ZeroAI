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
}
