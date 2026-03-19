// Copyright (c) 2026 @Natfii. All rights reserved.

//! Agent tool for permanently emptying the Trash folder.

use std::sync::Arc;

use async_trait::async_trait;
use serde_json::json;

use crate::tools::traits::{Tool, ToolResult};

use super::client::EmailClient;

/// Tool that permanently deletes all messages in the Trash folder.
pub struct EmailEmptyTrashTool {
    client: Arc<EmailClient>,
}

impl EmailEmptyTrashTool {
    /// Creates a new [`EmailEmptyTrashTool`] backed by the given client.
    pub fn new(client: Arc<EmailClient>) -> Self {
        Self { client }
    }
}

#[async_trait]
impl Tool for EmailEmptyTrashTool {
    fn name(&self) -> &str {
        "email_empty_trash"
    }

    fn description(&self) -> &str {
        "Permanently delete all messages in the Trash folder"
    }

    fn parameters_schema(&self) -> serde_json::Value {
        json!({
            "type": "object",
            "properties": {}
        })
    }

    async fn execute(&self, _args: serde_json::Value) -> anyhow::Result<ToolResult> {
        match self.client.empty_trash().await {
            Ok(count) => {
                let output = if count == 0 {
                    "Trash folder is already empty.".to_string()
                } else {
                    format!("Permanently deleted {count} message(s) from Trash.")
                };
                Ok(ToolResult {
                    success: true,
                    output,
                    error: None,
                })
            }
            Err(e) => Ok(ToolResult {
                success: false,
                output: String::new(),
                error: Some(format!("Failed to empty trash: {e}")),
            }),
        }
    }
}
