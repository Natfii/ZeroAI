// Copyright (c) 2026 @Natfii. All rights reserved.

//! SHA-256 content hashing for script identity.

use sha2::{Digest, Sha256};
use std::path::Path;

/// Computes the SHA-256 hex digest of a file's contents.
///
/// Used to bind capability grants to a specific script version.
/// Returns lowercase hex string (64 chars).
pub fn hash_file(path: &Path) -> std::io::Result<String> {
    let contents = std::fs::read(path)?;
    Ok(hash_bytes(&contents))
}

/// Computes the SHA-256 hex digest of a byte slice.
pub fn hash_bytes(data: &[u8]) -> String {
    let mut hasher = Sha256::new();
    hasher.update(data);
    format!("{:x}", hasher.finalize())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hash_bytes_is_deterministic() {
        let h1 = hash_bytes(b"hello world");
        let h2 = hash_bytes(b"hello world");
        assert_eq!(h1, h2);
        assert_eq!(h1.len(), 64);
    }

    #[test]
    fn hash_bytes_differs_for_different_input() {
        let h1 = hash_bytes(b"hello");
        let h2 = hash_bytes(b"world");
        assert_ne!(h1, h2);
    }

    #[test]
    fn hash_file_roundtrip() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("test.rhai");
        std::fs::write(&path, b"let x = 42;").unwrap();
        let h = hash_file(&path).unwrap();
        assert_eq!(h, hash_bytes(b"let x = 42;"));
    }
}
