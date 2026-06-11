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
use crate::runtime::{effective_model_provider_type, try_with_daemon_config};
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

crate::ffi_export!(
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
    /// Returns whether the active cloud provider supports vision (image input).
    ///
    /// Reads the daemon's default provider and checks if it has a known
    /// vision wire format. Used by the Android UI to decide whether captured
    /// images should be routed to the cloud provider or described on-device
    /// via Gemini Nano.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::StateError`] if the daemon is not running,
    /// [`crate::FfiError::StateCorrupted`] if internal state is poisoned, or
    /// [`crate::FfiError::InternalPanic`] if native code panics.
    fn get_provider_supports_vision() -> bool = get_provider_supports_vision_inner
);

pub(crate) fn get_provider_supports_vision_inner() -> Result<bool, FfiError> {
    // Upstream removed the flat `config.default_provider`; the canonical
    // provider type now comes from `effective_model_provider_type`, which
    // walks the nested `config.providers.models.<type>.<alias>` schema.
    try_with_daemon_config(|config| {
        let provider = effective_model_provider_type(config)?;
        Ok(classify_provider(&provider).is_some())
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

crate::ffi_export!(
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
    /// Sends a vision (image + text) message directly to the provider.
    ///
    /// Each entry in `image_data` is a base64-encoded image, paired with a
    /// corresponding MIME type in `mime_types`.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::EstopEngaged`] when emergency stop is
    /// active, [`crate::FfiError::ConfigError`] for validation failures,
    /// [`crate::FfiError::InvalidArgument`] for unsupported providers or
    /// MIME types, [`crate::FfiError::StateError`] if the daemon is not
    /// running, [`crate::FfiError::SpawnError`] for HTTP failures, or
    /// [`crate::FfiError::InternalPanic`] if native code panics.
    fn send_vision_message(
        text: String,
        image_data: Vec<String>,
        mime_types: Vec<String>
    ) -> String = send_vision_message_ffi
);

pub(crate) fn send_vision_message_ffi(
    text: String,
    image_data: Vec<String>,
    mime_types: Vec<String>,
) -> Result<String, FfiError> {
    if crate::estop::is_engaged() {
        return Err(FfiError::EstopEngaged {
            detail: "Emergency stop is engaged. Resume before sending messages.".into(),
        });
    }
    send_vision_message_inner(text, image_data, mime_types)
}

pub(crate) fn send_vision_message_inner(
    text: String,
    image_data: Vec<String>,
    mime_types: Vec<String>,
) -> Result<String, FfiError> {
    validate_vision_input(&image_data, &mime_types)?;

    // Provider routing on the new nested schema:
    //   - canonical provider type: `effective_model_provider_type`
    //   - default model:           `Config::resolve_default_model`
    //   - api_key + uri:           `config.providers.models.find(family, alias)`
    // The `(family, alias)` tuple is derived from
    // `first_model_provider_alias()` which returns `"<family>.<alias>"`.
    let (provider_name, api_key, model) = try_with_daemon_config(|config| {
        let provider = crate::runtime::effective_model_provider_type(config)?;
        let alias = config
            .first_model_provider_alias()
            .and_then(|s| s.as_str().split_once('.').map(|(_, a)| a.to_string()))
            .unwrap_or_else(|| "default".to_string());
        let key = config
            .providers
            .models
            .find(&provider, &alias)
            .and_then(|p| p.api_key.clone())
            .unwrap_or_default();
        let mdl = config.resolve_default_model().ok_or_else(|| FfiError::ConfigError {
            detail: crate::runtime::NO_MODEL_CONFIGURED.into(),
        })?;
        Ok((provider, key, mdl))
    })?;

    let vision_provider = classify_provider(&provider_name).ok_or_else(|| {
        let pname = provider_name.clone();
        FfiError::InvalidArgument {
            detail: format!(
                "Vision not supported for provider: {pname}. \
                 Use openai, anthropic, gemini, or ollama."
            ),
        }
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
#[path = "vision_tests.rs"]
mod tests;
