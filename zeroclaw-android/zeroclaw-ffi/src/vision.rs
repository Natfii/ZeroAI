/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

//! Direct-to-provider multimodal (vision) API dispatch.
//!
//! Bypasses `ZeroClaw`'s text-only agent loop and calls provider APIs
//! directly for image+text requests. Supports Anthropic Messages API,
//! OpenAI Chat Completions, Google Gemini `GenerateContent`, and
//! Ollama (OpenAI-compatible wire format).

use crate::error::FfiError;
use crate::runtime::with_daemon_config;
use serde_json::{Value, json};
use tokio::time::Duration;

/// Maximum number of images per vision request.
const MAX_IMAGES: usize = 5;

/// HTTP timeout for vision API calls (5 minutes).
const VISION_TIMEOUT_SECS: u64 = 300;

/// Default `max_tokens` for vision API responses (Anthropic and OpenAI-compatible).
const DEFAULT_MAX_TOKENS: u64 = 4096;

/// Supported vision API wire formats.
#[derive(Debug, PartialEq, Eq)]
pub(crate) enum VisionProvider {
    /// Anthropic Messages API (base64 image source).
    Anthropic {
        /// Base URL override; `None` means `https://api.anthropic.com`.
        base_url: Option<String>,
    },
    /// OpenAI Chat Completions or any compatible endpoint.
    OpenAi {
        /// Base URL override; `None` means `https://api.openai.com`.
        base_url: Option<String>,
    },
    /// Google Gemini `GenerateContent` endpoint.
    Gemini,
}

/// Maps a provider name to the vision wire format.
///
/// Only the four supported providers are accepted: `openai`, `anthropic`,
/// `gemini`, and `ollama`. Returns `None` for any unknown provider name.
pub(crate) fn classify_provider(name: &str) -> Option<VisionProvider> {
    match name.to_lowercase().as_str() {
        // Anthropic family
        "anthropic" | "claude" => Some(VisionProvider::Anthropic { base_url: None }),

        // Native OpenAI
        "openai" | "gpt" | "chatgpt" => Some(VisionProvider::OpenAi { base_url: None }),

        // Google Gemini family
        "gemini" | "google" | "google-ai" => Some(VisionProvider::Gemini),

        // Local inference via Ollama (OpenAI-compatible wire format)
        "ollama" => Some(VisionProvider::OpenAi {
            base_url: Some("http://localhost:11434/v1".into()),
        }),

        _ => None,
    }
}

/// Returns whether the active provider supports vision (image input).
///
/// Reads the default provider from the running daemon's configuration and
/// checks it against [`classify_provider`]. Returns `true` if the provider
/// has a known vision wire format, `false` otherwise.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running or the
/// daemon mutex is poisoned.
pub(crate) fn get_provider_supports_vision_inner() -> Result<bool, FfiError> {
    with_daemon_config(|config| {
        let provider = config.default_provider.as_deref().unwrap_or("anthropic");
        classify_provider(provider).is_some()
    })
}

/// Builds an Anthropic Messages API request body.
pub(crate) fn build_anthropic_body(
    text: &str,
    image_data: &[String],
    mime_types: &[String],
    model: &str,
) -> Value {
    let mut content = Vec::with_capacity(image_data.len() + 1);
    for (data, mime) in image_data.iter().zip(mime_types.iter()) {
        content.push(json!({
            "type": "image",
            "source": {
                "type": "base64",
                "media_type": mime,
                "data": data,
            }
        }));
    }
    content.push(json!({
        "type": "text",
        "text": text,
    }));

    json!({
        "model": model,
        "max_tokens": DEFAULT_MAX_TOKENS,
        "messages": [{
            "role": "user",
            "content": content,
        }]
    })
}

/// Builds an OpenAI Chat Completions request body (also used by Ollama).
pub(crate) fn build_openai_body(
    text: &str,
    image_data: &[String],
    mime_types: &[String],
    model: &str,
) -> Value {
    let mut content = Vec::with_capacity(image_data.len() + 1);
    for (data, mime) in image_data.iter().zip(mime_types.iter()) {
        content.push(json!({
            "type": "image_url",
            "image_url": {
                "url": format!("data:{mime};base64,{data}"),
                "detail": "auto",
            }
        }));
    }
    content.push(json!({
        "type": "text",
        "text": text,
    }));

    json!({
        "model": model,
        "max_tokens": DEFAULT_MAX_TOKENS,
        "messages": [{
            "role": "user",
            "content": content,
        }]
    })
}

/// Builds a Google Gemini `GenerateContent` request body.
pub(crate) fn build_gemini_body(text: &str, image_data: &[String], mime_types: &[String]) -> Value {
    let mut parts = Vec::with_capacity(image_data.len() + 1);
    for (data, mime) in image_data.iter().zip(mime_types.iter()) {
        parts.push(json!({
            "inline_data": {
                "mime_type": mime,
                "data": data,
            }
        }));
    }
    parts.push(json!({ "text": text }));

    json!({
        "contents": [{
            "parts": parts,
        }]
    })
}

/// Extracts the assistant text from an Anthropic Messages API response.
pub(crate) fn parse_anthropic_response(body: &Value) -> Result<String, FfiError> {
    body["content"]
        .as_array()
        .and_then(|blocks| {
            blocks.iter().find_map(|b| {
                if b["type"].as_str() == Some("text") {
                    b["text"].as_str().map(String::from)
                } else {
                    None
                }
            })
        })
        .ok_or_else(|| FfiError::StateError {
            detail: "Anthropic response missing text content block".into(),
        })
}

/// Extracts the assistant text from an OpenAI Chat Completions response.
pub(crate) fn parse_openai_response(body: &Value) -> Result<String, FfiError> {
    body["choices"]
        .as_array()
        .and_then(|choices| choices.first())
        .and_then(|choice| choice["message"]["content"].as_str())
        .map(String::from)
        .ok_or_else(|| FfiError::StateError {
            detail: "OpenAI response missing choices[0].message.content".into(),
        })
}

/// Extracts the assistant text from a Google Gemini response.
pub(crate) fn parse_gemini_response(body: &Value) -> Result<String, FfiError> {
    body["candidates"]
        .as_array()
        .and_then(|candidates| candidates.first())
        .and_then(|candidate| candidate["content"]["parts"].as_array())
        .and_then(|parts| parts.iter().find_map(|p| p["text"].as_str()))
        .map(String::from)
        .ok_or_else(|| FfiError::StateError {
            detail: "Gemini response missing candidates[0].content.parts[].text".into(),
        })
}

/// Sends a vision (image + text) message directly to the configured provider.
///
/// Reads the active provider, model, and API key from `DaemonState`
/// config, builds the appropriate request body, and dispatches an
/// HTTP POST. Returns the assistant's text reply.
///
/// # Errors
///
/// Returns [`FfiError::ConfigError`] for validation failures (empty
/// images, too many images, mismatched counts),
/// [`FfiError::InvalidArgument`] for unsupported provider names or
/// invalid MIME types,
/// [`FfiError::StateError`] if the daemon is not running or
/// response parsing fails,
/// [`FfiError::SpawnError`] for HTTP client or network failures.
pub(crate) fn send_vision_message_inner(
    text: String,
    image_data: Vec<String>,
    mime_types: Vec<String>,
) -> Result<String, FfiError> {
    validate_vision_input(&image_data, &mime_types)?;

    let (provider_name, api_key, model) = with_daemon_config(|config| {
        let provider = config
            .default_provider
            .clone()
            .unwrap_or_else(|| "anthropic".to_string());
        let key = config.api_key.clone().unwrap_or_default();
        let mdl = config
            .default_model
            .clone()
            .unwrap_or_else(|| "claude-sonnet-4-20250514".to_string());
        (provider, key, mdl)
    })?;

    let vision_provider =
        classify_provider(&provider_name).ok_or_else(|| FfiError::InvalidArgument {
            detail: format!(
                "Vision not supported for provider: {provider_name}. \
                 Use openai, anthropic, gemini, or ollama."
            ),
        })?;

    let handle = crate::runtime::get_or_create_runtime()?;
    handle.block_on(dispatch_vision_request(
        &vision_provider,
        &text,
        &image_data,
        &mime_types,
        &model,
        &api_key,
    ))
}

/// Allowed MIME types for vision image input.
const ALLOWED_MIME_TYPES: &[&str] = &["image/jpeg", "image/png", "image/gif", "image/webp"];

/// Validates vision request inputs before dispatching.
fn validate_vision_input(image_data: &[String], mime_types: &[String]) -> Result<(), FfiError> {
    if image_data.is_empty() {
        return Err(FfiError::ConfigError {
            detail: "at least one image is required".into(),
        });
    }
    if image_data.len() > MAX_IMAGES {
        return Err(FfiError::ConfigError {
            detail: format!("too many images ({}, max {MAX_IMAGES})", image_data.len()),
        });
    }
    if image_data.len() != mime_types.len() {
        return Err(FfiError::ConfigError {
            detail: format!(
                "image_data length ({}) != mime_types length ({})",
                image_data.len(),
                mime_types.len()
            ),
        });
    }
    for mime in mime_types {
        if !ALLOWED_MIME_TYPES.contains(&mime.as_str()) {
            return Err(FfiError::InvalidArgument {
                detail: format!(
                    "unsupported MIME type: {mime}. \
                     Allowed types: image/jpeg, image/png, image/gif, image/webp"
                ),
            });
        }
    }
    Ok(())
}

/// Returns `true` if the URL points to a local inference endpoint.
fn is_local_provider(provider: &VisionProvider) -> bool {
    match provider {
        VisionProvider::OpenAi {
            base_url: Some(url),
        } => url.starts_with("http://localhost") || url.starts_with("http://127.0.0.1"),
        _ => false,
    }
}

/// Builds the HTTP request, sends it, and parses the provider response.
async fn dispatch_vision_request(
    provider: &VisionProvider,
    text: &str,
    image_data: &[String],
    mime_types: &[String],
    model: &str,
    api_key: &str,
) -> Result<String, FfiError> {
    let client = reqwest::Client::builder()
        .timeout(Duration::from_secs(VISION_TIMEOUT_SECS))
        .build()
        .map_err(|e| FfiError::SpawnError {
            detail: format!("failed to build HTTP client: {e}"),
        })?;

    let (url, body) = match provider {
        VisionProvider::Anthropic { base_url } => {
            let base = base_url.as_deref().unwrap_or("https://api.anthropic.com");
            let body = build_anthropic_body(text, image_data, mime_types, model);
            (format!("{base}/v1/messages"), body)
        }
        VisionProvider::OpenAi { base_url } => {
            let base = base_url.as_deref().unwrap_or("https://api.openai.com/v1");
            let body = build_openai_body(text, image_data, mime_types, model);
            (format!("{base}/chat/completions"), body)
        }
        VisionProvider::Gemini => {
            let body = build_gemini_body(text, image_data, mime_types);
            let url = format!(
                "https://generativelanguage.googleapis.com/v1beta/models/\
                 {model}:generateContent"
            );
            (url, body)
        }
    };

    let mut request = client.post(&url).json(&body);
    match provider {
        VisionProvider::Anthropic { .. } => {
            request = request
                .header("x-api-key", api_key)
                .header("anthropic-version", "2023-06-01");
        }
        VisionProvider::OpenAi { .. } => {
            if !is_local_provider(provider) {
                request = request.header("Authorization", format!("Bearer {api_key}"));
            }
        }
        VisionProvider::Gemini => {
            request = request.header("x-goog-api-key", api_key);
        }
    }

    let response = request.send().await.map_err(|e| FfiError::SpawnError {
        detail: format!("vision API request failed: {e}"),
    })?;

    let status = response.status();
    if !status.is_success() {
        let error_body = response.text().await.unwrap_or_default();
        tracing::warn!(
            status = %status,
            body_len = error_body.len(),
            "vision API returned error response"
        );
        return Err(FfiError::SpawnError {
            detail: format!("vision API returned HTTP {status}"),
        });
    }

    let response_body: Value = response.json().await.map_err(|e| FfiError::SpawnError {
        detail: format!("failed to parse vision API response: {e}"),
    })?;

    match provider {
        VisionProvider::Anthropic { .. } => parse_anthropic_response(&response_body),
        VisionProvider::OpenAi { .. } => parse_openai_response(&response_body),
        VisionProvider::Gemini => parse_gemini_response(&response_body),
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    // ── classify_provider tests ────────────────────────────────────

    #[test]
    fn test_classify_anthropic() {
        assert_eq!(
            classify_provider("anthropic"),
            Some(VisionProvider::Anthropic { base_url: None })
        );
        assert_eq!(
            classify_provider("claude"),
            Some(VisionProvider::Anthropic { base_url: None })
        );
        assert_eq!(
            classify_provider("Anthropic"),
            Some(VisionProvider::Anthropic { base_url: None })
        );
    }

    #[test]
    fn test_classify_anthropic_custom_prefix_removed() {
        assert_eq!(
            classify_provider("anthropic-custom:https://my-proxy.example.com"),
            None,
        );
    }

    #[test]
    fn test_classify_openai() {
        assert_eq!(
            classify_provider("openai"),
            Some(VisionProvider::OpenAi { base_url: None })
        );
        assert_eq!(
            classify_provider("gpt"),
            Some(VisionProvider::OpenAi { base_url: None })
        );
        assert_eq!(
            classify_provider("chatgpt"),
            Some(VisionProvider::OpenAi { base_url: None })
        );
    }

    #[test]
    fn test_classify_removed_providers_return_none() {
        for name in &[
            "together",
            "groq",
            "perplexity",
            "deepseek",
            "fireworks",
            "mistral",
            "lmstudio",
            "vllm",
            "localai",
        ] {
            assert_eq!(
                classify_provider(name),
                None,
                "removed provider {name} should return None"
            );
        }
    }

    #[test]
    fn test_classify_custom_url_returns_none() {
        assert_eq!(classify_provider("custom:http://localhost:8080/v1"), None);
    }

    #[test]
    fn test_classify_gemini() {
        assert_eq!(classify_provider("gemini"), Some(VisionProvider::Gemini));
        assert_eq!(classify_provider("google"), Some(VisionProvider::Gemini));
    }

    #[test]
    fn test_classify_ollama() {
        let result = classify_provider("ollama");
        assert_eq!(
            result,
            Some(VisionProvider::OpenAi {
                base_url: Some("http://localhost:11434/v1".into()),
            })
        );
    }

    #[test]
    fn test_classify_anthropic_custom_returns_none() {
        assert_eq!(classify_provider("anthropic-custom:"), None);
        assert_eq!(
            classify_provider("anthropic-custom:https://my-proxy.example.com"),
            None
        );
    }

    #[test]
    fn test_classify_unsupported() {
        assert_eq!(classify_provider("unknown-provider-xyz"), None);
        assert_eq!(classify_provider(""), None);
    }

    // ── build body tests ───────────────────────────────────────────

    #[test]
    fn test_build_anthropic_body_single_image() {
        let body = build_anthropic_body(
            "describe this",
            &["aGVsbG8=".into()],
            &["image/jpeg".into()],
            "claude-sonnet-4-20250514",
        );
        assert_eq!(body["model"], "claude-sonnet-4-20250514");
        assert_eq!(body["max_tokens"], DEFAULT_MAX_TOKENS);
        let content = body["messages"][0]["content"].as_array().unwrap();
        assert_eq!(content.len(), 2);
        assert_eq!(content[0]["type"], "image");
        assert_eq!(content[0]["source"]["type"], "base64");
        assert_eq!(content[0]["source"]["media_type"], "image/jpeg");
        assert_eq!(content[0]["source"]["data"], "aGVsbG8=");
        assert_eq!(content[1]["type"], "text");
        assert_eq!(content[1]["text"], "describe this");
    }

    #[test]
    fn test_build_anthropic_body_multiple_images() {
        let body = build_anthropic_body(
            "compare",
            &["img1".into(), "img2".into(), "img3".into()],
            &["image/jpeg".into(), "image/png".into(), "image/jpeg".into()],
            "claude-sonnet-4-20250514",
        );
        let content = body["messages"][0]["content"].as_array().unwrap();
        assert_eq!(content.len(), 4);
        assert_eq!(content[2]["source"]["media_type"], "image/jpeg");
    }

    #[test]
    fn test_build_openai_body_single_image() {
        let body = build_openai_body(
            "what is this?",
            &["aGVsbG8=".into()],
            &["image/jpeg".into()],
            "gpt-4o",
        );
        assert_eq!(body["model"], "gpt-4o");
        assert_eq!(body["max_tokens"], DEFAULT_MAX_TOKENS);
        let content = body["messages"][0]["content"].as_array().unwrap();
        assert_eq!(content.len(), 2);
        assert_eq!(content[0]["type"], "image_url");
        let url = content[0]["image_url"]["url"].as_str().unwrap();
        assert!(url.starts_with("data:image/jpeg;base64,"));
        assert_eq!(content[0]["image_url"]["detail"], "auto");
        assert_eq!(content[1]["text"], "what is this?");
    }

    #[test]
    fn test_build_gemini_body_single_image() {
        let body = build_gemini_body("analyze", &["aGVsbG8=".into()], &["image/jpeg".into()]);
        let parts = body["contents"][0]["parts"].as_array().unwrap();
        assert_eq!(parts.len(), 2);
        assert_eq!(parts[0]["inline_data"]["mime_type"], "image/jpeg");
        assert_eq!(parts[0]["inline_data"]["data"], "aGVsbG8=");
        assert_eq!(parts[1]["text"], "analyze");
    }

    // ── parse response tests ───────────────────────────────────────

    #[test]
    fn test_parse_anthropic_response_ok() {
        let body = json!({
            "content": [
                {"type": "text", "text": "This is a cat."}
            ]
        });
        assert_eq!(parse_anthropic_response(&body).unwrap(), "This is a cat.");
    }

    #[test]
    fn test_parse_anthropic_response_missing() {
        let body = json!({"content": []});
        assert!(parse_anthropic_response(&body).is_err());
    }

    #[test]
    fn test_parse_openai_response_ok() {
        let body = json!({
            "choices": [{
                "message": {"content": "A beautiful sunset."}
            }]
        });
        assert_eq!(parse_openai_response(&body).unwrap(), "A beautiful sunset.");
    }

    #[test]
    fn test_parse_openai_response_missing() {
        let body = json!({"choices": []});
        assert!(parse_openai_response(&body).is_err());
    }

    #[test]
    fn test_parse_gemini_response_ok() {
        let body = json!({
            "candidates": [{
                "content": {
                    "parts": [{"text": "A dog playing."}]
                }
            }]
        });
        assert_eq!(parse_gemini_response(&body).unwrap(), "A dog playing.");
    }

    #[test]
    fn test_parse_gemini_response_missing() {
        let body = json!({"candidates": []});
        assert!(parse_gemini_response(&body).is_err());
    }

    // ── input validation tests ─────────────────────────────────────

    #[test]
    fn test_send_vision_empty_images() {
        let result = send_vision_message_inner("hello".into(), vec![], vec![]);
        assert!(result.is_err());
        match result.unwrap_err() {
            FfiError::ConfigError { detail } => {
                assert!(detail.contains("at least one image"));
            }
            other => panic!("expected ConfigError, got {other:?}"),
        }
    }

    #[test]
    fn test_send_vision_too_many_images() {
        let result = send_vision_message_inner(
            "hello".into(),
            vec!["a".into(); 6],
            vec!["image/jpeg".into(); 6],
        );
        assert!(result.is_err());
        match result.unwrap_err() {
            FfiError::ConfigError { detail } => {
                assert!(detail.contains("too many images"));
            }
            other => panic!("expected ConfigError, got {other:?}"),
        }
    }

    #[test]
    fn test_send_vision_mismatched_lengths() {
        let result = send_vision_message_inner(
            "hello".into(),
            vec!["a".into()],
            vec!["image/jpeg".into(), "image/png".into()],
        );
        assert!(result.is_err());
        match result.unwrap_err() {
            FfiError::ConfigError { detail } => {
                assert!(detail.contains("length"));
            }
            other => panic!("expected ConfigError, got {other:?}"),
        }
    }

    #[test]
    fn test_validate_vision_input_allowed_mimes() {
        let ok = validate_vision_input(&["a".into()], &["image/jpeg".into()]);
        assert!(ok.is_ok());
        for mime in &["image/png", "image/gif", "image/webp"] {
            assert!(
                validate_vision_input(&["a".into()], &[(*mime).into()]).is_ok(),
                "expected {mime} to be allowed"
            );
        }
    }

    #[test]
    fn test_validate_vision_input_rejects_bad_mime() {
        let result = validate_vision_input(&["a".into()], &["image/svg+xml".into()]);
        assert!(result.is_err());
        match result.unwrap_err() {
            FfiError::InvalidArgument { detail } => {
                assert!(detail.contains("unsupported MIME type"));
                assert!(detail.contains("image/svg+xml"));
            }
            other => panic!("expected InvalidArgument, got {other:?}"),
        }
    }

    #[test]
    fn test_validate_vision_input_rejects_arbitrary_mime() {
        let result = validate_vision_input(&["a".into()], &["application/pdf".into()]);
        assert!(result.is_err());
        match result.unwrap_err() {
            FfiError::InvalidArgument { detail } => {
                assert!(detail.contains("unsupported MIME type"));
            }
            other => panic!("expected InvalidArgument, got {other:?}"),
        }
    }

    // ── is_local_provider tests ───────────────────────────────────

    #[test]
    fn test_is_local_provider_ollama() {
        let p = VisionProvider::OpenAi {
            base_url: Some("http://localhost:11434/v1".into()),
        };
        assert!(is_local_provider(&p));
    }

    #[test]
    fn test_is_local_provider_loopback() {
        let p = VisionProvider::OpenAi {
            base_url: Some("http://127.0.0.1:8000/v1".into()),
        };
        assert!(is_local_provider(&p));
    }

    #[test]
    fn test_is_not_local_provider_cloud() {
        let p = VisionProvider::OpenAi {
            base_url: Some("https://api.openai.com/v1".into()),
        };
        assert!(!is_local_provider(&p));
    }

    #[test]
    fn test_is_not_local_provider_anthropic() {
        let p = VisionProvider::Anthropic { base_url: None };
        assert!(!is_local_provider(&p));
    }

    // ── classify_provider vision support tests ───────────────────

    #[test]
    fn test_classify_provider_vision_supported() {
        assert!(classify_provider("anthropic").is_some());
        assert!(classify_provider("openai").is_some());
        assert!(classify_provider("gemini").is_some());
        assert!(classify_provider("ollama").is_some());
    }

    #[test]
    fn test_classify_provider_vision_unsupported() {
        assert!(classify_provider("unknown-provider").is_none());
        assert!(classify_provider("").is_none());
    }

    #[test]
    fn test_classify_provider_case_insensitive() {
        assert!(classify_provider("Anthropic").is_some());
        assert!(classify_provider("OPENAI").is_some());
        assert!(classify_provider("Gemini").is_some());
    }
}
