/*
 * Copyright 2026 @Natfii
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

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};

use futures_util::StreamExt;
use zeroclaw::providers::traits::StreamOptions;

use crate::error::FfiError;
use crate::runtime::{try_with_daemon_config, with_daemon_config};
use crate::session::extract_thinking_from_text;

/// Per-stream cancellation token.
///
/// Stored in a mutex so that [`cancel_streaming_inner`] cancels whichever
/// stream is currently active, and a new stream replaces the token atomically.
static STREAM_CANCEL: Mutex<Option<Arc<AtomicBool>>> = Mutex::new(None);

/// Acquires the stream cancel mutex with poison recovery.
fn lock_stream_cancel() -> std::sync::MutexGuard<'static, Option<Arc<AtomicBool>>> {
    STREAM_CANCEL.lock().unwrap_or_else(|e| {
        tracing::warn!("Stream cancel mutex was poisoned; recovering: {e}");
        e.into_inner()
    })
}

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
/// - `config.api_url` (`Option<String>`) -> optional custom endpoint URL
/// - `config.routing` ([`zeroclaw::config::RoutingConfig`]) -> tier routing overrides
/// - `config.reliability` ([`zeroclaw::config::ReliabilityConfig`]) -> retry/fallback settings
///
/// Provider factory: `zeroclaw::providers::create_resilient_model_provider_with_options`
/// with message classification via `zeroai::router::classify`.
crate::ffi_export!(
    /// Sends a streaming message directly to the configured provider.
    ///
    /// Bypasses the agent loop and calls the provider's streaming API.
    /// Chunks are classified as thinking or response content and
    /// delivered to the listener callback in real time.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::EstopEngaged`] when emergency stop is
    /// active, [`crate::FfiError::ConfigError`] for oversized messages,
    /// [`crate::FfiError::StateError`] if the daemon is not running,
    /// [`crate::FfiError::SpawnError`] if provider creation or streaming
    /// fails, or [`crate::FfiError::InternalPanic`] if native code panics.
    fn send_message_streaming(
        message: String,
        listener: Box<dyn FfiStreamListener>
    ) -> () = send_message_streaming_boxed
);

pub(crate) fn send_message_streaming_boxed(
    message: String,
    listener: Box<dyn FfiStreamListener>,
) -> Result<(), FfiError> {
    if crate::estop::is_engaged() {
        return Err(FfiError::EstopEngaged {
            detail: "Emergency stop is engaged. Resume before sending messages.".into(),
        });
    }
    send_message_streaming_inner(message, Arc::from(listener))
}

pub(crate) fn send_message_streaming_inner(
    message: String,
    listener: Arc<dyn FfiStreamListener>,
) -> Result<(), FfiError> {
    let cancel_token = Arc::new(AtomicBool::new(false));
    *lock_stream_cancel() = Some(Arc::clone(&cancel_token));

    let rt = crate::runtime::get_or_create_runtime()?;

    // Upstream removed the flat `Config.effective_model`,
    // `effective_provider`, `default_temperature`, `api_key`, `api_url`,
    // and `routing` fields. Provider routing now lives under
    // `[providers.models.<type>.<alias>]` blocks; the provider factory
    // reads its own auth on construction. Temperature is per-provider
    // on `ModelProviderConfig.temperature`.
    let (model, temperature, provider) = try_with_daemon_config(|config| {
        let model = config.resolve_default_model().ok_or_else(|| FfiError::ConfigError {
            detail: crate::runtime::NO_MODEL_CONFIGURED.into(),
        })?;
        let provider_name = crate::runtime::effective_model_provider_type(config)?;
        let temperature: f64 = config
            .first_model_provider_alias()
            .and_then(|s| s.as_str().split_once('.').map(|(_, a)| a.to_string()))
            .and_then(|alias| {
                config.providers.models.find(&provider_name, &alias).and_then(|p| p.temperature)
            })
            .unwrap_or(0.7);

        let _ = &message; // router classification dropped during port

        let prov: anyhow::Result<Box<dyn zeroclaw::providers::ModelProvider>> =
            zeroclaw::providers::create_resilient_model_provider_with_options(
                &provider_name,
                None,
                None,
                &config.reliability,
                &zeroclaw::providers::ModelProviderRuntimeOptions::default(),
            );

        Ok((model, temperature, prov))
    })?;

    let provider = provider.map_err(|e| FfiError::SpawnError {
        detail: format!("Failed to create provider: {e}"),
    })?;

    rt.block_on(async {
        let options = StreamOptions::default();
        let mut stream =
            provider.stream_chat_with_system(None, &message, &model, Some(temperature), options);

        let mut full_response = String::new();

        while let Some(result) = stream.next().await {
            if cancel_token.load(Ordering::SeqCst) {
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

        // Extract thinking/reasoning blocks before delivering the final response.
        // Models like Qwen 3.5 and DeepSeek-R1 leak <think>...</think> tags even
        // with enable_thinking=false.  Route extracted content to the thinking
        // card and deliver only clean text as the completed response.
        let (clean_text, thinking) = extract_thinking_from_text(&full_response);
        if !thinking.is_empty() {
            listener.on_thinking_chunk(thinking);
        }
        listener.on_complete(clean_text);
        Ok(())
    })
}

/// Signals the current streaming operation to cancel.
///
/// Sets the per-stream cancellation token (if a stream is active) so that
/// the next chunk-polling iteration will observe the flag and stop.
///
/// Returns `Result` for consistency with the `catch_unwind` wrapper in
/// `lib.rs` — the caller expects `Result<(), FfiError>`.
crate::ffi_export!(
    /// Signals the current streaming operation to cancel.
    ///
    /// Sets an internal cancel flag that is checked between stream chunks.
    /// The streaming callback will receive an `on_error("Request cancelled")`
    /// call at the next chunk boundary.
    ///
    /// Safe to call at any time, including when no streaming is in progress.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::InternalPanic`] if native code panics.
    fn cancel_streaming() -> () = cancel_streaming_inner
);

#[allow(clippy::unnecessary_wraps)]
pub(crate) fn cancel_streaming_inner() -> Result<(), FfiError> {
    if let Some(token) = lock_stream_cancel().as_ref() {
        token.store(true, Ordering::SeqCst);
    }
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
    fn test_cancel_sets_active_token() {
        // Create a token as if a stream were active.
        let token = Arc::new(AtomicBool::new(false));
        *lock_stream_cancel() = Some(Arc::clone(&token));

        cancel_streaming_inner().unwrap();
        assert!(token.load(Ordering::SeqCst));

        // Cleanup.
        *lock_stream_cancel() = None;
    }

    #[test]
    fn test_cancel_without_active_stream_is_noop() {
        *lock_stream_cancel() = None;
        let result = cancel_streaming_inner();
        assert!(result.is_ok());
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
    fn test_extract_thinking_applied_to_complete() {
        // Simulate what would happen when streaming delivers a response
        // containing thinking tags — the extraction should separate them.
        let raw = "<think>internal reasoning</think>The answer is 42.";
        let (clean, thinking) = crate::session::extract_thinking_from_text(raw);
        assert_eq!(clean, "The answer is 42.");
        assert_eq!(thinking, "internal reasoning");

        // Verify listener routing: thinking goes to on_thinking_chunk,
        // clean text goes to on_complete.
        let listener = MockListener::new();
        if !thinking.is_empty() {
            listener.on_thinking_chunk(thinking);
        }
        listener.on_complete(clean);

        let chunks = listener.chunks.lock().unwrap();
        assert_eq!(chunks.len(), 2);
        assert_eq!(chunks[0], "think:internal reasoning");
        assert_eq!(chunks[1], "done:The answer is 42.");
    }

    #[test]
    fn test_no_thinking_tags_passes_through() {
        let raw = "Just a normal response.";
        let (clean, thinking) = crate::session::extract_thinking_from_text(raw);
        assert_eq!(clean, "Just a normal response.");
        assert!(thinking.is_empty());

        let listener = MockListener::new();
        if !thinking.is_empty() {
            listener.on_thinking_chunk(thinking);
        }
        listener.on_complete(clean);

        let chunks = listener.chunks.lock().unwrap();
        assert_eq!(chunks.len(), 1);
        assert_eq!(chunks[0], "done:Just a normal response.");
    }

    #[test]
    fn test_streaming_without_daemon_returns_error() {
        let listener = Arc::new(MockListener::new());
        let result = send_message_streaming_inner("test".to_string(), listener);
        assert!(result.is_err());
    }
}
