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
mod android_local_tools;
mod auth_profiles;
mod capability_grants;
mod cost;
mod credentials;
mod cron;
mod discord;
mod email;
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
mod repl_args;
mod runtime;
mod runtime_channels;
mod session;
mod session_history;
mod session_persistence;
mod session_registry;
mod session_text;
mod session_tool_specs;
mod session_tools;
mod shared_folder;
mod skills;
mod skills_community;
mod skills_install;
mod skills_loader;
mod skills_parser;
mod streaming;
mod tools_browse;
mod traces;
mod twitter;
mod twitter_browse_tool;
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


pub use error::FfiError;

/// Generates a `#[uniffi::export]` wrapper that delegates to an inner
/// fn, with `catch_unwind` panic isolation baked in.
///
/// Every FFI export in this crate has the same three-part shape:
///   1. `#[uniffi::export]` attribute
///   2. `pub fn NAME(ARGS) -> Result<RET, FfiError>` signature
///   3. `catch_unwind(AssertUnwindSafe(|| inner(ARGS)))` body that
///      converts a Rust panic into [`FfiError::InternalPanic`]
///
/// This macro generates all three from a single line. Use only when the
/// body is a direct call to one inner fn — if the wrapper needs inline
/// logic (history mapping, arg coercion, etc.) keep it hand-written.
///
/// `#[macro_export]` is applied so per-domain submodules can invoke the
/// macro as part of the lib.rs decomposition effort. The "export" here
/// is benign — `zeroclaw-ffi` has no external Rust consumers; Kotlin
/// only sees the UniFFI-generated bindings, never the macro itself.
///
/// # Example
/// ```ignore
/// /// Starts the daemon.
/// ffi_export!(fn start_daemon(
///     config_toml: String, data_dir: String, host: String, port: u16,
/// ) -> () = runtime::start_daemon_inner);
/// ```
#[macro_export]
macro_rules! ffi_export {
    ($(#[$attr:meta])* fn $name:ident() -> $ret:ty = $inner:path) => {
        $(#[$attr])*
        #[uniffi::export]
        pub fn $name() -> Result<$ret, $crate::FfiError> {
            ::std::panic::catch_unwind(::std::panic::AssertUnwindSafe(|| $inner()))
                .unwrap_or_else(|e| Err($crate::FfiError::InternalPanic {
                    detail: $crate::panic_detail(&e),
                }))
        }
    };
    ($(#[$attr:meta])* fn $name:ident($($arg:ident: $ty:ty),+ $(,)?) -> $ret:ty = $inner:path) => {
        $(#[$attr])*
        #[uniffi::export]
        // The generated wrapper mirrors its inner function's parameter
        // list verbatim — wide UniFFI surfaces are intentional here.
        #[allow(clippy::too_many_arguments)]
        pub fn $name($($arg: $ty),+) -> Result<$ret, $crate::FfiError> {
            ::std::panic::catch_unwind(::std::panic::AssertUnwindSafe(move || $inner($($arg),+)))
                .unwrap_or_else(|e| Err($crate::FfiError::InternalPanic {
                    detail: $crate::panic_detail(&e),
                }))
        }
    };
}

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

// Daemon lifecycle + health exports live in runtime.rs and health.rs.

/// Check if on-device Gemini Nano is available for agent scripting.
#[uniffi::export]
pub fn is_nano_available() -> bool {
    std::panic::catch_unwind(crate::runtime::is_nano_available_inner).unwrap_or(false)
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;
    use crate::auth_profiles::{list_auth_profiles, remove_auth_profile};
    use crate::models::discover_models;
    use crate::vision::get_provider_supports_vision;
    use crate::runtime::{
        bind_channel_identity, doctor_channels, get_channel_allowlist,
        get_configured_channel_names, get_status, get_version, send_message, start_daemon,
        stop_daemon, validate_config,
    };
    use crate::workspace::scaffold_workspace;

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
