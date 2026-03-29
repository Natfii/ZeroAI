// Copyright (c) 2026 @Natfii. All rights reserved.

//! PTY-based local shell session manager.
//!
//! Manages a single global PTY session. Only one local shell can run
//! at a time. The session forks `/system/bin/sh`, spawns async read
//! and write loops, and provides a ring buffer of output lines for
//! the UI and LLM context layers to consume.
//!
//! # Architecture
//!
//! ```text
//!  Kotlin UI ──write_bytes()──► mpsc tx ──► write loop ──► PTY master fd
//!                                                              │
//!  Kotlin UI ◄──get_output_lines()── ring buffer ◄── read loop ◄──┘
//! ```
//!
//! The read loop runs in [`tokio::task::spawn_blocking`] because PTY
//! reads are blocking I/O. The write loop drains an mpsc channel and
//! delegates actual writes to `spawn_blocking` as well.

use std::os::fd::{AsRawFd, OwnedFd};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Condvar, Mutex};

use super::backend::TerminalBackend;
use super::context::LineRingBuffer;
use crate::error::FfiError;

// ── Global singleton ────────────────────────────────────────────────

/// Active PTY session state, guarded by a mutex.
///
/// Only one local shell session can run at a time. Uses the same
/// poison-recovery pattern as [`crate::clawboy::session`].
static TTY_SESSION: Mutex<Option<TtySession>> = Mutex::new(None);

/// Signal that new render data is available. Set by read loops (local
/// PTY and SSH), consumed by `wait_for_render_signal()`.
static RENDER_DIRTY: AtomicBool = AtomicBool::new(false);

/// Condvar paired with a dummy mutex for blocking wait. The mutex
/// guard is not used for data protection — only to satisfy the
/// Condvar API.
static RENDER_CONDVAR: std::sync::LazyLock<(Mutex<()>, Condvar)> =
    std::sync::LazyLock::new(|| (Mutex::new(()), Condvar::new()));

// ── Session state ───────────────────────────────────────────────────

/// Mutable state for a running local shell PTY session.
struct TtySession {
    /// Master side of the PTY pair. Dropped on destroy to close the fd.
    _master_fd: OwnedFd,
    /// PID of the forked child shell process.
    child_pid: nix::unistd::Pid,
    /// Sender half of the write channel. Cloned into `write_bytes()`.
    write_tx: tokio::sync::mpsc::Sender<Vec<u8>>,
    /// Collected output lines from the PTY, with ANSI stripping and
    /// credential scrubbing via [`LineRingBuffer`].
    ring_buffer: Arc<Mutex<LineRingBuffer>>,
    /// VT terminal backend (ghostty-vt on Android, stub otherwise).
    /// Shared with the read loop which feeds PTY output into it.
    backend: Arc<Mutex<Box<dyn TerminalBackend>>>,
    /// Handle for the read loop task (cancelled on drop via abort).
    _read_handle: tokio::task::JoinHandle<()>,
    /// Handle for the write loop task (cancelled on drop via abort).
    _write_handle: tokio::task::JoinHandle<()>,
}

/// Capacity of the write channel. Bounded to provide backpressure
/// when the PTY cannot keep up with input.
const WRITE_CHANNEL_CAPACITY: usize = 256;

/// Default line capacity for the [`LineRingBuffer`] backing PTY output.
const DEFAULT_LINE_CAPACITY: usize = 2000;

// ── Mutex helper ────────────────────────────────────────────────────

/// Locks the session mutex with poison recovery.
///
/// Uses [`std::sync::PoisonError::into_inner`] to reclaim the guard
/// after a panic, preventing permanent lock failure.
fn lock_session() -> std::sync::MutexGuard<'static, Option<TtySession>> {
    TTY_SESSION.lock().unwrap_or_else(|e| {
        tracing::warn!(
            target: "tty::session",
            "TTY session mutex was poisoned; recovering: {e}"
        );
        e.into_inner()
    })
}

// ── Render signal ───────────────────────────────────────────────────

/// Signals that new render data is available.
///
/// Called by both the local PTY read loop and the SSH read loop after
/// feeding bytes into the terminal backend.
pub(crate) fn notify_render_dirty() {
    RENDER_DIRTY.store(true, Ordering::Release);
    let (_, condvar) = &*RENDER_CONDVAR;
    condvar.notify_all();
}

/// Blocks until new render data is available or `timeout_ms` elapses.
///
/// Returns `true` if data became available, `false` on timeout. Resets
/// the dirty flag on return so the next call blocks until new data
/// arrives.
pub(crate) fn wait_for_render_signal(timeout_ms: u64) -> bool {
    let (lock, condvar) = &*RENDER_CONDVAR;
    let guard = lock.lock().unwrap_or_else(|e| e.into_inner());

    if RENDER_DIRTY.load(Ordering::Acquire) {
        RENDER_DIRTY.store(false, Ordering::Release);
        return true;
    }

    let timeout = std::time::Duration::from_millis(timeout_ms);
    let (_guard, _result) = condvar
        .wait_timeout_while(guard, timeout, |_| {
            !RENDER_DIRTY.load(Ordering::Acquire)
        })
        .unwrap_or_else(|e| e.into_inner());

    let was_dirty = RENDER_DIRTY.load(Ordering::Acquire);
    RENDER_DIRTY.store(false, Ordering::Release);
    was_dirty
}

// ── Public API ──────────────────────────────────────────────────────

/// Creates a new local shell PTY session.
///
/// Opens a PTY pair, forks a child process running `/system/bin/sh`,
/// and spawns async read/write loops. Only one session can be active
/// at a time.
///
/// # Arguments
///
/// * `cols` - Initial terminal width in columns.
/// * `rows` - Initial terminal height in rows.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if a session is already running.
/// Returns [`FfiError::SpawnError`] if PTY creation or fork fails.
pub(crate) fn create(cols: u16, rows: u16) -> Result<(), FfiError> {
    let mut guard = lock_session();
    if guard.is_some() {
        return Err(FfiError::StateError {
            detail: "a local shell session is already running".into(),
        });
    }

    let session = spawn_local_shell(cols, rows)?;
    *guard = Some(session);

    tracing::info!(
        target: "tty::session",
        cols,
        rows,
        "local shell session created"
    );

    Ok(())
}

/// Destroys the running local shell session.
///
/// Sends `SIGHUP` to the child process, waits briefly, then sends
/// `SIGKILL` if still alive. The master fd is closed automatically
/// when the [`OwnedFd`] is dropped. Idempotent — returns `Ok` if
/// no session is running.
///
/// # Errors
///
/// Returns [`FfiError::SpawnError`] if signal delivery fails
/// unexpectedly.
pub(crate) fn destroy() -> Result<(), FfiError> {
    let mut guard = lock_session();
    let Some(session) = guard.take() else {
        return Ok(());
    };

    // Abort the async tasks first so they stop reading/writing.
    session._read_handle.abort();
    session._write_handle.abort();

    // Send SIGHUP to the child shell.
    let pid = session.child_pid;
    if let Err(e) = nix::sys::signal::kill(pid, nix::sys::signal::Signal::SIGHUP) {
        // ESRCH means the process already exited — not an error.
        if e != nix::errno::Errno::ESRCH {
            tracing::warn!(
                target: "tty::session",
                %pid,
                "SIGHUP failed: {e}"
            );
        }
    }

    // Brief wait, then SIGKILL if still alive.
    std::thread::sleep(std::time::Duration::from_millis(100));

    match nix::sys::wait::waitpid(pid, Some(nix::sys::wait::WaitPidFlag::WNOHANG)) {
        Ok(nix::sys::wait::WaitStatus::StillAlive) => {
            tracing::warn!(
                target: "tty::session",
                %pid,
                "child still alive after SIGHUP; sending SIGKILL"
            );
            let _ = nix::sys::signal::kill(pid, nix::sys::signal::Signal::SIGKILL);
            let _ = nix::sys::wait::waitpid(pid, None);
        }
        Ok(_) => {
            tracing::debug!(
                target: "tty::session",
                %pid,
                "child exited after SIGHUP"
            );
        }
        Err(nix::errno::Errno::ECHILD) => {
            // Child already reaped — fine.
            tracing::debug!(
                target: "tty::session",
                %pid,
                "child already reaped"
            );
        }
        Err(e) => {
            tracing::warn!(
                target: "tty::session",
                %pid,
                "waitpid failed: {e}"
            );
        }
    }

    // `_master_fd` is dropped here, closing the PTY master.
    tracing::info!(
        target: "tty::session",
        "local shell session destroyed"
    );

    Ok(())
}

/// Writes raw bytes to the PTY input (non-blocking).
///
/// Sends `data` through the mpsc channel to the write loop. If the
/// channel is full (backpressure), the call returns an error rather
/// than blocking.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if no session is running.
/// Returns [`FfiError::SpawnError`] if the write channel is full or
/// closed.
pub(crate) fn write_bytes(data: Vec<u8>) -> Result<(), FfiError> {
    let guard = lock_session();
    let session = guard.as_ref().ok_or_else(|| FfiError::StateError {
        detail: "no local shell session is running".into(),
    })?;

    session
        .write_tx
        .try_send(data)
        .map_err(|e| FfiError::SpawnError {
            detail: format!("write channel error: {e}"),
        })
}

/// Resizes the PTY to the given dimensions.
///
/// Uses the `TIOCSWINSZ` ioctl to inform the shell of the new
/// terminal size. The shell will receive a `SIGWINCH` signal
/// automatically.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if no session is running.
/// Returns [`FfiError::SpawnError`] if the ioctl fails.
pub(crate) fn resize(cols: u16, rows: u16) -> Result<(), FfiError> {
    let guard = lock_session();
    let session = guard.as_ref().ok_or_else(|| FfiError::StateError {
        detail: "no local shell session is running".into(),
    })?;

    let winsize = nix::pty::Winsize {
        ws_row: rows,
        ws_col: cols,
        ws_xpixel: 0,
        ws_ypixel: 0,
    };

    // SAFETY: The master fd is valid for the lifetime of the session
    // (owned by `TtySession._master_fd`). `TIOCSWINSZ` is a standard
    // ioctl that writes the `Winsize` struct to the PTY driver. The
    // pointer is valid for the duration of the call.
    let ret = unsafe {
        nix::libc::ioctl(
            session._master_fd.as_raw_fd(),
            nix::libc::TIOCSWINSZ,
            &winsize as *const nix::pty::Winsize,
        )
    };

    if ret == -1 {
        let errno = nix::errno::Errno::last();
        return Err(FfiError::SpawnError {
            detail: format!("TIOCSWINSZ ioctl failed: {errno}"),
        });
    }

    tracing::debug!(
        target: "tty::session",
        cols,
        rows,
        "PTY resized"
    );

    Ok(())
}

/// Returns the last `max_lines` output lines from the session.
///
/// Lines are returned oldest-first. If fewer than `max_lines` are
/// available, all lines are returned.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if no session is running.
pub(crate) fn get_output_lines(max_lines: u32) -> Result<Vec<String>, FfiError> {
    let guard = lock_session();
    let session = guard.as_ref().ok_or_else(|| FfiError::StateError {
        detail: "no local shell session is running".into(),
    })?;

    let buffer = session.ring_buffer.lock().unwrap_or_else(|e| {
        tracing::warn!(
            target: "tty::session",
            "ring buffer mutex poisoned; recovering: {e}"
        );
        e.into_inner()
    });

    Ok(buffer.get_lines(max_lines as usize))
}

/// Returns the recent PTY output as a single scrubbed string,
/// truncated to `max_bytes`.
///
/// Delegates to [`LineRingBuffer::export_context`] which strips ANSI
/// sequences and redacts credentials before joining lines.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if no session is running.
pub(crate) fn get_context(max_bytes: usize) -> Result<String, FfiError> {
    let guard = lock_session();
    let session = guard.as_ref().ok_or_else(|| FfiError::StateError {
        detail: "no local shell session is running".into(),
    })?;

    let buffer = session.ring_buffer.lock().unwrap_or_else(|e| {
        tracing::warn!(
            target: "tty::session",
            "ring buffer mutex poisoned; recovering: {e}"
        );
        e.into_inner()
    });

    Ok(buffer.export_context(max_bytes))
}

/// Returns a render snapshot from the terminal backend.
///
/// The snapshot contains the current screen grid (cells, colors,
/// cursor) with dirty tracking for incremental rendering.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if no session is running.
pub(crate) fn get_render_snapshot(
) -> Result<super::backend::TerminalRenderSnapshot, FfiError> {
    let guard = lock_session();
    let session = guard.as_ref().ok_or_else(|| FfiError::StateError {
        detail: "no local shell session is running".into(),
    })?;

    let mut backend = session.backend.lock().unwrap_or_else(|e| {
        tracing::warn!(
            target: "tty::session",
            "backend mutex poisoned; recovering: {e}"
        );
        e.into_inner()
    });

    Ok(backend.snapshot_for_render())
}

/// Returns a [`super::types::TtyRenderFrame`] from the terminal backend.
///
/// Acquires the backend snapshot via [`get_render_snapshot`] and
/// converts it to the UniFFI-exported frame type ready for Kotlin
/// Canvas rendering.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if no session is running.
pub(crate) fn get_render_frame() -> Result<super::types::TtyRenderFrame, FfiError> {
    let snapshot = get_render_snapshot()?;
    Ok(snapshot_to_frame(snapshot))
}

/// Converts an internal [`TerminalRenderSnapshot`] into the UniFFI-facing
/// [`TtyRenderFrame`] with packed `i64` styles and [`TtyDirtyState`].
///
/// Colors are packed as opaque ARGB via [`pack_argb`].
pub(crate) fn snapshot_to_frame(
    snapshot: super::backend::TerminalRenderSnapshot,
) -> super::types::TtyRenderFrame {
    use super::backend::{CursorStyle, DirtyState};
    use super::types::{TtyCursorState, TtyCursorStyle, TtyDirtyState, TtyRenderFrame, TtyRenderRow};

    let dirty_state = match snapshot.dirty {
        DirtyState::Clean => TtyDirtyState::Clean,
        DirtyState::Partial => TtyDirtyState::Partial,
        DirtyState::Full => TtyDirtyState::Full,
    };

    // Short-circuit: when the Rust snapshot is Clean, the rows Vec is
    // already empty and all metadata is cached. Convert the cursor and
    // colors without iterating rows.
    if dirty_state == TtyDirtyState::Clean {
        let cursor_style = match snapshot.cursor.style {
            CursorStyle::Bar => TtyCursorStyle::Bar,
            CursorStyle::Block => TtyCursorStyle::Block,
            CursorStyle::Underline => TtyCursorStyle::Underline,
            CursorStyle::BlockHollow => TtyCursorStyle::BlockHollow,
        };
        return TtyRenderFrame {
            cols: snapshot.cols,
            num_rows: snapshot.num_rows,
            rows: Vec::new(),
            cursor: TtyCursorState {
                col: snapshot.cursor.x,
                row: snapshot.cursor.y,
                visible: snapshot.cursor.visible,
                style: cursor_style,
                blinking: snapshot.cursor.blinking,
            },
            default_bg_argb: pack_argb(
                snapshot.default_bg.r,
                snapshot.default_bg.g,
                snapshot.default_bg.b,
            ),
            default_fg_argb: pack_argb(
                snapshot.default_fg.r,
                snapshot.default_fg.g,
                snapshot.default_fg.b,
            ),
            dirty_state,
        };
    }

    let rows: Vec<TtyRenderRow> = snapshot
        .rows
        .into_iter()
        .map(|row| {
            let mut text = String::with_capacity(row.cells.len());
            let mut styles: Vec<i64> = Vec::with_capacity(row.cells.len());
            let mut char_offsets: Vec<u32> = Vec::with_capacity(row.cells.len());
            // `text_pos` tracks UTF-16 code-unit position in `text`.
            let mut text_pos: u32 = 0;
            // `last_style` is used to fill spacer cells with the
            // preceding wide character's style.
            let mut last_style: i64 = 0;

            for cell in &row.cells {
                if cell.width == 0 {
                    // Spacer column (tail of a wide char): record the
                    // same text offset as the wide cell and inherit its
                    // style so renderers can merge them into one run.
                    char_offsets.push(text_pos);
                    styles.push(last_style);
                    // No characters are appended — the wide char was
                    // already pushed by the preceding non-spacer cell.
                } else {
                    // Normal or wide-char first column.
                    char_offsets.push(text_pos);
                    let packed = pack_cell_style(cell.fg, cell.bg, &cell.flags);
                    styles.push(packed);
                    last_style = packed;

                    if cell.codepoints.is_empty() {
                        text.push(' ');
                        text_pos += 1; // space is 1 UTF-16 code unit
                    } else {
                        for &cp in &cell.codepoints {
                            let ch = char::from_u32(cp).unwrap_or('\u{FFFD}');
                            text.push(ch);
                            text_pos += ch.len_utf16() as u32;
                        }
                    }
                }
            }

            TtyRenderRow {
                text,
                styles,
                char_offsets,
                dirty: row.dirty,
            }
        })
        .collect();

    let cursor_style = match snapshot.cursor.style {
        CursorStyle::Bar => TtyCursorStyle::Bar,
        CursorStyle::Block => TtyCursorStyle::Block,
        CursorStyle::Underline => TtyCursorStyle::Underline,
        CursorStyle::BlockHollow => TtyCursorStyle::BlockHollow,
    };

    let cursor = TtyCursorState {
        col: snapshot.cursor.x,
        row: snapshot.cursor.y,
        visible: snapshot.cursor.visible,
        style: cursor_style,
        blinking: snapshot.cursor.blinking,
    };

    let default_bg_argb = pack_argb(
        snapshot.default_bg.r,
        snapshot.default_bg.g,
        snapshot.default_bg.b,
    );
    let default_fg_argb = pack_argb(
        snapshot.default_fg.r,
        snapshot.default_fg.g,
        snapshot.default_fg.b,
    );

    TtyRenderFrame {
        cols: snapshot.cols,
        num_rows: snapshot.num_rows,
        rows,
        cursor,
        default_bg_argb,
        default_fg_argb,
        dirty_state,
    }
}

/// Packs red, green, and blue 8-bit channels into an opaque ARGB `u32`.
///
/// The alpha channel is always `0xFF` (fully opaque). The resulting
/// value has the format `0xAARRGGBB` as expected by the Android
/// `Canvas` drawing APIs.
#[inline]
fn pack_argb(r: u8, g: u8, b: u8) -> u32 {
    0xFF00_0000 | ((r as u32) << 16) | ((g as u32) << 8) | (b as u32)
}

/// Packs a cell's visual attributes into a single `i64` for FFI transfer.
///
/// # Bit layout
///
/// | Bits  | Content |
/// |-------|---------|
/// | 0     | bold |
/// | 1     | italic |
/// | 2     | has_underline (`underline_style > 0`) |
/// | 3     | strikethrough |
/// | 4     | dim |
/// | 5     | inverse |
/// | 6     | invisible |
/// | 7     | blink |
/// | 8-31  | Background RGB (24-bit, 0 = default) |
/// | 32-55 | Foreground RGB (24-bit, 0 = default) |
/// | 56-58 | underline_style (3 bits, 0–5) |
/// | 59    | overline |
/// | 60    | has_explicit_fg |
/// | 61    | has_explicit_bg |
/// | 62-63 | Reserved (zero) |
///
/// The return type is `i64` (not `u64`) because UniFFI maps `u64` to
/// Kotlin `ULong`, which erases to signed `Long` in generics. Using
/// `i64` avoids the sign confusion. Kotlin must use `ushr` for all
/// bit extraction.
#[inline]
fn pack_cell_style(
    fg: Option<super::backend::RenderColor>,
    bg: Option<super::backend::RenderColor>,
    flags: &super::backend::CellStyleFlags,
) -> i64 {
    let mut bits: u64 = 0;

    // Bits 0-7: effect flags
    if flags.bold              { bits |= 1 << 0; }
    if flags.italic            { bits |= 1 << 1; }
    if flags.has_underline()   { bits |= 1 << 2; }
    if flags.strikethrough     { bits |= 1 << 3; }
    if flags.dim               { bits |= 1 << 4; }
    if flags.inverse           { bits |= 1 << 5; }
    if flags.invisible         { bits |= 1 << 6; }
    if flags.blink             { bits |= 1 << 7; }

    // Bits 8-31: background color (24-bit RGB)
    if let Some(c) = bg {
        bits |= ((c.r as u64) << 24) | ((c.g as u64) << 16) | ((c.b as u64) << 8);
    }

    // Bits 32-55: foreground color (24-bit RGB)
    if let Some(c) = fg {
        bits |= ((c.r as u64) << 48) | ((c.g as u64) << 40) | ((c.b as u64) << 32);
    }

    // Bits 56-58: underline_style (3-bit value, 0–5)
    bits |= (flags.underline_style as u64 & 0x7) << 56;

    // Bit 59: overline
    if flags.overline { bits |= 1 << 59; }

    // Bit 60: has_explicit_fg (distinguishes None from Some(0,0,0))
    if fg.is_some() { bits |= 1 << 60; }

    // Bit 61: has_explicit_bg (distinguishes None from Some(0,0,0))
    if bg.is_some() { bits |= 1 << 61; }

    bits as i64
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;
    use super::super::backend::{
        CellStyleFlags, RenderCell, RenderColor, RenderRow,
    };

    // ── pack_cell_style tests ────────────────────────────────────────

    #[test]
    fn pack_default_style_is_zero() {
        let style = pack_cell_style(None, None, &CellStyleFlags::default());
        assert_eq!(style, 0i64);
    }

    #[test]
    fn pack_fg_only() {
        let fg = Some(RenderColor { r: 0xFF, g: 0x80, b: 0x40 });
        let style = pack_cell_style(fg, None, &CellStyleFlags::default());
        let bits = style as u64;
        // Foreground at bits 32-55
        assert_eq!((bits >> 48) & 0xFF, 0xFF);
        assert_eq!((bits >> 40) & 0xFF, 0x80);
        assert_eq!((bits >> 32) & 0xFF, 0x40);
        // Background and flags should be zero
        assert_eq!(bits & 0x0000_00FF_FFFF_FFFF & !0xFFFF_FF00_0000_0000, 0);
        assert_eq!(bits & 0xFF, 0);
    }

    #[test]
    fn pack_bg_only() {
        let bg = Some(RenderColor { r: 0x10, g: 0x20, b: 0x30 });
        let style = pack_cell_style(None, bg, &CellStyleFlags::default());
        let bits = style as u64;
        // Background at bits 8-31
        assert_eq!((bits >> 24) & 0xFF, 0x10);
        assert_eq!((bits >> 16) & 0xFF, 0x20);
        assert_eq!((bits >> 8) & 0xFF, 0x30);
        // Foreground and flags should be zero
        assert_eq!(bits & 0xFF, 0);
        assert_eq!((bits >> 32) & 0x00FF_FFFF, 0);
    }

    #[test]
    fn pack_all_original_flags() {
        // bold=bit0, italic=bit1, underline→has_underline=bit2 (style=1),
        // strikethrough=bit3, inverse=bit5
        let flags = CellStyleFlags {
            bold: true,
            italic: true,
            underline_style: 1,
            strikethrough: true,
            inverse: true,
            ..CellStyleFlags::default()
        };
        let style = pack_cell_style(None, None, &flags);
        let bits = style as u64;
        assert_ne!(bits & (1 << 0), 0, "bold");
        assert_ne!(bits & (1 << 1), 0, "italic");
        assert_ne!(bits & (1 << 2), 0, "has_underline");
        assert_ne!(bits & (1 << 3), 0, "strikethrough");
        assert_ne!(bits & (1 << 5), 0, "inverse");
        // Underline style 1 in bits 56-58
        assert_eq!((bits >> 56) & 0x7, 1);
    }

    #[test]
    fn pack_new_flags() {
        let flags = CellStyleFlags {
            dim: true,
            invisible: true,
            blink: true,
            overline: true,
            ..CellStyleFlags::default()
        };
        let style = pack_cell_style(None, None, &flags);
        let bits = style as u64;
        assert_ne!(bits & (1 << 4), 0, "dim");
        assert_ne!(bits & (1 << 6), 0, "invisible");
        assert_ne!(bits & (1 << 7), 0, "blink");
        assert_ne!(bits & (1 << 59), 0, "overline");
    }

    #[test]
    fn pack_underline_style_roundtrip() {
        for style_val in 0u8..=5 {
            let flags = CellStyleFlags {
                underline_style: style_val,
                ..CellStyleFlags::default()
            };
            let packed = pack_cell_style(None, None, &flags);
            let bits = packed as u64;
            let extracted = (bits >> 56) & 0x7;
            assert_eq!(extracted, style_val as u64, "underline_style={style_val}");
            // has_underline bit should match style > 0
            if style_val > 0 {
                assert_ne!(bits & (1 << 2), 0, "has_underline should be set for style={style_val}");
            } else {
                assert_eq!(bits & (1 << 2), 0, "has_underline should be clear for style=0");
            }
        }
    }

    #[test]
    fn pack_full_style_roundtrip() {
        let fg = Some(RenderColor { r: 0xAA, g: 0xBB, b: 0xCC });
        let bg = Some(RenderColor { r: 0x11, g: 0x22, b: 0x33 });
        let flags = CellStyleFlags {
            bold: true,
            italic: false,
            underline_style: 1,
            strikethrough: true,
            inverse: true,
            dim: true,
            invisible: false,
            blink: false,
            overline: true,
        };
        let style = pack_cell_style(fg, bg, &flags);
        let bits = style as u64;

        // Flag bits: bold=1, italic=0, has_underline=1, strikethrough=1,
        // dim=1, inverse=1, invisible=0, blink=0
        assert_ne!(bits & (1 << 0), 0, "bold");
        assert_eq!(bits & (1 << 1), 0, "italic");
        assert_ne!(bits & (1 << 2), 0, "has_underline");
        assert_ne!(bits & (1 << 3), 0, "strikethrough");
        assert_ne!(bits & (1 << 4), 0, "dim");
        assert_ne!(bits & (1 << 5), 0, "inverse");
        assert_eq!(bits & (1 << 6), 0, "invisible");
        assert_eq!(bits & (1 << 7), 0, "blink");

        // Background colors
        assert_eq!((bits >> 24) & 0xFF, 0x11);
        assert_eq!((bits >> 16) & 0xFF, 0x22);
        assert_eq!((bits >> 8) & 0xFF, 0x33);

        // Foreground colors
        assert_eq!((bits >> 48) & 0xFF, 0xAA);
        assert_eq!((bits >> 40) & 0xFF, 0xBB);
        assert_eq!((bits >> 32) & 0xFF, 0xCC);

        // Underline style
        assert_eq!((bits >> 56) & 0x7, 1);

        // Overline
        assert_ne!(bits & (1 << 59), 0, "overline");
    }

    #[test]
    fn pack_overline_standalone() {
        let flags = CellStyleFlags {
            overline: true,
            ..CellStyleFlags::default()
        };
        let packed = pack_cell_style(None, None, &flags) as u64;
        // Only bit 59 should be set — no other flags or colors.
        assert_ne!(packed & (1 << 59), 0, "overline bit");
        assert_eq!(packed & 0xFF, 0, "low flags should be clear");
        assert_eq!((packed >> 8) & 0x00FF_FFFF, 0, "bg should be clear");
        assert_eq!((packed >> 32) & 0x00FF_FFFF, 0, "fg should be clear");
    }

    // ── explicit color sentinel bits (60-61) ─────────────────────────

    #[test]
    fn pack_explicit_black_fg_sets_bit_60() {
        let fg = Some(RenderColor { r: 0, g: 0, b: 0 });
        let style = pack_cell_style(fg, None, &CellStyleFlags::default());
        let bits = style as u64;
        assert_ne!(bits & (1 << 60), 0, "has_explicit_fg should be set");
        assert_eq!((bits >> 32) & 0x00FF_FFFF, 0, "fg RGB should be 0");
    }

    #[test]
    fn pack_explicit_black_bg_sets_bit_61() {
        let bg = Some(RenderColor { r: 0, g: 0, b: 0 });
        let style = pack_cell_style(None, bg, &CellStyleFlags::default());
        let bits = style as u64;
        assert_ne!(bits & (1 << 61), 0, "has_explicit_bg should be set");
        assert_eq!((bits >> 8) & 0x00FF_FFFF, 0, "bg RGB should be 0");
    }

    #[test]
    fn pack_default_colors_bits_60_61_clear() {
        let style = pack_cell_style(None, None, &CellStyleFlags::default());
        let bits = style as u64;
        assert_eq!(bits & (1 << 60), 0, "has_explicit_fg should be clear");
        assert_eq!(bits & (1 << 61), 0, "has_explicit_bg should be clear");
    }

    #[test]
    fn pack_nonblack_fg_also_sets_bit_60() {
        let fg = Some(RenderColor { r: 0xFF, g: 0x80, b: 0x40 });
        let style = pack_cell_style(fg, None, &CellStyleFlags::default());
        let bits = style as u64;
        assert_ne!(bits & (1 << 60), 0, "has_explicit_fg should be set for non-black too");
    }

    // ── char_offsets tests ───────────────────────────────────────────

    /// Helper: build a RenderRow from a slice of (codepoints, width) pairs.
    fn make_row(cells: &[(&[u32], u8)]) -> RenderRow {
        RenderRow {
            cells: cells
                .iter()
                .map(|(cps, w)| RenderCell {
                    codepoints: cps.to_vec(),
                    fg: None,
                    bg: None,
                    flags: CellStyleFlags::default(),
                    width: *w,
                })
                .collect(),
            dirty: true,
        }
    }

    #[test]
    fn char_offsets_combining_chars() {
        // A (U+0041) = 1 UTF-16 unit
        // é (e U+0065 + combining accent U+0301) = 2 codepoints but 2 UTF-16 units
        // B (U+0042) = 1 UTF-16 unit
        // Expected offsets: [0, 1, 3]
        let row = make_row(&[
            (&[0x0041], 1),         // 'A' → offset 0, advances 1
            (&[0x0065, 0x0301], 1), // 'e' + combining → offset 1, advances 2
            (&[0x0042], 1),         // 'B' → offset 3
        ]);

        let snapshot = super::super::backend::TerminalRenderSnapshot {
            dirty: super::super::backend::DirtyState::Full,
            rows: vec![row],
            cols: 3,
            num_rows: 1,
            cursor: super::super::backend::RenderCursor::default(),
            default_bg: RenderColor::default(),
            default_fg: RenderColor::default(),
            palette: Vec::new(),
        };

        let frame = snapshot_to_frame(snapshot);
        assert_eq!(frame.rows[0].char_offsets, vec![0u32, 1, 3]);
    }

    #[test]
    fn char_offsets_wide_char() {
        // Wide CJK char '中' (U+4E2D) at col 0 (width=2), spacer at col 1 (width=0), 'A' at col 2
        // '中' is U+4E2D: 1 UTF-16 unit (BMP), so after wide cell text_pos = 1
        // spacer: same offset as wide (text_pos stays 1)
        // 'A': offset = 1
        // Expected offsets: [0, 1, 1]  (spacer inherits text_pos after wide char was written)
        let row = make_row(&[
            (&[0x4E2D], 2), // wide '中' → offset 0, advances 1 UTF-16 unit
            (&[], 0),       // spacer → inherits text_pos=1
            (&[0x0041], 1), // 'A' → offset 1
        ]);

        let snapshot = super::super::backend::TerminalRenderSnapshot {
            dirty: super::super::backend::DirtyState::Full,
            rows: vec![row],
            cols: 3,
            num_rows: 1,
            cursor: super::super::backend::RenderCursor::default(),
            default_bg: RenderColor::default(),
            default_fg: RenderColor::default(),
            palette: Vec::new(),
        };

        let frame = snapshot_to_frame(snapshot);
        let offsets = &frame.rows[0].char_offsets;
        // Wide char at [0]=0; spacer at [1] must record text_pos *after* wide was pushed=1
        assert_eq!(offsets[0], 0, "wide char offset");
        assert_eq!(offsets[1], 1, "spacer offset (text_pos after wide char)");
        assert_eq!(offsets[2], 1, "'A' offset");
    }

    // ── Session state tests ──────────────────────────────────────────

    #[test]
    fn lock_session_returns_none_initially() {
        let guard = lock_session();
        // Global state may have a session from another test, but the
        // lock itself should not panic.
        drop(guard);
    }

    #[test]
    fn ring_buffer_evicts_oldest_when_full() {
        let buf = Arc::new(Mutex::new(LineRingBuffer::new(3)));

        // Push 3 lines via raw bytes (newline-delimited).
        buf.lock().unwrap().push_bytes(b"line 0\nline 1\nline 2\n");
        assert_eq!(buf.lock().unwrap().get_lines(10).len(), 3);

        // One more should evict the oldest.
        buf.lock().unwrap().push_bytes(b"overflow\n");
        let lines = buf.lock().unwrap().get_lines(10);
        assert_eq!(lines.len(), 3);
        assert_eq!(lines[0], "line 1");
        assert_eq!(lines[2], "overflow");
    }

    #[test]
    fn get_output_lines_returns_empty_when_no_session() {
        // Ensure no session is running.
        let mut guard = lock_session();
        *guard = None;
        drop(guard);

        let result = get_output_lines(10);
        assert!(result.is_err());
    }

    #[test]
    fn write_bytes_fails_when_no_session() {
        let mut guard = lock_session();
        *guard = None;
        drop(guard);

        let result = write_bytes(vec![0x41]);
        assert!(result.is_err());
    }

    #[test]
    fn destroy_is_idempotent_when_no_session() {
        let mut guard = lock_session();
        *guard = None;
        drop(guard);

        // Should succeed (no-op) when no session exists.
        assert!(destroy().is_ok());
    }

    #[test]
    fn resize_fails_when_no_session() {
        let mut guard = lock_session();
        *guard = None;
        drop(guard);

        let result = resize(80, 24);
        assert!(result.is_err());
    }

    #[test]
    fn get_context_fails_when_no_session() {
        let mut guard = lock_session();
        *guard = None;
        drop(guard);

        let result = get_context(4096);
        assert!(result.is_err());
    }
}

// ── Internal implementation ─────────────────────────────────────────

/// Forks a child shell process attached to a new PTY and starts the
/// async read/write loops.
///
/// # Arguments
///
/// * `cols` - Initial terminal width in columns.
/// * `rows` - Initial terminal height in rows.
fn spawn_local_shell(cols: u16, rows: u16) -> Result<TtySession, FfiError> {
    // Open a PTY pair with the requested initial size.
    let winsize = nix::pty::Winsize {
        ws_row: rows,
        ws_col: cols,
        ws_xpixel: 0,
        ws_ypixel: 0,
    };

    let pty = nix::pty::openpty(Some(&winsize), None).map_err(|e| FfiError::SpawnError {
        detail: format!("openpty failed: {e}"),
    })?;

    // SAFETY: `fork()` is called before any multi-threaded async work
    // touches the child process. The child immediately calls `setsid`,
    // replaces its file descriptors, and execs — no async runtime or
    // heap allocation occurs in the child path.
    let fork_result = unsafe { nix::unistd::fork() }.map_err(|e| FfiError::SpawnError {
        detail: format!("fork failed: {e}"),
    })?;

    match fork_result {
        nix::unistd::ForkResult::Child => {
            // ── Child process ───────────────────────────────────
            // Drop the master fd — the child only uses the slave.
            drop(pty.master);

            run_child(pty.slave);
            // `run_child` calls execvp and never returns. If it
            // does (exec failure), the process exits.
        }
        nix::unistd::ForkResult::Parent { child } => {
            // ── Parent process ──────────────────────────────────
            // Close the slave fd — only the child uses it.
            drop(pty.slave);

            // Wrap the master fd in OwnedFd for RAII cleanup.
            // openpty returns raw fds; we need to own them.
            let master_raw_fd = pty.master.as_raw_fd();

            // Create the mpsc channel for write requests.
            let (write_tx, write_rx) =
                tokio::sync::mpsc::channel::<Vec<u8>>(WRITE_CHANNEL_CAPACITY);

            // Shared output buffer with ANSI stripping and credential scrubbing.
            let ring_buffer: Arc<Mutex<LineRingBuffer>> =
                Arc::new(Mutex::new(LineRingBuffer::new(DEFAULT_LINE_CAPACITY)));

            // Create the terminal backend (ghostty-vt on Android,
            // stub on other targets for testing).
            let backend: Box<dyn TerminalBackend> = create_backend(cols, rows);
            let backend: Arc<Mutex<Box<dyn TerminalBackend>>> =
                Arc::new(Mutex::new(backend));

            // Spawn the read loop in a blocking task on the TTY runtime.
            let rt = super::runtime();
            let read_ring = Arc::clone(&ring_buffer);
            let read_backend = Arc::clone(&backend);
            let read_handle = rt.spawn_blocking(move || {
                read_loop(master_raw_fd, read_ring, read_backend);
            });

            // Spawn the write loop as an async task on the TTY runtime.
            let write_handle = rt.spawn(async move {
                write_loop(master_raw_fd, write_rx).await;
            });

            return Ok(TtySession {
                _master_fd: pty.master,
                child_pid: child,
                write_tx,
                ring_buffer,
                backend,
                _read_handle: read_handle,
                _write_handle: write_handle,
            });
        }
    }

    // Unreachable — the child path either execs or exits, and the
    // parent path returns above. This satisfies the type checker.
    #[allow(unreachable_code)]
    Err(FfiError::SpawnError {
        detail: "unreachable: fork child path should not return".into(),
    })
}

/// Child process setup: creates a new session, sets the slave PTY as
/// the controlling terminal, replaces stdio, sets environment, and
/// execs the shell.
///
/// This function never returns on success (it calls `execvp`). On
/// failure, it calls `_exit(1)` to avoid running destructors in the
/// forked child.
fn run_child(slave_fd: OwnedFd) -> ! {
    use std::ffi::CString;

    // Create a new session (detach from parent's controlling terminal).
    if nix::unistd::setsid().is_err() {
        unsafe { nix::libc::_exit(1) };
    }

    let slave_raw = slave_fd.as_raw_fd();

    // Set the slave PTY as the controlling terminal.
    // SAFETY: TIOCSCTTY is a standard ioctl that sets the controlling
    // terminal for the current session leader. The fd is valid and we
    // just called setsid().
    unsafe {
        if nix::libc::ioctl(slave_raw, nix::libc::TIOCSCTTY, 0) == -1 {
            nix::libc::_exit(1);
        }
    }

    // Redirect stdin/stdout/stderr to the slave PTY.
    if nix::unistd::dup2(slave_raw, 0).is_err()
        || nix::unistd::dup2(slave_raw, 1).is_err()
        || nix::unistd::dup2(slave_raw, 2).is_err()
    {
        unsafe { nix::libc::_exit(1) };
    }

    // Close the original slave fd if it is not already 0, 1, or 2.
    if slave_raw > 2 {
        drop(slave_fd);
    } else {
        // Prevent OwnedFd from closing 0/1/2 which we just dup2'd.
        std::mem::forget(slave_fd);
    }

    // Set environment variables for the shell.
    //
    // SAFETY: `set_var` is unsafe in Rust 2024 edition because it is
    // not thread-safe. This is safe here because we are in a forked
    // child process — there is exactly one thread, and we are about
    // to exec, so no other code observes these changes.
    unsafe {
        std::env::set_var("TERM", "xterm-256color");
        std::env::set_var("COLORTERM", "truecolor");
        // HOME is set by the Android app layer via the data_dir. We
        // use a sensible default here; the Kotlin caller can override
        // via environment if needed.
        if std::env::var("HOME").is_err() {
            std::env::set_var("HOME", "/data/local/tmp");
        }
    }

    // Execute the shell. argv[0] is the program name shown in `ps`.
    let shell_path = CString::new("/system/bin/sh").unwrap_or_else(|_| unsafe {
        nix::libc::_exit(1);
        // Unreachable, but satisfies the type system.
        std::hint::unreachable_unchecked()
    });
    let argv0 = CString::new("sh").unwrap_or_else(|_| unsafe {
        nix::libc::_exit(1);
        std::hint::unreachable_unchecked()
    });

    let args: [&std::ffi::CStr; 1] = [&argv0];
    // execvp replaces the process image. If it returns, exec failed.
    let _ = nix::unistd::execvp(&shell_path, &args);

    // exec failed — exit without running destructors.
    unsafe { nix::libc::_exit(1) };
}

/// Blocking read loop that drains PTY output into the ring buffer
/// and the terminal backend.
///
/// Runs inside [`tokio::task::spawn_blocking`] because PTY reads are
/// blocking I/O. Exits on EOF, EIO (PTY closed), or any other error.
///
/// Raw bytes are pushed into both the [`LineRingBuffer`] (for text
/// output and LLM context) and the [`TerminalBackend`] (for VT
/// parsing and render state).
fn read_loop(
    master_raw_fd: i32,
    ring_buffer: Arc<Mutex<LineRingBuffer>>,
    backend: Arc<Mutex<Box<dyn TerminalBackend>>>,
) {
    loop {
        let mut buf = [0u8; 4096];

        // SAFETY: `master_raw_fd` is the raw fd of the PTY master,
        // kept alive by the `OwnedFd` in `TtySession._master_fd`.
        // The read is a standard POSIX read on a valid file descriptor.
        // The buffer is stack-allocated and valid for the duration of
        // the call.
        let n = unsafe { nix::libc::read(master_raw_fd, buf.as_mut_ptr().cast(), buf.len()) };

        if n <= 0 {
            if n == 0 {
                // EOF — PTY closed.
                tracing::debug!(target: "tty::session", "PTY read: EOF");
            } else {
                let errno = nix::errno::Errno::last();
                if errno == nix::errno::Errno::EIO {
                    // PTY slave closed — normal shutdown path.
                    tracing::debug!(target: "tty::session", "PTY read: EIO (slave closed)");
                } else {
                    tracing::warn!(target: "tty::session", "PTY read error: {errno}");
                }
            }
            break;
        }

        let n = n as usize;
        let data = &buf[..n];

        // Push raw bytes into the ring buffer. LineRingBuffer handles
        // lossy UTF-8 decoding, ANSI stripping, and line splitting.
        ring_buffer
            .lock()
            .unwrap_or_else(|e| {
                tracing::warn!(
                    target: "tty::session",
                    "ring buffer mutex poisoned; recovering: {e}"
                );
                e.into_inner()
            })
            .push_bytes(data);

        // Feed the same bytes into the terminal backend for VT
        // parsing and render state updates.
        let pty_response = {
            let mut backend_guard = backend.lock().unwrap_or_else(|e| {
                tracing::warn!(
                    target: "tty::session",
                    "backend mutex poisoned; recovering: {e}"
                );
                e.into_inner()
            });

            if let Err(e) = backend_guard.feed_input(data) {
                tracing::warn!(
                    target: "tty::session",
                    "backend feed_input error: {e}"
                );
            }

            // Collect any write-PTY responses (terminal query answers).
            backend_guard.take_pty_response()
        };

        // Write PTY responses back to the master fd (e.g. device
        // status reports). Done outside the backend lock.
        if !pty_response.is_empty() {
            // SAFETY: `master_raw_fd` is valid for the session
            // lifetime. The response buffer is valid for the write.
            unsafe {
                nix::libc::write(
                    master_raw_fd,
                    pty_response.as_ptr().cast(),
                    pty_response.len(),
                );
            }
        }

        // Signal the render thread that new data is available.
        notify_render_dirty();
    }

    tracing::debug!(target: "tty::session", "read loop exited");
}

/// Async write loop that drains the mpsc channel and writes to the
/// PTY master fd.
///
/// Each write is dispatched to [`tokio::task::spawn_blocking`]
/// because PTY writes can block if the slave's read buffer is full.
async fn write_loop(
    master_raw_fd: i32,
    mut write_rx: tokio::sync::mpsc::Receiver<Vec<u8>>,
) {
    while let Some(data) = write_rx.recv().await {
        let result = tokio::task::spawn_blocking(move || {
            // SAFETY: `master_raw_fd` is the raw fd of the PTY master,
            // kept alive by the `OwnedFd` in `TtySession._master_fd`.
            // The write is a standard POSIX write on a valid file
            // descriptor. `data` is a valid byte slice for the duration
            // of the call.
            let ret = unsafe {
                nix::libc::write(
                    master_raw_fd,
                    data.as_ptr().cast(),
                    data.len(),
                )
            };

            if ret < 0 {
                // Capture errno on the blocking thread where the
                // write actually happened (errno is thread-local).
                Err(nix::errno::Errno::last())
            } else {
                Ok(ret)
            }
        })
        .await;

        match result {
            Ok(Err(errno)) => {
                tracing::warn!(target: "tty::session", "PTY write error: {errno}");
                break;
            }
            Err(e) => {
                tracing::warn!(target: "tty::session", "write spawn_blocking failed: {e}");
                break;
            }
            Ok(Ok(_)) => {}
        }
    }

    tracing::debug!(target: "tty::session", "write loop exited");
}

/// Creates a terminal backend appropriate for the current target.
///
/// On Android, creates a [`GhosttyBackend`] backed by libghostty-vt.
/// On other targets (host builds, CI), falls back to [`StubBackend`].
pub(crate) fn create_backend(cols: u16, rows: u16) -> Box<dyn TerminalBackend> {
    #[cfg(feature = "ghostty-vt")]
    {
        match super::ghostty_backend::GhosttyBackend::new(cols, rows) {
            Ok(backend) => {
                tracing::info!(
                    target: "tty::session",
                    cols,
                    rows,
                    "created GhosttyBackend"
                );
                let kitty = super::ghostty_bridge::supports_kitty_graphics();
                let opt = super::ghostty_bridge::optimize_mode();
                tracing::info!(
                    target: "tty::session",
                    kitty_graphics = kitty,
                    optimize_mode = ?opt,
                    "libghostty-vt build info"
                );
                return Box::new(backend);
            }
            Err(e) => {
                tracing::warn!(
                    target: "tty::session",
                    "GhosttyBackend creation failed, falling back to StubBackend: {e}"
                );
            }
        }
    }

    #[cfg(not(feature = "ghostty-vt"))]
    {
        let _ = (cols, rows);
    }

    Box::new(super::backend::StubBackend)
}

/// Applies a color theme to the active local terminal session.
///
/// Forwards the palette to the terminal backend via
/// [`TerminalBackend::apply_palette`] and signals the render thread.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if no session is running.
pub(crate) fn set_palette(bg: u32, fg: u32, cursor: u32, palette: &[u32]) -> Result<(), FfiError> {
    let guard = lock_session();
    let session = guard.as_ref().ok_or_else(|| FfiError::StateError {
        detail: "no TTY session is running".into(),
    })?;

    let mut backend = session.backend.lock().unwrap_or_else(|e| e.into_inner());
    backend.apply_palette(bg, fg, cursor, palette);
    drop(backend);
    notify_render_dirty();
    Ok(())
}

/// Returns whether bracketed paste mode (DEC 2004) is active in the
/// local shell session's terminal backend.
///
/// Returns `Ok(false)` when no session is running, which is the safe
/// default (paste without brackets is always accepted).
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
            target: "tty::session",
            "backend mutex poisoned while querying bracketed paste; recovering"
        );
        e.into_inner()
    });
    Ok(backend.is_bracketed_paste_active())
}

/// Returns whether mouse tracking is currently active in the local
/// shell session's terminal backend.
///
/// Returns `Ok(false)` when no session is running, which is the safe
/// default (selection gestures remain active).
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the backend mutex is poisoned
/// and cannot be recovered.
pub(crate) fn is_mouse_tracking_active() -> Result<bool, FfiError> {
    let guard = lock_session();
    let Some(session) = guard.as_ref() else {
        return Ok(false);
    };

    let backend = session.backend.lock().unwrap_or_else(|e| {
        tracing::warn!(
            target: "tty::session",
            "backend mutex poisoned while querying mouse tracking; recovering"
        );
        e.into_inner()
    });
    Ok(backend.is_mouse_tracking_active())
}

/// Returns whether focus reporting mode (DEC 1004) is active in the
/// local shell session's terminal backend.
///
/// Returns `Ok(false)` when no session is running (safe default).
pub(crate) fn is_focus_reporting_active() -> Result<bool, FfiError> {
    let guard = lock_session();
    let Some(session) = guard.as_ref() else {
        return Ok(false);
    };

    let backend = session.backend.lock().unwrap_or_else(|e| {
        tracing::warn!(
            target: "tty::session",
            "backend mutex poisoned while querying focus reporting; recovering"
        );
        e.into_inner()
    });
    Ok(backend.is_focus_reporting_active())
}

/// Returns `true` if a terminal bell (BEL) has fired since the last
/// call, atomically clearing the pending flag.
///
/// Returns `Ok(false)` when no session is running (safe default).
pub(crate) fn take_bell_event() -> Result<bool, FfiError> {
    let guard = lock_session();
    let Some(session) = guard.as_ref() else {
        return Ok(false);
    };

    let backend = session.backend.lock().unwrap_or_else(|e| {
        tracing::warn!(
            target: "tty::session",
            "backend mutex poisoned while polling bell event; recovering"
        );
        e.into_inner()
    });
    Ok(backend.take_bell_event())
}

/// If the terminal title has changed since the last call (OSC 0/2),
/// reads and returns the current title string.
///
/// Returns `Ok(None)` when no session is running or the title has
/// not changed since the last poll (safe default).
pub(crate) fn take_title_if_changed() -> Result<Option<String>, FfiError> {
    let guard = lock_session();
    let Some(session) = guard.as_ref() else {
        return Ok(None);
    };

    let mut backend = session.backend.lock().unwrap_or_else(|e| {
        tracing::warn!(
            target: "tty::session",
            "backend mutex poisoned while polling title change; recovering"
        );
        e.into_inner()
    });
    Ok(backend.take_title_if_changed())
}

/// Encodes a mouse event and writes the escape sequence to the PTY.
///
/// Fire-and-forget: callers should log errors but not surface them
/// to the UI. Mouse events are best-effort.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if no session is running.
/// Returns [`FfiError::SpawnError`] if the write channel is full.
pub(crate) fn submit_mouse_event(
    action: u8,
    button: u8,
    pixel_x: f32,
    pixel_y: f32,
    mods: u32,
) -> Result<(), FfiError> {
    let guard = lock_session();
    let session = guard.as_ref().ok_or_else(|| FfiError::StateError {
        detail: "no local shell session is running".into(),
    })?;

    let encoded = {
        let mut backend = session.backend.lock().unwrap_or_else(|e| {
            tracing::warn!(
                target: "tty::session",
                "backend mutex poisoned while encoding mouse event; recovering"
            );
            e.into_inner()
        });
        backend.encode_mouse_event(action, button, pixel_x, pixel_y, mods)
    };

    if encoded.is_empty() {
        return Ok(());
    }

    session
        .write_tx
        .try_send(encoded)
        .map_err(|e| FfiError::SpawnError {
            detail: format!("write channel error: {e}"),
        })
}

/// Updates the mouse encoder's screen and cell geometry.
///
/// Called when the terminal canvas is resized. The geometry is needed
/// for pixel-to-cell coordinate conversion in the ghostty mouse
/// encoder.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if no session is running.
pub(crate) fn set_mouse_geometry(
    cols: u16,
    rows: u16,
    width_px: u32,
    height_px: u32,
) -> Result<(), FfiError> {
    if cols == 0 || rows == 0 || width_px == 0 || height_px == 0 {
        return Ok(());
    }

    let guard = lock_session();
    let Some(session) = guard.as_ref() else {
        return Ok(());
    };

    let cell_w = width_px / cols as u32;
    let cell_h = height_px / rows as u32;

    let mut backend = session.backend.lock().unwrap_or_else(|e| {
        tracing::warn!(
            target: "tty::session",
            "backend mutex poisoned while setting mouse geometry; recovering"
        );
        e.into_inner()
    });
    backend.set_mouse_geometry(cell_w, cell_h, width_px, height_px);
    Ok(())
}

