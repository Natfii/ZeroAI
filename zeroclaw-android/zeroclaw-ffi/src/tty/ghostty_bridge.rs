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
                self.handle,
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
                self.handle,
                GHOSTTY_MODE_BRACKETED_PASTE,
                &mut active,
            );
            if result != GhosttyResult::Success {
                return false;
            }
        }
        active
    }

    /// Returns the raw handle for passing to render state updates and
    /// key encoder sync. The caller must not free or store the handle.
    pub(crate) fn raw_handle(&self) -> GhosttyTerminal {
        self.handle
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
/// repeated allocation. Caches metadata from the last non-clean
/// frame so that [`DirtyState::Clean`] snapshots can short-circuit
/// without calling into the C library for dimensions, cursor, or
/// colors.
pub(crate) struct RenderState {
    state: GhosttyRenderState,
    row_iter: GhosttyRenderStateRowIterator,
    row_cells: GhosttyRenderStateRowCells,
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
                ghostty_render_state_update(self.state, terminal.raw_handle()),
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

            // Read Style=2: SGR attributes (bold, italic, etc.).
            let flags = {
                let mut style = GhosttyStyle::sized();
                // SAFETY: `style` is a valid sized struct with `size`
                // pre-filled. The output pointer is valid stack memory.
                let result = unsafe {
                    ghostty_render_state_row_cells_get(
                        self.row_cells,
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

            // Read raw cell value then query wide/narrow classification.
            let width = {
                let mut raw_cell: u64 = 0;
                // SAFETY: Output pointer is a valid u64 on the stack.
                let raw_result = unsafe {
                    ghostty_render_state_row_cells_get(
                        self.row_cells,
                        GhosttyRenderStateRowCellsData::Raw,
                        (&mut raw_cell as *mut u64).cast(),
                    )
                };
                if raw_result == GhosttyResult::Success {
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

// SAFETY: KeyEncoder holds two opaque C pointers (encoder, event).
// These are only accessed through &mut self in encode_key() and
// sync_from_terminal(). Moving across threads is safe as long as
// concurrent access is prevented (guaranteed by the Mutex in
// session.rs).
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

// ── Mouse Encoder ──────────────────────────────────────────────────

/// RAII wrapper around `GhosttyMouseEncoder` and a reusable
/// `GhosttyMouseEvent`. Encodes Android touch events into terminal
/// mouse escape sequences.
pub(crate) struct MouseEncoder {
    encoder: GhosttyMouseEncoder,
    event: GhosttyMouseEvent,
    /// `true` once `set_geometry` has been called at least once.
    has_geometry: bool,
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
        let mut encoder: GhosttyMouseEncoder = ptr::null_mut();
        let mut event: GhosttyMouseEvent = ptr::null_mut();

        // SAFETY: Output pointers are valid stack memory. Allocator is NULL (default).
        unsafe {
            check(
                ghostty_mouse_encoder_new(ptr::null(), &mut encoder),
                "ghostty_mouse_encoder_new",
            )?;
            check(
                ghostty_mouse_event_new(ptr::null(), &mut event),
                "ghostty_mouse_event_new",
            )?;
        }

        if encoder.is_null() {
            return Err(TtyBackendError::Internal {
                detail: "ghostty_mouse_encoder_new returned null handle".into(),
            });
        }
        if event.is_null() {
            return Err(TtyBackendError::Internal {
                detail: "ghostty_mouse_event_new returned null handle".into(),
            });
        }

        Ok(Self { encoder, event, has_geometry: false })
    }

    /// Syncs encoder options from the terminal's current mouse mode
    /// and format state (tracking mode, SGR/X10, etc.).
    pub(crate) fn sync_from_terminal(&mut self, terminal: &Terminal) {
        // SAFETY: Both handles are valid and non-null (checked in new()).
        // Terminal handle is valid and owned by the caller.
        unsafe {
            ghostty_mouse_encoder_setopt_from_terminal(
                self.encoder,
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
        let mut size = GhosttyMouseEncoderSize::sized();
        size.screen_width = screen_w;
        size.screen_height = screen_h;
        size.cell_width = cell_w;
        size.cell_height = cell_h;

        // SAFETY: Encoder handle is valid. The size struct pointer is
        // valid stack memory with correct `size` field set by sized().
        unsafe {
            ghostty_mouse_encoder_setopt(
                self.encoder,
                GhosttyMouseEncoderOption::Size,
                &size as *const GhosttyMouseEncoderSize as *const std::ffi::c_void,
            );
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

        // SAFETY: Event handle is valid (non-null, checked in new()).
        // Clear prior button state before setting new (C API is additive).
        unsafe {
            ghostty_mouse_event_set_action(self.event, mouse_action);
            ghostty_mouse_event_clear_button(self.event);
            if button != 0 {
                ghostty_mouse_event_set_button(self.event, mouse_button);
            }
            ghostty_mouse_event_set_position(self.event, position);
            ghostty_mouse_event_set_mods(self.event, mods as GhosttyMods);
        }

        let mut buf = [0u8; 128];
        let mut written: usize = 0;

        // SAFETY: Encoder and event handles are valid. Buffer pointer
        // and size are correct. `written` receives the actual byte count.
        let result = unsafe {
            ghostty_mouse_encoder_encode(
                self.encoder,
                self.event,
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
        // SAFETY: Both handles are valid and exclusively owned by self.
        unsafe {
            if !self.event.is_null() {
                ghostty_mouse_event_free(self.event);
            }
            if !self.encoder.is_null() {
                ghostty_mouse_encoder_free(self.encoder);
            }
        }
    }
}
