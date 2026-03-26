// Copyright (c) 2026 @Natfii. All rights reserved.

//! [`TerminalBackend`] implementation backed by libghostty-vt.
//!
//! Uses the safe wrappers in [`super::ghostty_bridge`] to manage a
//! terminal instance, render state, and key encoder. PTY output is
//! fed through the VT parser and rendered into
//! [`TerminalRenderSnapshot`] frames on demand.
//!
//! A write-PTY callback is registered so that terminal query responses
//! (device status reports, etc.) are captured and can be written back
//! to the PTY by the session layer.

use std::sync::{Arc, Mutex};

use super::backend::{TerminalBackend, TerminalRenderSnapshot, TtyBackendError};
use super::ghostty_bridge::{KeyEncoder, RenderState, Terminal};

/// Default scrollback line count.
const DEFAULT_SCROLLBACK: usize = 10_000;

/// Default cell width in pixels (used for pixel dimension reporting).
const DEFAULT_CELL_WIDTH_PX: u32 = 8;

/// Default cell height in pixels.
const DEFAULT_CELL_HEIGHT_PX: u32 = 16;

/// Terminal backend powered by libghostty-vt.
pub(crate) struct GhosttyBackend {
    /// The VT terminal instance.
    terminal: Terminal,
    /// Render state for incremental dirty tracking.
    render_state: RenderState,
    /// Key encoder for converting key events to escape sequences.
    key_encoder: KeyEncoder,
    /// Buffer for write-PTY callback responses. Shared with the C
    /// callback via a raw pointer.
    write_pty_buf: Arc<Mutex<Vec<u8>>>,
}

// SAFETY: All inner types implement Send. The write_pty_buf Arc is
// thread-safe by construction.
unsafe impl Send for GhosttyBackend {}

impl GhosttyBackend {
    /// Creates a new ghostty-backed terminal with the given dimensions.
    pub(crate) fn new(cols: u16, rows: u16) -> Result<Self, TtyBackendError> {
        let mut terminal = Terminal::new(cols, rows, DEFAULT_SCROLLBACK)?;
        let render_state = RenderState::new()?;
        let key_encoder = KeyEncoder::new()?;
        let write_pty_buf: Arc<Mutex<Vec<u8>>> = Arc::new(Mutex::new(Vec::new()));

        // Register the write-PTY callback so terminal query responses
        // are captured into `write_pty_buf`.
        //
        // SAFETY: The `Arc<Mutex<Vec<u8>>>` is converted to a raw
        // pointer that the C callback receives as userdata. The Arc
        // is kept alive by `self.write_pty_buf` for the lifetime of
        // the terminal. The callback is safe to call from within
        // `ghostty_terminal_vt_write` (no reentrancy — it only
        // appends to a Vec).
        unsafe {
            let userdata = Arc::into_raw(Arc::clone(&write_pty_buf)) as *mut std::ffi::c_void;
            terminal.set_write_pty_callback(Some(write_pty_callback), userdata);
        }

        Ok(Self {
            terminal,
            render_state,
            key_encoder,
            write_pty_buf,
        })
    }

    /// Takes any pending write-PTY response bytes (terminal query
    /// responses that should be written back to the PTY).
    pub(crate) fn take_write_pty_response(&self) -> Vec<u8> {
        let mut buf = self.write_pty_buf.lock().unwrap_or_else(|e| e.into_inner());
        std::mem::take(&mut *buf)
    }

    /// Returns a mutable reference to the key encoder for encoding
    /// key events.
    pub(crate) fn key_encoder_mut(&mut self) -> &mut KeyEncoder {
        &mut self.key_encoder
    }

    /// Syncs the key encoder with the terminal's current mode state.
    pub(crate) fn sync_key_encoder(&mut self) {
        self.key_encoder.sync_from_terminal(&self.terminal);
    }
}

impl TerminalBackend for GhosttyBackend {
    fn feed_input(&mut self, bytes: &[u8]) -> Result<(), TtyBackendError> {
        self.terminal.vt_write(bytes);
        Ok(())
    }

    fn resize(&mut self, cols: u16, rows: u16) -> Result<(), TtyBackendError> {
        self.terminal
            .resize(cols, rows, DEFAULT_CELL_WIDTH_PX, DEFAULT_CELL_HEIGHT_PX)
    }

    fn snapshot_for_render(&mut self) -> TerminalRenderSnapshot {
        self.render_state
            .snapshot(&self.terminal)
            .unwrap_or_default()
    }

    fn snapshot_for_accessibility(&self, visible_rows: usize) -> Vec<String> {
        // For accessibility, we produce one string per visible row by
        // converting codepoints to chars. This is a simplified path
        // that avoids a full render state cycle — it reads from the
        // terminal grid directly via grid_ref (deferred to a later
        // phase when the grid_ref API wrapper is added).
        //
        // For now, return empty — the LineRingBuffer in session.rs
        // provides the accessible text until this is fully wired.
        let _ = visible_rows;
        Vec::new()
    }

    fn snapshot_for_context(&self, max_bytes: usize) -> Vec<u8> {
        // Context extraction for @zero agent is handled by
        // LineRingBuffer in session.rs, which strips ANSI and scrubs
        // credentials. The backend's role here is supplementary.
        //
        // Deferred: use the formatter API to extract plain text from
        // the terminal grid for richer context.
        let _ = max_bytes;
        Vec::new()
    }

    fn take_pty_response(&self) -> Vec<u8> {
        self.take_write_pty_response()
    }
}

impl Drop for GhosttyBackend {
    fn drop(&mut self) {
        // Recover the Arc we leaked into the C callback userdata.
        // Clear the callback first to prevent use-after-free.
        //
        // SAFETY: We set the callback to None before recovering the
        // Arc. The raw pointer was created by Arc::into_raw in new().
        unsafe {
            self.terminal
                .set_write_pty_callback(None, std::ptr::null_mut());

            // Recover the leaked Arc. We cloned it in new(), so there
            // are two strong refs: self.write_pty_buf and this one.
            // Dropping this one brings the count back to 1.
            let _ = Arc::from_raw(
                Arc::as_ptr(&self.write_pty_buf) as *const Mutex<Vec<u8>>,
            );
        }
    }
}

/// C callback invoked by libghostty-vt when the terminal needs to
/// write data back to the PTY (e.g. device status report responses).
///
/// # Safety
///
/// `userdata` must be a valid `*const Mutex<Vec<u8>>` created by
/// `Arc::into_raw`. `data` must be valid for `len` bytes.
unsafe extern "C" fn write_pty_callback(
    _terminal: super::ghostty_sys::GhosttyTerminal,
    userdata: *mut std::ffi::c_void,
    data: *const u8,
    len: usize,
) {
    if userdata.is_null() || data.is_null() || len == 0 {
        return;
    }

    // SAFETY: `userdata` was created by Arc::into_raw and is valid
    // for the lifetime of the terminal (guaranteed by GhosttyBackend).
    // We must NOT drop this reference — just borrow it.
    let buf_ptr = userdata as *const Mutex<Vec<u8>>;
    let buf = unsafe { &*buf_ptr };

    // SAFETY: `data` is valid for `len` bytes per the C API contract.
    let bytes = unsafe { std::slice::from_raw_parts(data, len) };

    if let Ok(mut guard) = buf.lock() {
        guard.extend_from_slice(bytes);
    }
}
