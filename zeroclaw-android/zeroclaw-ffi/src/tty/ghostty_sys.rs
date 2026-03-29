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

// ── Sized-struct helper macro ────────────────────────────────────────

/// Creates a default-initialized sized struct with `size` pre-filled.
///
/// The "sized struct" ABI pattern lets the C library detect which struct
/// version the caller is using by reading the `size` field.
macro_rules! sized {
    ($ty:ty) => {{
        let mut t = <$ty as ::std::default::Default>::default();
        t.size = ::std::mem::size_of::<$ty>();
        t
    }};
}
pub(crate) use sized;

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

// ── Mode types ──────────────────────────────────────────────────────

/// Packed 16-bit terminal mode identifier.
///
/// Encodes a mode value (bits 0–14) and an ANSI flag (bit 15) into a
/// single 16-bit integer. Construct with `ghostty_mode_new` helpers or
/// use the named constants such as [`GHOSTTY_MODE_SYNC_OUTPUT`].
pub type GhosttyMode = u16;

/// Synchronized output mode (DEC private mode 2026).
///
/// When this mode is active the terminal is mid-batch update and
/// rendering should be deferred to avoid tearing.
pub const GHOSTTY_MODE_SYNC_OUTPUT: GhosttyMode = 2026 & 0x7FFF; // DEC private: ansi bit = 0

/// Bracketed paste mode (DEC private mode 2004).
///
/// When active, paste content must be wrapped in
/// `\x1b[200~` ... `\x1b[201~`.
pub const GHOSTTY_MODE_BRACKETED_PASTE: GhosttyMode = 2004 & 0x7FFF;

/// Focus reporting mode (DEC private mode 1004).
///
/// When active, the terminal expects `CSI I` (gained) and `CSI O`
/// (lost) sequences when the window gains or loses focus.
pub const GHOSTTY_MODE_FOCUS_REPORTING: GhosttyMode = 1004 & 0x7FFF;

// ── Focus Events ───────────────────────────────────────────────────

/// Focus event type for [`ghostty_focus_encode`].
#[repr(i32)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GhosttyFocusEvent {
    /// Terminal window gained focus.
    Gained = 0,
    /// Terminal window lost focus.
    Lost = 1,
}

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

/// Callback for `GHOSTTY_TERMINAL_OPT_BELL`.
///
/// Invoked by libghostty-vt when the terminal receives a BEL (0x07)
/// character. The callback receives the terminal handle and the
/// userdata pointer registered alongside it.
pub type GhosttyTerminalBellFn =
    Option<unsafe extern "C" fn(terminal: GhosttyTerminal, userdata: *mut c_void)>;

/// Callback for `GHOSTTY_TERMINAL_OPT_TITLE_CHANGED`.
///
/// Invoked by libghostty-vt when the terminal title changes via
/// OSC 0 or OSC 2. The callback receives the terminal handle and
/// the userdata pointer registered alongside it. The actual title
/// text must be read separately via `ghostty_terminal_get` with
/// [`GhosttyTerminalData::Title`].
pub type GhosttyTerminalTitleChangedFn =
    Option<unsafe extern "C" fn(terminal: GhosttyTerminal, userdata: *mut c_void)>;

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

impl Default for GhosttyRenderStateColors {
    fn default() -> Self {
        // SAFETY: All-zero is valid for this repr(C) struct: RGB fields
        // default to black, bool to false, palette to 256x black.
        unsafe { std::mem::zeroed() }
    }
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

/// Union payload for a style color value.
///
/// Which field is active depends on the accompanying [`GhosttyStyleColorTag`].
/// The `_padding` field ensures a stable 8-byte size across platforms.
#[repr(C)]
pub union GhosttyStyleColorValue {
    /// Palette index when tag is [`GhosttyStyleColorTag::Palette`].
    pub palette: u8,
    /// RGB triplet when tag is [`GhosttyStyleColorTag::Rgb`].
    pub rgb: GhosttyColorRgb,
    /// Padding to guarantee 8-byte size regardless of active variant.
    pub _padding: u64,
}

impl Default for GhosttyStyleColorValue {
    fn default() -> Self {
        // SAFETY: All-zero bytes are a valid representation for every
        // variant — palette=0, rgb={0,0,0}, _padding=0.
        Self { _padding: 0 }
    }
}

/// A tagged color value used in cell style information.
///
/// The `tag` field identifies which variant of `value` is active.
#[repr(C)]
#[derive(Default)]
pub struct GhosttyStyleColor {
    /// Discriminant identifying which `value` variant is active.
    pub tag: GhosttyStyleColorTag,
    /// The color payload; interpret according to `tag`.
    pub value: GhosttyStyleColorValue,
}

impl Default for GhosttyStyleColorTag {
    fn default() -> Self {
        Self::None
    }
}

/// SGR underline style variants.
#[repr(i32)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GhosttySgrUnderline {
    /// No underline.
    None = 0,
    /// Single underline (SGR 4).
    Single = 1,
    /// Double underline (SGR 21).
    Double = 2,
    /// Curly/wavy underline (SGR 4:3).
    Curly = 3,
    /// Dotted underline (SGR 4:4).
    Dotted = 4,
    /// Dashed underline (SGR 4:5).
    Dashed = 5,
}

/// Sized cell style struct (sized-struct ABI pattern).
///
/// `size` must be set to `size_of::<GhosttyStyle>()` before passing to
/// `ghostty_render_state_row_cells_get` with the `Style` data tag.
/// Use `sized!(GhosttyStyle)` to construct a correctly initialized instance.
#[repr(C)]
pub struct GhosttyStyle {
    /// Must equal `size_of::<GhosttyStyle>()`.
    pub size: usize,
    /// Foreground color.
    pub fg_color: GhosttyStyleColor,
    /// Background color.
    pub bg_color: GhosttyStyleColor,
    /// Underline color.
    pub underline_color: GhosttyStyleColor,
    /// SGR 1 bold.
    pub bold: bool,
    /// SGR 3 italic.
    pub italic: bool,
    /// SGR 2 faint/dim.
    pub faint: bool,
    /// SGR 5/6 blink.
    pub blink: bool,
    /// SGR 7 inverse video.
    pub inverse: bool,
    /// SGR 8 invisible/concealed.
    pub invisible: bool,
    /// SGR 9 strikethrough.
    pub strikethrough: bool,
    /// SGR 53 overline.
    pub overline: bool,
    /// SGR 4 underline style; maps to [`GhosttySgrUnderline`] variants.
    pub underline: i32,
}

impl Default for GhosttyStyle {
    fn default() -> Self {
        // SAFETY: All-zero bytes are a valid initialisation for this
        // `#[repr(C)]` struct: numeric fields zero, bool fields false,
        // tag fields map to `GhosttyStyleColorTag::None` (= 0).
        unsafe { std::mem::zeroed() }
    }
}

/// Cell content tag — describes what kind of content a cell holds.
///
/// Maps to `GhosttyCellContentTag` in `ghostty/vt/screen.h`.
/// Query via [`ghostty_cell_get`] with [`GhosttyCellData::ContentTag`]
/// on the raw cell value from [`GhosttyRenderStateRowCellsData::Raw`].
#[repr(i32)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GhosttyCellContentTag {
    /// A single codepoint (may be zero for an empty cell).
    Codepoint = 0,
    /// A codepoint that is part of a multi-codepoint grapheme cluster.
    CodepointGrapheme = 1,
    /// No text content; cell carries a background color from the palette.
    BgColorPalette = 2,
    /// No text content; cell carries a background color as an RGB value.
    BgColorRgb = 3,
}

/// Wide/narrow cell classification from the terminal grid.
#[repr(i32)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GhosttyCellWide {
    /// Normal single-column cell.
    Narrow = 0,
    /// First column of a two-column wide character.
    Wide = 1,
    /// Spacer occupying the second column of a wide character (tail).
    SpacerTail = 2,
    /// Spacer used as a placeholder before a wide character (head).
    SpacerHead = 3,
}

/// Cell data tags for `ghostty_cell_get`.
#[repr(i32)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GhosttyCellData {
    /// Invalid / unrecognised tag.
    Invalid = 0,
    /// Raw codepoint value.
    Codepoint = 1,
    /// Content tag discriminant.
    ContentTag = 2,
    /// Wide/narrow classification.
    Wide = 3,
    /// Whether the cell has a text codepoint.
    HasText = 4,
    /// Whether the cell carries explicit styling.
    HasStyling = 5,
    /// Style identifier.
    StyleId = 6,
    /// Whether the cell has a hyperlink.
    HasHyperlink = 7,
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
    // Writing System Keys (W3C § 3.1.1)
    Backquote,
    Backslash,
    BracketLeft,
    BracketRight,
    Comma,
    Digit0,
    Digit1,
    Digit2,
    Digit3,
    Digit4,
    Digit5,
    Digit6,
    Digit7,
    Digit8,
    Digit9,
    Equal,
    IntlBackslash,
    IntlRo,
    IntlYen,
    A,
    B,
    C,
    D,
    E,
    F,
    G,
    H,
    I,
    J,
    K,
    L,
    M,
    N,
    O,
    P,
    Q,
    R,
    S,
    T,
    U,
    V,
    W,
    X,
    Y,
    Z,
    Minus,
    Period,
    Quote,
    Semicolon,
    Slash,
    // Functional Keys (W3C § 3.1.2)
    AltLeft,
    AltRight,
    Backspace,
    CapsLock,
    ContextMenu,
    ControlLeft,
    ControlRight,
    Enter,
    MetaLeft,
    MetaRight,
    ShiftLeft,
    ShiftRight,
    Space,
    Tab,
    Convert,
    KanaMode,
    NonConvert,
    // Control Pad (W3C § 3.2)
    Delete,
    End,
    Help,
    Home,
    Insert,
    PageDown,
    PageUp,
    // Arrow Pad (W3C § 3.3)
    ArrowDown,
    ArrowLeft,
    ArrowRight,
    ArrowUp,
    // Numpad (W3C § 3.4) — all 41 variants, must match C header exactly
    NumLock,
    Numpad0,
    Numpad1,
    Numpad2,
    Numpad3,
    Numpad4,
    Numpad5,
    Numpad6,
    Numpad7,
    Numpad8,
    Numpad9,
    NumpadAdd,
    NumpadBackspace,
    NumpadClear,
    NumpadClearEntry,
    NumpadComma,
    NumpadDecimal,
    NumpadDivide,
    NumpadEnter,
    NumpadEqual,
    NumpadMemoryAdd,
    NumpadMemoryClear,
    NumpadMemoryRecall,
    NumpadMemoryStore,
    NumpadMemorySubtract,
    NumpadMultiply,
    NumpadParenLeft,
    NumpadParenRight,
    NumpadSubtract,
    NumpadSeparator,
    NumpadUp,
    NumpadDown,
    NumpadRight,
    NumpadLeft,
    NumpadBegin,
    NumpadHome,
    NumpadEnd,
    NumpadInsert,
    NumpadDelete,
    NumpadPageUp,
    NumpadPageDown,
    // Function Keys (W3C § 3.5)
    Escape,
    F1,
    F2,
    F3,
    F4,
    F5,
    F6,
    F7,
    F8,
    F9,
    F10,
    F11,
    F12,
    F13,
    F14,
    F15,
    F16,
    F17,
    F18,
    F19,
    F20,
    F21,
    F22,
    F23,
    F24,
    F25,
    Fn,
    FnLock,
    PrintScreen,
    ScrollLock,
    Pause,
}

// ── Mouse Types ────────────────────────────────────────────────────

/// Opaque handle to a mouse encoder instance.
pub type GhosttyMouseEncoder = *mut c_void;

/// Opaque handle to a mouse event instance.
pub type GhosttyMouseEvent = *mut c_void;

/// Mouse button action (press, release, or motion).
#[repr(i32)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GhosttyMouseAction {
    Press = 0,
    Release = 1,
    Motion = 2,
}

/// Mouse button identifier.
#[repr(i32)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GhosttyMouseButton {
    Unknown = 0,
    Left = 1,
    Right = 2,
    Middle = 3,
    Four = 4,
    Five = 5,
    Six = 6,
    Seven = 7,
    Eight = 8,
    Nine = 9,
    Ten = 10,
    Eleven = 11,
}

/// Mouse tracking mode set by the terminal application via DECSET.
#[repr(i32)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GhosttyMouseTrackingMode {
    None = 0,
    X10 = 1,
    Normal = 2,
    Button = 3,
    Any = 4,
}

/// Mouse encoding format set by the terminal application via DECSET.
#[repr(i32)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GhosttyMouseFormat {
    X10 = 0,
    Utf8 = 1,
    Sgr = 2,
    Urxvt = 3,
    SgrPixels = 4,
}

/// Configuration option tags for `ghostty_mouse_encoder_setopt`.
#[repr(i32)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GhosttyMouseEncoderOption {
    Event = 0,
    Format = 1,
    Size = 2,
    AnyButtonPressed = 3,
    TrackLastCell = 4,
}

/// Surface-space pixel position for mouse events.
#[repr(C)]
#[derive(Debug, Clone, Copy, Default)]
pub struct GhosttyMousePosition {
    pub x: f32,
    pub y: f32,
}

/// Screen and cell geometry for the mouse encoder.
///
/// `size` must be set to `std::mem::size_of::<Self>()` before passing
/// to the C library (struct versioning).
#[repr(C)]
#[derive(Debug, Clone, Default)]
pub struct GhosttyMouseEncoderSize {
    pub size: usize,
    pub screen_width: u32,
    pub screen_height: u32,
    pub cell_width: u32,
    pub cell_height: u32,
    pub padding_top: u32,
    pub padding_bottom: u32,
    pub padding_right: u32,
    pub padding_left: u32,
}

// ── Build info ──────────────────────────────────────────────────────

/// Build info query tags for `ghostty_build_info`.
#[repr(i32)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GhosttyBuildInfo {
    /// Whether SIMD acceleration is compiled in.
    Simd = 0,
    /// Whether Kitty graphics protocol support is compiled in.
    KittyGraphics = 1,
    /// Whether tmux control-mode integration is compiled in.
    TmuxControlMode = 2,
    /// The optimization mode the library was built with.
    Optimize = 3,
}

/// Optimization mode returned by [`GhosttyBuildInfo::Optimize`].
#[repr(i32)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GhosttyOptimizeMode {
    /// Debug build (no optimizations, safety checks enabled).
    Debug = 0,
    /// ReleaseSafe build (optimizations with safety checks).
    ReleaseSafe = 1,
    /// ReleaseSmall build (optimized for binary size).
    ReleaseSmall = 2,
    /// ReleaseFast build (maximum optimizations, no safety checks).
    ReleaseFast = 3,
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

    pub fn ghostty_terminal_vt_write(terminal: GhosttyTerminal, data: *const u8, len: usize);

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

    pub fn ghostty_terminal_mode_get(
        terminal: GhosttyTerminal,
        mode: GhosttyMode,
        out_value: *mut bool,
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

    pub fn ghostty_render_state_row_iterator_free(iterator: GhosttyRenderStateRowIterator);

    pub fn ghostty_render_state_row_iterator_next(iterator: GhosttyRenderStateRowIterator) -> bool;

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

    pub fn ghostty_render_state_row_cells_next(cells: GhosttyRenderStateRowCells) -> bool;

    pub fn ghostty_render_state_row_cells_select(
        cells: GhosttyRenderStateRowCells,
        x: u16,
    ) -> GhosttyResult;

    pub fn ghostty_render_state_row_cells_get(
        cells: GhosttyRenderStateRowCells,
        data: GhosttyRenderStateRowCellsData,
        out: *mut c_void,
    ) -> GhosttyResult;

    pub fn ghostty_render_state_row_cells_free(cells: GhosttyRenderStateRowCells);

    /// Queries a single cell field by tag, writing the result into `out`.
    ///
    /// `cell` is the raw 64-bit cell value obtained via
    /// `GhosttyRenderStateRowCellsData::Raw`. `out` must point to
    /// memory of the appropriate type for the requested `data` tag.
    pub fn ghostty_cell_get(cell: u64, data: GhosttyCellData, out: *mut c_void) -> GhosttyResult;

    /// Writes a default (zeroed) [`GhosttyStyle`] into `style`.
    ///
    /// Provided as a helper for initialising sized style structs
    /// without knowing their exact field layout.
    pub fn ghostty_style_default(style: *mut GhosttyStyle);

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

    pub fn ghostty_key_event_set_action(event: GhosttyKeyEvent, action: GhosttyKeyAction);

    pub fn ghostty_key_event_set_key(event: GhosttyKeyEvent, key: GhosttyKey);

    pub fn ghostty_key_event_set_mods(event: GhosttyKeyEvent, mods: GhosttyMods);

    pub fn ghostty_key_event_set_utf8(event: GhosttyKeyEvent, utf8: *const u8, len: usize);

    /// Returns `true` if the paste data is safe (no embedded newlines
    /// or bracketed-paste-end sequences).
    ///
    /// `data` must be non-null and valid for `len` bytes.
    pub fn ghostty_paste_is_safe(data: *const u8, len: usize) -> bool;

    // ── Mouse Event ────────────────────────────────────────────────

    pub fn ghostty_mouse_event_new(
        allocator: *const GhosttyAllocator,
        event: *mut GhosttyMouseEvent,
    ) -> GhosttyResult;

    pub fn ghostty_mouse_event_free(event: GhosttyMouseEvent);

    pub fn ghostty_mouse_event_set_action(event: GhosttyMouseEvent, action: GhosttyMouseAction);

    pub fn ghostty_mouse_event_set_button(event: GhosttyMouseEvent, button: GhosttyMouseButton);

    pub fn ghostty_mouse_event_clear_button(event: GhosttyMouseEvent);

    pub fn ghostty_mouse_event_set_mods(event: GhosttyMouseEvent, mods: GhosttyMods);

    pub fn ghostty_mouse_event_set_position(
        event: GhosttyMouseEvent,
        position: GhosttyMousePosition,
    );

    // ── Mouse Encoder ──────────────────────────────────────────────

    pub fn ghostty_mouse_encoder_new(
        allocator: *const GhosttyAllocator,
        encoder: *mut GhosttyMouseEncoder,
    ) -> GhosttyResult;

    pub fn ghostty_mouse_encoder_free(encoder: GhosttyMouseEncoder);

    pub fn ghostty_mouse_encoder_setopt(
        encoder: GhosttyMouseEncoder,
        option: GhosttyMouseEncoderOption,
        value: *const c_void,
    );

    pub fn ghostty_mouse_encoder_setopt_from_terminal(
        encoder: GhosttyMouseEncoder,
        terminal: GhosttyTerminal,
    );

    pub fn ghostty_mouse_encoder_reset(encoder: GhosttyMouseEncoder);

    pub fn ghostty_mouse_encoder_encode(
        encoder: GhosttyMouseEncoder,
        event: GhosttyMouseEvent,
        out_buf: *mut u8,
        out_buf_size: usize,
        out_len: *mut usize,
    ) -> GhosttyResult;

    // ── Focus ─────────────────────────────────────────────────────

    pub fn ghostty_focus_encode(
        event: GhosttyFocusEvent,
        buf: *mut u8,
        buf_len: usize,
        out_written: *mut usize,
    ) -> GhosttyResult;

    // ── Build info ────────────────────────────────────────────────

    /// Queries build-time capability flags and options.
    ///
    /// `tag` selects the capability to query. `out` must point to
    /// memory of the appropriate type for the requested tag:
    /// - [`GhosttyBuildInfo::Simd`] / [`GhosttyBuildInfo::KittyGraphics`]
    ///   / [`GhosttyBuildInfo::TmuxControlMode`]: `*mut bool`
    /// - [`GhosttyBuildInfo::Optimize`]: `*mut GhosttyOptimizeMode`
    pub fn ghostty_build_info(tag: GhosttyBuildInfo, out: *mut c_void) -> GhosttyResult;
}
