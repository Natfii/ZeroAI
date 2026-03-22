// Copyright (c) 2026 @Natfii. All rights reserved.

//! [`ScriptHost`] implementation for the agent `eval_script` tool.
//!
//! Routes `SendMessage` to the on-device Nano provider instead of the
//! user's configured cloud provider, explicitly denies `SendVision`
//! (Nano is text-only), and skips the dangerous-capability approval gate
//! (which uses `blocking_recv` and would deadlock inside an async context).

use zeroclaw::scripting::{ScriptError, ScriptHost, ScriptOperation, ScriptValue};

/// A [`ScriptHost`] that routes LLM calls to on-device Nano and delegates
/// all other operations to [`dispatch_common_operation`](crate::repl::dispatch_common_operation).
///
/// Unlike [`FfiScriptHost`](crate::repl), this host:
/// - Does **not** call `require_dangerous_capability_approval()`, avoiding
///   the `blocking_recv()` deadlock in async contexts.
/// - Routes `SendMessage` through
///   [`send_message_routed_inner`](crate::runtime::send_message_routed_inner)
///   with the `"nano"` route hint so inference stays on-device.
/// - Explicitly denies `SendVision` (Nano is text-only).
///
/// # Panics
///
/// [`AgentScriptHost::call`] for `SendMessage` operations calls
/// [`send_message_routed_inner`](crate::runtime::send_message_routed_inner)
/// which uses [`tokio::runtime::Handle::block_on`].
/// This must only be called from a `spawn_blocking` thread, never from within
/// an async task on the main runtime.
pub(crate) struct AgentScriptHost {
    /// Whether the on-device Nano provider is available for inference.
    nano_available: bool,
}

impl AgentScriptHost {
    /// Creates a new agent script host.
    ///
    /// When `nano_available` is `false`, `SendMessage` and `SendVision`
    /// operations return [`ScriptError::CapabilityDenied`] instead of
    /// attempting inference.
    pub(crate) fn new(nano_available: bool) -> Self {
        Self { nano_available }
    }
}

impl ScriptHost for AgentScriptHost {
    fn call(
        &self,
        operation: ScriptOperation,
        args: serde_json::Value,
    ) -> Result<ScriptValue, ScriptError> {
        match operation {
            ScriptOperation::SendMessage => {
                if !self.nano_available {
                    return Err(ScriptError::CapabilityDenied {
                        operation: operation.display_name().to_string(),
                        capability: "model.chat".to_string(),
                    });
                }
                let message = crate::repl::string_arg(&args, "message")?;
                let result = crate::runtime::send_message_routed_inner(
                    message,
                    "nano".to_string(),
                )
                .map_err(|e| ScriptError::HostError {
                    operation: operation.display_name().to_string(),
                    detail: e.to_string(),
                })?;
                Ok(ScriptValue::String(result))
            }
            ScriptOperation::SendVision => {
                // Nano is text-only; vision requests are not supported.
                Err(ScriptError::CapabilityDenied {
                    operation: operation.display_name().to_string(),
                    capability: "model.vision".to_string(),
                })
            }
            other => crate::repl::dispatch_common_operation(other, args),
        }
    }
}
