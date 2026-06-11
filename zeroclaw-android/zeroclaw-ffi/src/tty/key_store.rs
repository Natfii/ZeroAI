// Copyright (c) 2026 @Natfii. All rights reserved.

//! SSH key generation and import.
//!
//! Produces unencrypted OpenSSH-format PEM bytes that the Kotlin layer
//! encrypts at rest. Private keys are never written to disk in plaintext
//! by this module — generation builds the key purely in memory, and import
//! deletes its temporary source file before returning.

use std::path::Path;

use rand_core::OsRng;
use ssh_key::public::{EcdsaPublicKey, KeyData};
use ssh_key::{Algorithm, EcdsaCurve, HashAlg, LineEnding, PrivateKey, PublicKey};
use zeroize::Zeroize;

use crate::error::FfiError;
use crate::tty::types::{SshKeyAlgorithm, SshKeyMetadata};

/// Generates a new SSH keypair in memory.
///
/// Returns the public metadata alongside the unencrypted OpenSSH-format
/// private key PEM bytes. Nothing is written to disk; the caller is
/// responsible for encrypting the returned bytes at rest.
pub(crate) fn generate(
    algorithm: SshKeyAlgorithm,
    label: &str,
) -> Result<(SshKeyMetadata, Vec<u8>), FfiError> {
    let key = match algorithm {
        SshKeyAlgorithm::EcdsaP256 => PrivateKey::random(
            &mut OsRng,
            Algorithm::Ecdsa {
                curve: EcdsaCurve::NistP256,
            },
        )
        .map_err(|e| FfiError::IoError {
            detail: format!("key generation failed: {e}"),
        })?,
        SshKeyAlgorithm::Ed25519 => {
            PrivateKey::random(&mut OsRng, Algorithm::Ed25519).map_err(|e| FfiError::IoError {
                detail: format!("key generation failed: {e}"),
            })?
        }
        SshKeyAlgorithm::Rsa4096 => {
            let rsa_key =
                rsa::RsaPrivateKey::new(&mut OsRng, 4096).map_err(|e| FfiError::IoError {
                    detail: format!("RSA key generation failed: {e}"),
                })?;
            let keypair =
                ssh_key::private::RsaKeypair::try_from(rsa_key).map_err(|e| FfiError::IoError {
                    detail: format!("RSA key conversion failed: {e}"),
                })?;
            keypair.into()
        }
    };

    let key_id = uuid::Uuid::new_v4().to_string();
    // Build the (fallible) metadata first so the plaintext PEM is the last
    // value produced — nothing after it can fail and drop it un-zeroed.
    let metadata = build_metadata(key_id, &algorithm, label, &key)?;
    let pem = to_openssh_pem(&key)?;
    Ok((metadata, pem))
}

/// Imports a private key from a file and normalizes it to OpenSSH PEM bytes.
///
/// The source file at `file_path` is **unconditionally deleted** on both
/// success and error paths. A supplied passphrase decrypts the source key;
/// the returned PEM bytes are always unencrypted. Passphrase bytes are
/// zeroed after use, and nothing is written to the key store.
pub(crate) fn import_file(
    file_path: &Path,
    mut passphrase: Option<Vec<u8>>,
    label: &str,
) -> Result<(SshKeyMetadata, Vec<u8>), FfiError> {
    // Drop guard ensures the temp source file is always cleaned up.
    struct CleanupGuard<'a>(&'a Path);
    impl Drop for CleanupGuard<'_> {
        fn drop(&mut self) {
            let _ = std::fs::remove_file(self.0);
        }
    }
    let _cleanup = CleanupGuard(file_path);

    let mut pass_str: Option<String> = passphrase.as_ref().map(|s| {
        String::from_utf8(s.clone()).unwrap_or_else(|_| String::from_utf8_lossy(s).into_owned())
    });
    // Zero passphrase bytes immediately after converting to string.
    if let Some(ref mut bytes) = passphrase {
        bytes.as_mut_slice().zeroize();
    }
    let parsed = PrivateKey::read_openssh_file(file_path).map_err(|e| FfiError::IoError {
        detail: format!("failed to parse key file: {e}"),
    })?;
    let key = if parsed.is_encrypted() {
        let pass = pass_str.as_deref().ok_or_else(|| FfiError::InvalidArgument {
            detail: "key is encrypted but no passphrase was provided".to_owned(),
        })?;
        parsed.decrypt(pass).map_err(|e| FfiError::InvalidArgument {
            detail: format!("failed to decrypt key: {e}"),
        })?
    } else {
        parsed
    };
    // Zero the intermediate String copy of the passphrase.
    if let Some(ref mut s) = pass_str {
        s.zeroize();
    }

    let algorithm = match key.algorithm() {
        Algorithm::Ed25519 => SshKeyAlgorithm::Ed25519,
        Algorithm::Ecdsa { .. } => SshKeyAlgorithm::EcdsaP256,
        _ => SshKeyAlgorithm::Rsa4096,
    };

    let key_id = uuid::Uuid::new_v4().to_string();
    // Build the (fallible) metadata first so the plaintext PEM is the last
    // value produced — nothing after it can fail and drop it un-zeroed.
    let metadata = build_metadata(key_id, &algorithm, label, &key)?;
    let pem = to_openssh_pem(&key)?;
    Ok((metadata, pem))
}

// --- helpers ---

/// Serializes a private key to unencrypted OpenSSH PEM bytes in memory.
fn to_openssh_pem(key: &PrivateKey) -> Result<Vec<u8>, FfiError> {
    let pem = key
        .to_openssh(LineEnding::LF)
        .map_err(|e| FfiError::IoError {
            detail: format!("failed to serialize private key: {e}"),
        })?;
    Ok(pem.as_bytes().to_vec())
}

fn build_metadata(
    key_id: String,
    algorithm: &SshKeyAlgorithm,
    label: &str,
    key: &PrivateKey,
) -> Result<SshKeyMetadata, FfiError> {
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
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
        is_hardware: false,
    })
}

/// Builds public metadata for a hardware (Android Keystore) SSH key from its
/// raw EC public point.
///
/// The private key lives in the secure element and never reaches Rust; only the
/// SEC1 uncompressed point (`0x04 || X || Y`) crosses the FFI, from which the
/// OpenSSH `ecdsa-sha2-nistp256` public key and its SHA-256 fingerprint are
/// derived.
pub(crate) fn hardware_key_metadata(
    ec_point_sec1: &[u8],
    key_id: String,
    label: &str,
) -> Result<SshKeyMetadata, FfiError> {
    let ecdsa =
        EcdsaPublicKey::from_sec1_bytes(ec_point_sec1).map_err(|e| FfiError::InvalidArgument {
            detail: format!("invalid EC public point: {e}"),
        })?;
    let public_key = PublicKey::new(KeyData::Ecdsa(ecdsa), label.to_owned());
    let public_key_openssh = public_key.to_openssh().map_err(|e| FfiError::IoError {
        detail: format!("failed to serialize public key: {e}"),
    })?;
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as i64;
    Ok(SshKeyMetadata {
        key_id,
        algorithm: SshKeyAlgorithm::EcdsaP256,
        label: label.to_owned(),
        fingerprint_sha256: public_key.fingerprint(HashAlg::Sha256).to_string(),
        public_key_openssh,
        created_at_epoch_ms: now,
        is_hardware: true,
    })
}
