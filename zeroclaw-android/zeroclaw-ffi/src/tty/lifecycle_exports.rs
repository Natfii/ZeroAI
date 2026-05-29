// Copyright (c) 2026 @Natfii. All rights reserved.

//! TTY lifecycle and render FFI exports.
//!
//! Covers create/destroy, byte I/O, resize, output snapshots, render
//! frames, render-signal blocking, and palette updates. Each export
//! dispatches between the local PTY backend ([`crate::tty::session`])
//! and the SSH backend ([`crate::tty::ssh`]) at the inner-shim layer,
//! keeping `lib.rs` free of the ssh/local fork.

use crate::error::FfiError;
use crate::tty::{self, types};

// ── FFI exports ────────────────────────────────────────────────────────────

crate::ffi_export!(
    /// Creates a new local PTY shell session.
    ///
    /// Opens a PTY pair, forks `/system/bin/sh`, and starts async
    /// read/write loops. Only one session can be active at a time.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::StateError`] if a session is already
    /// running, [`crate::FfiError::SpawnError`] if PTY creation or fork
    /// fails, or [`crate::FfiError::InternalPanic`] if native code panics.
    fn tty_create(cols: u32, rows: u32) -> () = tty_create_inner
);

crate::ffi_export!(
    /// Configures the local shell environment for the bundled `ssh` client.
    ///
    /// Creates a `bin/` directory under `files_dir`, symlinks `ssh` to the
    /// bundled `libssh.so` in `native_lib_dir`, and records the paths so
    /// every spawned shell gets `bin` on its `PATH` and `files_dir` as
    /// `HOME` (where `~/.ssh` host keys persist). Call once at startup,
    /// before creating any session.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::IoError`] if the directory or symlink
    /// cannot be created, or [`crate::FfiError::InternalPanic`] if native
    /// code panics.
    fn tty_configure_shell(native_lib_dir: String, files_dir: String) -> ()
        = tty_configure_shell_inner
);

crate::ffi_export!(
    /// Destroys the running local shell PTY session.
    ///
    /// Sends `SIGHUP` then `SIGKILL` to the child process and closes
    /// the master fd. Idempotent — returns `Ok` if no session is running.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::SpawnError`] if signal delivery fails,
    /// or [`crate::FfiError::InternalPanic`] if native code panics.
    fn tty_destroy() -> () = tty_destroy_inner
);

crate::ffi_export!(
    /// Writes raw bytes to the active TTY input (non-blocking).
    ///
    /// Dispatches to the SSH backend if a connection is active,
    /// otherwise to the local PTY session.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::StateError`] if no session is running,
    /// [`crate::FfiError::SpawnError`] if the write channel is full or
    /// closed, or [`crate::FfiError::InternalPanic`] if native code panics.
    fn tty_write(bytes: Vec<u8>) -> () = tty_write_inner
);

crate::ffi_export!(
    /// Resizes the active TTY to the given dimensions.
    ///
    /// Uses the `TIOCSWINSZ` ioctl on local PTYs. `width_px` and
    /// `height_px` update the local mouse-encoder geometry.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::StateError`] if no session is running,
    /// [`crate::FfiError::SpawnError`] if the ioctl fails, or
    /// [`crate::FfiError::InternalPanic`] if native code panics.
    fn tty_resize(cols: u32, rows: u32, width_px: u32, height_px: u32) -> () = tty_resize_inner
);

crate::ffi_export!(
    /// Returns the last `max_lines` output lines from the active TTY.
    ///
    /// Lines are returned oldest-first with ANSI escape sequences stripped.
    /// If fewer than `max_lines` are available, all lines are returned.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::StateError`] if no session is running,
    /// or [`crate::FfiError::InternalPanic`] if native code panics.
    fn tty_get_output_snapshot(max_lines: u32) -> Vec<String> = tty_get_output_snapshot_inner
);

crate::ffi_export!(
    /// Returns recent PTY output as a single scrubbed string for LLM
    /// context injection.
    ///
    /// Credentials are redacted and the result is capped at `max_bytes`
    /// (defaults to 64 KiB when `None`). Oldest lines are truncated
    /// first to fit the budget.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::StateError`] if no session is running,
    /// or [`crate::FfiError::InternalPanic`] if native code panics.
    fn tty_get_context(max_bytes: Option<u32>) -> String = tty_get_context_inner
);

crate::ffi_export!(
    /// Returns a complete render frame from the active TTY backend.
    ///
    /// Colors are packed ARGB (`0xAARRGGBB`). A value of `0x00000000`
    /// for a span's foreground or background means "use the terminal
    /// default" and must not be interpreted as opaque black.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::StateError`] if no session is running,
    /// or [`crate::FfiError::InternalPanic`] if native code panics.
    fn tty_get_render_frame() -> types::TtyRenderFrame = tty_get_render_frame_inner
);

crate::ffi_export!(
    /// Blocks until new terminal render data is available or timeout
    /// expires.
    ///
    /// Returns `true` if render data became available, `false` on timeout.
    /// Replaces a 100ms polling loop with event-driven updates.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::InternalPanic`] if native code panics.
    fn tty_wait_for_render_signal(timeout_ms: u64) -> bool = tty_wait_for_render_signal_inner
);

crate::ffi_export!(
    /// Applies a color theme to the active terminal session.
    ///
    /// `bg`, `fg`, `cursor` are packed ARGB (`0xAARRGGBB`). `palette`
    /// must contain exactly 16 entries (ANSI colors 0-15).
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::StateError`] if no session is running,
    /// [`crate::FfiError::InvalidArgument`] if palette length is wrong,
    /// or [`crate::FfiError::InternalPanic`] if native code panics.
    fn tty_set_palette(bg: u32, fg: u32, cursor: u32, palette: Vec<u32>) -> () = tty_set_palette_inner
);

// ── Inner implementations ──────────────────────────────────────────────────

pub(crate) fn tty_create_inner(cols: u32, rows: u32) -> Result<(), FfiError> {
    tty::session::create(cols as u16, rows as u16)
}

pub(crate) fn tty_configure_shell_inner(
    native_lib_dir: String,
    files_dir: String,
) -> Result<(), FfiError> {
    tty::session::configure_shell(&native_lib_dir, &files_dir)
}

pub(crate) fn tty_destroy_inner() -> Result<(), FfiError> {
    tty::session::destroy()
}

pub(crate) fn tty_write_inner(bytes: Vec<u8>) -> Result<(), FfiError> {
    tty::session::write_bytes(bytes)
}

pub(crate) fn tty_resize_inner(
    cols: u32,
    rows: u32,
    width_px: u32,
    height_px: u32,
) -> Result<(), FfiError> {
    let result = tty::session::resize(cols as u16, rows as u16);
    let _ = tty::session::set_mouse_geometry(cols as u16, rows as u16, width_px, height_px);
    result
}

pub(crate) fn tty_get_output_snapshot_inner(max_lines: u32) -> Result<Vec<String>, FfiError> {
    tty::session::get_output_lines(max_lines)
}

pub(crate) fn tty_get_context_inner(max_bytes: Option<u32>) -> Result<String, FfiError> {
    let limit = max_bytes.unwrap_or(65_536) as usize;
    tty::session::get_context(limit)
}

pub(crate) fn tty_get_render_frame_inner() -> Result<types::TtyRenderFrame, FfiError> {
    tty::session::get_render_frame()
}

#[allow(clippy::unnecessary_wraps)]
pub(crate) fn tty_wait_for_render_signal_inner(timeout_ms: u64) -> Result<bool, FfiError> {
    Ok(tty::session::wait_for_render_signal(timeout_ms))
}

pub(crate) fn tty_set_palette_inner(
    bg: u32,
    fg: u32,
    cursor: u32,
    palette: Vec<u32>,
) -> Result<(), FfiError> {
    if palette.len() != 16 {
        return Err(FfiError::InvalidArgument {
            detail: format!("palette must have 16 entries, got {}", palette.len()),
        });
    }
    tty::session::set_palette(bg, fg, cursor, &palette)
}
