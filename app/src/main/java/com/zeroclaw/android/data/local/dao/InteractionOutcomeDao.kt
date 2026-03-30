/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.zeroclaw.android.data.local.entity.InteractionOutcomeEntity

/**
 * Data access for interaction outcome statistics.
 *
 * Feeds the provider leaderboard and "Suggest Improvement" flow.
 */
@Dao
interface InteractionOutcomeDao {
    /** Inserts a new interaction outcome. */
    @Insert
    suspend fun insert(outcome: InteractionOutcomeEntity)

    /** Returns the last [limit] outcomes, newest first. */
    @Query("SELECT * FROM interaction_outcomes ORDER BY created_at DESC LIMIT :limit")
    suspend fun recentOutcomes(limit: Int): List<InteractionOutcomeEntity>

    /** Returns total outcome count. */
    @Query("SELECT COUNT(*) FROM interaction_outcomes")
    suspend fun outcomeCount(): Int
}
