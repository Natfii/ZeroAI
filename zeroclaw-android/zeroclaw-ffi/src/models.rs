/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

//! Model discovery by querying provider APIs.
//!
//! Queries `/v1/models`, `/api/tags`, or returns hardcoded lists depending
//! on the provider type. Results are returned as a JSON array of
//! `{"id": "...", "name": "..."}` objects.

use crate::error::FfiError;

/// Discovers available models from a provider's API.
///
/// # Providers
///
/// - **OpenAI / OpenRouter / Compatible**: `GET /v1/models`
/// - **Ollama**: `GET /api/tags`
/// - **Anthropic**: returns a hardcoded list of known models
///
/// # Arguments
///
/// * `provider` - Provider ID (e.g. `"openai"`, `"anthropic"`, `"ollama"`).
/// * `api_key` - API key for Bearer authentication. Ignored for Ollama and Anthropic.
/// * `base_url` - Optional base URL override. Falls back to provider defaults.
///
/// # Returns
///
/// A JSON string containing an array of `{"id": "...", "name": "..."}` objects.
///
/// # Errors
///
/// Returns [`FfiError::SpawnError`] on HTTP client, network, or parse errors.
pub(crate) fn discover_models_inner(
    provider: String,
    api_key: String,
    base_url: Option<String>,
) -> Result<String, FfiError> {
    let handle = crate::runtime::get_or_create_runtime()?;

    handle.block_on(async {
        let client = reqwest::Client::builder()
            .timeout(std::time::Duration::from_secs(10))
            .build()
            .map_err(|e| FfiError::SpawnError {
                detail: format!("http client error: {e}"),
            })?;

        match provider.as_str() {
            "anthropic" => Ok(anthropic_models()),
            "ollama" => {
                let url = base_url.unwrap_or_else(|| "http://localhost:11434".into());
                fetch_ollama_models(&client, &url).await
            }
            _ => {
                let url = base_url.unwrap_or_else(|| default_base_url(&provider));
                fetch_openai_models(&client, &url, &api_key).await
            }
        }
    })
}

/// Fetches models from an `OpenAI`-compatible `/v1/models` endpoint.
async fn fetch_openai_models(
    client: &reqwest::Client,
    base_url: &str,
    api_key: &str,
) -> Result<String, FfiError> {
    let url = format!("{}/v1/models", base_url.trim_end_matches('/'));
    let resp = client
        .get(&url)
        .header("Authorization", format!("Bearer {api_key}"))
        .send()
        .await
        .map_err(|e| FfiError::SpawnError {
            detail: format!("model discovery failed: {e}"),
        })?;

    let body: serde_json::Value = resp.json().await.map_err(|e| FfiError::SpawnError {
        detail: format!("parse error: {e}"),
    })?;

    let models: Vec<serde_json::Value> = body
        .get("data")
        .and_then(|d| d.as_array())
        .map(|arr| {
            arr.iter()
                .map(|m| {
                    serde_json::json!({
                        "id": m.get("id").and_then(|v| v.as_str()).unwrap_or(""),
                        "name": m.get("id").and_then(|v| v.as_str()).unwrap_or(""),
                    })
                })
                .collect()
        })
        .unwrap_or_default();

    serde_json::to_string(&models).map_err(|e| FfiError::SpawnError {
        detail: format!("serialize error: {e}"),
    })
}

/// Fetches models from an Ollama `/api/tags` endpoint.
async fn fetch_ollama_models(client: &reqwest::Client, base_url: &str) -> Result<String, FfiError> {
    let url = format!("{}/api/tags", base_url.trim_end_matches('/'));
    let resp = client
        .get(&url)
        .send()
        .await
        .map_err(|e| FfiError::SpawnError {
            detail: format!("ollama discovery failed: {e}"),
        })?;

    let body: serde_json::Value = resp.json().await.map_err(|e| FfiError::SpawnError {
        detail: format!("parse error: {e}"),
    })?;

    let models: Vec<serde_json::Value> = body
        .get("models")
        .and_then(|m| m.as_array())
        .map(|arr| {
            arr.iter()
                .map(|m| {
                    serde_json::json!({
                        "id": m.get("name").and_then(|v| v.as_str()).unwrap_or(""),
                        "name": m.get("name").and_then(|v| v.as_str()).unwrap_or(""),
                    })
                })
                .collect()
        })
        .unwrap_or_default();

    serde_json::to_string(&models).map_err(|e| FfiError::SpawnError {
        detail: format!("serialize error: {e}"),
    })
}

/// Returns a hardcoded JSON list of known Anthropic models.
fn anthropic_models() -> String {
    serde_json::to_string(&serde_json::json!([
        {"id": "claude-opus-4-20250514", "name": "Claude Opus 4"},
        {"id": "claude-sonnet-4-20250514", "name": "Claude Sonnet 4"},
        {"id": "claude-haiku-4-20250506", "name": "Claude Haiku 4"},
        {"id": "claude-3-5-sonnet-20241022", "name": "Claude 3.5 Sonnet"},
    ]))
    .unwrap_or_else(|_| "[]".into())
}

/// Returns the default API base URL for a given provider ID.
fn default_base_url(provider: &str) -> String {
    match provider {
        "openrouter" => "https://openrouter.ai/api".into(),
        "groq" => "https://api.groq.com/openai".into(),
        "mistral" => "https://api.mistral.ai".into(),
        "deepseek" => "https://api.deepseek.com".into(),
        "together" => "https://api.together.xyz".into(),
        "xai" | "grok" => "https://api.x.ai".into(),
        _ => "https://api.openai.com".into(),
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    #[test]
    fn test_anthropic_models_returns_json() {
        let result = anthropic_models();
        let parsed: Vec<serde_json::Value> = serde_json::from_str(&result).unwrap();
        assert!(!parsed.is_empty());
        assert!(parsed[0].get("id").is_some());
        assert!(parsed[0].get("name").is_some());
    }

    #[test]
    fn test_anthropic_models_has_known_ids() {
        let result = anthropic_models();
        let parsed: Vec<serde_json::Value> = serde_json::from_str(&result).unwrap();
        let ids: Vec<&str> = parsed
            .iter()
            .filter_map(|m| m.get("id").and_then(|v| v.as_str()))
            .collect();
        assert!(ids.contains(&"claude-opus-4-20250514"));
        assert!(ids.contains(&"claude-sonnet-4-20250514"));
    }

    #[test]
    fn test_default_base_url_openai() {
        assert!(default_base_url("openai").contains("openai.com"));
    }

    #[test]
    fn test_default_base_url_openai_codex() {
        assert!(default_base_url("openai-codex").contains("openai.com"));
    }

    #[test]
    fn test_default_base_url_openrouter() {
        assert!(default_base_url("openrouter").contains("openrouter.ai"));
    }

    #[test]
    fn test_default_base_url_groq() {
        assert!(default_base_url("groq").contains("groq.com"));
    }

    #[test]
    fn test_default_base_url_mistral() {
        assert!(default_base_url("mistral").contains("mistral.ai"));
    }

    #[test]
    fn test_default_base_url_deepseek() {
        assert!(default_base_url("deepseek").contains("deepseek.com"));
    }

    #[test]
    fn test_default_base_url_together() {
        assert!(default_base_url("together").contains("together.xyz"));
    }

    #[test]
    fn test_default_base_url_xai() {
        assert!(default_base_url("xai").contains("x.ai"));
    }

    #[test]
    fn test_default_base_url_grok_alias() {
        assert_eq!(default_base_url("grok"), default_base_url("xai"));
    }

    #[test]
    fn test_default_base_url_unknown_falls_back_to_openai() {
        assert!(default_base_url("unknown_provider").contains("openai.com"));
    }

    #[test]
    fn test_discover_anthropic_returns_json() {
        let result = discover_models_inner("anthropic".into(), String::new(), None).unwrap();
        let parsed: Vec<serde_json::Value> = serde_json::from_str(&result).unwrap();
        assert!(!parsed.is_empty());
    }
}
