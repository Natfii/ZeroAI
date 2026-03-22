// Copyright (c) 2026 @Natfii. All rights reserved.

#![allow(clippy::unnecessary_literal_bound)]

//! Agent eval_script tool — sandboxed Rhai execution for the agent loop.

use async_trait::async_trait;
use serde_json::json;
use std::sync::Arc;
use zeroclaw::scripting::{
    RhaiScriptRuntime, ScriptError, ScriptHost, ScriptLimits, ScriptManifest, ScriptRuntimeKind,
    build_agent_capabilities,
};
use zeroclaw::tools::traits::{Tool, ToolResult};

/// Maximum output size before truncation (16 KiB).
const MAX_OUTPUT_BYTES: usize = 16 * 1024;

/// Evaluates inline Rhai scripts in a sandboxed environment with a fixed
/// safe capability set. Designed for agent tool-use loops where the LLM
/// needs batch computation and multi-tool composition.
///
/// Lives in the FFI crate (not core) because it needs [`AgentScriptHost`]
/// which implements Nano routing and is FFI-crate-specific.
pub(crate) struct EvalScriptTool;

impl EvalScriptTool {
    /// Creates a new stateless eval_script tool instance.
    pub(crate) fn new() -> Self {
        Self
    }
}

#[async_trait]
#[allow(clippy::too_many_lines)]
impl Tool for EvalScriptTool {
    fn name(&self) -> &str {
        "eval_script"
    }

    fn description(&self) -> &str {
        "Execute a Rhai script in a sandboxed environment. Use for batch data \
         processing, multi-step computations, JSON manipulation, and composing \
         multiple operations (memory, storage, tools) into a single call. Has \
         access to: memory read/write, storage read/write, tool listing/invocation, \
         cost/event reading, and on-device LLM (when available). 10M operation \
         budget, 30s timeout. Returns JSON. Prefer this over shell for non-OS tasks."
    }

    fn parameters_schema(&self) -> serde_json::Value {
        json!({
            "type": "object",
            "properties": {
                "code": {
                    "type": "string",
                    "description": "Rhai source code to execute"
                }
            },
            "required": ["code"]
        })
    }

    /// Executes the provided Rhai script in a sandboxed environment.
    ///
    /// Nano availability is snapshotted at invocation time via
    /// [`is_nano_available_inner`](crate::runtime::is_nano_available_inner)
    /// (Acquire load). If Nano becomes available or unavailable mid-execution,
    /// the change is not reflected until the next invocation.
    async fn execute(&self, args: serde_json::Value) -> anyhow::Result<ToolResult> {
        let code = args
            .get("code")
            .and_then(|v| v.as_str())
            .ok_or_else(|| anyhow::anyhow!("Missing 'code' parameter"))?;

        // Reject empty/whitespace-only
        if code.trim().is_empty() {
            return Ok(ToolResult {
                success: false,
                output: String::new(),
                error: Some("SyntaxError: script is empty".into()),
            });
        }

        // Size check
        if code.len() > 128 * 1024 {
            return Ok(ToolResult {
                success: false,
                output: String::new(),
                error: Some(format!(
                    "SyntaxError: script too large ({} bytes, max 128KB)",
                    code.len()
                )),
            });
        }

        // Check Nano availability PER-INVOCATION (spec requirement).
        // Build host and manifest fresh each call.
        let nano_available = crate::runtime::is_nano_available_inner();
        let caps = build_agent_capabilities(nano_available);
        let cap_count = caps.len();

        tracing::info!(
            nano_available = nano_available,
            "[eval_script] Nano available: {} — model.chat {}",
            nano_available,
            if nano_available {
                "granted"
            } else {
                "withheld"
            }
        );
        tracing::debug!(
            code_bytes = code.len(),
            capabilities = cap_count,
            "[eval_script] Executing: {}...",
            &code[..code.len().min(80)]
        );

        let manifest = ScriptManifest {
            name: "agent".to_string(),
            version: "0.0.0".to_string(),
            runtime: ScriptRuntimeKind::Rhai,
            script_path: None,
            capabilities: caps,
            explicit_capabilities: true,
            limits: ScriptLimits::for_agent_eval(),
            ..Default::default()
        };

        let start = std::time::Instant::now();

        // Build AgentScriptHost fresh each invocation (Nano flag may change).
        let host: Arc<dyn ScriptHost> = Arc::new(crate::agent_script_host::AgentScriptHost::new(
            nano_available,
        ));
        let engine = RhaiScriptRuntime::with_limits(host, manifest.limits.clone());

        // SAFETY (threading): Rhai host functions (dispatch_common_operation,
        // send_message_routed_inner) use Handle::block_on() and
        // script_tool_runtime().block_on(). spawn_blocking places this on a
        // dedicated blocking thread pool separate from the async runtime's
        // worker threads, so block_on will not deadlock.
        //
        // The agent loop awaits execute() on a tokio worker thread.
        // RhaiScriptRuntime::eval_script() is synchronous. Calling block_on
        // from a tokio worker panics — spawn_blocking avoids this.
        let source_owned = code.to_string();
        let result =
            tokio::task::spawn_blocking(move || engine.eval_script(&source_owned, Some(manifest)))
                .await
                .map_err(|e| anyhow::anyhow!("eval_script task panicked: {e}"))?;

        let duration_ms = start.elapsed().as_millis();

        match result {
            Ok(output) => {
                let full_bytes = output.len();
                let truncated = full_bytes > MAX_OUTPUT_BYTES;
                let display = if truncated {
                    let boundary = output.floor_char_boundary(MAX_OUTPUT_BYTES);
                    format!(
                        "{}...[truncated, {} bytes total]",
                        &output[..boundary],
                        full_bytes
                    )
                } else {
                    output
                };

                tracing::debug!(
                    duration_ms = duration_ms,
                    full_bytes = full_bytes,
                    truncated = truncated,
                    "[eval_script] Completed: success"
                );

                Ok(ToolResult {
                    success: true,
                    output: display,
                    error: None,
                })
            }
            Err(err) => {
                let error_str = categorize_script_error(&err);
                tracing::debug!(
                    duration_ms = duration_ms,
                    "[eval_script] Completed: {}",
                    error_str.split(':').next().unwrap_or("error")
                );
                Ok(ToolResult {
                    success: false,
                    output: String::new(),
                    error: Some(error_str),
                })
            }
        }
    }
}

/// Map [`ScriptError`] variants to LLM-facing error categories.
///
/// [`ScriptError`] has 5 variants: `InvalidArgument`, `ValidationError`,
/// `CapabilityDenied`, `HostError`, `InternalState`. Rhai compile errors
/// come through as `ValidationError`. Timeouts and op-limits come through
/// as `HostError` with descriptive strings from Rhai's `EvalAltResult`.
fn categorize_script_error(err: &ScriptError) -> String {
    match err {
        ScriptError::ValidationError { detail } => format!("SyntaxError: {detail}"),
        ScriptError::CapabilityDenied {
            operation,
            capability,
        } => format!("CapabilityDenied: {operation} requires {capability}"),
        ScriptError::HostError { detail, .. } => {
            // Detect timeout and op-limit from Rhai error strings
            let lower = detail.to_lowercase();
            if lower.contains("timed out") || lower.contains("timeout") {
                "Timeout: script exceeded 30s wall-clock limit".to_string()
            } else if lower.contains("operations") && lower.contains("limit") {
                "OperationLimit: exceeded 10000000 operations".to_string()
            } else {
                format!("RuntimeError: {detail}")
            }
        }
        ScriptError::InvalidArgument { detail } => {
            format!("RuntimeError: {detail}")
        }
        ScriptError::InternalState { detail } => {
            format!("RuntimeError: internal error: {detail}")
        }
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    fn make_tool() -> EvalScriptTool {
        EvalScriptTool::new()
    }

    #[tokio::test]
    async fn empty_script_returns_syntax_error() {
        let tool = make_tool();
        let args = serde_json::json!({ "code": "" });
        let result = tool.execute(args).await.expect("execute failed");
        assert!(!result.success);
        assert!(result.error.as_ref().unwrap().starts_with("SyntaxError:"));
    }

    #[tokio::test]
    async fn whitespace_only_returns_syntax_error() {
        let tool = make_tool();
        let args = serde_json::json!({ "code": "   \n\t  " });
        let result = tool.execute(args).await.expect("execute failed");
        assert!(!result.success);
        assert!(result.error.as_ref().unwrap().starts_with("SyntaxError:"));
    }

    #[tokio::test]
    async fn missing_code_param_returns_error() {
        let tool = make_tool();
        let args = serde_json::json!({});
        let result = tool.execute(args).await;
        assert!(result.is_err() || !result.unwrap().success);
    }

    #[tokio::test]
    async fn simple_expression_returns_json() {
        let tool = make_tool();
        let args = serde_json::json!({ "code": "40 + 2" });
        let result = tool.execute(args).await.expect("execute failed");
        assert!(result.success);
        assert_eq!(result.output, "42");
    }

    #[tokio::test]
    async fn syntax_error_returns_structured_error() {
        let tool = make_tool();
        let args = serde_json::json!({ "code": "let x = ;" });
        let result = tool.execute(args).await.expect("execute failed");
        assert!(!result.success);
        assert!(result.error.as_ref().unwrap().starts_with("SyntaxError:"));
    }

    #[tokio::test]
    async fn oversized_script_rejected() {
        let tool = make_tool();
        let big_code = "x".repeat(129 * 1024);
        let args = serde_json::json!({ "code": big_code });
        let result = tool.execute(args).await.expect("execute failed");
        assert!(!result.success);
        assert!(result.error.as_ref().unwrap().contains("too large"));
    }

    #[tokio::test]
    async fn unit_return_serializes_as_ok() {
        let tool = make_tool();
        let args = serde_json::json!({ "code": "let x = 1;" });
        let result = tool.execute(args).await.expect("execute failed");
        assert!(result.success);
        // Unit () returns are converted to "ok" by dynamic_to_string
        assert_eq!(result.output, "ok");
    }

    #[test]
    fn tool_name_is_eval_script() {
        let tool = make_tool();
        assert_eq!(tool.name(), "eval_script");
    }

    #[test]
    fn parameters_schema_has_code_field() {
        let tool = make_tool();
        let schema = tool.parameters_schema();
        assert!(schema["properties"]["code"].is_object());
        let required = schema["required"].as_array().unwrap();
        assert!(required.iter().any(|v| v == "code"));
    }

    #[tokio::test]
    async fn output_truncated_at_16kib() {
        let tool = make_tool();
        // Build a ~32KB string (under Rhai's 64KB max_string_size).
        // Start with "x" (1 byte), double 15 times = 2^15 = 32768 bytes.
        let args = serde_json::json!({
            "code": r#"let s = "x"; for i in 0..15 { s += s; } s"#
        });
        let result = tool.execute(args).await.expect("execute failed");
        assert!(result.success);
        assert!(result.output.len() <= 16 * 1024 + 100); // 16KiB + suffix
        assert!(result.output.contains("truncated"));
    }
}
