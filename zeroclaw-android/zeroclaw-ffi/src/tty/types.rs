// Copyright (c) 2026 @Natfii. All rights reserved.

//! UniFFI-exported types for the SSH terminal subsystem.
//!
//! These types cross the FFI boundary and become Kotlin data classes
//! or sealed classes via UniFFI code generation.

// ── Connection state machine ─────────────────────────────────────────

/// High-level SSH connection state, observed by the UI layer.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum SshState {
    /// No active connection.
    Disconnected,
    /// TCP handshake / SSH banner exchange in progress.
    Connecting,
    /// Server offered a host key that needs user approval.
    AwaitingHostKey,
    /// Performing password or public-key authentication.
    Authenticating,
    /// Authenticated session with a running PTY.
    Connected,
}

// ── Connect request ──────────────────────────────────────────────────

/// Parameters for initiating an SSH connection.
#[derive(Debug, Clone, uniffi::Record)]
pub struct TtySshConnectRequest {
    /// Remote hostname or IP address.
    pub host: String,
    /// Remote SSH port (typically 22).
    pub port: u16,
    /// Username for authentication.
    pub user: String,
}

// ── Host key verification ────────────────────────────────────────────

/// Prompt shown to the user when the server's host key is unknown or
/// has changed.
#[derive(Debug, Clone, uniffi::Record)]
pub struct TtyHostKeyPrompt {
    /// Remote hostname or IP address.
    pub host: String,
    /// Remote SSH port.
    pub port: u16,
    /// Key exchange algorithm (e.g. `"ssh-ed25519"`).
    pub algorithm: String,
    /// SHA-256 fingerprint of the server's public key.
    pub fingerprint_sha256: String,
    /// Whether the fingerprint differs from a previously trusted key.
    pub is_changed: bool,
}

/// User decision on an unknown or changed host key.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum TtyHostKeyDecision {
    /// Trust the key and add it to known hosts.
    Accept,
    /// Reject the key and abort the connection.
    Reject,
}

// ── Authentication method ────────────────────────────────────────────

/// SSH authentication method offered or selected.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum TtyAuthMethod {
    /// Password-based authentication.
    Password,
    /// Public-key authentication (agent or on-disk key).
    PublicKey,
}

// ── Key management ───────────────────────────────────────────────────

/// Supported SSH key algorithms for generation.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum SshKeyAlgorithm {
    /// Ed25519 (recommended, fast, small keys).
    Ed25519,
    /// RSA with 4096-bit key size.
    Rsa4096,
}

/// Metadata returned after key generation or import.
///
/// Contains only public information — the private key itself
/// never crosses the FFI boundary.
#[derive(Debug, Clone, uniffi::Record)]
pub struct SshKeyMetadata {
    /// Unique identifier for this key (UUID v4).
    pub key_id: String,
    /// Algorithm used to generate the key.
    pub algorithm: SshKeyAlgorithm,
    /// User-assigned label for the key.
    pub label: String,
    /// SHA-256 fingerprint in `SHA256:<base64>` format.
    pub fingerprint_sha256: String,
    /// Public key in OpenSSH format (`ssh-ed25519 AAAA...`).
    pub public_key_openssh: String,
    /// Creation timestamp as milliseconds since Unix epoch.
    pub created_at_epoch_ms: i64,
}

// ── Render frame (Kotlin-facing) ────────────────────────────────────────

/// Visual shape of the terminal cursor.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum TtyCursorStyle {
    /// Thin vertical bar (I-beam) between characters.
    Bar,
    /// Solid filled rectangle covering the entire cell.
    Block,
    /// Thin horizontal bar at the bottom of the cell.
    Underline,
    /// Block outline (border only, transparent centre).
    BlockHollow,
}

/// Snapshot of the cursor position and appearance for a single render frame.
#[derive(Debug, Clone, uniffi::Record)]
pub struct TtyCursorState {
    /// Zero-based column index of the cursor within the current row.
    pub col: u16,
    /// Zero-based row index of the cursor within the visible viewport.
    pub row: u16,
    /// Whether the cursor should be drawn at all (hidden during rapid
    /// output or when the application has disabled it).
    pub visible: bool,
    /// Visual shape of the cursor.
    pub style: TtyCursorStyle,
    /// Whether the cursor should blink according to the blink period
    /// configured by the terminal application or user preference.
    pub blinking: bool,
}

/// A contiguous run of columns on a single row that share the same
/// foreground colour, background colour, and text attributes.
///
/// Column indices follow the half-open interval convention:
/// `start_col` is **inclusive** and `end_col` is **exclusive**, so a
/// span covering columns 2, 3, 4 is represented as `start_col = 2`,
/// `end_col = 5`.
///
/// Colors are packed ARGB (`0xAARRGGBB`). A value of `0x00000000`
/// means "use the terminal's current default colour" and must not be
/// interpreted as opaque black.
#[derive(Debug, Clone, uniffi::Record)]
pub struct TtyColorSpan {
    /// First column covered by this span (inclusive, zero-based).
    pub start_col: u16,
    /// First column *not* covered by this span (exclusive, zero-based).
    pub end_col: u16,
    /// Foreground colour in packed ARGB format; `0x00000000` = terminal default.
    pub fg_argb: u32,
    /// Background colour in packed ARGB format; `0x00000000` = terminal default.
    pub bg_argb: u32,
    /// Whether the text in this span is rendered bold.
    pub bold: bool,
    /// Whether the text in this span is rendered in italics.
    pub italic: bool,
    /// Whether the text in this span has an underline decoration.
    pub underline: bool,
}

/// A single visible row of the terminal, ready to be painted by the
/// Kotlin Canvas renderer.
///
/// `text` holds the UTF-8 content of every cell concatenated into one
/// string (wide characters occupy two consecutive logical columns).
/// `spans` lists colour and attribute runs that cover `text` by column
/// index and may be applied in order without sorting.
#[derive(Debug, Clone, uniffi::Record)]
pub struct TtyRenderRow {
    /// Concatenated UTF-8 text for every cell in this row.
    pub text: String,
    /// Colour and attribute spans covering this row's columns.
    pub spans: Vec<TtyColorSpan>,
    /// `true` when this row's content has changed since the last frame
    /// and must be redrawn; `false` when the previous bitmap is still valid.
    pub dirty: bool,
}

/// A complete snapshot of the terminal viewport produced by the
/// renderer backend and consumed by the Kotlin Canvas drawing code.
///
/// Colors are packed ARGB (`0xAARRGGBB`). A value of `0x00000000`
/// means "use terminal default" and must not be interpreted as
/// opaque black.
#[derive(Debug, Clone, uniffi::Record)]
pub struct TtyRenderFrame {
    /// Ordered list of visible rows, from top (`rows[0]`) to bottom.
    pub rows: Vec<TtyRenderRow>,
    /// Number of columns in the current viewport.
    pub cols: u16,
    /// Number of visible rows in the current viewport (equals `rows.len()`).
    pub num_rows: u16,
    /// Cursor position and appearance for this frame.
    pub cursor: TtyCursorState,
    /// Default background colour in packed ARGB; `0x00000000` = terminal default.
    pub default_bg_argb: u32,
    /// Default foreground colour in packed ARGB; `0x00000000` = terminal default.
    pub default_fg_argb: u32,
    /// `true` when at least one row is dirty and the frame must be
    /// composited; `false` when the display is unchanged.
    pub has_changes: bool,
}
