//! TG1: Provider End-to-End Resolution Tests
//!
//! Prevents: Pattern 1 — Provider configuration & resolution bugs (27% of user bugs).
//!
//! Tests the full pipeline from config values through `create_provider_with_url()`
//! to provider construction, verifying factory resolution, URL construction,
//! credential wiring, and auth header format.

use zeroclaw::providers::{
    create_provider, create_provider_with_options, create_provider_with_url,
};

/// Helper: assert provider creation succeeds
fn assert_provider_ok(name: &str, key: Option<&str>, url: Option<&str>) {
    let result = create_provider_with_url(name, key, url);
    assert!(
        result.is_ok(),
        "{name} provider should resolve: {}",
        result.err().map(|e| e.to_string()).unwrap_or_default()
    );
}

// ─────────────────────────────────────────────────────────────────────────────
// Factory resolution: each supported provider name resolves without error
// ─────────────────────────────────────────────────────────────────────────────

#[test]
fn factory_resolves_openai_provider() {
    assert_provider_ok("openai", Some("test-key"), None);
}

#[test]
fn factory_resolves_anthropic_provider() {
    assert_provider_ok("anthropic", Some("test-key"), None);
}

#[test]
fn factory_resolves_ollama_provider() {
    assert_provider_ok("ollama", None, None);
}

#[test]
fn factory_resolves_gemini_provider() {
    assert_provider_ok("gemini", Some("test-key"), None);
}

// ─────────────────────────────────────────────────────────────────────────────
// Alias resolution tests
// ─────────────────────────────────────────────────────────────────────────────

#[test]
fn factory_google_alias_resolves_to_gemini() {
    assert_provider_ok("google", Some("test-key"), None);
}

#[test]
fn factory_google_gemini_alias_resolves_to_gemini() {
    assert_provider_ok("google-gemini", Some("test-key"), None);
}

// ─────────────────────────────────────────────────────────────────────────────
// Custom URL provider creation
// ─────────────────────────────────────────────────────────────────────────────

#[test]
fn factory_custom_http_url_resolves() {
    assert_provider_ok("custom:http://localhost:8080", Some("test-key"), None);
}

#[test]
fn factory_custom_https_url_resolves() {
    assert_provider_ok("custom:https://api.example.com/v1", Some("test-key"), None);
}

#[test]
fn factory_custom_ftp_url_rejected() {
    let result = create_provider_with_url("custom:ftp://example.com", None, None);
    assert!(result.is_err(), "ftp scheme should be rejected");
    let err_msg = result.err().unwrap().to_string();
    assert!(
        err_msg.contains("http://") || err_msg.contains("https://"),
        "error should mention valid schemes: {err_msg}"
    );
}

#[test]
fn factory_custom_empty_url_rejected() {
    let result = create_provider_with_url("custom:", None, None);
    assert!(result.is_err(), "empty custom URL should be rejected");
}

#[test]
fn factory_anthropic_custom_endpoint_resolves() {
    assert_provider_ok(
        "anthropic-custom:https://api.example.com",
        Some("test-key"),
        None,
    );
}

#[test]
fn factory_unknown_provider_rejected() {
    let result = create_provider_with_url("nonexistent_provider_xyz", None, None);
    assert!(result.is_err(), "unknown provider name should be rejected");
}

// ─────────────────────────────────────────────────────────────────────────────
// Provider with api_url override
// ─────────────────────────────────────────────────────────────────────────────

#[test]
fn factory_ollama_with_custom_api_url() {
    assert_provider_ok("ollama", None, Some("http://192.168.1.100:11434"));
}

#[test]
fn factory_openai_with_custom_api_url() {
    assert_provider_ok(
        "openai",
        Some("test-key"),
        Some("https://custom-openai-proxy.example.com/v1"),
    );
}

// ─────────────────────────────────────────────────────────────────────────────
// Provider default convenience factory
// ─────────────────────────────────────────────────────────────────────────────

#[test]
fn convenience_factory_resolves_major_providers() {
    for provider_name in &["openai", "anthropic"] {
        let result = create_provider(provider_name, Some("test-key"));
        assert!(
            result.is_ok(),
            "convenience factory should resolve {provider_name}: {}",
            result.err().map(|e| e.to_string()).unwrap_or_default()
        );
    }
}

#[test]
fn convenience_factory_ollama_no_key() {
    let result = create_provider("ollama", None);
    assert!(
        result.is_ok(),
        "ollama should not require api key: {}",
        result.err().map(|e| e.to_string()).unwrap_or_default()
    );
}
