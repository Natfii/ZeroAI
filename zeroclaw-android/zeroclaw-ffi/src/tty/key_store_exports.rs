// Copyright (c) 2026 @Natfii. All rights reserved.

//! SSH key store FFI exports.
//!
//! Thin shims that convert owned [`String`] arguments into the borrowed
//! types expected by [`crate::tty::key_store`], then dispatch through the
//! [`crate::ffi_export!`] macro for panic isolation. Keeps `lib.rs` free
//! of `unsafe`-adjacent path/&str juggling.

use std::path::{Path, PathBuf};

use crate::error::FfiError;
use crate::tty::{key_store, known_hosts, types};

// ── FFI exports ────────────────────────────────────────────────────────────

crate::ffi_export!(
    /// Initialises the SSH key store at `keys_dir` and the sibling
    /// `known_hosts.json` file. Idempotent.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::IoError`] if directory creation fails, or
    /// [`crate::FfiError::InternalPanic`] if native code panics.
    fn ssh_key_store_init(keys_dir: String) -> () = ssh_key_store_init_inner
);

crate::ffi_export!(
    /// Generates a new SSH keypair and stores the private key on disk.
    ///
    /// Returns metadata including the `key_id` needed for future
    /// operations. The private key never crosses the FFI boundary.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::IoError`] if key generation or file
    /// write fails, or [`crate::FfiError::InternalPanic`] if native code
    /// panics.
    fn ssh_generate_key(
        algorithm: types::SshKeyAlgorithm,
        label: String
    ) -> types::SshKeyMetadata = ssh_generate_key_inner
);

crate::ffi_export!(
    /// Imports a private key from a file on disk.
    ///
    /// The source file is **unconditionally deleted** on both success
    /// and error paths. Passphrase is zeroed after use.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::IoError`] if the file cannot be read or
    /// parsed, or [`crate::FfiError::InternalPanic`] if native code panics.
    fn ssh_import_key(
        file_path: String,
        passphrase: Option<Vec<u8>>,
        label: String
    ) -> types::SshKeyMetadata = ssh_import_key_inner
);

crate::ffi_export!(
    /// Deletes an SSH key from disk. Idempotent.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::IoError`] if file deletion fails, or
    /// [`crate::FfiError::InternalPanic`] if native code panics.
    fn ssh_delete_key(key_id: String) -> () = ssh_delete_key_inner
);

crate::ffi_export!(
    /// Returns the public key in OpenSSH format for the given key ID.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::InvalidArgument`] if the key is not
    /// found, or [`crate::FfiError::InternalPanic`] if native code panics.
    fn ssh_export_public_key(key_id: String) -> String = ssh_export_public_key_inner
);

crate::ffi_export!(
    /// Checks whether a key file exists on disk for the given ID.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::StateError`] if the key store is not
    /// initialised, or [`crate::FfiError::InternalPanic`] if native code
    /// panics.
    fn ssh_key_exists(key_id: String) -> bool = ssh_key_exists_inner
);

crate::ffi_export!(
    /// Lists all key IDs in the key store directory.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::IoError`] if the directory cannot be
    /// read, or [`crate::FfiError::InternalPanic`] if native code panics.
    fn ssh_list_key_ids() -> Vec<String> = ssh_list_key_ids_inner
);

// ── Inner implementations ──────────────────────────────────────────────────

pub(crate) fn ssh_key_store_init_inner(keys_dir: String) -> Result<(), FfiError> {
    let keys_path = PathBuf::from(&keys_dir);
    key_store::init(keys_path.clone())?;
    let hosts_path = keys_path
        .parent()
        .unwrap_or(&keys_path)
        .join("known_hosts.json");
    known_hosts::init(hosts_path)
}

pub(crate) fn ssh_generate_key_inner(
    algorithm: types::SshKeyAlgorithm,
    label: String,
) -> Result<types::SshKeyMetadata, FfiError> {
    key_store::generate(algorithm, &label)
}

pub(crate) fn ssh_import_key_inner(
    file_path: String,
    passphrase: Option<Vec<u8>>,
    label: String,
) -> Result<types::SshKeyMetadata, FfiError> {
    key_store::import_file(Path::new(&file_path), passphrase, &label)
}

pub(crate) fn ssh_delete_key_inner(key_id: String) -> Result<(), FfiError> {
    key_store::delete(&key_id)
}

pub(crate) fn ssh_export_public_key_inner(key_id: String) -> Result<String, FfiError> {
    key_store::export_public(&key_id)
}

pub(crate) fn ssh_key_exists_inner(key_id: String) -> Result<bool, FfiError> {
    key_store::key_exists(&key_id)
}

pub(crate) fn ssh_list_key_ids_inner() -> Result<Vec<String>, FfiError> {
    key_store::list_key_ids()
}
