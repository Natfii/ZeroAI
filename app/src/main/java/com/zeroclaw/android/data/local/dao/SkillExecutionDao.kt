/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.zeroclaw.android.data.local.entity.SkillExecutionEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data access object for [SkillExecutionEntity] records.
 */
@Dao
interface SkillExecutionDao {
    /**
     * Observes execution history for a specific skill.
     *
     * @param skillName the skill to filter by
     * @param limit maximum number of records to return, newest first
     * @return a [Flow] emitting the list whenever the table changes
     */
    @Query(
        "SELECT * FROM skill_execution_history " +
            "WHERE skill_name = :skillName " +
            "ORDER BY started_at DESC LIMIT :limit",
    )
    fun observeBySkill(
        skillName: String,
        limit: Int = 100,
    ): Flow<List<SkillExecutionEntity>>

    /**
     * Observes all recent executions across all skills.
     *
     * @param limit maximum number of records to return, newest first
     * @return a [Flow] emitting the list whenever the table changes
     */
    @Query("SELECT * FROM skill_execution_history ORDER BY started_at DESC LIMIT :limit")
    fun observeRecent(limit: Int = 100): Flow<List<SkillExecutionEntity>>

    /**
     * Inserts a new execution record.
     *
     * @param entity the record to insert
     * @return the auto-generated row ID of the inserted record
     */
    @Insert
    suspend fun insert(entity: SkillExecutionEntity): Long

    /**
     * Updates a running execution with completion data.
     *
     * @param id row ID of the record to update
     * @param status final status string (`"success"` or `"failed"`)
     * @param outputSummary first 500 characters of the response, or null
     * @param errorMessage error details when status is `"failed"`, or null
     * @param completedAt epoch milliseconds when execution finished
     * @param durationMs wall-clock duration in milliseconds
     */
    @Query(
        "UPDATE skill_execution_history SET " +
            "status = :status, output_summary = :outputSummary, " +
            "error_message = :errorMessage, completed_at = :completedAt, " +
            "duration_ms = :durationMs WHERE id = :id",
    )
    @Suppress("LongParameterList")
    suspend fun updateCompletion(
        id: Long,
        status: String,
        outputSummary: String?,
        errorMessage: String?,
        completedAt: Long,
        durationMs: Long,
    )

    /**
     * Deletes oldest records for a skill, keeping only [retainCount].
     *
     * @param skillName the skill whose old records should be pruned
     * @param retainCount number of most-recent records to keep
     */
    @Query(
        "DELETE FROM skill_execution_history " +
            "WHERE skill_name = :skillName AND id NOT IN (" +
            "  SELECT id FROM skill_execution_history " +
            "  WHERE skill_name = :skillName " +
            "  ORDER BY started_at DESC LIMIT :retainCount" +
            ")",
    )
    suspend fun pruneOldest(
        skillName: String,
        retainCount: Int = 500,
    )
}
