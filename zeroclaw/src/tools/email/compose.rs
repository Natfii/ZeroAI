// Copyright (c) 2026 @Natfii. All rights reserved.

//! Agent tool for composing and sending new emails.

use std::sync::Arc;

use async_trait::async_trait;
use serde_json::json;

use crate::tools::traits::{Tool, ToolResult};

use super::client::EmailClient;

/// Tool that composes and sends a new email to an arbitrary address.
pub struct EmailComposeTool {
    client: Arc<EmailClient>,
}

impl EmailComposeTool {
    /// Creates a new [`EmailComposeTool`] backed by the given client.
    pub fn new(client: Arc<EmailClient>) -> Self {
        Self { client }
    }
}

#[async_trait]
impl Tool for EmailComposeTool {
    fn name(&self) -> &str {
        "email_compose"
    }

    fn description(&self) -> &str {
        "Compose and send a new email to an arbitrary address"
    }

    fn parameters_schema(&self) -> serde_json::Value {
        json!({
            "type": "object",
            "properties": {
                "to": {
                    "type": "string",
                    "description": "Recipient email address"
                },
                "subject": {
                    "type": "string",
                    "description": "Email subject line"
                },
                "body": {
                    "type": "string",
                    "description": "Email body text"
                },
                "cc": {
                    "type": "string",
                    "description": "CC recipient email address (optional)"
                },
                "bcc": {
                    "type": "string",
                    "description": "BCC recipient email address (optional)"
                }
            },
            "required": ["to", "subject", "body"]
        })
    }

    async fn execute(&self, args: serde_json::Value) -> anyhow::Result<ToolResult> {
        let to = match args.get("to").and_then(|v| v.as_str()) {
            Some(v) => v,
            None => {
                return Ok(ToolResult {
                    success: false,
                    output: String::new(),
                    error: Some("Missing required parameter 'to'.".to_string()),
                });
            }
        };

        let subject = match args.get("subject").and_then(|v| v.as_str()) {
            Some(v) => v,
            None => {
                return Ok(ToolResult {
                    success: false,
                    output: String::new(),
                    error: Some("Missing required parameter 'subject'.".to_string()),
                });
            }
        };

        let body = match args.get("body").and_then(|v| v.as_str()) {
            Some(v) => v,
            None => {
                return Ok(ToolResult {
                    success: false,
                    output: String::new(),
                    error: Some("Missing required parameter 'body'.".to_string()),
                });
            }
        };

        let cc = args.get("cc").and_then(|v| v.as_str());
        let bcc = args.get("bcc").and_then(|v| v.as_str());

        match self.client.send_email(to, subject, body, cc, bcc).await {
            Ok(()) => Ok(ToolResult {
                success: true,
                output: format!("Email sent to {to} \u{2014} Subject: {subject}"),
                error: None,
            }),
            Err(e) => Ok(ToolResult {
                success: false,
                output: String::new(),
                error: Some(format!("Failed to send email: {e}")),
            }),
        }
    }
}
