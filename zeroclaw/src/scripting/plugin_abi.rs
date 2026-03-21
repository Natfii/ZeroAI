// Copyright (c) 2026 @Natfii. All rights reserved.

//! Stable Plugin ABI — the versioned bridge between guest runtimes and the host.

use crate::scripting::{ScriptError, ScriptHost, ScriptOperation};
use std::sync::Arc;

/// ABI version for compatibility checking.
pub const ABI_VERSION: &str = "0.2.0";

/// Stable host interface for plugin runtimes.
///
/// Maps 1:1 with the WIT `host` interface. Guest runtimes translate
/// their call conventions into these methods. Implementations delegate
/// to [`ScriptHost::call`] with the appropriate [`ScriptOperation`].
pub trait PluginHost: Send + Sync {
    /// Invoke a tool by name with JSON arguments.
    fn invoke_tool(&self, name: &str, args_json: &str) -> Result<String, String>;
    /// List all available tools as JSON.
    fn list_tools(&self) -> Result<String, String>;
    /// Query memory and return matching entries as JSON.
    fn read_memory(&self, query: &str, limit: u32) -> Result<String, String>;
    /// Write a key-value pair to memory.
    fn write_memory(&self, key: &str, value: &str) -> Result<bool, String>;
    /// Read a value from script-scoped storage.
    fn read_storage(&self, key: &str) -> Result<String, String>;
    /// Write a value to script-scoped storage.
    fn write_storage(&self, key: &str, value: &str) -> Result<bool, String>;
    /// Send a prompt to the configured LLM and return the response.
    fn model_chat(&self, prompt: &str) -> Result<String, String>;
    /// Return the agent status as JSON.
    fn agent_status(&self) -> Result<String, String>;
    /// Return recent events as JSON.
    fn recent_events(&self, limit: u32) -> Result<String, String>;
    /// Return the ABI version this host implements.
    fn abi_version(&self) -> &str {
        ABI_VERSION
    }
}

/// Adapts a [`ScriptHost`] into a [`PluginHost`].
pub struct ScriptHostPluginAdapter {
    host: Arc<dyn ScriptHost>,
}

impl ScriptHostPluginAdapter {
    /// Wrap a ScriptHost as a PluginHost.
    pub fn new(host: Arc<dyn ScriptHost>) -> Self {
        Self { host }
    }

    fn call_str(&self, op: ScriptOperation, args: serde_json::Value) -> Result<String, String> {
        self.host
            .call(op, args)
            .map(|v| v.to_display_string())
            .map_err(|e| e.to_string())
    }
}

impl PluginHost for ScriptHostPluginAdapter {
    fn invoke_tool(&self, name: &str, args_json: &str) -> Result<String, String> {
        self.call_str(
            ScriptOperation::InvokeTool,
            serde_json::json!({"name": name, "args": args_json}),
        )
    }

    fn list_tools(&self) -> Result<String, String> {
        self.call_str(ScriptOperation::ListTools, serde_json::Value::Null)
    }

    fn read_memory(&self, query: &str, limit: u32) -> Result<String, String> {
        self.call_str(
            ScriptOperation::RecallMemory,
            serde_json::json!({"query": query, "limit": limit}),
        )
    }

    fn write_memory(&self, _key: &str, _value: &str) -> Result<bool, String> {
        Err(ScriptError::HostError {
            operation: "write_memory".to_string(),
            detail: "write_memory is not yet implemented; use storage_write instead".to_string(),
        }
        .to_string())
    }

    fn read_storage(&self, key: &str) -> Result<String, String> {
        self.call_str(ScriptOperation::ReadStorage, serde_json::json!({"key": key}))
    }

    fn write_storage(&self, key: &str, value: &str) -> Result<bool, String> {
        match self
            .host
            .call(ScriptOperation::WriteStorage, serde_json::json!({"key": key, "value": value}))
        {
            Ok(_) => Ok(true),
            Err(e) => Err(e.to_string()),
        }
    }

    fn model_chat(&self, prompt: &str) -> Result<String, String> {
        self.call_str(
            ScriptOperation::SendMessage,
            serde_json::json!({"message": prompt}),
        )
    }

    fn agent_status(&self) -> Result<String, String> {
        self.call_str(ScriptOperation::Status, serde_json::Value::Null)
    }

    fn recent_events(&self, limit: u32) -> Result<String, String> {
        self.call_str(ScriptOperation::RecentEvents, serde_json::json!({"limit": limit}))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    struct MockHost;

    impl ScriptHost for MockHost {
        fn call(
            &self,
            op: ScriptOperation,
            _args: serde_json::Value,
        ) -> Result<ScriptValue, ScriptError> {
            match op {
                ScriptOperation::Status => {
                    Ok(ScriptValue::String(r#"{"running":true}"#.to_string()))
                }
                ScriptOperation::ListTools => Ok(ScriptValue::String("[]".to_string())),
                _ => Err(ScriptError::HostError {
                    operation: op.display_name().to_string(),
                    detail: "mock".to_string(),
                }),
            }
        }
    }

    #[test]
    fn abi_version_is_0_2_0() {
        assert_eq!(ABI_VERSION, "0.2.0");
    }

    #[test]
    fn adapter_delegates_to_script_host() {
        let adapter = ScriptHostPluginAdapter::new(Arc::new(MockHost));
        let status = adapter.agent_status().unwrap();
        assert!(status.contains("running"));
        assert_eq!(adapter.list_tools().unwrap(), "[]");
    }

    #[test]
    fn adapter_propagates_errors() {
        let adapter = ScriptHostPluginAdapter::new(Arc::new(MockHost));
        assert!(adapter.model_chat("hello").is_err());
    }
}
