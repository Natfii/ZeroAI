// Copyright (c) 2026 @Natfii. All rights reserved.

//! Known-hosts store for SSH host key verification.
//!
//! Persists accepted host key fingerprints across sessions and detects
//! when a server's key has changed since the last successful connection.
//!
//! The store is seeded and loaded by [`init`] as part of the SSH key
//! store setup.

use std::fs;
use std::path::PathBuf;
use std::sync::{Mutex, OnceLock};

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
