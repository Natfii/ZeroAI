/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

//! Direct provider streaming without the full agent loop.
//!
//! This module exposes a simple streaming interface that bypasses the
//! multi-turn agent session machinery in [`crate::session`]. It sends a
//! single user message to the configured provider and streams the
//! response back through an [`FfiStreamListener`] callback.
//!
//! Use [`crate::session`] for the full agent loop with tool execution;
//! use this module for lightweight, fire-and-forget streaming.

use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};

use futures_util::StreamExt;
use zeroclaw::providers::traits::StreamOptions;

use crate::error::FfiError;
use crate::runtime::with_daemon_config;

/// Global cancellation flag for the current streaming operation.
///
/// Set to `true` by [`cancel_streaming_inner`] and checked between
/// stream chunks. Reset to `false` at the start of each new stream.
static CANCEL_FLAG: AtomicBool = AtomicBool::new(false);

/// Callback interface for receiving streaming chunks from the provider.
///
/// Implemented on the Kotlin side by `StreamingBridge`. UniFFI generates
/// the JNI bridge automatically from this trait definition.
#[uniffi::export(callback_interface)]
pub trait FfiStreamListener: Send + Sync {
    /// Called with each thinking/reasoning token chunk.
    fn on_thinking_chunk(&self, text: String);

    /// Called with each response content token chunk.
    fn on_response_chunk(&self, text: String);

    /// Called when the stream completes successfully.
    fn on_complete(&self, full_response: String);

    /// Called when an error occurs during streaming.
    fn on_error(&self, error: String);
}

/// Sends a message to the configured provider and streams the response.
///
/// Reads the daemon configuration to build a provider, then opens a
/// streaming chat request. Each chunk is forwarded to the `listener`
/// callback. The cancel flag is checked between chunks.
///
/// Config field mappings (verified against `zeroclaw::Config` in
/// `zeroclaw/src/config/schema.rs`):
/// - `config.default_provider` (`Option<String>`) -> provider factory name
/// - `config.default_model` (`Option<String>`) -> model string
/// - `config.default_temperature` (`f64`) -> temperature float
/// - `config.api_key` (`Option<String>`) -> API key for provider creation
///
/// Provider factory: `zeroclaw::providers::create_provider(name: &str, api_key: Option<&str>)`
/// from `zeroclaw/src/providers/mod.rs:909`.
pub(crate) fn send_message_streaming_inner(
    message: String,
    listener: Arc<dyn FfiStreamListener>,
) -> Result<(), FfiError> {
    CANCEL_FLAG.store(false, Ordering::SeqCst);

    let rt = crate::runtime::get_or_create_runtime()?;

    let (model, temperature, provider) = with_daemon_config(|config| {
        let model = config
            .default_model
            .clone()
            .unwrap_or_else(|| "anthropic/claude-sonnet-4-6".to_string());
        let temperature = config.default_temperature;
        let provider_name = config
            .default_provider
            .clone()
            .unwrap_or_else(|| "openrouter".to_string());
        let api_key = config.api_key.as_deref();

        let prov = zeroclaw::providers::create_provider(&provider_name, api_key);

        (model, temperature, prov)
    })?;

    let provider = provider.map_err(|e| FfiError::SpawnError {
        detail: format!("Failed to create provider: {e}"),
    })?;

    rt.block_on(async {
        let options = StreamOptions::default();
        let mut stream =
            provider.stream_chat_with_system(None, &message, &model, temperature, options);

        let mut full_response = String::new();

        while let Some(result) = stream.next().await {
            if CANCEL_FLAG.load(Ordering::SeqCst) {
                listener.on_error("Request cancelled".to_string());
                return Ok(());
            }

            match result {
                Ok(chunk) => {
                    if !chunk.delta.is_empty() {
                        full_response.push_str(&chunk.delta);
                        listener.on_response_chunk(chunk.delta);
                    }
                    if chunk.is_final {
                        break;
                    }
                }
                Err(e) => {
                    listener.on_error(format!("{e}"));
                    return Ok(());
                }
            }
        }

        listener.on_complete(full_response);
        Ok(())
    })
}

/// Signals the current streaming operation to cancel.
///
/// Sets the cancel flag that is checked between stream chunks.
///
/// Returns `Result` for consistency with the `catch_unwind` wrapper in
/// `lib.rs` — the caller expects `Result<(), FfiError>`.
#[allow(clippy::unnecessary_wraps)]
pub(crate) fn cancel_streaming_inner() -> Result<(), FfiError> {
    CANCEL_FLAG.store(true, Ordering::SeqCst);
    Ok(())
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    struct MockListener {
        chunks: std::sync::Mutex<Vec<String>>,
    }

    impl MockListener {
        fn new() -> Self {
            Self {
                chunks: std::sync::Mutex::new(Vec::new()),
            }
        }
    }

    impl FfiStreamListener for MockListener {
        fn on_thinking_chunk(&self, text: String) {
            self.chunks.lock().unwrap().push(format!("think:{text}"));
        }

        fn on_response_chunk(&self, text: String) {
            self.chunks.lock().unwrap().push(format!("resp:{text}"));
        }

        fn on_complete(&self, full_response: String) {
            self.chunks
                .lock()
                .unwrap()
                .push(format!("done:{full_response}"));
        }

        fn on_error(&self, error: String) {
            self.chunks.lock().unwrap().push(format!("err:{error}"));
        }
    }

    #[test]
    fn test_cancel_flag_starts_false() {
        CANCEL_FLAG.store(false, Ordering::SeqCst);
        assert!(!CANCEL_FLAG.load(Ordering::SeqCst));
    }

    #[test]
    fn test_cancel_sets_flag() {
        CANCEL_FLAG.store(false, Ordering::SeqCst);
        cancel_streaming_inner().unwrap();
        assert!(CANCEL_FLAG.load(Ordering::SeqCst));
    }

    #[test]
    fn test_mock_listener_collects_events() {
        let listener = MockListener::new();
        listener.on_thinking_chunk("hmm".to_string());
        listener.on_response_chunk("hello".to_string());
        listener.on_complete("hello world".to_string());

        let chunks = listener.chunks.lock().unwrap();
        assert_eq!(chunks.len(), 3);
        assert_eq!(chunks[0], "think:hmm");
        assert_eq!(chunks[1], "resp:hello");
        assert_eq!(chunks[2], "done:hello world");
    }

    #[test]
    fn test_streaming_without_daemon_returns_error() {
        let listener = Arc::new(MockListener::new());
        let result = send_message_streaming_inner("test".to_string(), listener);
        assert!(result.is_err());
    }
}
