// Copyright (c) 2026 @Natfii. All rights reserved.

//! Pure render-frame conversion helpers for the TTY subsystem.
//!
//! These functions translate the internal `TerminalRenderSnapshot`
//! produced by the terminal backend into the UniFFI-facing
//! `TtyRenderFrame` consumed by the Android canvas renderer. They are
//! free of session state and synchronization primitives.

/// Converts an internal [`TerminalRenderSnapshot`] into the UniFFI-facing
/// [`TtyRenderFrame`] with packed `i64` styles and [`TtyDirtyState`].
///
/// Colors are packed as opaque ARGB via [`pack_argb`].
pub(crate) fn snapshot_to_frame(
    snapshot: super::backend::TerminalRenderSnapshot,
) -> super::types::TtyRenderFrame {
    use super::backend::{CursorStyle, DirtyState};
    use super::types::{
        TtyCursorState, TtyCursorStyle, TtyDirtyState, TtyRenderFrame, TtyRenderRow,
    };

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
    if flags.bold {
        bits |= 1 << 0;
    }
    if flags.italic {
        bits |= 1 << 1;
    }
    if flags.has_underline() {
        bits |= 1 << 2;
    }
    if flags.strikethrough {
        bits |= 1 << 3;
    }
    if flags.dim {
        bits |= 1 << 4;
    }
    if flags.inverse {
        bits |= 1 << 5;
    }
    if flags.invisible {
        bits |= 1 << 6;
    }
    if flags.blink {
        bits |= 1 << 7;
    }

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
    if flags.overline {
        bits |= 1 << 59;
    }

    // Bit 60: has_explicit_fg (distinguishes None from Some(0,0,0))
    if fg.is_some() {
        bits |= 1 << 60;
    }

    // Bit 61: has_explicit_bg (distinguishes None from Some(0,0,0))
    if bg.is_some() {
        bits |= 1 << 61;
    }

    bits as i64
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::super::backend::{CellStyleFlags, RenderCell, RenderColor, RenderRow};
    use super::*;

    // ── pack_cell_style tests ────────────────────────────────────────

    #[test]
    fn pack_default_style_is_zero() {
        let style = pack_cell_style(None, None, &CellStyleFlags::default());
        assert_eq!(style, 0i64);
    }

    #[test]
    fn pack_fg_only() {
        let fg = Some(RenderColor {
            r: 0xFF,
            g: 0x80,
            b: 0x40,
        });
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
        let bg = Some(RenderColor {
            r: 0x10,
            g: 0x20,
            b: 0x30,
        });
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
                assert_ne!(
                    bits & (1 << 2),
                    0,
                    "has_underline should be set for style={style_val}"
                );
            } else {
                assert_eq!(
                    bits & (1 << 2),
                    0,
                    "has_underline should be clear for style=0"
                );
            }
        }
    }

    #[test]
    fn pack_full_style_roundtrip() {
        let fg = Some(RenderColor {
            r: 0xAA,
            g: 0xBB,
            b: 0xCC,
        });
        let bg = Some(RenderColor {
            r: 0x11,
            g: 0x22,
            b: 0x33,
        });
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
        let fg = Some(RenderColor {
            r: 0xFF,
            g: 0x80,
            b: 0x40,
        });
        let style = pack_cell_style(fg, None, &CellStyleFlags::default());
        let bits = style as u64;
        assert_ne!(
            bits & (1 << 60),
            0,
            "has_explicit_fg should be set for non-black too"
        );
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
