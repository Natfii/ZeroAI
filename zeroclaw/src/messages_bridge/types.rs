// Copyright (c) 2026 @Natfii. All rights reserved.

//! Shared types for the Google Messages bridge.

/// Bridge connection status exposed to Kotlin via FFI.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum BridgeStatus {
    /// Not paired with any device.
    Unpaired,
    /// QR code displayed, waiting for user to scan.
    AwaitingPairing { qr_url: String },
    /// Paired and actively receiving messages.
    Connected,
    /// Connection lost, attempting to reconnect.
    Reconnecting { attempt: u32 },
    /// Paired but phone is not responding to pings.
    PhoneNotResponding,
}

/// A conversation synced from Google Messages.
#[derive(Debug, Clone)]
pub struct BridgedConversation {
    /// Google internal conversation ID.
    pub id: String,
    /// Contact or group name.
    pub display_name: String,
    /// Whether this is a group conversation.
    pub is_group: bool,
    /// Last message text preview for display in allowlist.
    pub last_message_preview: String,
    /// Epoch millis of last message.
    pub last_message_timestamp: i64,
    /// Whether the AI agent is allowed to read this conversation.
    pub agent_allowed: bool,
    /// Optional epoch millis cutoff. Null means all history.
    pub window_start: Option<i64>,
}

/// A single message from a bridged conversation.
#[derive(Debug, Clone)]
pub struct BridgedMessage {
    /// Google internal message ID.
    pub id: String,
    /// Conversation this message belongs to.
    pub conversation_id: String,
    /// Sender display name.
    pub sender_name: String,
    /// Message text content (or placeholder for non-text types).
    pub body: String,
    /// Epoch millis.
    pub timestamp: i64,
    /// Whether sent by the device owner.
    pub is_outgoing: bool,
    /// Content type.
    pub message_type: MessageType,
}

/// Message content type.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum MessageType {
    /// Regular text message.
    Text,
    /// Emoji reaction (body stores "[Reaction: {emoji}]").
    Reaction,
    /// Read receipt (body stores "[Read]").
    ReadReceipt,
}

impl MessageType {
    /// Converts to string representation for SQLite storage.
    pub fn as_str(&self) -> &'static str {
        match self {
            Self::Text => "text",
            Self::Reaction => "reaction",
            Self::ReadReceipt => "read_receipt",
        }
    }

    /// Parses from the SQLite string representation.
    pub fn parse_db(s: &str) -> Self {
        match s {
            "reaction" => Self::Reaction,
            "read_receipt" => Self::ReadReceipt,
            _ => Self::Text,
        }
    }
}

/// Device identity established during pairing.
/// Stored in AuthProfilesStore, not in plaintext.
#[derive(Debug, Clone)]
pub struct PairedDevice {
    /// Browser device identity bytes.
    pub browser_id: Vec<u8>,
    /// Mobile device identity bytes.
    pub mobile_id: Vec<u8>,
    /// Tachyon auth token for API requests.
    pub tachyon_auth_token: Vec<u8>,
    /// Token TTL from the server, included in outgoing RPC messages.
    pub tachyon_ttl: i64,
    /// AES-256 key for data channel encryption.
    pub aes_key: [u8; 32],
    /// HMAC-SHA256 key for data channel authentication.
    pub hmac_key: [u8; 32],
    /// Raw ECDSA P-256 signing key bytes from the pairing session.
    /// Used by [`super::methods::refresh_auth_token`] to sign the
    /// `RegisterRefresh` request after pairing completes.
    pub signing_key: Vec<u8>,
}
