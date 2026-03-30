/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zeroclaw.android.data.local.entity.MemoryFactEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data access for read-only memory fact mirrors.
 *
 * Source of truth is Rust brain.db. This DAO provides
 * query access for the Memory Browser UI.
 */
@Dao
interface MemoryFactDao {
    /** Returns all facts ordered by creation time (newest first). */
    @Query("SELECT * FROM memory_facts ORDER BY created_at DESC")
    fun getAllFacts(): Flow<List<MemoryFactEntity>>

    /** Returns facts filtered by category, newest first. */
    @Query("SELECT * FROM memory_facts WHERE category = :category ORDER BY created_at DESC")
    fun getFactsByCategory(category: String): Flow<List<MemoryFactEntity>>

    /** Inserts or replaces a fact (used by mirror sync). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFact(fact: MemoryFactEntity)

    /** Deletes a fact by ID. */
    @Query("DELETE FROM memory_facts WHERE id = :id")
    suspend fun deleteFact(id: String)

    /** Returns total fact count. */
    @Query("SELECT COUNT(*) FROM memory_facts")
    suspend fun factCount(): Int
}
