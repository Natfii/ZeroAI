/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.data.local.discord

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Archived Discord message from a guild channel.
 *
 * Maps directly to the `messages` table created by the Rust daemon in
 * `discord_archive.db`. Column names use snake_case to match the Rust schema.
 *
 * @property id Discord message snowflake ID.
 * @property channelId The channel this message belongs to.
 * @property guildId The guild this message belongs to.
 * @property authorId Discord user ID of the message author.
 * @property authorName Display name of the message author.
 * @property content Text content of the message.
 * @property timestamp Unix timestamp in seconds.
 * @property embedding Optional vector embedding blob computed by Rust.
 */
@Entity(
    tableName = "messages",
    indices = [
        Index(name = "idx_messages_channel", value = ["channel_id", "timestamp"]),
        Index(name = "idx_messages_timestamp", value = ["timestamp"]),
    ],
)
data class DiscordMessageEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "channel_id")
    val channelId: String,
    @ColumnInfo(name = "guild_id")
    val guildId: String,
    @ColumnInfo(name = "author_id")
    val authorId: String,
    @ColumnInfo(name = "author_name")
    val authorName: String,
    val content: String,
    val timestamp: Long,
    val embedding: ByteArray? = null,
)
