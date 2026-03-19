/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.zeroclaw.android.data.local.dao.ActivityEventDao
import com.zeroclaw.android.data.local.dao.AgentDao
import com.zeroclaw.android.data.local.dao.ConnectedChannelDao
import com.zeroclaw.android.data.local.dao.EmailConfigDao
import com.zeroclaw.android.data.local.dao.LogEntryDao
import com.zeroclaw.android.data.local.dao.PluginDao
import com.zeroclaw.android.data.local.dao.SkillExecutionDao
import com.zeroclaw.android.data.local.dao.TerminalEntryDao
import com.zeroclaw.android.data.local.entity.ActivityEventEntity
import com.zeroclaw.android.data.local.entity.AgentEntity
import com.zeroclaw.android.data.local.entity.ConnectedChannelEntity
import com.zeroclaw.android.data.local.entity.EmailConfigEntity
import com.zeroclaw.android.data.local.entity.LogEntryEntity
import com.zeroclaw.android.data.local.entity.PluginEntity
import com.zeroclaw.android.data.local.entity.SkillExecutionEntity
import com.zeroclaw.android.data.local.entity.TerminalEntryEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Room database for persistent storage of agents, plugins, log entries,
 * and activity events.
 *
 * Use [build] to create an instance with seed data callback.
 *
 * Migration strategy: explicit [Migration] objects in [MIGRATIONS] are
 * required for all schema changes. If a migration is missing, Room will
 * throw [IllegalStateException] at startup rather than silently dropping
 * data.
 */
@Database(
    entities = [
        AgentEntity::class,
        PluginEntity::class,
        LogEntryEntity::class,
        ActivityEventEntity::class,
        ConnectedChannelEntity::class,
        TerminalEntryEntity::class,
        EmailConfigEntity::class,
        SkillExecutionEntity::class,
    ],
    version = 16,
    exportSchema = true,
)
abstract class ZeroAIDatabase : RoomDatabase() {
    /** Data access object for agent operations. */
    abstract fun agentDao(): AgentDao

    /** Data access object for plugin operations. */
    abstract fun pluginDao(): PluginDao

    /** Data access object for log entry operations. */
    abstract fun logEntryDao(): LogEntryDao

    /** Data access object for activity event operations. */
    abstract fun activityEventDao(): ActivityEventDao

    /** Data access object for connected channel operations. */
    abstract fun connectedChannelDao(): ConnectedChannelDao

    /** Data access object for terminal REPL entry operations. */
    abstract fun terminalEntryDao(): TerminalEntryDao

    /** Data access object for the singleton email configuration. */
    abstract fun emailConfigDao(): EmailConfigDao

    /** Data access object for skill execution history. */
    abstract fun skillExecutionDao(): SkillExecutionDao

    /** Factory and constants for [ZeroAIDatabase]. */
    companion object {
        /** Database file name. */
        private const val DATABASE_NAME = "zeroclaw.db"

        /** Migration from schema version 1 to 2: adds the connected_channels table. */
        private val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `connected_channels` (
                            `id` TEXT NOT NULL,
                            `type` TEXT NOT NULL,
                            `is_enabled` INTEGER NOT NULL,
                            `config_json` TEXT NOT NULL,
                            `created_at` INTEGER NOT NULL,
                            PRIMARY KEY(`id`)
                        )
                        """.trimIndent(),
                    )
                }
            }

        /** Migration from schema version 2 to 3: adds temperature and max_depth to agents. */
        private val MIGRATION_2_3 =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE agents ADD COLUMN temperature REAL")
                    db.execSQL(
                        "ALTER TABLE agents ADD COLUMN max_depth INTEGER NOT NULL DEFAULT 3",
                    )
                }
            }

        /** Migration from schema version 3 to 4: adds the chat_messages table. */
        private val MIGRATION_3_4 =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `chat_messages` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `timestamp` INTEGER NOT NULL,
                            `content` TEXT NOT NULL,
                            `is_from_user` INTEGER NOT NULL
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_chat_messages_timestamp` ON `chat_messages` (`timestamp`)",
                    )
                }
            }

        /** Migration from schema version 4 to 5: adds remote_version column to plugins. */
        private val MIGRATION_4_5 =
            object : Migration(4, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE plugins ADD COLUMN remote_version TEXT")
                }
            }

        /** Migration from schema version 5 to 6: adds images_json column to chat_messages. */
        private val MIGRATION_5_6 =
            object : Migration(5, 6) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE chat_messages ADD COLUMN images_json TEXT DEFAULT NULL",
                    )
                }
            }

        /** Migration from schema version 6 to 7: adds unique index on connected_channels.type. */
        private val MIGRATION_6_7 =
            object : Migration(6, 7) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS `index_connected_channels_type` ON `connected_channels` (`type`)",
                    )
                }
            }

        /** Migration from schema version 7 to 8: adds the terminal_entries table. */
        private val MIGRATION_7_8 =
            object : Migration(7, 8) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `terminal_entries` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `content` TEXT NOT NULL,
                            `entry_type` TEXT NOT NULL,
                            `timestamp` INTEGER NOT NULL,
                            `image_uris` TEXT NOT NULL DEFAULT '[]'
                        )
                        """.trimIndent(),
                    )
                }
            }

        /** Migration from schema version 8 to 9: drops the deprecated chat_messages table. */
        private val MIGRATION_8_9 =
            object : Migration(8, 9) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("DROP TABLE IF EXISTS `chat_messages`")
                }
            }

        /** Migration from schema version 9 to 10: inserts official plugin rows. */
        @Suppress("LongMethod")
        private val MIGRATION_9_10 =
            object : Migration(9, 10) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    val officialPlugins =
                        listOf(
                            arrayOf(
                                "official-web-search",
                                "Web Search",
                                "Search the web via DuckDuckGo or Brave.",
                                "1.0.0",
                                "ZeroAI",
                                "TOOL",
                                1,
                                0,
                                "{}",
                            ),
                            arrayOf(
                                "official-web-fetch",
                                "Web Fetch",
                                "Fetch and read web page content.",
                                "1.0.0",
                                "ZeroAI",
                                "TOOL",
                                1,
                                0,
                                "{}",
                            ),
                            arrayOf(
                                "official-http-request",
                                "HTTP Request",
                                "Make HTTP calls to external APIs.",
                                "1.0.0",
                                "ZeroAI",
                                "TOOL",
                                1,
                                0,
                                "{}",
                            ),
                            arrayOf(
                                "official-browser",
                                "Browser",
                                "Browse and interact with web pages.",
                                "1.0.0",
                                "ZeroAI",
                                "TOOL",
                                1,
                                0,
                                "{}",
                            ),
                            arrayOf(
                                "official-composio",
                                "Composio",
                                "Third-party tool integrations via Composio.",
                                "1.0.0",
                                "ZeroAI",
                                "TOOL",
                                1,
                                0,
                                "{}",
                            ),
                            arrayOf(
                                "official-vision",
                                "Vision",
                                "Process images for multimodal queries.",
                                "1.0.0",
                                "ZeroAI",
                                "TOOL",
                                1,
                                1,
                                "{}",
                            ),
                            arrayOf(
                                "official-transcription",
                                "Transcription",
                                "Transcribe audio via Whisper-compatible API.",
                                "1.0.0",
                                "ZeroAI",
                                "TOOL",
                                1,
                                0,
                                "{}",
                            ),
                            arrayOf(
                                "official-query-classification",
                                "Query Classification",
                                "Classify queries for intelligent model routing.",
                                "1.0.0",
                                "ZeroAI",
                                "OTHER",
                                1,
                                0,
                                "{}",
                            ),
                        )
                    for (p in officialPlugins) {
                        db.execSQL(
                            """INSERT OR IGNORE INTO plugins
                               (id, name, description, version, author, category,
                                is_installed, is_enabled, config_json)
                               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                            p,
                        )
                    }
                }
            }

        /**
         * Migration from schema version 10 to 11: removes Browser plugin
         * (unavailable on Android) and updates stale web search description.
         */
        private val MIGRATION_10_11 =
            object : Migration(10, 11) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("DELETE FROM plugins WHERE id = 'official-browser'")
                    db.execSQL(
                        """UPDATE plugins SET description = 'Search the web via DuckDuckGo.'
                           WHERE id = 'official-web-search'""",
                    )
                }
            }

        /** Migration from schema version 11 to 12: inserts the Twitter Browse official plugin. */
        private val MIGRATION_11_12 =
            object : Migration(11, 12) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """INSERT OR IGNORE INTO plugins
                           (id, name, description, version, author, category,
                            is_installed, is_enabled, config_json)
                           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                        arrayOf(
                            "official-twitter-browse",
                            "Twitter Browse",
                            "Browse X/Twitter using authenticated cookies.",
                            "1.0.0",
                            "ZeroAI",
                            "TOOL",
                            1,
                            0,
                            "{}",
                        ),
                    )
                }
            }

        /** Migration from schema version 12 to 13: adds slot_id to agents. */
        private val MIGRATION_12_13 =
            object : Migration(12, 13) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE agents ADD COLUMN slot_id TEXT NOT NULL DEFAULT ''",
                    )
                }
            }

        /** Migration from schema version 13 to 14: updates web search description. */
        private val MIGRATION_13_14 =
            object : Migration(13, 14) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """UPDATE plugins SET description = 'Search the web via Brave or Google.'
                           WHERE id = 'official-web-search'""",
                    )
                }
            }

        /** Migration from schema version 14 to 15: adds the email_config table. */
        private val MIGRATION_14_15 =
            object : Migration(14, 15) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `email_config` (
                            `id` INTEGER NOT NULL,
                            `imap_host` TEXT NOT NULL DEFAULT '',
                            `imap_port` INTEGER NOT NULL DEFAULT 993,
                            `smtp_host` TEXT NOT NULL DEFAULT '',
                            `smtp_port` INTEGER NOT NULL DEFAULT 465,
                            `address` TEXT NOT NULL DEFAULT '',
                            `check_times` TEXT NOT NULL DEFAULT '',
                            `is_enabled` INTEGER NOT NULL DEFAULT 0,
                            PRIMARY KEY(`id`)
                        )
                        """.trimIndent(),
                    )
                }
            }

        /** Migration from schema version 15 to 16: adds the skill_execution_history table. */
        private val MIGRATION_15_16 =
            object : Migration(15, 16) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `skill_execution_history` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`skill_name` TEXT NOT NULL, `tool_name` TEXT NOT NULL, " +
                            "`status` TEXT NOT NULL, `input_summary` TEXT, " +
                            "`output_summary` TEXT, `error_message` TEXT, " +
                            "`started_at` INTEGER NOT NULL, `completed_at` INTEGER, " +
                            "`duration_ms` INTEGER)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS " +
                            "`index_skill_execution_history_skill_name_started_at` " +
                            "ON `skill_execution_history` (`skill_name`, `started_at`)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS " +
                            "`index_skill_execution_history_started_at` " +
                            "ON `skill_execution_history` (`started_at`)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS " +
                            "`index_skill_execution_history_status` " +
                            "ON `skill_execution_history` (`status`)",
                    )
                }
            }

        /**
         * Ordered array of schema migrations.
         *
         * Add new [Migration] instances here as the schema evolves.
         * Each migration covers a single version increment (e.g. 1->2).
         */
        val MIGRATIONS: Array<Migration> =
            arrayOf(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8,
                MIGRATION_8_9,
                MIGRATION_9_10,
                MIGRATION_10_11,
                MIGRATION_11_12,
                MIGRATION_12_13,
                MIGRATION_13_14,
                MIGRATION_14_15,
                MIGRATION_15_16,
            )

        /**
         * Builds a [ZeroAIDatabase] instance with seed data inserted on first creation.
         *
         * Applies all registered [MIGRATIONS]. If a migration path is missing,
         * Room throws [IllegalStateException] at startup rather than silently
         * dropping user data.
         *
         * @param context Application context for database file location.
         * @param scope Coroutine scope for seed data insertion.
         * @return Configured [ZeroAIDatabase] instance.
         */
        fun build(
            context: Context,
            scope: CoroutineScope,
        ): ZeroAIDatabase {
            var instance: ZeroAIDatabase? = null
            val passphrase = DatabasePassphrase.getOrCreate(context)
            DatabaseEncryptionMigrator.migrateIfNeeded(context, passphrase)
            val factory = SupportOpenHelperFactory(passphrase.toByteArray(Charsets.UTF_8))
            val db =
                Room
                    .databaseBuilder(
                        context.applicationContext,
                        ZeroAIDatabase::class.java,
                        DATABASE_NAME,
                    ).openHelperFactory(factory)
                    .apply { MIGRATIONS.forEach { addMigrations(it) } }
                    .addCallback(
                        object : Callback() {
                            override fun onCreate(db: SupportSQLiteDatabase) {
                                super.onCreate(db)
                                scope.launch {
                                    instance?.let { database ->
                                        database.pluginDao().insertAllIgnoreConflicts(
                                            SeedData.seedPlugins(),
                                        )
                                    }
                                }
                            }
                        },
                    ).build()
            instance = db
            return db
        }
    }
}
