// Copyright (c) 2026 @Natfii. All rights reserved.

//! Agent tool for checking the email inbox for new unread messages.

use std::sync::Arc;

use async_trait::async_trait;
use serde_json::json;

use crate::tools::traits::{Tool, ToolResult};

use super::client::EmailClient;

/// Tool that checks the email inbox for unread messages and returns a summary.
pub struct EmailCheckTool {
    client: Arc<EmailClient>,
}

impl EmailCheckTool {
    /// Creates a new [`EmailCheckTool`] backed by the given client.
    pub fn new(client: Arc<EmailClient>) -> Self {
        Self { client }
    }
}

#[async_trait]
impl Tool for EmailCheckTool {
    fn name(&self) -> &str {
        "email_check"
    }

    fn description(&self) -> &str {
        "Check the email inbox for new unread messages and return a summary"
    }

    fn parameters_schema(&self) -> serde_json::Value {
        json!({
            "type": "object",
            "properties": {
                "limit": {
                    "type": "integer",
                    "description": "Maximum number of unread messages to return (default 10)"
                }
            }
        })
    }

    async fn execute(&self, args: serde_json::Value) -> anyhow::Result<ToolResult> {
        let limit = args
            .get("limit")
            .and_then(|v| v.as_u64())
            .unwrap_or(10) as u32;

        match self.client.fetch_unread(limit).await {
            Ok(emails) => {
                if emails.is_empty() {
                    return Ok(ToolResult {
                        success: true,
                        output: "No new unread emails.".to_string(),
                        error: None,
                    });
                }

                let count = emails.len();
                let mut lines = Vec::with_capacity(count + 1);
                lines.push(format!("Found {} unread email(s):", count));

                for (i, email) in emails.iter().enumerate() {
                    let preview = if email.body.len() > 200 {
                        let mut end = 200;
                        while !email.body.is_char_boundary(end) && end > 0 {
                            end -= 1;
                        }
                        format!("{}...", &email.body[..end])
                    } else {
                        email.body.clone()
                    };
                    lines.push(format!(
                        "{}. [UID {}] From: {} | Subject: {} | Date: {}\n   {}",
                        i + 1,
                        email.uid,
                        email.sender,
                        email.subject,
                        email.date,
                        preview,
                    ));
                }

                Ok(ToolResult {
                    success: true,
                    output: lines.join("\n"),
                    error: None,
                })
            }
            Err(e) => Ok(ToolResult {
                success: false,
                output: String::new(),
                error: Some(format!("Failed to check inbox: {e}")),
            }),
        }
    }
}
