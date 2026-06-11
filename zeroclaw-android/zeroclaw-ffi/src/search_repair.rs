// Copyright (c) 2026 @Natfii. All rights reserved.

//! On-device search-repair bridge.
//!
//! Lets Kotlin supply a local completion backend (Gemini Nano via ML Kit
//! GenAI) for the metasearch self-repair prompt. The engine's repair
//! ladder tries the registered handler before escalating to the
//! configured cloud provider; its answer still has to pass the engine's
//! model-free validation gate, so a weak or unavailable on-device model
//! can never adopt a broken engine spec.

use crate::FfiError;
use std::sync::Arc;
use zeroclaw_tools::metasearch::repair::{RepairCompleter, set_repair_completer};

/// Callback interface implemented in Kotlin for on-device repair
/// completions.
#[uniffi::export(callback_interface)]
pub trait SearchRepairHandler: Send + Sync {
    /// Runs the repair prompt on the on-device model and returns its raw
    /// text answer.
    ///
    /// Called from a Rust blocking-pool thread, never the main thread; the
    /// implementation may block for tens of seconds on first model load.
    /// Implementations should fail fast (rather than trigger a model
    /// download) when the on-device model is not ready.
    fn complete(&self, prompt: String) -> Result<String, FfiError>;
}

/// Adapter exposing the Kotlin handler to the engine's repair ladder.
struct HandlerCompleter {
    handler: Box<dyn SearchRepairHandler>,
}

impl RepairCompleter for HandlerCompleter {
    fn complete(&self, prompt: String) -> anyhow::Result<String> {
        self.handler
            .complete(prompt)
            .map_err(|e| anyhow::Error::msg(format!("on-device completion failed: {e}")))
    }
}

/// Registers the Kotlin-side on-device repair handler with the engine.
#[uniffi::export]
pub fn register_search_repair_handler(handler: Box<dyn SearchRepairHandler>) {
    set_repair_completer(Some(Arc::new(HandlerCompleter { handler })));
}

/// Unregisters the on-device repair handler (e.g. at daemon shutdown).
#[uniffi::export]
pub fn unregister_search_repair_handler() {
    set_repair_completer(None);
}

#[cfg(test)]
mod tests {
    use super::*;

    struct EchoHandler;

    impl SearchRepairHandler for EchoHandler {
        fn complete(&self, prompt: String) -> Result<String, FfiError> {
            Ok(format!("echo: {prompt}"))
        }
    }

    struct FailingHandler;

    impl SearchRepairHandler for FailingHandler {
        fn complete(&self, _prompt: String) -> Result<String, FfiError> {
            Err(FfiError::StateError {
                detail: "model unavailable".into(),
            })
        }
    }

    #[test]
    fn handler_completer_passes_success_through() {
        let adapter = HandlerCompleter {
            handler: Box::new(EchoHandler),
        };
        let answer = adapter
            .complete("prompt".into())
            .expect("echo handler must succeed");
        assert_eq!(answer, "echo: prompt");
    }

    #[test]
    fn handler_completer_maps_ffi_errors_to_anyhow() {
        let adapter = HandlerCompleter {
            handler: Box::new(FailingHandler),
        };
        let err = adapter
            .complete("prompt".into())
            .expect_err("failing handler must error");
        assert!(err.to_string().contains("on-device completion failed"));
        assert!(err.to_string().contains("model unavailable"));
    }
}
