// Copyright (c) 2026 @Natfii. All rights reserved.

//! Minimal trust-on-first-use host-key store.
//!
//! Records accepted `[host]:port fingerprint` lines under
//! `$HOME/.ssh/zssh_known_hosts`. This is intentionally a small, self
//! contained store (not the full OpenSSH `known_hosts` format) so the
//! bundled client has no extra dependencies.

use std::io::{BufRead, Write};
use std::path::PathBuf;

/// Result of checking a server key against the store.
pub(crate) enum Verdict {
    /// The host is recorded and its fingerprint matches.
    Trusted,
    /// The host has no recorded fingerprint yet.
    Unknown,
    /// The host is recorded but the fingerprint differs (possible MITM).
    Changed,
}

/// Returns the path to the known-hosts file, or `None` if `$HOME` is unset.
fn store_path() -> Option<PathBuf> {
    let home = std::env::var("HOME").ok()?;
    Some(PathBuf::from(home).join(".ssh").join("zssh_known_hosts"))
}

/// Builds the canonical `[host]:port` key used as the line prefix.
fn host_key(host: &str, port: u16) -> String {
    format!("[{host}]:{port}")
}

/// Checks `fingerprint` for `host:port` against the store.
///
/// @param host The remote hostname.
/// @param port The remote port.
/// @param fingerprint The server key's SHA-256 fingerprint string.
/// @return The [`Verdict`] for this host/key pair.
pub(crate) fn check(host: &str, port: u16, fingerprint: &str) -> Verdict {
    let key = host_key(host, port);
    let Some(path) = store_path() else {
        return Verdict::Unknown;
    };
    let Ok(file) = std::fs::File::open(&path) else {
        return Verdict::Unknown;
    };
    for line in std::io::BufReader::new(file).lines().map_while(Result::ok) {
        let mut parts = line.splitn(2, ' ');
        if parts.next() == Some(key.as_str()) {
            return if parts.next() == Some(fingerprint) {
                Verdict::Trusted
            } else {
                Verdict::Changed
            };
        }
    }
    Verdict::Unknown
}

/// Appends a trusted `host:port fingerprint` entry to the store, creating
/// the `.ssh` directory if needed.
///
/// @param host The remote hostname.
/// @param port The remote port.
/// @param fingerprint The server key's SHA-256 fingerprint string.
/// @return `Ok` on success, or the underlying I/O error.
pub(crate) fn trust(host: &str, port: u16, fingerprint: &str) -> std::io::Result<()> {
    let Some(path) = store_path() else {
        return Ok(());
    };
    if let Some(dir) = path.parent() {
        std::fs::create_dir_all(dir)?;
    }
    let mut file = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(&path)?;
    writeln!(file, "{} {}", host_key(host, port), fingerprint)?;
    Ok(())
}
