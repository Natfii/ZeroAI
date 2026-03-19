// Copyright (c) 2026 Zeroclaw Labs. All rights reserved.

//! Discord archive — SQLite-backed store for guild channel message history.
//!
//! Manages `discord_archive.db` with messages, FTS5 full-text search,
//! per-channel sync cursors, and channel configuration. Lives alongside
//! `brain.db` as a separate database file so it can be independently
//! managed without touching core memories.

use anyhow::{Context, Result};
use chrono::Utc;
use parking_lot::Mutex;
use rusqlite::{params, Connection};
use std::path::{Path, PathBuf};
use std::sync::Arc;

/// A single Discord message stored in the archive.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ArchiveMessage {
    /// Discord message snowflake ID.
    pub id: String,
    /// Channel snowflake ID.
    pub channel_id: String,
    /// Guild snowflake ID.
    pub guild_id: String,
    /// Author user snowflake ID.
    pub author_id: String,
    /// Author display name at time of message.
    pub author_name: String,
    /// Message text content.
    pub content: String,
    /// Unix timestamp (seconds since epoch).
    pub timestamp: i64,
}

/// Per-channel archive configuration.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ChannelConfig {
    /// Channel snowflake ID.
    pub channel_id: String,
    /// Guild snowflake ID.
    pub guild_id: String,
    /// Human-readable channel name.
    pub channel_name: String,
    /// Backfill depth setting (e.g. "none", "7d", "30d", "all").
    pub backfill_depth: String,
    /// Whether this channel is enabled for archiving.
    pub enabled: bool,
}

/// Sync cursor state for incremental message fetching.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SyncState {
    /// Channel snowflake ID.
    pub channel_id: String,
    /// Guild snowflake ID.
    pub guild_id: String,
    /// Oldest fetched message ID (for backward pagination).
    pub oldest_id: Option<String>,
    /// Newest fetched message ID (for forward pagination).
    pub newest_id: Option<String>,
    /// Unix timestamp of last sync operation.
    pub last_sync: Option<i64>,
    /// Whether historical backfill is complete.
    pub backfill_done: bool,
}

/// Aggregate statistics for a single channel.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ChannelStats {
    /// Total messages archived for this channel.
    pub message_count: usize,
    /// Earliest message timestamp (None if empty).
    pub earliest_timestamp: Option<i64>,
    /// Latest message timestamp (None if empty).
    pub latest_timestamp: Option<i64>,
    /// Top authors by message count, descending.
    pub top_authors: Vec<(String, usize)>,
}

/// Discord message archive backed by a dedicated SQLite database.
///
/// Stores guild channel messages with FTS5 full-text search, per-channel
/// sync cursors for incremental fetching, and channel configuration.
pub struct DiscordArchive {
    /// SQLite connection guarded by [`parking_lot::Mutex`].
    ///
    /// `rusqlite::Connection` is `!Sync`, but wrapping it in a `Mutex`
    /// serialises all access and makes `DiscordArchive` safely `Send + Sync`.
    /// This lets the archive be shared across the async WebSocket listener
    /// task and the blocking flush/backfill paths via `Arc`.
    conn: Arc<Mutex<Connection>>,
    db_path: PathBuf,
}

impl DiscordArchive {
    /// Open (or create) the discord archive database at `dir/memory/discord_archive.db`.
    ///
    /// Creates the directory if it does not exist, sets WAL pragmas, and
    /// initialises all tables, indexes, FTS5 virtual table, and triggers.
    pub fn open(dir: &Path) -> Result<Self> {
        let db_dir = dir.join("memory");
        std::fs::create_dir_all(&db_dir)
            .context("failed to create memory directory for discord archive")?;
        let db_path = db_dir.join("discord_archive.db");

        let conn = Connection::open(&db_path).context("failed to open discord_archive.db")?;

        conn.execute_batch(
            "PRAGMA journal_mode = WAL;
             PRAGMA synchronous  = NORMAL;
             PRAGMA mmap_size    = 8388608;
             PRAGMA cache_size   = -2000;
             PRAGMA temp_store   = MEMORY;",
        )?;

        Self::init_schema(&conn)?;

        Ok(Self {
            conn: Arc::new(Mutex::new(conn)),
            db_path,
        })
    }

    /// Initialise the database schema (tables, indexes, FTS5, triggers).
    fn init_schema(conn: &Connection) -> Result<()> {
        // Migrate legacy tables that lack NOT NULL constraints.
        // Room requires exact schema match; old tables without NOT NULL
        // on channel_config columns and messages.id cause validation failure.
        Self::migrate_legacy_tables(conn);

        conn.execute_batch(
            "CREATE TABLE IF NOT EXISTS messages (
                id          TEXT NOT NULL PRIMARY KEY,
                channel_id  TEXT NOT NULL,
                guild_id    TEXT NOT NULL,
                author_id   TEXT NOT NULL,
                author_name TEXT NOT NULL,
                content     TEXT NOT NULL,
                timestamp   INTEGER NOT NULL,
                embedding   BLOB
            );

            DROP INDEX IF EXISTS idx_messages_channel;
            CREATE INDEX idx_messages_channel
                ON messages(channel_id, timestamp);

            DROP INDEX IF EXISTS idx_messages_timestamp;
            CREATE INDEX idx_messages_timestamp
                ON messages(timestamp);

            CREATE VIRTUAL TABLE IF NOT EXISTS messages_fts
                USING fts5(content, author_name, content=messages, content_rowid=rowid);

            CREATE TABLE IF NOT EXISTS sync_state (
                channel_id    TEXT NOT NULL PRIMARY KEY,
                guild_id      TEXT NOT NULL,
                oldest_id     TEXT,
                newest_id     TEXT,
                last_sync     INTEGER,
                backfill_done INTEGER DEFAULT 0
            );

            CREATE TABLE IF NOT EXISTS channel_config (
                channel_id     TEXT NOT NULL PRIMARY KEY,
                guild_id       TEXT NOT NULL,
                channel_name   TEXT NOT NULL,
                backfill_depth TEXT NOT NULL DEFAULT 'none',
                enabled        INTEGER NOT NULL DEFAULT 1
            );",
        )?;

        conn.execute_batch(
            "CREATE TRIGGER IF NOT EXISTS messages_ai AFTER INSERT ON messages BEGIN
                INSERT INTO messages_fts(rowid, content, author_name)
                VALUES (new.rowid, new.content, new.author_name);
            END;

            CREATE TRIGGER IF NOT EXISTS messages_au AFTER UPDATE ON messages BEGIN
                INSERT INTO messages_fts(messages_fts, rowid, content, author_name)
                VALUES ('delete', old.rowid, old.content, old.author_name);
                INSERT INTO messages_fts(rowid, content, author_name)
                VALUES (new.rowid, new.content, new.author_name);
            END;

            CREATE TRIGGER IF NOT EXISTS messages_ad AFTER DELETE ON messages BEGIN
                INSERT INTO messages_fts(messages_fts, rowid, content, author_name)
                VALUES ('delete', old.rowid, old.content, old.author_name);
            END;",
        )?;

        Ok(())
    }

    /// One-time migration: recreate tables that were created without NOT NULL
    /// constraints on columns that Room expects to be non-nullable.
    ///
    /// Checks the `channel_config.backfill_depth` column — if it allows NULL,
    /// the legacy schema is present and all tables are recreated. Data in
    /// `channel_config` is preserved via a temp table; `messages` and
    /// `sync_state` are kept as-is since `CREATE TABLE IF NOT EXISTS` with
    /// the corrected schema handles them (messages.id already has NOT NULL
    /// in practice via PRIMARY KEY constraint enforcement).
    fn migrate_legacy_tables(conn: &Connection) {
        // Check if channel_config.backfill_depth allows NULL (legacy schema).
        let needs_migrate: bool = conn
            .prepare("PRAGMA table_info(channel_config)")
            .and_then(|mut stmt| {
                let rows = stmt.query_map([], |row| {
                    Ok((
                        row.get::<_, String>(1)?, // column name
                        row.get::<_, i32>(3)?,    // notnull flag
                    ))
                })?;
                for row in rows {
                    let (name, notnull) = row?;
                    if name == "backfill_depth" && notnull == 0 {
                        return Ok(true);
                    }
                }
                Ok(false)
            })
            .unwrap_or(false);

        if !needs_migrate {
            return;
        }

        tracing::info!("Migrating legacy discord_archive schema");
        let _ = conn.execute_batch(
            "ALTER TABLE channel_config RENAME TO _channel_config_old;

             CREATE TABLE channel_config (
                 channel_id     TEXT NOT NULL PRIMARY KEY,
                 guild_id       TEXT NOT NULL,
                 channel_name   TEXT NOT NULL,
                 backfill_depth TEXT NOT NULL DEFAULT 'none',
                 enabled        INTEGER NOT NULL DEFAULT 1
             );

             INSERT INTO channel_config
                 SELECT channel_id, guild_id, channel_name,
                        COALESCE(backfill_depth, 'none'),
                        COALESCE(enabled, 1)
                 FROM _channel_config_old;

             DROP TABLE _channel_config_old;",
        );
    }

    /// Batch-insert messages, ignoring duplicates (by primary key).
    ///
    /// All inserts are wrapped in a single transaction for performance.
    pub fn store_messages(&self, messages: &[ArchiveMessage]) -> Result<()> {
        if messages.is_empty() {
            return Ok(());
        }

        let conn = self.conn.lock();
        let tx = conn.unchecked_transaction()?;

        {
            let mut stmt = tx.prepare_cached(
                "INSERT OR IGNORE INTO messages
                    (id, channel_id, guild_id, author_id, author_name, content, timestamp)
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
            )?;

            for msg in messages {
                stmt.execute(params![
                    msg.id,
                    msg.channel_id,
                    msg.guild_id,
                    msg.author_id,
                    msg.author_name,
                    msg.content,
                    msg.timestamp,
                ])?;
            }
        }

        tx.commit()?;
        Ok(())
    }

    /// Count messages in a specific channel.
    pub fn message_count(&self, channel_id: &str) -> Result<usize> {
        let conn = self.conn.lock();
        let count: i64 = conn.query_row(
            "SELECT COUNT(*) FROM messages WHERE channel_id = ?1",
            params![channel_id],
            |row| row.get(0),
        )?;
        #[allow(clippy::cast_sign_loss)]
        Ok(count as usize)
    }

    /// Full-text search across archived messages.
    ///
    /// Uses FTS5 MATCH for keyword queries with optional channel and
    /// time-window filters. Results are ordered by timestamp descending.
    pub fn search(
        &self,
        query: &str,
        channel_id: Option<&str>,
        days_back: Option<u32>,
        limit: usize,
    ) -> Result<Vec<ArchiveMessage>> {
        let conn = self.conn.lock();

        let mut sql = String::from(
            "SELECT m.id, m.channel_id, m.guild_id, m.author_id,
                    m.author_name, m.content, m.timestamp
             FROM messages m
             JOIN messages_fts f ON m.rowid = f.rowid
             WHERE messages_fts MATCH ?1",
        );

        let mut param_idx = 2;
        let mut params_vec: Vec<Box<dyn rusqlite::types::ToSql>> =
            vec![Box::new(query.to_string())];

        if let Some(cid) = channel_id {
            sql.push_str(&format!(" AND m.channel_id = ?{param_idx}"));
            params_vec.push(Box::new(cid.to_string()));
            param_idx += 1;
        }

        if let Some(days) = days_back {
            let cutoff = Utc::now().timestamp() - i64::from(days) * 86_400;
            sql.push_str(&format!(" AND m.timestamp >= ?{param_idx}"));
            params_vec.push(Box::new(cutoff));
            param_idx += 1;
        }

        sql.push_str(&format!(" ORDER BY m.timestamp DESC LIMIT ?{param_idx}"));
        params_vec.push(Box::new(limit as i64));

        let mut stmt = conn.prepare(&sql)?;
        let params_refs: Vec<&dyn rusqlite::types::ToSql> =
            params_vec.iter().map(|p| p.as_ref()).collect();

        let rows = stmt.query_map(params_refs.as_slice(), |row| {
            Ok(ArchiveMessage {
                id: row.get(0)?,
                channel_id: row.get(1)?,
                guild_id: row.get(2)?,
                author_id: row.get(3)?,
                author_name: row.get(4)?,
                content: row.get(5)?,
                timestamp: row.get(6)?,
            })
        })?;

        let mut results = Vec::new();
        for row in rows {
            results.push(row?);
        }
        Ok(results)
    }

    /// Retrieve recent messages within a time window, from enabled channels only.
    ///
    /// Returns messages from the last `seconds_back` seconds, ordered by
    /// timestamp descending, limited to `limit` rows.
    pub fn recent_messages(&self, seconds_back: i64, limit: usize) -> Result<Vec<ArchiveMessage>> {
        let conn = self.conn.lock();
        let cutoff = Utc::now().timestamp() - seconds_back;

        let mut stmt = conn.prepare(
            "SELECT m.id, m.channel_id, m.guild_id, m.author_id,
                    m.author_name, m.content, m.timestamp
             FROM messages m
             JOIN channel_config c ON m.channel_id = c.channel_id
             WHERE c.enabled = 1 AND m.timestamp >= ?1
             ORDER BY m.timestamp DESC
             LIMIT ?2",
        )?;

        let rows = stmt.query_map(params![cutoff, limit as i64], |row| {
            Ok(ArchiveMessage {
                id: row.get(0)?,
                channel_id: row.get(1)?,
                guild_id: row.get(2)?,
                author_id: row.get(3)?,
                author_name: row.get(4)?,
                content: row.get(5)?,
                timestamp: row.get(6)?,
            })
        })?;

        let mut results = Vec::new();
        for row in rows {
            results.push(row?);
        }
        Ok(results)
    }

    /// Configure a channel for archiving.
    ///
    /// Inserts or replaces the channel config and creates a sync state
    /// entry if one does not already exist.
    pub fn configure_channel(
        &self,
        channel_id: &str,
        guild_id: &str,
        name: &str,
        depth: &str,
    ) -> Result<()> {
        let conn = self.conn.lock();
        conn.execute(
            "INSERT OR REPLACE INTO channel_config
                (channel_id, guild_id, channel_name, backfill_depth, enabled)
             VALUES (?1, ?2, ?3, ?4, 1)",
            params![channel_id, guild_id, name, depth],
        )?;

        conn.execute(
            "INSERT OR IGNORE INTO sync_state (channel_id, guild_id)
             VALUES (?1, ?2)",
            params![channel_id, guild_id],
        )?;

        Ok(())
    }

    /// Remove a channel and all its associated data.
    ///
    /// Deletes from messages, sync_state, and channel_config.
    pub fn remove_channel(&self, channel_id: &str) -> Result<()> {
        let conn = self.conn.lock();
        let tx = conn.unchecked_transaction()?;
        tx.execute(
            "DELETE FROM messages WHERE channel_id = ?1",
            params![channel_id],
        )?;
        tx.execute(
            "DELETE FROM sync_state WHERE channel_id = ?1",
            params![channel_id],
        )?;
        tx.execute(
            "DELETE FROM channel_config WHERE channel_id = ?1",
            params![channel_id],
        )?;
        tx.commit()?;
        Ok(())
    }

    /// List all channel configurations, ordered by channel name.
    pub fn list_channel_configs(&self) -> Result<Vec<ChannelConfig>> {
        let conn = self.conn.lock();
        let mut stmt = conn.prepare(
            "SELECT channel_id, guild_id, channel_name, backfill_depth, enabled
             FROM channel_config
             ORDER BY channel_name",
        )?;

        let rows = stmt.query_map([], |row| {
            Ok(ChannelConfig {
                channel_id: row.get(0)?,
                guild_id: row.get(1)?,
                channel_name: row.get(2)?,
                backfill_depth: row.get(3)?,
                enabled: row.get::<_, i64>(4)? != 0,
            })
        })?;

        let mut configs = Vec::new();
        for row in rows {
            configs.push(row?);
        }
        Ok(configs)
    }

    /// Get sync state for a specific channel.
    pub fn get_sync_state(&self, channel_id: &str) -> Result<Option<SyncState>> {
        let conn = self.conn.lock();
        let mut stmt = conn.prepare(
            "SELECT channel_id, guild_id, oldest_id, newest_id, last_sync, backfill_done
             FROM sync_state
             WHERE channel_id = ?1",
        )?;

        let result = stmt.query_row(params![channel_id], |row| {
            Ok(SyncState {
                channel_id: row.get(0)?,
                guild_id: row.get(1)?,
                oldest_id: row.get(2)?,
                newest_id: row.get(3)?,
                last_sync: row.get(4)?,
                backfill_done: row.get::<_, i64>(5)? != 0,
            })
        });

        match result {
            Ok(state) => Ok(Some(state)),
            Err(rusqlite::Error::QueryReturnedNoRows) => Ok(None),
            Err(e) => Err(e.into()),
        }
    }

    /// Update sync state cursors for a channel.
    ///
    /// Updates whichever cursor fields are provided (oldest_id, newest_id,
    /// backfill_done) and always sets `last_sync` to the current time.
    pub fn update_sync_state(
        &self,
        channel_id: &str,
        oldest_id: Option<&str>,
        newest_id: Option<&str>,
        backfill_done: bool,
    ) -> Result<()> {
        let conn = self.conn.lock();
        let now = Utc::now().timestamp();

        conn.execute(
            "UPDATE sync_state
             SET oldest_id     = COALESCE(?2, oldest_id),
                 newest_id     = COALESCE(?3, newest_id),
                 last_sync     = ?4,
                 backfill_done = ?5
             WHERE channel_id = ?1",
            params![channel_id, oldest_id, newest_id, now, backfill_done as i64,],
        )?;

        Ok(())
    }

    /// Get aggregate statistics for a channel.
    pub fn channel_stats(&self, channel_id: &str) -> Result<ChannelStats> {
        let conn = self.conn.lock();

        let (message_count, earliest_timestamp, latest_timestamp): (i64, Option<i64>, Option<i64>) =
            conn.query_row(
                "SELECT COUNT(*), MIN(timestamp), MAX(timestamp)
                 FROM messages WHERE channel_id = ?1",
                params![channel_id],
                |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
            )?;

        let mut stmt = conn.prepare(
            "SELECT author_name, COUNT(*) as cnt
             FROM messages
             WHERE channel_id = ?1
             GROUP BY author_name
             ORDER BY cnt DESC
             LIMIT 5",
        )?;

        let rows = stmt.query_map(params![channel_id], |row| {
            let name: String = row.get(0)?;
            let count: i64 = row.get(1)?;
            #[allow(clippy::cast_sign_loss)]
            Ok((name, count as usize))
        })?;

        let mut top_authors = Vec::new();
        for row in rows {
            top_authors.push(row?);
        }

        #[allow(clippy::cast_sign_loss)]
        Ok(ChannelStats {
            message_count: message_count as usize,
            earliest_timestamp,
            latest_timestamp,
            top_authors,
        })
    }

    /// List channel IDs where archiving is enabled.
    pub fn enabled_channel_ids(&self) -> Result<Vec<String>> {
        let conn = self.conn.lock();
        let mut stmt = conn.prepare("SELECT channel_id FROM channel_config WHERE enabled = 1")?;

        let rows = stmt.query_map([], |row| row.get(0))?;

        let mut ids = Vec::new();
        for row in rows {
            ids.push(row?);
        }
        Ok(ids)
    }

    /// Returns the path to the database file.
    pub fn db_path(&self) -> &Path {
        &self.db_path
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::TempDir;

    /// Helper: create a fresh archive in a temp directory.
    fn test_archive() -> (DiscordArchive, TempDir) {
        let tmp = TempDir::new().unwrap();
        let archive = DiscordArchive::open(tmp.path()).unwrap();
        (archive, tmp)
    }

    /// Helper: build a test message with sensible defaults.
    fn make_msg(id: &str, channel_id: &str, content: &str, timestamp: i64) -> ArchiveMessage {
        ArchiveMessage {
            id: id.to_string(),
            channel_id: channel_id.to_string(),
            guild_id: "guild_1".to_string(),
            author_id: "author_1".to_string(),
            author_name: "test_user".to_string(),
            content: content.to_string(),
            timestamp,
        }
    }

    #[test]
    fn db_creation_and_empty_state() {
        let (archive, _tmp) = test_archive();

        assert!(archive.db_path().exists());
        assert_eq!(archive.message_count("ch_1").unwrap(), 0);
        assert!(archive.list_channel_configs().unwrap().is_empty());
        assert!(archive.enabled_channel_ids().unwrap().is_empty());

        let stats = archive.channel_stats("ch_1").unwrap();
        assert_eq!(stats.message_count, 0);
        assert!(stats.earliest_timestamp.is_none());
        assert!(stats.latest_timestamp.is_none());
        assert!(stats.top_authors.is_empty());
    }

    #[test]
    fn store_and_retrieve_messages() {
        let (archive, _tmp) = test_archive();

        let messages = vec![
            make_msg("msg_1", "ch_1", "hello world", 1000),
            make_msg("msg_2", "ch_1", "rust is great", 1001),
            make_msg("msg_3", "ch_2", "other channel", 1002),
        ];

        archive.store_messages(&messages).unwrap();

        assert_eq!(archive.message_count("ch_1").unwrap(), 2);
        assert_eq!(archive.message_count("ch_2").unwrap(), 1);
        assert_eq!(archive.message_count("ch_3").unwrap(), 0);
    }

    #[test]
    fn duplicate_messages_are_ignored() {
        let (archive, _tmp) = test_archive();

        let msg = make_msg("msg_dup", "ch_1", "first version", 1000);
        archive.store_messages(&[msg]).unwrap();

        let msg2 = make_msg("msg_dup", "ch_1", "second version", 1001);
        archive.store_messages(&[msg2]).unwrap();

        assert_eq!(archive.message_count("ch_1").unwrap(), 1);

        let results = archive.search("first", None, None, 10).unwrap();
        assert_eq!(results.len(), 1);
        assert_eq!(results[0].content, "first version");
    }

    #[test]
    fn fts_keyword_search() {
        let (archive, _tmp) = test_archive();

        let messages = vec![
            make_msg("m1", "ch_1", "the quick brown fox jumps", 1000),
            make_msg("m2", "ch_1", "lazy dog sleeps all day", 1001),
            make_msg("m3", "ch_1", "fox and dog are friends", 1002),
        ];
        archive.store_messages(&messages).unwrap();

        let results = archive.search("fox", None, None, 10).unwrap();
        assert_eq!(results.len(), 2);

        let results = archive.search("lazy", None, None, 10).unwrap();
        assert_eq!(results.len(), 1);
        assert_eq!(results[0].id, "m2");
    }

    #[test]
    fn search_filter_by_channel() {
        let (archive, _tmp) = test_archive();

        let messages = vec![
            make_msg("m1", "ch_1", "deploy the application", 1000),
            make_msg("m2", "ch_2", "deploy the infrastructure", 1001),
        ];
        archive.store_messages(&messages).unwrap();

        let results = archive.search("deploy", None, None, 10).unwrap();
        assert_eq!(results.len(), 2);

        let results = archive.search("deploy", Some("ch_1"), None, 10).unwrap();
        assert_eq!(results.len(), 1);
        assert_eq!(results[0].channel_id, "ch_1");
    }

    #[test]
    fn search_filter_by_days_back() {
        let (archive, _tmp) = test_archive();

        let now = Utc::now().timestamp();
        let two_days_ago = now - 2 * 86_400;
        let ten_days_ago = now - 10 * 86_400;

        let messages = vec![
            make_msg("m_recent", "ch_1", "recent update", two_days_ago),
            make_msg("m_old", "ch_1", "old update", ten_days_ago),
        ];
        archive.store_messages(&messages).unwrap();

        let results = archive.search("update", None, Some(5), 10).unwrap();
        assert_eq!(results.len(), 1);
        assert_eq!(results[0].id, "m_recent");

        let results = archive.search("update", None, Some(30), 10).unwrap();
        assert_eq!(results.len(), 2);
    }

    #[test]
    fn recent_messages_respects_enabled_channels() {
        let (archive, _tmp) = test_archive();

        let now = Utc::now().timestamp();

        archive
            .configure_channel("ch_1", "guild_1", "general", "7d")
            .unwrap();
        archive
            .configure_channel("ch_2", "guild_1", "random", "7d")
            .unwrap();

        let messages = vec![
            make_msg("m1", "ch_1", "enabled channel msg", now - 60),
            make_msg("m2", "ch_2", "also enabled msg", now - 30),
            make_msg("m3", "ch_3", "unconfigured channel msg", now - 10),
        ];
        archive.store_messages(&messages).unwrap();

        let recent = archive.recent_messages(3600, 100).unwrap();
        assert_eq!(recent.len(), 2);

        let channel_ids: Vec<&str> = recent.iter().map(|m| m.channel_id.as_str()).collect();
        assert!(channel_ids.contains(&"ch_1"));
        assert!(channel_ids.contains(&"ch_2"));
        assert!(!channel_ids.contains(&"ch_3"));
    }

    #[test]
    fn channel_config_crud() {
        let (archive, _tmp) = test_archive();

        assert!(archive.list_channel_configs().unwrap().is_empty());

        archive
            .configure_channel("ch_1", "guild_1", "general", "7d")
            .unwrap();
        archive
            .configure_channel("ch_2", "guild_1", "random", "30d")
            .unwrap();

        let configs = archive.list_channel_configs().unwrap();
        assert_eq!(configs.len(), 2);
        assert_eq!(configs[0].channel_name, "general");
        assert_eq!(configs[0].backfill_depth, "7d");
        assert!(configs[0].enabled);
        assert_eq!(configs[1].channel_name, "random");
        assert_eq!(configs[1].backfill_depth, "30d");

        archive
            .configure_channel("ch_1", "guild_1", "general", "all")
            .unwrap();
        let configs = archive.list_channel_configs().unwrap();
        assert_eq!(configs.len(), 2);
        assert_eq!(configs[0].backfill_depth, "all");

        archive.remove_channel("ch_1").unwrap();
        let configs = archive.list_channel_configs().unwrap();
        assert_eq!(configs.len(), 1);
        assert_eq!(configs[0].channel_id, "ch_2");
    }

    #[test]
    fn sync_state_tracking() {
        let (archive, _tmp) = test_archive();

        archive
            .configure_channel("ch_1", "guild_1", "general", "7d")
            .unwrap();

        let state = archive.get_sync_state("ch_1").unwrap().unwrap();
        assert_eq!(state.channel_id, "ch_1");
        assert_eq!(state.guild_id, "guild_1");
        assert!(state.oldest_id.is_none());
        assert!(state.newest_id.is_none());
        assert!(state.last_sync.is_none());
        assert!(!state.backfill_done);

        archive
            .update_sync_state("ch_1", Some("100"), None, false)
            .unwrap();
        let state = archive.get_sync_state("ch_1").unwrap().unwrap();
        assert_eq!(state.oldest_id.as_deref(), Some("100"));
        assert!(state.newest_id.is_none());
        assert!(state.last_sync.is_some());
        assert!(!state.backfill_done);

        archive
            .update_sync_state("ch_1", None, Some("999"), true)
            .unwrap();
        let state = archive.get_sync_state("ch_1").unwrap().unwrap();
        assert_eq!(state.oldest_id.as_deref(), Some("100"));
        assert_eq!(state.newest_id.as_deref(), Some("999"));
        assert!(state.backfill_done);

        assert!(archive.get_sync_state("ch_missing").unwrap().is_none());
    }

    #[test]
    fn channel_stats_aggregation() {
        let (archive, _tmp) = test_archive();

        let messages = vec![
            ArchiveMessage {
                id: "s1".to_string(),
                channel_id: "ch_1".to_string(),
                guild_id: "guild_1".to_string(),
                author_id: "a1".to_string(),
                author_name: "user_alpha".to_string(),
                content: "msg one".to_string(),
                timestamp: 1000,
            },
            ArchiveMessage {
                id: "s2".to_string(),
                channel_id: "ch_1".to_string(),
                guild_id: "guild_1".to_string(),
                author_id: "a1".to_string(),
                author_name: "user_alpha".to_string(),
                content: "msg two".to_string(),
                timestamp: 2000,
            },
            ArchiveMessage {
                id: "s3".to_string(),
                channel_id: "ch_1".to_string(),
                guild_id: "guild_1".to_string(),
                author_id: "a2".to_string(),
                author_name: "user_beta".to_string(),
                content: "msg three".to_string(),
                timestamp: 3000,
            },
        ];
        archive.store_messages(&messages).unwrap();

        let stats = archive.channel_stats("ch_1").unwrap();
        assert_eq!(stats.message_count, 3);
        assert_eq!(stats.earliest_timestamp, Some(1000));
        assert_eq!(stats.latest_timestamp, Some(3000));
        assert_eq!(stats.top_authors.len(), 2);
        assert_eq!(stats.top_authors[0].0, "user_alpha");
        assert_eq!(stats.top_authors[0].1, 2);
        assert_eq!(stats.top_authors[1].0, "user_beta");
        assert_eq!(stats.top_authors[1].1, 1);
    }
}
