// Copyright (c) 2026 @Natfii. All rights reserved.

//! Agent tool for reading the full content of a specific email.

use std::sync::Arc;

use async_trait::async_trait;
use serde_json::json;

use crate::tools::traits::{Tool, ToolResult};

use super::client::EmailClient;
use super::types::MAX_READ_OUTPUT_CHARS;

/// Tool that reads the full content of a specific email by UID or by
/// searching sender/subject.
pub struct EmailReadTool {
    client: Arc<EmailClient>,
}

impl EmailReadTool {
    /// Creates a new [`EmailReadTool`] backed by the given client.
    pub fn new(client: Arc<EmailClient>) -> Self {
        Self { client }
    }
}

#[async_trait]
impl Tool for EmailReadTool {
    fn name(&self) -> &str {
        "email_read"
    }

    fn description(&self) -> &str {
        "Read the full content of a specific email by UID or by searching sender/subject"
    }

    fn parameters_schema(&self) -> serde_json::Value {
        json!({
            "type": "object",
            "properties": {
                "uid": {
                    "type": "integer",
                    "description": "IMAP UID of the message to read"
                },
                "sender": {
                    "type": "string",
                    "description": "Sender address or name to search for"
                },
                "subject": {
                    "type": "string",
                    "description": "Subject line text to search for"
                }
            }
        })
    }

    async fn execute(&self, args: serde_json::Value) -> anyhow::Result<ToolResult> {
        let uid = args.get("uid").and_then(|v| v.as_u64()).map(|v| v as u32);
        let sender = args.get("sender").and_then(|v| v.as_str());
        let subject = args.get("subject").and_then(|v| v.as_str());

        let resolved_uid = if let Some(u) = uid {
            u
        } else if sender.is_some() || subject.is_some() {
            // Search by sender/subject and use first match
            match self
                .client
                .search(
                    sender,
                    None,
                    subject,
                    None,
                    None,
                    None,
                    false,
                )
                .await
            {
                Ok(results) => {
                    if let Some(first) = results.first() {
                        first.uid
                    } else {
                        return Ok(ToolResult {
                            success: false,
                            output: String::new(),
                            error: Some(
                                "No emails found matching the given sender/subject criteria."
                                    .to_string(),
                            ),
                        });
                    }
                }
                Err(e) => {
                    return Ok(ToolResult {
                        success: false,
                        output: String::new(),
                        error: Some(format!("Failed to search for email: {e}")),
                    });
                }
            }
        } else {
            return Ok(ToolResult {
                success: false,
                output: String::new(),
                error: Some(
                    "Either 'uid' or at least one of 'sender'/'subject' must be provided."
                        .to_string(),
                ),
            });
        };

        match self.client.fetch_message(resolved_uid).await {
            Ok(email) => {
                let mut output = format!(
                    "UID: {}\nFrom: {}\nSubject: {}\nDate: {}\nMessage-ID: {}\n\n{}",
                    email.uid,
                    email.sender,
                    email.subject,
                    email.date,
                    email.message_id,
                    email.body,
                );

                if output.len() > MAX_READ_OUTPUT_CHARS {
                    let mut end = MAX_READ_OUTPUT_CHARS;
                    while !output.is_char_boundary(end) && end > 0 {
                        end -= 1;
                    }
                    output.truncate(end);
                    output.push_str("\n[content truncated]");
                }

                Ok(ToolResult {
                    success: true,
                    output,
                    error: None,
                })
            }
            Err(e) => Ok(ToolResult {
                success: false,
                output: String::new(),
                error: Some(format!("Failed to read email UID {resolved_uid}: {e}")),
            }),
        }
    }
}
