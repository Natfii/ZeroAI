// Copyright (c) 2026 @Natfii. All rights reserved.

//! Agent tool for replying to a specific email.

use std::sync::Arc;

use async_trait::async_trait;
use serde_json::json;

use crate::tools::traits::{Tool, ToolResult};

use super::client::EmailClient;

/// Tool that replies to a specific email, maintaining thread via
/// `In-Reply-To` and `References` headers.
pub struct EmailReplyTool {
    client: Arc<EmailClient>,
}

impl EmailReplyTool {
    /// Creates a new [`EmailReplyTool`] backed by the given client.
    pub fn new(client: Arc<EmailClient>) -> Self {
        Self { client }
    }
}

#[async_trait]
impl Tool for EmailReplyTool {
    fn name(&self) -> &str {
        "email_reply"
    }

    fn description(&self) -> &str {
        "Reply to a specific email, maintaining thread via In-Reply-To and References headers"
    }

    fn parameters_schema(&self) -> serde_json::Value {
        json!({
            "type": "object",
            "properties": {
                "uid": {
                    "type": "integer",
                    "description": "IMAP UID of the message to reply to"
                },
                "body": {
                    "type": "string",
                    "description": "Reply body text"
                }
            },
            "required": ["uid", "body"]
        })
    }

    async fn execute(&self, args: serde_json::Value) -> anyhow::Result<ToolResult> {
        let uid = match args.get("uid").and_then(|v| v.as_u64()) {
            Some(u) => u as u32,
            None => {
                return Ok(ToolResult {
                    success: false,
                    output: String::new(),
                    error: Some("Missing required parameter 'uid'.".to_string()),
                });
            }
        };

        let body = match args.get("body").and_then(|v| v.as_str()) {
            Some(b) => b,
            None => {
                return Ok(ToolResult {
                    success: false,
                    output: String::new(),
                    error: Some("Missing required parameter 'body'.".to_string()),
                });
            }
        };

        match self.client.reply(uid, body).await {
            Ok((recipient, subject)) => Ok(ToolResult {
                success: true,
                output: format!(
                    "Reply sent to {recipient} \u{2014} Subject: {subject}"
                ),
                error: None,
            }),
            Err(e) => Ok(ToolResult {
                success: false,
                output: String::new(),
                error: Some(format!("Failed to send reply: {e}")),
            }),
        }
    }
}
