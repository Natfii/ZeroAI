// Copyright (c) 2026 @Natfii. All rights reserved.

//! WebView-based page renderer callback interface.
//!
//! Provides a UniFFI callback interface so that Kotlin can register an
//! Android WebView renderer. When the Rust engine encounters a page that
//! requires JavaScript execution (e.g. Cloudflare challenge, SPA content),
//! it calls through this bridge to render the page in a headless WebView
//! on the Android side and return the resulting HTML.
//!
//! Follows the same global-slot pattern as [`crate::events::FfiEventListener`].

use std::sync::{Arc, Mutex, OnceLock};

use crate::error::FfiError;

/// Error returned by the [`WebRenderer`] callback when page rendering fails.
///
/// UniFFI 0.29 requires callback interface error types to be exported enum
/// errors (not plain `String`). Kotlin throws `WebRendererException.RenderException`
/// which UniFFI marshals back into this variant.
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum WebRendererError {
    /// The WebView failed to render the page (timeout, load error, JS failure, etc.).
    #[error("{reason}")]
    Render {
        /// Human-readable description of what went wrong.
        reason: String,
    },
}

/// Callback interface that Kotlin implements to render web pages.
///
/// The Kotlin side creates a headless `WebView`, loads the given URL,
/// waits for the page to settle, and returns the final DOM HTML.
/// Implementations must be thread-safe because [`render_page`](WebRenderer::render_page)
/// is called from a Rust background thread.
#[uniffi::export(callback_interface)]
pub trait WebRenderer: Send + Sync {
    /// Renders a URL in an Android WebView and returns the final page text.
    ///
    /// The implementation should load `url` in a headless WebView,
    /// wait up to `timeout_ms` milliseconds for the page to settle,
    /// then extract and return the visible text content.
    ///
    /// Returns `Ok(text)` on success or `Err(WebRendererError::Render { .. })`
    /// if the page failed to load, timed out, or the WebView is unavailable.
    fn render_page(&self, url: String, timeout_ms: u64) -> Result<String, WebRendererError>;
}

/// Global renderer slot.
static RENDERER: OnceLock<Mutex<Option<Arc<dyn WebRenderer>>>> = OnceLock::new();

/// Returns a reference to the renderer mutex, initialising on first access.
fn renderer_slot() -> &'static Mutex<Option<Arc<dyn WebRenderer>>> {
    RENDERER.get_or_init(|| Mutex::new(None))
}

/// Acquires the renderer mutex with poison recovery.
///
/// Uses the same `unwrap_or_else(|e| e.into_inner())` pattern as
/// [`crate::events`] to prevent permanent failure after a panic.
fn lock_renderer() -> std::sync::MutexGuard<'static, Option<Arc<dyn WebRenderer>>> {
    renderer_slot().lock().unwrap_or_else(|e| {
        tracing::warn!("WebRenderer mutex was poisoned; recovering: {e}");
        e.into_inner()
    })
}

/// Registers a Kotlin-side web renderer.
///
/// Only one renderer can be registered at a time. A new renderer replaces
/// the previous one. Accepts an [`Arc`] so the caller can convert from the
/// UniFFI `Box<dyn WebRenderer>` at the FFI boundary.
#[allow(clippy::unnecessary_wraps)]
pub(crate) fn register_web_renderer_inner(renderer: Arc<dyn WebRenderer>) -> Result<(), FfiError> {
    let mut slot = lock_renderer();
    *slot = Some(renderer);
    Ok(())
}

/// Unregisters the current web renderer.
///
/// After this call, [`try_render_page`] will return `None`.
#[allow(clippy::unnecessary_wraps)]
pub(crate) fn unregister_web_renderer_inner() -> Result<(), FfiError> {
    let mut slot = lock_renderer();
    *slot = None;
    Ok(())
}

/// Attempts to render a page using the registered WebView renderer.
///
/// Returns `None` if no renderer is registered. Otherwise, clones the
/// [`Arc`] (to avoid holding the mutex across the foreign callback) and
/// invokes [`WebRenderer::render_page`].
///
/// # Returns
///
/// - `None` — no renderer registered.
/// - `Some(Ok(text))` — page rendered successfully.
/// - `Some(Err(WebRendererError))` — renderer returned an error.
#[allow(dead_code)]
pub(crate) fn try_render_page(
    url: &str,
    timeout_ms: u64,
) -> Option<Result<String, WebRendererError>> {
    let maybe_renderer = lock_renderer().as_ref().map(Arc::clone);
    maybe_renderer.map(|renderer| renderer.render_page(url.to_owned(), timeout_ms))
}

/// Adapter that bridges the FFI [`WebRenderer`] callback to the engine's
/// [`WebViewFallback`] trait. Created at daemon startup and passed into
/// the tool registry.
pub(crate) struct FfiWebViewFallback;

impl zeroclaw::tools::web_fetch::WebViewFallback for FfiWebViewFallback {
    fn render_page(&self, url: &str, timeout_ms: u64) -> Result<String, String> {
        match try_render_page(url, timeout_ms) {
            Some(Ok(text)) => Ok(text),
            Some(Err(e)) => Err(e.to_string()),
            None => Err("No WebView renderer registered".to_string()),
        }
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;
    use std::sync::Arc;

    /// A test renderer that returns a fixed HTML string.
    struct EchoRenderer;

    impl WebRenderer for EchoRenderer {
        fn render_page(&self, url: String, _timeout_ms: u64) -> Result<String, WebRendererError> {
            Ok(format!("<html><body>Rendered: {url}</body></html>"))
        }
    }

    /// A test renderer that always returns an error.
    struct FailRenderer;

    impl WebRenderer for FailRenderer {
        fn render_page(&self, _url: String, _timeout_ms: u64) -> Result<String, WebRendererError> {
            Err(WebRendererError::Render {
                reason: "WebView unavailable".to_string(),
            })
        }
    }

    #[test]
    fn test_no_renderer_returns_none() {
        unregister_web_renderer_inner().unwrap();
        let result = try_render_page("https://example.com", 5000);
        assert!(
            result.is_none(),
            "expected None when no renderer registered"
        );
    }

    #[test]
    fn test_register_and_render() {
        let renderer: Arc<dyn WebRenderer> = Arc::new(EchoRenderer);
        register_web_renderer_inner(renderer).unwrap();

        let result = try_render_page("https://example.com", 5000);
        assert!(result.is_some(), "expected Some when renderer registered");
        let html = result.unwrap().unwrap();
        assert!(
            html.contains("Rendered: https://example.com"),
            "unexpected html: {html}"
        );

        unregister_web_renderer_inner().unwrap();
    }

    #[test]
    fn test_render_error_propagates() {
        let renderer: Arc<dyn WebRenderer> = Arc::new(FailRenderer);
        register_web_renderer_inner(renderer).unwrap();

        let result = try_render_page("https://example.com", 5000);
        assert!(result.is_some());
        let err = result.unwrap().unwrap_err();
        let msg = err.to_string();
        assert!(
            msg.contains("WebView unavailable"),
            "unexpected error: {msg}"
        );

        unregister_web_renderer_inner().unwrap();
    }

    #[test]
    fn test_unregister_clears_renderer() {
        let renderer: Arc<dyn WebRenderer> = Arc::new(EchoRenderer);
        register_web_renderer_inner(renderer).unwrap();
        unregister_web_renderer_inner().unwrap();

        let result = try_render_page("https://example.com", 5000);
        assert!(result.is_none(), "expected None after unregister");
    }

    #[test]
    fn test_replace_renderer() {
        let renderer1: Arc<dyn WebRenderer> = Arc::new(EchoRenderer);
        register_web_renderer_inner(renderer1).unwrap();

        let renderer2: Arc<dyn WebRenderer> = Arc::new(FailRenderer);
        register_web_renderer_inner(renderer2).unwrap();

        let result = try_render_page("https://example.com", 5000);
        assert!(result.is_some());
        assert!(
            result.unwrap().is_err(),
            "second renderer (FailRenderer) should be active"
        );

        unregister_web_renderer_inner().unwrap();
    }
}
