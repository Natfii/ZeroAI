// Copyright (c) 2026 @Natfii. All rights reserved.

//! SSH key lifecycle management.
//!
//! Private keys are stored as OpenSSH-format PEM files under an
//! app-private directory. All operations are serialized via a
//! module-level [`Mutex`].

use std::fs;
use std::path::{Path, PathBuf};
use std::sync::{Mutex, OnceLock};
use std::time::{SystemTime, UNIX_EPOCH};

use rand_core::OsRng;
use russh_keys::{HashAlg, PrivateKey};
use ssh_key::{Algorithm, LineEnding};
use zeroize::Zeroize;

use crate::error::FfiError;
use crate::tty::types::{SshKeyAlgorithm, SshKeyMetadata};

/// Global storage directory, set once via [`init`].
static KEYS_DIR: OnceLock<PathBuf> = OnceLock::new();

/// Serializes all file operations.
static LOCK: Mutex<()> = Mutex::new(());

/// Initializes the key store directory.
///
/// Creates the directory (and parents) if absent. Idempotent if
/// called again with the same path; returns [`FfiError::StateError`]
/// if called with a different path.
pub(crate) fn init(keys_dir: PathBuf) -> Result<(), FfiError> {
    fs::create_dir_all(&keys_dir).map_err(|e| FfiError::IoError {
        detail: format!("failed to create keys directory: {e}"),
    })?;
    // OnceLock::set returns Err(value_you_tried_to_set) on failure,
    // so we must read the stored value via get() to compare.
    if let Some(existing) = KEYS_DIR.get() {
        return if *existing == keys_dir {
            Ok(())
        } else {
            Err(FfiError::StateError {
                detail: "key store already initialized with a different path".into(),
            })
        };
    }
    let _ = KEYS_DIR.set(keys_dir);
    Ok(())
}

/// Generates a new SSH keypair and writes it to disk.
pub(crate) fn generate(
    algorithm: SshKeyAlgorithm,
    label: &str,
) -> Result<SshKeyMetadata, FfiError> {
    let dir = keys_dir()?;
    let _guard = lock()?;

    let key = match algorithm {
        SshKeyAlgorithm::Ed25519 => {
            PrivateKey::random(&mut OsRng, Algorithm::Ed25519).map_err(|e| {
                FfiError::IoError {
                    detail: format!("key generation failed: {e}"),
                }
            })?
        }
        SshKeyAlgorithm::Rsa4096 => {
            let rsa_key = rsa::RsaPrivateKey::new(&mut OsRng, 4096).map_err(|e| {
                FfiError::IoError {
                    detail: format!("RSA key generation failed: {e}"),
                }
            })?;
            let keypair = ssh_key::private::RsaKeypair::try_from(rsa_key)
                .map_err(|e| FfiError::IoError {
                    detail: format!("RSA key conversion failed: {e}"),
                })?;
            keypair.into()
        }
    };

    let key_id = uuid::Uuid::new_v4().to_string();
    let tmp_path = dir.join(format!("{key_id}.tmp"));
    let pem_path = dir.join(format!("{key_id}.pem"));

    // Write to .tmp first for atomic rename.
    if let Err(e) = key.write_openssh_file(&tmp_path, LineEnding::LF) {
        let _ = fs::remove_file(&tmp_path);
        return Err(FfiError::IoError {
            detail: format!("failed to write key file: {e}"),
        });
    }
    fs::rename(&tmp_path, &pem_path).map_err(|e| {
        let _ = fs::remove_file(&tmp_path);
        FfiError::IoError {
            detail: format!("failed to rename key file: {e}"),
        }
    })?;

    build_metadata(key_id, &algorithm, label, &key)
}

/// Imports a private key from a file.
///
/// The source file at `file_path` is **unconditionally deleted** on
/// both success and error paths. Passphrase bytes are zeroed after use.
pub(crate) fn import_file(
    file_path: &Path,
    mut passphrase: Option<Vec<u8>>,
    label: &str,
) -> Result<SshKeyMetadata, FfiError> {
    // Drop guard ensures temp file is always cleaned up.
    struct CleanupGuard<'a>(&'a Path);
    impl Drop for CleanupGuard<'_> {
        fn drop(&mut self) {
            let _ = fs::remove_file(self.0);
        }
    }
    let _cleanup = CleanupGuard(file_path);

    let dir = keys_dir()?;
    let _guard = lock()?;

    let mut pass_str: Option<String> = passphrase.as_ref().map(|s| {
        String::from_utf8(s.clone()).unwrap_or_else(|_| {
            String::from_utf8_lossy(s).into_owned()
        })
    });
    // Zero passphrase bytes immediately after converting to string.
    if let Some(ref mut bytes) = passphrase {
        bytes.as_mut_slice().zeroize();
    }
    let key = russh_keys::load_secret_key(file_path, pass_str.as_deref())
        .map_err(|e| FfiError::IoError {
            detail: format!("failed to parse key file: {e}"),
        })?;
    // Zero the intermediate String copy of the passphrase.
    if let Some(ref mut s) = pass_str {
        s.zeroize();
    }

    let algorithm = match key.algorithm() {
        Algorithm::Ed25519 => SshKeyAlgorithm::Ed25519,
        _ => SshKeyAlgorithm::Rsa4096,
    };

    let key_id = uuid::Uuid::new_v4().to_string();
    let pem_path = dir.join(format!("{key_id}.pem"));
    key.write_openssh_file(&pem_path, LineEnding::LF)
        .map_err(|e| FfiError::IoError {
            detail: format!("failed to write imported key: {e}"),
        })?;

    build_metadata(key_id, &algorithm, label, &key)
}

/// Deletes a key file. Idempotent — returns `Ok` if already absent.
pub(crate) fn delete(key_id: &str) -> Result<(), FfiError> {
    let dir = keys_dir()?;
    let _guard = lock()?;
    let path = dir.join(format!("{key_id}.pem"));
    match fs::remove_file(&path) {
        Ok(()) => Ok(()),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(e) => Err(FfiError::IoError {
            detail: format!("failed to delete key: {e}"),
        }),
    }
}

/// Returns the public key in OpenSSH format.
pub(crate) fn export_public(key_id: &str) -> Result<String, FfiError> {
    let key = load_key(key_id)?;
    key.public_key()
        .to_openssh()
        .map_err(|e| FfiError::IoError {
            detail: format!("failed to export public key: {e}"),
        })
}

/// Checks whether a key file exists on disk.
pub(crate) fn key_exists(key_id: &str) -> Result<bool, FfiError> {
    let dir = keys_dir()?;
    Ok(dir.join(format!("{key_id}.pem")).exists())
}

/// Lists all key IDs (filenames without `.pem` extension).
pub(crate) fn list_key_ids() -> Result<Vec<String>, FfiError> {
    let dir = keys_dir()?;
    let _guard = lock()?;
    let mut ids = Vec::new();
    let entries = fs::read_dir(dir).map_err(|e| FfiError::IoError {
        detail: format!("failed to read keys directory: {e}"),
    })?;
    for entry in entries {
        let entry = entry.map_err(|e| FfiError::IoError {
            detail: format!("failed to read directory entry: {e}"),
        })?;
        if let Some(name) = entry.path().file_stem() {
            if entry.path().extension().map_or(false, |ext| ext == "pem") {
                ids.push(name.to_string_lossy().into_owned());
            }
        }
    }
    Ok(ids)
}

/// Returns the filesystem path to a key file.
///
/// Used by the SSH module to load keys with `russh::keys` types
/// (which differ from `russh_keys` types due to the internal fork).
pub(crate) fn key_path(key_id: &str) -> Result<std::path::PathBuf, FfiError> {
    let dir = keys_dir()?;
    let path = dir.join(format!("{key_id}.pem"));
    if !path.exists() {
        return Err(FfiError::InvalidArgument {
            detail: format!("key file not found: {key_id}"),
        });
    }
    Ok(path)
}

// --- helpers ---

fn keys_dir() -> Result<&'static PathBuf, FfiError> {
    KEYS_DIR.get().ok_or_else(|| FfiError::StateError {
        detail: "key store not initialized — call ssh_key_store_init first".into(),
    })
}

fn lock() -> Result<std::sync::MutexGuard<'static, ()>, FfiError> {
    LOCK.lock().map_err(|_| FfiError::StateCorrupted {
        detail: "key store mutex poisoned".into(),
    })
}

pub(crate) fn load_key(key_id: &str) -> Result<PrivateKey, FfiError> {
    let dir = keys_dir()?;
    let _guard = lock()?;
    let path = dir.join(format!("{key_id}.pem"));
    russh_keys::load_secret_key(&path, None).map_err(|e| FfiError::InvalidArgument {
        detail: format!("key not found or corrupt: {e}"),
    })
}

fn build_metadata(
    key_id: String,
    algorithm: &SshKeyAlgorithm,
    label: &str,
    key: &PrivateKey,
) -> Result<SshKeyMetadata, FfiError> {
    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as i64;
    let public_key_openssh = key
        .public_key()
        .to_openssh()
        .map_err(|e| FfiError::IoError {
            detail: format!("failed to serialize public key: {e}"),
        })?;
    Ok(SshKeyMetadata {
        key_id,
        algorithm: algorithm.clone(),
        label: label.to_owned(),
        fingerprint_sha256: key.fingerprint(HashAlg::Sha256).to_string(),
        public_key_openssh,
        created_at_epoch_ms: now,
    })
}
