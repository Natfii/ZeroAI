// Copyright (c) 2026 @Natfii. All rights reserved.

//! SSH terminal client subsystem.
//!
//! Provides a virtual terminal backend, SSH connection state types,
//! a native-window handle wrapper for GPU-accelerated rendering,
//! and a PTY-based local shell session.

use std::sync::OnceLock;

pub(crate) mod backend;
pub(crate) mod context;
#[cfg(feature = "ghostty-vt")]
pub(crate) mod ghostty_backend;
#[cfg(feature = "ghostty-vt")]
pub(crate) mod ghostty_bridge;
#[cfg(feature = "ghostty-vt")]
#[allow(non_camel_case_types, dead_code)]
pub(crate) mod ghostty_sys;
pub(crate) mod key_store;
pub(crate) mod known_hosts;
pub(crate) mod native_window;
pub(crate) mod session;
pub(crate) mod ssh;
pub mod types;

/// Shared tokio runtime for the TTY subsystem (local shell + SSH).
///
/// Independent of the daemon runtime so that `@tty` works without
/// starting the daemon. Created lazily on first use.
static TTY_RUNTIME: OnceLock<tokio::runtime::Runtime> = OnceLock::new();

/// Returns a reference to the TTY tokio runtime, creating it on first call.
pub(crate) fn runtime() -> &'static tokio::runtime::Runtime {
    TTY_RUNTIME.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .thread_name("tty-rt")
            .enable_all()
            .build()
            .expect("failed to create TTY tokio runtime")
    })
}
