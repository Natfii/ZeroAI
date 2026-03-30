/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Read-only mirror of memory facts for Android UI display.
 *
 * Source of truth is Rust brain.db. Mirror syncs on FFI write events.
 *
 * @property id Unique fact identifier.
 * @property key Fact key (e.g. "user_name", "preference_a1b2c3").
 * @property contentPreview First 200 chars of content.
 * @property category Memory category (core, daily, custom).
 * @property tags Comma-separated tags.
 * @property confidence Extraction confidence [0.0, 1.0].
 * @property source Extraction source (heuristic, llm, agent, user).
 * @property accessCount Number of times recalled.
 * @property createdAt Epoch millis.
 * @property lastAccessedAt Epoch millis of last recall, null if never.
 * @property decayHalfLifeDays Ebbinghaus half-life in days.
 */
@Entity(
    tableName = "memory_facts",
    indices = [
        Index(value = ["category"]),
        Index(value = ["last_accessed_at"]),
    ],
)
data class MemoryFactEntity(
    @PrimaryKey val id: String,
    val key: String,
    @ColumnInfo(name = "content_preview") val contentPreview: String,
    val category: String,
    val tags: String,
    val confidence: Double,
    val source: String,
    @ColumnInfo(name = "access_count") val accessCount: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "last_accessed_at") val lastAccessedAt: Long?,
    @ColumnInfo(name = "decay_half_life_days") val decayHalfLifeDays: Int,
)
