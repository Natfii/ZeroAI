// Copyright (c) 2026 @Natfii. All rights reserved.

//! FFI credential resolver callback with per-provider caching.
//!
//! Allows Kotlin to register a callback that resolves API keys from
//! `EncryptedSharedPreferences` on demand. Resolved credentials are
//! cached in-process so the callback is invoked at most once per
//! provider until the cache is explicitly cleared.

use std::collections::HashMap;
use std::sync::{Arc, Mutex, OnceLock, RwLock};

use crate::error::FfiError;

/// Callback interface that Kotlin implements to resolve API credentials.
///
/// The generated Kotlin class is called from a Rust background thread,
/// so implementations must be thread-safe.
#[uniffi::export(callback_interface)]
pub trait FfiCredentialResolver: Send + Sync {
    /// Returns the API key for the given provider name, or an empty
    /// string if no credential is available.
    fn resolve_credential(&self, provider: String) -> String;
}

/// Global resolver slot.
static RESOLVER: OnceLock<Mutex<Option<Arc<dyn FfiCredentialResolver>>>> = OnceLock::new();

/// Per-provider credential cache.
static CACHE: std::sync::LazyLock<RwLock<HashMap<String, String>>> =
    std::sync::LazyLock::new(|| RwLock::new(HashMap::new()));

/// Returns a reference to the resolver mutex, initialising on first access.
fn resolver_slot() -> &'static Mutex<Option<Arc<dyn FfiCredentialResolver>>> {
    RESOLVER.get_or_init(|| Mutex::new(None))
}

/// Acquires the resolver mutex with poison recovery.
fn lock_resolver() -> std::sync::MutexGuard<'static, Option<Arc<dyn FfiCredentialResolver>>> {
    resolver_slot().lock().unwrap_or_else(|e| {
        tracing::warn!("Credential resolver mutex was poisoned; recovering: {e}");
        e.into_inner()
    })
}

/// Registers a Kotlin-side credential resolver.
///
/// Only one resolver can be registered at a time. A new resolver replaces
/// the previous one and clears the credential cache. Also wires into the
/// core crate's [`zeroclaw::ffi_credential_hook`] so that
/// `resolve_provider_credential` can reach this callback.
#[allow(clippy::unnecessary_wraps)]
pub(crate) fn register_credential_resolver_inner(
    resolver: Arc<dyn FfiCredentialResolver>,
) -> Result<(), FfiError> {
    let mut slot = lock_resolver();
    *slot = Some(resolver);

    // Wire the core crate hook so `resolve_provider_credential` can
    // reach this callback without depending on `zeroclaw-ffi`.
    zeroclaw::ffi_credential_hook::register(Box::new(move |provider| {
        resolve_credential_via_callback(provider)
    }));

    // Clear the cache when a new resolver is registered so stale
    // credentials from a previous resolver do not persist.
    if let Ok(mut cache) = CACHE.write() {
        cache.clear();
    }

    Ok(())
}

/// Unregisters the current credential resolver, the core crate hook,
/// and clears the cache.
#[allow(clippy::unnecessary_wraps)]
pub(crate) fn unregister_credential_resolver_inner() -> Result<(), FfiError> {
    let mut slot = lock_resolver();
    *slot = None;

    zeroclaw::ffi_credential_hook::unregister();

    if let Ok(mut cache) = CACHE.write() {
        cache.clear();
    }

    Ok(())
}

/// Clears the per-provider credential cache.
///
/// The next `resolve_credential_via_callback` call for each provider
/// will re-invoke the Kotlin callback.
#[allow(clippy::unnecessary_wraps)]
pub(crate) fn clear_credential_cache_inner() -> Result<(), FfiError> {
    if let Ok(mut cache) = CACHE.write() {
        cache.clear();
    }
    Ok(())
}

/// Resolves a credential for the given provider, using the cache first
/// and falling back to the registered Kotlin callback.
///
/// Returns `None` if no resolver is registered or if the resolver
/// returns an empty string.
pub(crate) fn resolve_credential_via_callback(provider: &str) -> Option<String> {
    // Fast path: check the cache under a read lock.
    if let Ok(cache) = CACHE.read()
        && let Some(cached) = cache.get(provider)
    {
        return Some(cached.clone());
    }

    // Slow path: invoke the Kotlin callback.
    let maybe_resolver = lock_resolver().as_ref().map(Arc::clone);
    let resolver = maybe_resolver?;
    let value = resolver.resolve_credential(provider.to_owned());
    let trimmed = value.trim().to_owned();
    if trimmed.is_empty() {
        return None;
    }

    // Cache the result.
    if let Ok(mut cache) = CACHE.write() {
        cache.insert(provider.to_owned(), trimmed.clone());
    }

    Some(trimmed)
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering};

    /// Resets global state between tests.
    fn reset_state() {
        let mut slot = lock_resolver();
        *slot = None;
        if let Ok(mut cache) = CACHE.write() {
            cache.clear();
        }
    }

    /// A test resolver that returns a fixed key for "openai" and empty
    /// for everything else.
    struct TestResolver {
        call_count: Arc<AtomicUsize>,
    }

    impl FfiCredentialResolver for TestResolver {
        fn resolve_credential(&self, provider: String) -> String {
            self.call_count.fetch_add(1, Ordering::SeqCst);
            if provider == "openai" {
                "sk-test-key-12345".to_string()
            } else {
                String::new()
            }
        }
    }

    #[test]
    fn test_callback_resolves_known_provider() {
        reset_state();
        let count = Arc::new(AtomicUsize::new(0));
        let resolver: Arc<dyn FfiCredentialResolver> = Arc::new(TestResolver {
            call_count: count.clone(),
        });
        register_credential_resolver_inner(resolver).unwrap();

        let result = resolve_credential_via_callback("openai");
        assert_eq!(result, Some("sk-test-key-12345".to_string()));
        assert_eq!(count.load(Ordering::SeqCst), 1);

        reset_state();
    }

    #[test]
    fn test_returns_none_for_empty_string() {
        reset_state();
        let count = Arc::new(AtomicUsize::new(0));
        let resolver: Arc<dyn FfiCredentialResolver> = Arc::new(TestResolver {
            call_count: count.clone(),
        });
        register_credential_resolver_inner(resolver).unwrap();

        let result = resolve_credential_via_callback("unknown-provider");
        assert_eq!(result, None);

        reset_state();
    }

    #[test]
    fn test_returns_none_with_no_callback() {
        reset_state();
        let result = resolve_credential_via_callback("openai");
        assert_eq!(result, None);
    }

    #[test]
    fn test_cache_prevents_second_callback_call() {
        reset_state();
        let count = Arc::new(AtomicUsize::new(0));
        let resolver: Arc<dyn FfiCredentialResolver> = Arc::new(TestResolver {
            call_count: count.clone(),
        });
        register_credential_resolver_inner(resolver).unwrap();

        // First call — hits callback.
        let r1 = resolve_credential_via_callback("openai");
        assert_eq!(r1, Some("sk-test-key-12345".to_string()));
        assert_eq!(count.load(Ordering::SeqCst), 1);

        // Second call — should hit cache, not callback.
        let r2 = resolve_credential_via_callback("openai");
        assert_eq!(r2, Some("sk-test-key-12345".to_string()));
        assert_eq!(count.load(Ordering::SeqCst), 1);

        reset_state();
    }

    #[test]
    fn test_clear_cache_forces_re_resolve() {
        reset_state();
        let count = Arc::new(AtomicUsize::new(0));
        let resolver: Arc<dyn FfiCredentialResolver> = Arc::new(TestResolver {
            call_count: count.clone(),
        });
        register_credential_resolver_inner(resolver).unwrap();

        // Populate cache.
        let _ = resolve_credential_via_callback("openai");
        assert_eq!(count.load(Ordering::SeqCst), 1);

        // Clear and re-resolve.
        clear_credential_cache_inner().unwrap();
        let _ = resolve_credential_via_callback("openai");
        assert_eq!(count.load(Ordering::SeqCst), 2);

        reset_state();
    }
}
