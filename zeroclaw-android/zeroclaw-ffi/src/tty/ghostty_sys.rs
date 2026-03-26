// Copyright (c) 2026 @Natfii. All rights reserved.

//! Raw C FFI declarations for libghostty-vt.
//!
//! Hand-written from the vendored `ghostty/vt.h` headers. Only the
//! subset of the API used by [`super::ghostty_bridge`] is declared.
//!
//! All functions in this module are `unsafe extern "C"` and must only
//! be called through the safe wrappers in [`super::ghostty_bridge`].

#![allow(non_camel_case_types, dead_code)]

use core::ffi::c_void;

// ── Result codes ────────────────────────────────────────────────────

/// Result codes for libghostty-vt operations.
#[repr(i32)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GhosttyResult {
    /// Operation completed successfully.
    Success = 0,
    /// Operation failed due to failed allocation.
    OutOfMemory = -1,
    /// Operation failed due to invalid value.
    InvalidValue = -2,
    /// Provided buffer was too small.
    OutOfSpace = -3,
}

// ── Borrowed string ─────────────────────────────────────────────────

/// A borrowed byte string (pointer + length).
#[repr(C)]
#[derive(Debug, Clone, Copy)]
pub struct GhosttyString {
    pub ptr: *const u8,
    pub len: usize,
}

// ── Color types ─────────────────────────────────────────────────────

/// RGB color value.
#[repr(C)]
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct GhosttyColorRgb {
    pub r: u8,
    pub g: u8,
    pub b: u8,
}

/// Palette color index (0-255).
pub type GhosttyColorPaletteIndex = u8;

// ── Opaque handles ──────────────────────────────────────────────────

/// Opaque handle to a terminal instance.
pub type GhosttyTerminal = *mut c_void;

/// Opaque handle to a render state instance.
pub type GhosttyRenderState = *mut c_void;

/// Opaque handle to a render-state row iterator.
pub type GhosttyRenderStateRowIterator = *mut c_void;

/// Opaque handle to render-state row cells.
pub type GhosttyRenderStateRowCells = *mut c_void;

/// Opaque handle to a key encoder instance.
pub type GhosttyKeyEncoder = *mut c_void;

/// Opaque handle to a key event.
pub type GhosttyKeyEvent = *mut c_void;

/// Allocator pointer (always NULL to use default).
pub type GhosttyAllocator = c_void;

// ── Terminal options ────────────────────────────────────────────────

/// Terminal initialization options.
#[repr(C)]
#[derive(Debug, Clone, Copy)]
pub struct GhosttyTerminalOptions {
    pub cols: u16,
    pub rows: u16,
    pub max_scrollback: usize,
}

/// Terminal option identifiers for `ghostty_terminal_set`.
#[repr(i32)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GhosttyTerminalOption {
    Userdata = 0,
    WritePty = 1,
    Bell = 2,
    Enquiry = 3,
    Xtversion = 4,
    TitleChanged = 5,
    Size = 6,
    ColorScheme = 7,
    DeviceAttributes = 8,
    Title = 9,
    Pwd = 10,
}

/// Terminal data types for `ghostty_terminal_get`.
#[repr(i32)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GhosttyTerminalData {
    Invalid = 0,
    Cols = 1,
    Rows = 2,
    CursorX = 3,
    CursorY = 4,
    CursorPendingWrap = 5,
    ActiveScreen = 6,
    CursorVisible = 7,
    KittyKeyboardFlags = 8,
    Scrollbar = 9,
    CursorStyle = 10,
    MouseTracking = 11,
    Title = 12,
    Pwd = 13,
    TotalRows = 14,
    ScrollbackRows = 15,
    WidthPx = 16,
    HeightPx = 17,
}

/// Callback for `GHOSTTY_TERMINAL_OPT_WRITE_PTY`.
pub type GhosttyTerminalWritePtyFn = Option<
    unsafe extern "C" fn(
        terminal: GhosttyTerminal,
        userdata: *mut c_void,
        data: *const u8,
        len: usize,
    ),
>;

// ── Render state enums ──────────────────────────────────────────────

/// Dirty state of a render state after update.
#[repr(i32)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GhosttyRenderStateDirty {
    False = 0,
    Partial = 1,
    Full = 2,
}

/// Visual style of the cursor.
#[repr(i32)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GhosttyRenderStateCursorVisualStyle {
    Bar = 0,
    Block = 1,
    Underline = 2,
    BlockHollow = 3,
}

/// Queryable data kinds for `ghostty_render_state_get`.
#[repr(i32)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GhosttyRenderStateData {
    Invalid = 0,
    Cols = 1,
    Rows = 2,
    Dirty = 3,
    RowIterator = 4,
    ColorBackground = 5,
    ColorForeground = 6,
    ColorCursor = 7,
    ColorCursorHasValue = 8,
    ColorPalette = 9,
    CursorVisualStyle = 10,
    CursorVisible = 11,
    CursorBlinking = 12,
    CursorPasswordInput = 13,
    CursorViewportHasValue = 14,
    CursorViewportX = 15,
    CursorViewportY = 16,
    CursorViewportWideTail = 17,
}

/// Settable options for `ghostty_render_state_set`.
#[repr(i32)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GhosttyRenderStateOption {
    Dirty = 0,
}

/// Queryable data for row iterator.
#[repr(i32)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GhosttyRenderStateRowData {
    Invalid = 0,
    Dirty = 1,
    Raw = 2,
    Cells = 3,
}

/// Settable options for row iterator.
#[repr(i32)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GhosttyRenderStateRowOption {
    Dirty = 0,
}

/// Queryable data for row cells.
#[repr(i32)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GhosttyRenderStateRowCellsData {
    Invalid = 0,
    Raw = 1,
    Style = 2,
    GraphemesLen = 3,
    GraphemesBuf = 4,
    BgColor = 5,
    FgColor = 6,
}

// ── Render state colors (sized struct) ──────────────────────────────

/// Render-state color information (sized-struct ABI pattern).
#[repr(C)]
pub struct GhosttyRenderStateColors {
    pub size: usize,
    pub background: GhosttyColorRgb,
    pub foreground: GhosttyColorRgb,
    pub cursor: GhosttyColorRgb,
    pub cursor_has_value: bool,
    pub palette: [GhosttyColorRgb; 256],
}

// ── Style types ─────────────────────────────────────────────────────

/// Style color tags.
#[repr(i32)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GhosttyStyleColorTag {
    None = 0,
    Palette = 1,
    Rgb = 2,
}

// ── Key encoding types ──────────────────────────────────────────────

/// Key action.
#[repr(i32)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GhosttyKeyAction {
    Release = 0,
    Press = 1,
    Repeat = 2,
}

/// Modifier keys bitmask.
pub type GhosttyMods = u16;

pub const GHOSTTY_MODS_SHIFT: GhosttyMods = 1 << 0;
pub const GHOSTTY_MODS_CTRL: GhosttyMods = 1 << 1;
pub const GHOSTTY_MODS_ALT: GhosttyMods = 1 << 2;
pub const GHOSTTY_MODS_SUPER: GhosttyMods = 1 << 3;

/// Physical key codes (subset used by our Android key mapping).
#[repr(i32)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[allow(clippy::enum_variant_names)]
pub enum GhosttyKey {
    Unidentified = 0,
    // Writing system keys
    Backquote, Backslash, BracketLeft, BracketRight, Comma,
    Digit0, Digit1, Digit2, Digit3, Digit4,
    Digit5, Digit6, Digit7, Digit8, Digit9,
    Equal, IntlBackslash, IntlRo, IntlYen,
    A, B, C, D, E, F, G, H, I, J, K, L, M,
    N, O, P, Q, R, S, T, U, V, W, X, Y, Z,
    Minus, Period, Quote, Semicolon, Slash,
    // Functional keys
    AltLeft, AltRight, Backspace, CapsLock, ContextMenu,
    ControlLeft, ControlRight, Enter, MetaLeft, MetaRight,
    ShiftLeft, ShiftRight, Space, Tab,
    Convert, KanaMode, NonConvert,
    // Control pad
    Delete, End, Help, Home, Insert, PageDown, PageUp,
    // Arrow pad
    ArrowDown, ArrowLeft, ArrowRight, ArrowUp,
    // Numpad (truncated — we only use a few)
    NumLock,
    // Function keys
    Escape = 86, // offset to match C enum after all numpad entries
    F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12,
}

// ── Extern C functions ──────────────────────────────────────────────

unsafe extern "C" {
    // ── Terminal ──────────────────────────────────────────────────

    pub fn ghostty_terminal_new(
        allocator: *const GhosttyAllocator,
        terminal: *mut GhosttyTerminal,
        options: GhosttyTerminalOptions,
    ) -> GhosttyResult;

    pub fn ghostty_terminal_free(terminal: GhosttyTerminal);

    pub fn ghostty_terminal_reset(terminal: GhosttyTerminal);

    pub fn ghostty_terminal_vt_write(
        terminal: GhosttyTerminal,
        data: *const u8,
        len: usize,
    );

    pub fn ghostty_terminal_resize(
        terminal: GhosttyTerminal,
        cols: u16,
        rows: u16,
        cell_width_px: u32,
        cell_height_px: u32,
    ) -> GhosttyResult;

    pub fn ghostty_terminal_set(
        terminal: GhosttyTerminal,
        option: GhosttyTerminalOption,
        value: *const c_void,
    ) -> GhosttyResult;

    pub fn ghostty_terminal_get(
        terminal: GhosttyTerminal,
        data: GhosttyTerminalData,
        out: *mut c_void,
    ) -> GhosttyResult;

    // ── Render State ─────────────────────────────────────────────

    pub fn ghostty_render_state_new(
        allocator: *const GhosttyAllocator,
        state: *mut GhosttyRenderState,
    ) -> GhosttyResult;

    pub fn ghostty_render_state_free(state: GhosttyRenderState);

    pub fn ghostty_render_state_update(
        state: GhosttyRenderState,
        terminal: GhosttyTerminal,
    ) -> GhosttyResult;

    pub fn ghostty_render_state_get(
        state: GhosttyRenderState,
        data: GhosttyRenderStateData,
        out: *mut c_void,
    ) -> GhosttyResult;

    pub fn ghostty_render_state_set(
        state: GhosttyRenderState,
        option: GhosttyRenderStateOption,
        value: *const c_void,
    ) -> GhosttyResult;

    pub fn ghostty_render_state_colors_get(
        state: GhosttyRenderState,
        out_colors: *mut GhosttyRenderStateColors,
    ) -> GhosttyResult;

    // ── Row Iterator ─────────────────────────────────────────────

    pub fn ghostty_render_state_row_iterator_new(
        allocator: *const GhosttyAllocator,
        out_iterator: *mut GhosttyRenderStateRowIterator,
    ) -> GhosttyResult;

    pub fn ghostty_render_state_row_iterator_free(
        iterator: GhosttyRenderStateRowIterator,
    );

    pub fn ghostty_render_state_row_iterator_next(
        iterator: GhosttyRenderStateRowIterator,
    ) -> bool;

    pub fn ghostty_render_state_row_get(
        iterator: GhosttyRenderStateRowIterator,
        data: GhosttyRenderStateRowData,
        out: *mut c_void,
    ) -> GhosttyResult;

    pub fn ghostty_render_state_row_set(
        iterator: GhosttyRenderStateRowIterator,
        option: GhosttyRenderStateRowOption,
        value: *const c_void,
    ) -> GhosttyResult;

    // ── Row Cells ────────────────────────────────────────────────

    pub fn ghostty_render_state_row_cells_new(
        allocator: *const GhosttyAllocator,
        out_cells: *mut GhosttyRenderStateRowCells,
    ) -> GhosttyResult;

    pub fn ghostty_render_state_row_cells_next(
        cells: GhosttyRenderStateRowCells,
    ) -> bool;

    pub fn ghostty_render_state_row_cells_select(
        cells: GhosttyRenderStateRowCells,
        x: u16,
    ) -> GhosttyResult;

    pub fn ghostty_render_state_row_cells_get(
        cells: GhosttyRenderStateRowCells,
        data: GhosttyRenderStateRowCellsData,
        out: *mut c_void,
    ) -> GhosttyResult;

    pub fn ghostty_render_state_row_cells_free(
        cells: GhosttyRenderStateRowCells,
    );

    // ── Key Encoder ──────────────────────────────────────────────

    pub fn ghostty_key_encoder_new(
        allocator: *const GhosttyAllocator,
        encoder: *mut GhosttyKeyEncoder,
    ) -> GhosttyResult;

    pub fn ghostty_key_encoder_free(encoder: GhosttyKeyEncoder);

    pub fn ghostty_key_encoder_setopt_from_terminal(
        encoder: GhosttyKeyEncoder,
        terminal: GhosttyTerminal,
    );

    pub fn ghostty_key_encoder_encode(
        encoder: GhosttyKeyEncoder,
        event: GhosttyKeyEvent,
        out_buf: *mut u8,
        out_buf_size: usize,
        out_len: *mut usize,
    ) -> GhosttyResult;

    // ── Key Event ────────────────────────────────────────────────

    pub fn ghostty_key_event_new(
        allocator: *const GhosttyAllocator,
        event: *mut GhosttyKeyEvent,
    ) -> GhosttyResult;

    pub fn ghostty_key_event_free(event: GhosttyKeyEvent);

    pub fn ghostty_key_event_set_action(
        event: GhosttyKeyEvent,
        action: GhosttyKeyAction,
    );

    pub fn ghostty_key_event_set_key(
        event: GhosttyKeyEvent,
        key: GhosttyKey,
    );

    pub fn ghostty_key_event_set_mods(
        event: GhosttyKeyEvent,
        mods: GhosttyMods,
    );

    pub fn ghostty_key_event_set_utf8(
        event: GhosttyKeyEvent,
        utf8: *const u8,
        len: usize,
    );
}
