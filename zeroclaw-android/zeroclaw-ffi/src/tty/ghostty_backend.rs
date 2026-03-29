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

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Instant;

use super::backend::{TerminalBackend, TerminalRenderSnapshot, TtyBackendError};
use super::ghostty_bridge::{KeyEncoder, MouseEncoder, RenderState, Terminal};

/// Default scrollback line count.
const DEFAULT_SCROLLBACK: usize = 10_000;

/// Default cell width in pixels (used for pixel dimension reporting).
const DEFAULT_CELL_WIDTH_PX: u32 = 8;

/// Default cell height in pixels.
const DEFAULT_CELL_HEIGHT_PX: u32 = 16;

/// Shared state passed as C callback userdata for all libghostty-vt
/// callbacks (write-PTY, bell, title-changed, etc.).
///
/// Stored behind `Arc` and leaked into the C userdata pointer via
/// [`Arc::into_raw`]. The `GhosttyBackend` holds one strong reference;
/// the leaked raw pointer is the second. Both are dropped in
/// [`GhosttyBackend::drop`].
pub(crate) struct CallbackState {
    /// Buffer for write-PTY callback responses (device status reports,
    /// cursor position queries, etc.).
    write_buf: Mutex<Vec<u8>>,
    /// Set by the bell callback; polled and cleared by Kotlin via
    /// [`GhosttyBackend::take_bell_event`].
    bell_pending: AtomicBool,
    /// Rate limiter: last time a bell was actually recorded.
    last_bell: Mutex<Option<Instant>>,
    /// Set by the title-changed callback; polled and cleared by
    /// [`GhosttyBackend::take_title_if_changed`].
    title_changed: AtomicBool,
}

/// Terminal backend powered by libghostty-vt.
pub(crate) struct GhosttyBackend {
    /// The VT terminal instance.
    terminal: Terminal,
    /// Render state for incremental dirty tracking.
    render_state: RenderState,
    /// Key encoder for converting key events to escape sequences.
    key_encoder: KeyEncoder,
    /// Mouse encoder for converting touch events to escape sequences.
    mouse_encoder: MouseEncoder,
    /// Callback state shared with the C callback via a raw pointer.
    callback_state: Arc<CallbackState>,
    /// Cached snapshot for synchronized output mode. When DEC 2026 is
    /// active, we return this instead of updating the render state.
    cached_snapshot: Option<TerminalRenderSnapshot>,
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
        let mouse_encoder = MouseEncoder::new()?;
        let callback_state = Arc::new(CallbackState {
            write_buf: Mutex::new(Vec::new()),
            bell_pending: AtomicBool::new(false),
            last_bell: Mutex::new(None),
            title_changed: AtomicBool::new(false),
        });

        // Register the write-PTY callback so terminal query responses
        // are captured into `callback_state.write_buf`.
        //
        // SAFETY: We clone the Arc (bringing strong count to 2) and
        // leak the clone via `Arc::into_raw`. The raw pointer is
        // passed as C userdata and reconstituted as a borrow (not an
        // owned Arc) inside the callback. The leaked Arc is recovered
        // in `Drop` after the callback is cleared. The callback only
        // appends to a Vec — no reentrancy into libghostty-vt.
        unsafe {
            let userdata =
                Arc::into_raw(Arc::clone(&callback_state)) as *mut std::ffi::c_void;
            terminal.set_write_pty_callback(Some(write_pty_callback), userdata);
            terminal.set_bell_callback(Some(bell_callback));
            terminal.set_title_changed_callback(Some(title_changed_callback));
        }

        Ok(Self {
            terminal,
            render_state,
            key_encoder,
            mouse_encoder,
            callback_state,
            cached_snapshot: None,
        })
    }

    /// Takes any pending write-PTY response bytes (terminal query
    /// responses that should be written back to the PTY).
    pub(crate) fn take_write_pty_response(&self) -> Vec<u8> {
        let mut buf = self
            .callback_state
            .write_buf
            .lock()
            .unwrap_or_else(|e| e.into_inner());
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

    /// Syncs the mouse encoder with the terminal's current mouse
    /// tracking mode and format state.
    pub(crate) fn sync_mouse_encoder(&mut self) {
        self.mouse_encoder.sync_from_terminal(&self.terminal);
    }

    /// Returns `true` if a bell event has fired since the last call,
    /// atomically clearing the pending flag.
    ///
    /// This is the poll-and-clear entry point called from the FFI
    /// layer on each render frame. O(1) — single atomic swap.
    pub(crate) fn take_bell_event(&self) -> bool {
        self.callback_state
            .bell_pending
            .swap(false, Ordering::AcqRel)
    }

    /// If the terminal title has changed since the last call, reads
    /// and returns the current title. Returns `None` if unchanged or
    /// if no title is set.
    ///
    /// Atomically clears the title-changed flag before reading.
    pub(crate) fn take_title_if_changed(&mut self) -> Option<String> {
        if self.callback_state.title_changed.swap(false, Ordering::AcqRel) {
            self.terminal.title()
        } else {
            None
        }
    }
}

impl TerminalBackend for GhosttyBackend {
    fn feed_input(&mut self, bytes: &[u8]) -> Result<(), TtyBackendError> {
        self.terminal.vt_write(bytes);
        self.mouse_encoder.sync_from_terminal(&self.terminal);
        Ok(())
    }

    fn resize(&mut self, cols: u16, rows: u16) -> Result<(), TtyBackendError> {
        self.terminal
            .resize(cols, rows, DEFAULT_CELL_WIDTH_PX, DEFAULT_CELL_HEIGHT_PX)
    }

    fn snapshot_for_render(&mut self) -> TerminalRenderSnapshot {
        if self.terminal.is_synchronized_output() {
            // App is mid-batch — return cached snapshot to prevent tearing.
            return self
                .cached_snapshot
                .clone()
                .unwrap_or_default();
        }

        let snapshot = self
            .render_state
            .snapshot(&self.terminal)
            .unwrap_or_else(|e| {
                tracing::error!(target: "tty::backend", "render snapshot failed: {e}");
                TerminalRenderSnapshot::default()
            });
        self.cached_snapshot = Some(snapshot.clone());
        snapshot
    }

    fn is_synchronized_output(&self) -> bool {
        self.terminal.is_synchronized_output()
    }

    fn is_bracketed_paste_active(&self) -> bool {
        self.terminal.is_bracketed_paste_active()
    }

    fn is_focus_reporting_active(&self) -> bool {
        self.terminal.is_focus_reporting_active()
    }

    fn is_mouse_tracking_active(&self) -> bool {
        MouseEncoder::is_tracking_active(&self.terminal)
    }

    fn encode_mouse_event(
        &mut self,
        action: u8,
        button: u8,
        x: f32,
        y: f32,
        mods: u32,
    ) -> Vec<u8> {
        self.mouse_encoder.encode(action, button, x, y, mods)
    }

    fn set_mouse_geometry(
        &mut self,
        cell_w: u32,
        cell_h: u32,
        screen_w: u32,
        screen_h: u32,
    ) {
        self.mouse_encoder.set_geometry(cell_w, cell_h, screen_w, screen_h);
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

    fn take_bell_event(&self) -> bool {
        self.take_bell_event()
    }

    fn take_title_if_changed(&mut self) -> Option<String> {
        self.take_title_if_changed()
    }

    fn apply_palette(&mut self, bg: u32, fg: u32, cursor: u32, palette: &[u32]) {
        let unpack = |argb: u32| -> (u8, u8, u8) {
            (
                ((argb >> 16) & 0xFF) as u8,
                ((argb >> 8) & 0xFF) as u8,
                (argb & 0xFF) as u8,
            )
        };

        // Set 16 ANSI palette colors (OSC 4).
        for (i, &color) in palette.iter().enumerate().take(16) {
            let (r, g, b) = unpack(color);
            self.terminal.set_palette_color(i as u16, r, g, b);
        }

        // Set background (OSC 11), foreground (OSC 10), cursor (OSC 12).
        let (r, g, b) = unpack(bg);
        self.terminal.set_palette_color(256, r, g, b);
        let (r, g, b) = unpack(fg);
        self.terminal.set_palette_color(257, r, g, b);
        let (r, g, b) = unpack(cursor);
        self.terminal.set_palette_color(258, r, g, b);
    }
}

impl Drop for GhosttyBackend {
    fn drop(&mut self) {
        // SAFETY: Recover the Arc<CallbackState> leaked into C userdata
        // in new(). Three invariants must hold for Arc::from_raw:
        //
        // 1. **Pointer origin**: The raw pointer was created by
        //    `Arc::into_raw(Arc::clone(&callback_state))` in `new()`.
        //    `Arc::as_ptr(&self.callback_state)` yields the same inner
        //    pointer because both Arcs share the same allocation.
        //
        // 2. **Single recovery**: This is the only site that calls
        //    `Arc::from_raw` for this allocation. `Drop` runs exactly
        //    once (GhosttyBackend is non-Copy, never `mem::forget`ed).
        //
        // 3. **No concurrent access to the raw pointer**: We clear the
        //    callback before recovering the Arc. Clearing is synchronous
        //    — `ghostty_terminal_set` immediately replaces the stored
        //    function pointer. All callbacks are invoked synchronously
        //    during `vt_write`, and we hold `&mut self` (which requires
        //    the session Mutex), so no `vt_write` can be in-flight.
        //    Therefore no callback will dereference the userdata pointer
        //    after this point.
        //
        // After recovery, the Arc's strong count drops from 2 to 1
        // (self.callback_state holds the remaining reference, which is
        // dropped when the struct fields are dropped).
        unsafe {
            self.terminal
                .set_write_pty_callback(None, std::ptr::null_mut());
            self.terminal.set_bell_callback(None);
            self.terminal.set_title_changed_callback(None);

            let _ = Arc::from_raw(
                Arc::as_ptr(&self.callback_state) as *const CallbackState,
            );
        }
    }
}

/// C callback invoked by libghostty-vt when the terminal needs to
/// write data back to the PTY (e.g. device status report responses).
///
/// # Safety
///
/// `userdata` must be a valid `*const CallbackState` created by
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

    // SAFETY: `userdata` was created by Arc::into_raw(Arc<CallbackState>)
    // in GhosttyBackend::new() and is valid for the lifetime of the
    // terminal. We borrow the pointer — we must NOT drop it.
    let state = unsafe { &*(userdata as *const CallbackState) };

    // SAFETY: `data` is valid for `len` bytes per the C API contract.
    let bytes = unsafe { std::slice::from_raw_parts(data, len) };

    state
        .write_buf
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .extend_from_slice(bytes);
}

/// Minimum interval between recorded bell events (rate limiter).
const BELL_COOLDOWN: std::time::Duration = std::time::Duration::from_millis(100);

/// C callback invoked by libghostty-vt when BEL (0x07) is received.
///
/// Rate-limited to at most one bell per [`BELL_COOLDOWN`] interval.
/// Sets [`CallbackState::bell_pending`] to `true` which is polled
/// and cleared by the Kotlin render loop via
/// [`GhosttyBackend::take_bell_event`].
///
/// # Safety
///
/// `userdata` must be a valid `*const CallbackState` created by
/// `Arc::into_raw` in [`GhosttyBackend::new`].
unsafe extern "C" fn bell_callback(
    _terminal: super::ghostty_sys::GhosttyTerminal,
    userdata: *mut std::ffi::c_void,
) {
    if userdata.is_null() {
        return;
    }

    // SAFETY: `userdata` was created by Arc::into_raw(Arc<CallbackState>)
    // in GhosttyBackend::new() and is valid for the lifetime of the
    // terminal. We borrow the pointer — we must NOT drop it.
    let state = unsafe { &*(userdata as *const CallbackState) };

    // Rate limit: only record a bell if 100ms+ has elapsed since the
    // last one. This prevents haptic spam from rapid BEL sequences.
    let now = Instant::now();
    let mut last = state.last_bell.lock().unwrap_or_else(|e| e.into_inner());
    if let Some(prev) = *last {
        if now.duration_since(prev) < BELL_COOLDOWN {
            return;
        }
    }
    *last = Some(now);
    state.bell_pending.store(true, Ordering::Release);
}

/// C callback invoked by libghostty-vt when the terminal title
/// changes via OSC 0 or OSC 2.
///
/// Sets [`CallbackState::title_changed`] to `true` which is polled
/// and cleared by the Kotlin render loop via
/// [`GhosttyBackend::take_title_if_changed`]. The actual title text
/// must be read separately via [`Terminal::title`].
///
/// # Safety
///
/// `userdata` must be a valid `*const CallbackState` created by
/// `Arc::into_raw` in [`GhosttyBackend::new`].
unsafe extern "C" fn title_changed_callback(
    _terminal: super::ghostty_sys::GhosttyTerminal,
    userdata: *mut std::ffi::c_void,
) {
    if userdata.is_null() {
        return;
    }

    // SAFETY: `userdata` was created by Arc::into_raw(Arc<CallbackState>)
    // in GhosttyBackend::new() and is valid for the lifetime of the
    // terminal. We borrow the pointer — we must NOT drop it.
    let state = unsafe { &*(userdata as *const CallbackState) };
    state.title_changed.store(true, Ordering::Release);
}
