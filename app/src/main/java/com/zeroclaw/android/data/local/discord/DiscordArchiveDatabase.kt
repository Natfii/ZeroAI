/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.data.local.discord

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import java.io.File

/**
 * Logically read-only Room database wrapping the Rust-managed `discord_archive.db`.
 *
 * Room 2.6 does not expose a builder-level read-only mode, so the database
 * is opened read-write at the SQLite level. Read-only intent is enforced by
 * the DAO contract: all methods are `@Query` (SELECT) only — no `@Insert`,
 * `@Update`, or `@Delete` annotations exist.
 *
 * Opened without encryption because guild channel data is public.
 * The Rust daemon writes via rusqlite with WAL mode; Kotlin reads via Room.
 *
 * This database is completely independent from the main [ZeroAIDatabase]
 * which uses SQLCipher encryption.
 */
@Database(
    entities = [DiscordMessageEntity::class, DiscordChannelConfigEntity::class],
    version = 4,
    exportSchema = false,
)
abstract class DiscordArchiveDatabase : RoomDatabase() {
    /** Read-only access to archived Discord messages and channel config. */
    abstract fun messageDao(): DiscordMessageDao

    /** Factory for [DiscordArchiveDatabase]. */
    companion object {
        /**
         * Opens the archive database at the given path if the file exists.
         *
         * Returns null when the daemon has not yet created the archive, so
         * callers must handle the absent case gracefully.
         *
         * @param context Application context.
         * @param dbFile Absolute path to `discord_archive.db`.
         * @return The database instance, or null if the file does not exist yet.
         */
        @Suppress("TooGenericExceptionCaught")
        fun openIfExists(
            context: Context,
            dbFile: File,
        ): DiscordArchiveDatabase? {
            if (!dbFile.exists()) return null
            return try {
                val db =
                    Room
                        .databaseBuilder(
                            context,
                            DiscordArchiveDatabase::class.java,
                            dbFile.absolutePath,
                        ).setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                        .fallbackToDestructiveMigration()
                        .build()
                db.openHelper.writableDatabase
                db
            } catch (e: Exception) {
                Log.w(TAG, "Schema mismatch, deleting stale archive: ${e.message}")
                dbFile.delete()
                File(dbFile.path + "-wal").delete()
                File(dbFile.path + "-shm").delete()
                null
            }
        }

        private const val TAG = "DiscordArchiveDB"
    }
}
