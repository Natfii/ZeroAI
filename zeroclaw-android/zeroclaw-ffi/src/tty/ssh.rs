// Copyright (c) 2026 @Natfii. All rights reserved.

//! SSH client state machine built on russh.
//!
//! Manages the full lifecycle of an SSH connection: TCP transport,
//! host key verification, authentication, and PTY session multiplexing.
//!
//! # Architecture
//!
//! Three separate mutexes prevent deadlock during host key verification:
//!
//! - [`SSH_SESSION`] holds the connection state, handle, and ring buffer.
//! - [`SSH_DECISION_TX`] holds the oneshot sender for host key decisions.
//! - [`SSH_WRITE_HALF`] holds the shared channel write half for data
//!   writes and window change operations.
//!
//! The russh handler blocks on a oneshot receiver inside `check_server_key`
//! while Kotlin sends the user's decision through [`SSH_DECISION_TX`].
//! If both were behind the same mutex, the handler would deadlock.
//!
//! # Read/Write loops
//!
//! After authentication succeeds, the channel is split:
//!
//! - **Read half** is moved into the read loop tokio task, which polls
//!   [`ChannelReadHalf::wait`] for incoming data, EOF, and close messages,
//!   pushing bytes into the session's [`LineRingBuffer`].
//! - **Write half** is stored in [`SSH_WRITE_HALF`] behind an `Arc` and
//!   shared between the write loop task (which drains an mpsc channel
//!   and calls [`ChannelWriteHalf::data`]) and the [`resize`] function
//!   (which calls [`ChannelWriteHalf::window_change`]).

use std::sync::{Arc, Mutex};

use russh::keys::{HashAlg, PrivateKeyWithHashAlg};
use russh::client::KeyboardInteractiveAuthResponse;
use russh::{ChannelMsg, ChannelWriteHalf, Disconnect};
use tokio::sync::mpsc;
use zeroize::Zeroize;

/// Re-export of the `PublicKey` type from russh's internal fork of ssh-key.
///
/// russh 0.56 bundles `internal-russh-forked-ssh-key` which is a
/// separate crate from the standalone `ssh-key` used by `russh-keys`.
/// The handler callback receives this forked type, so we must match
/// it exactly.
type RusshPublicKey = russh::keys::PublicKey;

use super::backend::TerminalBackend;
use super::context::LineRingBuffer;
use super::types::{SshState, TtyHostKeyPrompt};
use crate::error::FfiError;

// ── Global singletons ──────────────────────────────────────────────

/// Active SSH session state, guarded by a mutex.
///
/// Only one SSH connection can be active at a time. The session is
/// `None` when disconnected.
static SSH_SESSION: Mutex<Option<SshSession>> = Mutex::new(None);

/// Oneshot sender for the host key verification decision.
///
/// Separated from [`SSH_SESSION`] to prevent deadlock: the russh
/// handler holds no mutex while awaiting the decision.
static SSH_DECISION_TX: Mutex<Option<tokio::sync::oneshot::Sender<bool>>> = Mutex::new(None);

/// Shared channel write half for data writes and window change.
///
/// Stored separately because it is shared between the write loop task
/// and the [`resize`] function. All methods on [`ChannelWriteHalf`]
/// take `&self`, so `Arc` without a `Mutex` is sufficient.
static SSH_WRITE_HALF: Mutex<Option<Arc<ChannelWriteHalf<russh::client::Msg>>>> =
    Mutex::new(None);

// ── Session state ──────────────────────────────────────────────────

/// Capacity of the write channel for SSH data.
const WRITE_CHANNEL_CAPACITY: usize = 256;

/// Default line capacity for the SSH output ring buffer.
const DEFAULT_LINE_CAPACITY: usize = 2000;

/// Mutable state for an active SSH connection.
struct SshSession {
    /// Current connection state, observed by the UI layer.
    state: SshState,
    /// Handle to the SSH client session for sending auth and channel
    /// requests.
    handle: Option<russh::client::Handle<SshHandler>>,
    /// Output ring buffer with ANSI stripping and credential scrubbing.
    ring_buffer: Arc<Mutex<LineRingBuffer>>,
    /// Terminal backend for VT parsing and render snapshots.
    backend: Arc<Mutex<Box<dyn TerminalBackend>>>,
    /// Sender half of the write channel for PTY input.
    write_tx: Option<mpsc::Sender<Vec<u8>>>,
    /// Pending host key prompt awaiting user decision.
    pending_host_key: Option<TtyHostKeyPrompt>,
    /// Username for the SSH connection.
    user: String,
    /// Remote hostname or IP address.
    host: String,
    /// Remote SSH port.
    port: u16,
    /// Handle for the read loop task (aborted on disconnect).
    read_handle: Option<tokio::task::JoinHandle<()>>,
    /// Handle for the write loop task (aborted on disconnect).
    write_handle: Option<tokio::task::JoinHandle<()>>,
}

// ── Mutex helpers ──────────────────────────────────────────────────

/// Locks the session mutex with poison recovery.
fn lock_session() -> std::sync::MutexGuard<'static, Option<SshSession>> {
    SSH_SESSION.lock().unwrap_or_else(|e| {
        tracing::warn!(
            target: "tty::ssh",
            "SSH session mutex was poisoned; recovering: {e}"
        );
        e.into_inner()
    })
}

/// Locks the decision sender mutex with poison recovery.
fn lock_decision() -> std::sync::MutexGuard<'static, Option<tokio::sync::oneshot::Sender<bool>>> {
    SSH_DECISION_TX.lock().unwrap_or_else(|e| {
        tracing::warn!(
            target: "tty::ssh",
            "SSH decision mutex was poisoned; recovering: {e}"
        );
        e.into_inner()
    })
}

/// Locks the write half mutex with poison recovery.
fn lock_write_half(
) -> std::sync::MutexGuard<'static, Option<Arc<ChannelWriteHalf<russh::client::Msg>>>> {
    SSH_WRITE_HALF.lock().unwrap_or_else(|e| {
        tracing::warn!(
            target: "tty::ssh",
            "SSH write half mutex was poisoned; recovering: {e}"
        );
        e.into_inner()
    })
}

// ── russh Handler ──────────────────────────────────────────────────

/// Client handler for the russh SSH session.
///
/// Implements [`russh::client::Handler`] with host key verification
/// that blocks on a oneshot channel until the user accepts or rejects
/// the server's key.
pub(crate) struct SshHandler {
    /// Receives the user's host key decision (true = accept).
    decision_rx: Option<tokio::sync::oneshot::Receiver<bool>>,
    /// Remote hostname for known-hosts lookups.
    host: String,
    /// Remote port for known-hosts lookups.
    port: u16,
}

impl russh::client::Handler for SshHandler {
    type Error = russh::Error;

    async fn check_server_key(
        &mut self,
        server_public_key: &RusshPublicKey,
    ) -> Result<bool, Self::Error> {
        let fp = server_public_key
            .fingerprint(HashAlg::Sha256)
            .to_string();
        let algo = server_public_key.algorithm().to_string();

        // Check known hosts (cache lookup -- no file I/O).
        let is_changed = match super::known_hosts::lookup(&self.host, self.port) {
            Some(entry) if entry.fingerprint_sha256 == fp => return Ok(true),
            Some(_) => true,
            None => false,
        };

        // Write prompt to SSH_SESSION so Kotlin can read it.
        {
            let mut guard = lock_session();
            if let Some(ref mut s) = *guard {
                s.pending_host_key = Some(TtyHostKeyPrompt {
                    host: self.host.clone(),
                    port: self.port,
                    algorithm: algo.clone(),
                    fingerprint_sha256: fp.clone(),
                    is_changed,
                });
                s.state = SshState::AwaitingHostKey;
            }
        } // mutex released before .await

        // Take the oneshot receiver. It must exist exactly once.
        let rx = self
            .decision_rx
            .take()
            .ok_or(russh::Error::Disconnect)?;

        // Block with 120s timeout for user decision.
        match tokio::time::timeout(std::time::Duration::from_secs(120), rx).await {
            Ok(Ok(true)) => {
                // Store in known hosts on acceptance.
                let _ = super::known_hosts::trust(&self.host, self.port, &algo, &fp);
                Ok(true)
            }
            _ => Ok(false),
        }
    }
}

// ── Public API ─────────────────────────────────────────────────────

/// Initiates an SSH connection to the given host.
///
/// Validates inputs, tears down any existing local PTY session,
/// creates the session state, and spawns an async connection task.
///
/// # Arguments
///
/// * `host` - Remote hostname or IP address (max 253 chars).
/// * `port` - Remote SSH port (1-65535).
/// * `user` - Username for authentication (max 64 chars).
///
/// # Errors
///
/// Returns [`FfiError::InvalidArgument`] if inputs fail validation.
/// Returns [`FfiError::StateError`] if an SSH session is already active.
pub(crate) fn start_ssh(host: &str, port: u16, user: &str) -> Result<(), FfiError> {
    // ── Input validation ───────────────────────────────────────
    if user.is_empty() || user.len() > 64 {
        return Err(FfiError::InvalidArgument {
            detail: "user must be 1-64 characters".into(),
        });
    }
    if !user
        .chars()
        .all(|c| c.is_ascii_alphanumeric() || c == '.' || c == '_' || c == '-')
    {
        return Err(FfiError::InvalidArgument {
            detail: "user contains invalid characters (allowed: a-zA-Z0-9._-)".into(),
        });
    }
    if host.is_empty() || host.len() > 253 {
        return Err(FfiError::InvalidArgument {
            detail: "host must be 1-253 characters".into(),
        });
    }
    if !host
        .chars()
        .all(|c| c.is_ascii_alphanumeric() || c == '.' || c == '_' || c == ':' || c == '-')
    {
        return Err(FfiError::InvalidArgument {
            detail: "host contains invalid characters (allowed: a-zA-Z0-9._:-)".into(),
        });
    }
    if port == 0 {
        return Err(FfiError::InvalidArgument {
            detail: "port must be 1-65535".into(),
        });
    }

    // ── State check ────────────────────────────────────────────
    {
        let guard = lock_session();
        if guard.is_some() {
            return Err(FfiError::StateError {
                detail: "an SSH session is already active".into(),
            });
        }
    }

    // Destroy local PTY session first (ignore NotRunning error).
    let _ = super::session::destroy();

    // ── Create oneshot for host key decision ───────────────────
    let (decision_tx, decision_rx) = tokio::sync::oneshot::channel::<bool>();
    {
        let mut guard = lock_decision();
        *guard = Some(decision_tx);
    }

    // ── Create SSH session in Connecting state ─────────────────
    let ring_buffer = Arc::new(Mutex::new(LineRingBuffer::new(DEFAULT_LINE_CAPACITY)));
    let backend: Box<dyn TerminalBackend> = super::session::create_backend(80, 24);
    let backend = Arc::new(Mutex::new(backend));
    {
        let mut guard = lock_session();
        *guard = Some(SshSession {
            state: SshState::Connecting,
            handle: None,
            ring_buffer,
            backend,
            write_tx: None,
            pending_host_key: None,
            user: user.to_owned(),
            host: host.to_owned(),
            port,
            read_handle: None,
            write_handle: None,
        });
    }

    // ── Spawn async connection task (fire-and-forget) ──────────
    let host_owned = host.to_owned();
    let port_owned = port;

    super::runtime().spawn(async move {
        if let Err(e) = connect_async(&host_owned, port_owned, decision_rx).await {
            tracing::error!(
                target: "tty::ssh",
                "SSH connection failed: {e}"
            );
            let mut guard = lock_session();
            if let Some(ref mut s) = *guard {
                s.ring_buffer
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .push_bytes(format!("\r\nSSH error: {e}\r\n").as_bytes());
                s.state = SshState::Disconnected;
            }
        }
    });

    tracing::info!(
        target: "tty::ssh",
        %host,
        port,
        %user,
        "SSH connection initiated"
    );

    Ok(())
}

/// Submits a password for SSH authentication.
///
/// First attempts the `password` auth method. If the server rejects it
/// but advertises `keyboard-interactive`, falls back to that method
/// automatically (common for Windows OpenSSH).
///
/// # Returns
///
/// `Ok(true)` if authentication succeeded, `Ok(false)` if rejected.
///
/// # Errors
///
/// Returns [`FfiError::InvalidArgument`] if the password is too long
/// or not valid UTF-8.
/// Returns [`FfiError::StateError`] if no SSH session is active.
pub(crate) fn submit_password(mut password: Vec<u8>) -> Result<bool, FfiError> {
    if password.len() > 1024 {
        password.as_mut_slice().zeroize();
        return Err(FfiError::InvalidArgument {
            detail: "password exceeds 1024 bytes".into(),
        });
    }

    let password_str = match String::from_utf8(password) {
        Ok(s) => s,
        Err(e) => {
            let mut raw = e.into_bytes();
            raw.as_mut_slice().zeroize();
            return Err(FfiError::InvalidArgument {
                detail: "password is not valid UTF-8".into(),
            });
        }
    };

    let mut password_z = zeroize::Zeroizing::new(password_str);
    let rt_handle = super::runtime().handle().clone();

    rt_handle.block_on(async {
        let (mut ssh_handle, user) = take_handle_and_user()?;

        {
            let mut guard = lock_session();
            if let Some(ref mut s) = *guard {
                s.state = SshState::Authenticating;
            }
        }

        // Try password auth first.
        let auth_result = ssh_handle
            .authenticate_password(&user, password_z.as_str())
            .await
            .map_err(map_russh_error);

        match auth_result {
            Ok(result) if result.success() => {
                password_z.zeroize();
                restore_handle(ssh_handle);
                open_pty_channel().await?;
                return Ok(true);
            }
            Ok(_) => {
                // Password method rejected — try keyboard-interactive.
                tracing::debug!(
                    target: "tty::ssh",
                    "password auth rejected, trying keyboard-interactive"
                );
            }
            Err(e) => {
                password_z.zeroize();
                {
                    let mut guard = lock_session();
                    if let Some(ref mut s) = *guard {
                        s.state = SshState::Disconnected;
                    }
                }
                restore_handle(ssh_handle);
                return Err(e);
            }
        }

        // Keyboard-interactive fallback.
        let ki_result =
            try_keyboard_interactive(&mut ssh_handle, &user, &password_z).await;

        password_z.zeroize();

        match ki_result {
            Ok(true) => {
                restore_handle(ssh_handle);
                open_pty_channel().await?;
                Ok(true)
            }
            Ok(false) => {
                restore_handle(ssh_handle);
                Ok(false)
            }
            Err(e) => {
                {
                    let mut guard = lock_session();
                    if let Some(ref mut s) = *guard {
                        s.state = SshState::Disconnected;
                    }
                }
                restore_handle(ssh_handle);
                Err(e)
            }
        }
    })
}

/// Attempts keyboard-interactive auth, responding to server prompts
/// with the provided password.
///
/// Handles the multi-round challenge-response protocol (RFC 4256).
/// Windows OpenSSH sends exactly one prompt ("Password: ") in one
/// round. Other servers may send zero-prompt info messages or
/// multiple rounds.
async fn try_keyboard_interactive(
    handle: &mut russh::client::Handle<SshHandler>,
    user: &str,
    password: &str,
) -> Result<bool, FfiError> {
    const MAX_ROUNDS: u8 = 20;
    let mut rounds: u8 = 0;

    let mut response = handle
        .authenticate_keyboard_interactive_start(user, None)
        .await
        .map_err(map_russh_error)?;

    loop {
        rounds += 1;
        if rounds > MAX_ROUNDS {
            return Err(FfiError::StateError {
                detail: "keyboard-interactive: too many challenge rounds".into(),
            });
        }

        match response {
            KeyboardInteractiveAuthResponse::Success => return Ok(true),
            KeyboardInteractiveAuthResponse::Failure { .. } => return Ok(false),
            KeyboardInteractiveAuthResponse::InfoRequest { prompts, .. } => {
                let mut answers: Vec<String> = prompts
                    .iter()
                    .map(|_| password.to_owned())
                    .collect();

                let result = handle
                    .authenticate_keyboard_interactive_respond(answers.clone())
                    .await
                    .map_err(map_russh_error);

                for a in &mut answers {
                    a.zeroize();
                }

                response = result?;
            }
        }
    }
}

/// Submits a stored SSH key for public-key authentication.
///
/// Loads the private key from the key store and calls
/// `authenticate_publickey` on the russh handle.
///
/// # Arguments
///
/// * `key_id` - UUID of the key in the key store.
///
/// # Returns
///
/// `Ok(true)` if authentication succeeded, `Ok(false)` if rejected.
///
/// # Errors
///
/// Returns [`FfiError::InvalidArgument`] if the key cannot be loaded.
/// Returns [`FfiError::StateError`] if no SSH session is active.
pub(crate) fn submit_key(key_id: &str) -> Result<bool, FfiError> {
    // Load the key using russh::keys types (the internal fork), NOT
    // russh_keys which uses the standalone ssh-key crate. These are
    // different types at the Rust level even though they look the same.
    let key_path = super::key_store::key_path(key_id)?;
    let key = russh::keys::load_secret_key(&key_path, None).map_err(|e| {
        FfiError::InvalidArgument {
            detail: format!("failed to load SSH key: {e}"),
        }
    })?;

    let hash_alg = if key.algorithm().is_rsa() {
        Some(HashAlg::Sha256)
    } else {
        None
    };
    let key_with_alg = PrivateKeyWithHashAlg::new(Arc::new(key), hash_alg);

    let rt_handle = super::runtime().handle().clone();

    rt_handle.block_on(async {
        let (mut ssh_handle, user) = take_handle_and_user()?;

        {
            let mut guard = lock_session();
            if let Some(ref mut s) = *guard {
                s.state = SshState::Authenticating;
            }
        }

        let auth_result = ssh_handle
            .authenticate_publickey(&user, key_with_alg)
            .await
            .map_err(map_russh_error);

        match auth_result {
            Ok(result) if result.success() => {
                restore_handle(ssh_handle);
                open_pty_channel().await?;
                Ok(true)
            }
            Ok(_) => {
                restore_handle(ssh_handle);
                Ok(false)
            }
            Err(e) => {
                restore_handle(ssh_handle);
                Err(e)
            }
        }
    })
}

/// Disconnects the active SSH session.
///
/// Sends a disconnect message to the server, aborts read/write tasks,
/// and clears all session state. Idempotent -- returns `Ok` if no
/// session is active.
pub(crate) fn disconnect() -> Result<(), FfiError> {
    let session = {
        let mut guard = lock_session();
        guard.take()
    };

    // Clear decision sender.
    {
        let mut guard = lock_decision();
        *guard = None;
    }

    // Clear write half.
    {
        let mut guard = lock_write_half();
        *guard = None;
    }

    let Some(session) = session else {
        return Ok(());
    };

    // Abort read/write tasks.
    if let Some(ref h) = session.read_handle {
        h.abort();
    }
    if let Some(ref h) = session.write_handle {
        h.abort();
    }

    // Send disconnect to the server if we have a handle.
    if let Some(ssh_handle) = session.handle {
        let rt = super::runtime();
        let _ = rt.handle().block_on(async {
            let _ = ssh_handle
                .disconnect(Disconnect::ByApplication, "", "en")
                .await;
        });
    }

    tracing::info!(target: "tty::ssh", "SSH session disconnected");
    Ok(())
}

/// Returns the pending host key prompt, if any.
///
/// The UI polls this after observing [`SshState::AwaitingHostKey`] to
/// display the fingerprint to the user.
pub(crate) fn get_pending_host_key() -> Option<TtyHostKeyPrompt> {
    let guard = lock_session();
    guard.as_ref().and_then(|s| s.pending_host_key.clone())
}

/// Sends the user's host key decision (accept or reject).
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if no host key decision is pending.
pub(crate) fn answer_host_key(accept: bool) -> Result<(), FfiError> {
    let sender = {
        let mut guard = lock_decision();
        guard.take()
    };

    let sender = sender.ok_or_else(|| FfiError::StateError {
        detail: "no host key decision is pending".into(),
    })?;

    let _ = sender.send(accept);

    tracing::info!(
        target: "tty::ssh",
        accept,
        "host key decision sent"
    );

    Ok(())
}

/// Returns whether the SSH session is currently connected.
pub(crate) fn is_connected() -> bool {
    let guard = lock_session();
    guard
        .as_ref()
        .is_some_and(|s| s.state == SshState::Connected)
}

/// Returns whether any SSH session exists (any state, including connecting/error).
pub(crate) fn has_session() -> bool {
    lock_session().is_some()
}

/// Writes raw bytes to the SSH channel input (non-blocking).
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if no SSH session is connected.
/// Returns [`FfiError::SpawnError`] if the write channel is full or
/// closed.
pub(crate) fn write_bytes(data: Vec<u8>) -> Result<(), FfiError> {
    let guard = lock_session();
    let session = guard.as_ref().ok_or_else(|| FfiError::StateError {
        detail: "no SSH session is active".into(),
    })?;

    let tx = session.write_tx.as_ref().ok_or_else(|| FfiError::StateError {
        detail: "SSH write channel not available (not yet connected)".into(),
    })?;

    tx.try_send(data).map_err(|e| FfiError::SpawnError {
        detail: format!("SSH write channel error: {e}"),
    })
}

/// Returns the last `max_lines` output lines from the SSH session.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if no SSH session is active.
pub(crate) fn get_output_lines(max_lines: u32) -> Result<Vec<String>, FfiError> {
    let guard = lock_session();
    let session = guard.as_ref().ok_or_else(|| FfiError::StateError {
        detail: "no SSH session is active".into(),
    })?;

    let buffer = session.ring_buffer.lock().unwrap_or_else(|e| {
        tracing::warn!(
            target: "tty::ssh",
            "ring buffer mutex poisoned; recovering: {e}"
        );
        e.into_inner()
    });

    Ok(buffer.get_lines(max_lines as usize))
}

/// Returns a render frame from the SSH session's terminal backend.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if no SSH session is active.
pub(crate) fn get_render_frame() -> Result<super::types::TtyRenderFrame, FfiError> {
    let guard = lock_session();
    let session = guard.as_ref().ok_or_else(|| FfiError::StateError {
        detail: "no SSH session is active".into(),
    })?;

    let mut backend = session.backend.lock().unwrap_or_else(|e| {
        tracing::warn!(
            target: "tty::ssh",
            "backend mutex poisoned; recovering: {e}"
        );
        e.into_inner()
    });

    let snapshot = backend.snapshot_for_render();
    Ok(super::session::snapshot_to_frame(snapshot))
}

/// Resizes the remote PTY to the given dimensions.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if no SSH session is connected
/// or the channel write half is not available.
pub(crate) fn resize(cols: u16, rows: u16) -> Result<(), FfiError> {
    let write_half = {
        let guard = lock_write_half();
        guard.as_ref().map(Arc::clone).ok_or_else(|| FfiError::StateError {
            detail: "SSH channel not open".into(),
        })?
    };

    let rt_handle = super::runtime().handle().clone();

    rt_handle.block_on(async {
        write_half
            .window_change(u32::from(cols), u32::from(rows), 0, 0)
            .await
            .map_err(map_russh_error)?;

        tracing::debug!(
            target: "tty::ssh",
            cols,
            rows,
            "SSH PTY resized"
        );

        Ok(())
    })?;

    // Also resize the terminal backend so the VT grid matches.
    let guard = lock_session();
    if let Some(ref session) = *guard {
        let mut backend = session.backend.lock().unwrap_or_else(|e| e.into_inner());
        let _ = backend.resize(cols, rows);
    }

    Ok(())
}

/// Returns the current SSH connection state.
pub(crate) fn get_state() -> SshState {
    let guard = lock_session();
    guard
        .as_ref()
        .map_or(SshState::Disconnected, |s| s.state.clone())
}

// ── Internal helpers ───────────────────────────────────────────────

/// Takes the SSH handle and user out of the session for an async
/// operation. The handle must be restored via [`restore_handle`]
/// after the operation completes.
fn take_handle_and_user() -> Result<(russh::client::Handle<SshHandler>, String), FfiError> {
    let mut guard = lock_session();
    let session = guard.as_mut().ok_or_else(|| FfiError::StateError {
        detail: "no SSH session is active".into(),
    })?;
    let handle = session.handle.take().ok_or_else(|| FfiError::StateError {
        detail: "SSH handle not available (connection may still be in progress)".into(),
    })?;
    let user = session.user.clone();
    Ok((handle, user))
}

/// Puts the SSH handle back into the session after an async operation.
fn restore_handle(handle: russh::client::Handle<SshHandler>) {
    let mut guard = lock_session();
    if let Some(ref mut s) = *guard {
        s.handle = Some(handle);
    }
}

/// Async connection logic spawned by [`start_ssh`].
///
/// Connects to the remote host with a 30-second timeout, stores the
/// handle in [`SSH_SESSION`], and transitions state to `Authenticating`
/// (the handler may pause at `AwaitingHostKey` during the connection
/// if the server key is unknown).
async fn connect_async(
    host: &str,
    port: u16,
    decision_rx: tokio::sync::oneshot::Receiver<bool>,
) -> Result<(), FfiError> {
    let config = russh::client::Config {
        inactivity_timeout: Some(std::time::Duration::from_secs(300)),
        keepalive_interval: Some(std::time::Duration::from_secs(30)),
        keepalive_max: 3,
        ..Default::default()
    };

    let handler = SshHandler {
        decision_rx: Some(decision_rx),
        host: host.to_owned(),
        port,
    };

    let addr = format!("{host}:{port}");

    let ssh_handle = tokio::time::timeout(
        std::time::Duration::from_secs(30),
        russh::client::connect(Arc::new(config), &addr, handler),
    )
    .await
    .map_err(|_| FfiError::NetworkError {
        detail: format!("SSH connection to {addr} timed out after 30s"),
    })?
    .map_err(map_russh_error)?;

    // Store the handle and transition to Authenticating.
    let mut guard = lock_session();
    if let Some(ref mut s) = *guard {
        s.handle = Some(ssh_handle);
        if s.state == SshState::Connecting || s.state == SshState::AwaitingHostKey {
            s.state = SshState::Authenticating;
        }
    }

    tracing::info!(
        target: "tty::ssh",
        %host,
        port,
        "SSH transport established, awaiting authentication"
    );

    Ok(())
}

/// Opens a PTY channel after successful authentication.
///
/// Requests a session channel, allocates a PTY with `xterm-256color`,
/// requests a shell, splits the channel into read/write halves, and
/// spawns the async I/O loops.
async fn open_pty_channel() -> Result<(), FfiError> {
    let ssh_handle = {
        let mut guard = lock_session();
        let session = guard.as_mut().ok_or_else(|| FfiError::StateError {
            detail: "no SSH session is active".into(),
        })?;
        session.handle.take().ok_or_else(|| FfiError::StateError {
            detail: "SSH handle not available".into(),
        })?
    };

    // Open a session channel.
    let channel = ssh_handle
        .channel_open_session()
        .await
        .map_err(map_russh_error)?;

    // Request PTY allocation (80x24 default, Kotlin sends resize after).
    channel
        .request_pty(false, "xterm-256color", 80, 24, 0, 0, &[])
        .await
        .map_err(map_russh_error)?;

    // Request a shell.
    channel
        .request_shell(true)
        .await
        .map_err(map_russh_error)?;

    // Split channel into read and write halves.
    let (read_half, write_half) = channel.split();
    let write_half = Arc::new(write_half);

    // Store the write half globally for resize and the write loop.
    {
        let mut guard = lock_write_half();
        *guard = Some(Arc::clone(&write_half));
    }

    // Get the ring buffer and backend.
    let (ring_buffer, backend) = {
        let guard = lock_session();
        let session = guard.as_ref().ok_or_else(|| FfiError::StateError {
            detail: "no SSH session is active".into(),
        })?;
        (Arc::clone(&session.ring_buffer), Arc::clone(&session.backend))
    };

    // Create write mpsc channel.
    let (write_tx, write_rx) = mpsc::channel::<Vec<u8>>(WRITE_CHANNEL_CAPACITY);

    // Spawn read loop.
    let read_ring = Arc::clone(&ring_buffer);
    let read_backend = Arc::clone(&backend);
    let read_handle = tokio::spawn(async move {
        ssh_read_loop(read_half, read_ring, read_backend).await;
    });

    // Spawn write loop.
    let write_ch = Arc::clone(&write_half);
    let write_handle = tokio::spawn(async move {
        ssh_write_loop(write_ch, write_rx).await;
    });

    // Store everything back in the session.
    let mut guard = lock_session();
    if let Some(ref mut s) = *guard {
        s.handle = Some(ssh_handle);
        s.write_tx = Some(write_tx);
        s.state = SshState::Connected;
        s.read_handle = Some(read_handle);
        s.write_handle = Some(write_handle);
    }

    tracing::info!(target: "tty::ssh", "SSH PTY channel opened and shell started");

    Ok(())
}

/// Read loop that drains incoming SSH channel messages into the ring
/// buffer.
///
/// Runs as a tokio task. Exits on EOF, Close, or channel drop, then
/// marks the session as disconnected.
async fn ssh_read_loop(
    mut read_half: russh::ChannelReadHalf,
    ring_buffer: Arc<Mutex<LineRingBuffer>>,
    backend: Arc<Mutex<Box<dyn TerminalBackend>>>,
) {
    loop {
        match read_half.wait().await {
            Some(ChannelMsg::Data { ref data }) => {
                ring_buffer
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .push_bytes(data);
                if let Ok(mut guard) = backend.lock() {
                    let _ = guard.feed_input(data);
                }
                // Signal the render thread that new SSH data is available.
                super::session::notify_render_dirty();
            }
            Some(ChannelMsg::ExtendedData { ref data, .. }) => {
                // stderr -- push to ring buffer too.
                ring_buffer
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .push_bytes(data);
                if let Ok(mut guard) = backend.lock() {
                    let _ = guard.feed_input(data);
                }
            }
            Some(ChannelMsg::Eof) => {
                tracing::debug!(target: "tty::ssh", "SSH channel EOF");
                break;
            }
            Some(ChannelMsg::Close) => {
                tracing::debug!(target: "tty::ssh", "SSH channel closed");
                break;
            }
            None => {
                tracing::debug!(target: "tty::ssh", "SSH channel receiver dropped");
                break;
            }
            _ => {
                // Ignore other messages (WindowAdjust, etc.)
            }
        }
    }

    // Mark session as disconnected.
    let mut guard = lock_session();
    if let Some(ref mut s) = *guard {
        s.state = SshState::Disconnected;
    }

    tracing::debug!(target: "tty::ssh", "SSH read loop exited");
}

/// Write loop that drains the mpsc channel and sends data to the
/// remote SSH shell.
///
/// Runs as a tokio task. Exits when the mpsc sender is dropped or
/// a write error occurs.
async fn ssh_write_loop(
    write_half: Arc<ChannelWriteHalf<russh::client::Msg>>,
    mut write_rx: mpsc::Receiver<Vec<u8>>,
) {
    while let Some(data) = write_rx.recv().await {
        if let Err(e) = write_half.data(std::io::Cursor::new(data)).await {
            tracing::warn!(target: "tty::ssh", "SSH write error: {e}");
            break;
        }
    }
    tracing::debug!(target: "tty::ssh", "SSH write loop exited");
}

/// Maps a russh error to an [`FfiError`].
fn map_russh_error(e: russh::Error) -> FfiError {
    FfiError::NetworkError {
        detail: format!("{e}"),
    }
}

/// Applies a color theme to the SSH terminal session.
///
/// Forwards the palette to the terminal backend via
/// [`TerminalBackend::apply_palette`] and signals the render thread.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if no SSH session is active.
pub(crate) fn set_palette(bg: u32, fg: u32, cursor: u32, palette: &[u32]) -> Result<(), FfiError> {
    let guard = lock_session();
    let session = guard.as_ref().ok_or_else(|| FfiError::StateError {
        detail: "no SSH session is active".into(),
    })?;

    let mut backend = session.backend.lock().unwrap_or_else(|e| e.into_inner());
    backend.apply_palette(bg, fg, cursor, palette);
    drop(backend);
    super::session::notify_render_dirty();
    Ok(())
}

/// Returns whether bracketed paste mode (DEC 2004) is active in the
/// SSH session's terminal backend.
///
/// Returns `Ok(false)` when no SSH session is running.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the backend mutex is poisoned
/// and cannot be recovered.
pub(crate) fn is_bracketed_paste_active() -> Result<bool, FfiError> {
    let guard = lock_session();
    let Some(session) = guard.as_ref() else {
        return Ok(false);
    };

    let backend = session.backend.lock().unwrap_or_else(|e| {
        tracing::warn!(
            target: "tty::ssh",
            "backend mutex poisoned while querying bracketed paste; recovering"
        );
        e.into_inner()
    });
    Ok(backend.is_bracketed_paste_active())
}

/// Returns whether focus reporting mode (DEC 1004) is active in the
/// SSH session's terminal backend.
pub(crate) fn is_focus_reporting_active() -> Result<bool, FfiError> {
    let guard = lock_session();
    let Some(session) = guard.as_ref() else {
        return Ok(false);
    };

    let backend = session.backend.lock().unwrap_or_else(|e| {
        tracing::warn!(
            target: "tty::ssh",
            "backend mutex poisoned while querying focus reporting; recovering"
        );
        e.into_inner()
    });
    Ok(backend.is_focus_reporting_active())
}

/// Returns `true` if a terminal bell (BEL) has fired since the last
/// call, atomically clearing the pending flag.
///
/// Returns `Ok(false)` when no SSH session is running.
pub(crate) fn take_bell_event() -> Result<bool, FfiError> {
    let guard = lock_session();
    let Some(session) = guard.as_ref() else {
        return Ok(false);
    };

    let backend = session.backend.lock().unwrap_or_else(|e| {
        tracing::warn!(
            target: "tty::ssh",
            "backend mutex poisoned while polling bell event; recovering"
        );
        e.into_inner()
    });
    Ok(backend.take_bell_event())
}

/// If the terminal title has changed since the last call (OSC 0/2),
/// reads and returns the current title string.
///
/// Returns `Ok(None)` when no SSH session is running or the title
/// has not changed since the last poll.
pub(crate) fn take_title_if_changed() -> Result<Option<String>, FfiError> {
    let guard = lock_session();
    let Some(session) = guard.as_ref() else {
        return Ok(None);
    };

    let mut backend = session.backend.lock().unwrap_or_else(|e| {
        tracing::warn!(
            target: "tty::ssh",
            "backend mutex poisoned while polling title change; recovering"
        );
        e.into_inner()
    });
    Ok(backend.take_title_if_changed())
}

// ── Tests ──────────────────────────────────────────────────────────

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    #[test]
    fn start_ssh_rejects_empty_user() {
        let result = start_ssh("example.com", 22, "");
        assert!(result.is_err());
    }

    #[test]
    fn start_ssh_rejects_long_user() {
        let long_user = "a".repeat(65);
        let result = start_ssh("example.com", 22, &long_user);
        assert!(result.is_err());
    }

    #[test]
    fn start_ssh_rejects_invalid_user_chars() {
        let result = start_ssh("example.com", 22, "user@host");
        assert!(result.is_err());
    }

    #[test]
    fn start_ssh_rejects_empty_host() {
        let result = start_ssh("", 22, "root");
        assert!(result.is_err());
    }

    #[test]
    fn start_ssh_rejects_zero_port() {
        let result = start_ssh("example.com", 0, "root");
        assert!(result.is_err());
    }

    #[test]
    fn disconnect_is_idempotent() {
        {
            let mut guard = lock_session();
            *guard = None;
        }
        assert!(disconnect().is_ok());
    }

    #[test]
    fn is_connected_returns_false_when_no_session() {
        {
            let mut guard = lock_session();
            *guard = None;
        }
        assert!(!is_connected());
    }

    #[test]
    fn get_state_returns_disconnected_when_no_session() {
        {
            let mut guard = lock_session();
            *guard = None;
        }
        assert_eq!(get_state(), SshState::Disconnected);
    }

    #[test]
    fn answer_host_key_fails_when_no_decision_pending() {
        {
            let mut guard = lock_decision();
            *guard = None;
        }
        assert!(answer_host_key(true).is_err());
    }

    #[test]
    fn write_bytes_fails_when_no_session() {
        {
            let mut guard = lock_session();
            *guard = None;
        }
        assert!(write_bytes(vec![0x41]).is_err());
    }

    #[test]
    fn get_output_lines_fails_when_no_session() {
        {
            let mut guard = lock_session();
            *guard = None;
        }
        assert!(get_output_lines(10).is_err());
    }
}
