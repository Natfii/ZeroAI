// Copyright (c) 2026 @Natfii. All rights reserved.

//! SSH key store FFI exports.
//!
//! Thin shims that convert owned [`String`] arguments into the borrowed
//! types expected by [`crate::tty::key_store`], then dispatch through the
//! [`crate::ffi_export!`] macro for panic isolation. Keys are generated or
//! imported in memory and returned to Kotlin for encryption at rest; this
//! module performs no disk persistence.

use std::path::Path;

use crate::error::FfiError;
use crate::tty::{key_store, types};

// ── FFI exports ────────────────────────────────────────────────────────────

crate::ffi_export!(
    /// Generates a new SSH keypair in memory and returns it.
    ///
    /// The returned [`types::SshGeneratedKey`] carries the public metadata
    /// plus the unencrypted OpenSSH private PEM bytes. Nothing is written
    /// to disk — the caller encrypts the private bytes at rest.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::IoError`] if key generation or
    /// serialization fails, or [`crate::FfiError::InternalPanic`] if native
    /// code panics.
    fn ssh_generate_key(
        algorithm: types::SshKeyAlgorithm,
        label: String
    ) -> types::SshGeneratedKey = ssh_generate_key_inner
);

crate::ffi_export!(
    /// Imports a private key from a file and normalizes it in memory.
    ///
    /// The source file is **unconditionally deleted** on both success and
    /// error paths. Any passphrase is used only to decrypt the source and is
    /// zeroed after use; the returned PEM bytes are unencrypted. Nothing is
    /// written to the key store.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::IoError`] if the file cannot be read or
    /// parsed, or [`crate::FfiError::InternalPanic`] if native code panics.
    fn ssh_import_key(
        file_path: String,
        passphrase: Option<Vec<u8>>,
        label: String
    ) -> types::SshGeneratedKey = ssh_import_key_inner
);

// ── Inner implementations ──────────────────────────────────────────────────

pub(crate) fn ssh_generate_key_inner(
    algorithm: types::SshKeyAlgorithm,
    label: String,
) -> Result<types::SshGeneratedKey, FfiError> {
    let (metadata, private_pem) = key_store::generate(algorithm, &label)?;
    Ok(types::SshGeneratedKey {
        metadata,
        private_pem,
    })
}

pub(crate) fn ssh_import_key_inner(
    file_path: String,
    passphrase: Option<Vec<u8>>,
    label: String,
) -> Result<types::SshGeneratedKey, FfiError> {
    let (metadata, private_pem) =
        key_store::import_file(Path::new(&file_path), passphrase, &label)?;
    Ok(types::SshGeneratedKey {
        metadata,
        private_pem,
    })
}
