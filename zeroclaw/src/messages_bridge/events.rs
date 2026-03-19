// Copyright (c) 2026 @Natfii. All rights reserved.

//! Bridge events and RPC message handler for the Google Messages long-poll stream.
//!
//! Incoming long-poll payloads are encrypted [`super::proto::rpc::RpcMessageData`]
//! blobs.  This module decrypts them, deduplicates by SHA-256 hash, identifies the
//! action type, and converts the payload into a [`BridgeEvent`] that the session
//! manager can act on.
//!
//! Ported from mautrix-gmessages:
//! <https://github.com/mautrix/gmessages/blob/main/pkg/libgm/event_handler.go>

use std::collections::VecDeque;

use prost::Message;
use sha2::{Digest, Sha256};
use tracing::{debug, trace, warn};

use super::crypto::{self, BugleCryptoKeys};
use super::proto::{events, rpc};
use super::types::{BridgedConversation, BridgedMessage, MessageType};

/// Maximum number of recently seen message hashes retained for deduplication.
const MAX_SEEN_HASHES: usize = 256;

/// Events emitted by the Google Messages bridge.
///
/// Each variant represents a distinct lifecycle or data event that the session
/// manager should handle — updating the store, notifying the UI, or adjusting
/// connection state.
#[derive(Debug)]
pub enum BridgeEvent {
    /// Pairing completed successfully.
    PairSuccess {
        /// Serialised mobile [`super::proto::authentication::Device`] bytes.
        mobile_device: Vec<u8>,
        /// Fresh Tachyon auth token for subsequent API calls.
        tachyon_auth_token: Vec<u8>,
    },
    /// Conversation list synced from phone.
    ConversationListSync {
        /// Full set of conversations received from the phone.
        conversations: Vec<BridgedConversation>,
    },
    /// New or updated message received.
    NewMessage(BridgedMessage),
    /// Phone is not responding to pings.
    PhoneNotResponding,
    /// Phone started responding again after being unresponsive.
    PhoneRespondingAgain,
    /// The long-poll connection was lost.
    Disconnected {
        /// Human-readable reason for the disconnection.
        reason: String,
    },
    /// The bridge was unpaired by Google or the phone.
    Unpaired,
    /// A browser presence check was received — must be acknowledged.
    BrowserPresenceCheck,
    /// A user alert was received from the phone.
    UserAlert {
        /// The raw [`events::AlertType`] integer value.
        alert_type: i32,
    },
}

/// Processes an incoming RPC message from the long-poll stream.
///
/// The function performs three steps:
///
/// 1. **Decrypt** — the raw `data` bytes are decrypted via
///    [`crypto::decrypt`] using the session's AES/HMAC keys.
/// 2. **Deduplicate** — a SHA-256 hash of the plaintext is checked against
///    `seen_hashes`.  If already seen the message is silently dropped.
/// 3. **Decode** — the plaintext is decoded as [`rpc::RpcMessageData`] and
///    dispatched to the appropriate handler based on `action`.
///
/// Returns `None` for heartbeats, duplicates, and unrecognised action types.
///
/// The `action` comes from the outer [`rpc::RpcMessageData`] decoded in the
/// longpoll handler — the decrypted plaintext is the event payload itself
/// (e.g. `ConversationEvent`), not another `RpcMessageData` wrapper.
pub fn handle_rpc_message(
    data: &[u8],
    action: i32,
    crypto_keys: &BugleCryptoKeys,
    seen_hashes: &mut VecDeque<[u8; 32]>,
) -> Option<BridgeEvent> {
    // 1. Decrypt.
    let plaintext = match crypto::decrypt(crypto_keys, data) {
        Ok(pt) => pt,
        Err(e) => {
            warn!("failed to decrypt RPC message: {e}");
            return None;
        }
    };

    // 2. Deduplicate by SHA-256 hash.
    let hash: [u8; 32] = Sha256::digest(&plaintext).into();
    if seen_hashes.contains(&hash) {
        trace!("dropping duplicate RPC message (hash already seen)");
        return None;
    }
    seen_hashes.push_back(hash);
    if seen_hashes.len() > MAX_SEEN_HASHES {
        seen_hashes.pop_front();
    }

    debug!(
        action = action,
        plaintext_len = plaintext.len(),
        "decrypted RPC payload"
    );

    // 3. Dispatch using the action from the outer RpcMessageData.
    //    The plaintext IS the event payload (e.g. ConversationEvent).
    dispatch_action(action, &plaintext)
}

/// Routes a decoded [`rpc::RpcMessageData`] to the appropriate event builder
/// based on its [`rpc::ActionType`].
/// Routes a decrypted event payload to the appropriate handler based on its
/// [`rpc::ActionType`].
///
/// The `payload` is the raw decrypted bytes — the actual event proto
/// (e.g. `ConversationEvent`, `MessageEvent`), not an `RpcMessageData` wrapper.
fn dispatch_action(action: i32, payload: &[u8]) -> Option<BridgeEvent> {
    if payload.is_empty() {
        trace!(action, "RPC message has no payload data");
        return None;
    }

    match rpc::ActionType::try_from(action) {
        Ok(rpc::ActionType::ListConversations) => handle_conversation_list(payload),
        Ok(rpc::ActionType::MessageUpdates) => handle_message_updates(payload),
        Ok(rpc::ActionType::ConversationUpdates) => handle_conversation_updates(payload),
        Ok(rpc::ActionType::BrowserPresenceCheck) => Some(BridgeEvent::BrowserPresenceCheck),
        Ok(rpc::ActionType::UserAlert) => handle_user_alert(payload),
        Ok(rpc::ActionType::TypingUpdates) => {
            trace!("ignoring typing update (not surfaced to bridge)");
            None
        }
        Ok(rpc::ActionType::SettingsUpdate) => {
            trace!("ignoring settings update (not surfaced to bridge)");
            None
        }
        Ok(other) => {
            debug!(?other, "unhandled RPC action type");
            None
        }
        Err(_) => {
            debug!(action, "unknown RPC action type");
            None
        }
    }
}

/// Decodes a conversation list payload into a [`BridgeEvent::ConversationListSync`].
fn handle_conversation_list(payload: &[u8]) -> Option<BridgeEvent> {
    let event = match events::ConversationEvent::decode(payload) {
        Ok(e) => e,
        Err(e) => {
            warn!("failed to decode ConversationEvent: {e}");
            return None;
        }
    };

    let conversations = event
        .data
        .iter()
        .map(proto_conversation_to_bridged)
        .collect();

    Some(BridgeEvent::ConversationListSync { conversations })
}

/// Decodes a conversation update payload into a [`BridgeEvent::ConversationListSync`].
fn handle_conversation_updates(payload: &[u8]) -> Option<BridgeEvent> {
    // Conversation updates use the same proto shape as the full list.
    handle_conversation_list(payload)
}

/// Decodes a message update payload into one or more [`BridgeEvent::NewMessage`] events.
///
/// Returns the first message event for simplicity.  The session manager should
/// call this in a loop for multi-message payloads in a future refinement.
fn handle_message_updates(payload: &[u8]) -> Option<BridgeEvent> {
    let event = match events::MessageEvent::decode(payload) {
        Ok(e) => e,
        Err(e) => {
            warn!("failed to decode MessageEvent: {e}");
            return None;
        }
    };

    event.data.first().map(|msg| {
        BridgeEvent::NewMessage(proto_message_to_bridged(msg))
    })
}

/// Decodes a user alert payload into a [`BridgeEvent::UserAlert`].
fn handle_user_alert(payload: &[u8]) -> Option<BridgeEvent> {
    let event = match events::UserAlertEvent::decode(payload) {
        Ok(e) => e,
        Err(e) => {
            warn!("failed to decode UserAlertEvent: {e}");
            return None;
        }
    };

    Some(BridgeEvent::UserAlert {
        alert_type: event.alert_type,
    })
}

/// Converts a protobuf [`conversations::Conversation`] to a [`BridgedConversation`].
fn proto_conversation_to_bridged(
    conv: &super::proto::conversations::Conversation,
) -> BridgedConversation {
    let preview = conv
        .latest_message
        .as_ref()
        .map(|lm| lm.display_content.clone())
        .unwrap_or_default();

    BridgedConversation {
        id: conv.conversation_id.clone(),
        display_name: conv.name.clone(),
        is_group: conv.is_group_chat,
        last_message_preview: preview,
        last_message_timestamp: conv.last_message_timestamp,
        agent_allowed: false,
        window_start: None,
    }
}

/// Converts a protobuf [`conversations::Message`] to a [`BridgedMessage`].
fn proto_message_to_bridged(
    msg: &super::proto::conversations::Message,
) -> BridgedMessage {
    // Extract text content from the first MessageInfo entry.
    let body = msg
        .message_info
        .first()
        .and_then(|info| match &info.data {
            Some(super::proto::conversations::message_info::Data::MessageContent(mc)) => {
                Some(mc.content.clone())
            }
            _ => None,
        })
        .unwrap_or_default();

    // Determine sender name from the embedded participant, if present.
    let sender_name = msg
        .sender_participant
        .as_ref()
        .map(|p| {
            if p.full_name.is_empty() {
                p.first_name.clone()
            } else {
                p.full_name.clone()
            }
        })
        .unwrap_or_default();

    // Infer message type: reactions have a non-empty reactions list and empty body.
    let message_type = if !msg.reactions.is_empty() && body.is_empty() {
        let emoji = msg
            .reactions
            .first()
            .and_then(|r| r.data.as_ref())
            .map(|d| d.unicode.clone())
            .unwrap_or_default();
        // Store the reaction emoji in the body for display.
        return BridgedMessage {
            id: msg.message_id.clone(),
            conversation_id: msg.conversation_id.clone(),
            sender_name,
            body: format!("[Reaction: {emoji}]"),
            timestamp: msg.timestamp,
            is_outgoing: false,
            message_type: MessageType::Reaction,
        };
    } else {
        MessageType::Text
    };

    BridgedMessage {
        id: msg.message_id.clone(),
        conversation_id: msg.conversation_id.clone(),
        sender_name,
        body,
        timestamp: msg.timestamp,
        is_outgoing: false,
        message_type,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn seen_hashes_evicts_oldest() {
        let mut seen = VecDeque::new();
        for i in 0..MAX_SEEN_HASHES + 10 {
            let hash: [u8; 32] = Sha256::digest(&i.to_le_bytes()).into();
            seen.push_back(hash);
            if seen.len() > MAX_SEEN_HASHES {
                seen.pop_front();
            }
        }
        assert_eq!(seen.len(), MAX_SEEN_HASHES);
    }
}
