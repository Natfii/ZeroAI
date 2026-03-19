/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

//! Model discovery by querying provider APIs.
//!
//! Queries `/v1/models`, `/api/tags`, or returns hardcoded lists depending
//! on the provider type. Results are returned as a JSON array of
//! `{"id": "...", "name": "..."}` objects.

use crate::error::FfiError;
use zeroclaw::auth_exports::{AuthService, state_dir_from_config};

const GEMINI_MODELS_URL: &str = "https://generativelanguage.googleapis.com/v1beta/models";

/// Discovers available models from a provider's API.
///
/// # Providers
///
/// - **OpenAI / Compatible**: `GET /v1/models`
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
    let provider = resolve_provider(provider)?;
    let gemini_state_dir = if provider == "gemini" && api_key.trim().is_empty() {
        Some(crate::runtime::with_daemon_config(state_dir_from_config)?)
    } else {
        None
    };
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
            "gemini" => {
                fetch_gemini_models(
                    &client,
                    base_url.as_deref(),
                    &api_key,
                    gemini_state_dir.as_deref(),
                )
                .await
            }
            _ => {
                let url = base_url.unwrap_or_else(|| default_base_url(&provider));
                fetch_openai_models(&client, &url, &api_key).await
            }
        }
    })
}

fn resolve_provider(provider: String) -> Result<String, FfiError> {
    let trimmed = provider.trim();
    let raw_provider = if trimmed.is_empty() {
        crate::runtime::clone_daemon_config()?
            .default_provider
            .filter(|value| !value.trim().is_empty())
            .ok_or_else(|| FfiError::InvalidArgument {
                detail: "provider is required when the daemon has no default provider".into(),
            })?
    } else {
        trimmed.to_string()
    };

    Ok(match raw_provider.trim().to_lowercase().as_str() {
        "google" | "google-gemini" => "gemini".into(),
        "chatgpt" | "codex" | "openai-codex" => "openai".into(),
        "claude-code" => "anthropic".into(),
        other => other.into(),
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

    ensure_success_status(&resp, "model discovery")?;

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

/// Fetches models from Google's Gemini `v1beta/models` endpoint.
async fn fetch_gemini_models(
    client: &reqwest::Client,
    base_url: Option<&str>,
    api_key: &str,
    state_dir: Option<&std::path::Path>,
) -> Result<String, FfiError> {
    let url = resolve_gemini_models_url(base_url);

    let request = if !api_key.trim().is_empty() {
        client.get(&url).header("x-goog-api-key", api_key)
    } else if let Some(state_dir) = state_dir {
        let token = AuthService::new(state_dir, true)
            .get_valid_gemini_access_token(None)
            .await
            .map_err(|e| FfiError::SpawnError {
                detail: format!("failed to read Gemini access token: {e}"),
            })?
            .ok_or_else(|| FfiError::InvalidArgument {
                detail:
                    "Gemini model discovery requires an API key or a connected Google Gemini profile"
                        .into(),
            })?;
        client.get(&url).bearer_auth(token)
    } else {
        return Err(FfiError::InvalidArgument {
            detail:
                "Gemini model discovery requires an API key or a running daemon with a connected Google Gemini profile"
                    .into(),
        });
    };

    let resp = request.send().await.map_err(|e| FfiError::SpawnError {
        detail: format!("gemini model discovery failed: {e}"),
    })?;

    ensure_success_status(&resp, "gemini model discovery")?;

    let body: serde_json::Value = resp.json().await.map_err(|e| FfiError::SpawnError {
        detail: format!("parse error: {e}"),
    })?;

    let models: Vec<serde_json::Value> = body
        .get("models")
        .and_then(|m| m.as_array())
        .map(|arr| {
            arr.iter()
                .filter_map(|m| {
                    let name = m.get("name").and_then(|v| v.as_str())?.trim();
                    if name.is_empty() {
                        return None;
                    }
                    let id = name.trim_start_matches("models/");
                    Some(serde_json::json!({
                        "id": id,
                        "name": id,
                    }))
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

    ensure_success_status(&resp, "ollama model discovery")?;

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

fn resolve_gemini_models_url(base_url: Option<&str>) -> String {
    let Some(base_url) = base_url.map(str::trim).filter(|value| !value.is_empty()) else {
        return GEMINI_MODELS_URL.into();
    };

    if base_url.ends_with("/models") {
        base_url.to_string()
    } else {
        format!("{}/models", base_url.trim_end_matches('/'))
    }
}

fn ensure_success_status(resp: &reqwest::Response, operation: &str) -> Result<(), FfiError> {
    if resp.status().is_success() {
        return Ok(());
    }

    Err(FfiError::SpawnError {
        detail: format!("{operation} failed: HTTP {}", resp.status().as_u16()),
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
    fn test_resolve_provider_normalizes_aliases() {
        assert_eq!(resolve_provider("google-gemini".into()).unwrap(), "gemini");
        assert_eq!(resolve_provider("google".into()).unwrap(), "gemini");
        assert_eq!(resolve_provider("openai-codex".into()).unwrap(), "openai");
        assert_eq!(resolve_provider("claude-code".into()).unwrap(), "anthropic");
    }

    #[test]
    fn test_resolve_gemini_models_url_uses_google_endpoint_by_default() {
        assert_eq!(resolve_gemini_models_url(None), GEMINI_MODELS_URL);
    }

    #[test]
    fn test_resolve_gemini_models_url_appends_models_suffix_when_needed() {
        assert_eq!(
            resolve_gemini_models_url(Some("https://generativelanguage.googleapis.com/v1beta")),
            GEMINI_MODELS_URL
        );
        assert_eq!(
            resolve_gemini_models_url(Some("https://proxy.example.com/v1beta/models")),
            "https://proxy.example.com/v1beta/models"
        );
    }

    #[test]
    fn test_discover_anthropic_returns_json() {
        let result = discover_models_inner("anthropic".into(), String::new(), None).unwrap();
        let parsed: Vec<serde_json::Value> = serde_json::from_str(&result).unwrap();
        assert!(!parsed.is_empty());
    }
}
