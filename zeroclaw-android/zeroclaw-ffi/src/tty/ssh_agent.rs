// Copyright (c) 2026 @Natfii. All rights reserved.

//! In-process SSH agent.
//!
//! Runs a russh ssh-agent server bound to a Unix-domain socket inside the
//! app process. Decrypted private keys live only in this agent for the
//! duration of the session; the bundled `zssh` client authenticates against
//! it via `SSH_AUTH_SOCK`. There is no biometric gate — the agent's
//! `confirm_request` always returns `true`.
//!
//! The agent's key store is seeded exclusively through the agent protocol
//! (an [`AgentClient`] connection's `add_identity`), never through a Rust
//! API, so adding and resetting identities both go through a short-lived
//! client connection to the same socket.

use std::os::unix::fs::PermissionsExt;
use std::path::{Path, PathBuf};
use std::sync::{Mutex, OnceLock};

use russh::keys::PrivateKey;
use russh::keys::agent::client::AgentClient;
use tokio::net::UnixListener;
use tokio_stream::wrappers::UnixListenerStream;
use zeroize::Zeroize;

use crate::error::FfiError;
use crate::tty;

/// Path of the running agent's Unix-domain socket.
///
/// Set once by [`start`]; subsequent calls are idempotent and return the
/// stored path. `None` until the agent has been started.
static AGENT_SOCK: OnceLock<PathBuf> = OnceLock::new();

/// Serializes [`start`] so concurrent callers cannot both bind the socket.
static START_LOCK: Mutex<()> = Mutex::new(());

/// Returns the running agent's socket path, or `None` if not yet started.
pub(crate) fn agent_sock_path() -> Option<&'static Path> {
    AGENT_SOCK.get().map(PathBuf::as_path)
}

/// Starts the in-process ssh-agent on `<socket_dir>/agent.sock`.
///
/// Idempotent: if the agent is already running, returns the existing socket
/// path without rebinding. A stale socket file at the target path is unlinked
/// before binding. The `serve` future runs detached on the TTY runtime.
///
/// Returns the absolute socket path as a string for export as
/// `SSH_AUTH_SOCK`.
pub(crate) fn start(socket_dir: &str) -> Result<String, FfiError> {
    if let Some(existing) = AGENT_SOCK.get() {
        return Ok(existing.to_string_lossy().into_owned());
    }

    // Serialize startup so two callers can't both unlink + bind the socket —
    // the loser would orphan the winner's listener and silently lose its keys.
    let _guard = START_LOCK.lock().map_err(|_| FfiError::StateError {
        detail: "ssh agent start lock poisoned".to_owned(),
    })?;
    if let Some(existing) = AGENT_SOCK.get() {
        return Ok(existing.to_string_lossy().into_owned());
    }

    let sock_path = Path::new(socket_dir).join("agent.sock");

    // Remove any stale socket left by a previous process so bind succeeds.
    let _ = std::fs::remove_file(&sock_path);

    // Enter the TTY runtime: tokio's `UnixListener::bind` registers the socket
    // with the I/O reactor and fails ("no reactor running") when called outside
    // a runtime context — and this runs on a bare JNI thread.
    let _runtime = tty::runtime().enter();
    let listener = UnixListener::bind(&sock_path).map_err(|e| FfiError::IoError {
        detail: format!("failed to bind agent socket: {e}"),
    })?;
    // Restrict the socket to the app uid (belt-and-suspenders atop the 0700
    // app-private dir). Best-effort: a failure here is not fatal.
    let _ = std::fs::set_permissions(&sock_path, std::fs::Permissions::from_mode(0o600));

    let stream = UnixListenerStream::new(listener);
    // `()` implements `Agent` with `confirm_request` always true — no biometric.
    tty::runtime().spawn(async move {
        if let Err(e) = russh::keys::agent::server::serve(stream, ()).await {
            tracing::warn!(target: "tty::ssh_agent", "agent serve loop exited: {e}");
        }
    });

    // We hold START_LOCK and re-checked above, so this set always succeeds.
    let _ = AGENT_SOCK.set(sock_path.clone());
    Ok(sock_path.to_string_lossy().into_owned())
}

/// Adds a decrypted private key to the running agent.
///
/// Parses `private_pem` (unencrypted OpenSSH PEM bytes) into a
/// [`PrivateKey`] and registers it with the agent via a short-lived client
/// connection. The caller's buffer is zeroed before returning.
pub(crate) fn add_identity(mut private_pem: Vec<u8>) -> Result<(), FfiError> {
    let result = parse_and_add(&private_pem);
    private_pem.zeroize();
    result
}

/// Removes every identity from the running agent.
///
/// Used on key add/delete so the agent is reloaded from scratch by the
/// caller rather than accumulating stale identities.
pub(crate) fn reset() -> Result<(), FfiError> {
    run_op(AgentOp::RemoveAll)
}

/// An operation performed against the running agent over a fresh connection.
enum AgentOp {
    /// Remove every registered identity.
    RemoveAll,
    /// Register a parsed private key (boxed — `PrivateKey` is large).
    Add(Box<PrivateKey>),
}

/// Connects to the running agent and performs `op`.
///
/// Centralizes the socket-resolution, connect, and error-mapping shared by
/// [`reset`] and [`parse_and_add`].
fn run_op(op: AgentOp) -> Result<(), FfiError> {
    let sock = agent_sock_path()
        .ok_or_else(|| FfiError::StateError {
            detail: "ssh agent not started".to_owned(),
        })?
        .to_path_buf();
    tty::runtime().block_on(async move {
        let mut client = AgentClient::connect_uds(&sock)
            .await
            .map_err(|e| FfiError::IoError {
                detail: format!("failed to connect to ssh agent: {e}"),
            })?;
        match op {
            AgentOp::RemoveAll => {
                client
                    .remove_all_identities()
                    .await
                    .map_err(|e| FfiError::IoError {
                        detail: format!("failed to reset ssh agent: {e}"),
                    })
            }
            AgentOp::Add(key) => {
                client
                    .add_identity(&key, &[])
                    .await
                    .map_err(|e| FfiError::IoError {
                        detail: format!("failed to add identity to ssh agent: {e}"),
                    })
            }
        }
    })
}

/// Parses PEM bytes and registers the key with the agent.
///
/// Separated from [`add_identity`] so the buffer can be zeroed regardless of
/// success or failure.
fn parse_and_add(private_pem: &[u8]) -> Result<(), FfiError> {
    let key = PrivateKey::from_openssh(private_pem).map_err(|e| FfiError::InvalidArgument {
        detail: format!("failed to parse private key: {e}"),
    })?;
    run_op(AgentOp::Add(Box::new(key)))
}

// ── FFI exports ────────────────────────────────────────────────────────────

crate::ffi_export!(
    /// Starts the in-process ssh-agent and returns its socket path.
    ///
    /// Binds `<socket_dir>/agent.sock`, unlinking any stale socket first.
    /// Idempotent — repeated calls return the path of the already-running
    /// agent. The returned path is exported as `SSH_AUTH_SOCK` to terminal
    /// shells and the bundled `zssh` client.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::IoError`] if the socket cannot be bound, or
    /// [`crate::FfiError::InternalPanic`] if native code panics.
    fn ssh_agent_start(socket_dir: String) -> String = ssh_agent_start_inner
);

crate::ffi_export!(
    /// Adds a decrypted private key to the running ssh-agent.
    ///
    /// `private_pem` is unencrypted OpenSSH PEM bytes. The agent holds the
    /// key for the session; the buffer is zeroed before returning.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::InvalidArgument`] if the key cannot be
    /// parsed, [`crate::FfiError::StateError`] if the agent is not running,
    /// [`crate::FfiError::IoError`] if the agent rejects the identity, or
    /// [`crate::FfiError::InternalPanic`] if native code panics.
    fn ssh_agent_add_identity(private_pem: Vec<u8>) -> () = ssh_agent_add_identity_inner
);

crate::ffi_export!(
    /// Removes all identities from the running ssh-agent.
    ///
    /// Used on key add/delete so the agent can be reloaded from scratch.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::StateError`] if the agent is not running,
    /// [`crate::FfiError::IoError`] if the agent cannot be reached, or
    /// [`crate::FfiError::InternalPanic`] if native code panics.
    fn ssh_agent_reset() -> () = ssh_agent_reset_inner
);

// ── Inner implementations ──────────────────────────────────────────────────

pub(crate) fn ssh_agent_start_inner(socket_dir: String) -> Result<String, FfiError> {
    start(&socket_dir)
}

pub(crate) fn ssh_agent_add_identity_inner(private_pem: Vec<u8>) -> Result<(), FfiError> {
    add_identity(private_pem)
}

pub(crate) fn ssh_agent_reset_inner() -> Result<(), FfiError> {
    reset()
}
