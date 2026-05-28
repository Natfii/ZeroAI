// Copyright (c) 2026 @Natfii. All rights reserved.

//! TTY SSH connection FFI exports.
//!
//! Covers start/disconnect, password+key auth submission, host-key
//! prompts and answers, and the SSH connection state query. The
//! existing `tty_submit_password` macro export lives in `lib.rs` because
//! it directly forwards [`tty::ssh::submit_password`] without a shim.

use crate::error::FfiError;
use crate::tty::{self, types};

// ── FFI exports ────────────────────────────────────────────────────────────

crate::ffi_export!(
    /// Starts an SSH connection to the given host.
    ///
    /// Initiates the SSH handshake and authentication flow. Use the
    /// password/key submission helpers after this call returns, and
    /// `tty_get_pending_host_key` to handle unknown-host prompts.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::IoError`] if the connection fails, or
    /// [`crate::FfiError::InternalPanic`] if native code panics.
    fn tty_start_ssh(host: String, port: u32, user: String) -> () = tty_start_ssh_inner
);

crate::ffi_export!(
    /// Submits a password for the pending SSH authentication challenge.
    ///
    /// Returns `true` if authentication succeeded, `false` if it failed.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::StateError`] if there is no pending
    /// auth challenge, or [`crate::FfiError::InternalPanic`] if native
    /// code panics.
    fn tty_submit_password(password: Vec<u8>) -> bool = tty::ssh::submit_password
);

crate::ffi_export!(
    /// Submits a stored SSH key for the pending authentication challenge.
    ///
    /// Returns `true` if authentication succeeded, `false` if it failed.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::StateError`] if there is no pending
    /// auth challenge or the key ID is not found, or
    /// [`crate::FfiError::InternalPanic`] if native code panics.
    fn tty_submit_key(key_id: String) -> bool = tty_submit_key_inner
);

crate::ffi_export!(
    /// Disconnects the active SSH session. Idempotent.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::InternalPanic`] if native code panics.
    fn tty_disconnect_ssh() -> () = tty_disconnect_ssh_inner
);

crate::ffi_export!(
    /// Returns the pending host-key verification prompt, if any.
    ///
    /// Returns `Some` when the SSH handshake produced an unknown or
    /// changed host key that the user must accept or reject before
    /// authentication can continue.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::InternalPanic`] if native code panics.
    fn tty_get_pending_host_key() -> Option<types::TtyHostKeyPrompt> = tty_get_pending_host_key_inner
);

crate::ffi_export!(
    /// Accepts or rejects the pending SSH host-key verification prompt.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::StateError`] if there is no pending
    /// host-key prompt, or [`crate::FfiError::InternalPanic`] if native
    /// code panics.
    fn tty_answer_host_key(decision: types::TtyHostKeyDecision) -> () = tty_answer_host_key_inner
);

crate::ffi_export!(
    /// Returns the current SSH connection state.
    ///
    /// Returns [`types::SshState::Disconnected`] when no SSH session exists.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::InternalPanic`] if native code panics.
    fn tty_get_ssh_state() -> types::SshState = tty_get_ssh_state_inner
);

// ── Inner implementations ──────────────────────────────────────────────────

pub(crate) fn tty_start_ssh_inner(host: String, port: u32, user: String) -> Result<(), FfiError> {
    tty::ssh::start_ssh(&host, port as u16, &user)
}

pub(crate) fn tty_submit_key_inner(key_id: String) -> Result<bool, FfiError> {
    tty::ssh::submit_key(&key_id)
}

pub(crate) fn tty_disconnect_ssh_inner() -> Result<(), FfiError> {
    tty::ssh::disconnect()
}

#[allow(clippy::unnecessary_wraps)]
pub(crate) fn tty_get_pending_host_key_inner()
-> Result<Option<types::TtyHostKeyPrompt>, FfiError> {
    Ok(tty::ssh::get_pending_host_key())
}

pub(crate) fn tty_answer_host_key_inner(
    decision: types::TtyHostKeyDecision,
) -> Result<(), FfiError> {
    tty::ssh::answer_host_key(decision == types::TtyHostKeyDecision::Accept)
}

#[allow(clippy::unnecessary_wraps)]
pub(crate) fn tty_get_ssh_state_inner() -> Result<types::SshState, FfiError> {
    Ok(tty::ssh::get_state())
}
