// Copyright (c) 2026 @Natfii. All rights reserved.

//! Agent tool for searching the inbox with structured criteria.

use std::sync::Arc;

use async_trait::async_trait;
use serde_json::json;

use crate::tools::traits::{Tool, ToolResult};

use super::client::EmailClient;

/// Tool that searches the inbox using structured criteria (from, to, subject,
/// body, date range, read status).
pub struct EmailSearchTool {
    client: Arc<EmailClient>,
}

impl EmailSearchTool {
    /// Creates a new [`EmailSearchTool`] backed by the given client.
    pub fn new(client: Arc<EmailClient>) -> Self {
        Self { client }
    }
}

#[async_trait]
impl Tool for EmailSearchTool {
    fn name(&self) -> &str {
        "email_search"
    }

    fn description(&self) -> &str {
        "Search the inbox using structured criteria (from, to, subject, body, date range)"
    }

    fn parameters_schema(&self) -> serde_json::Value {
        json!({
            "type": "object",
            "properties": {
                "from": {
                    "type": "string",
                    "description": "Filter by sender address or name"
                },
                "to": {
                    "type": "string",
                    "description": "Filter by recipient address"
                },
                "subject": {
                    "type": "string",
                    "description": "Filter by subject line text"
                },
                "body": {
                    "type": "string",
                    "description": "Filter by body text content"
                },
                "since": {
                    "type": "string",
                    "description": "Only messages on or after this date (format: 1-Jan-2026)"
                },
                "before": {
                    "type": "string",
                    "description": "Only messages before this date (format: 1-Jan-2026)"
                },
                "unread_only": {
                    "type": "boolean",
                    "description": "Only return unread messages (default false)"
                }
            }
        })
    }

    async fn execute(&self, args: serde_json::Value) -> anyhow::Result<ToolResult> {
        let from = args.get("from").and_then(|v| v.as_str());
        let to = args.get("to").and_then(|v| v.as_str());
        let subject = args.get("subject").and_then(|v| v.as_str());
        let body = args.get("body").and_then(|v| v.as_str());
        let since = args.get("since").and_then(|v| v.as_str());
        let before = args.get("before").and_then(|v| v.as_str());
        let unread_only = args
            .get("unread_only")
            .and_then(|v| v.as_bool())
            .unwrap_or(false);

        match self
            .client
            .search(from, to, subject, body, since, before, unread_only)
            .await
        {
            Ok(results) => {
                if results.is_empty() {
                    return Ok(ToolResult {
                        success: true,
                        output: "No emails found matching the search criteria.".to_string(),
                        error: None,
                    });
                }

                let count = results.len();
                let mut lines = Vec::with_capacity(count + 1);
                lines.push(format!("Found {} result(s):", count));

                for (i, summary) in results.iter().enumerate() {
                    lines.push(format!(
                        "{}. [UID {}] From: {} | Subject: {} | Date: {}",
                        i + 1,
                        summary.uid,
                        summary.sender,
                        summary.subject,
                        summary.date,
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
                error: Some(format!("Failed to search emails: {e}")),
            }),
        }
    }
}
