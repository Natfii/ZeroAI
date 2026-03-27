/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

#![deny(missing_docs)]

//! UniFFI-annotated facade for `ZeroClaw` Android bindings.
//!
//! This crate provides a thin FFI layer over the `ZeroClaw` daemon,
//! exposing daemon lifecycle, health, cost, events, cron, skills, tools,
//! and memory browsing functions to Kotlin via UniFFI-generated bindings.

uniffi::setup_scaffolding!();

mod agent_script_host;
mod auth_profiles;
mod capability_grants;
mod cost;
mod credentials;
mod cron;
mod discord;
mod error;
mod estop;
mod eval_script_tool;
mod events;
mod ffi_health;
mod gateway_client;
mod health;
mod memory_browse;
mod models;
mod repl;
mod runtime;
mod session;
mod session_persistence;
mod shared_folder;
mod skills;
mod streaming;
mod tools_browse;
mod traces;
mod twitter;
mod types;
mod url_helpers;
mod vision;
mod web_renderer;
mod workspace;

mod clawboy;
mod email_cron;
mod messages_bridge;
mod messages_bridge_page;
mod tailnet;
mod tty;

use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::Arc;

pub use error::FfiError;

/// Initialises the Rust tracing subscriber for Android logcat output.
///
/// On Android debug builds, routes `tracing` events (info, warn, error)
/// to `__android_log_write` with the tag `"zeroai_ffi"`. On release
/// builds or non-Android targets, this is a no-op.
///
/// Safe to call multiple times — the second and subsequent calls are
/// silently ignored by the subscriber registry.
#[uniffi::export]
pub fn init_logging() {
    let _ = std::panic::catch_unwind(|| {
        #[cfg(target_os = "android")]
        {
            use tracing_subscriber::EnvFilter;
            use tracing_subscriber::prelude::*;

            // Noisy HTTP/TLS crates → WARN only; everything else → DEBUG.
            let filter = if cfg!(debug_assertions) {
                EnvFilter::new(
                    "debug,hyper=warn,hyper_util=warn,reqwest=warn,rustls=warn,h2=warn,tower=warn",
                )
            } else {
                EnvFilter::new(
                    "info,hyper=warn,hyper_util=warn,reqwest=warn,rustls=warn,h2=warn,tower=warn",
                )
            };

            if let Ok(layer) = tracing_android::layer("zeroai_ffi") {
                let _ = tracing_subscriber::registry()
                    .with(layer.with_filter(filter))
                    .try_init();
                tracing::info!("Rust tracing initialised");
            }
        }
    });
}

/// Extracts a human-readable message from a caught panic payload.
pub(crate) fn panic_detail(payload: &Box<dyn std::any::Any + Send>) -> String {
    payload
        .downcast_ref::<&str>()
        .map(std::string::ToString::to_string)
        .or_else(|| payload.downcast_ref::<String>().cloned())
        .unwrap_or_else(|| "unknown panic".to_string())
}

/// Starts the `ZeroClaw` daemon with the given TOML configuration.
///
/// Parses `config_toml`, overrides paths using `data_dir` (typically
/// `context.filesDir` from Kotlin), and spawns the gateway on
/// `host:port`. All daemon components run as supervised async tasks.
///
/// # Errors
///
/// Returns [`FfiError::ConfigError`] for TOML parse failures,
/// [`FfiError::StateError`] if the daemon is already running,
/// [`FfiError::SpawnError`] on spawn failure,
/// [`FfiError::StateCorrupted`] if internal state is poisoned, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn start_daemon(
    config_toml: String,
    data_dir: String,
    host: String,
    port: u16,
) -> Result<(), FfiError> {
    catch_unwind(|| runtime::start_daemon_inner(config_toml, data_dir, host, port)).unwrap_or_else(
        |e| {
            Err(FfiError::InternalPanic {
                detail: panic_detail(&e),
            })
        },
    )
}

/// Stops the running `ZeroClaw` daemon.
///
/// Signals all component supervisors to shut down and waits for
/// their tasks to complete.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running,
/// [`FfiError::StateCorrupted`] if internal state is poisoned, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn stop_daemon() -> Result<(), FfiError> {
    catch_unwind(runtime::stop_daemon_inner).unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Returns a JSON string describing daemon and component health.
///
/// The JSON includes upstream health fields (`pid`, `uptime_seconds`,
/// `components`) plus a `daemon_running` boolean.
///
/// # Errors
///
/// Returns [`FfiError::SpawnError`] on serialisation failure,
/// [`FfiError::StateCorrupted`] if internal state is poisoned, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn get_status() -> Result<String, FfiError> {
    catch_unwind(runtime::get_status_inner).unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Returns structured health detail for all daemon components.
///
/// Unlike [`get_status`] which returns raw JSON, this function returns
/// typed component-level data including restart counts and last errors.
///
/// # Errors
///
/// Returns [`FfiError::StateCorrupted`] if internal state is poisoned, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn get_health_detail() -> Result<health::FfiHealthDetail, FfiError> {
    catch_unwind(health::get_health_detail_inner).unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Returns health for a single named component.
///
/// Returns `None` if no component with the given name exists.
///
/// # Errors
///
/// Returns [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn get_component_health(name: String) -> Result<Option<health::FfiComponentHealth>, FfiError> {
    catch_unwind(|| Ok(health::get_component_health_inner(name))).unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Sends a message through the full agent loop and returns the response.
///
/// Routes through [`zeroclaw::agent::process_message`] which provides
/// memory recall, tool access, and proper workspace identity injection.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running,
/// [`FfiError::SpawnError`] if agent processing fails,
/// [`FfiError::StateCorrupted`] if internal state is poisoned, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn send_message(message: String) -> Result<String, FfiError> {
    catch_unwind(|| {
        if estop::is_engaged() {
            return Err(FfiError::EstopEngaged {
                detail: "Emergency stop is engaged. Resume before sending messages.".into(),
            });
        }
        runtime::send_message_inner(message)
    })
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Sends a message with a route hint from on-device classification.
///
/// The `route_hint` parameter accepts: `"simple"`, `"complex"`,
/// `"creative"`, `"tool_use"`, or empty string for default routing.
///
/// # Errors
///
/// Returns [`FfiError::EstopEngaged`] if e-stop is active,
/// or [`FfiError::SpawnError`] if agent processing fails.
#[uniffi::export]
pub fn send_message_routed(message: String, route_hint: String) -> Result<String, FfiError> {
    catch_unwind(|| {
        if estop::is_engaged() {
            return Err(FfiError::EstopEngaged {
                detail: "emergency stop is engaged — all agent execution is blocked".into(),
            });
        }
        runtime::send_message_routed_inner(message, route_hint)
    })
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Validates a TOML config string without starting the daemon.
///
/// Parses `config_toml` using the same `toml::from_str::<Config>()` path
/// as [`start_daemon`]. Returns an empty string on success, or a
/// human-readable error message on parse failure.
///
/// No state mutation, no mutex acquisition, no file I/O.
///
/// # Errors
///
/// Returns [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn validate_config(config_toml: String) -> Result<String, FfiError> {
    catch_unwind(|| runtime::validate_config_inner(config_toml)).unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Returns the TOML config the running daemon was started with.
///
/// Useful for verifying the daemon's active configuration matches
/// what the Kotlin layer expects. The returned TOML may differ from
/// the original input because path overrides and default-filling
/// have been applied during [`start_daemon`].
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running,
/// [`FfiError::SpawnError`] if serialisation fails,
/// or [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn get_running_config() -> Result<String, FfiError> {
    catch_unwind(runtime::get_running_config_inner).unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Hot-swaps the default provider and model without restarting the daemon.
///
/// The change takes effect on the next message send. Does not persist
/// to disk; the Kotlin layer is responsible for persisting the setting
/// and rebuilding the TOML on next full restart.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn swap_provider(
    provider: String,
    model: String,
    api_key: Option<String>,
) -> Result<(), FfiError> {
    catch_unwind(|| runtime::swap_provider_inner(provider, model, api_key)).unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Runs channel health checks without starting the daemon.
///
/// Parses the TOML config, overrides paths with `data_dir`, then
/// instantiates each configured channel and calls `health_check()` with
/// a timeout. Returns a JSON array of channel statuses.
///
/// # Errors
///
/// Returns [`FfiError::ConfigError`] on TOML parse failure,
/// [`FfiError::SpawnError`] on channel-check or serialisation failure,
/// or [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn doctor_channels(config_toml: String, data_dir: String) -> Result<String, FfiError> {
    catch_unwind(|| runtime::doctor_channels_inner(config_toml, data_dir)).unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Returns the names of all channels with non-null config sections in
/// the running daemon's parsed TOML.
///
/// Useful for UI progress tracking during daemon startup -- the caller
/// knows which channels to expect without re-parsing the TOML.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running,
/// or [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn get_configured_channel_names() -> Result<Vec<String>, FfiError> {
    catch_unwind(runtime::get_configured_channel_names_inner).unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Binds a user identity to a channel's allowlist in the running daemon.
///
/// Appends `user_id` to the appropriate allowlist field for `channel_name`
/// (e.g. `allowed_users` for Telegram and Discord).
/// Returns the field name used on success, or `"already_bound"` if the
/// identity was already present.
///
/// **Important:** This mutates the in-memory config only. The caller must
/// restart the daemon for the change to take effect on the live channel.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running,
/// [`FfiError::ConfigError`] if `channel_name` is unknown or not configured,
/// or [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn bind_channel_identity(channel_name: String, user_id: String) -> Result<String, FfiError> {
    catch_unwind(|| runtime::bind_channel_identity_inner(channel_name, user_id)).unwrap_or_else(
        |e| {
            Err(FfiError::InternalPanic {
                detail: panic_detail(&e),
            })
        },
    )
}

/// Returns the current allowlist for a named channel from the running daemon.
///
/// Returns an empty list if the channel is configured but has no entries.
/// Useful for checking whether channel binding is needed after setup.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running,
/// [`FfiError::ConfigError`] if `channel_name` is unknown or not configured,
/// or [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn get_channel_allowlist(channel_name: String) -> Result<Vec<String>, FfiError> {
    catch_unwind(|| runtime::get_channel_allowlist_inner(channel_name)).unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Lists all auth profiles from the daemon's workspace.
///
/// Reads `auth-profiles.json` from the running daemon's workspace directory
/// and returns all stored profiles. Returns an empty list if the file does
/// not exist yet (no profiles have been stored).
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running,
/// [`FfiError::SpawnError`] on I/O or parse failure, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn list_auth_profiles() -> Result<Vec<auth_profiles::FfiAuthProfile>, FfiError> {
    catch_unwind(auth_profiles::list_auth_profiles_inner).unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Lists all auth profiles from a standalone app-owned files directory.
///
/// This variant does not require the daemon to be running and is intended for
/// Android UI flows that need to inspect auth-profile metadata before startup.
///
/// # Errors
///
/// Returns [`FfiError::InvalidArgument`] when `data_dir` is not an absolute,
/// canonical app `files/` directory, [`FfiError::SpawnError`] on I/O failure,
/// or [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn list_auth_profiles_standalone(
    data_dir: String,
) -> Result<Vec<auth_profiles::FfiAuthProfile>, FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        auth_profiles::list_auth_profiles_standalone_inner(data_dir)
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Returns the Anthropic bearer token from the standalone auth-profile store.
///
/// Anthropic OAuth tokens are long-lived and do not need refresh. The daemon
/// service uses this to inject the token into the TOML `api_key` field when
/// no direct API key is stored.
///
/// # Errors
///
/// Returns [`FfiError::InvalidArgument`] when `data_dir` is invalid,
/// [`FfiError::SpawnError`] on I/O failure, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn get_anthropic_access_token_standalone(data_dir: String) -> Result<Option<String>, FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        auth_profiles::get_anthropic_access_token_standalone_inner(data_dir)
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Returns a valid OpenAI access token from the standalone auth-profile store.
///
/// OpenAI OAuth tokens are short-lived JWTs. This function transparently
/// refreshes expired tokens using the stored refresh token before returning.
/// The Android daemon service uses this to inject a fresh bearer token into
/// the TOML `api_key` field at startup.
///
/// # Errors
///
/// Returns [`FfiError::InvalidArgument`] when `data_dir` is invalid,
/// [`FfiError::SpawnError`] on I/O or token-refresh failure, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn get_openai_access_token_standalone(data_dir: String) -> Result<Option<String>, FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        auth_profiles::get_valid_openai_access_token_standalone_inner(data_dir)
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Returns a valid Gemini access token from the standalone auth-profile store.
///
/// This is intended for Android UI flows that need to call Google Workspace APIs
/// while the Rust auth-profile store remains the sole durable owner of token
/// material.
///
/// # Errors
///
/// Returns [`FfiError::InvalidArgument`] when `data_dir` is invalid,
/// [`FfiError::SpawnError`] on I/O or token-refresh failure, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn get_valid_gemini_access_token_standalone(
    data_dir: String,
) -> Result<Option<String>, FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        auth_profiles::get_valid_gemini_access_token_standalone_inner(data_dir)
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Removes an auth profile by provider and profile name.
///
/// Constructs the profile ID as `"provider:profile_name"`, removes it
/// from the profiles map, and clears the active-profile entry if the
/// removed profile was the active one. Writes the updated JSON back
/// to disk.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running or the
/// auth-profiles file does not exist,
/// [`FfiError::SpawnError`] on I/O, parse, or serialisation failure, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn remove_auth_profile(provider: String, profile_name: String) -> Result<(), FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        auth_profiles::remove_auth_profile_inner(provider, profile_name)
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Writes or updates an OAuth profile in the encrypted Rust-owned auth profile store.
///
/// Does not require the daemon to be running; the `data_dir` path is
/// supplied explicitly so this can be called during onboarding.
///
/// # Arguments
///
/// * `data_dir` – Absolute path to the directory containing `auth-profiles.json`
///   (typically `context.filesDir`).
/// * `provider` – Provider key (e.g. `"anthropic"`, `"gemini"`).  Must not be empty.
/// * `profile_name` – Profile name within the provider (e.g. `"default"`).
/// * `access_token` – OAuth access token.
/// * `refresh_token` – Optional OAuth refresh token.
/// * `id_token` – Optional OpenID Connect ID token (Google returns this).
/// * `expires_at_ms` – Optional token expiry as epoch milliseconds.
/// * `scopes` – Optional space-separated OAuth scopes string.
///
/// # Errors
///
/// Returns [`FfiError::InvalidArgument`] when `data_dir`, `provider`,
/// `profile_name`, or `access_token` are invalid,
/// [`FfiError::SpawnError`] on I/O or serialisation failure, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
#[allow(clippy::too_many_arguments)]
pub fn write_auth_profile(
    data_dir: String,
    provider: String,
    profile_name: String,
    access_token: String,
    refresh_token: Option<String>,
    id_token: Option<String>,
    expires_at_ms: Option<i64>,
    scopes: Option<String>,
) -> Result<(), FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        auth_profiles::write_auth_profile_inner(
            data_dir,
            provider,
            profile_name,
            access_token,
            refresh_token,
            id_token,
            expires_at_ms,
            scopes,
        )
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Removes an OAuth profile from the encrypted auth profile store without requiring the
/// daemon to be running.
///
/// Constructs the profile ID as `"provider:profile_name"` and removes it
/// from the store. If the profile is not found, this is a no-op.
///
/// # Errors
///
/// Returns [`FfiError::SpawnError`] on I/O or parse failure, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn remove_auth_profile_standalone(
    data_dir: String,
    provider: String,
    profile_name: String,
) -> Result<(), FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        auth_profiles::remove_auth_profile_standalone_inner(data_dir, provider, profile_name)
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Merges non-secret metadata entries into a standalone auth profile.
///
/// This does not require the daemon to be running. Blank metadata values remove
/// the corresponding key from the stored profile.
/// The metadata payload must be a JSON object mapping string keys to string values.
///
/// # Errors
///
/// Returns [`FfiError::InvalidArgument`] when `data_dir`, `provider`,
/// `profile_name`, `metadata_json`, or a metadata key is invalid, [`FfiError::SpawnError`] on
/// I/O or persistence failure, or [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn merge_auth_profile_metadata_standalone(
    data_dir: String,
    provider: String,
    profile_name: String,
    metadata_json: String,
) -> Result<(), FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        auth_profiles::merge_auth_profile_metadata_standalone_inner(
            data_dir,
            provider,
            profile_name,
            metadata_json,
        )
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Returns the stored access token for any provider from the standalone auth-profile store.
///
/// Does not require the daemon to be running. Useful for per-skill credential
/// retrieval using the `skill::<name>` provider namespace.
///
/// Pass `"default"` for `profile_name` to retrieve the default profile.
///
/// # Errors
///
/// Returns [`FfiError::InvalidArgument`] when `data_dir` is invalid,
/// [`FfiError::SpawnError`] on I/O or read failure, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn get_access_token_standalone(
    data_dir: String,
    provider: String,
    profile_name: String,
) -> Result<Option<String>, FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        auth_profiles::get_access_token_standalone_inner(data_dir, provider, profile_name)
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Returns the version string of the native library.
///
/// Reads from the crate version set at compile time via `CARGO_PKG_VERSION`.
///
/// # Errors
///
/// Returns [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn get_version() -> Result<String, FfiError> {
    catch_unwind(|| env!("CARGO_PKG_VERSION").to_string()).map_err(|e| FfiError::InternalPanic {
        detail: panic_detail(&e),
    })
}

/// Returns the port the gateway HTTP server is bound to.
///
/// The gateway binds to port 0 (OS-assigned) by default, so this is the
/// only way to discover the actual port after daemon start.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn get_gateway_port() -> Result<u16, FfiError> {
    catch_unwind(runtime::get_gateway_port).unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Update on-device Gemini Nano availability.
///
/// Called from Kotlin after ML Kit `checkModelStatus()` at daemon startup
/// and on config changes. Default is `false` (Nano unavailable).
///
/// # Errors
///
/// Returns [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn set_nano_available(available: bool) -> Result<(), FfiError> {
    std::panic::catch_unwind(|| {
        crate::runtime::set_nano_available_inner(available);
        Ok(())
    })
    .unwrap_or_else(|_| {
        Err(FfiError::InternalPanic {
            detail: "set_nano_available panicked".into(),
        })
    })
}

/// Check if on-device Gemini Nano is available for agent scripting.
#[uniffi::export]
pub fn is_nano_available() -> bool {
    std::panic::catch_unwind(crate::runtime::is_nano_available_inner).unwrap_or(false)
}

/// Generate a bearer token for WebView authentication.
///
/// Creates a random token, registers its SHA-256 hash with the gateway's
/// pairing guard, and returns the plaintext. The token is never persisted --
/// caller must hold it in memory only.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn create_pairing_token() -> Result<String, FfiError> {
    catch_unwind(|| {
        let guard = runtime::get_pairing_guard()?;
        Ok(guard.register_programmatic_token())
    })
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Engages the emergency stop, cancelling all active agent execution.
///
/// While engaged, [`send_message`], [`session_send`], and
/// [`send_message_streaming`] return [`FfiError::EstopEngaged`].
/// State is persisted to disk and survives process death.
///
/// # Errors
///
/// Returns [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn engage_estop() -> Result<(), FfiError> {
    catch_unwind(estop::engage_estop_inner).unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Returns the current emergency stop status.
///
/// The returned [`estop::FfiEstopStatus`] includes whether the stop is
/// engaged and the epoch-millisecond timestamp of engagement (if available).
///
/// # Errors
///
/// Returns [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn get_estop_status() -> Result<estop::FfiEstopStatus, FfiError> {
    catch_unwind(estop::get_estop_status_inner).unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Resumes from an engaged emergency stop.
///
/// Clears the kill-all flag and persists the resumed state to disk.
/// Agent-executing functions will accept requests again immediately.
///
/// # Errors
///
/// Returns [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn resume_estop() -> Result<(), FfiError> {
    catch_unwind(estop::resume_estop_inner).unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Scaffolds the `ZeroClaw` workspace directory with identity files.
///
/// Creates 5 subdirectories (`sessions/`, `memory/`, `state/`, `cron/`,
/// `skills/`) and writes 8 markdown template files (`IDENTITY.md`,
/// `AGENTS.md`, `HEARTBEAT.md`, `SOUL.md`, `USER.md`, `TOOLS.md`,
/// `BOOTSTRAP.md`, `MEMORY.md`) inside `workspace_path`.
///
/// Idempotent: existing files are never overwritten. Empty parameter
/// strings are replaced with upstream defaults (e.g. agent name
/// defaults to `"ZeroAI"`).
///
/// # Errors
///
/// Returns [`FfiError::ConfigError`] if directory creation or file
/// writing fails, or [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn scaffold_workspace(
    workspace_path: String,
    agent_name: String,
    user_name: String,
    timezone: String,
    communication_style: String,
) -> Result<(), FfiError> {
    catch_unwind(|| {
        workspace::create_workspace(
            &workspace_path,
            &agent_name,
            &user_name,
            &timezone,
            &communication_style,
        )
    })
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Returns the current cost summary for session, day, and month.
///
/// Requires the daemon to be running with cost tracking enabled.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running or
/// cost tracking is disabled,
/// [`FfiError::SpawnError`] on tracker or serialisation failure, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn get_cost_summary() -> Result<cost::FfiCostSummary, FfiError> {
    catch_unwind(cost::get_cost_summary_inner).unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Returns the cost for a specific day in USD.
///
/// Requires the daemon to be running with cost tracking enabled.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running or
/// cost tracking is disabled,
/// [`FfiError::SpawnError`] on invalid date or tracker failure, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn get_daily_cost(year: i32, month: u32, day: u32) -> Result<f64, FfiError> {
    catch_unwind(|| cost::get_daily_cost_inner(year, month, day)).unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Returns the cost for a specific month in USD.
///
/// Requires the daemon to be running with cost tracking enabled.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running or
/// cost tracking is disabled,
/// [`FfiError::SpawnError`] on tracker failure, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn get_monthly_cost(year: i32, month: u32) -> Result<f64, FfiError> {
    catch_unwind(|| cost::get_monthly_cost_inner(year, month)).unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Checks whether an estimated cost fits within configured budget limits.
///
/// Returns [`cost::FfiBudgetStatus::Allowed`] when within budget,
/// [`cost::FfiBudgetStatus::Warning`] when approaching limits, or
/// [`cost::FfiBudgetStatus::Exceeded`] when limits are breached.
///
/// Requires the daemon to be running with cost tracking enabled.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running or
/// cost tracking is disabled,
/// [`FfiError::SpawnError`] on tracker failure, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn check_budget(estimated_cost_usd: f64) -> Result<cost::FfiBudgetStatus, FfiError> {
    catch_unwind(|| cost::check_budget_inner(estimated_cost_usd)).unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Registers a Kotlin-side event listener to receive live observer events.
///
/// Only one listener can be registered at a time. Registering a new
/// listener replaces the previous one.
///
/// # Errors
///
/// Returns [`FfiError::StateCorrupted`] if internal state is poisoned, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn register_event_listener(
    listener: Box<dyn events::FfiEventListener>,
) -> Result<(), FfiError> {
    let listener: Arc<dyn events::FfiEventListener> = Arc::from(listener);
    catch_unwind(AssertUnwindSafe(|| {
        events::register_event_listener_inner(listener)
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Unregisters the current event listener.
///
/// After this call, events are still buffered in the ring buffer but
/// no longer forwarded to Kotlin.
///
/// # Errors
///
/// Returns [`FfiError::StateCorrupted`] if internal state is poisoned, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn unregister_event_listener() -> Result<(), FfiError> {
    // Direct function reference preferred over closure by clippy::redundant_closure.
    catch_unwind(events::unregister_event_listener_inner).unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Registers a Kotlin-side credential resolver callback.
///
/// When registered, the Rust engine resolves per-provider API keys by
/// invoking this callback instead of reading environment variables.
/// Only one resolver can be registered at a time; a new one replaces
/// the previous.
///
/// # Errors
///
/// Returns [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn register_credential_resolver(
    resolver: Box<dyn credentials::FfiCredentialResolver>,
) -> Result<(), FfiError> {
    let resolver: Arc<dyn credentials::FfiCredentialResolver> = Arc::from(resolver);
    catch_unwind(AssertUnwindSafe(|| {
        credentials::register_credential_resolver_inner(resolver)
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Unregisters the current credential resolver and clears the cache.
///
/// After this call, credential resolution falls back to environment
/// variables and config-file overrides.
///
/// # Errors
///
/// Returns [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn unregister_credential_resolver() -> Result<(), FfiError> {
    catch_unwind(credentials::unregister_credential_resolver_inner).unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Clears the per-provider credential cache.
///
/// The next credential resolution for each provider will re-invoke the
/// Kotlin callback. Useful after the user adds or rotates an API key.
///
/// # Errors
///
/// Returns [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn clear_credential_cache() -> Result<(), FfiError> {
    catch_unwind(credentials::clear_credential_cache_inner).unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Returns the most recent events as a JSON array.
///
/// Events are ordered chronologically (oldest first). The `limit`
/// parameter caps how many events to return.
///
/// # Errors
///
/// Returns [`FfiError::StateCorrupted`] if internal state is poisoned, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn get_recent_events(limit: u32) -> Result<String, FfiError> {
    catch_unwind(|| events::get_recent_events_inner(limit)).unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Lists all cron jobs registered with the running daemon.
///
/// Requires the daemon to be running so the cron SQLite database is accessible.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running,
/// [`FfiError::SpawnError`] on database access failure, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn list_cron_jobs() -> Result<Vec<cron::FfiCronJob>, FfiError> {
    catch_unwind(cron::list_cron_jobs_inner).unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Retrieves a single cron job by its identifier.
///
/// Returns `None` if no job with the given `id` exists.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running,
/// [`FfiError::SpawnError`] on database access failure, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn get_cron_job(id: String) -> Result<Option<cron::FfiCronJob>, FfiError> {
    catch_unwind(AssertUnwindSafe(|| cron::get_cron_job_inner(id))).unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Adds a new recurring cron job with the given expression and command.
///
/// The `expression` must be a valid cron expression (e.g. `"0 0/5 * * *"`).
/// The `command` is the prompt or action the scheduler will execute.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running,
/// [`FfiError::SpawnError`] on invalid expression or database failure, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn add_cron_job(expression: String, command: String) -> Result<cron::FfiCronJob, FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        cron::add_cron_job_inner(expression, command)
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Adds a one-shot job that fires once after the given delay.
///
/// The `delay` string uses human-readable durations (e.g. `"5m"`, `"2h"`).
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running,
/// [`FfiError::SpawnError`] on invalid delay or database failure, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn add_one_shot_job(delay: String, command: String) -> Result<cron::FfiCronJob, FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        cron::add_one_shot_job_inner(delay, command)
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Adds a one-shot cron job that fires at a specific RFC 3339 timestamp.
///
/// The `timestamp_rfc3339` must be a valid RFC 3339 string (e.g.
/// `"2026-12-31T23:59:59Z"`). The job self-deletes after firing.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running,
/// [`FfiError::SpawnError`] on invalid timestamp or database failure, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn add_cron_job_at(
    timestamp_rfc3339: String,
    command: String,
) -> Result<cron::FfiCronJob, FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        cron::add_cron_job_at_inner(timestamp_rfc3339, command)
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Adds a fixed-interval repeating cron job.
///
/// The `interval_ms` specifies the repeat interval in milliseconds.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running,
/// [`FfiError::SpawnError`] on database failure, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn add_cron_job_every(interval_ms: u64, command: String) -> Result<cron::FfiCronJob, FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        cron::add_cron_job_every_inner(interval_ms, command)
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Removes a cron job by its identifier.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running,
/// [`FfiError::SpawnError`] if the job does not exist or database fails, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn remove_cron_job(id: String) -> Result<(), FfiError> {
    catch_unwind(AssertUnwindSafe(|| cron::remove_cron_job_inner(id))).unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Pauses a cron job so it will not fire until resumed.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running,
/// [`FfiError::SpawnError`] if the job does not exist or database fails, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn pause_cron_job(id: String) -> Result<(), FfiError> {
    catch_unwind(AssertUnwindSafe(|| cron::pause_cron_job_inner(id))).unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Resumes a previously paused cron job.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running,
/// [`FfiError::SpawnError`] if the job does not exist or database fails, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn resume_cron_job(id: String) -> Result<(), FfiError> {
    catch_unwind(AssertUnwindSafe(|| cron::resume_cron_job_inner(id))).unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Lists all skills loaded from the workspace's `skills/` directory.
///
/// Each skill includes its name, description, version, author, tags,
/// and the names of any tools it provides.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn list_skills() -> Result<Vec<skills::FfiSkill>, FfiError> {
    catch_unwind(skills::list_skills_inner).unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Lists the tools provided by a specific skill.
///
/// Returns an empty list if the skill is not found or has no tools.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn get_skill_tools(skill_name: String) -> Result<Vec<skills::FfiSkillTool>, FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        skills::get_skill_tools_inner(skill_name)
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Installs a skill from a URL or local path.
///
/// For URLs, performs a `git clone --depth 1` into the skills directory.
/// For local paths, creates a symlink (or copies on platforms without
/// symlink support).
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running,
/// [`FfiError::SpawnError`] on install failure, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn install_skill(source: String) -> Result<(), FfiError> {
    catch_unwind(AssertUnwindSafe(|| skills::install_skill_inner(source))).unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Removes an installed skill by name.
///
/// Deletes the skill directory from the workspace's `skills/` folder.
/// Path traversal attempts are rejected.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running,
/// [`FfiError::SpawnError`] if removal fails, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn remove_skill(name: String) -> Result<(), FfiError> {
    catch_unwind(AssertUnwindSafe(|| skills::remove_skill_inner(name))).unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Saves a community skill's `SKILL.md` content to the workspace.
///
/// Creates the skill directory if needed. Overwrites existing files.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running,
/// [`FfiError::ConfigError`] if the name is unsafe,
/// [`FfiError::SpawnError`] if writing fails, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn save_community_skill(name: String, content: String) -> Result<(), FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        skills::save_community_skill_inner(name, content)
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Toggles a community skill between enabled and disabled.
///
/// Renames `SKILL.md` to `SKILL.md.disabled` or vice versa.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running,
/// [`FfiError::ConfigError`] if the name is unsafe,
/// [`FfiError::SpawnError`] if the rename fails, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn toggle_community_skill(name: String, enabled: bool) -> Result<(), FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        skills::toggle_community_skill_inner(name, enabled)
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Reads the raw `SKILL.md` content of a community skill.
///
/// Returns the full file content including YAML frontmatter.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running,
/// [`FfiError::ConfigError`] if the name is unsafe,
/// [`FfiError::SpawnError`] if the file is not found, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn get_skill_content(name: String) -> Result<String, FfiError> {
    catch_unwind(AssertUnwindSafe(|| skills::get_skill_content_inner(name))).unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Lists all available tools based on daemon config and installed skills.
///
/// Returns built-in tools (always present), conditional tools (browser,
/// HTTP, Composio, delegate), and skill-provided tools.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn list_tools() -> Result<Vec<tools_browse::FfiToolSpec>, FfiError> {
    catch_unwind(tools_browse::list_tools_inner).unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Lists memory entries, optionally filtered by category and/or session.
///
/// Categories: `"core"`, `"daily"`, `"conversation"`, or any custom
/// category name. Pass `None` for all categories.
///
/// When `session_id` is provided, only entries from that session are returned.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running or
/// memory is unavailable,
/// [`FfiError::SpawnError`] on backend failure, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn list_memories(
    category: Option<String>,
    limit: u32,
    session_id: Option<String>,
) -> Result<Vec<memory_browse::FfiMemoryEntry>, FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        memory_browse::list_memories_inner(category, limit, session_id)
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Searches memory entries by keyword query, optionally scoped to a session.
///
/// Returns up to `limit` entries ranked by relevance.
///
/// When `session_id` is provided, only entries from that session are searched.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running or
/// memory is unavailable,
/// [`FfiError::SpawnError`] on backend failure, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn recall_memory(
    query: String,
    limit: u32,
    session_id: Option<String>,
) -> Result<Vec<memory_browse::FfiMemoryEntry>, FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        memory_browse::recall_memory_inner(query, limit, session_id)
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Deletes a memory entry by key.
///
/// Returns `true` if the entry was found and deleted, `false` otherwise.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running or
/// memory is unavailable,
/// [`FfiError::SpawnError`] on backend failure, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn forget_memory(key: String) -> Result<bool, FfiError> {
    catch_unwind(AssertUnwindSafe(|| memory_browse::forget_memory_inner(key))).unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Sends a vision (image + text) message directly to the configured provider.
///
/// Bypasses `ZeroClaw`'s text-only agent loop and calls the provider's
/// multimodal API directly. `image_data` contains base64-encoded images
/// and `mime_types` contains the corresponding MIME type for each image.
///
/// # Errors
///
/// Returns [`FfiError::ConfigError`] for validation failures,
/// [`FfiError::InvalidArgument`] for unsupported providers or invalid MIME types,
/// [`FfiError::StateError`] if the daemon is not running or response parsing fails,
/// [`FfiError::SpawnError`] for HTTP client or network failures, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn send_vision_message(
    text: String,
    image_data: Vec<String>,
    mime_types: Vec<String>,
) -> Result<String, FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        if estop::is_engaged() {
            return Err(FfiError::EstopEngaged {
                detail: "Emergency stop is engaged. Resume before sending messages.".into(),
            });
        }
        vision::send_vision_message_inner(text, image_data, mime_types)
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Returns whether the active cloud provider supports vision (image input).
///
/// Reads the daemon's default provider and checks if it has a known
/// vision wire format. Used by the Android UI to decide whether captured
/// images should be routed to the cloud provider or described on-device
/// via Gemini Nano.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running,
/// [`FfiError::StateCorrupted`] if internal state is poisoned, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn get_provider_supports_vision() -> Result<bool, FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        vision::get_provider_supports_vision_inner()
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Returns the total number of memory entries.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running or
/// memory is unavailable,
/// [`FfiError::SpawnError`] on backend failure, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn memory_count() -> Result<u32, FfiError> {
    catch_unwind(memory_browse::memory_count_inner).unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Evaluates a Rhai expression against the embedded REPL engine.
///
/// The REPL engine has all gateway functions registered as native Rhai
/// calls. Structured return values are serialised to JSON; unit results
/// become `"ok"`; primitives are converted to strings.
///
/// # Errors
///
/// Returns [`FfiError::StateCorrupted`] if the engine mutex is poisoned,
/// [`FfiError::SpawnError`] if the Rhai evaluation fails, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn eval_script(expression: String) -> Result<String, FfiError> {
    catch_unwind(AssertUnwindSafe(|| repl::eval_script_inner(expression))).unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Evaluates a script using an explicit capability grant set.
///
/// An empty `granted_capabilities` list explicitly denies all host access.
///
/// # Errors
///
/// Returns [`FfiError::InvalidArgument`] for invalid input, validation
/// failures, or denied capabilities, and [`FfiError::InternalPanic`] if
/// native code panics.
#[uniffi::export]
pub fn eval_script_with_capabilities(
    expression: String,
    granted_capabilities: Vec<String>,
) -> Result<String, FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        repl::eval_script_with_capabilities_inner(expression, granted_capabilities)
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Validates a script and reports the capabilities it requests.
///
/// This performs core-Rust preflight validation without mutating daemon
/// state. The response includes the inferred or explicit capability set,
/// missing manifest permissions, and any non-fatal warnings.
///
/// # Errors
///
/// Returns [`FfiError::InvalidArgument`] for invalid script source or
/// validation failures, or [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn validate_script(expression: String) -> Result<repl::FfiScriptValidation, FfiError> {
    catch_unwind(AssertUnwindSafe(|| repl::validate_script_inner(expression))).unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Validates a script using an explicit capability grant set.
///
/// This makes the deny-all case reviewable before execution.
///
/// # Errors
///
/// Returns [`FfiError::InvalidArgument`] for invalid input or validation
/// failures, and [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn validate_script_with_capabilities(
    expression: String,
    granted_capabilities: Vec<String>,
) -> Result<repl::FfiScriptValidation, FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        repl::validate_script_with_capabilities_inner(expression, granted_capabilities)
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Lists the scripting capabilities enforced by the Rust runtime.
///
/// Includes both active capabilities (for example `model.chat`) and
/// default deny declarations such as `net.none`.
///
/// # Errors
///
/// Returns [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn list_script_capabilities() -> Result<Vec<String>, FfiError> {
    catch_unwind(|| Ok(repl::list_script_capabilities_inner())).unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Lists workspace and skill-packaged scripts discoverable by the daemon.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn list_workspace_scripts() -> Result<Vec<repl::FfiWorkspaceScript>, FfiError> {
    catch_unwind(AssertUnwindSafe(repl::list_workspace_scripts_inner)).unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Validates a packaged workspace script without executing it.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running,
/// [`FfiError::InvalidArgument`] for invalid paths or validation failures,
/// or [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn validate_workspace_script(
    relative_path: String,
) -> Result<repl::FfiScriptValidation, FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        repl::validate_workspace_script_inner(relative_path)
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Executes a packaged workspace script using an explicit capability grant set.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running,
/// [`FfiError::InvalidArgument`] for invalid paths, denied capabilities, or
/// unsupported runtimes, and [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn run_workspace_script(
    relative_path: String,
    granted_capabilities: Vec<String>,
) -> Result<String, FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        repl::run_workspace_script_inner(relative_path, granted_capabilities)
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Lists the scripting runtimes known to this build.
///
/// # Errors
///
/// Returns [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn list_script_runtimes() -> Result<Vec<repl::FfiScriptRuntime>, FfiError> {
    catch_unwind(|| Ok(repl::list_script_runtimes_inner())).unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Returns the stable WIT host definition for plugin guests.
///
/// # Errors
///
/// Returns [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn get_script_plugin_host_wit() -> Result<String, FfiError> {
    catch_unwind(|| Ok(repl::script_plugin_host_wit_inner())).unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Scan workspace skills for cron triggers and register them as scheduled jobs.
///
/// Idempotent: existing script jobs with the same name are skipped.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn register_script_triggers() -> Result<u32, FfiError> {
    catch_unwind(AssertUnwindSafe(repl::register_script_triggers_inner)).unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Evaluates a Rhai expression against the embedded REPL engine.
///
/// This is a backward-compatible alias for [`eval_script`].
///
/// # Errors
///
/// Returns [`FfiError::InvalidArgument`] for invalid script input,
/// [`FfiError::SpawnError`] if evaluation fails, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn eval_repl(expression: String) -> Result<String, FfiError> {
    catch_unwind(AssertUnwindSafe(|| repl::eval_repl_inner(expression))).unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Queries runtime trace events from the daemon's JSONL trace file.
///
/// Returns a JSON array of trace event objects, newest last.
/// Returns `"[]"` if tracing is disabled or no events match.
///
/// # Arguments
///
/// * `filter` - Optional case-insensitive substring match on message/payload.
/// * `event_type` - Optional exact match on event_type (e.g. `"tool_call"`, `"model_reply"`).
/// * `limit` - Maximum events to return.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running,
/// [`FfiError::SpawnError`] on I/O or serialisation failure, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn query_runtime_traces(
    filter: Option<String>,
    event_type: Option<String>,
    limit: u32,
) -> Result<String, FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        traces::query_traces_inner(filter, event_type, limit)
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Sends a streaming message directly to the configured provider.
///
/// Bypasses the full agent loop and calls the provider's streaming API
/// directly. Chunks are classified as thinking or response content and
/// delivered to the [listener] callback in real time. The stream can be
/// cancelled by calling [`cancel_streaming`].
///
/// Falls back path: if the provider does not support streaming, returns
/// an error. Callers should use [`send_message`] for non-streaming providers.
///
/// # Errors
///
/// Returns [`FfiError::ConfigError`] for oversized messages,
/// [`FfiError::StateError`] if the daemon is not running,
/// [`FfiError::SpawnError`] if provider creation or streaming fails, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn send_message_streaming(
    message: String,
    listener: Box<dyn streaming::FfiStreamListener>,
) -> Result<(), FfiError> {
    let listener: Arc<dyn streaming::FfiStreamListener> = Arc::from(listener);
    catch_unwind(AssertUnwindSafe(|| {
        if estop::is_engaged() {
            return Err(FfiError::EstopEngaged {
                detail: "Emergency stop is engaged. Resume before sending messages.".into(),
            });
        }
        streaming::send_message_streaming_inner(message, listener)
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Signals the current streaming operation to cancel.
///
/// Sets an internal cancel flag that is checked between stream chunks.
/// The streaming callback will receive an `on_error("Request cancelled")`
/// call at the next chunk boundary.
///
/// Safe to call at any time, including when no streaming is in progress.
///
/// # Errors
///
/// Returns [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn cancel_streaming() -> Result<(), FfiError> {
    catch_unwind(streaming::cancel_streaming_inner).unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

// ── Live agent session ──────────────────────────────────────────────────

/// Creates a new live agent session from the running daemon's configuration.
///
/// Builds the system prompt, tools registry, and provider configuration.
/// Only one session may exist at a time; call [`session_destroy`] first
/// if a previous session is still active.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if a session is already active or the
/// daemon is not running, [`FfiError::StateCorrupted`] if the session
/// mutex is poisoned, [`FfiError::SpawnError`] if provider creation fails,
/// or [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn session_start() -> Result<(), FfiError> {
    catch_unwind(session::session_start_inner).unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Injects seed messages into the active session's conversation history.
///
/// Used to restore prior context from Room persistence before the first
/// [`session_send`] call. At most 20 entries are accepted; system-role
/// messages are silently skipped.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if no session is active,
/// [`FfiError::StateCorrupted`] if the session mutex is poisoned, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn session_seed(messages: Vec<session::SessionMessage>) -> Result<(), FfiError> {
    catch_unwind(AssertUnwindSafe(|| session::session_seed_inner(messages))).unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Sends a message through the live agent session's tool-call loop.
///
/// Runs the full agent loop with memory recall, tool execution, streaming
/// progress, and auto-compaction. Events are delivered to the `listener`
/// callback in real time. The send can be cancelled by calling
/// [`session_cancel`].
///
/// Images are optional. When provided, each entry in `image_data` is a
/// base64-encoded image and `mime_types` holds the corresponding MIME
/// type (e.g. `image/jpeg`). The images are embedded as `[IMAGE:...]`
/// markers in the user message so the upstream provider can convert
/// them to multimodal content parts.
///
/// # Errors
///
/// Returns [`FfiError::ConfigError`] for oversized messages or
/// mismatched image arrays, [`FfiError::StateError`] if no session is
/// active, [`FfiError::StateCorrupted`] if the session mutex is
/// poisoned, [`FfiError::SpawnError`] if the agent loop or provider
/// creation fails, or [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn session_send(
    message: String,
    image_data: Vec<String>,
    mime_types: Vec<String>,
    listener: Box<dyn session::FfiSessionListener>,
) -> Result<(), FfiError> {
    let listener: Arc<dyn session::FfiSessionListener> = Arc::from(listener);
    catch_unwind(AssertUnwindSafe(|| {
        if estop::is_engaged() {
            return Err(FfiError::EstopEngaged {
                detail: "Emergency stop is engaged. Resume before sending messages.".into(),
            });
        }
        session::session_send_inner(message, image_data, mime_types, listener)
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Cancels the currently running [`session_send`] call.
///
/// Sets the internal cancellation token. The agent loop aborts at the
/// next check point and fires `on_cancelled()` on the listener.
/// No-op if no send is in progress.
///
/// # Errors
///
/// Returns [`FfiError::StateCorrupted`] if the cancel token mutex is
/// poisoned, or [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn session_cancel() -> Result<(), FfiError> {
    catch_unwind(|| {
        session::session_cancel_inner();
        Ok(())
    })
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Clears the active session's conversation history.
///
/// Retains the system prompt but discards all user, assistant, and tool
/// messages. The session remains active and ready for new sends.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if no session is active,
/// [`FfiError::StateCorrupted`] if the session mutex is poisoned, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn session_clear() -> Result<(), FfiError> {
    catch_unwind(session::session_clear_inner).unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Returns the current conversation history as a list of session messages.
///
/// Includes the system prompt as the first entry, followed by user,
/// assistant, and tool messages in chronological order.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if no session is active,
/// [`FfiError::StateCorrupted`] if the session mutex is poisoned, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn session_history() -> Result<Vec<session::SessionMessage>, FfiError> {
    catch_unwind(session::session_history_inner).unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Discovers available models from a provider's API.
///
/// Returns a JSON array of `{"id": "model-id", "name": "display-name"}` objects.
/// For Anthropic, returns a hardcoded list of known models. For Ollama, queries
/// the local `/api/tags` endpoint. All other providers use the `OpenAI`-compatible
/// `/v1/models` endpoint.
///
/// This function does NOT require the daemon to be running. It creates its own
/// HTTP client and queries the provider API directly.
///
/// # Errors
///
/// Returns [`FfiError::SpawnError`] on HTTP client, network, or parse errors,
/// or [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn discover_models(
    provider: String,
    api_key: String,
    base_url: Option<String>,
) -> Result<String, FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        models::discover_models_inner(provider, api_key, base_url)
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Destroys the active session and releases all resources.
///
/// Cancels any in-flight send, drops the tools registry, and clears
/// the session slot. A new session may be created afterwards with
/// [`session_start`].
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if no session is active,
/// [`FfiError::StateCorrupted`] if the session mutex is poisoned, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn session_destroy() -> Result<(), FfiError> {
    catch_unwind(session::session_destroy_inner).unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

// ── Session persistence ─────────────────────────────────────────────────

/// Saves the active session's conversation history to a JSON file.
///
/// Serializes the current in-memory conversation transcript (including the
/// system prompt) to a versioned JSON envelope at `path`. Creates parent
/// directories if they do not exist. The file can later be restored with
/// [`restore_session_state`].
///
/// Intended for use by the Android lifecycle layer to persist state before
/// the OS kills the foreground service process.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if no session is active,
/// [`FfiError::ConfigError`] on I/O or serialization failures, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn save_session_state(session_id: String, path: String) -> Result<(), FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        let _ = &session_id; // reserved for future multi-session support
        let history: Vec<zeroclaw::providers::ChatMessage> = session::session_history_inner()?
            .iter()
            .map(|m| {
                #[allow(clippy::match_same_arms)]
                match m.role.as_str() {
                    "system" => zeroclaw::providers::ChatMessage::system(&m.content),
                    "user" => zeroclaw::providers::ChatMessage::user(&m.content),
                    "assistant" => zeroclaw::providers::ChatMessage::assistant(&m.content),
                    "tool" => zeroclaw::providers::ChatMessage::tool(&m.content),
                    _ => zeroclaw::providers::ChatMessage::user(&m.content),
                }
            })
            .collect();
        session_persistence::save_interactive_session_history(
            &std::path::PathBuf::from(&path),
            &history,
        )
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Restores a session's conversation history from a JSON file.
///
/// Loads the persisted conversation transcript and injects it into the
/// active session via [`session_seed`]. The system prompt from the active
/// session is used, replacing any stale system prompt in the file.
///
/// Returns `true` if state was restored, `false` if the file does not
/// exist (the session continues with its default empty history).
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if no session is active,
/// [`FfiError::ConfigError`] on parse or I/O failures, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn restore_session_state(session_id: String, path: String) -> Result<bool, FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        let _ = &session_id; // reserved for future multi-session support
        let file_path = std::path::PathBuf::from(&path);
        if !file_path.exists() {
            return Ok(false);
        }

        // Get the current system prompt from the active session.
        let current_history = session::session_history_inner()?;
        let system_prompt = current_history
            .iter()
            .find(|m| m.role == "system")
            .map(|m| m.content.clone())
            .unwrap_or_default();

        let history =
            session_persistence::load_interactive_session_history(&file_path, &system_prompt)?;

        // Seed non-system messages into the active session.
        let seed_messages: Vec<session::SessionMessage> = history
            .iter()
            .filter(|m| m.role != "system")
            .map(|m| session::SessionMessage {
                role: m.role.clone(),
                content: m.content.clone(),
            })
            .collect();

        if !seed_messages.is_empty() {
            session::session_seed_inner(seed_messages)?;
        }

        Ok(true)
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Lists session IDs that have persisted state files in a directory.
///
/// Scans `dir` for `.json` files and returns their stems as session
/// identifiers. If the directory does not exist, returns an empty list.
///
/// Does NOT require the daemon or a session to be running.
///
/// # Errors
///
/// Returns [`FfiError::ConfigError`] if the directory exists but cannot
/// be read, or [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn list_persisted_sessions(dir: String) -> Result<Vec<String>, FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        session_persistence::list_persisted_sessions(&std::path::PathBuf::from(&dir))
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

// ── Discord archive ─────────────────────────────────────────────────────

/// Fetches the guilds the bot is a member of from the Discord REST API.
///
/// Calls `GET /users/@me/guilds` with Bot authorization and returns
/// partial guild objects (id, name, icon). Does NOT require the daemon
/// to be running.
///
/// # Errors
///
/// Returns [`FfiError::InvalidArgument`] if `bot_token` is empty,
/// [`FfiError::SpawnError`] on HTTP or parse failure, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn discord_fetch_bot_guilds(
    bot_token: String,
) -> Result<Vec<discord::FfiDiscordGuild>, FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        discord::discord_fetch_bot_guilds_inner(bot_token)
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Fetches text channels from a Discord guild via the REST API.
///
/// Calls `GET /guilds/{guild_id}/channels` with Bot authorization,
/// filters to type 0 (text channels), and returns them sorted by name.
/// Does NOT require the daemon to be running.
///
/// # Errors
///
/// Returns [`FfiError::InvalidArgument`] if `bot_token` or `guild_id` is empty,
/// [`FfiError::SpawnError`] on HTTP or parse failure, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn discord_fetch_guild_channels(
    bot_token: String,
    guild_id: String,
) -> Result<Vec<discord::FfiDiscordChannel>, FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        discord::discord_fetch_guild_channels_inner(bot_token, guild_id)
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Validates a Discord user by fetching their profile from the REST API.
///
/// Calls `GET /users/{user_id}` with Bot authorization and returns the
/// user's ID, username, and avatar URL. Does NOT require the daemon to
/// be running.
///
/// # Errors
///
/// Returns [`FfiError::InvalidArgument`] if `bot_token` or `user_id` is empty,
/// [`FfiError::SpawnError`] on HTTP or parse failure, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn discord_validate_user(
    bot_token: String,
    user_id: String,
) -> Result<discord::FfiDiscordUser, FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        discord::discord_validate_user_inner(bot_token, user_id)
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Configures a Discord channel for archiving.
///
/// Creates or replaces the channel's archive config and sync state entry
/// in the daemon's Discord archive database.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running or the
/// Discord archive is unavailable,
/// [`FfiError::SpawnError`] on database failure, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn discord_configure_channel(
    channel_id: String,
    guild_id: String,
    channel_name: String,
    backfill_depth: String,
) -> Result<(), FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        discord::discord_configure_channel_inner(channel_id, guild_id, channel_name, backfill_depth)
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Removes a channel and all its archived data from the Discord archive.
///
/// Deletes messages, sync state, and channel config for the given channel.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running or the
/// Discord archive is unavailable,
/// [`FfiError::SpawnError`] on database failure, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn discord_remove_channel(channel_id: String) -> Result<(), FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        discord::discord_remove_channel_inner(channel_id)
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Links a Discord DM user ID in the shared Discord runtime state.
///
/// Stores the user ID so that future DM routing logic can find it. This
/// can be called before or after the daemon starts; the Kotlin layer is
/// responsible for persisting the durable source of truth separately.
///
/// # Errors
///
/// Returns [`FfiError::InvalidArgument`] if `user_id` is empty or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn discord_link_dm_user(user_id: String) -> Result<(), FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        discord::discord_link_dm_user_inner(user_id)
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Clears the linked Discord DM user from the shared Discord runtime state.
///
/// Safe to call before or after daemon startup. This removes any stale
/// in-process DM-link target until Kotlin replays a persisted value.
///
/// # Errors
///
/// Returns [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn discord_unlink_dm_user() -> Result<(), FfiError> {
    catch_unwind(AssertUnwindSafe(discord::discord_unlink_dm_user_inner)).unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Searches the Discord archive using FTS5 full-text search.
///
/// Returns matching messages from the archive, optionally filtered by
/// channel and time window.
///
/// # Errors
///
/// Returns [`FfiError::InvalidArgument`] if `query` is empty,
/// [`FfiError::StateError`] if the daemon is not running or the
/// Discord archive is unavailable,
/// [`FfiError::SpawnError`] on search failure, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn discord_search_history(
    query: String,
    channel_id: Option<String>,
    days_back: Option<i64>,
    limit: Option<u32>,
) -> Result<Vec<discord::FfiDiscordSearchResult>, FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        discord::discord_search_history_inner(query, channel_id, days_back, limit)
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Returns sync status for a specific archived Discord channel.
///
/// Combines the sync cursor state with the message count for a
/// comprehensive status view.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running or the
/// Discord archive is unavailable,
/// [`FfiError::SpawnError`] on database failure, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn discord_get_sync_status(
    channel_id: String,
) -> Result<discord::FfiDiscordSyncStatus, FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        discord::discord_get_sync_status_inner(channel_id)
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Triggers a background backfill task for a Discord channel.
///
/// Reads the channel's configured backfill depth, resolves the cutoff
/// timestamp, and spawns the backfill engine on the tokio runtime. The
/// task runs asynchronously until completion or daemon shutdown.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running or the
/// Discord archive is unavailable,
/// [`FfiError::ConfigError`] if the channel is not configured or the
/// backfill depth does not require backfill,
/// [`FfiError::SpawnError`] on runtime failure, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn discord_trigger_backfill(channel_id: String) -> Result<(), FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        discord::discord_trigger_backfill_inner(channel_id)
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

// ── Messages bridge ─────────────────────────────────────────────────────

/// Returns the current Google Messages bridge connection status.
///
/// Returns [`FfiBridgeStatus::Unpaired`] if no session is active.
///
/// # Errors
///
/// Returns [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn messages_bridge_get_status() -> Result<messages_bridge::FfiBridgeStatus, FfiError> {
    catch_unwind(AssertUnwindSafe(|| Ok(messages_bridge::get_status_inner()))).unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Lists all bridged Google Messages conversations.
///
/// Returns conversations ordered by last message timestamp descending,
/// including allowlist state and optional time-window cutoff.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the bridge session is not active,
/// [`FfiError::SpawnError`] on store query failure, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn messages_bridge_list_conversations()
-> Result<Vec<messages_bridge::FfiBridgedConversation>, FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        messages_bridge::list_conversations_inner()
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Sets whether the AI agent is allowed to read a specific conversation.
///
/// When `allowed` is `true`, the optional `window_start_ms` sets the
/// earliest timestamp the agent may see. Pass `null` to allow all history.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the bridge session is not active,
/// [`FfiError::SpawnError`] on store update failure, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn messages_bridge_set_allowed(
    conversation_id: String,
    allowed: bool,
    window_start_ms: Option<i64>,
) -> Result<(), FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        messages_bridge::set_allowed_inner(conversation_id, allowed, window_start_ms)
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Disconnects the Google Messages bridge.
///
/// Stops the long-poll listener and sets status back to
/// [`FfiBridgeStatus::Unpaired`]. Store data is preserved so the
/// user can re-pair without losing conversation history.
///
/// # Errors
///
/// Returns [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn messages_bridge_disconnect() -> Result<(), FfiError> {
    catch_unwind(AssertUnwindSafe(messages_bridge::disconnect_inner)).unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Disconnects the Google Messages bridge and wipes all stored data.
///
/// Stops the listener and deletes all conversations, messages, and FTS
/// data from the SQLite store. This is a destructive operation.
///
/// # Errors
///
/// Returns [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn messages_bridge_disconnect_and_clear() -> Result<(), FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        messages_bridge::disconnect_and_clear_inner()
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Initiates QR-code pairing with Google Messages.
///
/// Creates or resumes a bridge session at `data_dir`, generates a pairing
/// QR code URL via the Bugle API, and returns it for display. The
/// `data_dir` should be the Android `context.filesDir` path.
///
/// This is a blocking call that runs the async pairing flow on the shared
/// tokio runtime.
///
/// # Errors
///
/// Returns [`FfiError::SpawnError`] if the runtime cannot be created or
/// the pairing RPC call fails, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn messages_bridge_start_pairing(data_dir: String) -> Result<String, FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        messages_bridge::start_pairing_inner(data_dir)
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

// ── Web renderer ────────────────────────────────────────────────────────

/// Registers a Kotlin-side WebView renderer for JavaScript-heavy pages.
///
/// The renderer is called from Rust when the HTTP fetch tool encounters
/// a page that requires client-side JavaScript execution (e.g. Cloudflare
/// challenge, SPA content). Only one renderer can be registered at a time;
/// a new registration replaces the previous one.
///
/// # Errors
///
/// Returns [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn register_web_renderer(renderer: Box<dyn web_renderer::WebRenderer>) -> Result<(), FfiError> {
    let renderer: Arc<dyn web_renderer::WebRenderer> = Arc::from(renderer);
    catch_unwind(AssertUnwindSafe(|| {
        web_renderer::register_web_renderer_inner(renderer)
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Unregisters the current WebView renderer.
///
/// After this call, JavaScript-heavy pages will not be rendered via
/// WebView and the fetch tool will return the raw HTTP response instead.
///
/// # Errors
///
/// Returns [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn unregister_web_renderer() -> Result<(), FfiError> {
    catch_unwind(web_renderer::unregister_web_renderer_inner).unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Sets the device-authentic User-Agent string for the web_fetch tool's
/// reqwest HTTP client. Called once during daemon startup from Kotlin.
///
/// # Errors
///
/// Returns [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn set_web_fetch_user_agent(user_agent: String) -> Result<(), FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        zeroclaw::tools::web_fetch::set_global_user_agent(user_agent);
        Ok(())
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

// ── Twitter browse config ────────────────────────────────────────────────

/// Returns the current twitter browse tool configuration.
///
/// Reads the daemon's in-memory config and returns the enabled state,
/// cookie presence, max items, and timeout as an [`FfiTwitterBrowseConfig`].
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn get_twitter_browse_config() -> Result<twitter::FfiTwitterBrowseConfig, FfiError> {
    catch_unwind(twitter::get_twitter_browse_config_inner).unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Hot-swaps the cookie string on the daemon's in-memory config.
///
/// The change takes effect on the next twitter browse tool invocation.
/// The Kotlin layer is responsible for persisting the cookie to
/// `EncryptedSharedPreferences`.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn set_twitter_browse_cookie(cookie_string: String) -> Result<(), FfiError> {
    catch_unwind(|| twitter::set_twitter_browse_cookie_inner(cookie_string)).unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Clears the cookie from the daemon's in-memory config.
///
/// After this call the twitter browse tool will not have credentials
/// and will fail with an auth error on the next invocation.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn clear_twitter_browse_cookie() -> Result<(), FfiError> {
    catch_unwind(twitter::clear_twitter_browse_cookie_inner).unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Verifies a Twitter cookie string is valid by checking for required cookies.
///
/// Parses the cookie string and validates that it contains the `ct0`
/// and `auth_token` cookies required for authenticated Twitter API
/// access. Extracts the user ID from the `twid` cookie if present.
///
/// Does NOT make a network request; validation is purely syntactic.
///
/// # Errors
///
/// Returns [`FfiError::InvalidArgument`] if required cookies are missing, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn verify_twitter_connection(
    cookie_string: String,
) -> Result<twitter::FfiTwitterUser, FfiError> {
    catch_unwind(|| twitter::verify_twitter_connection_inner(cookie_string)).unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Hot-updates the twitter browse tool config in the daemon's memory.
///
/// Changes `enabled`, `max_items`, and `timeout_secs` in-place without
/// restarting the daemon.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn update_twitter_browse_config(
    enabled: bool,
    max_items: u32,
    timeout_secs: u32,
) -> Result<(), FfiError> {
    catch_unwind(|| twitter::update_twitter_browse_config_inner(enabled, max_items, timeout_secs))
        .unwrap_or_else(|e| {
            Err(FfiError::InternalPanic {
                detail: panic_detail(&e),
            })
        })
}

// ─── ClawBoy: AI-played Game Boy emulator ───────────────────────────

/// Verifies a ROM file against the expected Pokemon Red hash.
///
/// Computes SHA-1 of `data` and compares to the hardcoded hash for
/// Pokemon Red (USA/Europe). Returns a [`RomVerification`](clawboy::types::RomVerification)
/// with the result and computed hash string.
///
/// Does NOT require the daemon to be running.
///
/// # Errors
///
/// Returns [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn clawboy_verify_rom(data: Vec<u8>) -> Result<clawboy::types::RomVerification, FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        Ok(clawboy::emulator::Emulator::verify_rom(&data))
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Starts a ClawBoy emulator session.
///
/// Boots the emulator with the ROM at `rom_path`, starts the WebSocket
/// viewer server, and begins the emulation tick loop. Returns viewer URL
/// and port for browser access. Only one session can run at a time.
///
/// Does NOT require the daemon to be running. Creates a tokio runtime
/// if one is not already active.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if a session is already running,
/// [`FfiError::SpawnError`] if the ROM cannot be read, the emulator
/// fails to initialise, or the server cannot bind, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn clawboy_start_session(
    rom_path: String,
    decision_interval_ms: u64,
    data_dir: String,
    channel_id: Option<String>,
) -> Result<clawboy::types::ClawBoySessionInfo, FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        let handle = runtime::get_or_create_runtime()?;
        let path = std::path::Path::new(&data_dir);
        handle
            .block_on(clawboy::session::start_session(
                rom_path,
                decision_interval_ms,
                path,
                channel_id,
            ))
            .map_err(|e| {
                if e.contains("already running") {
                    FfiError::StateError { detail: e }
                } else {
                    FfiError::SpawnError { detail: e }
                }
            })
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Stops the running ClawBoy emulator session.
///
/// Signals the tick loop to shut down, waits for it to finish saving
/// state, and stops the WebSocket viewer server.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if no session is currently running,
/// [`FfiError::SpawnError`] on shutdown failure, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn clawboy_stop_session() -> Result<(), FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        let handle = runtime::get_or_create_runtime()?;
        handle
            .block_on(clawboy::session::stop_session())
            .map_err(|e| {
                if e.contains("not running") {
                    FfiError::StateError { detail: e }
                } else {
                    FfiError::SpawnError { detail: e }
                }
            })
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Returns the current status of the ClawBoy emulator.
///
/// Returns [`ClawBoyStatus::Idle`](clawboy::types::ClawBoyStatus::Idle)
/// when no session is active,
/// [`ClawBoyStatus::Playing`](clawboy::types::ClawBoyStatus::Playing)
/// when the emulator is running, or
/// [`ClawBoyStatus::Paused`](clawboy::types::ClawBoyStatus::Paused)
/// when the session is paused.
///
/// Does NOT require the daemon to be running.
///
/// # Errors
///
/// Returns [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn clawboy_get_status() -> Result<clawboy::types::ClawBoyStatus, FfiError> {
    catch_unwind(|| Ok(clawboy::session::get_status())).unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Updates the agent decision interval for the running ClawBoy session.
///
/// Phase 2 hook — in Phase 1 the tick loop ignores this value, but
/// the channel is wired so Phase 2 can read it without changes.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if no session is currently running, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn clawboy_set_decision_interval(ms: u64) -> Result<(), FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        clawboy::session::set_decision_interval(ms).map_err(|e| FfiError::StateError { detail: e })
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Pauses the running ClawBoy emulator session.
///
/// The emulator tick loop will sleep instead of advancing frames until
/// [`clawboy_resume_session`] is called. Useful for battery saver or
/// when the user navigates away from the viewer.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if no session is currently running, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn clawboy_pause_session() -> Result<(), FfiError> {
    catch_unwind(|| {
        clawboy::session::pause_session().map_err(|e| FfiError::StateError { detail: e })
    })
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Resumes a paused ClawBoy emulator session.
///
/// The emulator tick loop will resume advancing frames at ~60 fps.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if no session is currently running, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn clawboy_resume_session() -> Result<(), FfiError> {
    catch_unwind(|| {
        clawboy::session::resume_session().map_err(|e| FfiError::StateError { detail: e })
    })
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Notifies the daemon that a verified ROM is ready at the given path.
///
/// Sets the cached ROM-present flag and stores the data directory so
/// that trigger-based session starts can locate the ROM without an
/// explicit path from the UI layer.
///
/// # Errors
///
/// Returns [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn clawboy_notify_rom_ready(data_dir: String) -> Result<(), FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        let path = std::path::Path::new(&data_dir);
        clawboy::session::notify_rom_ready(path);
        Ok(())
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Notifies the daemon that the ClawBoy ROM has been removed.
///
/// Clears the cached ROM-present flag so that trigger checks short-
/// circuit immediately, adding zero overhead to normal message flow.
///
/// # Errors
///
/// Returns [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn clawboy_notify_rom_removed() -> Result<(), FfiError> {
    catch_unwind(|| {
        clawboy::session::notify_rom_removed();
        Ok(())
    })
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Configure the agent's email mailbox.
///
/// Validates the configuration, updates the daemon's runtime config,
/// and syncs the scheduled email-check cron jobs. When `enabled` is
/// `false` in the JSON payload, all existing email-check cron jobs are
/// removed without adding new ones.
///
/// # Errors
///
/// Returns [`FfiError::InvalidArgument`] if the JSON is malformed or check times are invalid.
/// Returns [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn configure_email(config_json: String) -> Result<(), FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        let email_config: zeroclaw::config::EmailConfig = serde_json::from_str(&config_json)
            .map_err(|e| FfiError::InvalidArgument {
                detail: format!("Invalid email config JSON: {e}"),
            })?;

        zeroclaw::config::schema::validate_check_times(&email_config.check_times).map_err(|e| {
            FfiError::InvalidArgument {
                detail: format!("Invalid check times: {e}"),
            }
        })?;

        // Update daemon config.
        runtime::with_daemon_config_mut(|config| {
            config.email = Some(email_config.clone());
        })?;

        // Sync cron jobs.
        if email_config.enabled {
            email_cron::sync_email_cron_jobs(
                &email_config.check_times,
                email_config.timezone.as_deref(),
            )?;
        } else {
            // Remove all email check jobs when disabled.
            email_cron::sync_email_cron_jobs(&[], None)?;
        }

        Ok(())
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Test IMAP and SMTP connectivity for the given email configuration.
///
/// Returns a status string like `"IMAP: OK\nSMTP: OK"` or error details
/// per protocol. Uses a 10-second timeout for each test for UI
/// responsiveness.
///
/// # Errors
///
/// Returns [`FfiError::InvalidArgument`] if the JSON is malformed.
/// Returns [`FfiError::SpawnError`] if the runtime cannot be created.
/// Returns [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn test_email_connection(config_json: String) -> Result<String, FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        let config: zeroclaw::config::EmailConfig =
            serde_json::from_str(&config_json).map_err(|e| FfiError::InvalidArgument {
                detail: format!("Invalid email config JSON: {e}"),
            })?;

        let handle = runtime::get_or_create_runtime()?;

        handle.block_on(async {
            let mut results: Vec<String> = Vec::new();

            match tokio::time::timeout(
                std::time::Duration::from_secs(10),
                zeroclaw::tools::email::client::EmailClient::test_imap(
                    &config.imap_host,
                    config.imap_port,
                    &config.address,
                    &config.password,
                ),
            )
            .await
            {
                Ok(Ok(())) => results.push("IMAP: OK".to_string()),
                Ok(Err(e)) => results.push(format!("IMAP: Failed - {e}")),
                Err(_) => results.push("IMAP: Timed out (10s)".to_string()),
            }

            match tokio::time::timeout(
                std::time::Duration::from_secs(10),
                zeroclaw::tools::email::client::EmailClient::test_smtp(
                    &config.smtp_host,
                    config.smtp_port,
                    &config.address,
                    &config.password,
                ),
            )
            .await
            {
                Ok(Ok(())) => results.push("SMTP: OK".to_string()),
                Ok(Err(e)) => results.push(format!("SMTP: Failed - {e}")),
                Err(_) => results.push("SMTP: Timed out (10s)".to_string()),
            }

            Ok(results.join("\n"))
        })
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

// ── Tailnet discovery ────────────────────────────────────────────────

/// Attempts to query the Tailscale local API for peer auto-discovery.
///
/// Hits the daemon-local HTTP API at `100.100.100.100` to retrieve
/// tailnet membership, this device's IP, and all online peers.
///
/// # Errors
///
/// Returns [`FfiError::NetworkError`] if the Tailscale daemon is
/// unreachable or returns an unexpected response, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn tailnet_auto_discover() -> Result<tailnet::TailnetAutoDiscoverResult, FfiError> {
    catch_unwind(AssertUnwindSafe(tailnet::tailnet_auto_discover_inner)).unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Probes a list of peer addresses for known services (Ollama, zeroclaw).
///
/// Each entry can be a bare IP (`"100.78.1.2"`) or `ip:port`
/// (`"100.78.1.2:8080"`). A bare IP probes Ollama on 11434 and zeroclaw
/// on 42617 (ZeroAI default). An explicit port overrides the zeroclaw
/// probe port; Ollama always uses its standard port.
///
/// # Errors
///
/// Returns [`FfiError::NetworkError`] if the HTTP client cannot be
/// built, or [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn tailnet_probe_services(
    peer_addresses: Vec<String>,
) -> Result<Vec<tailnet::TailnetPeer>, FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        tailnet::tailnet_probe_services_inner(peer_addresses)
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Sends a message to a peer agent and returns the response text.
///
/// # Blocking
///
/// This function performs a synchronous HTTP request and may block for
/// up to 63 seconds (3s connect + 60s response timeout). Callers
/// **must** invoke from a background dispatcher (`Dispatchers.IO`).
/// Never call from the main thread.
///
/// # Errors
///
/// - [`FfiError::InvalidArgument`] — malformed IP address or unsupported peer kind
/// - [`FfiError::NetworkError`] — connection failure, timeout, or malformed response
/// - [`FfiError::InternalPanic`] — unexpected internal panic (caught)
#[uniffi::export]
pub fn peer_send_message(
    ip: String,
    port: u16,
    kind: tailnet::TailnetServiceKind,
    token: Option<String>,
    message: String,
) -> Result<String, FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        tailnet::peer_send_message_inner(ip, port, kind, token, message)
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Sends a formatted response back through a Rust-managed channel.
///
/// Called by Kotlin after peer message handling to relay the response
/// through the originating channel (Telegram, Discord, or CLI).
///
/// # Errors
///
/// - [`FfiError::StateError`] — daemon is not running
/// - [`FfiError::NetworkError`] — channel dispatch failure
/// - [`FfiError::InternalPanic`] — unexpected internal panic (caught)
#[uniffi::export]
pub fn peer_send_channel_response(
    channel: tailnet::PeerChannelKind,
    recipient: String,
    message: String,
) -> Result<(), FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        tailnet::peer_send_channel_response_inner(channel, recipient, message)
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

// ---------------------------------------------------------------------------
// Capability grants
// ---------------------------------------------------------------------------

/// Returns all pending capability approval requests.
///
/// Kotlin UI should poll this to discover new approval prompts, then call
/// [`resolve_capability_request`] with the user's decision.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the internal lock is poisoned, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn get_pending_approvals() -> Result<Vec<capability_grants::PendingApprovalInfo>, FfiError> {
    catch_unwind(capability_grants::get_pending_approvals_inner).unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Resolves a pending capability approval request.
///
/// Sends the user's decision (`approved`) back to the waiting Rust task.
/// If the request ID is not found (already resolved or expired), returns
/// [`FfiError::InvalidArgument`].
///
/// # Errors
///
/// Returns [`FfiError::InvalidArgument`] if `request_id` is unknown,
/// [`FfiError::StateError`] if the internal lock is poisoned, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn resolve_capability_request(request_id: String, approved: bool) -> Result<(), FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        capability_grants::resolve_capability_request_inner(request_id, approved)
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Lists all persisted capability grants from the workspace grants file.
///
/// `data_dir` is the absolute path to the app's files directory (typically
/// `context.filesDir` from Kotlin). The grants file is read from
/// `<data_dir>/capability_grants.json`.
///
/// # Errors
///
/// Returns [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn list_capability_grants(
    data_dir: String,
) -> Result<Vec<capability_grants::CapabilityGrantInfo>, FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        let path = std::path::Path::new(&data_dir);
        capability_grants::list_capability_grants_inner(path)
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Revokes a persisted capability grant for a specific skill and capability.
///
/// `data_dir` is the absolute path to the app's files directory. The grants
/// file at `<data_dir>/capability_grants.json` is updated atomically.
/// If the grant does not exist, this is a no-op.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] on I/O or serialisation failure, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn revoke_capability_grant(
    data_dir: String,
    skill_name: String,
    capability: String,
) -> Result<(), FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        let path = std::path::Path::new(&data_dir);
        capability_grants::revoke_capability_grant_inner(path, &skill_name, &capability)
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

// ── TTY (local shell) ────────────────────────────────────────────────

/// Creates a new local PTY shell session.
///
/// Opens a PTY pair, forks `/system/bin/sh`, and starts async
/// read/write loops. Only one session can be active at a time.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if a session is already running,
/// [`FfiError::SpawnError`] if PTY creation or fork fails, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn tty_create(cols: u32, rows: u32) -> Result<(), FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        tty::session::create(cols as u16, rows as u16)
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Destroys the running local shell PTY session.
///
/// Sends `SIGHUP` then `SIGKILL` to the child process and closes
/// the master fd. Idempotent — returns `Ok` if no session is running.
///
/// # Errors
///
/// Returns [`FfiError::SpawnError`] if signal delivery fails, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn tty_destroy() -> Result<(), FfiError> {
    catch_unwind(AssertUnwindSafe(|| tty::session::destroy())).unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Writes raw bytes to the PTY input (non-blocking).
///
/// Sends `bytes` through the mpsc channel to the write loop. If the
/// channel is full (backpressure), returns an error.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if no session is running,
/// [`FfiError::SpawnError`] if the write channel is full/closed, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn tty_write(bytes: Vec<u8>) -> Result<(), FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        if tty::ssh::is_connected() {
            tty::ssh::write_bytes(bytes)
        } else {
            tty::session::write_bytes(bytes)
        }
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Resizes the PTY to the given dimensions.
///
/// Uses the `TIOCSWINSZ` ioctl. `width_px` and `height_px` are
/// reserved for future pixel-dimension support and currently unused.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if no session is running,
/// [`FfiError::SpawnError`] if the ioctl fails, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn tty_resize(cols: u32, rows: u32, width_px: u32, height_px: u32) -> Result<(), FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        if tty::ssh::is_connected() {
            tty::ssh::resize(cols as u16, rows as u16)
        } else {
            let result = tty::session::resize(cols as u16, rows as u16);
            // Update mouse encoder geometry (best-effort, ignore errors).
            let _ = tty::session::set_mouse_geometry(
                cols as u16,
                rows as u16,
                width_px,
                height_px,
            );
            result
        }
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Returns the last `max_lines` output lines from the PTY session.
///
/// Lines are returned oldest-first with ANSI escape sequences stripped.
/// If fewer than `max_lines` are available, all lines are returned.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if no session is running, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn tty_get_output_snapshot(max_lines: u32) -> Result<Vec<String>, FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        if tty::ssh::has_session() {
            tty::ssh::get_output_lines(max_lines)
        } else {
            tty::session::get_output_lines(max_lines)
        }
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Returns recent PTY output as a single scrubbed string for LLM
/// context injection.
///
/// Credentials are redacted and the result is capped at `max_bytes`
/// (defaults to 64 KiB when `None`). Oldest lines are truncated
/// first to fit the budget.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if no session is running, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn tty_get_context(max_bytes: Option<u32>) -> Result<String, FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        let limit = max_bytes.unwrap_or(65_536) as usize;
        tty::session::get_context(limit)
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Returns a complete render frame from the terminal backend.
///
/// Captures the current screen grid from the local shell session,
/// converts it to a [`tty::types::TtyRenderFrame`] that is ready for
/// Kotlin Canvas drawing, and returns it across the FFI boundary.
///
/// Colors are packed ARGB (`0xAARRGGBB`). A value of `0x00000000` for
/// a span's foreground or background means "use the terminal default"
/// and must not be interpreted as opaque black.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if no session is running, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn tty_get_render_frame() -> Result<tty::types::TtyRenderFrame, FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        if tty::ssh::has_session() {
            tty::ssh::get_render_frame()
        } else {
            tty::session::get_render_frame()
        }
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Blocks until new terminal render data is available or timeout expires.
///
/// Returns `true` if render data became available, `false` on timeout.
/// Designed to replace the 100ms polling loop with event-driven updates.
///
/// # Errors
///
/// Returns [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn tty_wait_for_render_signal(timeout_ms: u64) -> Result<bool, FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        Ok(tty::session::wait_for_render_signal(timeout_ms))
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Applies a color theme to the active terminal session.
///
/// `bg`, `fg`, `cursor` are packed ARGB (`0xAARRGGBB`). `palette`
/// must contain exactly 16 entries (ANSI colors 0-15).
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if no session is running,
/// [`FfiError::InvalidArgument`] if palette length is wrong, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn tty_set_palette(
    bg: u32,
    fg: u32,
    cursor: u32,
    palette: Vec<u32>,
) -> Result<(), FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        if palette.len() != 16 {
            return Err(FfiError::InvalidArgument {
                detail: format!(
                    "palette must have 16 entries, got {}",
                    palette.len()
                ),
            });
        }
        if tty::ssh::has_session() {
            tty::ssh::set_palette(bg, fg, cursor, &palette)
        } else {
            tty::session::set_palette(bg, fg, cursor, &palette)
        }
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Initializes the SSH key store directory.
///
/// Creates the directory and parents if absent. Idempotent with the
/// same path; returns [`FfiError::StateError`] if called with a
/// different path.
///
/// # Errors
///
/// Returns [`FfiError::IoError`] if directory creation fails, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn ssh_key_store_init(keys_dir: String) -> Result<(), FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        let keys_path = std::path::PathBuf::from(&keys_dir);
        tty::key_store::init(keys_path.clone())?;
        let hosts_path = keys_path
            .parent()
            .unwrap_or(&keys_path)
            .join("known_hosts.json");
        tty::known_hosts::init(hosts_path)
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Generates a new SSH keypair and stores the private key on disk.
///
/// Returns metadata including the `key_id` needed for future
/// operations. The private key never crosses the FFI boundary.
///
/// # Errors
///
/// Returns [`FfiError::IoError`] if key generation or file write
/// fails, or [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn ssh_generate_key(
    algorithm: tty::types::SshKeyAlgorithm,
    label: String,
) -> Result<tty::types::SshKeyMetadata, FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        tty::key_store::generate(algorithm, &label)
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Imports a private key from a file on disk.
///
/// The source file is **unconditionally deleted** on both success
/// and error paths. Passphrase is zeroed after use.
///
/// # Errors
///
/// Returns [`FfiError::IoError`] if the file cannot be read or
/// parsed, or [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn ssh_import_key(
    file_path: String,
    passphrase: Option<Vec<u8>>,
    label: String,
) -> Result<tty::types::SshKeyMetadata, FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        tty::key_store::import_file(
            std::path::Path::new(&file_path),
            passphrase,
            &label,
        )
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Deletes an SSH key from disk. Idempotent.
///
/// # Errors
///
/// Returns [`FfiError::IoError`] if file deletion fails, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn ssh_delete_key(key_id: String) -> Result<(), FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        tty::key_store::delete(&key_id)
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Returns the public key in OpenSSH format for the given key ID.
///
/// # Errors
///
/// Returns [`FfiError::InvalidArgument`] if the key is not found,
/// or [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn ssh_export_public_key(key_id: String) -> Result<String, FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        tty::key_store::export_public(&key_id)
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Checks whether a key file exists on disk for the given ID.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the key store is not
/// initialized, or [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn ssh_key_exists(key_id: String) -> Result<bool, FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        tty::key_store::key_exists(&key_id)
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Lists all key IDs in the key store directory.
///
/// # Errors
///
/// Returns [`FfiError::IoError`] if the directory cannot be read,
/// or [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn ssh_list_key_ids() -> Result<Vec<String>, FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        tty::key_store::list_key_ids()
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Starts an SSH connection to the given host.
///
/// Initiates the SSH handshake and authentication flow. Use
/// [`tty_submit_password`] or [`tty_submit_key`] to supply credentials
/// after this call returns, and [`tty_get_pending_host_key`] to handle
/// unknown-host prompts.
///
/// # Errors
///
/// Returns [`FfiError::IoError`] if the connection fails, or
/// [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn tty_start_ssh(host: String, port: u32, user: String) -> Result<(), FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        tty::ssh::start_ssh(&host, port as u16, &user)
    }))
    .unwrap_or_else(|e| Err(FfiError::InternalPanic { detail: panic_detail(&e) }))
}

/// Submits a password for the pending SSH authentication challenge.
///
/// Returns `true` if authentication succeeded, `false` if it failed.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if there is no pending auth challenge,
/// or [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn tty_submit_password(password: Vec<u8>) -> Result<bool, FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        tty::ssh::submit_password(password)
    }))
    .unwrap_or_else(|e| Err(FfiError::InternalPanic { detail: panic_detail(&e) }))
}

/// Submits a stored SSH key for the pending authentication challenge.
///
/// Returns `true` if authentication succeeded, `false` if it failed.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if there is no pending auth challenge or
/// the key ID is not found, or [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn tty_submit_key(key_id: String) -> Result<bool, FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        tty::ssh::submit_key(&key_id)
    }))
    .unwrap_or_else(|e| Err(FfiError::InternalPanic { detail: panic_detail(&e) }))
}

/// Disconnects the active SSH session.
///
/// Closes the SSH channel and underlying TCP connection. No-op if no
/// SSH session is currently active.
///
/// # Errors
///
/// Returns [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn tty_disconnect_ssh() -> Result<(), FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        tty::ssh::disconnect()
    }))
    .unwrap_or_else(|e| Err(FfiError::InternalPanic { detail: panic_detail(&e) }))
}

/// Returns the pending host-key verification prompt, if any.
///
/// Returns `Some` when the SSH handshake has produced an unknown or
/// changed host key that the user must accept or reject before
/// authentication can continue. Call [`tty_answer_host_key`] to respond.
///
/// # Errors
///
/// Returns [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn tty_get_pending_host_key() -> Result<Option<tty::types::TtyHostKeyPrompt>, FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        Ok(tty::ssh::get_pending_host_key())
    }))
    .unwrap_or_else(|e| Err(FfiError::InternalPanic { detail: panic_detail(&e) }))
}

/// Accepts or rejects the pending SSH host-key verification prompt.
///
/// Pass [`TtyHostKeyDecision::Accept`] to trust the key and continue,
/// or [`TtyHostKeyDecision::Reject`] to abort the connection.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if there is no pending host-key prompt,
/// or [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn tty_answer_host_key(decision: tty::types::TtyHostKeyDecision) -> Result<(), FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        tty::ssh::answer_host_key(decision == tty::types::TtyHostKeyDecision::Accept)
    }))
    .unwrap_or_else(|e| Err(FfiError::InternalPanic { detail: panic_detail(&e) }))
}

/// Returns the current SSH connection state.
///
/// Returns [`SshState::Disconnected`] when no SSH session exists.
///
/// # Errors
///
/// Returns [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn tty_get_ssh_state() -> Result<tty::types::SshState, FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        Ok(tty::ssh::get_state())
    }))
    .unwrap_or_else(|e| Err(FfiError::InternalPanic { detail: panic_detail(&e) }))
}

/// Encodes a special key into terminal escape bytes.
///
/// Used by `TtyKeyRow` for keys that cannot be expressed as UTF-8
/// text (arrows, Tab, Escape, function keys, Ctrl combinations).
///
/// Supported key names: `tab`, `escape`, `enter`, `backspace`,
/// `delete`, `up`, `down`, `left`, `right`, `home`, `end`,
/// `page_up`, `page_down`, `f1`-`f12`.
///
/// Modifier flags: bit 0 = Ctrl, bit 1 = Alt, bit 2 = Shift.
///
/// Returns the encoded escape sequence bytes, or an empty vec for
/// unrecognised keys.
///
/// # Errors
///
/// Returns [`FfiError::InternalPanic`] if native code panics.
#[uniffi::export]
pub fn tty_encode_special_key(key_name: String, modifier_flags: u32) -> Result<Vec<u8>, FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        Ok(encode_special_key(&key_name, modifier_flags))
    }))
    .unwrap_or_else(|e| Err(FfiError::InternalPanic { detail: panic_detail(&e) }))
}

/// Returns whether the given text is safe to paste without user confirmation.
///
/// Delegates to `ghostty_paste_is_safe` from the vendored C library.
/// Returns `Ok(false)` (treat as unsafe) if the C call panics.
///
/// # Errors
///
/// Returns [`FfiError::InternalPanic`] if a panic is caught.
#[uniffi::export]
pub fn tty_is_paste_safe(text: String) -> Result<bool, FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        #[cfg(feature = "ghostty-vt")]
        {
            let bytes = text.as_bytes();
            // SAFETY: `text` is a valid UTF-8 String. `as_bytes()` returns
            // a pointer to the string's backing buffer with correct length.
            // The string is not moved or dropped during the call.
            // ghostty_paste_is_safe does not retain the pointer.
            let safe = unsafe {
                tty::ghostty_sys::ghostty_paste_is_safe(bytes.as_ptr(), bytes.len())
            };
            Ok(safe)
        }
        #[cfg(not(feature = "ghostty-vt"))]
        {
            // Without the ghostty-vt C library, perform a conservative
            // pure-Rust check: treat text as unsafe if it contains a
            // newline or the bracketed-paste-end sequence.
            let safe = !text.contains('\n')
                && !text.contains('\r')
                && !text.contains("\x1b[201~");
            Ok(safe)
        }
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Returns whether bracketed paste mode (DEC 2004) is active in the
/// current terminal session.
///
/// When active, paste content must be wrapped in `\x1b[200~` …
/// `\x1b[201~` before being sent to the PTY. Returns `Ok(false)` when
/// no session is running (safe default — paste without brackets is always
/// accepted by the shell).
///
/// # Errors
///
/// Returns [`FfiError::InternalPanic`] if a panic is caught.
#[uniffi::export]
pub fn tty_is_bracketed_paste_active() -> Result<bool, FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        if tty::ssh::has_session() {
            // SSH backend: delegate to SSH session's bracketed paste state.
            tty::ssh::is_bracketed_paste_active()
        } else {
            tty::session::is_bracketed_paste_active()
        }
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Returns whether mouse tracking is currently active in the
/// terminal session.
///
/// Returns `Ok(false)` when no session exists (safe default:
/// selection gestures remain active).
///
/// # Errors
///
/// Returns [`FfiError::InternalPanic`] if a panic is caught.
#[uniffi::export]
pub fn tty_is_mouse_tracking_active() -> Result<bool, FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        if tty::ssh::has_session() {
            Ok(false)
        } else {
            tty::session::is_mouse_tracking_active()
        }
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Encodes a mouse event and writes the escape sequence to the PTY.
///
/// Fire-and-forget: errors are logged at appropriate levels but not
/// surfaced to the Kotlin UI.
///
/// # Errors
///
/// Returns [`FfiError::InternalPanic`] if a panic is caught.
#[uniffi::export]
pub fn tty_submit_mouse_event(
    action: u8,
    button: u8,
    pixel_x: f32,
    pixel_y: f32,
    mods: u32,
) -> Result<(), FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        let result = if tty::ssh::is_connected() {
            Ok(())
        } else {
            tty::session::submit_mouse_event(action, button, pixel_x, pixel_y, mods)
        };

        match &result {
            Err(FfiError::StateError { .. }) => {
                tracing::debug!(target: "tty", "mouse event ignored: no session");
            }
            Err(FfiError::SpawnError { detail }) => {
                tracing::warn!(target: "tty", "mouse event channel pressure: {detail}");
            }
            _ => {}
        }
        result
    }))
    .unwrap_or_else(|e| {
        tracing::error!(target: "tty", "panic in tty_submit_mouse_event");
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

/// Maps a key name + modifier flags to terminal escape bytes.
///
/// Uses standard xterm/VT escape sequences. Ctrl combos are
/// handled by converting to the corresponding control character.
fn encode_special_key(key_name: &str, modifier_flags: u32) -> Vec<u8> {
    let ctrl = modifier_flags & 0x01 != 0;
    let alt = modifier_flags & 0x02 != 0;

    // Ctrl+letter → control character (e.g. Ctrl+C → 0x03).
    if ctrl && key_name.len() == 1 {
        let ch = key_name.as_bytes()[0];
        if ch.is_ascii_alphabetic() {
            let ctrl_char = (ch.to_ascii_uppercase() - b'A') + 1;
            return if alt {
                vec![0x1b, ctrl_char]
            } else {
                vec![ctrl_char]
            };
        }
    }

    let base: &[u8] = match key_name {
        "tab" => b"\x09",
        "escape" | "esc" => b"\x1b",
        "enter" | "return" => b"\r",
        "backspace" => b"\x7f",
        "delete" => b"\x1b[3~",
        "up" => b"\x1b[A",
        "down" => b"\x1b[B",
        "right" => b"\x1b[C",
        "left" => b"\x1b[D",
        "home" => b"\x1b[H",
        "end" => b"\x1b[F",
        "page_up" => b"\x1b[5~",
        "page_down" => b"\x1b[6~",
        "insert" => b"\x1b[2~",
        "f1" => b"\x1bOP",
        "f2" => b"\x1bOQ",
        "f3" => b"\x1bOR",
        "f4" => b"\x1bOS",
        "f5" => b"\x1b[15~",
        "f6" => b"\x1b[17~",
        "f7" => b"\x1b[18~",
        "f8" => b"\x1b[19~",
        "f9" => b"\x1b[20~",
        "f10" => b"\x1b[21~",
        "f11" => b"\x1b[23~",
        "f12" => b"\x1b[24~",
        _ => return Vec::new(),
    };

    if alt {
        // Alt prefix: ESC before the sequence.
        let mut result = vec![0x1b];
        result.extend_from_slice(base);
        result
    } else {
        base.to_vec()
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    #[test]
    fn test_get_version() {
        let version = get_version().unwrap();
        assert_eq!(version, env!("CARGO_PKG_VERSION"));
    }

    #[test]
    fn test_start_daemon_invalid_toml() {
        let result = start_daemon(
            "this is not valid toml {{{{".to_string(),
            "/tmp/test".to_string(),
            "127.0.0.1".to_string(),
            8080,
        );
        assert!(result.is_err());
        match result.unwrap_err() {
            FfiError::ConfigError { detail } => {
                assert!(detail.contains("failed to parse config TOML"));
            }
            other => panic!("expected ConfigError, got {other:?}"),
        }
    }

    #[test]
    fn test_stop_daemon_not_running() {
        let result = stop_daemon();
        assert!(result.is_err());
        match result.unwrap_err() {
            FfiError::StateError { detail } => {
                assert!(detail.contains("not running"));
            }
            other => panic!("expected StateError, got {other:?}"),
        }
    }

    #[test]
    fn test_send_message_not_running() {
        let result = send_message("hello".to_string());
        assert!(result.is_err());
        match result.unwrap_err() {
            FfiError::StateError { detail } => {
                assert!(detail.contains("not running"));
            }
            other => panic!("expected StateError, got {other:?}"),
        }
    }

    #[test]
    fn test_get_status_returns_json() {
        let status = get_status().unwrap();
        let parsed: serde_json::Value = serde_json::from_str(&status).unwrap();
        assert!(parsed.get("daemon_running").is_some());
    }

    #[test]
    fn test_validate_config_valid() {
        let toml = "default_temperature = 0.7\n";
        let result = validate_config(toml.to_string()).unwrap();
        assert!(
            result.is_empty(),
            "expected empty string for valid config, got: {result}"
        );
    }

    #[test]
    fn test_validate_config_invalid() {
        let toml = "this is not valid {{{{";
        let result = validate_config(toml.to_string()).unwrap();
        assert!(
            !result.is_empty(),
            "expected non-empty error message for invalid config"
        );
    }

    #[test]
    fn test_doctor_channels_invalid_toml() {
        let result = doctor_channels("not valid {{".to_string(), "/tmp/test".to_string());
        assert!(result.is_err());
        match result.unwrap_err() {
            FfiError::ConfigError { detail } => {
                assert!(detail.contains("failed to parse config TOML"));
            }
            other => panic!("expected ConfigError, got {other:?}"),
        }
    }

    #[test]
    fn test_get_configured_channel_names_no_daemon() {
        let result = get_configured_channel_names();
        assert!(result.is_err());
        match result.unwrap_err() {
            FfiError::StateError { detail } => {
                assert!(detail.contains("daemon not running"));
            }
            other => panic!("expected StateError, got {other:?}"),
        }
    }

    #[test]
    fn test_scaffold_workspace_creates_files() {
        let dir = std::env::temp_dir().join("zeroclaw_test_scaffold");
        let _ = std::fs::remove_dir_all(&dir);

        let result = scaffold_workspace(
            dir.to_string_lossy().to_string(),
            "TestAgent".to_string(),
            "TestUser".to_string(),
            "America/New_York".to_string(),
            String::new(),
        );
        assert!(result.is_ok());

        for subdir in &["sessions", "memory", "state", "cron", "skills"] {
            assert!(dir.join(subdir).is_dir(), "missing directory: {subdir}");
        }

        let expected_files = [
            "IDENTITY.md",
            "AGENTS.md",
            "HEARTBEAT.md",
            "SOUL.md",
            "USER.md",
            "TOOLS.md",
            "BOOTSTRAP.md",
            "MEMORY.md",
        ];
        for filename in &expected_files {
            assert!(dir.join(filename).is_file(), "missing file: {filename}");
        }

        let identity = std::fs::read_to_string(dir.join("IDENTITY.md")).unwrap();
        assert!(identity.contains("TestAgent"));

        let user_md = std::fs::read_to_string(dir.join("USER.md")).unwrap();
        assert!(user_md.contains("TestUser"));
        assert!(user_md.contains("America/New_York"));

        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn test_scaffold_workspace_idempotent() {
        let dir = std::env::temp_dir().join("zeroclaw_test_idem");
        let _ = std::fs::remove_dir_all(&dir);

        scaffold_workspace(
            dir.to_string_lossy().to_string(),
            "Agent1".to_string(),
            String::new(),
            String::new(),
            String::new(),
        )
        .unwrap();

        scaffold_workspace(
            dir.to_string_lossy().to_string(),
            "Agent2".to_string(),
            String::new(),
            String::new(),
            String::new(),
        )
        .unwrap();

        let identity = std::fs::read_to_string(dir.join("IDENTITY.md")).unwrap();
        assert!(
            identity.contains("Agent1"),
            "existing file should not be overwritten"
        );
        assert!(!identity.contains("Agent2"));

        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn test_scaffold_workspace_defaults() {
        let dir = std::env::temp_dir().join("zeroclaw_test_defaults");
        let _ = std::fs::remove_dir_all(&dir);

        scaffold_workspace(
            dir.to_string_lossy().to_string(),
            String::new(),
            String::new(),
            String::new(),
            String::new(),
        )
        .unwrap();

        let identity = std::fs::read_to_string(dir.join("IDENTITY.md")).unwrap();
        assert!(identity.contains("ZeroAI"), "default agent name");

        let user_md = std::fs::read_to_string(dir.join("USER.md")).unwrap();
        assert!(user_md.contains("**Name:** User"), "default user name");
        assert!(user_md.contains("**Timezone:** UTC"), "default timezone");

        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn test_bind_channel_identity_no_daemon() {
        let result = bind_channel_identity("telegram".into(), "alice".into());
        assert!(result.is_err());
        match result.unwrap_err() {
            FfiError::StateError { detail } => assert!(detail.contains("not running")),
            other => panic!("unexpected: {other:?}"),
        }
    }

    #[test]
    fn test_get_channel_allowlist_no_daemon() {
        let result = get_channel_allowlist("telegram".into());
        assert!(result.is_err());
        match result.unwrap_err() {
            FfiError::StateError { detail } => assert!(detail.contains("not running")),
            other => panic!("unexpected: {other:?}"),
        }
    }

    #[test]
    fn test_list_auth_profiles_no_daemon() {
        let result = list_auth_profiles();
        assert!(result.is_err());
        match result.unwrap_err() {
            FfiError::StateError { detail } => assert!(detail.contains("not running")),
            other => panic!("unexpected: {other:?}"),
        }
    }

    #[test]
    fn test_remove_auth_profile_no_daemon() {
        let result = remove_auth_profile("openai".into(), "default".into());
        assert!(result.is_err());
        match result.unwrap_err() {
            FfiError::StateError { detail } => assert!(detail.contains("not running")),
            other => panic!("unexpected: {other:?}"),
        }
    }

    #[test]
    fn test_get_provider_supports_vision_no_daemon() {
        let result = get_provider_supports_vision();
        assert!(result.is_err());
        match result.unwrap_err() {
            FfiError::StateError { detail } => {
                assert!(detail.contains("not running"));
            }
            other => panic!("expected StateError, got {other:?}"),
        }
    }

    #[test]
    fn test_discover_models_anthropic() {
        let result = discover_models("anthropic".into(), String::new(), None).unwrap();
        let parsed: Vec<serde_json::Value> = serde_json::from_str(&result).unwrap();
        assert!(!parsed.is_empty());
        assert!(parsed[0].get("id").is_some());
        assert!(parsed[0].get("name").is_some());
    }

    #[test]
    fn test_panic_detail_str_payload() {
        let payload: Box<dyn std::any::Any + Send> = Box::new("boom");
        assert_eq!(panic_detail(&payload), "boom");
    }

    #[test]
    fn test_panic_detail_string_payload() {
        let payload: Box<dyn std::any::Any + Send> = Box::new(String::from("kaboom"));
        assert_eq!(panic_detail(&payload), "kaboom");
    }

    #[test]
    fn test_panic_detail_unknown_payload() {
        let payload: Box<dyn std::any::Any + Send> = Box::new(42_i32);
        assert_eq!(panic_detail(&payload), "unknown panic");
    }

    #[test]
    fn test_catch_unwind_returns_internal_panic() {
        let result: Result<(), FfiError> = std::panic::catch_unwind(|| -> Result<(), FfiError> {
            panic!("test panic for FFI boundary");
        })
        .unwrap_or_else(|e| {
            Err(FfiError::InternalPanic {
                detail: panic_detail(&e),
            })
        });
        match result.unwrap_err() {
            FfiError::InternalPanic { detail } => {
                assert!(detail.contains("test panic for FFI boundary"));
            }
            other => panic!("expected InternalPanic, got {other:?}"),
        }
    }

    #[test]
    fn test_operational_after_caught_panic() {
        let panic_result: Result<String, FfiError> =
            std::panic::catch_unwind(|| -> Result<String, FfiError> {
                panic!("simulated panic");
            })
            .unwrap_or_else(|e| {
                Err(FfiError::InternalPanic {
                    detail: panic_detail(&e),
                })
            });
        assert!(panic_result.is_err());

        let version = get_version().unwrap();
        assert_eq!(version, env!("CARGO_PKG_VERSION"));
    }
}
