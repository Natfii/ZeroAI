// Copyright (c) 2026 @Natfii. All rights reserved.

//! SQLite message store for the Google Messages bridge.
//!
//! Manages `messages_bridge.db` with conversations, messages, FTS5 full-text
//! search, and per-conversation allowlisting with time-windowed access.
//! Follows the same architecture as [`crate::memory::discord_archive`].

use anyhow::{bail, Context, Result};
use parking_lot::Mutex;
use rusqlite::{params, Connection};
use std::path::{Path, PathBuf};
use std::sync::Arc;

use super::types::{BridgedConversation, BridgedMessage, MessageType};

/// SQLite-backed store for bridged Google Messages conversations and messages.
///
/// Provides conversation management, batch message storage, per-conversation
/// allowlisting with optional time windows, and FTS5 full-text search.
/// The connection is wrapped in [`parking_lot::Mutex`] so the store is
/// `Send + Sync` and safe to share across async tasks via `Arc`.
pub struct MessagesBridgeStore {
    /// SQLite connection guarded by [`parking_lot::Mutex`].
    conn: Arc<Mutex<Connection>>,
    /// Path to the database file on disk.
    db_path: PathBuf,
}

impl MessagesBridgeStore {
    /// Open (or create) the messages bridge database at `dir/memory/messages_bridge.db`.
    ///
    /// Creates the directory if it does not exist, sets WAL pragmas, and
    /// initialises all tables, indexes, FTS5 virtual table, and triggers.
    pub fn open(dir: &Path) -> Result<Self> {
        let db_dir = dir.join("memory");
        std::fs::create_dir_all(&db_dir)
            .context("failed to create memory directory for messages bridge")?;
        let db_path = db_dir.join("messages_bridge.db");

        let conn =
            Connection::open(&db_path).context("failed to open messages_bridge.db")?;

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
        conn.execute_batch(
            "CREATE TABLE IF NOT EXISTS bridged_conversations (
                id                     TEXT PRIMARY KEY,
                display_name           TEXT NOT NULL,
                is_group               INTEGER NOT NULL DEFAULT 0,
                last_message_preview   TEXT NOT NULL DEFAULT '',
                last_message_timestamp INTEGER NOT NULL DEFAULT 0,
                agent_allowed          INTEGER NOT NULL DEFAULT 0,
                window_start           INTEGER
            );

            CREATE TABLE IF NOT EXISTS bridged_messages (
                id              TEXT PRIMARY KEY,
                conversation_id TEXT NOT NULL,
                sender_name     TEXT NOT NULL,
                body            TEXT NOT NULL,
                timestamp       INTEGER NOT NULL,
                is_outgoing     INTEGER NOT NULL DEFAULT 0,
                message_type    TEXT NOT NULL DEFAULT 'text'
            );

            CREATE INDEX IF NOT EXISTS idx_bm_conv_time
                ON bridged_messages (conversation_id, timestamp DESC);

            CREATE VIRTUAL TABLE IF NOT EXISTS bridged_messages_fts
                USING fts5(body, sender_name, content=bridged_messages, content_rowid=rowid);",
        )?;

        conn.execute_batch(
            "CREATE TRIGGER IF NOT EXISTS bm_ai AFTER INSERT ON bridged_messages BEGIN
                INSERT INTO bridged_messages_fts(rowid, body, sender_name)
                VALUES (new.rowid, new.body, new.sender_name);
            END;

            CREATE TRIGGER IF NOT EXISTS bm_au AFTER UPDATE ON bridged_messages BEGIN
                INSERT INTO bridged_messages_fts(bridged_messages_fts, rowid, body, sender_name)
                VALUES ('delete', old.rowid, old.body, old.sender_name);
                INSERT INTO bridged_messages_fts(rowid, body, sender_name)
                VALUES (new.rowid, new.body, new.sender_name);
            END;

            CREATE TRIGGER IF NOT EXISTS bm_ad AFTER DELETE ON bridged_messages BEGIN
                INSERT INTO bridged_messages_fts(bridged_messages_fts, rowid, body, sender_name)
                VALUES ('delete', old.rowid, old.body, old.sender_name);
            END;",
        )?;

        Ok(())
    }

    /// Insert or replace a conversation record.
    pub fn upsert_conversation(&self, conv: &BridgedConversation) -> Result<()> {
        let conn = self.conn.lock();
        conn.execute(
            "INSERT OR REPLACE INTO bridged_conversations
                (id, display_name, is_group, last_message_preview,
                 last_message_timestamp, agent_allowed, window_start)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
            params![
                conv.id,
                conv.display_name,
                i64::from(conv.is_group),
                conv.last_message_preview,
                conv.last_message_timestamp,
                i64::from(conv.agent_allowed),
                conv.window_start,
            ],
        )?;
        Ok(())
    }

    /// Batch-insert messages, ignoring duplicates (by primary key).
    ///
    /// All inserts are wrapped in a single transaction for performance.
    pub fn store_messages(&self, messages: &[BridgedMessage]) -> Result<()> {
        if messages.is_empty() {
            return Ok(());
        }

        let conn = self.conn.lock();
        let tx = conn.unchecked_transaction()?;

        {
            let mut stmt = tx.prepare_cached(
                "INSERT OR IGNORE INTO bridged_messages
                    (id, conversation_id, sender_name, body, timestamp,
                     is_outgoing, message_type)
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
            )?;

            for msg in messages {
                stmt.execute(params![
                    msg.id,
                    msg.conversation_id,
                    msg.sender_name,
                    msg.body,
                    msg.timestamp,
                    i64::from(msg.is_outgoing),
                    msg.message_type.as_str(),
                ])?;
            }
        }

        tx.commit()?;
        Ok(())
    }

    /// List all conversations, ordered by last message timestamp descending.
    pub fn list_conversations(&self) -> Result<Vec<BridgedConversation>> {
        let conn = self.conn.lock();
        let mut stmt = conn.prepare(
            "SELECT id, display_name, is_group, last_message_preview,
                    last_message_timestamp, agent_allowed, window_start
             FROM bridged_conversations
             ORDER BY last_message_timestamp DESC",
        )?;

        let rows = stmt.query_map([], |row| {
            Ok(BridgedConversation {
                id: row.get(0)?,
                display_name: row.get(1)?,
                is_group: row.get::<_, i64>(2)? != 0,
                last_message_preview: row.get(3)?,
                last_message_timestamp: row.get(4)?,
                agent_allowed: row.get::<_, i64>(5)? != 0,
                window_start: row.get(6)?,
            })
        })?;

        let mut convs = Vec::new();
        for row in rows {
            convs.push(row?);
        }
        Ok(convs)
    }

    /// Set whether the AI agent is allowed to read a conversation.
    ///
    /// When `allowed` is `true`, the optional `window_start` sets the earliest
    /// timestamp the agent may see. Pass `None` to allow all history.
    pub fn set_allowed(
        &self,
        conversation_id: &str,
        allowed: bool,
        window_start: Option<i64>,
    ) -> Result<()> {
        let conn = self.conn.lock();
        conn.execute(
            "UPDATE bridged_conversations
             SET agent_allowed = ?2, window_start = ?3
             WHERE id = ?1",
            params![conversation_id, i64::from(allowed), window_start],
        )?;
        Ok(())
    }

    /// Query messages from an allowed conversation.
    ///
    /// Returns an error if the conversation does not exist or is not allowed.
    /// Respects the conversation's `window_start` and the caller's `since`
    /// parameter, using whichever is later. Results are ordered by timestamp
    /// descending, limited to `limit` rows.
    pub fn query_messages(
        &self,
        conversation_id: &str,
        since: Option<i64>,
        limit: u32,
    ) -> Result<Vec<BridgedMessage>> {
        let conn = self.conn.lock();

        // Check that the conversation exists and is allowed.
        let (allowed, window_start): (bool, Option<i64>) = conn
            .query_row(
                "SELECT agent_allowed, window_start
                 FROM bridged_conversations WHERE id = ?1",
                params![conversation_id],
                |row| {
                    Ok((row.get::<_, i64>(0)? != 0, row.get(1)?))
                },
            )
            .context("conversation not found")?;

        if !allowed {
            bail!(
                "agent access not allowed for conversation '{}'",
                conversation_id
            );
        }

        // Use the later of window_start and since as the effective cutoff.
        let effective_since = match (window_start, since) {
            (Some(w), Some(s)) => Some(w.max(s)),
            (Some(w), None) => Some(w),
            (None, Some(s)) => Some(s),
            (None, None) => None,
        };

        let (sql, params_vec): (String, Vec<Box<dyn rusqlite::types::ToSql>>) =
            if let Some(cutoff) = effective_since {
                (
                    "SELECT id, conversation_id, sender_name, body, timestamp,
                            is_outgoing, message_type
                     FROM bridged_messages
                     WHERE conversation_id = ?1 AND timestamp >= ?2
                     ORDER BY timestamp DESC
                     LIMIT ?3"
                        .to_string(),
                    vec![
                        Box::new(conversation_id.to_string()),
                        Box::new(cutoff),
                        Box::new(i64::from(limit)),
                    ],
                )
            } else {
                (
                    "SELECT id, conversation_id, sender_name, body, timestamp,
                            is_outgoing, message_type
                     FROM bridged_messages
                     WHERE conversation_id = ?1
                     ORDER BY timestamp DESC
                     LIMIT ?2"
                        .to_string(),
                    vec![
                        Box::new(conversation_id.to_string()),
                        Box::new(i64::from(limit)),
                    ],
                )
            };

        let mut stmt = conn.prepare(&sql)?;
        let params_refs: Vec<&dyn rusqlite::types::ToSql> =
            params_vec.iter().map(|p| p.as_ref()).collect();

        let rows = stmt.query_map(params_refs.as_slice(), |row| {
            Ok(BridgedMessage {
                id: row.get(0)?,
                conversation_id: row.get(1)?,
                sender_name: row.get(2)?,
                body: row.get(3)?,
                timestamp: row.get(4)?,
                is_outgoing: row.get::<_, i64>(5)? != 0,
                message_type: MessageType::parse_db(
                    &row.get::<_, String>(6)?,
                ),
            })
        })?;

        let mut results = Vec::new();
        for row in rows {
            results.push(row?);
        }
        Ok(results)
    }

    /// Full-text search across bridged messages.
    ///
    /// Uses FTS5 MATCH for keyword queries with an optional conversation
    /// filter. Results are ordered by timestamp descending.
    pub fn search(
        &self,
        query: &str,
        conversation_id: Option<&str>,
        limit: u32,
    ) -> Result<Vec<BridgedMessage>> {
        let conn = self.conn.lock();

        let mut sql = String::from(
            "SELECT m.id, m.conversation_id, m.sender_name, m.body,
                    m.timestamp, m.is_outgoing, m.message_type
             FROM bridged_messages m
             JOIN bridged_messages_fts f ON m.rowid = f.rowid
             WHERE bridged_messages_fts MATCH ?1",
        );

        let mut param_idx = 2;
        let mut params_vec: Vec<Box<dyn rusqlite::types::ToSql>> =
            vec![Box::new(query.to_string())];

        if let Some(cid) = conversation_id {
            use std::fmt::Write as _;
            write!(sql, " AND m.conversation_id = ?{param_idx}").unwrap();
            params_vec.push(Box::new(cid.to_string()));
            param_idx += 1;
        }

        {
            use std::fmt::Write as _;
            write!(sql, " ORDER BY m.timestamp DESC LIMIT ?{param_idx}").unwrap();
        }
        params_vec.push(Box::new(i64::from(limit)));

        let mut stmt = conn.prepare(&sql)?;
        let params_refs: Vec<&dyn rusqlite::types::ToSql> =
            params_vec.iter().map(|p| p.as_ref()).collect();

        let rows = stmt.query_map(params_refs.as_slice(), |row| {
            Ok(BridgedMessage {
                id: row.get(0)?,
                conversation_id: row.get(1)?,
                sender_name: row.get(2)?,
                body: row.get(3)?,
                timestamp: row.get(4)?,
                is_outgoing: row.get::<_, i64>(5)? != 0,
                message_type: MessageType::parse_db(
                    &row.get::<_, String>(6)?,
                ),
            })
        })?;

        let mut results = Vec::new();
        for row in rows {
            results.push(row?);
        }
        Ok(results)
    }

    /// Delete all conversations and messages, and rebuild the FTS index.
    pub fn wipe(&self) -> Result<()> {
        let conn = self.conn.lock();
        let tx = conn.unchecked_transaction()?;
        tx.execute_batch(
            "DELETE FROM bridged_messages;
             DELETE FROM bridged_conversations;
             INSERT INTO bridged_messages_fts(bridged_messages_fts) VALUES('rebuild');",
        )?;
        tx.commit()?;
        Ok(())
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

    /// Helper: create a fresh store in a temp directory.
    fn test_store() -> (MessagesBridgeStore, TempDir) {
        let tmp = TempDir::new().unwrap();
        let store = MessagesBridgeStore::open(tmp.path()).unwrap();
        (store, tmp)
    }

    /// Helper: build a test conversation.
    fn make_conv(
        id: &str,
        name: &str,
        allowed: bool,
        ts: i64,
    ) -> BridgedConversation {
        BridgedConversation {
            id: id.to_string(),
            display_name: name.to_string(),
            is_group: false,
            last_message_preview: "hello".to_string(),
            last_message_timestamp: ts,
            agent_allowed: allowed,
            window_start: None,
        }
    }

    /// Helper: build a test message.
    fn make_msg(
        id: &str,
        conv_id: &str,
        body: &str,
        ts: i64,
    ) -> BridgedMessage {
        BridgedMessage {
            id: id.to_string(),
            conversation_id: conv_id.to_string(),
            sender_name: "Alice".to_string(),
            body: body.to_string(),
            timestamp: ts,
            is_outgoing: false,
            message_type: MessageType::Text,
        }
    }

    #[test]
    fn test_store_and_retrieve_conversation() {
        let (store, _tmp) = test_store();

        let conv = make_conv("c1", "Alice", true, 1000);
        store.upsert_conversation(&conv).unwrap();

        let convs = store.list_conversations().unwrap();
        assert_eq!(convs.len(), 1);
        assert_eq!(convs[0].id, "c1");
        assert_eq!(convs[0].display_name, "Alice");
        assert!(convs[0].agent_allowed);
        assert_eq!(convs[0].last_message_timestamp, 1000);

        // Upsert again with updated name.
        let conv2 = make_conv("c1", "Alice B.", true, 2000);
        store.upsert_conversation(&conv2).unwrap();

        let convs = store.list_conversations().unwrap();
        assert_eq!(convs.len(), 1);
        assert_eq!(convs[0].display_name, "Alice B.");
        assert_eq!(convs[0].last_message_timestamp, 2000);
    }

    #[test]
    fn test_store_and_retrieve_messages() {
        let (store, _tmp) = test_store();

        let conv = make_conv("c1", "Alice", true, 3000);
        store.upsert_conversation(&conv).unwrap();

        let messages = vec![
            make_msg("m1", "c1", "hello world", 1000),
            make_msg("m2", "c1", "how are you", 2000),
            make_msg("m3", "c1", "goodbye", 3000),
        ];
        store.store_messages(&messages).unwrap();

        let results = store.query_messages("c1", None, 100).unwrap();
        assert_eq!(results.len(), 3);
        // Ordered by timestamp DESC.
        assert_eq!(results[0].id, "m3");
        assert_eq!(results[1].id, "m2");
        assert_eq!(results[2].id, "m1");
    }

    #[test]
    fn test_allowlist_filters() {
        let (store, _tmp) = test_store();

        // Create conversation as NOT allowed.
        let conv = make_conv("c1", "Alice", false, 1000);
        store.upsert_conversation(&conv).unwrap();

        let messages = vec![make_msg("m1", "c1", "secret", 1000)];
        store.store_messages(&messages).unwrap();

        // Querying a not-allowed conversation should fail.
        let err = store.query_messages("c1", None, 100);
        assert!(err.is_err());
        assert!(
            err.unwrap_err().to_string().contains("not allowed"),
            "error should mention 'not allowed'"
        );

        // Now allow it.
        store.set_allowed("c1", true, None).unwrap();

        let results = store.query_messages("c1", None, 100).unwrap();
        assert_eq!(results.len(), 1);
        assert_eq!(results[0].body, "secret");
    }

    #[test]
    fn test_time_window() {
        let (store, _tmp) = test_store();

        let conv = make_conv("c1", "Alice", true, 5000);
        store.upsert_conversation(&conv).unwrap();

        let messages = vec![
            make_msg("m1", "c1", "old message", 1000),
            make_msg("m2", "c1", "middle message", 3000),
            make_msg("m3", "c1", "new message", 5000),
        ];
        store.store_messages(&messages).unwrap();

        // Set window_start to 2500 — only m2 and m3 should be visible.
        store.set_allowed("c1", true, Some(2500)).unwrap();

        let results = store.query_messages("c1", None, 100).unwrap();
        assert_eq!(results.len(), 2);
        assert_eq!(results[0].id, "m3");
        assert_eq!(results[1].id, "m2");

        // With since=4000, only m3 should be visible (since > window_start).
        let results = store.query_messages("c1", Some(4000), 100).unwrap();
        assert_eq!(results.len(), 1);
        assert_eq!(results[0].id, "m3");
    }

    #[test]
    fn test_fts5_search() {
        let (store, _tmp) = test_store();

        let conv = make_conv("c1", "Alice", true, 5000);
        store.upsert_conversation(&conv).unwrap();

        let messages = vec![
            make_msg("m1", "c1", "the quick brown fox jumps over", 1000),
            make_msg("m2", "c1", "lazy dog sleeps all day long", 2000),
            make_msg("m3", "c1", "fox and dog are best friends", 3000),
        ];
        store.store_messages(&messages).unwrap();

        // Search for "fox" — should match m1 and m3.
        let results = store.search("fox", None, 10).unwrap();
        assert_eq!(results.len(), 2);

        // Search for "lazy" — should match m2 only.
        let results = store.search("lazy", None, 10).unwrap();
        assert_eq!(results.len(), 1);
        assert_eq!(results[0].id, "m2");

        // Search with conversation filter.
        let results = store.search("fox", Some("c1"), 10).unwrap();
        assert_eq!(results.len(), 2);

        let results = store.search("fox", Some("c_nonexistent"), 10).unwrap();
        assert!(results.is_empty());
    }

    #[test]
    fn test_wipe() {
        let (store, _tmp) = test_store();

        let conv = make_conv("c1", "Alice", true, 1000);
        store.upsert_conversation(&conv).unwrap();

        let messages = vec![make_msg("m1", "c1", "hello", 1000)];
        store.store_messages(&messages).unwrap();

        assert_eq!(store.list_conversations().unwrap().len(), 1);
        assert_eq!(store.query_messages("c1", None, 100).unwrap().len(), 1);

        store.wipe().unwrap();

        assert!(store.list_conversations().unwrap().is_empty());

        // FTS should also be empty after wipe.
        let results = store.search("hello", None, 10).unwrap();
        assert!(results.is_empty());
    }
}
