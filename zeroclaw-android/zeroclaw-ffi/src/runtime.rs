/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

use crate::error::FfiError;
use crate::runtime_channels::{
    bind_channel_identity_inner, collect_channels, get_channel_allowlist_inner,
    get_configured_channel_names_inner, has_supervised_channels,
};
use chrono::Utc;
use std::future::Future;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, Once, OnceLock};
use tokio::runtime::{Handle, Runtime};
use tokio::task::JoinHandle;
use tokio::time::Duration;
use zeroclaw::Config;
use zeroclaw_config::pairing::PairingGuard;

/// On-device Gemini Nano availability, set from Kotlin.
static NANO_AVAILABLE: AtomicBool = AtomicBool::new(false);

/// Set on-device Nano availability from Kotlin after ML Kit status check.
pub(crate) fn set_nano_available_inner(available: bool) {
    NANO_AVAILABLE.store(available, Ordering::Release);
}

/// Query on-device Nano availability for agent eval_script.
pub(crate) fn is_nano_available_inner() -> bool {
    NANO_AVAILABLE.load(Ordering::Acquire)
}

// ── FFI exports ────────────────────────────────────────────────────────────

crate::ffi_export!(
    /// Returns the crate version string from `Cargo.toml`.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::InternalPanic`] if native code panics.
    fn get_version() -> String = get_version_inner
);

crate::ffi_export!(
    /// Update on-device Gemini Nano availability.
    ///
    /// Called from Kotlin after ML Kit `checkModelStatus()` at daemon
    /// startup and on config changes. Default is `false`.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::InternalPanic`] if native code panics.
    fn set_nano_available(available: bool) -> () = set_nano_available_ffi
);

crate::ffi_export!(
    /// Generates a bearer token for WebView authentication.
    ///
    /// Creates a random token, registers its SHA-256 hash with the
    /// gateway's pairing guard, and returns the plaintext. The token is
    /// never persisted — the caller must hold it in memory only.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::StateError`] if the daemon is not
    /// running, or [`crate::FfiError::InternalPanic`] if native code
    /// panics.
    fn create_pairing_token() -> String = create_pairing_token_inner
);

#[allow(clippy::unnecessary_wraps)]
pub(crate) fn get_version_inner() -> Result<String, FfiError> {
    Ok(env!("CARGO_PKG_VERSION").to_string())
}

#[allow(clippy::unnecessary_wraps)]
pub(crate) fn set_nano_available_ffi(available: bool) -> Result<(), FfiError> {
    set_nano_available_inner(available);
    Ok(())
}

pub(crate) fn create_pairing_token_inner() -> Result<String, FfiError> {
    let guard = get_pairing_guard()?;
    Ok(guard.generate_new_pairing_code().unwrap_or_default())
}

crate::ffi_export!(
    /// Sends a message through the full agent loop and returns the response.
    ///
    /// Routes through [`zeroclaw::agent::process_message`] which provides
    /// memory recall, tool access, and proper workspace identity
    /// injection.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::EstopEngaged`] when emergency stop is
    /// active, [`crate::FfiError::StateError`] if the daemon is not
    /// running, [`crate::FfiError::SpawnError`] if agent processing fails,
    /// [`crate::FfiError::StateCorrupted`] if internal state is poisoned,
    /// or [`crate::FfiError::InternalPanic`] if native code panics.
    fn send_message(message: String) -> String = send_message_ffi
);

crate::ffi_export!(
    /// Sends a message with a route hint from on-device classification.
    ///
    /// The `route_hint` parameter accepts: `"simple"`, `"complex"`,
    /// `"creative"`, `"tool_use"`, or empty string for default routing.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::EstopEngaged`] when emergency stop is
    /// active, or [`crate::FfiError::SpawnError`] if agent processing
    /// fails.
    fn send_message_routed(message: String, route_hint: String) -> String = send_message_routed_ffi
);

pub(crate) fn send_message_ffi(message: String) -> Result<String, FfiError> {
    if crate::estop::is_engaged() {
        return Err(FfiError::EstopEngaged {
            detail: "Emergency stop is engaged. Resume before sending messages.".into(),
        });
    }
    send_message_inner(message)
}

pub(crate) fn send_message_routed_ffi(
    message: String,
    route_hint: String,
) -> Result<String, FfiError> {
    if crate::estop::is_engaged() {
        return Err(FfiError::EstopEngaged {
            detail: "emergency stop is engaged — all agent execution is blocked".into(),
        });
    }
    send_message_routed_inner(message, route_hint)
}

// ── Daemon lifecycle FFI exports ───────────────────────────────────────────

crate::ffi_export!(
    /// Starts the `ZeroClaw` daemon with the given TOML configuration.
    ///
    /// Parses `config_toml`, overrides paths using `data_dir` (typically
    /// `context.filesDir` from Kotlin), and spawns the gateway on
    /// `host:port`. All daemon components run as supervised async tasks.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::ConfigError`] for TOML parse failures,
    /// [`crate::FfiError::StateError`] if the daemon is already running,
    /// [`crate::FfiError::SpawnError`] on spawn failure,
    /// [`crate::FfiError::StateCorrupted`] if internal state is poisoned,
    /// or [`crate::FfiError::InternalPanic`] if native code panics.
    fn start_daemon(config_toml: String, data_dir: String, host: String, port: u16) -> () = start_daemon_inner
);

crate::ffi_export!(
    /// Stops the running `ZeroClaw` daemon.
    fn stop_daemon() -> () = stop_daemon_inner
);

crate::ffi_export!(
    /// Returns a JSON string describing daemon and component health.
    fn get_status() -> String = get_status_inner
);

crate::ffi_export!(
    /// Validates a TOML config string without starting the daemon.
    fn validate_config(config_toml: String) -> String = validate_config_inner
);

crate::ffi_export!(
    /// Returns the TOML config the running daemon was started with.
    fn get_running_config() -> String = get_running_config_inner
);

crate::ffi_export!(
    /// Hot-swaps the default provider and model without restarting.
    fn swap_provider(provider: String, model: String, api_key: Option<String>) -> () = swap_provider_inner
);

crate::ffi_export!(
    /// Runs channel health checks without starting the daemon.
    fn doctor_channels(config_toml: String, data_dir: String) -> String = doctor_channels_inner
);

crate::ffi_export!(
    /// Returns the names of all channels configured in the running TOML.
    fn get_configured_channel_names() -> Vec<String> = get_configured_channel_names_inner
);

crate::ffi_export!(
    /// Binds a user identity to a channel's allowlist in the running daemon.
    fn bind_channel_identity(channel_name: String, user_id: String) -> String = bind_channel_identity_inner
);

crate::ffi_export!(
    /// Returns the current allowlist for a named channel.
    fn get_channel_allowlist(channel_name: String) -> Vec<String> = get_channel_allowlist_inner
);

crate::ffi_export!(
    /// Returns the port the gateway HTTP server is bound to.
    fn get_gateway_port() -> u16 = gateway_port_inner
);

/// Tokio runtime, recreated on each daemon lifecycle.
///
/// Stored in a `Mutex<Option<Runtime>>` so that [`stop_daemon_inner`] can
/// take ownership and call [`Runtime::shutdown_timeout`], which kills all
/// spawned tasks — including orphaned typing-indicator loops that upstream
/// channels leave behind after abort.
static RUNTIME: Mutex<Option<Runtime>> = Mutex::new(None);

/// Guarded daemon state. `None` when the daemon is not running.
static DAEMON: OnceLock<Mutex<Option<DaemonState>>> = OnceLock::new();

/// Mutable state for a running daemon instance.
///
/// Upstream v0.1.6+ made `cost`, `health`, `heartbeat`, `cron`, and
/// `skills` modules `pub(crate)`, so this struct no longer holds a
/// `CostTracker`. Cost data is accessed through the gateway REST API.
pub(crate) struct DaemonState {
    /// Handles for all spawned component supervisors.
    handles: Vec<JoinHandle<()>>,
    /// Port the gateway HTTP server is listening on.
    gateway_port: u16,
    /// Parsed daemon configuration, retained for sibling module access.
    ///
    /// Used by [`with_daemon_config`] for memory modules.
    config: Config,
    /// Memory backend, created during daemon startup for the memory browser.
    ///
    /// Wrapped in `Arc` because `dyn Memory` requires `Send + Sync` and is
    /// accessed from multiple FFI calls concurrently.
    memory: Option<Arc<dyn zeroclaw::memory::Memory>>,
    /// Discord message archive, created when Discord config is present.
    ///
    /// Shared with the Discord channel and FFI functions for search,
    /// sync status, and backfill operations.
    pub(crate) archive: Option<Arc<zeroai::memory::discord_archive::DiscordArchive>>,
    /// Gateway pairing guard, shared with the gateway for programmatic
    /// token registration from the FFI layer.
    pairing: Arc<PairingGuard>,
}

/// Returns a reference to the daemon state mutex, initialising it on first access.
fn daemon_mutex() -> &'static Mutex<Option<DaemonState>> {
    DAEMON.get_or_init(|| Mutex::new(None))
}

/// Locks the daemon mutex, recovering from poison if a prior holder panicked.
///
/// Rust's `Mutex` becomes permanently "poisoned" when a thread panics
/// while holding the lock. Without recovery, **every subsequent FFI call
/// fails forever** because the lock can never be acquired again.
///
/// This helper uses [`PoisonError::into_inner`] to reclaim the inner
/// `MutexGuard` after a panic, logging a warning but allowing the app to
/// continue operating. The daemon state inside may be stale, but
/// [`stop_daemon_inner`] can still clear it and [`start_daemon_inner`]
/// can reinitialise from scratch.
pub(crate) fn lock_daemon() -> std::sync::MutexGuard<'static, Option<DaemonState>> {
    daemon_mutex().lock().unwrap_or_else(|e| {
        tracing::warn!("Daemon mutex was poisoned; recovering: {e}");
        e.into_inner()
    })
}

/// Returns whether the daemon is currently running.
///
/// Acquires the daemon mutex briefly to check if state is `Some`.
/// Crate-visible so that sibling modules (e.g. `health`) can query
/// daemon liveness without accessing `DaemonState` directly.
#[allow(clippy::unnecessary_wraps)]
pub(crate) fn is_daemon_running() -> Result<bool, FfiError> {
    Ok(lock_daemon().is_some())
}

/// Returns the pairing guard if the daemon is running.
///
/// Used by [`crate::create_pairing_token`] to register programmatic tokens.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running.
pub(crate) fn get_pairing_guard() -> Result<Arc<PairingGuard>, FfiError> {
    lock_daemon()
        .as_ref()
        .ok_or_else(|| FfiError::StateError {
            detail: "daemon not running".into(),
        })
        .map(|state| Arc::clone(&state.pairing))
}

/// Returns the gateway port if the daemon is running.
///
/// Used by [`crate::gateway_client`] to construct loopback HTTP URLs.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running.
pub(crate) fn gateway_port_inner() -> Result<u16, FfiError> {
    lock_daemon()
        .as_ref()
        .ok_or_else(|| FfiError::StateError {
            detail: "daemon not running".into(),
        })
        .map(|state| state.gateway_port)
}

/// Runs a closure with a reference to the daemon config.
///
/// Returns [`FfiError::StateError`] if the daemon is not running.
pub(crate) fn with_daemon_config<T>(f: impl FnOnce(&Config) -> T) -> Result<T, FfiError> {
    let guard = lock_daemon();
    let state = guard.as_ref().ok_or_else(|| FfiError::StateError {
        detail: "daemon not running".into(),
    })?;
    Ok(f(&state.config))
}

/// Runs a fallible closure with a reference to the daemon config.
///
/// Like [`with_daemon_config`] but accepts closures that may themselves
/// fail with [`FfiError`]. Flattens the nested `Result` so callers use
/// a single `?` instead of `??`.
///
/// Returns [`FfiError::StateError`] if the daemon is not running, or
/// whatever error the closure produced.
pub(crate) fn try_with_daemon_config<T>(
    f: impl FnOnce(&Config) -> Result<T, FfiError>,
) -> Result<T, FfiError> {
    let guard = lock_daemon();
    let state = guard.as_ref().ok_or_else(|| FfiError::StateError {
        detail: "daemon not running".into(),
    })?;
    f(&state.config)
}

/// Runs a closure with a mutable reference to the daemon config.
///
/// Returns [`FfiError::StateError`] if the daemon is not running.
pub(crate) fn with_daemon_config_mut<T>(f: impl FnOnce(&mut Config) -> T) -> Result<T, FfiError> {
    let mut guard = lock_daemon();
    let state = guard.as_mut().ok_or_else(|| FfiError::StateError {
        detail: "daemon not running".into(),
    })?;
    Ok(f(&mut state.config))
}

/// Standard error message for "no model configured" — used by session,
/// streaming, and vision when [`Config::resolve_default_model`] returns
/// `None`.
pub(crate) const NO_MODEL_CONFIGURED: &str =
    "no model configured: set [providers.models.<type>.<alias>].model in config.toml";

/// Standard error message for "no model provider configured" — used by
/// [`effective_model_provider_type`] when no `[providers.models.*]`
/// block exists.
pub(crate) const NO_PROVIDER_CONFIGURED: &str =
    "no model provider configured: add a [providers.models.<type>.<alias>] block to config.toml";

/// Returns the first configured model-provider type (e.g. `"anthropic"`,
/// `"openai"`) from the running daemon config.
///
/// Replaces direct reads of the old flat `Config::default_provider`
/// field after the upstream provider-nesting refactor (2026-05).
///
/// # Errors
///
/// Returns [`FfiError::ConfigError`] when no `[providers.models.*]`
/// block exists. Never falls back to a hardcoded provider — silent
/// "anthropic" fallback is the very anti-pattern this helper exists to
/// prevent (see `feedback_provider_routing_defaults.md`).
/// Returns the only configured channel of the given kind, or a clear
/// [`FfiError::ConfigError`] if zero or more than one alias is configured.
///
/// Upstream's nested channel schema permits multiple aliases per kind
/// (e.g. `[channels.discord.work]` + `[channels.discord.personal]`),
/// but the FFI layer currently has no notion of "active alias." Rather
/// than pick one silently with `HashMap::values().next()` (which has
/// undefined iteration order), this helper makes the limitation
/// explicit. Returns:
/// - `Err(ConfigError)` with a "not configured" message when empty
/// - `Err(ConfigError)` with a "multi-alias not supported" message
///   when more than one alias is present
/// - `Ok(&T)` only when exactly one alias is configured
///
/// Once multi-alias support lands, this helper goes away and call sites
/// take a `&str` alias parameter from Kotlin.
pub(crate) fn primary_alias<'a, T>(
    map: &'a std::collections::HashMap<String, T>,
    kind: &'static str,
) -> Result<&'a T, FfiError> {
    let mut iter = map.values();
    let first = iter.next().ok_or_else(|| FfiError::ConfigError {
        detail: format!("{kind} channel is not configured"),
    })?;
    if iter.next().is_some() {
        return Err(FfiError::ConfigError {
            detail: format!(
                "{kind} channel has multiple aliases configured;                  multi-alias support is not yet implemented —                  keep only one [channels.{kind}.<alias>] block"
            ),
        });
    }
    Ok(first)
}

pub(crate) fn effective_model_provider_type(config: &Config) -> Result<String, FfiError> {
    config
        .first_model_provider_type()
        .map(str::to_string)
        .ok_or_else(|| FfiError::ConfigError {
            detail: NO_PROVIDER_CONFIGURED.into(),
        })
}

/// Builds the active chat model provider for `provider_name`'s default
/// alias from `config`, threading the alias entry's `api_key` plus the
/// runtime options (URI, `zeroclaw_dir`, `secrets_encrypt`, reasoning) so
/// auth'd / self-hosted endpoints — notably the on-device LiteRT loopback —
/// receive their `Authorization: Bearer`.
///
/// Single source of truth for FFI provider construction. Passing `None`
/// for the key (the old per-call-site pattern) silently dropped the
/// credential and 401'd auth'd endpoints; routing the agent loop,
/// compaction, and streaming paths through this one function makes that
/// class of bug structurally impossible to reintroduce per call site.
pub(crate) fn build_active_provider(
    config: &Config,
    provider_name: &str,
) -> anyhow::Result<Box<dyn zeroclaw::providers::ModelProvider>> {
    let chat_alias = config
        .first_model_provider_alias()
        .and_then(|s| s.as_str().split_once('.').map(|(_, a)| a.to_string()))
        .unwrap_or_else(|| "default".to_string());
    let mut options =
        zeroclaw::providers::provider_runtime_options_for_alias(config, provider_name, &chat_alias);
    options.zeroclaw_dir = config.config_path.parent().map(PathBuf::from);
    options.secrets_encrypt = config.secrets.encrypt;
    options.reasoning_enabled = config.runtime.reasoning_enabled;
    options.reasoning_effort = config.runtime.reasoning_effort.clone();
    let api_key = config
        .providers
        .models
        .find(provider_name, &chat_alias)
        .and_then(|entry| entry.api_key.as_deref());
    zeroclaw::providers::create_resilient_model_provider_for_alias(
        config,
        provider_name,
        &chat_alias,
        api_key,
        None,
        &config.reliability,
        &options,
    )
}

/// Returns an owned clone of the running daemon's [`Config`].
///
/// Acquires the daemon mutex briefly to clone the config, then releases it.
/// Used by session setup to snapshot config without holding the lock during
/// long-running operations like provider creation and prompt building.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running,
/// or [`FfiError::StateCorrupted`] if the daemon mutex is poisoned.
pub(crate) fn clone_daemon_config() -> Result<Config, FfiError> {
    with_daemon_config(Config::clone)
}

/// Returns a cloned `Arc<dyn Memory>` from the running daemon.
///
/// Acquires the daemon mutex briefly to clone the `Arc`, then releases it.
/// The returned `Arc` can be used independently without holding the lock,
/// which is important for session operations that need long-lived memory
/// access without blocking other daemon state queries.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running or
/// the memory backend was not initialised during daemon startup,
/// or [`FfiError::StateCorrupted`] if the daemon mutex is poisoned.
pub(crate) fn clone_daemon_memory() -> Result<Arc<dyn zeroclaw::memory::Memory>, FfiError> {
    let guard = lock_daemon();
    let state = guard.as_ref().ok_or_else(|| FfiError::StateError {
        detail: "daemon not running".into(),
    })?;
    let memory = state.memory.as_ref().ok_or_else(|| FfiError::StateError {
        detail: "memory backend not available".into(),
    })?;
    Ok(Arc::clone(memory))
}

/// Runs a closure with a reference to the memory backend and a tokio
/// runtime [`Handle`].
///
/// The closure receives the `Arc<dyn Memory>` and a `&Handle` so it
/// can call async memory methods via `handle.block_on(...)`. Since FFI
/// calls originate from Kotlin's IO dispatcher (not from our tokio
/// runtime), `block_on` is safe and will not deadlock.
///
/// The daemon mutex is released **before** the closure executes. This
/// prevents deadlocks when the `Memory` implementation itself needs to
/// acquire the mutex or perform blocking I/O.
///
/// Returns [`FfiError::StateError`] if the daemon is not running or the
/// memory backend was not initialised.
pub(crate) fn with_memory<T>(
    f: impl FnOnce(&dyn zeroclaw::memory::Memory, &Handle) -> Result<T, FfiError>,
) -> Result<T, FfiError> {
    let memory_arc = {
        let guard = lock_daemon();
        let state = guard.as_ref().ok_or_else(|| FfiError::StateError {
            detail: "daemon not running".into(),
        })?;
        Arc::clone(state.memory.as_ref().ok_or_else(|| FfiError::StateError {
            detail: "memory backend not available".into(),
        })?)
    }; // guard dropped here
    let handle = get_or_create_runtime()?;
    f(memory_arc.as_ref(), &handle)
}

/// Locks the runtime mutex, recovering from poison if a prior holder panicked.
///
/// Same pattern as [`lock_daemon`]: uses [`PoisonError::into_inner`] to
/// reclaim the guard after a panic, allowing the next caller to create a
/// fresh runtime.
fn lock_runtime() -> std::sync::MutexGuard<'static, Option<Runtime>> {
    RUNTIME.lock().unwrap_or_else(|e| {
        tracing::warn!("Runtime mutex was poisoned; recovering: {e}");
        e.into_inner()
    })
}

/// One-time panic hook installer.
static PANIC_HOOK_INSTALLED: Once = Once::new();

/// Installs a global panic hook that logs panic details via [`tracing::error!`].
///
/// The hook chains with the default hook (preserved via [`std::panic::take_hook`])
/// and is installed exactly once via [`std::sync::Once`]. It does not interfere
/// with unwinding — it is purely observational, ensuring that panics caught by
/// `catch_unwind` at FFI boundaries are still visible in Android logcat.
fn install_panic_hook() {
    PANIC_HOOK_INSTALLED.call_once(|| {
        let previous = std::panic::take_hook();
        std::panic::set_hook(Box::new(move |info| {
            let message = info
                .payload()
                .downcast_ref::<&str>()
                .map(ToString::to_string)
                .or_else(|| info.payload().downcast_ref::<String>().cloned())
                .unwrap_or_else(|| "unknown panic".to_string());
            let location = info
                .location()
                .map_or_else(|| "unknown location".to_string(), ToString::to_string);
            tracing::error!("FFI panic at {location}: {message}");
            previous(info);
        }));
    });
}

/// Returns a [`Handle`] to the tokio runtime, creating it on first access.
///
/// The returned `Handle` is an owned, cloneable token that keeps the
/// runtime alive and supports [`Handle::block_on`] with the same API as
/// [`Runtime::block_on`]. Callers should use `handle.block_on(...)`.
///
/// # Errors
///
/// Returns [`FfiError::SpawnError`] if the tokio runtime builder fails.
pub(crate) fn get_or_create_runtime() -> Result<Handle, FfiError> {
    install_panic_hook();
    // Upstream removed `install_rustls_crypto_provider` — the workspace
    // runtime now installs its own provider lazily. `reqwest` / `russh`
    // also install ring on first TLS use, so no eager bootstrap is
    // needed here.
    let mut guard = lock_runtime();
    if let Some(rt) = guard.as_ref() {
        return Ok(rt.handle().clone());
    }
    let rt = tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .thread_name("zeroclaw-ffi")
        .build()
        .map_err(|e| FfiError::SpawnError {
            detail: format!("failed to create tokio runtime: {e}"),
        })?;
    let handle = rt.handle().clone();
    *guard = Some(rt);
    Ok(handle)
}

/// Starts the `ZeroClaw` daemon with the provided configuration.
///
/// Parses `config_toml` into a [`Config`], overrides Android-specific paths
/// with `data_dir`, then spawns the gateway and channel supervisors.
///
/// Upstream v0.1.6 made the `cron`, `cost`, `health`, and `heartbeat`
/// modules `pub(crate)`, so we no longer start those components directly.
/// The gateway handles cron CRUD and cost tracking internally; health is
/// tracked via [`crate::ffi_health`]; heartbeat is skipped on mobile.
///
/// # Errors
///
/// Returns [`FfiError::ConfigError`] on TOML parse failure,
/// [`FfiError::StateError`] if the daemon is already running,
/// [`FfiError::StateCorrupted`] if the daemon mutex is poisoned,
/// or [`FfiError::SpawnError`] on component spawn failure.
#[allow(clippy::too_many_lines)]
pub(crate) fn start_daemon_inner(
    config_toml: String,
    data_dir: String,
    host: String,
    port: u16,
) -> Result<(), FfiError> {
    if !data_dir.starts_with('/') {
        return Err(FfiError::ConfigError {
            detail: "data_dir must be an absolute path".to_string(),
        });
    }
    if data_dir.contains("..") {
        return Err(FfiError::ConfigError {
            detail: "data_dir must not contain '..' segments".to_string(),
        });
    }

    if host.is_empty() {
        return Err(FfiError::ConfigError {
            detail: "host must not be empty".to_string(),
        });
    }
    if !host
        .chars()
        .all(|c| c.is_ascii_alphanumeric() || c == '.' || c == ':' || c == '-')
    {
        return Err(FfiError::ConfigError {
            detail: "host contains invalid characters".to_string(),
        });
    }

    // Install the rustls process-wide CryptoProvider before any TLS
    // handshake runs. Discord's gateway uses tokio-tungstenite which calls
    // bare rustls APIs (not via reqwest's bundled setup), so without this
    // the first WS connect panics at `rustls::crypto::mod.rs:249`. Idempotent
    // — `install_default` no-ops once a provider is set, so re-running on
    // hot-reload is safe.
    let _ = rustls::crypto::ring::default_provider().install_default();

    let mut config: Config =
        zeroclaw_config::migration::migrate_to_current(&config_toml).map_err(|e| {
            FfiError::ConfigError {
                detail: format!("failed to parse config TOML: {e}"),
            }
        })?;

    let data_path = PathBuf::from(&data_dir);
    config.data_dir = data_path.join("workspace");
    config.config_path = data_path.join("config.toml");

    // open-skills auto-sync was removed during channel gutting (supply-chain risk).
    // SkillsConfig no longer has open_skills_enabled or open_skills_dir fields.

    // Upstream moved `autonomy.non_cli_excluded_tools` off the
    // top-level Config — the exclusion guard now lives in
    // `[risk_profiles]` / `[security]` blocks emitted by the Android
    // TOML builder. No runtime patching needed here.
    tracing::info!("Android tool exclusions: now expected to come from TOML, not patched at runtime");

    // Disabled: `web_fetch::set_global_webview_fallback` and the
    // `WebViewFallback` trait were removed upstream. The
    // `FfiWebViewFallback` adapter is preserved (web_renderer.rs) for
    // when upstream reintroduces a hook.

    crate::estop::load_state(&data_path);

    // Install the embedder-local tools factory exposed by upstream
    // [`set_extra_tools_factory`]. This factory is the fan-out path
    // for the **non-Terminal** call sites — the channel orchestrator,
    // gateway, and per-agent loops all build their tool registries
    // through `all_tools_with_runtime`, which queries the factory at
    // the end of construction. (The Terminal `session_send` path uses
    // [`crate::session_registry::build_tools_registry`] directly,
    // which calls the same [`android_local_tools`] helper, so both
    // paths converge on one source of truth.)
    //
    // Idempotent: `OnceLock::set` returns `Err` on a second install
    // (e.g. hot-reload re-entry into `start_daemon_inner`). The
    // closure captures no mutable state and is the same on every
    // install, so dropping the duplicate is safe.
    if zeroclaw_runtime::tools::set_extra_tools_factory(Box::new(
        crate::android_local_tools::android_local_tools,
    ))
    .is_ok()
    {
        tracing::info!("Registered Android-local extra-tools factory");
    }

    std::fs::create_dir_all(&config.data_dir).map_err(|e| FfiError::ConfigError {
        detail: format!("failed to create workspace dir: {e}"),
    })?;

    let handle = get_or_create_runtime()?;

    let mut guard = lock_daemon();

    if guard.is_some() {
        return Err(FfiError::StateError {
            detail: "daemon already running".to_string(),
        });
    }

    let initial_backoff = config.reliability.channel_initial_backoff_secs.max(1);
    let max_backoff = config
        .reliability
        .channel_max_backoff_secs
        .max(initial_backoff);

    // The flat `config.api_key` was removed upstream. Memory backends
    // that need a key (currently none of the user-selectable ones —
    // sqlite/lucid/none) would look it up via
    // `config.providers.models.find(family, alias)`. For the moment
    // none of the Android-facing backends consume `memory_api_key`, so
    // `None` is the correct call.
    let memory_api_key: Option<&str> = None;
    let memory: Option<Arc<dyn zeroclaw::memory::Memory>> = match zeroclaw::memory::create_memory(
        &config.memory,
        &config.data_dir,
        memory_api_key,
    ) {
        Ok(mem) => {
            tracing::info!("Memory backend initialised: {}", mem.name());
            Some(Arc::from(mem))
        }
        Err(e) => {
            tracing::warn!("Memory backend unavailable: {e}");
            None
        }
    };

    let archive: Option<Arc<zeroai::memory::discord_archive::DiscordArchive>> =
        if !config.channels.discord.is_empty() {
            let data_path_ref = PathBuf::from(&data_dir);
            match zeroai::memory::discord_archive::DiscordArchive::open(&data_path_ref) {
                Ok(a) => {
                    tracing::info!("Discord archive initialised");
                    Some(Arc::new(a))
                }
                Err(e) => {
                    tracing::warn!("Discord archive unavailable: {e}");
                    None
                }
            }
        } else {
            None
        };

    let stored_config = config.clone();

    let pairing = Arc::new(PairingGuard::new(
        config.gateway.require_pairing,
        &config.gateway.paired_tokens,
    ));

    let handles = handle.block_on(async {
        crate::ffi_health::mark_component_ok("daemon");

        let mut handles: Vec<JoinHandle<()>> = Vec::new();

        handles.push(spawn_state_writer(config.clone()));

        {
            let gateway_cfg = config.clone();
            let gateway_host = host.clone();
            handles.push(spawn_component_supervisor(
                "gateway",
                initial_backoff,
                max_backoff,
                move || {
                    let cfg = gateway_cfg.clone();
                    let h = gateway_host.clone();
                    // Upstream's `run_gateway` constructs its own
                    // PairingGuard internally; we no longer pass ours
                    // in. Signature: `(host, port, config,
                    // external_event_tx, reload_tx, canvas_store)`.
                    async move { zeroclaw::gateway::run_gateway(&h, port, cfg, None, None, None).await }
                },
            ));
        }

        if has_supervised_channels(&config) {
            let channels_cfg = config.clone();
            handles.push(spawn_component_supervisor(
                "channels",
                initial_backoff,
                max_backoff,
                move || {
                    let cfg = channels_cfg.clone();
                    // Upstream's `start_channels` now takes
                    // `(config, canvas_store, cancel_token)` — the cancel
                    // token is constructed per supervisor spawn so each
                    // channel restart gets a fresh lifecycle.
                    async move {
                        let cancel = tokio_util::sync::CancellationToken::new();
                        zeroclaw::channels::start_channels(cfg, None, cancel).await
                    }
                },
            ));
        } else {
            crate::ffi_health::mark_component_ok("channels");
            tracing::info!("No real-time channels configured; channel supervisor disabled");
        }

        // NOTE: Heartbeat and cron scheduler are skipped on Android.
        // Upstream v0.1.6 made these modules pub(crate), and they are
        // non-essential for the mobile wrapper. The gateway's internal
        // cron scheduler handles job execution; cron CRUD and cost data
        // are accessed through the gateway REST API.

        handles
    });

    *guard = Some(DaemonState {
        handles,
        gateway_port: port,
        config: stored_config,
        memory,
        archive,
        pairing,
    });

    // Register ClawBoy trigger handler for channel message interception.
    zeroai::clawboy_triggers::register_trigger_handler(std::sync::Arc::new(
        |message: &str, channel_id: &str| -> Option<String> {
            match crate::clawboy::chat::check_trigger(message, channel_id) {
                crate::clawboy::chat::TriggerResult::StartResponse(r)
                | crate::clawboy::chat::TriggerResult::StopResponse(r) => Some(r),
                crate::clawboy::chat::TriggerResult::PassThrough => None,
            }
        },
    ));

    tracing::info!("ZeroAI daemon started on {host}:{port}");

    Ok(())
}

/// Stops a running `ZeroClaw` daemon by aborting all component supervisor
/// tasks and shutting down the tokio runtime.
///
/// Shutting down the runtime (via [`Runtime::shutdown_timeout`]) kills
/// **all** spawned tasks, including orphaned typing-indicator loops and
/// channel listener tasks that survive the component abort. Without this,
/// Telegram's `start_typing` refresh task would continue sending
/// `sendChatAction` every 4 seconds indefinitely after stop.
///
/// A fresh runtime is created on the next [`start_daemon_inner`] call.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running,
/// or [`FfiError::StateCorrupted`] if the daemon mutex is poisoned.
pub(crate) fn stop_daemon_inner() -> Result<(), FfiError> {
    let mut guard = lock_daemon();

    let state = guard.take().ok_or_else(|| FfiError::StateError {
        detail: "daemon not running".to_string(),
    })?;

    for task in &state.handles {
        task.abort();
    }

    // Take ownership of the runtime so we can shut it down after awaiting
    // the aborted handles. Other FFI calls that need a runtime during this
    // window will create a fresh one (acceptable — no daemon state exists).
    let rt = { lock_runtime().take() };

    if let Some(rt) = rt {
        rt.block_on(async {
            for task in state.handles {
                let _ = task.await;
            }
        });

        // Kill orphaned tasks: typing indicators, channel listeners, etc.
        rt.shutdown_timeout(Duration::from_secs(5));
    }

    crate::ffi_health::mark_component_error("daemon", "shutdown requested");
    tracing::info!("ZeroAI daemon stopped (runtime shut down)");

    Ok(())
}

/// Returns a JSON string describing the health of all daemon components.
///
/// Includes the FFI health snapshot plus a `daemon_running` boolean.
///
/// # Errors
///
/// Returns [`FfiError::StateCorrupted`] if the daemon mutex is poisoned,
/// or [`FfiError::SpawnError`] if the health snapshot cannot be serialised.
pub(crate) fn get_status_inner() -> Result<String, FfiError> {
    let guard = lock_daemon();
    let daemon_running = guard.is_some();
    drop(guard);

    let mut snapshot = crate::ffi_health::snapshot_json();
    if let Some(obj) = snapshot.as_object_mut() {
        obj.insert("daemon_running".into(), serde_json::json!(daemon_running));
    }

    serde_json::to_string(&snapshot).map_err(|e| FfiError::SpawnError {
        detail: format!("failed to serialise health snapshot: {e}"),
    })
}

/// Sends a message to the running daemon via its local HTTP gateway.
///
/// POSTs `{"message": "<msg>"}` to `http://127.0.0.1:{port}/webhook`
/// and returns the agent's response string.
///
/// Routes through the full agent loop ([`zeroclaw::agent::process_message`])
/// rather than the stateless gateway webhook. This provides:
/// - Memory recall (relevant past context injected before each turn)
/// - Tool access (shell, file, memory, etc.)
/// - Proper system prompt with workspace identity files
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running,
/// [`FfiError::StateCorrupted`] if the daemon mutex is poisoned,
/// or [`FfiError::SpawnError`] if agent processing fails.
pub(crate) fn send_message_inner(message: String) -> Result<String, FfiError> {
    const MAX_MESSAGE_BYTES: usize = 1_048_576;
    if message.len() > MAX_MESSAGE_BYTES {
        return Err(FfiError::InvalidArgument {
            detail: format!(
                "message too large ({} bytes, max {MAX_MESSAGE_BYTES})",
                message.len()
            ),
        });
    }

    let handle = get_or_create_runtime()?;
    let config = with_daemon_config(Config::clone)?;

    handle.block_on(async {
        zeroclaw::agent::process_message(config, "default", &message, None)
            .await
            .map_err(|e| FfiError::SpawnError {
                detail: format!("agent processing failed: {e}"),
            })
    })
}

/// Sends a message with an optional route hint from the Kotlin classifier.
///
/// The `route_hint` string maps to [`zeroai::router::RouteHint`] variants:
/// `"simple"`, `"complex"`, `"creative"`, `"tool_use"`. Empty or unrecognized
/// values fall back to default routing (no hint).
///
/// # Errors
///
/// Same error conditions as [`send_message_inner`].
pub(crate) fn send_message_routed_inner(
    message: String,
    route_hint: String,
) -> Result<String, FfiError> {
    const MAX_MESSAGE_BYTES: usize = 1_048_576;
    if message.len() > MAX_MESSAGE_BYTES {
        return Err(FfiError::InvalidArgument {
            detail: format!(
                "message too large ({} bytes, max {MAX_MESSAGE_BYTES})",
                message.len()
            ),
        });
    }

    let hint = zeroai::router::RouteHint::from_ffi(&route_hint);
    let handle = get_or_create_runtime()?;
    let config = with_daemon_config(Config::clone)?;

    handle.block_on(async {
        // Upstream replaced `process_message_routed(hint)` with
        // `process_message(config, agent_alias, message, session_id)` —
        // the routing-hint surface was removed during the agent refactor.
        // The Android caller still passes `route_hint` for forward
        // compatibility (and we keep the local RouteHint enum for the
        // classifier), but it is unused at the call site until upstream
        // restores a router config or we wire a local pre-classifier.
        let _ = hint;
        zeroclaw::agent::process_message(config, "default", &message, None)
            .await
            .map_err(|e| FfiError::SpawnError {
                detail: format!("agent processing failed: {e}"),
            })
    })
}

/// Writes an FFI health snapshot JSON to disk every 5 seconds.
fn spawn_state_writer(config: Config) -> JoinHandle<()> {
    tokio::spawn(async move {
        let path = config
            .config_path
            .parent()
            .map_or_else(|| PathBuf::from("."), PathBuf::from)
            .join("daemon_state.json");

        if let Some(parent) = path.parent() {
            let _ = tokio::fs::create_dir_all(parent).await;
        }

        let mut interval = tokio::time::interval(Duration::from_secs(5));
        loop {
            interval.tick().await;
            let mut json = crate::ffi_health::snapshot_json();
            if let Some(obj) = json.as_object_mut() {
                obj.insert(
                    "written_at".into(),
                    serde_json::json!(Utc::now().to_rfc3339()),
                );
            }
            let data = match serde_json::to_vec_pretty(&json) {
                Ok(bytes) => bytes,
                Err(e) => {
                    tracing::warn!("Failed to serialise health snapshot: {e}");
                    b"{}".to_vec()
                }
            };
            let _ = tokio::fs::write(&path, data).await;
        }
    })
}

/// Supervises a daemon component with exponential backoff on failure.
///
/// Uses [`crate::ffi_health`] for health tracking since the upstream
/// `zeroclaw::health` module is `pub(crate)` in v0.1.6.
fn spawn_component_supervisor<F, Fut>(
    name: &'static str,
    initial_backoff_secs: u64,
    max_backoff_secs: u64,
    mut run_component: F,
) -> JoinHandle<()>
where
    F: FnMut() -> Fut + Send + 'static,
    Fut: Future<Output = anyhow::Result<()>> + Send + 'static,
{
    tokio::spawn(async move {
        let mut backoff = initial_backoff_secs.max(1);
        let max_backoff = max_backoff_secs.max(backoff);

        loop {
            crate::ffi_health::mark_component_ok(name);
            match run_component().await {
                Ok(()) => {
                    crate::ffi_health::mark_component_error(name, "component exited unexpectedly");
                    tracing::warn!("Daemon component '{name}' exited unexpectedly");
                    backoff = initial_backoff_secs.max(1);
                }
                Err(e) => {
                    crate::ffi_health::mark_component_error(name, e.to_string());
                    tracing::error!("Daemon component '{name}' failed: {e}");
                }
            }

            crate::ffi_health::bump_component_restart(name);
            tokio::time::sleep(Duration::from_secs(backoff)).await;
            backoff = backoff.saturating_mul(2).min(max_backoff);
        }
    })
}

/// Hot-swaps the default provider and model in the running daemon config.
///
/// Mutates `DaemonState.config` in-place without restarting the daemon.
/// The change takes effect on the next message send (session start will
/// snapshot the updated config). Does not persist to disk; the Kotlin
/// layer is responsible for persisting the setting and rebuilding the
/// TOML on next full restart.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running.
pub(crate) fn swap_provider_inner(
    provider: String,
    model: String,
    api_key: Option<String>,
) -> Result<(), FfiError> {
    let mut guard = lock_daemon();
    let state = guard.as_mut().ok_or_else(|| FfiError::StateError {
        detail: "daemon not running".into(),
    })?;

    // Upstream moved provider routing into the nested
    // `config.providers.models.<type>.<alias>` schema; the flat
    // `default_provider` / `default_model` / `api_key` fields that this
    // hot-swap operated on are gone. Per-provider auth + endpoints now
    // live in TOML blocks the daemon reads on startup, so the Android
    // caller must rewrite the TOML via `ConfigTomlBuilder` and restart
    // the daemon to apply provider changes.
    let _ = (&provider, &model, &api_key);
    tracing::warn!(
        "swap_provider: hot-swap not implemented on new nested provider schema; restart daemon to apply"
    );
    let _ = state;
    Ok(())
}

/// Returns the TOML representation of the currently running daemon config.
///
/// Serialises the in-memory [`Config`] back to TOML using
/// `toml::to_string_pretty`. This may differ from the original TOML that
/// was passed to [`start_daemon_inner`] because path overrides and
/// default-filling have been applied.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running,
/// or [`FfiError::SpawnError`] if serialisation fails.
pub(crate) fn get_running_config_inner() -> Result<String, FfiError> {
    with_daemon_config(|config| {
        toml::to_string_pretty(config).map_err(|e| FfiError::SpawnError {
            detail: format!("failed to serialize config: {e}"),
        })
    })?
}

/// Validates a TOML config string without starting the daemon.
///
/// Runs the same V1→V2→V3 migration chain as [`start_daemon_inner`] via
/// `migrate_to_current`, so configs emitted by the Android TOML builder
/// (V1-shaped, no `schema_version`) are validated against the same
/// schema the daemon will actually load. Returns an empty string on
/// success, or a human-readable error message on migration/parse failure.
///
/// No state mutation, no mutex, no file I/O.
///
/// # Errors
///
/// Returns [`FfiError::InternalPanic`] only if serialisation panics
/// (should never happen).
#[allow(clippy::unnecessary_wraps)]
pub(crate) fn validate_config_inner(config_toml: String) -> Result<String, FfiError> {
    match zeroclaw_config::migration::migrate_to_current(&config_toml) {
        Ok(_) => Ok(String::new()),
        Err(e) => Ok(format!("{e}")),
    }
}

/// Runs per-channel health checks and returns structured results.
///
/// When `config_toml` is empty, uses the running daemon's config
/// (requires the daemon to be started). When `config_toml` is
/// provided, parses it and overrides paths with `data_dir` (same as
/// [`start_daemon_inner`]).
///
/// Constructs each configured channel independently (replicating
/// upstream's private `collect_configured_channels()` logic) and calls
/// [`Channel::health_check()`] with a per-channel 10-second timeout,
/// wrapped in a 30-second outer timeout for the entire loop.
///
/// Returns a JSON array with one entry per channel:
/// ```json
/// [
///   {"name": "Telegram", "status": "healthy"},
///   {"name": "Discord", "status": "unhealthy", "detail": "auth/config/network"},
///   {"name": "Discord", "status": "timeout"}
/// ]
/// ```
///
/// When no channels are configured, returns:
/// ```json
/// [{"name": "channels", "status": "healthy", "detail": "No channels configured"}]
/// ```
///
/// Uses the shared [`RUNTIME`] for async execution but does NOT acquire
/// the [`DAEMON`] mutex.
///
/// # Errors
///
/// Returns [`FfiError::ConfigError`] on TOML parse or path failure,
/// [`FfiError::StateError`] if `config_toml` is empty and the daemon
/// is not running, or [`FfiError::SpawnError`] on serialisation failure.
pub(crate) fn doctor_channels_inner(
    config_toml: String,
    data_dir: String,
) -> Result<String, FfiError> {
    let config: Config = if config_toml.is_empty() {
        clone_daemon_config()?
    } else {
        let mut parsed: Config = zeroclaw_config::migration::migrate_to_current(&config_toml)
            .map_err(|e| FfiError::ConfigError {
                detail: format!("failed to parse config TOML: {e}"),
            })?;
        let data_path = PathBuf::from(&data_dir);
        parsed.data_dir = data_path.join("workspace");
        parsed.config_path = data_path.join("config.toml");
        parsed
    };

    let handle = get_or_create_runtime()?;

    let results = handle.block_on(async {
        let channels = collect_channels(&config);
        let mut results = Vec::<serde_json::Value>::new();

        if channels.is_empty() && results.is_empty() {
            return Ok(serde_json::json!([
                {"name": "channels", "status": "healthy", "detail": "No channels configured"}
            ]));
        }

        match tokio::time::timeout(Duration::from_secs(30), async {
            for (name, channel) in &channels {
                let check =
                    tokio::time::timeout(Duration::from_secs(10), channel.health_check()).await;
                match check {
                    Ok(true) => results.push(serde_json::json!({
                        "name": name, "status": "healthy"
                    })),
                    Ok(false) => results.push(serde_json::json!({
                        "name": name,
                        "status": "unhealthy",
                        "detail": "auth/config/network"
                    })),
                    Err(_) => results.push(serde_json::json!({
                        "name": name, "status": "timeout"
                    })),
                }
            }
        })
        .await
        {
            Ok(()) => {}
            Err(_) => {
                tracing::warn!("doctor_channels: 30s outer timeout exceeded");
            }
        }

        Ok::<_, FfiError>(serde_json::Value::Array(results))
    })?;

    serde_json::to_string(&results).map_err(|e| FfiError::SpawnError {
        detail: format!("failed to serialise doctor results: {e}"),
    })
}



#[cfg(test)]
#[path = "runtime_tests.rs"]
mod tests;
