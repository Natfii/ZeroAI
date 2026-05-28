/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Records the outcome of each agent interaction for statistical analysis.
 *
 * Feeds the provider leaderboard and improvement suggestions.
 *
 * @property id Auto-generated primary key.
 * @property routeHint Classifier hint active during this interaction.
 *   Historically tied to the now-removed cascade router; kept as a free-form
 *   string column so existing rows survive and future routing experiments
 *   can reuse the slot.
 * @property provider Provider used (e.g. "anthropic").
 * @property model Model used (e.g. "claude-sonnet-4-20250514").
 * @property outcome Classified outcome (SUCCESS, FAILURE, RETRY, DEGRADED, NEUTRAL).
 * @property toolCallCount Number of tool calls made.
 * @property latencyMs Response latency in milliseconds.
 * @property createdAt Epoch millis.
 */
@Entity(
    tableName = "interaction_outcomes",
    indices = [
        Index(value = ["provider"]),
        Index(value = ["created_at"]),
    ],
)
data class InteractionOutcomeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "route_hint") val routeHint: String,
    val provider: String,
    val model: String,
    val outcome: String,
    @ColumnInfo(name = "tool_call_count") val toolCallCount: Int,
    @ColumnInfo(name = "latency_ms") val latencyMs: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
