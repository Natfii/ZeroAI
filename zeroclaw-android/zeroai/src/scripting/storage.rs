// Copyright (c) 2026 @Natfii. All rights reserved.

//! Script-scoped key-value storage backed by SQLite.

use anyhow::{Context, Result};
use rusqlite::{params, Connection};
use std::path::Path;

/// Maximum number of keys per script namespace.
const MAX_KEYS_PER_SCRIPT: usize = 1_000;

/// Maximum size of a single value in bytes.
const MAX_VALUE_SIZE: usize = 64 * 1024; // 64 KiB

/// Maximum total storage size per script in bytes.
const MAX_TOTAL_SIZE_PER_SCRIPT: usize = 10 * 1024 * 1024; // 10 MiB

/// Script-scoped key-value store backed by a single SQLite database.
///
/// Each script name gets its own namespace — keys do not collide across
/// scripts. Values are arbitrary UTF-8 strings (JSON recommended).
pub struct ScriptStorage {
    conn: Connection,
}

impl ScriptStorage {
    /// Open or create the storage database in `dir`.
    pub fn open(dir: &Path) -> Result<Self> {
        let db_path = dir.join("script_storage.db");
        let conn = Connection::open(&db_path)
            .with_context(|| format!("open script storage at {}", db_path.display()))?;
        conn.execute_batch(
            "CREATE TABLE IF NOT EXISTS script_kv (
                script TEXT NOT NULL,
                key    TEXT NOT NULL,
                value  TEXT NOT NULL,
                PRIMARY KEY (script, key)
            )",
        )?;
        // Clean up incorrectly namespaced data from before C3 fix
        conn.execute("DELETE FROM script_kv WHERE script = 'anonymous'", [])?;
        Ok(Self { conn })
    }

    /// Read a value by script name and key.
    pub fn read(&self, script: &str, key: &str) -> Result<Option<String>> {
        let mut stmt = self.conn.prepare(
            "SELECT value FROM script_kv WHERE script = ?1 AND key = ?2",
        )?;
        let mut rows = stmt.query(params![script, key])?;
        match rows.next()? {
            Some(row) => Ok(Some(row.get(0)?)),
            None => Ok(None),
        }
    }

    /// Write (upsert) a value, enforcing per-script quotas.
    pub fn write(&self, script: &str, key: &str, value: &str) -> Result<()> {
        // Check value size
        if value.len() > MAX_VALUE_SIZE {
            anyhow::bail!(
                "value size {} exceeds limit of {} bytes",
                value.len(),
                MAX_VALUE_SIZE
            );
        }

        // Check key count (excluding current key if it exists)
        let key_count: usize = self.conn.query_row(
            "SELECT COUNT(*) FROM script_kv WHERE script = ?1 AND key != ?2",
            params![script, key],
            |row| row.get(0),
        )?;
        if key_count >= MAX_KEYS_PER_SCRIPT {
            anyhow::bail!(
                "script '{}' has {} keys, limit is {}",
                script,
                key_count,
                MAX_KEYS_PER_SCRIPT
            );
        }

        // Check total size (excluding current key's old value)
        let total_size: usize = self.conn.query_row(
            "SELECT COALESCE(SUM(LENGTH(value)), 0) FROM script_kv \
             WHERE script = ?1 AND key != ?2",
            params![script, key],
            |row| row.get(0),
        )?;
        if total_size + value.len() > MAX_TOTAL_SIZE_PER_SCRIPT {
            anyhow::bail!(
                "script '{}' total storage {} + {} exceeds limit of {} bytes",
                script,
                total_size,
                value.len(),
                MAX_TOTAL_SIZE_PER_SCRIPT
            );
        }

        self.conn.execute(
            "INSERT INTO script_kv (script, key, value) VALUES (?1, ?2, ?3)
             ON CONFLICT(script, key) DO UPDATE SET value = excluded.value",
            params![script, key, value],
        )?;
        Ok(())
    }

    /// Delete a key. Returns true if the key existed.
    pub fn delete(&self, script: &str, key: &str) -> Result<bool> {
        let affected = self.conn.execute(
            "DELETE FROM script_kv WHERE script = ?1 AND key = ?2",
            params![script, key],
        )?;
        Ok(affected > 0)
    }

    /// Deletes all storage for a script. Used on uninstall.
    pub fn cleanup_script(&self, script: &str) -> Result<usize> {
        let affected = self.conn.execute(
            "DELETE FROM script_kv WHERE script = ?1",
            params![script],
        )?;
        Ok(affected)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trip_read_write() {
        let dir = tempfile::tempdir().unwrap();
        let store = ScriptStorage::open(dir.path()).unwrap();
        store.write("test-script", "greeting", "hello").unwrap();
        let val = store.read("test-script", "greeting").unwrap();
        assert_eq!(val, Some("hello".to_string()));
    }

    #[test]
    fn read_missing_key_returns_none() {
        let dir = tempfile::tempdir().unwrap();
        let store = ScriptStorage::open(dir.path()).unwrap();
        assert_eq!(store.read("s", "missing").unwrap(), None);
    }

    #[test]
    fn scripts_are_isolated() {
        let dir = tempfile::tempdir().unwrap();
        let store = ScriptStorage::open(dir.path()).unwrap();
        store.write("script-a", "key", "a-value").unwrap();
        store.write("script-b", "key", "b-value").unwrap();
        assert_eq!(store.read("script-a", "key").unwrap(), Some("a-value".to_string()));
        assert_eq!(store.read("script-b", "key").unwrap(), Some("b-value".to_string()));
    }

    #[test]
    fn upsert_overwrites_existing() {
        let dir = tempfile::tempdir().unwrap();
        let store = ScriptStorage::open(dir.path()).unwrap();
        store.write("s", "k", "v1").unwrap();
        store.write("s", "k", "v2").unwrap();
        assert_eq!(store.read("s", "k").unwrap(), Some("v2".to_string()));
    }

    #[test]
    fn delete_returns_false_for_missing() {
        let dir = tempfile::tempdir().unwrap();
        let store = ScriptStorage::open(dir.path()).unwrap();
        assert!(!store.delete("s", "missing").unwrap());
    }

    #[test]
    fn write_rejects_oversized_value() {
        let dir = tempfile::tempdir().unwrap();
        let store = ScriptStorage::open(dir.path()).unwrap();
        let big_value = "x".repeat(65 * 1024); // 65 KiB > 64 KiB limit
        let result = store.write("test", "key", &big_value);
        assert!(result.is_err());
        assert!(result.unwrap_err().to_string().contains("value size"));
    }

    #[test]
    fn write_rejects_too_many_keys() {
        let dir = tempfile::tempdir().unwrap();
        let store = ScriptStorage::open(dir.path()).unwrap();
        for i in 0..MAX_KEYS_PER_SCRIPT {
            store.write("test", &format!("key-{i}"), "v").unwrap();
        }
        let result = store.write("test", "one-too-many", "v");
        assert!(result.is_err());
        assert!(result.unwrap_err().to_string().contains("keys"));
    }
}
