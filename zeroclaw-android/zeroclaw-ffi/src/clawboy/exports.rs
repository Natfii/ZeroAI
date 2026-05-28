// Copyright (c) 2026 @Natfii. All rights reserved.

//! ClawBoy FFI exports and inner implementations.
//!
//! Wraps the [`clawboy::session`](super::session) and
//! [`clawboy::emulator`](super::emulator) APIs with panic-isolated UniFFI
//! exports via the [`crate::ffi_export!`] macro.

use std::panic::{AssertUnwindSafe, catch_unwind};

use crate::clawboy;
use crate::error::FfiError;
use crate::panic_detail;
use crate::runtime;

// ── FFI exports ────────────────────────────────────────────────────────────

crate::ffi_export!(
    /// Verifies a ROM file against the expected Pokemon Red hash.
    ///
    /// Computes SHA-1 of `data` and compares to the hardcoded hash for
    /// Pokemon Red (USA/Europe). Returns a
    /// [`RomVerification`](clawboy::types::RomVerification) with the result
    /// and computed hash string. Does NOT require the daemon to be running.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::InternalPanic`] if native code panics.
    fn clawboy_verify_rom(data: Vec<u8>) -> clawboy::types::RomVerification = clawboy_verify_rom_inner
);

crate::ffi_export!(
    /// Starts a ClawBoy emulator session.
    ///
    /// Boots the emulator with the ROM at `rom_path`, starts the WebSocket
    /// viewer server, and begins the emulation tick loop. Returns viewer
    /// URL and port for browser access. Only one session can run at a time.
    /// Does NOT require the daemon to be running.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::StateError`] if a session is already running,
    /// [`crate::FfiError::SpawnError`] if the ROM cannot be read, the emulator
    /// fails to initialise, or the server cannot bind, or
    /// [`crate::FfiError::InternalPanic`] if native code panics.
    fn clawboy_start_session(
        rom_path: String,
        decision_interval_ms: u64,
        data_dir: String,
        channel_id: Option<String>
    ) -> clawboy::types::ClawBoySessionInfo = clawboy_start_session_inner
);

crate::ffi_export!(
    /// Stops the running ClawBoy emulator session.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::StateError`] if no session is running,
    /// [`crate::FfiError::SpawnError`] on shutdown failure, or
    /// [`crate::FfiError::InternalPanic`] if native code panics.
    fn clawboy_stop_session() -> () = clawboy_stop_session_inner
);

crate::ffi_export!(
    /// Returns the current status of the ClawBoy emulator.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::InternalPanic`] if native code panics.
    fn clawboy_get_status() -> clawboy::types::ClawBoyStatus = clawboy_get_status_inner
);

crate::ffi_export!(
    /// Updates the agent decision interval for the running ClawBoy session.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::StateError`] if no session is running, or
    /// [`crate::FfiError::InternalPanic`] if native code panics.
    fn clawboy_set_decision_interval(ms: u64) -> () = clawboy_set_decision_interval_inner
);

crate::ffi_export!(
    /// Pauses the running ClawBoy emulator session.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::StateError`] if no session is running, or
    /// [`crate::FfiError::InternalPanic`] if native code panics.
    fn clawboy_pause_session() -> () = clawboy_pause_session_inner
);

crate::ffi_export!(
    /// Resumes a paused ClawBoy emulator session.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::StateError`] if no session is running, or
    /// [`crate::FfiError::InternalPanic`] if native code panics.
    fn clawboy_resume_session() -> () = clawboy_resume_session_inner
);

crate::ffi_export!(
    /// Notifies the daemon that a verified ROM is ready at the given path.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::InternalPanic`] if native code panics.
    fn clawboy_notify_rom_ready(data_dir: String) -> () = clawboy_notify_rom_ready_inner
);

crate::ffi_export!(
    /// Notifies the daemon that the ClawBoy ROM has been removed.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::InternalPanic`] if native code panics.
    fn clawboy_notify_rom_removed() -> () = clawboy_notify_rom_removed_inner
);

// ── Inner implementations ──────────────────────────────────────────────────

pub(crate) fn clawboy_verify_rom_inner(
    data: Vec<u8>,
) -> Result<clawboy::types::RomVerification, FfiError> {
    catch_unwind(AssertUnwindSafe(|| {
        Ok(clawboy::emulator::Emulator::verify_rom(&data))
    }))
    .unwrap_or_else(|e| {
        Err(FfiError::InternalPanic {
            detail: panic_detail(&e),
        })
    })
}

pub(crate) fn clawboy_start_session_inner(
    rom_path: String,
    decision_interval_ms: u64,
    data_dir: String,
    channel_id: Option<String>,
) -> Result<clawboy::types::ClawBoySessionInfo, FfiError> {
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
}

pub(crate) fn clawboy_stop_session_inner() -> Result<(), FfiError> {
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
}

pub(crate) fn clawboy_get_status_inner()
-> Result<clawboy::types::ClawBoyStatus, FfiError> {
    Ok(clawboy::session::get_status())
}

pub(crate) fn clawboy_set_decision_interval_inner(ms: u64) -> Result<(), FfiError> {
    clawboy::session::set_decision_interval(ms)
        .map_err(|e| FfiError::StateError { detail: e })
}

pub(crate) fn clawboy_pause_session_inner() -> Result<(), FfiError> {
    clawboy::session::pause_session().map_err(|e| FfiError::StateError { detail: e })
}

pub(crate) fn clawboy_resume_session_inner() -> Result<(), FfiError> {
    clawboy::session::resume_session().map_err(|e| FfiError::StateError { detail: e })
}

pub(crate) fn clawboy_notify_rom_ready_inner(data_dir: String) -> Result<(), FfiError> {
    let path = std::path::Path::new(&data_dir);
    clawboy::session::notify_rom_ready(path);
    Ok(())
}

pub(crate) fn clawboy_notify_rom_removed_inner() -> Result<(), FfiError> {
    clawboy::session::notify_rom_removed();
    Ok(())
}
