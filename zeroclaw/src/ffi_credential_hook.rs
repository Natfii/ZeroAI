// Copyright (c) 2026 @Natfii. All rights reserved.

//! Optional hook point for FFI credential resolution.
//!
//! Provides a global callback slot that the `zeroclaw-ffi` crate can
//! register into, allowing the core engine to resolve per-provider
//! credentials from Android's `EncryptedSharedPreferences` without
//! depending on the FFI crate directly.

use std::sync::{Mutex, OnceLock};

type CredentialFn = Box<dyn Fn(&str) -> Option<String> + Send + Sync>;

static HOOK: OnceLock<Mutex<Option<CredentialFn>>> = OnceLock::new();

fn lock_hook() -> std::sync::MutexGuard<'static, Option<CredentialFn>> {
    HOOK.get_or_init(|| Mutex::new(None))
        .lock()
        .unwrap_or_else(|e| e.into_inner())
}

/// Registers the credential resolution hook.
pub fn register(hook: CredentialFn) {
    let mut slot = lock_hook();
    *slot = Some(hook);
}

/// Unregisters the credential resolution hook.
pub fn unregister() {
    let mut slot = lock_hook();
    *slot = None;
}

/// Attempts to resolve a credential via the registered hook.
pub fn resolve_via_callback(provider: &str) -> Option<String> {
    let slot = lock_hook();
    slot.as_ref().and_then(|f| f(provider))
}
