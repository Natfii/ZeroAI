// Copyright (c) 2026 @Natfii. All rights reserved.

#![allow(clippy::unnecessary_literal_bound)]

//! Shared folder tool shims that delegate to Kotlin via UniFFI callback.
//!
//! Three tools (`shared_folder_list`, `shared_folder_read`,
//! `shared_folder_write`) implement the [`zeroclaw::tools::Tool`] trait
//! as thin wrappers. Actual SAF file I/O is performed by the Kotlin-side
//! [`SharedFolderHandler`] implementation registered via
//! [`register_shared_folder_handler`].

use crate::FfiError;
use async_trait::async_trait;
use serde_json::json;
use std::sync::Mutex;
use zeroclaw::tools::{Tool, ToolResult};

/// Callback interface implemented in Kotlin for SAF operations.
#[uniffi::export(callback_interface)]
pub trait SharedFolderHandler: Send + Sync {
    /// Executes a shared folder tool operation.
    fn execute_shared_folder_tool(
        &self,
        tool_name: String,
        params_json: String,
    ) -> Result<String, FfiError>;
}

static HANDLER: Mutex<Option<Box<dyn SharedFolderHandler>>> = Mutex::new(None);

/// Registers the Kotlin-side shared folder handler.
#[uniffi::export]
pub fn register_shared_folder_handler(handler: Box<dyn SharedFolderHandler>) {
    let mut guard = HANDLER
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    *guard = Some(handler);
}

/// Unregisters the shared folder handler.
#[uniffi::export]
pub fn unregister_shared_folder_handler() {
    let mut guard = HANDLER
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    *guard = None;
}

/// Dispatches a shared folder tool call to the registered handler.
fn dispatch(tool_name: &str, params_json: &str) -> Result<String, String> {
    let guard = HANDLER
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    match guard.as_ref() {
        Some(handler) => handler
            .execute_shared_folder_tool(tool_name.to_string(), params_json.to_string())
            .map_err(|e| format!("{e}")),
        None => Err(
            "Shared folder handler not registered. Enable the Shared Folder plugin in Hub > Plugins.".into(),
        ),
    }
}

// ---------------------------------------------------------------------------
// Tool trait implementations
// ---------------------------------------------------------------------------

/// Lists contents of a path within the shared folder.
pub(crate) struct SharedFolderListTool;

#[async_trait]
impl Tool for SharedFolderListTool {
    fn name(&self) -> &str {
        "shared_folder_list"
    }

    fn description(&self) -> &str {
        "List files and directories in the shared folder. Returns JSON array of entries with name, type, size_bytes, and last_modified."
    }

    fn parameters_schema(&self) -> serde_json::Value {
        json!({
            "type": "object",
            "properties": {
                "path": {
                    "type": "string",
                    "description": "Path relative to the shared folder root. Default: \"/\" (root).",
                    "default": "/"
                }
            }
        })
    }

    async fn execute(&self, args: serde_json::Value) -> anyhow::Result<ToolResult> {
        let params = serde_json::to_string(&args)?;
        match dispatch("shared_folder_list", &params) {
            Ok(output) => Ok(ToolResult {
                success: true,
                output,
                error: None,
            }),
            Err(e) => Ok(ToolResult {
                success: false,
                output: String::new(),
                error: Some(e),
            }),
        }
    }
}

/// Reads a file from the shared folder.
pub(crate) struct SharedFolderReadTool;

#[async_trait]
impl Tool for SharedFolderReadTool {
    fn name(&self) -> &str {
        "shared_folder_read"
    }

    fn description(&self) -> &str {
        "Read a file from the shared folder. Text files return raw content; binary files return base64. Max 10MB text, 2MB binary."
    }

    fn parameters_schema(&self) -> serde_json::Value {
        json!({
            "type": "object",
            "properties": {
                "path": {
                    "type": "string",
                    "description": "Path to the file, relative to the shared folder root."
                }
            },
            "required": ["path"]
        })
    }

    async fn execute(&self, args: serde_json::Value) -> anyhow::Result<ToolResult> {
        let params = serde_json::to_string(&args)?;
        match dispatch("shared_folder_read", &params) {
            Ok(output) => Ok(ToolResult {
                success: true,
                output,
                error: None,
            }),
            Err(e) => Ok(ToolResult {
                success: false,
                output: String::new(),
                error: Some(e),
            }),
        }
    }
}

/// Writes a file or creates a directory in the shared folder.
pub(crate) struct SharedFolderWriteTool;

#[async_trait]
impl Tool for SharedFolderWriteTool {
    fn name(&self) -> &str {
        "shared_folder_write"
    }

    fn description(&self) -> &str {
        "Write a file or create a directory in the shared folder. Overwrites existing files. Max 50MB."
    }

    fn parameters_schema(&self) -> serde_json::Value {
        json!({
            "type": "object",
            "properties": {
                "path": {
                    "type": "string",
                    "description": "Path for the file or directory, relative to the shared folder root."
                },
                "content": {
                    "type": "string",
                    "description": "File content to write. Ignored if mkdir is true."
                },
                "is_base64": {
                    "type": "boolean",
                    "description": "Whether content is base64-encoded binary data.",
                    "default": false
                },
                "mkdir": {
                    "type": "boolean",
                    "description": "If true, create a directory at path instead of a file.",
                    "default": false
                }
            },
            "required": ["path"]
        })
    }

    async fn execute(&self, args: serde_json::Value) -> anyhow::Result<ToolResult> {
        let params = serde_json::to_string(&args)?;
        match dispatch("shared_folder_write", &params) {
            Ok(output) => Ok(ToolResult {
                success: true,
                output,
                error: None,
            }),
            Err(e) => Ok(ToolResult {
                success: false,
                output: String::new(),
                error: Some(e),
            }),
        }
    }
}

/// Creates boxed Tool instances for injection via [`zeroclaw::tools::set_global_extra_tools`].
pub(crate) fn create_shared_folder_tools() -> Vec<Box<dyn Tool>> {
    vec![
        Box::new(SharedFolderListTool),
        Box::new(SharedFolderReadTool),
        Box::new(SharedFolderWriteTool),
    ]
}
