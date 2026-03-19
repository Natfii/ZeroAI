/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.data.local.discord

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Configuration for an archived Discord channel.
 *
 * Maps directly to the `channel_config` table created by the Rust daemon in
 * `discord_archive.db`. Column names use snake_case to match the Rust schema.
 *
 * @property channelId Discord channel snowflake ID.
 * @property guildId The guild this channel belongs to.
 * @property channelName Human-readable channel name.
 * @property backfillDepth Configured backfill depth (none/3d/7d/30d/90d/all).
 * @property enabled Whether archiving is active for this channel (1 = yes, 0 = no).
 */
@Entity(tableName = "channel_config")
data class DiscordChannelConfigEntity(
    @PrimaryKey
    @ColumnInfo(name = "channel_id")
    val channelId: String,
    @ColumnInfo(name = "guild_id")
    val guildId: String,
    @ColumnInfo(name = "channel_name")
    val channelName: String,
    @ColumnInfo(name = "backfill_depth")
    val backfillDepth: String,
    val enabled: Int,
)
