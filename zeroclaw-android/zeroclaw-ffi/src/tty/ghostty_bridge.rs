// Copyright (c) 2026 @Natfii. All rights reserved.

//! Safe Rust wrappers around the libghostty-vt C API.
//!
//! Each opaque handle type from [`super::ghostty_sys`] is wrapped in a
//! Rust struct that owns the handle and frees it on [`Drop`]. Fallible
//! C calls are checked by [`check`] / [`check_with_len`], which return
//! [`GhosttyError`]; the [`From`] impl converts these to
//! [`TtyBackendError`] at the public API boundary via `?`.
//!
//! Thread safety: libghostty-vt is **not** thread-safe. All access to
//! a single terminal + render state must be serialised. The caller
//! (typically behind a `Mutex`) is responsible for this.

use std::marker::PhantomData;
use std::ptr::{self, NonNull};

use super::backend::{
    CellStyleFlags, CursorStyle, DirtyState, RenderCell, RenderColor, RenderCursor, RenderRow,
    TerminalRenderSnapshot, TtyBackendError,
};
use super::ghostty_sys::*;

// ── Error Type ──────────────────────────────────────────────────────

/// Structured errors returned by the ghostty C API helpers.
///
/// Each variant preserves the failure kind so that callers can
/// pattern-match without parsing a formatted string. The [`Display`]
/// impl produces a human-readable message that matches the previous
/// string-formatted output exactly, so [`From<GhosttyError> for TtyBackendError`]
/// is lossless in terms of diagnostic information.
#[derive(Debug, Clone)]
pub(crate) enum GhosttyError {
    /// The C allocator returned null.
    OutOfMemory { context: &'static str },
    /// A function argument or result was out of the accepted range.
    InvalidValue { context: &'static str },
    /// The output buffer was too small; `required` is the size the API
    /// reported as necessary.
    OutOfSpace { context: &'static str, required: usize },
    /// A handle returned by the C API was null.
    NullHandle { context: &'static str },
}

impl std::fmt::Display for GhosttyError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::OutOfMemory { context } => write!(f, "{context}: out of memory"),
            Self::InvalidValue { context } => write!(f, "{context}: invalid value"),
            Self::OutOfSpace { context, required } => {
                write!(f, "{context}: out of space ({required} bytes required)")
            }
            Self::NullHandle { context } => write!(f, "{context}: null handle"),
        }
    }
}

impl std::error::Error for GhosttyError {}

impl From<GhosttyError> for TtyBackendError {
    fn from(e: GhosttyError) -> Self {
        TtyBackendError::Internal { detail: e.to_string() }
    }
}

// ── Helpers ─────────────────────────────────────────────────────────

/// Converts a [`GhosttyResult`] to `Ok(())` or a [`GhosttyError`].
///
/// The `context` parameter is a `&'static str` so no allocation occurs
/// on the error path — the string literal is embedded in the binary.
#[inline]
fn check(result: GhosttyResult, context: &'static str) -> Result<(), GhosttyError> {
    match result {
        GhosttyResult::Success => Ok(()),
        GhosttyResult::OutOfMemory => Err(GhosttyError::OutOfMemory { context }),
        GhosttyResult::InvalidValue => Err(GhosttyError::InvalidValue { context }),
        GhosttyResult::OutOfSpace => Err(GhosttyError::OutOfSpace { context, required: 0 }),
    }
}

/// Like [`check`], but interprets the accompanying `len` value as the
/// number of bytes required when the result is [`GhosttyResult::OutOfSpace`].
///
/// Returns `Ok(len)` on success so callers can propagate the written
/// byte count in a single expression.
#[allow(dead_code)] // Used by upcoming zero-alloc grapheme extraction
#[inline]
fn check_with_len(
    result: GhosttyResult,
    len: usize,
    context: &'static str,
) -> Result<usize, GhosttyError> {
    match result {
        GhosttyResult::Success => Ok(len),
        GhosttyResult::OutOfMemory => Err(GhosttyError::OutOfMemory { context }),
        GhosttyResult::InvalidValue => Err(GhosttyError::InvalidValue { context }),
        GhosttyResult::OutOfSpace => Err(GhosttyError::OutOfSpace { context, required: len }),
    }
}

fn color_from_c(c: GhosttyColorRgb) -> RenderColor {
    RenderColor {
        r: c.r,
        g: c.g,
        b: c.b,
    }
}

/// Sanitizes a terminal-provided string (title, pwd).
///
/// Strips null bytes and Unicode bidirectional override codepoints
/// (U+202A–U+202E, U+2066–U+2069) which are a security risk in UI
/// display, then truncates to 64 characters. Returns `None` if the
/// result is empty after sanitization.
fn sanitize_terminal_string(bytes: &[u8]) -> Option<String> {
    let raw = String::from_utf8_lossy(bytes);
    let sanitized: String = raw
        .chars()
        .filter(|&c| {
            // Strip null bytes
            c != '\0'
            // Strip Unicode bidi overrides (security risk in UI display)
            && !matches!(c, '\u{202A}'..='\u{202E}' | '\u{2066}'..='\u{2069}')
        })
        .take(64)
        .collect();
    if sanitized.is_empty() { None } else { Some(sanitized) }
}

// ── GhosttyObject<T> RAII Wrapper ──────────────────────────────────

/// Type-safe RAII wrapper for an opaque C handle.
///
/// Wraps a [`NonNull`] pointer with a [`PhantomData`] marker so that
/// different handle kinds (terminal, render state, key encoder, etc.)
/// cannot be accidentally interchanged at the type level. The `T`
/// parameter is an uninhabited ZST — it exists only for type
/// discrimination and carries no runtime cost.
pub(crate) struct GhosttyObject<T> {
    ptr: NonNull<std::ffi::c_void>,
    _marker: PhantomData<T>,
}

impl<T> GhosttyObject<T> {
    /// Wraps a raw C handle, returning an error if it is null.
    pub(crate) fn new(raw: *mut std::ffi::c_void) -> Result<Self, TtyBackendError> {
        let ptr = NonNull::new(raw).ok_or(TtyBackendError::Internal {
            detail: format!("{} returned null handle", std::any::type_name::<T>()),
        })?;
        Ok(Self {
            ptr,
            _marker: PhantomData,
        })
    }

    /// Returns the underlying raw pointer for passing to C functions.
    #[inline]
    pub(crate) fn as_raw(&self) -> *mut std::ffi::c_void {
        self.ptr.as_ptr()
    }
}

// SAFETY: The underlying C handles are only accessed through &mut self
// behind a Mutex. Moving the pointer across threads is safe as long as
// concurrent access is serialised (guaranteed by session.rs).
unsafe impl<T> Send for GhosttyObject<T> {}

/// Marker ZST for [`Terminal`] handles.
pub(crate) enum TerminalHandle {}

/// Marker ZST for [`RenderState`] state handles.
pub(crate) enum RenderStateHandle {}

/// Marker ZST for [`RenderState`] row iterator handles.
pub(crate) enum RowIteratorHandle {}

/// Marker ZST for [`RenderState`] row cells handles.
pub(crate) enum RowCellsHandle {}

/// Marker ZST for [`KeyEncoder`] encoder handles.
pub(crate) enum KeyEncoderHandle {}

/// Marker ZST for [`KeyEncoder`] event handles.
pub(crate) enum KeyEventHandle {}

/// Marker ZST for [`MouseEncoder`] encoder handles.
pub(crate) enum MouseEncoderHandle {}

/// Marker ZST for [`MouseEncoder`] event handles.
pub(crate) enum MouseEventHandle {}

// ── Focus Encoding ─────────────────────────────────────────────────

/// Encodes a focus gained/lost event into a terminal escape sequence.
///
/// Returns the encoded bytes (`CSI I` for gained, `CSI O` for lost),
/// or an empty vec on failure. The focus event is only meaningful when
/// DEC 1004 (focus reporting) is active — callers should check
/// [`Terminal::is_focus_reporting_active`] first.
pub(crate) fn encode_focus_event(gained: bool) -> Vec<u8> {
    let event = if gained {
        GhosttyFocusEvent::Gained
    } else {
        GhosttyFocusEvent::Lost
    };
    let mut buf = [0u8; 8];
    let mut written: usize = 0;
    // SAFETY: Buffer is valid stack memory. ghostty_focus_encode writes
    // at most 3 bytes (ESC [ I or ESC [ O). out_written is valid.
    let result = unsafe {
        ghostty_focus_encode(event, buf.as_mut_ptr(), buf.len(), &mut written)
    };
    if result == GhosttyResult::Success && written > 0 {
        buf[..written].to_vec()
    } else {
        Vec::new()
    }
}

// ── Terminal ────────────────────────────────────────────────────────

/// RAII wrapper around a `GhosttyTerminal` handle.
pub(crate) struct Terminal {
    handle: GhosttyObject<TerminalHandle>,
}

// SAFETY: The terminal handle is only accessed through &mut self or
// &self behind a Mutex. libghostty-vt itself is single-threaded but
// the handle is just a pointer — moving it between threads is fine
// as long as access is serialised.
unsafe impl Send for Terminal {}

impl Terminal {
    /// Creates a new terminal with the given dimensions and scrollback.
    pub(crate) fn new(cols: u16, rows: u16, max_scrollback: usize) -> Result<Self, TtyBackendError> {
        let opts = GhosttyTerminalOptions {
            cols,
            rows,
            max_scrollback,
        };

        let mut raw: GhosttyTerminal = ptr::null_mut();

        // SAFETY: `ghostty_terminal_new` writes a valid handle to
        // `raw` on success and returns a result code. The allocator
        // is NULL (default). The handle pointer is valid stack memory.
        let result = unsafe { ghostty_terminal_new(ptr::null(), &mut raw, opts) };
        check(result, "ghostty_terminal_new")?;

        let handle = GhosttyObject::<TerminalHandle>::new(raw)?;
        Ok(Self { handle })
    }

    /// Feeds raw VT data (PTY output) into the terminal parser.
    pub(crate) fn vt_write(&mut self, data: &[u8]) {
        if data.is_empty() {
            return;
        }
        // SAFETY: `ghostty_terminal_vt_write` never fails. The data
        // pointer and length are valid for the duration of the call.
        unsafe {
            ghostty_terminal_vt_write(self.handle.as_raw(), data.as_ptr(), data.len());
        }
    }

    /// Resizes the terminal grid and pixel dimensions.
    pub(crate) fn resize(
        &mut self,
        cols: u16,
        rows: u16,
        cell_width_px: u32,
        cell_height_px: u32,
    ) -> Result<(), TtyBackendError> {
        if cols == 0 || rows == 0 {
            return Err(TtyBackendError::InvalidSize {
                detail: format!("cols={cols}, rows={rows}: must be > 0"),
            });
        }
        // SAFETY: The handle is valid (non-null, owned by self).
        // The dimensions have been validated.
        let result =
            unsafe { ghostty_terminal_resize(self.handle.as_raw(), cols, rows, cell_width_px, cell_height_px) };
        check(result, "ghostty_terminal_resize").map_err(Into::into)
    }

    /// Registers a write-PTY callback for terminal query responses.
    ///
    /// # Safety
    ///
    /// The callback and userdata must remain valid for the lifetime of
    /// the terminal. The callback must not call `vt_write` on the same
    /// terminal (no reentrancy).
    pub(crate) unsafe fn set_write_pty_callback(
        &mut self,
        callback: GhosttyTerminalWritePtyFn,
        userdata: *mut std::ffi::c_void,
    ) {
        // SAFETY: Setting userdata first, then the callback. Both
        // pointers are caller-guaranteed to be valid.
        unsafe {
            ghostty_terminal_set(
                self.handle.as_raw(),
                GhosttyTerminalOption::Userdata,
                userdata.cast(),
            );
            ghostty_terminal_set(
                self.handle.as_raw(),
                GhosttyTerminalOption::WritePty,
                callback
                    .map_or(ptr::null(), |f| f as *const std::ffi::c_void),
            );
        }
    }

    /// Registers a bell callback for BEL (0x07) events.
    ///
    /// # Safety
    ///
    /// The callback and userdata must remain valid for the lifetime of
    /// the terminal. The userdata pointer must have already been set
    /// via [`set_write_pty_callback`] (which sets both userdata and the
    /// write-PTY callback). This method only sets the bell function
    /// pointer — it does **not** re-set userdata.
    pub(crate) unsafe fn set_bell_callback(
        &mut self,
        callback: GhosttyTerminalBellFn,
    ) {
        // SAFETY: Userdata was already set by set_write_pty_callback.
        // We only register the bell function pointer here.
        unsafe {
            ghostty_terminal_set(
                self.handle.as_raw(),
                GhosttyTerminalOption::Bell,
                callback
                    .map_or(ptr::null(), |f| f as *const std::ffi::c_void),
            );
        }
    }

    /// Checks whether synchronized output mode (DEC 2026) is active.
    ///
    /// When active, the terminal is in the middle of a batched update
    /// and the render state should not be refreshed.
    pub(crate) fn is_synchronized_output(&self) -> bool {
        let mut active = false;
        // SAFETY: The handle is valid. The output pointer is valid stack
        // memory for a bool.
        unsafe {
            let result = ghostty_terminal_mode_get(
                self.handle.as_raw(),
                GHOSTTY_MODE_SYNC_OUTPUT,
                &mut active,
            );
            if result != GhosttyResult::Success {
                return false;
            }
        }
        active
    }

    /// Returns whether bracketed paste mode (DEC 2004) is active.
    pub(crate) fn is_bracketed_paste_active(&self) -> bool {
        let mut active = false;
        // SAFETY: The handle is valid. The output pointer is valid stack
        // memory for a bool.
        unsafe {
            let result = ghostty_terminal_mode_get(
                self.handle.as_raw(),
                GHOSTTY_MODE_BRACKETED_PASTE,
                &mut active,
            );
            if result != GhosttyResult::Success {
                return false;
            }
        }
        active
    }

    /// Returns whether focus reporting mode (DEC 1004) is active.
    ///
    /// When active, the terminal expects `CSI I` (gained) and `CSI O`
    /// (lost) sequences when the window gains or loses focus.
    pub(crate) fn is_focus_reporting_active(&self) -> bool {
        let mut active = false;
        // SAFETY: The handle is valid. The output pointer is valid stack
        // memory for a bool.
        unsafe {
            let result = ghostty_terminal_mode_get(
                self.handle.as_raw(),
                GHOSTTY_MODE_FOCUS_REPORTING,
                &mut active,
            );
            if result != GhosttyResult::Success {
                return false;
            }
        }
        active
    }

    /// Registers a title-changed callback for OSC 0/2 events.
    ///
    /// # Safety
    ///
    /// The callback and userdata must remain valid for the lifetime of
    /// the terminal. The userdata pointer must have already been set
    /// via [`set_write_pty_callback`] (which sets both userdata and the
    /// write-PTY callback). This method only sets the title-changed
    /// function pointer — it does **not** re-set userdata.
    pub(crate) unsafe fn set_title_changed_callback(
        &mut self,
        callback: GhosttyTerminalTitleChangedFn,
    ) {
        // SAFETY: Userdata was already set by set_write_pty_callback.
        // We only register the title-changed function pointer here.
        unsafe {
            ghostty_terminal_set(
                self.handle.as_raw(),
                GhosttyTerminalOption::TitleChanged,
                callback
                    .map_or(ptr::null(), |f| f as *const std::ffi::c_void),
            );
        }
    }

    /// Returns the terminal title set by OSC 0/2, or `None` if unset.
    ///
    /// The returned string is copied from terminal-internal memory.
    /// Must be called while the session Mutex is held (no concurrent
    /// `vt_write`).
    pub(crate) fn title(&self) -> Option<String> {
        let mut gs = GhosttyString { ptr: std::ptr::null(), len: 0 };
        // SAFETY: The handle is valid. `gs` is valid stack memory.
        // ghostty_terminal_get with Title writes a GhosttyString
        // pointing into terminal-internal memory (no ownership transfer).
        let result = unsafe {
            ghostty_terminal_get(
                self.handle.as_raw(),
                GhosttyTerminalData::Title,
                (&mut gs as *mut GhosttyString).cast(),
            )
        };
        if result != GhosttyResult::Success || gs.ptr.is_null() || gs.len == 0 {
            return None;
        }
        // SAFETY: `gs.ptr` is valid for `gs.len` bytes per the C API
        // contract. The slice is only borrowed for the duration of
        // sanitize_terminal_string — it does not escape this scope.
        let bytes = unsafe { std::slice::from_raw_parts(gs.ptr, gs.len) };
        sanitize_terminal_string(bytes)
    }

    /// Returns the working directory set by OSC 7, or `None` if unset.
    ///
    /// The returned string is copied from terminal-internal memory.
    /// Must be called while the session Mutex is held (no concurrent
    /// `vt_write`).
    pub(crate) fn pwd(&self) -> Option<String> {
        let mut gs = GhosttyString { ptr: std::ptr::null(), len: 0 };
        // SAFETY: The handle is valid. `gs` is valid stack memory.
        // ghostty_terminal_get with Pwd writes a GhosttyString
        // pointing into terminal-internal memory (no ownership transfer).
        let result = unsafe {
            ghostty_terminal_get(
                self.handle.as_raw(),
                GhosttyTerminalData::Pwd,
                (&mut gs as *mut GhosttyString).cast(),
            )
        };
        if result != GhosttyResult::Success || gs.ptr.is_null() || gs.len == 0 {
            return None;
        }
        // SAFETY: `gs.ptr` is valid for `gs.len` bytes per the C API
        // contract. The slice is only borrowed for the duration of
        // sanitize_terminal_string — it does not escape this scope.
        let bytes = unsafe { std::slice::from_raw_parts(gs.ptr, gs.len) };
        sanitize_terminal_string(bytes)
    }

    /// Returns the raw handle for passing to render state updates and
    /// key encoder sync. The caller must not free or store the handle.
    pub(crate) fn raw_handle(&self) -> GhosttyTerminal {
        self.handle.as_raw()
    }

    /// Sets a terminal color via OSC escape sequences fed through the
    /// VT parser. The OSC approach works because ghostty-vt processes
    /// OSC 4/10/11/12 and updates its internal palette.
    ///
    /// - `index` 0-255: ANSI/extended palette (OSC 4)
    /// - `index` 256: background (OSC 11)
    /// - `index` 257: foreground (OSC 10)
    /// - `index` 258: cursor (OSC 12)
    pub(crate) fn set_palette_color(&mut self, index: u16, r: u8, g: u8, b: u8) {
        let osc = match index {
            0..=255 => format!("\x1b]4;{index};rgb:{r:02x}/{g:02x}/{b:02x}\x1b\\"),
            256 => format!("\x1b]11;rgb:{r:02x}/{g:02x}/{b:02x}\x1b\\"),
            257 => format!("\x1b]10;rgb:{r:02x}/{g:02x}/{b:02x}\x1b\\"),
            258 => format!("\x1b]12;rgb:{r:02x}/{g:02x}/{b:02x}\x1b\\"),
            _ => return,
        };
        self.vt_write(osc.as_bytes());
    }
}

impl Drop for Terminal {
    fn drop(&mut self) {
        // SAFETY: The handle is valid (GhosttyObject guarantees
        // non-null) and exclusively owned by this struct.
        unsafe { ghostty_terminal_free(self.handle.as_raw()) };
    }
}

// ── Render State ────────────────────────────────────────────────────

/// RAII wrapper around `GhosttyRenderState`, `GhosttyRenderStateRowIterator`,
/// and `GhosttyRenderStateRowCells`.
///
/// Owns all three handles and reuses them across frames to avoid
/// repeated allocation. Caches metadata from the last non-clean
/// frame so that [`DirtyState::Clean`] snapshots can short-circuit
/// without calling into the C library for dimensions, cursor, or
/// colors.
///
/// Field order matters: Rust drops fields in declaration order, so
/// `row_cells` is listed before `row_iter` before `state`. This
/// ensures the C library frees child handles before the parent
/// render state, preventing dangling internal references.
pub(crate) struct RenderState {
    row_cells: GhosttyObject<RowCellsHandle>,
    row_iter: GhosttyObject<RowIteratorHandle>,
    state: GhosttyObject<RenderStateHandle>,
    /// Cached column count from the last non-clean snapshot.
    last_cols: u16,
    /// Cached row count from the last non-clean snapshot.
    last_num_rows: u16,
    /// Cached cursor state from the last non-clean snapshot.
    last_cursor: RenderCursor,
    /// Cached default background color from the last non-clean snapshot.
    last_default_bg: RenderColor,
    /// Cached default foreground color from the last non-clean snapshot.
    last_default_fg: RenderColor,
}

// SAFETY: RenderState holds three opaque C pointers (render state,
// row iterator, row cells). These are only accessed through &mut self
// in snapshot(), which requires exclusive access. Moving the struct
// across threads is safe as long as concurrent access is prevented
// (guaranteed by the Mutex in session.rs).
unsafe impl Send for RenderState {}

impl RenderState {
    /// Creates a new render state with pre-allocated iterators.
    pub(crate) fn new() -> Result<Self, TtyBackendError> {
        let mut raw_state: GhosttyRenderState = ptr::null_mut();
        let mut raw_row_iter: GhosttyRenderStateRowIterator = ptr::null_mut();
        let mut raw_row_cells: GhosttyRenderStateRowCells = ptr::null_mut();

        // SAFETY: All output pointers are valid stack memory. The
        // allocator is NULL (default).
        unsafe {
            check(
                ghostty_render_state_new(ptr::null(), &mut raw_state),
                "ghostty_render_state_new",
            )?;
            check(
                ghostty_render_state_row_iterator_new(ptr::null(), &mut raw_row_iter),
                "ghostty_render_state_row_iterator_new",
            )?;
            check(
                ghostty_render_state_row_cells_new(ptr::null(), &mut raw_row_cells),
                "ghostty_render_state_row_cells_new",
            )?;
        }

        let state = GhosttyObject::<RenderStateHandle>::new(raw_state)?;
        let row_iter = GhosttyObject::<RowIteratorHandle>::new(raw_row_iter)?;
        let row_cells = GhosttyObject::<RowCellsHandle>::new(raw_row_cells)?;

        Ok(Self {
            row_cells,
            row_iter,
            state,
            last_cols: 0,
            last_num_rows: 0,
            last_cursor: RenderCursor::default(),
            last_default_bg: RenderColor::default(),
            last_default_fg: RenderColor::default(),
        })
    }

    /// Updates the render state from the terminal, then extracts a
    /// full [`TerminalRenderSnapshot`].
    ///
    /// After extraction, the render state dirty flags are cleared so
    /// the next update only reports new changes.
    pub(crate) fn snapshot(
        &mut self,
        terminal: &Terminal,
    ) -> Result<TerminalRenderSnapshot, TtyBackendError> {
        // SAFETY: Both handles are non-null and owned by their
        // respective RAII wrappers. The terminal is borrowed
        // immutably — render_state_update only reads terminal state.
        unsafe {
            check(
                ghostty_render_state_update(self.state.as_raw(), terminal.raw_handle()),
                "ghostty_render_state_update",
            )?;
        }

        let dirty = self.get_dirty()?;

        // Short-circuit: when nothing changed, skip the ~50 C library
        // calls for row iteration / cell extraction and return cached
        // metadata with an empty row list. The Kotlin ViewModel already
        // discards Clean frames, so the only cost is the dirty check
        // itself (~2 C calls).
        if dirty == DirtyState::Clean {
            return Ok(TerminalRenderSnapshot {
                dirty: DirtyState::Clean,
                rows: Vec::new(),
                cols: self.last_cols,
                num_rows: self.last_num_rows,
                cursor: self.last_cursor,
                default_bg: self.last_default_bg,
                default_fg: self.last_default_fg,
                palette: Vec::new(),
            });
        }

        let (cols, num_rows) = self.get_dimensions()?;
        let cursor = self.get_cursor()?;
        let (default_bg, default_fg, palette) = self.get_colors()?;
        let rows = self.extract_rows(cols)?;

        // Clear dirty state after extraction.
        self.clear_dirty();

        // Cache metadata for future Clean short-circuits.
        self.last_cols = cols;
        self.last_num_rows = num_rows;
        self.last_cursor = cursor;
        self.last_default_bg = default_bg;
        self.last_default_fg = default_fg;

        Ok(TerminalRenderSnapshot {
            dirty,
            rows,
            cols,
            num_rows,
            cursor,
            default_bg,
            default_fg,
            palette,
        })
    }

    fn get_dirty(&self) -> Result<DirtyState, TtyBackendError> {
        let mut dirty = GhosttyRenderStateDirty::False;
        // SAFETY: `dirty` is a valid output pointer for the Dirty
        // data type.
        unsafe {
            check(
                ghostty_render_state_get(
                    self.state.as_raw(),
                    GhosttyRenderStateData::Dirty,
                    (&mut dirty as *mut GhosttyRenderStateDirty).cast(),
                ),
                "render_state_get(Dirty)",
            )?;
        }
        Ok(match dirty {
            GhosttyRenderStateDirty::False => DirtyState::Clean,
            GhosttyRenderStateDirty::Partial => DirtyState::Partial,
            GhosttyRenderStateDirty::Full => DirtyState::Full,
        })
    }

    fn get_dimensions(&self) -> Result<(u16, u16), TtyBackendError> {
        let mut cols: u16 = 0;
        let mut rows: u16 = 0;
        // SAFETY: Output pointers are valid stack u16s.
        unsafe {
            check(
                ghostty_render_state_get(
                    self.state.as_raw(),
                    GhosttyRenderStateData::Cols,
                    (&mut cols as *mut u16).cast(),
                ),
                "render_state_get(Cols)",
            )?;
            check(
                ghostty_render_state_get(
                    self.state.as_raw(),
                    GhosttyRenderStateData::Rows,
                    (&mut rows as *mut u16).cast(),
                ),
                "render_state_get(Rows)",
            )?;
        }
        Ok((cols, rows))
    }

    fn get_cursor(&self) -> Result<RenderCursor, TtyBackendError> {
        let mut visible = false;
        let mut has_viewport = false;
        let mut x: u16 = 0;
        let mut y: u16 = 0;
        let mut blinking = false;
        let mut style_raw = GhosttyRenderStateCursorVisualStyle::Block;

        // SAFETY: All output pointers are valid stack memory matching
        // the expected type for each data kind.
        unsafe {
            let _ = ghostty_render_state_get(
                self.state.as_raw(),
                GhosttyRenderStateData::CursorVisible,
                (&mut visible as *mut bool).cast(),
            );
            let _ = ghostty_render_state_get(
                self.state.as_raw(),
                GhosttyRenderStateData::CursorViewportHasValue,
                (&mut has_viewport as *mut bool).cast(),
            );
            if has_viewport {
                let _ = ghostty_render_state_get(
                    self.state.as_raw(),
                    GhosttyRenderStateData::CursorViewportX,
                    (&mut x as *mut u16).cast(),
                );
                let _ = ghostty_render_state_get(
                    self.state.as_raw(),
                    GhosttyRenderStateData::CursorViewportY,
                    (&mut y as *mut u16).cast(),
                );
            }
            let _ = ghostty_render_state_get(
                self.state.as_raw(),
                GhosttyRenderStateData::CursorBlinking,
                (&mut blinking as *mut bool).cast(),
            );
            let _ = ghostty_render_state_get(
                self.state.as_raw(),
                GhosttyRenderStateData::CursorVisualStyle,
                (&mut style_raw as *mut GhosttyRenderStateCursorVisualStyle).cast(),
            );
        }

        let style = match style_raw {
            GhosttyRenderStateCursorVisualStyle::Bar => CursorStyle::Bar,
            GhosttyRenderStateCursorVisualStyle::Block => CursorStyle::Block,
            GhosttyRenderStateCursorVisualStyle::Underline => CursorStyle::Underline,
            GhosttyRenderStateCursorVisualStyle::BlockHollow => CursorStyle::BlockHollow,
        };

        Ok(RenderCursor {
            x,
            y,
            visible: visible && has_viewport,
            style,
            blinking,
        })
    }

    fn get_colors(&self) -> Result<(RenderColor, RenderColor, Vec<RenderColor>), TtyBackendError> {
        let mut colors = sized!(GhosttyRenderStateColors);

        // SAFETY: `colors.size` is correctly set. The output pointer
        // is valid for the full struct size.
        unsafe {
            check(
                ghostty_render_state_colors_get(self.state.as_raw(), &mut colors),
                "render_state_colors_get",
            )?;
        }

        let default_bg = color_from_c(colors.background);
        let default_fg = color_from_c(colors.foreground);
        let palette: Vec<RenderColor> = colors.palette.iter().map(|c| color_from_c(*c)).collect();

        Ok((default_bg, default_fg, palette))
    }

    fn extract_rows(&mut self, cols: u16) -> Result<Vec<RenderRow>, TtyBackendError> {
        // Populate the row iterator from the render state.
        // SAFETY: Both handles are valid. The row iterator is
        // populated by reference — data is valid until the next
        // render_state_update.
        unsafe {
            check(
                ghostty_render_state_get(
                    self.state.as_raw(),
                    GhosttyRenderStateData::RowIterator,
                    self.row_iter.as_raw().cast(),
                ),
                "render_state_get(RowIterator)",
            )?;
        }

        let mut rows = Vec::new();

        // SAFETY: The row iterator was just populated. `next()` is
        // safe to call until it returns false.
        while unsafe { ghostty_render_state_row_iterator_next(self.row_iter.as_raw()) } {
            let mut row_dirty = false;
            // SAFETY: The iterator is positioned on a valid row.
            unsafe {
                let _ = ghostty_render_state_row_get(
                    self.row_iter.as_raw(),
                    GhosttyRenderStateRowData::Dirty,
                    (&mut row_dirty as *mut bool).cast(),
                );
            }

            // Only extract cell data for dirty rows. Clean rows get
            // an empty sentinel — the Kotlin side reuses cached data.
            let cells = if row_dirty {
                self.extract_cells(cols)?
            } else {
                Vec::new()
            };

            // Clear per-row dirty flag after reading.
            // SAFETY: The iterator is positioned on a valid row.
            unsafe {
                let false_val = false;
                let _ = ghostty_render_state_row_set(
                    self.row_iter.as_raw(),
                    GhosttyRenderStateRowOption::Dirty,
                    (&false_val as *const bool).cast(),
                );
            }

            rows.push(RenderRow {
                cells,
                dirty: row_dirty,
            });
        }

        Ok(rows)
    }

    fn extract_cells(&mut self, cols: u16) -> Result<Vec<RenderCell>, TtyBackendError> {
        // Populate row cells from the current row.
        // SAFETY: The row iterator is positioned on a valid row.
        unsafe {
            check(
                ghostty_render_state_row_get(
                    self.row_iter.as_raw(),
                    GhosttyRenderStateRowData::Cells,
                    self.row_cells.as_raw().cast(),
                ),
                "render_state_row_get(Cells)",
            )?;
        }

        let mut cells = Vec::with_capacity(cols as usize);

        // Stack buffer reused across all cells in the row, eliminating
        // one heap allocation per grapheme cell. Covers clusters up to
        // 16 codepoints; clusters larger than 16 fall back to a heap Vec
        // (extremely rare in practice).
        let mut grapheme_buf = [0u32; 16];

        // SAFETY: The row cells handle was just populated and is
        // valid until the next render_state_update.
        while unsafe { ghostty_render_state_row_cells_next(self.row_cells.as_raw()) } {
            // Read the raw cell value first — needed for both the content
            // tag check (Task 10) and the wide/narrow classification below.
            let mut raw_cell: u64 = 0;
            // SAFETY: Output pointer is a valid u64 on the stack.
            let raw_ok = unsafe {
                ghostty_render_state_row_cells_get(
                    self.row_cells.as_raw(),
                    GhosttyRenderStateRowCellsData::Raw,
                    (&mut raw_cell as *mut u64).cast(),
                )
            } == GhosttyResult::Success;

            // Determine content tag so we can skip grapheme extraction
            // for bg-color-only cells (BgColorPalette / BgColorRgb).
            // Default to Codepoint so the grapheme path still runs when
            // the raw read fails or the content tag is unrecognised.
            let content_tag = if raw_ok {
                let mut tag = GhosttyCellContentTag::Codepoint;
                // SAFETY: `raw_cell` is a valid packed cell value. `tag`
                // is valid stack memory whose repr matches the C enum.
                let tag_ok = unsafe {
                    ghostty_cell_get(
                        raw_cell,
                        GhosttyCellData::ContentTag,
                        (&mut tag as *mut GhosttyCellContentTag).cast(),
                    )
                } == GhosttyResult::Success;
                if tag_ok { tag } else { GhosttyCellContentTag::Codepoint }
            } else {
                GhosttyCellContentTag::Codepoint
            };

            // Skip grapheme extraction entirely for bg-color-only cells.
            // These cells carry no text — querying GraphemesLen / GraphemesBuf
            // would return 0 / nothing anyway, but skipping the C calls
            // avoids two redundant round-trips on every such cell.
            let has_text = !matches!(
                content_tag,
                GhosttyCellContentTag::BgColorPalette | GhosttyCellContentTag::BgColorRgb
            );

            // Read codepoints (zero-alloc fast path via stack buffer).
            let codepoints = if has_text {
                // Read grapheme length.
                let mut graphemes_len: u32 = 0;
                // SAFETY: Output pointer is valid stack memory.
                unsafe {
                    let _ = ghostty_render_state_row_cells_get(
                        self.row_cells.as_raw(),
                        GhosttyRenderStateRowCellsData::GraphemesLen,
                        (&mut graphemes_len as *mut u32).cast(),
                    );
                }

                if graphemes_len == 0 {
                    Vec::new()
                } else {
                    let n = graphemes_len as usize;
                    if n <= grapheme_buf.len() {
                        // Fast path: write into the reused stack buffer, then
                        // copy only the live slice into the output Vec.
                        // SAFETY: `grapheme_buf` is valid stack memory with
                        // capacity >= n; GraphemesLen reported exactly n
                        // codepoints so the write stays in bounds.
                        unsafe {
                            let _ = ghostty_render_state_row_cells_get(
                                self.row_cells.as_raw(),
                                GhosttyRenderStateRowCellsData::GraphemesBuf,
                                grapheme_buf.as_mut_ptr().cast(),
                            );
                        }
                        grapheme_buf[..n].to_vec()
                    } else {
                        // Rare fallback: grapheme cluster > 16 codepoints.
                        let mut heap_buf = vec![0u32; n];
                        // SAFETY: `heap_buf` is correctly sized for the
                        // number of codepoints reported by GraphemesLen.
                        unsafe {
                            let _ = ghostty_render_state_row_cells_get(
                                self.row_cells.as_raw(),
                                GhosttyRenderStateRowCellsData::GraphemesBuf,
                                heap_buf.as_mut_ptr().cast(),
                            );
                        }
                        heap_buf
                    }
                }
            } else {
                Vec::new()
            };

            // Read foreground color (returns InvalidValue if default).
            let fg = {
                let mut color = GhosttyColorRgb::default();
                // SAFETY: Output pointer is valid stack memory.
                let result = unsafe {
                    ghostty_render_state_row_cells_get(
                        self.row_cells.as_raw(),
                        GhosttyRenderStateRowCellsData::FgColor,
                        (&mut color as *mut GhosttyColorRgb).cast(),
                    )
                };
                if result == GhosttyResult::Success {
                    Some(color_from_c(color))
                } else {
                    None
                }
            };

            // Read background color (returns InvalidValue if default).
            let bg = {
                let mut color = GhosttyColorRgb::default();
                // SAFETY: Output pointer is valid stack memory.
                let result = unsafe {
                    ghostty_render_state_row_cells_get(
                        self.row_cells.as_raw(),
                        GhosttyRenderStateRowCellsData::BgColor,
                        (&mut color as *mut GhosttyColorRgb).cast(),
                    )
                };
                if result == GhosttyResult::Success {
                    Some(color_from_c(color))
                } else {
                    None
                }
            };

            // Read Style=2: SGR attributes (bold, italic, etc.).
            let flags = {
                let mut style = sized!(GhosttyStyle);
                // SAFETY: `style` is a valid sized struct with `size`
                // pre-filled. The output pointer is valid stack memory.
                let result = unsafe {
                    ghostty_render_state_row_cells_get(
                        self.row_cells.as_raw(),
                        GhosttyRenderStateRowCellsData::Style,
                        (&mut style as *mut GhosttyStyle).cast(),
                    )
                };
                if result == GhosttyResult::Success {
                    CellStyleFlags {
                        bold: style.bold,
                        italic: style.italic,
                        strikethrough: style.strikethrough,
                        inverse: style.inverse,
                        dim: style.faint,
                        invisible: style.invisible,
                        blink: style.blink,
                        overline: style.overline,
                        underline_style: style.underline.clamp(0, 5) as u8,
                    }
                } else {
                    CellStyleFlags::default()
                }
            };

            // Query wide/narrow classification from the raw cell already read.
            let width = {
                if raw_ok {
                    let mut wide = GhosttyCellWide::Narrow;
                    // SAFETY: `raw_cell` is the packed cell value just
                    // read. `wide` is valid stack memory for the enum.
                    let wide_result = unsafe {
                        ghostty_cell_get(
                            raw_cell,
                            GhosttyCellData::Wide,
                            (&mut wide as *mut GhosttyCellWide).cast(),
                        )
                    };
                    if wide_result == GhosttyResult::Success {
                        match wide {
                            GhosttyCellWide::Wide => 2,
                            GhosttyCellWide::SpacerTail | GhosttyCellWide::SpacerHead => 0,
                            GhosttyCellWide::Narrow => 1,
                        }
                    } else {
                        1
                    }
                } else {
                    1
                }
            };

            cells.push(RenderCell {
                codepoints,
                fg,
                bg,
                flags,
                width,
            });
        }

        Ok(cells)
    }

    fn clear_dirty(&mut self) {
        let clean = GhosttyRenderStateDirty::False;
        // SAFETY: The render state handle is valid. The value pointer
        // points to a valid GhosttyRenderStateDirty on the stack.
        unsafe {
            let _ = ghostty_render_state_set(
                self.state.as_raw(),
                GhosttyRenderStateOption::Dirty,
                (&clean as *const GhosttyRenderStateDirty).cast(),
            );
        }
    }
}

impl Drop for RenderState {
    fn drop(&mut self) {
        // SAFETY: All handles are valid (GhosttyObject guarantees
        // non-null) and exclusively owned by this struct. Rust drops
        // fields in declaration order (row_cells, row_iter, state),
        // but we free explicitly here for clarity and to document
        // the required reverse-allocation order.
        unsafe {
            ghostty_render_state_row_cells_free(self.row_cells.as_raw());
            ghostty_render_state_row_iterator_free(self.row_iter.as_raw());
            ghostty_render_state_free(self.state.as_raw());
        }
    }
}

// ── Key Encoder ─────────────────────────────────────────────────────

/// RAII wrapper around `GhosttyKeyEncoder` and a reusable
/// `GhosttyKeyEvent`.
pub(crate) struct KeyEncoder {
    encoder: GhosttyObject<KeyEncoderHandle>,
    event: GhosttyObject<KeyEventHandle>,
}

// SAFETY: KeyEncoder holds two opaque C pointers (encoder, event).
// These are only accessed through &mut self in encode_key() and
// sync_from_terminal(). Moving across threads is safe as long as
// concurrent access is prevented (guaranteed by the Mutex in
// session.rs).
unsafe impl Send for KeyEncoder {}

impl KeyEncoder {
    /// Creates a new key encoder with a reusable event.
    pub(crate) fn new() -> Result<Self, TtyBackendError> {
        let mut raw_encoder: GhosttyKeyEncoder = ptr::null_mut();
        let mut raw_event: GhosttyKeyEvent = ptr::null_mut();

        // SAFETY: Output pointers are valid stack memory. Allocator
        // is NULL (default).
        unsafe {
            check(
                ghostty_key_encoder_new(ptr::null(), &mut raw_encoder),
                "ghostty_key_encoder_new",
            )?;
            check(
                ghostty_key_event_new(ptr::null(), &mut raw_event),
                "ghostty_key_event_new",
            )?;
        }

        let encoder = GhosttyObject::<KeyEncoderHandle>::new(raw_encoder)?;
        let event = GhosttyObject::<KeyEventHandle>::new(raw_event)?;

        Ok(Self { encoder, event })
    }

    /// Syncs encoder options from the terminal's current mode state
    /// (cursor key mode, Kitty keyboard flags, etc.).
    pub(crate) fn sync_from_terminal(&mut self, terminal: &Terminal) {
        // SAFETY: Both handles are valid and non-null.
        unsafe {
            ghostty_key_encoder_setopt_from_terminal(
                self.encoder.as_raw(),
                terminal.raw_handle(),
            );
        }
    }

    /// Encodes a key press into terminal escape sequences.
    ///
    /// Returns the encoded bytes, or an empty vec if the key produces
    /// no output (e.g. bare modifier keys).
    pub(crate) fn encode_key(
        &mut self,
        key: GhosttyKey,
        action: GhosttyKeyAction,
        mods: GhosttyMods,
        utf8_text: Option<&[u8]>,
    ) -> Vec<u8> {
        // Configure the reusable event.
        // SAFETY: The event handle is valid and owned by self.
        unsafe {
            ghostty_key_event_set_action(self.event.as_raw(), action);
            ghostty_key_event_set_key(self.event.as_raw(), key);
            ghostty_key_event_set_mods(self.event.as_raw(), mods);

            if let Some(text) = utf8_text {
                ghostty_key_event_set_utf8(self.event.as_raw(), text.as_ptr(), text.len());
            } else {
                ghostty_key_event_set_utf8(self.event.as_raw(), ptr::null(), 0);
            }
        }

        // Encode into a stack buffer (128 bytes is enough for any
        // escape sequence).
        let mut buf = [0u8; 128];
        let mut written: usize = 0;

        // SAFETY: The encoder and event handles are valid. The buffer
        // pointer and size are correct. `written` receives the number
        // of bytes actually written.
        let result = unsafe {
            ghostty_key_encoder_encode(
                self.encoder.as_raw(),
                self.event.as_raw(),
                buf.as_mut_ptr(),
                buf.len(),
                &mut written,
            )
        };

        if result == GhosttyResult::Success && written > 0 {
            buf[..written].to_vec()
        } else {
            Vec::new()
        }
    }
}

impl Drop for KeyEncoder {
    fn drop(&mut self) {
        // SAFETY: Both handles are valid (GhosttyObject guarantees
        // non-null) and exclusively owned by this struct. Free event
        // before encoder to prevent dangling internal references.
        unsafe {
            ghostty_key_event_free(self.event.as_raw());
            ghostty_key_encoder_free(self.encoder.as_raw());
        }
    }
}

// ── Mouse Encoder ──────────────────────────────────────────────────

/// RAII wrapper around `GhosttyMouseEncoder` and a reusable
/// `GhosttyMouseEvent`. Encodes Android touch events into terminal
/// mouse escape sequences.
pub(crate) struct MouseEncoder {
    encoder: GhosttyObject<MouseEncoderHandle>,
    event: GhosttyObject<MouseEventHandle>,
    /// `true` once `set_geometry` has been called at least once.
    has_geometry: bool,
    /// Tracks whether any mouse button is currently pressed. Used to
    /// inform the encoder for button-motion vs no-button-motion encoding.
    any_pressed: bool,
}

// SAFETY: MouseEncoder holds two opaque C pointers (encoder, event).
// These are only accessed through &mut self in encode(),
// sync_from_terminal(), and set_geometry(). Moving across threads is
// safe as long as concurrent access is prevented (guaranteed by the
// Mutex in GhosttyBackend / session.rs).
unsafe impl Send for MouseEncoder {}

impl MouseEncoder {
    /// Creates a new mouse encoder with a reusable event.
    pub(crate) fn new() -> Result<Self, TtyBackendError> {
        let mut raw_encoder: GhosttyMouseEncoder = ptr::null_mut();
        let mut raw_event: GhosttyMouseEvent = ptr::null_mut();

        // SAFETY: Output pointers are valid stack memory. Allocator is NULL (default).
        unsafe {
            check(
                ghostty_mouse_encoder_new(ptr::null(), &mut raw_encoder),
                "ghostty_mouse_encoder_new",
            )?;
            check(
                ghostty_mouse_event_new(ptr::null(), &mut raw_event),
                "ghostty_mouse_event_new",
            )?;
        }

        let encoder = GhosttyObject::<MouseEncoderHandle>::new(raw_encoder)?;
        let event = GhosttyObject::<MouseEventHandle>::new(raw_event)?;

        // Enable cell-level motion deduplication. On Android
        // touchscreens, events fire at 120Hz+ but the terminal only
        // cares about cell-granularity changes. Without this, every
        // sub-pixel motion generates a redundant escape sequence.
        let track = true;
        // SAFETY: Encoder handle is valid (GhosttyObject guarantees
        // non-null). The bool pointer is valid stack memory.
        unsafe {
            ghostty_mouse_encoder_setopt(
                encoder.as_raw(),
                GhosttyMouseEncoderOption::TrackLastCell,
                &track as *const bool as *const std::ffi::c_void,
            );
        }

        Ok(Self { encoder, event, has_geometry: false, any_pressed: false })
    }

    /// Syncs encoder options from the terminal's current mouse mode
    /// and format state (tracking mode, SGR/X10, etc.).
    pub(crate) fn sync_from_terminal(&mut self, terminal: &Terminal) {
        // SAFETY: Both handles are valid and non-null (GhosttyObject
        // guarantees non-null). Terminal handle is valid and owned by
        // the caller.
        unsafe {
            ghostty_mouse_encoder_setopt_from_terminal(
                self.encoder.as_raw(),
                terminal.raw_handle(),
            );
        }
    }

    /// Updates the screen and cell geometry used for coordinate
    /// conversion. Must be called at least once before `encode()`.
    pub(crate) fn set_geometry(
        &mut self,
        cell_w: u32,
        cell_h: u32,
        screen_w: u32,
        screen_h: u32,
    ) {
        let mut size = sized!(GhosttyMouseEncoderSize);
        size.screen_width = screen_w;
        size.screen_height = screen_h;
        size.cell_width = cell_w;
        size.cell_height = cell_h;

        // SAFETY: Encoder handle is valid. The size struct pointer is
        // valid stack memory with correct `size` field set by sized().
        unsafe {
            ghostty_mouse_encoder_setopt(
                self.encoder.as_raw(),
                GhosttyMouseEncoderOption::Size,
                &size as *const GhosttyMouseEncoderSize as *const std::ffi::c_void,
            );
            // The cell grid changed, so the cached "last cell" for
            // dedup is stale. Reset to prevent dropped events.
            ghostty_mouse_encoder_reset(self.encoder.as_raw());
        }
        self.has_geometry = true;
    }

    /// Encodes an Android touch event into terminal mouse escape sequences.
    ///
    /// Returns the encoded bytes, or an empty vec if the event produces
    /// no output (e.g. tracking is disabled, or geometry not yet set).
    ///
    /// # Parameters
    ///
    /// - `action`: 0 = Press, 1 = Release, 2 = Motion
    /// - `button`: 0 = Unknown, 1 = Left, 2 = Right, 3 = Middle, 4–11 = extra buttons
    /// - `x`, `y`: pixel coordinates of the touch event (must be finite)
    /// - `mods`: packed modifier flags matching [`GhosttyMods`]
    pub(crate) fn encode(
        &mut self,
        action: u8,
        button: u8,
        x: f32,
        y: f32,
        mods: u32,
    ) -> Vec<u8> {
        if !self.has_geometry {
            tracing::warn!(target: "tty::mouse", "encode called before set_geometry; ignoring");
            return Vec::new();
        }

        if !x.is_finite() || !y.is_finite() {
            tracing::warn!(target: "tty::mouse", "non-finite coordinates ({x}, {y}); ignoring");
            return Vec::new();
        }

        let mouse_action = match action {
            0 => GhosttyMouseAction::Press,
            1 => GhosttyMouseAction::Release,
            2 => GhosttyMouseAction::Motion,
            _ => {
                tracing::warn!(target: "tty::mouse", "unknown mouse action {action}; ignoring");
                return Vec::new();
            }
        };

        let mouse_button = match button {
            0 => GhosttyMouseButton::Unknown,
            1 => GhosttyMouseButton::Left,
            2 => GhosttyMouseButton::Right,
            3 => GhosttyMouseButton::Middle,
            4 => GhosttyMouseButton::Four,
            5 => GhosttyMouseButton::Five,
            6 => GhosttyMouseButton::Six,
            7 => GhosttyMouseButton::Seven,
            8 => GhosttyMouseButton::Eight,
            9 => GhosttyMouseButton::Nine,
            10 => GhosttyMouseButton::Ten,
            11 => GhosttyMouseButton::Eleven,
            _ => {
                tracing::warn!(target: "tty::mouse", "unknown mouse button {button}; ignoring");
                return Vec::new();
            }
        };

        let position = GhosttyMousePosition { x, y };

        // Track any-button-pressed for motion encoding. Button-event
        // tracking mode distinguishes button-motion from no-button-motion.
        match mouse_action {
            GhosttyMouseAction::Press => self.any_pressed = true,
            GhosttyMouseAction::Release => self.any_pressed = false,
            GhosttyMouseAction::Motion => {}
        }

        // SAFETY: Event handle is valid (GhosttyObject guarantees
        // non-null). Clear prior button state before setting new
        // (C API is additive).
        unsafe {
            ghostty_mouse_event_set_action(self.event.as_raw(), mouse_action);
            ghostty_mouse_event_clear_button(self.event.as_raw());
            if button != 0 {
                ghostty_mouse_event_set_button(self.event.as_raw(), mouse_button);
            }
            ghostty_mouse_event_set_position(self.event.as_raw(), position);
            ghostty_mouse_event_set_mods(self.event.as_raw(), mods as GhosttyMods);

            // Inform encoder whether any button is currently held.
            ghostty_mouse_encoder_setopt(
                self.encoder.as_raw(),
                GhosttyMouseEncoderOption::AnyButtonPressed,
                &self.any_pressed as *const bool as *const std::ffi::c_void,
            );
        }

        let mut buf = [0u8; 128];
        let mut written: usize = 0;

        // SAFETY: Encoder and event handles are valid. Buffer pointer
        // and size are correct. `written` receives the actual byte count.
        let result = unsafe {
            ghostty_mouse_encoder_encode(
                self.encoder.as_raw(),
                self.event.as_raw(),
                buf.as_mut_ptr(),
                buf.len(),
                &mut written,
            )
        };

        match result {
            GhosttyResult::Success if written > 0 => buf[..written].to_vec(),
            GhosttyResult::Success => Vec::new(),
            GhosttyResult::OutOfSpace => {
                tracing::error!(
                    target: "tty::mouse",
                    "mouse encode buffer too small ({written} written, 128 available); \
                     dropping event to prevent truncated escape sequence"
                );
                Vec::new()
            }
            other => {
                tracing::warn!(target: "tty::mouse", "mouse encode failed: {other:?}; ignoring");
                Vec::new()
            }
        }
    }

    /// Queries whether any mouse tracking mode is active on the
    /// terminal (DEC modes 9, 1000, 1002, or 1003).
    pub(crate) fn is_tracking_active(terminal: &Terminal) -> bool {
        let mut active: bool = false;

        // SAFETY: Terminal handle is valid. Output pointer is valid stack memory.
        let result = unsafe {
            ghostty_terminal_get(
                terminal.raw_handle(),
                GhosttyTerminalData::MouseTracking,
                &mut active as *mut bool as *mut std::ffi::c_void,
            )
        };

        result == GhosttyResult::Success && active
    }
}

impl Drop for MouseEncoder {
    fn drop(&mut self) {
        // Free event first, then encoder. Event-before-encoder order
        // prevents dangling internal references in the C library.
        //
        // SAFETY: Both handles are valid (GhosttyObject guarantees
        // non-null) and exclusively owned by self.
        unsafe {
            ghostty_mouse_event_free(self.event.as_raw());
            ghostty_mouse_encoder_free(self.encoder.as_raw());
        }
    }
}

// ── Paste safety ─────────────────────────────────────────────────────

/// Checks whether paste data is safe (no newlines or paste-end sequences).
///
/// Delegates to `ghostty_paste_is_safe` from the C library.
pub(crate) fn is_paste_safe(data: &str) -> bool {
    if data.is_empty() {
        return true;
    }
    // SAFETY: data is a valid UTF-8 &str. as_bytes() returns a pointer
    // valid for the call duration. ghostty_paste_is_safe does not retain
    // the pointer.
    unsafe { ghostty_paste_is_safe(data.as_bytes().as_ptr(), data.len()) }
}

// ── Build info queries ───────────────────────────────────────────────

/// Queries whether the vendored libghostty-vt was built with Kitty
/// graphics protocol support.
pub(crate) fn supports_kitty_graphics() -> bool {
    let mut value: bool = false;
    // SAFETY: `value` is valid stack memory of the correct type for
    // the KittyGraphics tag. ghostty_build_info does not retain the pointer.
    let result = unsafe {
        ghostty_build_info(
            GhosttyBuildInfo::KittyGraphics,
            (&mut value as *mut bool).cast(),
        )
    };
    result == GhosttyResult::Success && value
}

/// Returns the optimization mode the library was built with.
pub(crate) fn optimize_mode() -> Option<GhosttyOptimizeMode> {
    let mut mode = GhosttyOptimizeMode::Debug;
    // SAFETY: `mode` is valid stack memory of the correct type for
    // the Optimize tag. ghostty_build_info does not retain the pointer.
    let result = unsafe {
        ghostty_build_info(
            GhosttyBuildInfo::Optimize,
            (&mut mode as *mut GhosttyOptimizeMode).cast(),
        )
    };
    if result == GhosttyResult::Success { Some(mode) } else { None }
}
