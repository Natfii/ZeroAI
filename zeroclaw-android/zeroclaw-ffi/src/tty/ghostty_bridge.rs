// Copyright (c) 2026 @Natfii. All rights reserved.

//! Safe Rust wrappers around the libghostty-vt C API.
//!
//! Each opaque handle type from [`super::ghostty_sys`] is wrapped in a
//! Rust struct that owns the handle and frees it on [`Drop`]. All
//! fallible C calls check [`GhosttyResult`] and return
//! [`TtyBackendError`] on failure.
//!
//! Thread safety: libghostty-vt is **not** thread-safe. All access to
//! a single terminal + render state must be serialised. The caller
//! (typically behind a `Mutex`) is responsible for this.

use std::ptr;

use super::backend::{
    CellStyleFlags, CursorStyle, DirtyState, RenderCell, RenderColor, RenderCursor, RenderRow,
    TerminalRenderSnapshot, TtyBackendError,
};
use super::ghostty_sys::*;

// ── Helpers ─────────────────────────────────────────────────────────

/// Converts a [`GhosttyResult`] to `Ok(())` or a [`TtyBackendError`].
fn check(result: GhosttyResult, context: &str) -> Result<(), TtyBackendError> {
    match result {
        GhosttyResult::Success => Ok(()),
        GhosttyResult::OutOfMemory => Err(TtyBackendError::Internal {
            detail: format!("{context}: out of memory"),
        }),
        GhosttyResult::InvalidValue => Err(TtyBackendError::Internal {
            detail: format!("{context}: invalid value"),
        }),
        GhosttyResult::OutOfSpace => Err(TtyBackendError::Internal {
            detail: format!("{context}: out of space"),
        }),
    }
}

fn color_from_c(c: GhosttyColorRgb) -> RenderColor {
    RenderColor {
        r: c.r,
        g: c.g,
        b: c.b,
    }
}

// ── Terminal ────────────────────────────────────────────────────────

/// RAII wrapper around a `GhosttyTerminal` handle.
pub(crate) struct Terminal {
    handle: GhosttyTerminal,
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

        let mut handle: GhosttyTerminal = ptr::null_mut();

        // SAFETY: `ghostty_terminal_new` writes a valid handle to
        // `handle` on success and returns a result code. The allocator
        // is NULL (default). The handle pointer is valid stack memory.
        let result = unsafe { ghostty_terminal_new(ptr::null(), &mut handle, opts) };
        check(result, "ghostty_terminal_new")?;

        if handle.is_null() {
            return Err(TtyBackendError::Internal {
                detail: "ghostty_terminal_new returned null handle".into(),
            });
        }

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
            ghostty_terminal_vt_write(self.handle, data.as_ptr(), data.len());
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
            unsafe { ghostty_terminal_resize(self.handle, cols, rows, cell_width_px, cell_height_px) };
        check(result, "ghostty_terminal_resize")
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
                self.handle,
                GhosttyTerminalOption::Userdata,
                userdata.cast(),
            );
            ghostty_terminal_set(
                self.handle,
                GhosttyTerminalOption::WritePty,
                callback
                    .map_or(ptr::null(), |f| f as *const std::ffi::c_void),
            );
        }
    }

    /// Returns the raw handle for passing to render state updates and
    /// key encoder sync. The caller must not free or store the handle.
    pub(crate) fn raw_handle(&self) -> GhosttyTerminal {
        self.handle
    }
}

impl Drop for Terminal {
    fn drop(&mut self) {
        if !self.handle.is_null() {
            // SAFETY: The handle is valid and owned by this struct.
            // After this call it becomes invalid.
            unsafe { ghostty_terminal_free(self.handle) };
            self.handle = ptr::null_mut();
        }
    }
}

// ── Render State ────────────────────────────────────────────────────

/// RAII wrapper around `GhosttyRenderState`, `GhosttyRenderStateRowIterator`,
/// and `GhosttyRenderStateRowCells`.
///
/// Owns all three handles and reuses them across frames to avoid
/// repeated allocation.
pub(crate) struct RenderState {
    state: GhosttyRenderState,
    row_iter: GhosttyRenderStateRowIterator,
    row_cells: GhosttyRenderStateRowCells,
}

unsafe impl Send for RenderState {}

impl RenderState {
    /// Creates a new render state with pre-allocated iterators.
    pub(crate) fn new() -> Result<Self, TtyBackendError> {
        let mut state: GhosttyRenderState = ptr::null_mut();
        let mut row_iter: GhosttyRenderStateRowIterator = ptr::null_mut();
        let mut row_cells: GhosttyRenderStateRowCells = ptr::null_mut();

        // SAFETY: All output pointers are valid stack memory. The
        // allocator is NULL (default).
        unsafe {
            check(
                ghostty_render_state_new(ptr::null(), &mut state),
                "ghostty_render_state_new",
            )?;
            check(
                ghostty_render_state_row_iterator_new(ptr::null(), &mut row_iter),
                "ghostty_render_state_row_iterator_new",
            )?;
            check(
                ghostty_render_state_row_cells_new(ptr::null(), &mut row_cells),
                "ghostty_render_state_row_cells_new",
            )?;
        }

        Ok(Self {
            state,
            row_iter,
            row_cells,
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
                ghostty_render_state_update(self.state, terminal.raw_handle()),
                "ghostty_render_state_update",
            )?;
        }

        let dirty = self.get_dirty()?;
        let (cols, num_rows) = self.get_dimensions()?;
        let cursor = self.get_cursor()?;
        let (default_bg, default_fg, palette) = self.get_colors()?;
        let rows = self.extract_rows(cols)?;

        // Clear dirty state after extraction.
        self.clear_dirty();

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
                    self.state,
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
                    self.state,
                    GhosttyRenderStateData::Cols,
                    (&mut cols as *mut u16).cast(),
                ),
                "render_state_get(Cols)",
            )?;
            check(
                ghostty_render_state_get(
                    self.state,
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
                self.state,
                GhosttyRenderStateData::CursorVisible,
                (&mut visible as *mut bool).cast(),
            );
            let _ = ghostty_render_state_get(
                self.state,
                GhosttyRenderStateData::CursorViewportHasValue,
                (&mut has_viewport as *mut bool).cast(),
            );
            if has_viewport {
                let _ = ghostty_render_state_get(
                    self.state,
                    GhosttyRenderStateData::CursorViewportX,
                    (&mut x as *mut u16).cast(),
                );
                let _ = ghostty_render_state_get(
                    self.state,
                    GhosttyRenderStateData::CursorViewportY,
                    (&mut y as *mut u16).cast(),
                );
            }
            let _ = ghostty_render_state_get(
                self.state,
                GhosttyRenderStateData::CursorBlinking,
                (&mut blinking as *mut bool).cast(),
            );
            let _ = ghostty_render_state_get(
                self.state,
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
        let mut colors = GhosttyRenderStateColors {
            size: core::mem::size_of::<GhosttyRenderStateColors>(),
            background: GhosttyColorRgb::default(),
            foreground: GhosttyColorRgb::default(),
            cursor: GhosttyColorRgb::default(),
            cursor_has_value: false,
            palette: [GhosttyColorRgb::default(); 256],
        };

        // SAFETY: `colors.size` is correctly set. The output pointer
        // is valid for the full struct size.
        unsafe {
            check(
                ghostty_render_state_colors_get(self.state, &mut colors),
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
                    self.state,
                    GhosttyRenderStateData::RowIterator,
                    (self.row_iter as *mut std::ffi::c_void).cast(),
                ),
                "render_state_get(RowIterator)",
            )?;
        }

        let mut rows = Vec::new();

        // SAFETY: The row iterator was just populated. `next()` is
        // safe to call until it returns false.
        while unsafe { ghostty_render_state_row_iterator_next(self.row_iter) } {
            let mut row_dirty = false;
            // SAFETY: The iterator is positioned on a valid row.
            unsafe {
                let _ = ghostty_render_state_row_get(
                    self.row_iter,
                    GhosttyRenderStateRowData::Dirty,
                    (&mut row_dirty as *mut bool).cast(),
                );
            }

            let cells = self.extract_cells(cols)?;

            // Clear per-row dirty flag after reading.
            // SAFETY: The iterator is positioned on a valid row.
            unsafe {
                let false_val = false;
                let _ = ghostty_render_state_row_set(
                    self.row_iter,
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
                    self.row_iter,
                    GhosttyRenderStateRowData::Cells,
                    (self.row_cells as *mut std::ffi::c_void).cast(),
                ),
                "render_state_row_get(Cells)",
            )?;
        }

        let mut cells = Vec::with_capacity(cols as usize);

        // SAFETY: The row cells handle was just populated and is
        // valid until the next render_state_update.
        while unsafe { ghostty_render_state_row_cells_next(self.row_cells) } {
            // Read grapheme length.
            let mut graphemes_len: u32 = 0;
            // SAFETY: Output pointer is valid stack memory.
            unsafe {
                let _ = ghostty_render_state_row_cells_get(
                    self.row_cells,
                    GhosttyRenderStateRowCellsData::GraphemesLen,
                    (&mut graphemes_len as *mut u32).cast(),
                );
            }

            // Read codepoints if any.
            let codepoints = if graphemes_len > 0 {
                let mut buf = vec![0u32; graphemes_len as usize];
                // SAFETY: The buffer is correctly sized for the
                // number of codepoints reported by GraphemesLen.
                unsafe {
                    let _ = ghostty_render_state_row_cells_get(
                        self.row_cells,
                        GhosttyRenderStateRowCellsData::GraphemesBuf,
                        buf.as_mut_ptr().cast(),
                    );
                }
                buf
            } else {
                Vec::new()
            };

            // Read foreground color (returns InvalidValue if default).
            let fg = {
                let mut color = GhosttyColorRgb::default();
                // SAFETY: Output pointer is valid stack memory.
                let result = unsafe {
                    ghostty_render_state_row_cells_get(
                        self.row_cells,
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
                        self.row_cells,
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

            cells.push(RenderCell {
                codepoints,
                fg,
                bg,
                flags: CellStyleFlags::default(),
                width: 1,
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
                self.state,
                GhosttyRenderStateOption::Dirty,
                (&clean as *const GhosttyRenderStateDirty).cast(),
            );
        }
    }
}

impl Drop for RenderState {
    fn drop(&mut self) {
        // SAFETY: All handles are valid and owned by this struct.
        // Free in reverse allocation order.
        unsafe {
            if !self.row_cells.is_null() {
                ghostty_render_state_row_cells_free(self.row_cells);
            }
            if !self.row_iter.is_null() {
                ghostty_render_state_row_iterator_free(self.row_iter);
            }
            if !self.state.is_null() {
                ghostty_render_state_free(self.state);
            }
        }
    }
}

// ── Key Encoder ─────────────────────────────────────────────────────

/// RAII wrapper around `GhosttyKeyEncoder` and a reusable
/// `GhosttyKeyEvent`.
pub(crate) struct KeyEncoder {
    encoder: GhosttyKeyEncoder,
    event: GhosttyKeyEvent,
}

unsafe impl Send for KeyEncoder {}

impl KeyEncoder {
    /// Creates a new key encoder with a reusable event.
    pub(crate) fn new() -> Result<Self, TtyBackendError> {
        let mut encoder: GhosttyKeyEncoder = ptr::null_mut();
        let mut event: GhosttyKeyEvent = ptr::null_mut();

        // SAFETY: Output pointers are valid stack memory. Allocator
        // is NULL (default).
        unsafe {
            check(
                ghostty_key_encoder_new(ptr::null(), &mut encoder),
                "ghostty_key_encoder_new",
            )?;
            check(
                ghostty_key_event_new(ptr::null(), &mut event),
                "ghostty_key_event_new",
            )?;
        }

        Ok(Self { encoder, event })
    }

    /// Syncs encoder options from the terminal's current mode state
    /// (cursor key mode, Kitty keyboard flags, etc.).
    pub(crate) fn sync_from_terminal(&mut self, terminal: &Terminal) {
        // SAFETY: Both handles are valid and non-null.
        unsafe {
            ghostty_key_encoder_setopt_from_terminal(
                self.encoder,
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
            ghostty_key_event_set_action(self.event, action);
            ghostty_key_event_set_key(self.event, key);
            ghostty_key_event_set_mods(self.event, mods);

            if let Some(text) = utf8_text {
                ghostty_key_event_set_utf8(self.event, text.as_ptr(), text.len());
            } else {
                ghostty_key_event_set_utf8(self.event, ptr::null(), 0);
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
                self.encoder,
                self.event,
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
        // SAFETY: Both handles are valid and owned by this struct.
        unsafe {
            if !self.event.is_null() {
                ghostty_key_event_free(self.event);
            }
            if !self.encoder.is_null() {
                ghostty_key_encoder_free(self.encoder);
            }
        }
    }
}
