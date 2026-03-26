// Copyright (c) 2026 @Natfii. All rights reserved.

//! Known-hosts store for SSH host key verification.
//!
//! Persists accepted host key fingerprints across sessions and detects
//! when a server's key has changed since the last successful connection.
//!
//! The store is backed by a JSON file on disk and an in-memory cache.
//! [`lookup`] reads from the cache only — no file I/O. [`trust`] and
//! [`remove`] write-through to both the cache and the JSON file.

use std::fs;
use std::path::PathBuf;
use std::sync::{Mutex, OnceLock};
use std::time::{SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};

use crate::error::FfiError;

/// Path to the `known_hosts.json` file, set once via [`init`].
static HOSTS_PATH: OnceLock<PathBuf> = OnceLock::new();

/// In-memory cache of all known host entries.
///
/// This IS the backing store — every mutating operation updates this
/// cache and then writes it through to the JSON file atomically.
static CACHE: Mutex<Vec<KnownHostEntry>> = Mutex::new(Vec::new());

/// A single trusted host key record.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub(crate) struct KnownHostEntry {
    /// `"hostname:port"` — e.g. `"example.com:22"`.
    pub host_port: String,
    /// Key algorithm — e.g. `"ssh-ed25519"`.
    pub algorithm: String,
    /// SHA-256 fingerprint — e.g. `"SHA256:<base64>"`.
    pub fingerprint_sha256: String,
    /// Unix epoch (milliseconds) when the entry was trusted.
    pub trusted_at_epoch_ms: i64,
}

/// Initializes the known-hosts store.
///
/// Creates the parent directory if absent, loads the JSON file into
/// the in-memory cache (creating an empty file when none exists), and
/// records the file path in [`HOSTS_PATH`].
///
/// Idempotent if called again with the same path; returns
/// [`FfiError::StateError`] if called with a different path.
pub(crate) fn init(path: PathBuf) -> Result<(), FfiError> {
    // Idempotency check before touching the filesystem.
    if let Some(existing) = HOSTS_PATH.get() {
        return if *existing == path {
            Ok(())
        } else {
            Err(FfiError::StateError {
                detail: "known-hosts store already initialized with a different path".into(),
            })
        };
    }

    // Create parent directory.
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).map_err(|e| FfiError::IoError {
            detail: format!("failed to create known-hosts directory: {e}"),
        })?;
    }

    // Load or create the JSON file.
    let entries: Vec<KnownHostEntry> = if path.exists() {
        let raw = fs::read_to_string(&path).map_err(|e| FfiError::IoError {
            detail: format!("failed to read known_hosts.json: {e}"),
        })?;
        serde_json::from_str(&raw).unwrap_or_default()
    } else {
        // Seed an empty file so the path is never missing on next boot.
        fs::write(&path, b"[]\n").map_err(|e| FfiError::IoError {
            detail: format!("failed to create known_hosts.json: {e}"),
        })?;
        Vec::new()
    };

    // Populate cache (poison recovery: use whatever data survives).
    let mut cache = CACHE.lock().unwrap_or_else(|e| e.into_inner());
    *cache = entries;
    drop(cache);

    let _ = HOSTS_PATH.set(path);
    Ok(())
}

/// Looks up a host key entry in the in-memory cache.
///
/// Returns a clone of the matching [`KnownHostEntry`], or [`None`] if
/// the host has never been trusted. Performs zero file I/O.
pub(crate) fn lookup(host: &str, port: u16) -> Option<KnownHostEntry> {
    let key = format!("{host}:{port}");
    let cache = CACHE.lock().unwrap_or_else(|e| e.into_inner());
    cache.iter().find(|e| e.host_port == key).cloned()
}

/// Adds or replaces a trusted host key entry.
///
/// If an entry for `host:port` already exists it is replaced with the
/// new `algorithm` and `fingerprint`. The updated cache is written
/// through to the JSON file immediately.
pub(crate) fn trust(
    host: &str,
    port: u16,
    algorithm: &str,
    fingerprint: &str,
) -> Result<(), FfiError> {
    let key = format!("{host}:{port}");
    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as i64;

    let entry = KnownHostEntry {
        host_port: key.clone(),
        algorithm: algorithm.to_owned(),
        fingerprint_sha256: fingerprint.to_owned(),
        trusted_at_epoch_ms: now,
    };

    let mut cache = CACHE.lock().unwrap_or_else(|e| e.into_inner());
    if let Some(existing) = cache.iter_mut().find(|e| e.host_port == key) {
        *existing = entry;
    } else {
        cache.push(entry);
    }
    write_cache(&cache)
}

/// Removes a trusted host key entry.
///
/// Intended for explicit user action (e.g., "Forget this host"). The
/// updated cache is written through to the JSON file immediately.
/// Returns `Ok` if the host was not present (idempotent).
pub(crate) fn remove(host: &str, port: u16) -> Result<(), FfiError> {
    let key = format!("{host}:{port}");
    let mut cache = CACHE.lock().unwrap_or_else(|e| e.into_inner());
    cache.retain(|e| e.host_port != key);
    write_cache(&cache)
}

/// Serializes `entries` to pretty JSON and writes it to the file
/// recorded in [`HOSTS_PATH`].
///
/// Caller must hold the [`CACHE`] mutex while calling this function
/// to ensure the written data is consistent with the in-memory state.
fn write_cache(entries: &[KnownHostEntry]) -> Result<(), FfiError> {
    let path = HOSTS_PATH.get().ok_or_else(|| FfiError::StateError {
        detail: "known-hosts store not initialized — call ssh_key_store_init first".into(),
    })?;
    let json = serde_json::to_string_pretty(entries).map_err(|e| FfiError::IoError {
        detail: format!("failed to serialize known_hosts: {e}"),
    })?;
    fs::write(path, json.as_bytes()).map_err(|e| FfiError::IoError {
        detail: format!("failed to write known_hosts.json: {e}"),
    })
}
