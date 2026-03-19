/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.data.local.discord

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Query

/**
 * Read-only data access for the Discord message archive.
 *
 * This DAO queries `discord_archive.db` which is written by the Rust daemon.
 * All queries are suspend (one-shot) to avoid live observers and save battery.
 */
@Dao
interface DiscordMessageDao {
    /**
     * Fetches a page of messages for a channel, ordered newest first.
     *
     * @param channelId Discord channel snowflake ID.
     * @param limit Maximum number of messages to return.
     * @param offset Number of messages to skip for pagination.
     * @return List of messages in reverse chronological order.
     */
    @Query(
        """SELECT * FROM messages
           WHERE channel_id = :channelId
           ORDER BY timestamp DESC
           LIMIT :limit OFFSET :offset""",
    )
    suspend fun getMessages(
        channelId: String,
        limit: Int,
        offset: Int,
    ): List<DiscordMessageEntity>

    /**
     * Returns the total message count for a channel.
     *
     * @param channelId Discord channel snowflake ID.
     * @return Number of archived messages in the channel.
     */
    @Query("SELECT COUNT(*) FROM messages WHERE channel_id = :channelId")
    suspend fun countByChannel(channelId: String): Int

    /**
     * Returns the top 5 authors by message count in a channel.
     *
     * @param channelId Discord channel snowflake ID.
     * @return Up to 5 [AuthorCount] entries ordered by message count descending.
     */
    @Query(
        """SELECT author_name, COUNT(*) as cnt FROM messages
           WHERE channel_id = :channelId
           GROUP BY author_name ORDER BY cnt DESC LIMIT 5""",
    )
    suspend fun topAuthors(channelId: String): List<AuthorCount>

    /**
     * Returns the earliest and latest timestamps for a channel.
     *
     * @param channelId Discord channel snowflake ID.
     * @return A [DateRange] with the min/max timestamps, or null if no messages exist.
     */
    @Query(
        """SELECT MIN(timestamp) as earliest, MAX(timestamp) as latest
           FROM messages WHERE channel_id = :channelId""",
    )
    suspend fun dateRange(channelId: String): DateRange?

    /**
     * Returns all configured channels ordered by name.
     *
     * @return List of all [DiscordChannelConfigEntity] rows.
     */
    @Query("SELECT * FROM channel_config ORDER BY channel_name")
    suspend fun getAllChannelConfigs(): List<DiscordChannelConfigEntity>
}

/**
 * Projection for top-author aggregation.
 *
 * @property authorName Display name of the author.
 * @property cnt Number of messages by this author in the queried channel.
 */
data class AuthorCount(
    @ColumnInfo(name = "author_name")
    val authorName: String,
    val cnt: Int,
)

/**
 * Projection for date range aggregation.
 *
 * @property earliest Unix timestamp of the oldest message, or null if empty.
 * @property latest Unix timestamp of the newest message, or null if empty.
 */
data class DateRange(
    val earliest: Long?,
    val latest: Long?,
)
