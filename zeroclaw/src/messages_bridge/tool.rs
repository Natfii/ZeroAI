// Copyright (c) 2026 @Natfii. All rights reserved.

//! Agent tool for reading Google Messages conversations from the bridge store.
//!
//! [`ReadMessagesTool`] exposes bridged SMS/RCS conversations to the LLM as a
//! timestamped transcript. Access is gated by the per-conversation allowlist —
//! the user must enable a conversation in Hub > Google Messages > Allowlist
//! before the agent can read it.

use std::sync::Arc;

use async_trait::async_trait;
use chrono::DateTime;
use serde_json::json;

use crate::tools::traits::{Tool, ToolResult};

use super::store::MessagesBridgeStore;

/// Tool that reads text messages from an allowlisted Google Messages conversation.
///
/// Returns a timestamped transcript of messages from the matched conversation.
/// Contact matching is case-insensitive substring matching against the
/// conversation's display name. The conversation must have `agent_allowed`
/// set to `true` in the bridge allowlist.
/// Resolves the store lazily via [`super::session::get_store`] so the tool
/// can be registered at daemon startup before the bridge is paired.
pub struct ReadMessagesTool {
    /// Optional store override for testing. When `None`, resolves from the
    /// global bridge session at execution time.
    store_override: Option<Arc<MessagesBridgeStore>>,
}

impl ReadMessagesTool {
    /// Creates a tool that resolves the store lazily from the bridge session.
    pub fn new_lazy() -> Self {
        Self { store_override: None }
    }

    /// Creates a tool backed by a specific store (for tests).
    #[cfg(test)]
    pub fn new(store: Arc<MessagesBridgeStore>) -> Self {
        Self { store_override: Some(store) }
    }

    /// Returns the bridge store, or an error if no session is active.
    fn store(&self) -> Result<Arc<MessagesBridgeStore>, anyhow::Error> {
        if let Some(s) = &self.store_override {
            return Ok(Arc::clone(s));
        }
        super::session::get_store()
            .ok_or_else(|| anyhow::anyhow!(
                "Google Messages bridge is not paired. \
                 Pair in Hub > Google Messages first."
            ))
    }
}

#[async_trait]
impl Tool for ReadMessagesTool {
    fn name(&self) -> &str {
        "read_messages"
    }

    fn description(&self) -> &str {
        "Read text messages from Google Messages conversations that the user has allowed. \
        Returns a timestamped transcript."
    }

    fn parameters_schema(&self) -> serde_json::Value {
        json!({
            "type": "object",
            "properties": {
                "contact": {
                    "type": "string",
                    "description": "Contact or group name (case-insensitive substring match)"
                },
                "since": {
                    "type": "string",
                    "description": "ISO-8601 timestamp, only messages after this"
                },
                "limit": {
                    "type": "integer",
                    "description": "Max messages to return (default 100)"
                }
            },
            "required": ["contact"]
        })
    }

    async fn execute(&self, args: serde_json::Value) -> anyhow::Result<ToolResult> {
        // Extract required contact param.
        let contact = args
            .get("contact")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .trim()
            .to_string();

        if contact.is_empty() {
            return Ok(ToolResult {
                success: false,
                output: String::new(),
                error: Some("'contact' parameter is required and must not be empty".to_string()),
            });
        }

        // Extract optional limit (default 100).
        let limit = args
            .get("limit")
            .and_then(|v| v.as_u64())
            .unwrap_or(100)
            .min(1000) as u32;

        // Extract optional since as epoch millis.
        let since_millis: Option<i64> = args
            .get("since")
            .and_then(|v| v.as_str())
            .and_then(|s| DateTime::parse_from_rfc3339(s).ok())
            .map(|dt| dt.timestamp_millis());

        // Lazily resolve the store (bridge may not have been paired at startup).
        let store = match self.store() {
            Ok(s) => s,
            Err(e) => {
                return Ok(ToolResult {
                    success: false,
                    output: String::new(),
                    error: Some(e.to_string()),
                });
            }
        };

        // List conversations and find a match by case-insensitive substring.
        let conversations = match store.list_conversations() {
            Ok(convs) => convs,
            Err(e) => {
                return Ok(ToolResult {
                    success: false,
                    output: String::new(),
                    error: Some(format!("Failed to list conversations: {e}")),
                });
            }
        };

        let contact_lower = contact.to_lowercase();

        // Check for exact match first, then fall back to partial matches.
        let exact: Vec<_> = conversations
            .iter()
            .filter(|c| c.display_name.to_lowercase() == contact_lower)
            .collect();

        let partial: Vec<_> = conversations
            .iter()
            .filter(|c| c.display_name.to_lowercase().contains(&contact_lower))
            .collect();

        let matched = if !exact.is_empty() {
            exact[0]
        } else if partial.len() == 1 {
            partial[0]
        } else if partial.is_empty() {
            let available: Vec<&str> = conversations
                .iter()
                .map(|c| c.display_name.as_str())
                .collect();
            let list = if available.is_empty() {
                "No conversations have been bridged yet.".to_string()
            } else {
                format!("Available conversations: {}", available.join(", "))
            };
            return Ok(ToolResult {
                success: false,
                output: String::new(),
                error: Some(format!(
                    "No conversation found matching '{contact}'. {list}"
                )),
            });
        } else {
            // Multiple partial matches — ask the user to be more specific.
            let matches: Vec<&str> = partial.iter().map(|c| c.display_name.as_str()).collect();
            return Ok(ToolResult {
                success: false,
                output: String::new(),
                error: Some(format!(
                    "Multiple conversations match '{contact}': {}. \
                    Please use a more specific name.",
                    matches.join(", ")
                )),
            });
        };

        // Check agent allowlist.
        if !matched.agent_allowed {
            return Ok(ToolResult {
                success: false,
                output: String::new(),
                error: Some(format!(
                    "Agent access is not enabled for '{}'. \
                    Enable it in Hub > Google Messages > Allowlist.",
                    matched.display_name
                )),
            });
        }

        // Resolve effective since: use conversation window_start as baseline,
        // then take the later of window_start and the caller's since.
        let effective_since = match (matched.window_start, since_millis) {
            (Some(w), Some(s)) => Some(w.max(s)),
            (Some(w), None) => Some(w),
            (None, s) => s,
        };

        // Query messages from the store.
        let mut messages = match store.query_messages(&matched.id, effective_since, limit) {
            Ok(msgs) => msgs,
            Err(e) => {
                return Ok(ToolResult {
                    success: false,
                    output: String::new(),
                    error: Some(format!("Failed to read messages: {e}")),
                });
            }
        };

        // Auto-fetch history if store is empty for this conversation.
        if messages.is_empty() {
            match super::session::fetch_conversation_history(
                &matched.id,
                limit.into(),
            )
            .await
            {
                Ok(fetched) => {
                    tracing::info!(
                        target: "messages_bridge::tool",
                        conversation_id = %matched.id,
                        count = fetched.len(),
                        "auto-fetched message history"
                    );
                    // Re-query from store (fetch_conversation_history stores them).
                    messages = store
                        .query_messages(&matched.id, effective_since, limit)
                        .unwrap_or_default();
                }
                Err(e) => {
                    tracing::warn!(
                        target: "messages_bridge::tool",
                        conversation_id = %matched.id,
                        "failed to auto-fetch history: {e}"
                    );
                }
            }
        }

        if messages.is_empty() {
            return Ok(ToolResult {
                success: true,
                output: format!(
                    "No messages found in conversation with '{}' for the given time range.",
                    matched.display_name
                ),
                error: None,
            });
        }

        // Build a chronological timestamped transcript (messages arrive DESC from store).
        let mut chronological = messages;
        chronological.reverse();

        let count = chronological.len();
        let mut lines = Vec::with_capacity(count + 2);
        lines.push(format!(
            "Messages with '{}' ({} message{}):\n",
            matched.display_name,
            count,
            if count == 1 { "" } else { "s" }
        ));

        for msg in &chronological {
            let sender = if msg.is_outgoing {
                "You".to_string()
            } else {
                msg.sender_name.clone()
            };

            let formatted_time = DateTime::from_timestamp_millis(msg.timestamp)
                .map(|dt| dt.format("%Y-%m-%d %H:%M").to_string())
                .unwrap_or_else(|| msg.timestamp.to_string());

            lines.push(format!("[{formatted_time}] {sender}: {}", msg.body));
        }

        Ok(ToolResult {
            success: true,
            output: lines.join("\n"),
            error: None,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::messages_bridge::store::MessagesBridgeStore;
    use crate::messages_bridge::types::{BridgedConversation, BridgedMessage, MessageType};
    use tempfile::TempDir;

    fn make_store(tmp: &TempDir) -> Arc<MessagesBridgeStore> {
        Arc::new(MessagesBridgeStore::open(tmp.path()).unwrap())
    }

    fn make_conv(id: &str, name: &str, allowed: bool) -> BridgedConversation {
        BridgedConversation {
            id: id.to_string(),
            display_name: name.to_string(),
            is_group: false,
            last_message_preview: String::new(),
            last_message_timestamp: 0,
            agent_allowed: allowed,
            window_start: None,
        }
    }

    fn make_msg(id: &str, conv_id: &str, sender: &str, body: &str, ts: i64) -> BridgedMessage {
        BridgedMessage {
            id: id.to_string(),
            conversation_id: conv_id.to_string(),
            sender_name: sender.to_string(),
            body: body.to_string(),
            timestamp: ts,
            is_outgoing: false,
            message_type: MessageType::Text,
        }
    }

    #[tokio::test]
    async fn returns_transcript_for_allowed_conversation() {
        let tmp = TempDir::new().unwrap();
        let store = make_store(&tmp);
        store.upsert_conversation(&make_conv("c1", "Mom", true)).unwrap();
        store
            .store_messages(&[
                make_msg("m1", "c1", "Mom", "Are you coming to dinner?", 1_742_000_000_000),
                make_msg("m2", "c1", "Mom", "Let me know!", 1_742_000_120_000),
            ])
            .unwrap();

        let tool = ReadMessagesTool::new(store);
        let result = tool
            .execute(json!({ "contact": "Mom" }))
            .await
            .unwrap();

        assert!(result.success);
        assert!(result.output.contains("Messages with 'Mom'"));
        assert!(result.output.contains("Are you coming to dinner?"));
        assert!(result.error.is_none());
    }

    #[tokio::test]
    async fn returns_error_when_contact_not_found() {
        let tmp = TempDir::new().unwrap();
        let store = make_store(&tmp);
        store.upsert_conversation(&make_conv("c1", "Alice", true)).unwrap();

        let tool = ReadMessagesTool::new(store);
        let result = tool
            .execute(json!({ "contact": "Zara" }))
            .await
            .unwrap();

        assert!(!result.success);
        let err = result.error.unwrap();
        assert!(err.contains("No conversation found"));
        assert!(err.contains("Alice"));
    }

    #[tokio::test]
    async fn returns_error_when_not_allowed() {
        let tmp = TempDir::new().unwrap();
        let store = make_store(&tmp);
        store.upsert_conversation(&make_conv("c1", "Bob", false)).unwrap();
        store
            .store_messages(&[make_msg("m1", "c1", "Bob", "secret", 1_000)])
            .unwrap();

        let tool = ReadMessagesTool::new(store);
        let result = tool
            .execute(json!({ "contact": "Bob" }))
            .await
            .unwrap();

        assert!(!result.success);
        let err = result.error.unwrap();
        assert!(err.contains("Agent access is not enabled"));
        assert!(err.contains("Allowlist"));
    }

    #[tokio::test]
    async fn returns_error_on_empty_contact() {
        let tmp = TempDir::new().unwrap();
        let store = make_store(&tmp);

        let tool = ReadMessagesTool::new(store);
        let result = tool.execute(json!({ "contact": "  " })).await.unwrap();

        assert!(!result.success);
        assert!(result.error.unwrap().contains("required"));
    }

    #[tokio::test]
    async fn returns_disambiguation_error_on_multiple_partial_matches() {
        let tmp = TempDir::new().unwrap();
        let store = make_store(&tmp);
        store.upsert_conversation(&make_conv("c1", "Alice Smith", true)).unwrap();
        store.upsert_conversation(&make_conv("c2", "Alice Jones", true)).unwrap();

        let tool = ReadMessagesTool::new(store);
        let result = tool
            .execute(json!({ "contact": "alice" }))
            .await
            .unwrap();

        assert!(!result.success);
        let err = result.error.unwrap();
        assert!(err.contains("Multiple conversations match"));
    }

    #[tokio::test]
    async fn exact_match_preferred_over_partial() {
        let tmp = TempDir::new().unwrap();
        let store = make_store(&tmp);
        store.upsert_conversation(&make_conv("c1", "Alice", true)).unwrap();
        store.upsert_conversation(&make_conv("c2", "Alice Smith", true)).unwrap();
        store
            .store_messages(&[make_msg("m1", "c1", "Alice", "exact match", 1_000)])
            .unwrap();

        let tool = ReadMessagesTool::new(store);
        // Exact match on "Alice" should return c1, not disambiguation error.
        let result = tool
            .execute(json!({ "contact": "Alice" }))
            .await
            .unwrap();

        assert!(result.success);
        assert!(result.output.contains("exact match"));
    }

    #[tokio::test]
    async fn outgoing_messages_labeled_as_you() {
        let tmp = TempDir::new().unwrap();
        let store = make_store(&tmp);
        store.upsert_conversation(&make_conv("c1", "Dad", true)).unwrap();
        let mut msg = make_msg("m1", "c1", "You", "On my way!", 1_000);
        msg.is_outgoing = true;
        store.store_messages(&[msg]).unwrap();

        let tool = ReadMessagesTool::new(store);
        let result = tool
            .execute(json!({ "contact": "Dad" }))
            .await
            .unwrap();

        assert!(result.success);
        assert!(result.output.contains("] You: On my way!"));
    }
}
