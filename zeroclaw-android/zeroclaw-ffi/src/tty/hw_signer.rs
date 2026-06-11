// Copyright (c) 2026 @Natfii. All rights reserved.

//! Hardware SSH signer callback bridge.
//!
//! Kotlin registers an [`FfiHardwareSshSigner`] whose `sign` method runs a
//! Keystore `SHA256withECDSA` signature for keys whose private half is
//! sealed in StrongBox/TEE. The agent protocol loop
//! ([`crate::tty::agent_protocol`]) calls through this slot for hardware
//! identities; software keys never touch it.
//!
//! Follows the same global-slot pattern as
//! [`crate::credentials::FfiCredentialResolver`].

use std::sync::{Arc, Mutex, OnceLock};

/// Callback interface Kotlin implements to sign with an Android Keystore
/// key.
///
/// Called from a Rust blocking-pool thread, so implementations must be
/// thread-safe.
#[uniffi::export(callback_interface)]
pub trait FfiHardwareSshSigner: Send + Sync {
    /// Signs `data` with the Keystore key under `keystore_alias` using
    /// `SHA256withECDSA` (the data is hashed by Keystore; pass it raw).
    ///
    /// Returns the DER-encoded ECDSA signature, or an empty vector when
    /// signing fails (missing key, locked device, invalidated key).
    fn sign(&self, keystore_alias: String, data: Vec<u8>) -> Vec<u8>;
}

/// Global signer slot.
static SIGNER: OnceLock<Mutex<Option<Arc<dyn FfiHardwareSshSigner>>>> = OnceLock::new();

/// Returns the signer mutex, initialising on first access.
fn signer_slot() -> &'static Mutex<Option<Arc<dyn FfiHardwareSshSigner>>> {
    SIGNER.get_or_init(|| Mutex::new(None))
}

/// Acquires the signer mutex with poison recovery.
fn lock_signer() -> std::sync::MutexGuard<'static, Option<Arc<dyn FfiHardwareSshSigner>>> {
    signer_slot().lock().unwrap_or_else(|e| {
        tracing::warn!("Hardware SSH signer mutex was poisoned; recovering: {e}");
        e.into_inner()
    })
}

/// Stores `signer` as the process-wide hardware signer.
///
/// A new registration replaces the previous one.
pub(crate) fn register(signer: Arc<dyn FfiHardwareSshSigner>) {
    *lock_signer() = Some(signer);
}

/// Signs `data` under `keystore_alias` via the registered callback.
///
/// Returns `None` when no signer is registered or the callback reports
/// failure (empty signature). Blocking — call from a blocking-pool thread.
pub(crate) fn sign(keystore_alias: &str, data: Vec<u8>) -> Option<Vec<u8>> {
    let signer = lock_signer().as_ref().map(Arc::clone)?;
    let der = signer.sign(keystore_alias.to_owned(), data);
    if der.is_empty() { None } else { Some(der) }
}
