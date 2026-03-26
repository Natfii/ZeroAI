// Copyright (c) 2026 @Natfii. All rights reserved.

//! Terminal backend trait, render snapshot types, and stub implementation.
//!
//! [`TerminalBackend`] abstracts the VT parser + screen buffer so the
//! FFI layer can swap between a stub (used during tests or before a
//! connection is established) and the real libghostty-vt backend.

use std::fmt;

// ── Error type ───────────────────────────────────────────────────────

/// Errors produced by a [`TerminalBackend`] implementation.
#[derive(Debug, Clone)]
pub enum TtyBackendError {
    /// The terminal has not been initialised yet.
    NotInitialised,
    /// The requested resize dimensions are invalid.
    InvalidSize {
        /// Human-readable reason the size was rejected.
        detail: String,
    },
    /// An internal terminal processing error.
    Internal {
        /// Description of the failure.
        detail: String,
    },
}

impl fmt::Display for TtyBackendError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::NotInitialised => write!(f, "terminal backend not initialised"),
            Self::InvalidSize { detail } => write!(f, "invalid terminal size: {detail}"),
            Self::Internal { detail } => write!(f, "terminal backend error: {detail}"),
        }
    }
}

impl std::error::Error for TtyBackendError {}

// ── Color types ─────────────────────────────────────────────────────

/// RGB color with 8-bit channels.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct RenderColor {
    /// Red channel (0-255).
    pub r: u8,
    /// Green channel (0-255).
    pub g: u8,
    /// Blue channel (0-255).
    pub b: u8,
}

// ── Cursor types ────────────────────────────────────────────────────

/// Visual style of the terminal cursor.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CursorStyle {
    /// Thin vertical bar (DECSCUSR 5, 6).
    Bar,
    /// Filled block (DECSCUSR 1, 2).
    Block,
    /// Horizontal underline (DECSCUSR 3, 4).
    Underline,
    /// Hollow block outline.
    BlockHollow,
}

impl Default for CursorStyle {
    fn default() -> Self {
        Self::Block
    }
}

/// Cursor state within the terminal viewport.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct RenderCursor {
    /// Column position (0-indexed), if visible in viewport.
    pub x: u16,
    /// Row position (0-indexed), if visible in viewport.
    pub y: u16,
    /// Whether the cursor is visible.
    pub visible: bool,
    /// Visual style of the cursor.
    pub style: CursorStyle,
    /// Whether the cursor should blink.
    pub blinking: bool,
}

// ── Cell and row types ──────────────────────────────────────────────

/// Style flags for a single terminal cell.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct CellStyleFlags {
    /// Bold text.
    pub bold: bool,
    /// Italic text.
    pub italic: bool,
    /// Underlined text.
    pub underline: bool,
    /// Strikethrough text.
    pub strikethrough: bool,
    /// Reversed foreground/background.
    pub inverse: bool,
}

/// A single rendered terminal cell.
#[derive(Debug, Clone, Default)]
pub struct RenderCell {
    /// Unicode codepoints for this cell's grapheme cluster.
    /// Empty for blank/space cells.
    pub codepoints: Vec<u32>,
    /// Foreground color, or `None` for the terminal default.
    pub fg: Option<RenderColor>,
    /// Background color, or `None` for the terminal default.
    pub bg: Option<RenderColor>,
    /// Style flags (bold, italic, etc.).
    pub flags: CellStyleFlags,
    /// Cell width in columns (1 for normal, 2 for wide chars).
    pub width: u8,
}

/// A single rendered terminal row.
#[derive(Debug, Clone, Default)]
pub struct RenderRow {
    /// Cells in this row, one per column.
    pub cells: Vec<RenderCell>,
    /// Whether this row changed since the last snapshot.
    pub dirty: bool,
}

// ── Render snapshot ──────────────────────────────────────────────────

/// Dirty state of the terminal after a render state update.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DirtyState {
    /// Nothing changed — rendering can be skipped.
    Clean,
    /// Some rows changed — incremental redraw possible.
    Partial,
    /// Global state changed — full redraw needed.
    Full,
}

impl Default for DirtyState {
    fn default() -> Self {
        Self::Clean
    }
}

/// Complete snapshot of the terminal screen state for rendering.
///
/// Produced by [`TerminalBackend::snapshot_for_render`]. Contains all
/// data needed to draw one frame: grid cells, cursor, colors, and
/// dirty tracking.
#[derive(Debug, Clone, Default)]
pub struct TerminalRenderSnapshot {
    /// Overall dirty state since the last snapshot.
    pub dirty: DirtyState,
    /// Terminal rows, top to bottom.
    pub rows: Vec<RenderRow>,
    /// Number of columns in the grid.
    pub cols: u16,
    /// Number of rows in the grid.
    pub num_rows: u16,
    /// Cursor state.
    pub cursor: RenderCursor,
    /// Default background color for the terminal.
    pub default_bg: RenderColor,
    /// Default foreground color for the terminal.
    pub default_fg: RenderColor,
    /// The 256-color palette (indices 0-255).
    pub palette: Vec<RenderColor>,
}

// ── Trait ─────────────────────────────────────────────────────────────

/// Abstraction over a virtual terminal emulator (VT parser + grid).
///
/// Implementations must be [`Send`] so they can live behind a `Mutex`
/// in the async FFI layer.
pub(crate) trait TerminalBackend: Send {
    /// Feed raw bytes (PTY output) into the terminal parser.
    fn feed_input(&mut self, bytes: &[u8]) -> Result<(), TtyBackendError>;

    /// Resize the terminal grid.
    fn resize(&mut self, cols: u16, rows: u16) -> Result<(), TtyBackendError>;

    /// Capture the current screen state for rendering.
    fn snapshot_for_render(&mut self) -> TerminalRenderSnapshot;

    /// Produce one string per visible row for TalkBack / accessibility.
    fn snapshot_for_accessibility(&self, visible_rows: usize) -> Vec<String>;

    /// Serialise the visible scrollback into a byte buffer for LLM
    /// context injection (UTF-8 text, truncated to `max_bytes`).
    fn snapshot_for_context(&self, max_bytes: usize) -> Vec<u8>;

    /// Takes any pending write-PTY response bytes (terminal query
    /// responses that should be written back to the PTY/SSH channel).
    ///
    /// Default implementation returns empty. Backends that register
    /// write-PTY callbacks override this.
    fn take_pty_response(&self) -> Vec<u8> {
        Vec::new()
    }
}

// ── Stub implementation ──────────────────────────────────────────────

/// No-op backend that returns empty/default values.
///
/// Used before an SSH connection is established or during unit tests.
#[derive(Debug, Default)]
pub(crate) struct StubBackend;

impl TerminalBackend for StubBackend {
    fn feed_input(&mut self, _bytes: &[u8]) -> Result<(), TtyBackendError> {
        Ok(())
    }

    fn resize(&mut self, _cols: u16, _rows: u16) -> Result<(), TtyBackendError> {
        Ok(())
    }

    fn snapshot_for_render(&mut self) -> TerminalRenderSnapshot {
        TerminalRenderSnapshot::default()
    }

    fn snapshot_for_accessibility(&self, _visible_rows: usize) -> Vec<String> {
        Vec::new()
    }

    fn snapshot_for_context(&self, _max_bytes: usize) -> Vec<u8> {
        Vec::new()
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    #[test]
    fn stub_feed_input_is_noop() {
        let mut backend = StubBackend;
        assert!(backend.feed_input(b"hello").is_ok());
    }

    #[test]
    fn stub_resize_is_noop() {
        let mut backend = StubBackend;
        assert!(backend.resize(80, 24).is_ok());
    }

    #[test]
    fn stub_render_snapshot_is_empty() {
        let mut backend = StubBackend;
        let snap = backend.snapshot_for_render();
        assert_eq!(snap.dirty, DirtyState::Clean);
        assert!(snap.rows.is_empty());
    }

    #[test]
    fn stub_accessibility_snapshot_is_empty() {
        let backend = StubBackend;
        assert!(backend.snapshot_for_accessibility(24).is_empty());
    }

    #[test]
    fn stub_context_snapshot_is_empty() {
        let backend = StubBackend;
        assert!(backend.snapshot_for_context(65536).is_empty());
    }
}
