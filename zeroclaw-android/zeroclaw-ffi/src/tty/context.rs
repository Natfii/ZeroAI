// Copyright (c) 2026 @Natfii. All rights reserved.

//! Terminal context extraction for LLM consumption.
//!
//! Provides [`LineRingBuffer`], a circular buffer that stores the last N lines
//! of terminal output with ANSI escape sequence stripping and credential
//! scrubbing. The buffer is designed to produce clean, safe text that can be
//! fed to an LLM as terminal context.
//!
//! # Components
//!
//! - [`LineRingBuffer`] — circular buffer with ANSI stripping on ingest
//! - [`AnsiStripState`] — stateful ANSI escape sequence stripper that handles
//!   sequences split across multiple `push_bytes` calls
//! - [`scrub_lines`] — credential scrubbing for sensitive patterns (PEM keys,
//!   env vars, bearer tokens, hex strings)

use std::collections::VecDeque;

/// Default number of lines retained in the ring buffer.
const DEFAULT_CAPACITY: usize = 500;

/// Redaction placeholder for scrubbed credentials.
const REDACTED: &str = "[REDACTED]";

// ── ANSI stripping ──────────────────────────────────────────────────

/// States for the ANSI escape sequence stripping state machine.
///
/// Handles sequences that may be split across multiple `push_bytes`
/// calls by persisting state between invocations.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum AnsiStripState {
    /// Normal text pass-through.
    Normal,
    /// Saw `\x1b`, waiting for the next byte to determine sequence type.
    Escape,
    /// Inside a CSI sequence (`\x1b[`), consuming until a final byte
    /// in the range 0x40..=0x7E.
    Csi,
    /// Inside an OSC sequence (`\x1b]`), consuming until BEL (`\x07`)
    /// or ST (`\x1b\\`).
    Osc,
    /// Inside an OSC sequence and just saw `\x1b`, which might be the
    /// start of the ST terminator (`\x1b\\`).
    OscEscape,
    /// Saw an escape intermediate byte (`(`, `)`, `*`, `+`, `#`, `%`).
    /// Consumes exactly one more byte to complete the sequence.
    EscapeParam,
}

impl AnsiStripState {
    /// Processes a single character through the state machine.
    ///
    /// Returns `Some(ch)` if the character should be emitted to output,
    /// or `None` if it is part of an escape sequence being stripped.
    fn feed(&mut self, ch: char) -> Option<char> {
        match self {
            Self::Normal => {
                if ch == '\x1b' {
                    *self = Self::Escape;
                    None
                } else if ch == '\n' || ch == '\r' || ch == '\t' {
                    // Pass through these C0 control characters.
                    Some(ch)
                } else if (ch as u32) < 0x20 {
                    // Strip other C0 control characters.
                    None
                } else {
                    Some(ch)
                }
            }
            Self::Escape => {
                match ch {
                    '[' => {
                        *self = Self::Csi;
                        None
                    }
                    ']' => {
                        *self = Self::Osc;
                        None
                    }
                    // Simple two-byte escapes that complete immediately:
                    // \x1b= (DECKPAM), \x1b> (DECKPNM), \x1bN (SS2),
                    // \x1bO (SS3), \x1bc (RIS), \x1b7/8 (save/restore),
                    // \x1bD (IND), \x1bE (NEL), \x1bH (HTS), \x1bM (RI),
                    // \x1bZ (DECID).
                    '=' | '>' | 'N' | 'O' | 'c' | '7' | '8'
                    | 'D' | 'E' | 'H' | 'M' | 'Z' => {
                        *self = Self::Normal;
                        None
                    }
                    // Three-byte sequences: intermediate byte + one param.
                    // \x1b(B (charset), \x1b)0, \x1b#8, \x1b%G, etc.
                    '(' | ')' | '*' | '+' | '#' | '%' => {
                        *self = Self::EscapeParam;
                        None
                    }
                    _ => {
                        // Unknown escape — return to normal, don't emit.
                        *self = Self::Normal;
                        None
                    }
                }
            }
            Self::Csi => {
                // CSI sequences end with a byte in 0x40..=0x7E.
                // Intermediate bytes are 0x20..=0x3F (params and intermediate).
                if (0x40..=0x7E).contains(&(ch as u32)) {
                    *self = Self::Normal;
                }
                None
            }
            Self::Osc => {
                // OSC terminates on BEL (0x07) or ST (\x1b\\).
                if ch == '\x07' {
                    *self = Self::Normal;
                    None
                } else if ch == '\x1b' {
                    *self = Self::OscEscape;
                    None
                } else {
                    None
                }
            }
            Self::OscEscape => {
                // We saw \x1b inside an OSC. If next is '\\', that's ST.
                // Otherwise, treat it as still inside the OSC.
                if ch == '\\' {
                    *self = Self::Normal;
                } else {
                    *self = Self::Osc;
                }
                None
            }
            Self::EscapeParam => {
                // Consumes exactly one byte after the intermediate byte
                // (e.g., 'B' in \x1b(B). Return to normal.
                *self = Self::Normal;
                None
            }
        }
    }
}

// ── Line ring buffer ────────────────────────────────────────────────

/// A circular buffer that stores the last N lines of terminal output
/// with ANSI escape sequence stripping.
///
/// Bytes are pushed in via [`push_bytes`](Self::push_bytes) and decoded
/// as lossy UTF-8. ANSI escape sequences are stripped during ingest.
/// Partial lines (data not yet terminated by a newline) accumulate in
/// an internal buffer until the next newline arrives.
pub(crate) struct LineRingBuffer {
    /// Completed lines stored in insertion order (oldest first).
    lines: VecDeque<String>,
    /// Maximum number of lines to retain.
    capacity: usize,
    /// Bytes accumulated for the current incomplete line.
    partial_line: String,
    /// Stateful ANSI escape sequence stripper.
    ansi_state: AnsiStripState,
}

impl LineRingBuffer {
    /// Creates a new ring buffer with the given line capacity.
    pub(crate) fn new(capacity: usize) -> Self {
        Self {
            lines: VecDeque::with_capacity(capacity.min(1024)),
            capacity,
            partial_line: String::new(),
            ansi_state: AnsiStripState::Normal,
        }
    }

    /// Creates a new ring buffer with the default capacity (500 lines).
    #[cfg(test)]
    pub(crate) fn default_capacity() -> Self {
        Self::new(DEFAULT_CAPACITY)
    }

    /// Pushes raw bytes from the terminal into the buffer.
    ///
    /// Performs lossy UTF-8 decoding, strips ANSI escape sequences,
    /// splits on newlines, and appends complete lines to the ring.
    /// Partial lines (data after the last newline) accumulate until
    /// the next call delivers a newline.
    ///
    /// Carriage returns (`\r`) are stripped to normalize line endings.
    pub(crate) fn push_bytes(&mut self, data: &[u8]) {
        let text = String::from_utf8_lossy(data);

        for ch in text.chars() {
            if let Some(out) = self.ansi_state.feed(ch) {
                if out == '\n' {
                    // Complete the current line and push it.
                    let line = std::mem::take(&mut self.partial_line);
                    self.push_line(line);
                } else if out == '\r' {
                    // Strip carriage returns (normalize \r\n to \n).
                } else {
                    self.partial_line.push(out);
                }
            }
        }
    }

    /// Returns the last `max_lines` completed lines, oldest first.
    ///
    /// If fewer than `max_lines` are available, all lines are returned.
    pub(crate) fn get_lines(&self, max_lines: usize) -> Vec<String> {
        let start = self.lines.len().saturating_sub(max_lines);
        self.lines.iter().skip(start).cloned().collect()
    }

    /// Returns scrubbed lines as a single newline-joined string, capped
    /// at `max_bytes`. Oldest lines are truncated first to fit the budget.
    ///
    /// The output is suitable for feeding to an LLM as terminal context.
    pub(crate) fn export_context(&self, max_bytes: usize) -> String {
        let lines_vec: Vec<String> = self.lines.iter().cloned().collect();
        let scrubbed = scrub_lines(&lines_vec);

        // Walk backwards from most recent, accumulating lines until the
        // byte budget is exhausted.
        let mut selected: Vec<&str> = Vec::new();
        let mut total_bytes: usize = 0;

        for line in scrubbed.iter().rev() {
            let needed = if selected.is_empty() {
                line.len()
            } else {
                line.len() + 1 // +1 for the \n separator
            };

            if total_bytes + needed > max_bytes {
                break;
            }

            total_bytes += needed;
            selected.push(line);
        }

        // Reverse to restore chronological order.
        selected.reverse();
        selected.join("\n")
    }

    /// Clears all lines and resets the partial line accumulator and
    /// ANSI state machine.
    pub(crate) fn clear(&mut self) {
        self.lines.clear();
        self.partial_line.clear();
        self.ansi_state = AnsiStripState::Normal;
    }

    /// Returns the number of completed lines currently in the buffer.
    #[cfg(test)]
    pub(crate) fn len(&self) -> usize {
        self.lines.len()
    }

    /// Returns a reference to the partial (incomplete) line being
    /// accumulated.
    #[cfg(test)]
    pub(crate) fn partial(&self) -> &str {
        &self.partial_line
    }

    /// Pushes a completed line into the ring, evicting the oldest line
    /// if the buffer is at capacity.
    fn push_line(&mut self, line: String) {
        if self.lines.len() >= self.capacity {
            self.lines.pop_front();
        }
        self.lines.push_back(line);
    }
}

// ── Credential scrubbing ────────────────────────────────────────────

/// Sensitive environment variable names that trigger line redaction.
///
/// Matched case-insensitively when followed by `=` or `:` and a value.
const SENSITIVE_KEYS: &[&str] = &[
    "AWS_SECRET_ACCESS_KEY",
    "AWS_SESSION_TOKEN",
    "PRIVATE_KEY",
    "SECRET_KEY",
    "API_KEY",
    "TOKEN",
    "PASSWORD",
    "BEARER",
];

/// Redacts lines containing sensitive credential patterns.
///
/// Patterns detected:
/// 1. PEM private key blocks (multi-line, from `-----BEGIN` to `-----END`)
/// 2. Lines with sensitive env var names followed by `=` or `:` and a value
/// 3. Lines starting with `Bearer ` followed by a long token (20+ chars)
/// 4. Standalone hex strings longer than 40 characters
///
/// Over-redaction is preferred over leaking credentials.
pub(crate) fn scrub_lines(lines: &[String]) -> Vec<String> {
    let mut result = Vec::with_capacity(lines.len());
    let mut in_pem_block = false;

    for line in lines {
        // Pattern 1: PEM block detection.
        if is_pem_begin(line) {
            in_pem_block = true;
            result.push(REDACTED.to_owned());
            continue;
        }

        if in_pem_block {
            if is_pem_end(line) {
                in_pem_block = false;
            }
            result.push(REDACTED.to_owned());
            continue;
        }

        // Pattern 2: Sensitive key=value or key: value patterns.
        if has_sensitive_key_value(line) {
            result.push(REDACTED.to_owned());
            continue;
        }

        // Pattern 3: Bearer token.
        if has_bearer_token(line) {
            result.push(REDACTED.to_owned());
            continue;
        }

        // Pattern 4: Long standalone hex string.
        if is_long_hex_string(line) {
            result.push(REDACTED.to_owned());
            continue;
        }

        result.push(line.clone());
    }

    result
}

/// Checks if a line contains `-----BEGIN ... PRIVATE KEY-----`.
fn is_pem_begin(line: &str) -> bool {
    let trimmed = line.trim();
    trimmed.contains("-----BEGIN") && trimmed.contains("PRIVATE KEY-----")
}

/// Checks if a line contains `-----END ... PRIVATE KEY-----`.
fn is_pem_end(line: &str) -> bool {
    let trimmed = line.trim();
    trimmed.contains("-----END") && trimmed.contains("PRIVATE KEY-----")
}

/// Checks if a line contains a sensitive key followed by `=` or `:`
/// and a non-empty value.
fn has_sensitive_key_value(line: &str) -> bool {
    let upper = line.to_uppercase();
    for key in SENSITIVE_KEYS {
        if let Some(pos) = upper.find(key) {
            // Look for = or : after the key, possibly with whitespace.
            let after_key = &line[pos + key.len()..];
            let after_key_trimmed = after_key.trim_start();
            if after_key_trimmed.starts_with('=') || after_key_trimmed.starts_with(':') {
                // Check there's a non-whitespace value after the separator.
                let after_sep = after_key_trimmed[1..].trim_start();
                if !after_sep.is_empty() {
                    return true;
                }
            }
        }
    }
    false
}

/// Checks if a line contains `Bearer ` followed by a long token
/// (20+ non-whitespace characters).
fn has_bearer_token(line: &str) -> bool {
    // Case-insensitive search for "bearer " prefix.
    let lower = line.to_lowercase();
    let mut search_from = 0;

    while let Some(pos) = lower[search_from..].find("bearer ") {
        let abs_pos = search_from + pos;
        let after = line[abs_pos + 7..].trim_start();
        // Count non-whitespace characters.
        let token_len = after.chars().take_while(|c| !c.is_whitespace()).count();
        if token_len >= 20 {
            return true;
        }
        search_from = abs_pos + 7;
    }

    false
}

/// Checks if a line is predominantly a long hex string (40+ hex chars).
///
/// The line (trimmed) must consist of at least 40 hex digits, optionally
/// with common separators like colons or spaces. We check that at least
/// 80% of non-separator characters are hex digits.
fn is_long_hex_string(line: &str) -> bool {
    let trimmed = line.trim();
    if trimmed.len() < 40 {
        return false;
    }

    let mut hex_count: usize = 0;
    let mut non_sep_count: usize = 0;

    for ch in trimmed.chars() {
        if ch.is_ascii_hexdigit() {
            hex_count += 1;
            non_sep_count += 1;
        } else if ch == ':' || ch == ' ' || ch == '-' {
            // Common separators in key representations; skip.
        } else {
            non_sep_count += 1;
        }
    }

    // Must have 40+ hex digits and they must be the dominant content.
    hex_count >= 40 && non_sep_count > 0 && hex_count * 100 / non_sep_count >= 80
}

// ── Tests ───────────────────────────────────────────────────────────

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    // ── AnsiStripState tests ────────────────────────────────────────

    /// Feeds a string through the ANSI stripper and collects output.
    fn strip_ansi(input: &str) -> String {
        let mut state = AnsiStripState::Normal;
        input.chars().filter_map(|ch| state.feed(ch)).collect()
    }

    /// Feeds bytes through the ANSI stripper using a shared state,
    /// allowing tests to simulate split sequences.
    fn strip_ansi_stateful(state: &mut AnsiStripState, input: &str) -> String {
        input.chars().filter_map(|ch| state.feed(ch)).collect()
    }

    #[test]
    fn ansi_strip_plain_text_passes_through() {
        assert_eq!(strip_ansi("hello world"), "hello world");
    }

    #[test]
    fn ansi_strip_sgr_color_codes() {
        // Bold red text then reset.
        assert_eq!(strip_ansi("\x1b[1;31mERROR\x1b[0m"), "ERROR");
    }

    #[test]
    fn ansi_strip_sgr_256_color() {
        assert_eq!(strip_ansi("\x1b[38;5;196mred\x1b[0m"), "red");
    }

    #[test]
    fn ansi_strip_sgr_truecolor() {
        assert_eq!(
            strip_ansi("\x1b[38;2;255;0;0mred\x1b[0m"),
            "red"
        );
    }

    #[test]
    fn ansi_strip_cursor_movement() {
        // Cursor home + clear screen + text.
        assert_eq!(strip_ansi("\x1b[H\x1b[2Jhello"), "hello");
    }

    #[test]
    fn ansi_strip_osc_title_with_bel() {
        // Set terminal title (OSC 0).
        assert_eq!(
            strip_ansi("\x1b]0;my title\x07prompt$"),
            "prompt$"
        );
    }

    #[test]
    fn ansi_strip_osc_title_with_st() {
        // Set terminal title terminated by ST (\x1b\\).
        assert_eq!(
            strip_ansi("\x1b]0;my title\x1b\\prompt$"),
            "prompt$"
        );
    }

    #[test]
    fn ansi_strip_simple_escapes() {
        // Save/restore cursor.
        assert_eq!(strip_ansi("\x1b7hello\x1b8"), "hello");
    }

    #[test]
    fn ansi_strip_c0_control_chars() {
        // BEL, BS, and other C0 chars should be stripped.
        assert_eq!(strip_ansi("a\x07b\x08c\x01d"), "abcd");
    }

    #[test]
    fn ansi_strip_preserves_tab() {
        assert_eq!(strip_ansi("a\tb"), "a\tb");
    }

    #[test]
    fn ansi_strip_preserves_newline() {
        assert_eq!(strip_ansi("a\nb"), "a\nb");
    }

    #[test]
    fn ansi_strip_preserves_carriage_return() {
        // \r is preserved by the stripper (LineRingBuffer handles it).
        assert_eq!(strip_ansi("a\rb"), "a\rb");
    }

    #[test]
    fn ansi_strip_split_csi_across_calls() {
        let mut state = AnsiStripState::Normal;
        // Split "\x1b[31mhello" across two calls.
        let out1 = strip_ansi_stateful(&mut state, "before\x1b");
        assert_eq!(out1, "before");
        assert_eq!(state, AnsiStripState::Escape);

        let out2 = strip_ansi_stateful(&mut state, "[31m");
        assert_eq!(out2, "");
        assert_eq!(state, AnsiStripState::Normal);

        let out3 = strip_ansi_stateful(&mut state, "after");
        assert_eq!(out3, "after");
    }

    #[test]
    fn ansi_strip_split_osc_across_calls() {
        let mut state = AnsiStripState::Normal;

        // "\x1b]0;ti" then "tle\x07text"
        let out1 = strip_ansi_stateful(&mut state, "\x1b]0;ti");
        assert_eq!(out1, "");
        assert_eq!(state, AnsiStripState::Osc);

        let out2 = strip_ansi_stateful(&mut state, "tle\x07text");
        assert_eq!(out2, "text");
        assert_eq!(state, AnsiStripState::Normal);
    }

    #[test]
    fn ansi_strip_split_osc_st_across_calls() {
        let mut state = AnsiStripState::Normal;

        // OSC with ST split: "\x1b]0;title\x1b" then "\\"
        let out1 = strip_ansi_stateful(&mut state, "\x1b]0;title\x1b");
        assert_eq!(out1, "");
        assert_eq!(state, AnsiStripState::OscEscape);

        let out2 = strip_ansi_stateful(&mut state, "\\done");
        assert_eq!(out2, "done");
        assert_eq!(state, AnsiStripState::Normal);
    }

    #[test]
    fn ansi_strip_csi_with_intermediate_bytes() {
        // CSI with intermediate byte (e.g., \x1b[?25h — show cursor).
        assert_eq!(strip_ansi("\x1b[?25hvisible"), "visible");
    }

    #[test]
    fn ansi_strip_charset_designation() {
        // \x1b(B — designate US ASCII to G0.
        assert_eq!(strip_ansi("\x1b(Btext"), "text");
    }

    // ── LineRingBuffer tests ────────────────────────────────────────

    #[test]
    fn ring_buffer_basic_push_and_get() {
        let mut buf = LineRingBuffer::new(10);
        buf.push_bytes(b"line one\nline two\n");

        let lines = buf.get_lines(10);
        assert_eq!(lines, vec!["line one", "line two"]);
    }

    #[test]
    fn ring_buffer_partial_line_accumulation() {
        let mut buf = LineRingBuffer::new(10);
        buf.push_bytes(b"hello ");
        assert_eq!(buf.len(), 0);
        assert_eq!(buf.partial(), "hello ");

        buf.push_bytes(b"world\n");
        assert_eq!(buf.len(), 1);
        assert_eq!(buf.partial(), "");
        assert_eq!(buf.get_lines(10), vec!["hello world"]);
    }

    #[test]
    fn ring_buffer_partial_line_across_multiple_pushes() {
        let mut buf = LineRingBuffer::new(10);
        buf.push_bytes(b"one ");
        buf.push_bytes(b"two ");
        buf.push_bytes(b"three\n");

        assert_eq!(buf.get_lines(10), vec!["one two three"]);
    }

    #[test]
    fn ring_buffer_multiple_lines_in_one_push() {
        let mut buf = LineRingBuffer::new(10);
        buf.push_bytes(b"a\nb\nc\nd\n");

        assert_eq!(buf.get_lines(10), vec!["a", "b", "c", "d"]);
    }

    #[test]
    fn ring_buffer_strips_ansi_on_ingest() {
        let mut buf = LineRingBuffer::new(10);
        buf.push_bytes(b"\x1b[1;31mERROR\x1b[0m: something failed\n");

        assert_eq!(buf.get_lines(10), vec!["ERROR: something failed"]);
    }

    #[test]
    fn ring_buffer_strips_ansi_split_across_pushes() {
        let mut buf = LineRingBuffer::new(10);
        // Split an SGR sequence across two push_bytes calls.
        buf.push_bytes(b"before\x1b");
        buf.push_bytes(b"[31mred\x1b[0m\n");

        assert_eq!(buf.get_lines(10), vec!["beforered"]);
    }

    #[test]
    fn ring_buffer_normalizes_crlf() {
        let mut buf = LineRingBuffer::new(10);
        buf.push_bytes(b"line one\r\nline two\r\n");

        assert_eq!(buf.get_lines(10), vec!["line one", "line two"]);
    }

    #[test]
    fn ring_buffer_evicts_oldest_at_capacity() {
        let mut buf = LineRingBuffer::new(3);
        buf.push_bytes(b"a\nb\nc\nd\ne\n");

        let lines = buf.get_lines(10);
        assert_eq!(lines, vec!["c", "d", "e"]);
    }

    #[test]
    fn ring_buffer_get_lines_respects_max() {
        let mut buf = LineRingBuffer::new(10);
        buf.push_bytes(b"a\nb\nc\nd\ne\n");

        assert_eq!(buf.get_lines(2), vec!["d", "e"]);
        assert_eq!(buf.get_lines(1), vec!["e"]);
    }

    #[test]
    fn ring_buffer_get_lines_returns_all_when_fewer_than_max() {
        let mut buf = LineRingBuffer::new(10);
        buf.push_bytes(b"only\n");

        assert_eq!(buf.get_lines(100), vec!["only"]);
    }

    #[test]
    fn ring_buffer_clear_resets_everything() {
        let mut buf = LineRingBuffer::new(10);
        buf.push_bytes(b"line\npartial");
        assert_eq!(buf.len(), 1);
        assert_eq!(buf.partial(), "partial");

        buf.clear();
        assert_eq!(buf.len(), 0);
        assert_eq!(buf.partial(), "");
        assert!(buf.get_lines(10).is_empty());
    }

    #[test]
    fn ring_buffer_clear_resets_ansi_state() {
        let mut buf = LineRingBuffer::new(10);
        // Push a partial escape sequence.
        buf.push_bytes(b"text\x1b");
        buf.clear();
        // After clear, text should not be swallowed by stale state.
        buf.push_bytes(b"clean\n");
        assert_eq!(buf.get_lines(10), vec!["clean"]);
    }

    #[test]
    fn ring_buffer_empty_lines_preserved() {
        let mut buf = LineRingBuffer::new(10);
        buf.push_bytes(b"a\n\nb\n");

        assert_eq!(buf.get_lines(10), vec!["a", "", "b"]);
    }

    #[test]
    fn ring_buffer_lossy_utf8() {
        let mut buf = LineRingBuffer::new(10);
        // Invalid UTF-8 sequence should be replaced with U+FFFD.
        buf.push_bytes(&[0x48, 0x65, 0x6C, 0x6C, 0x6F, 0xFF, 0x0A]);

        let lines = buf.get_lines(10);
        assert_eq!(lines.len(), 1);
        assert!(lines[0].starts_with("Hello"));
        assert!(lines[0].contains('\u{FFFD}'));
    }

    #[test]
    fn ring_buffer_capacity_one() {
        let mut buf = LineRingBuffer::new(1);
        buf.push_bytes(b"first\nsecond\nthird\n");

        assert_eq!(buf.get_lines(10), vec!["third"]);
    }

    #[test]
    fn ring_buffer_default_capacity_constructor() {
        let buf = LineRingBuffer::default_capacity();
        assert_eq!(buf.capacity, DEFAULT_CAPACITY);
    }

    // ── export_context tests ────────────────────────────────────────

    #[test]
    fn export_context_basic() {
        let mut buf = LineRingBuffer::new(10);
        buf.push_bytes(b"line one\nline two\nline three\n");

        let ctx = buf.export_context(1000);
        assert_eq!(ctx, "line one\nline two\nline three");
    }

    #[test]
    fn export_context_byte_budget_truncates_oldest() {
        let mut buf = LineRingBuffer::new(10);
        buf.push_bytes(b"aaaa\nbbbb\ncccc\n");

        // "cccc" = 4 bytes, fits.
        // "bbbb\ncccc" = 9 bytes, fits.
        // "aaaa\nbbbb\ncccc" = 14 bytes, needs budget >= 14.
        let ctx = buf.export_context(9);
        assert_eq!(ctx, "bbbb\ncccc");
    }

    #[test]
    fn export_context_budget_too_small_for_any_line() {
        let mut buf = LineRingBuffer::new(10);
        buf.push_bytes(b"toolongline\n");

        let ctx = buf.export_context(3);
        assert_eq!(ctx, "");
    }

    #[test]
    fn export_context_exact_budget() {
        let mut buf = LineRingBuffer::new(10);
        buf.push_bytes(b"ab\ncd\n");

        // "cd" = 2, "ab\ncd" = 5.
        let ctx = buf.export_context(5);
        assert_eq!(ctx, "ab\ncd");
    }

    #[test]
    fn export_context_empty_buffer() {
        let buf = LineRingBuffer::new(10);
        assert_eq!(buf.export_context(1000), "");
    }

    #[test]
    fn export_context_scrubs_credentials() {
        let mut buf = LineRingBuffer::new(10);
        buf.push_bytes(b"safe line\nAPI_KEY=sk-1234567890\nanother safe\n");

        let ctx = buf.export_context(1000);
        assert!(ctx.contains("safe line"));
        assert!(ctx.contains(REDACTED));
        assert!(!ctx.contains("sk-1234567890"));
        assert!(ctx.contains("another safe"));
    }

    // ── Credential scrubbing tests ──────────────────────────────────

    #[test]
    fn scrub_pem_block() {
        let lines: Vec<String> = vec![
            "before".into(),
            "-----BEGIN RSA PRIVATE KEY-----".into(),
            "MIIEpAIBAAKCAQEA...".into(),
            "-----END RSA PRIVATE KEY-----".into(),
            "after".into(),
        ];

        let scrubbed = scrub_lines(&lines);
        assert_eq!(scrubbed[0], "before");
        assert_eq!(scrubbed[1], REDACTED);
        assert_eq!(scrubbed[2], REDACTED);
        assert_eq!(scrubbed[3], REDACTED);
        assert_eq!(scrubbed[4], "after");
    }

    #[test]
    fn scrub_pem_ec_key() {
        let lines: Vec<String> = vec![
            "-----BEGIN EC PRIVATE KEY-----".into(),
            "base64data".into(),
            "-----END EC PRIVATE KEY-----".into(),
        ];

        let scrubbed = scrub_lines(&lines);
        assert!(scrubbed.iter().all(|l| l == REDACTED));
    }

    #[test]
    fn scrub_env_var_with_equals() {
        let lines = vec!["export AWS_SECRET_ACCESS_KEY=wJalrXUtnFEMI".into()];
        let scrubbed = scrub_lines(&lines);
        assert_eq!(scrubbed[0], REDACTED);
    }

    #[test]
    fn scrub_env_var_with_colon() {
        let lines = vec!["SECRET_KEY: my-super-secret-value".into()];
        let scrubbed = scrub_lines(&lines);
        assert_eq!(scrubbed[0], REDACTED);
    }

    #[test]
    fn scrub_env_var_case_insensitive() {
        let lines = vec!["api_key = sk_live_abc123".into()];
        let scrubbed = scrub_lines(&lines);
        assert_eq!(scrubbed[0], REDACTED);
    }

    #[test]
    fn scrub_password_field() {
        let lines = vec!["password=hunter2".into()];
        let scrubbed = scrub_lines(&lines);
        assert_eq!(scrubbed[0], REDACTED);
    }

    #[test]
    fn scrub_token_field() {
        let lines = vec!["TOKEN=ghp_xxxxxxxxxxxxxxxxxxxx".into()];
        let scrubbed = scrub_lines(&lines);
        assert_eq!(scrubbed[0], REDACTED);
    }

    #[test]
    fn scrub_does_not_redact_key_without_value() {
        // "API_KEY=" with nothing after should not trigger.
        let lines = vec!["API_KEY= ".into()];
        let scrubbed = scrub_lines(&lines);
        // Trimmed value is empty, so should not redact.
        assert_eq!(scrubbed[0], "API_KEY= ");
    }

    #[test]
    fn scrub_does_not_redact_normal_text() {
        let lines = vec![
            "hello world".into(),
            "compiling crate v1.0.0".into(),
            "error[E0308]: mismatched types".into(),
        ];
        let scrubbed = scrub_lines(&lines);
        assert_eq!(scrubbed, lines);
    }

    #[test]
    fn scrub_bearer_token_long() {
        let lines = vec![
            "Authorization: Bearer eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.long_token_here".into(),
        ];
        let scrubbed = scrub_lines(&lines);
        assert_eq!(scrubbed[0], REDACTED);
    }

    #[test]
    fn scrub_bearer_token_short_not_redacted() {
        // Short bearer token (< 20 chars) — might be a false hit; we allow it.
        let lines = vec!["Bearer short".into()];
        let scrubbed = scrub_lines(&lines);
        assert_eq!(scrubbed[0], "Bearer short");
    }

    #[test]
    fn scrub_bearer_case_insensitive() {
        let lines =
            vec!["bearer abcdefghijklmnopqrstuvwxyz1234567890".into()];
        let scrubbed = scrub_lines(&lines);
        assert_eq!(scrubbed[0], REDACTED);
    }

    #[test]
    fn scrub_long_hex_string() {
        // 64-char hex string (looks like a SHA-256 hash or key).
        let hex = "a".repeat(64);
        let lines = vec![hex.clone()];
        let scrubbed = scrub_lines(&lines);
        assert_eq!(scrubbed[0], REDACTED);
    }

    #[test]
    fn scrub_hex_with_colons() {
        // SSH fingerprint-like hex with colons.
        let hex = "aa:bb:cc:dd:ee:ff:00:11:22:33:44:55:66:77:88:99:aa:bb:cc:dd:ee:ff";
        let lines = vec![hex.into()];
        let scrubbed = scrub_lines(&lines);
        assert_eq!(scrubbed[0], REDACTED);
    }

    #[test]
    fn scrub_short_hex_not_redacted() {
        // 32-char hex string — below 40 threshold.
        let hex = "a".repeat(32);
        let lines = vec![hex.clone()];
        let scrubbed = scrub_lines(&lines);
        assert_eq!(scrubbed[0], hex);
    }

    #[test]
    fn scrub_hex_mixed_with_non_hex_not_redacted() {
        // Mostly non-hex content.
        let lines = vec!["this is a normal line with some hex abc123 in it".into()];
        let scrubbed = scrub_lines(&lines);
        assert_eq!(scrubbed[0], "this is a normal line with some hex abc123 in it");
    }

    #[test]
    fn scrub_pem_block_not_closed_redacts_remaining() {
        // PEM begin without end — everything after should be redacted
        // until end of input (defensive).
        let lines: Vec<String> = vec![
            "safe".into(),
            "-----BEGIN RSA PRIVATE KEY-----".into(),
            "key data line 1".into(),
            "key data line 2".into(),
        ];

        let scrubbed = scrub_lines(&lines);
        assert_eq!(scrubbed[0], "safe");
        assert_eq!(scrubbed[1], REDACTED);
        assert_eq!(scrubbed[2], REDACTED);
        assert_eq!(scrubbed[3], REDACTED);
    }

    #[test]
    fn scrub_multiple_patterns_in_sequence() {
        let lines: Vec<String> = vec![
            "normal output".into(),
            "API_KEY=sk-proj-abc123".into(),
            "more output".into(),
            "-----BEGIN RSA PRIVATE KEY-----".into(),
            "MIIEpAIBAAKCAQ==".into(),
            "-----END RSA PRIVATE KEY-----".into(),
            "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9".into(),
            "final output".into(),
        ];

        let scrubbed = scrub_lines(&lines);
        assert_eq!(scrubbed[0], "normal output");
        assert_eq!(scrubbed[1], REDACTED); // API_KEY
        assert_eq!(scrubbed[2], "more output");
        assert_eq!(scrubbed[3], REDACTED); // PEM begin
        assert_eq!(scrubbed[4], REDACTED); // PEM body
        assert_eq!(scrubbed[5], REDACTED); // PEM end
        assert_eq!(scrubbed[6], REDACTED); // Bearer
        assert_eq!(scrubbed[7], "final output");
    }

    #[test]
    fn scrub_preserves_line_count() {
        let lines: Vec<String> = (0..10).map(|i| format!("line {i}")).collect();
        let scrubbed = scrub_lines(&lines);
        assert_eq!(scrubbed.len(), lines.len());
    }

    #[test]
    fn scrub_empty_input() {
        let scrubbed = scrub_lines(&[]);
        assert!(scrubbed.is_empty());
    }

    // ── Integration tests ───────────────────────────────────────────

    #[test]
    fn ring_buffer_full_pipeline_ansi_plus_scrub() {
        let mut buf = LineRingBuffer::new(100);

        // Push colored output with a secret.
        buf.push_bytes(b"\x1b[32m$\x1b[0m export API_KEY=sk-secret-key-value\n");
        buf.push_bytes(b"\x1b[32m$\x1b[0m echo hello\n");
        buf.push_bytes(b"hello\n");

        let ctx = buf.export_context(1000);
        assert!(!ctx.contains("sk-secret-key-value"));
        assert!(ctx.contains(REDACTED));
        assert!(ctx.contains("$ echo hello"));
        assert!(ctx.contains("hello"));
    }

    #[test]
    fn ring_buffer_large_volume() {
        let mut buf = LineRingBuffer::new(DEFAULT_CAPACITY);

        // Push 1000 lines.
        for i in 0..1000 {
            buf.push_bytes(format!("line {i}\n").as_bytes());
        }

        assert_eq!(buf.len(), DEFAULT_CAPACITY);

        let lines = buf.get_lines(DEFAULT_CAPACITY);
        assert_eq!(lines.len(), DEFAULT_CAPACITY);
        // Oldest retained should be line 500.
        assert_eq!(lines[0], "line 500");
        assert_eq!(lines[DEFAULT_CAPACITY - 1], "line 999");
    }

    #[test]
    fn ring_buffer_osc_title_in_prompt() {
        let mut buf = LineRingBuffer::new(10);
        // Common bash prompt with OSC title set.
        buf.push_bytes(
            b"\x1b]0;user@host:~\x07\x1b[01;32muser@host\x1b[0m:\x1b[01;34m~\x1b[0m$ ls\n",
        );

        let lines = buf.get_lines(10);
        assert_eq!(lines, vec!["user@host:~$ ls"]);
    }

    #[test]
    fn export_context_with_scrubbing_and_budget() {
        let mut buf = LineRingBuffer::new(10);
        buf.push_bytes(b"safe1\nPASSWORD=secret\nsafe2\nsafe3\n");

        // Budget enough for "safe2\nsafe3" (11 bytes) but not all 4 lines.
        let ctx = buf.export_context(22);
        assert!(!ctx.contains("secret"));
        // Should include the scrubbed version.
        assert!(ctx.contains("safe3"));
    }
}
