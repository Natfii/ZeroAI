// Copyright (c) 2026 @Natfii. All rights reserved.

//! Agent tool for deleting emails by UID.

use std::sync::Arc;

use async_trait::async_trait;
use serde_json::json;

use crate::tools::traits::{Tool, ToolResult};

use super::client::EmailClient;

/// Tool that deletes one or more emails by UID.
pub struct EmailDeleteTool {
    client: Arc<EmailClient>,
}

impl EmailDeleteTool {
    /// Creates a new [`EmailDeleteTool`] backed by the given client.
    pub fn new(client: Arc<EmailClient>) -> Self {
        Self { client }
    }
}

#[async_trait]
impl Tool for EmailDeleteTool {
    fn name(&self) -> &str {
        "email_delete"
    }

    fn description(&self) -> &str {
        "Delete one or more emails by UID"
    }

    fn parameters_schema(&self) -> serde_json::Value {
        json!({
            "type": "object",
            "properties": {
                "uids": {
                    "type": "array",
                    "items": { "type": "integer" },
                    "description": "List of IMAP UIDs to delete"
                }
            },
            "required": ["uids"]
        })
    }

    async fn execute(&self, args: serde_json::Value) -> anyhow::Result<ToolResult> {
        let uids: Vec<u32> = match args.get("uids").and_then(|v| v.as_array()) {
            Some(arr) => arr
                .iter()
                .filter_map(|v| v.as_u64().map(|u| u as u32))
                .collect(),
            None => {
                return Ok(ToolResult {
                    success: false,
                    output: String::new(),
                    error: Some("Missing required parameter 'uids'.".to_string()),
                });
            }
        };

        if uids.is_empty() {
            return Ok(ToolResult {
                success: false,
                output: String::new(),
                error: Some("'uids' array must not be empty.".to_string()),
            });
        }

        match self.client.delete(&uids).await {
            Ok(count) => Ok(ToolResult {
                success: true,
                output: format!("Deleted {count} email(s)."),
                error: None,
            }),
            Err(e) => Ok(ToolResult {
                success: false,
                output: String::new(),
                error: Some(format!("Failed to delete emails: {e}")),
            }),
        }
    }
}
