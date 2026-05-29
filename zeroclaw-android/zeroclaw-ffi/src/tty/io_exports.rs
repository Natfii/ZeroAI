// Copyright (c) 2026 @Natfii- All rights reserved.

//! TTY input/output FFI exports.
//!
//! Special-key encoding, paste safety, bracketed paste detection,
//! bell/title polling, mouse-tracking state, focus events, and mouse
//! event submission. Each export routes ssh/local dispatch through an
//! inner shim, keeping `lib.rs` free of TTY-specific branches.

use crate::error::FfiError;
use crate::tty;

// ── FFI exports ────────────────────────────────────────────────────────────

crate::ffi_export!(
    /// Encodes a special key into terminal escape bytes.
    ///
    /// Returns the encoded escape sequence bytes, or an empty vec for
    /// unrecognised keys. Supported key names: `tab`, `escape`, `enter`,
    /// `backspace`, `delete`, `up`, `down`, `left`, `right`, `home`,
    /// `end`, `page_up`, `page_down`, `f1`-`f12`. Modifier flags:
    /// bit 0 = Ctrl, bit 1 = Alt, bit 2 = Shift.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::InternalPanic`] if native code panics.
    fn tty_encode_special_key(key_name: String, modifier_flags: u32) -> Vec<u8> = tty_encode_special_key_inner
);

crate::ffi_export!(
    /// Returns whether the given text is safe to paste without user
    /// confirmation. Falls back to a conservative pure-Rust check when
    /// the `ghostty-vt` feature is disabled.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::InternalPanic`] if native code panics.
    fn tty_is_paste_safe(text: String) -> bool = tty_is_paste_safe_inner
);

crate::ffi_export!(
    /// Returns whether bracketed paste mode (DEC 2004) is active in the
    /// current terminal session.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::InternalPanic`] if native code panics.
    fn tty_is_bracketed_paste_active() -> bool = tty_is_bracketed_paste_active_inner
);

crate::ffi_export!(
    /// Returns `true` if a terminal bell (BEL, 0x07) has fired since the
    /// last call, atomically clearing the pending flag.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::InternalPanic`] if native code panics.
    fn tty_take_bell_event() -> bool = tty_take_bell_event_inner
);

crate::ffi_export!(
    /// If the terminal title has changed since the last call, reads and
    /// returns the current title string (sanitized; bidi overrides
    /// stripped, length capped at 64 characters).
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::InternalPanic`] if native code panics.
    fn tty_take_title_if_changed() -> Option<String> = tty_take_title_if_changed_inner
);

crate::ffi_export!(
    /// Returns whether mouse tracking is currently active in the
    /// terminal session. Always `false` for SSH backends.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::InternalPanic`] if native code panics.
    fn tty_is_mouse_tracking_active() -> bool = tty_is_mouse_tracking_active_inner
);

crate::ffi_export!(
    /// Sends a focus gained/lost event to the terminal if focus
    /// reporting (DEC 1004) is active. Silent no-op otherwise.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::InternalPanic`] if native code panics.
    fn tty_send_focus_event(gained: bool) -> () = tty_send_focus_event_inner
);

crate::ffi_export!(
    /// Encodes a mouse event and writes the escape sequence to the PTY.
    /// Fire-and-forget; errors are logged but not surfaced to UI.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::InternalPanic`] if native code panics.
    fn tty_submit_mouse_event(
        action: u8,
        button: u8,
        pixel_x: f32,
        pixel_y: f32,
        mods: u32
    ) -> () = tty_submit_mouse_event_inner
);

// ── Inner implementations ──────────────────────────────────────────────────

#[allow(clippy::unnecessary_wraps)]
pub(crate) fn tty_encode_special_key_inner(
    key_name: String,
    modifier_flags: u32,
) -> Result<Vec<u8>, FfiError> {
    Ok(encode_special_key(&key_name, modifier_flags))
}

#[allow(clippy::unnecessary_wraps)]
pub(crate) fn tty_is_paste_safe_inner(text: String) -> Result<bool, FfiError> {
    #[cfg(feature = "ghostty-vt")]
    {
        Ok(tty::ghostty_bridge::is_paste_safe(&text))
    }
    #[cfg(not(feature = "ghostty-vt"))]
    {
        let safe =
            !text.contains('\n') && !text.contains('\r') && !text.contains("\x1b[201~");
        Ok(safe)
    }
}

pub(crate) fn tty_is_bracketed_paste_active_inner() -> Result<bool, FfiError> {
    tty::session::is_bracketed_paste_active()
}

pub(crate) fn tty_take_bell_event_inner() -> Result<bool, FfiError> {
    tty::session::take_bell_event()
}

pub(crate) fn tty_take_title_if_changed_inner() -> Result<Option<String>, FfiError> {
    tty::session::take_title_if_changed()
}

pub(crate) fn tty_is_mouse_tracking_active_inner() -> Result<bool, FfiError> {
    tty::session::is_mouse_tracking_active()
}

pub(crate) fn tty_send_focus_event_inner(gained: bool) -> Result<(), FfiError> {
    if !tty::session::is_focus_reporting_active().unwrap_or(false) {
        return Ok(());
    }
    let encoded = tty::ghostty_bridge::encode_focus_event(gained);
    if !encoded.is_empty() {
        tty::session::write_bytes(encoded)
    } else {
        Ok(())
    }
}

pub(crate) fn tty_submit_mouse_event_inner(
    action: u8,
    button: u8,
    pixel_x: f32,
    pixel_y: f32,
    mods: u32,
) -> Result<(), FfiError> {
    let result = tty::session::submit_mouse_event(action, button, pixel_x, pixel_y, mods);

    match &result {
        Err(FfiError::StateError { .. }) => {
            tracing::debug!(target: "tty", "mouse event ignored: no session");
        }
        Err(FfiError::SpawnError { detail }) => {
            tracing::warn!(target: "tty", "mouse event channel pressure: {detail}");
        }
        _ => {}
    }
    result
}

/// Maps a key name + modifier flags to terminal escape bytes.
///
/// Uses standard xterm/VT escape sequences. Ctrl combos are handled by
/// converting to the corresponding control character.
fn encode_special_key(key_name: &str, modifier_flags: u32) -> Vec<u8> {
    let ctrl = modifier_flags & 0x01 != 0;
    let alt = modifier_flags & 0x02 != 0;

    if ctrl && key_name.len() == 1 {
        let ch = key_name.as_bytes()[0];
        if ch.is_ascii_alphabetic() {
            let ctrl_char = (ch.to_ascii_uppercase() - b'A') + 1;
            return if alt {
                vec![0x1b, ctrl_char]
            } else {
                vec![ctrl_char]
            };
        }
    }

    let base: &[u8] = match key_name {
        "tab" => b"\x09",
        "escape" | "esc" => b"\x1b",
        "enter" | "return" => b"\r",
        "backspace" => b"\x7f",
        "delete" => b"\x1b[3~",
        "up" => b"\x1b[A",
        "down" => b"\x1b[B",
        "right" => b"\x1b[C",
        "left" => b"\x1b[D",
        "home" => b"\x1b[H",
        "end" => b"\x1b[F",
        "page_up" => b"\x1b[5~",
        "page_down" => b"\x1b[6~",
        "insert" => b"\x1b[2~",
        "f1" => b"\x1bOP",
        "f2" => b"\x1bOQ",
        "f3" => b"\x1bOR",
        "f4" => b"\x1bOS",
        "f5" => b"\x1b[15~",
        "f6" => b"\x1b[17~",
        "f7" => b"\x1b[18~",
        "f8" => b"\x1b[19~",
        "f9" => b"\x1b[20~",
        "f10" => b"\x1b[21~",
        "f11" => b"\x1b[23~",
        "f12" => b"\x1b[24~",
        _ => return Vec::new(),
    };

    if alt {
        let mut result = vec![0x1b];
        result.extend_from_slice(base);
        result
    } else {
        base.to_vec()
    }
}
