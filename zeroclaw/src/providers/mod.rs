// Copyright (c) 2026 Zeroclaw Labs. All rights reserved.

//! Provider subsystem for model inference backends.
//!
//! This module implements the factory pattern for AI model providers. Each provider
//! implements the [`Provider`] trait defined in [`traits`], and is registered in the
//! factory function [`create_provider`] by its canonical string key (e.g., `"openai"`,
//! `"anthropic"`, `"ollama"`, `"gemini"`).
//!
//! Single-provider creation with optional resilience wrappers.

pub mod anthropic;
pub mod cascade;
pub mod gemini;
pub mod ollama;
pub mod openai;
pub mod xai;
pub mod deepseek;
pub mod qwen;
pub mod openai_compat;
pub mod traits;

#[allow(unused_imports)]
pub use traits::{
    ChatMessage, ChatRequest, ChatResponse, ConversationMessage, Provider, ProviderCapabilityError,
    ToolCall, ToolResultMessage,
};

use crate::auth::AuthService;
use crate::config::schema::ReasoningEffort;
use std::path::PathBuf;

const MAX_API_ERROR_CHARS: usize = 200;

fn read_non_empty_env(name: &str) -> Option<String> {
    std::env::var(name)
        .ok()
        .map(|value| value.trim().to_string())
        .filter(|value| !value.is_empty())
}

#[derive(Debug, Clone)]
pub struct ProviderRuntimeOptions {
    pub auth_profile_override: Option<String>,
    pub provider_api_url: Option<String>,
    pub zeroclaw_dir: Option<PathBuf>,
    pub secrets_encrypt: bool,
    pub reasoning_enabled: Option<bool>,
    pub reasoning_effort: Option<ReasoningEffort>,
    /// Optional custom HTTP headers to include in all LLM API requests.
    pub custom_headers: Option<std::collections::HashMap<String, String>>,
}

impl Default for ProviderRuntimeOptions {
    fn default() -> Self {
        Self {
            auth_profile_override: None,
            provider_api_url: None,
            zeroclaw_dir: None,
            secrets_encrypt: true,
            reasoning_enabled: None,
            reasoning_effort: None,
            custom_headers: None,
        }
    }
}

/// Apply custom HTTP headers to a request builder.
pub(crate) fn apply_custom_headers(
    mut builder: reqwest::RequestBuilder,
    headers: &Option<std::collections::HashMap<String, String>>,
) -> reqwest::RequestBuilder {
    if let Some(ref custom) = headers {
        for (key, value) in custom {
            builder = builder.header(key.as_str(), value.as_str());
        }
    }
    builder
}

fn is_secret_char(c: char) -> bool {
    c.is_ascii_alphanumeric() || matches!(c, '-' | '_' | '.' | ':')
}

fn token_end(input: &str, from: usize) -> usize {
    let mut end = from;
    for (i, c) in input[from..].char_indices() {
        if is_secret_char(c) {
            end = from + i + c.len_utf8();
        } else {
            break;
        }
    }
    end
}

/// Scrub known secret-like token prefixes from provider error strings.
pub fn scrub_secret_patterns(input: &str) -> String {
    const PREFIXES: [&str; 8] = [
        "sk-",
        "xoxb-",
        "xoxp-",
        "ghp_",
        "gho_",
        "ghu_",
        "github_pat_",
        "xai-",
    ];

    let mut scrubbed = input.to_string();

    for prefix in PREFIXES {
        let mut search_from = 0;
        loop {
            let Some(rel) = scrubbed[search_from..].find(prefix) else {
                break;
            };

            let start = search_from + rel;
            let content_start = start + prefix.len();
            let end = token_end(&scrubbed, content_start);

            if end == content_start {
                search_from = content_start;
                continue;
            }

            scrubbed.replace_range(start..end, "[REDACTED]");
            search_from = start + "[REDACTED]".len();
        }
    }

    scrubbed
}

/// Sanitize API error text by scrubbing secrets and truncating length.
pub fn sanitize_api_error(input: &str) -> String {
    let scrubbed = scrub_secret_patterns(input);

    if scrubbed.chars().count() <= MAX_API_ERROR_CHARS {
        return scrubbed;
    }

    let mut end = MAX_API_ERROR_CHARS;
    while end > 0 && !scrubbed.is_char_boundary(end) {
        end -= 1;
    }

    format!("{}...", &scrubbed[..end])
}

/// Build a sanitized provider error from a failed HTTP response.
pub async fn api_error(provider: &str, response: reqwest::Response) -> anyhow::Error {
    let status = response.status();
    let body = response
        .text()
        .await
        .unwrap_or_else(|_| "<failed to read provider error body>".to_string());
    let sanitized = sanitize_api_error(&body);
    anyhow::anyhow!("{provider} API error ({status}): {sanitized}")
}

/// Resolve API key for a provider from config and environment variables.
///
/// Resolution order:
/// 1. FFI credential callback (Android runtime) — when registered, this
///    is always authoritative and the `credential_override` is ignored.
/// 2. Explicitly provided `api_key` parameter (trimmed, filtered if empty)
/// 3. Provider-specific environment variable (e.g., `ANTHROPIC_API_KEY`)
/// 4. Generic fallback variables (`ZEROAI_API_KEY`, `API_KEY`)
fn resolve_provider_credential(name: &str, credential_override: Option<&str>) -> Option<String> {
    // Step 1: If a credential callback is registered (Android runtime),
    // it is always authoritative — the override is ignored entirely.
    if let Some(resolved) = crate::ffi_credential_hook::resolve_via_callback(name) {
        return Some(resolved);
    }

    // Step 2: No callback — use override if non-empty (tests, CLI).
    if let Some(raw_override) = credential_override {
        let trimmed_override = raw_override.trim();
        if !trimmed_override.is_empty() {
            return Some(trimmed_override.to_owned());
        }
    }

    // Step 3: Provider-specific environment variables.
    let provider_env_candidates: Vec<&str> = match name {
        "anthropic" => vec!["ANTHROPIC_OAUTH_TOKEN", "ANTHROPIC_API_KEY"],
        "openai" => vec!["OPENAI_API_KEY"],
        "ollama" => vec!["OLLAMA_API_KEY"],
        "gemini" | "google" | "google-gemini" => vec!["GEMINI_API_KEY", "GOOGLE_API_KEY"],
        "openrouter" => vec!["OPENROUTER_API_KEY"],
        "xai" | "grok" => vec!["XAI_API_KEY"],
        "deepseek" => vec!["DEEPSEEK_API_KEY"],
        "qwen" | "qwen-cn" | "qwen-us" | "dashscope" | "dashscope-cn" | "dashscope-us" => {
            vec!["DASHSCOPE_API_KEY", "QWEN_API_KEY"]
        }
        _ => vec![],
    };

    for env_var in provider_env_candidates {
        if let Ok(value) = std::env::var(env_var) {
            let value = value.trim();
            if !value.is_empty() {
                return Some(value.to_string());
            }
        }
    }

    // Step 4: Generic fallback variables.
    for env_var in ["ZEROAI_API_KEY", "API_KEY"] {
        if let Ok(value) = std::env::var(env_var) {
            let value = value.trim();
            if !value.is_empty() {
                return Some(value.to_string());
            }
        }
    }

    None
}

fn parse_custom_provider_url(
    raw_url: &str,
    provider_label: &str,
    format_hint: &str,
) -> anyhow::Result<String> {
    let base_url = raw_url.trim();

    if base_url.is_empty() {
        anyhow::bail!("{provider_label} requires a URL. Format: {format_hint}");
    }

    let parsed = reqwest::Url::parse(base_url).map_err(|_| {
        anyhow::anyhow!("{provider_label} requires a valid URL. Format: {format_hint}")
    })?;

    match parsed.scheme() {
        "http" | "https" => Ok(base_url.to_string()),
        _ => anyhow::bail!(
            "{provider_label} requires an http:// or https:// URL. Format: {format_hint}"
        ),
    }
}

/// Factory: create the right provider from config (without custom URL)
pub fn create_provider(name: &str, api_key: Option<&str>) -> anyhow::Result<Box<dyn Provider>> {
    create_provider_with_options(name, api_key, &ProviderRuntimeOptions::default())
}

/// Factory: create provider with runtime options (auth profile override, state dir).
pub fn create_provider_with_options(
    name: &str,
    api_key: Option<&str>,
    options: &ProviderRuntimeOptions,
) -> anyhow::Result<Box<dyn Provider>> {
    create_provider_with_url_and_options(name, api_key, None, options)
}

/// Factory: create the right provider from config with optional custom base URL
pub fn create_provider_with_url(
    name: &str,
    api_key: Option<&str>,
    api_url: Option<&str>,
) -> anyhow::Result<Box<dyn Provider>> {
    create_provider_with_url_and_options(name, api_key, api_url, &ProviderRuntimeOptions::default())
}

/// Factory: create provider with optional base URL and runtime options.
pub(crate) fn create_provider_with_url_and_options(
    name: &str,
    api_key: Option<&str>,
    api_url: Option<&str>,
    options: &ProviderRuntimeOptions,
) -> anyhow::Result<Box<dyn Provider>> {
    let resolved_credential = resolve_provider_credential(name, api_key)
        .map(|v| String::from_utf8(v.into_bytes()).unwrap_or_default());
    #[allow(clippy::option_as_ref_deref)]
    let key = resolved_credential.as_ref().map(String::as_str);

    let headers = options.custom_headers.clone();
    match name {
        "anthropic" => Ok(Box::new(
            anthropic::AnthropicProvider::new(key).with_custom_headers(headers),
        )),
        "openai" => Ok(Box::new(
            openai::OpenAiProvider::with_base_url_and_reasoning(
                api_url,
                key,
                options.reasoning_effort,
            )
            .with_custom_headers(headers),
        )),
        "ollama" => Ok(Box::new(
            ollama::OllamaProvider::new_with_reasoning(api_url, key, options.reasoning_enabled)
                .with_custom_headers(headers),
        )),
        "gemini" | "google" | "google-gemini" => {
            let state_dir = options.zeroclaw_dir.clone().unwrap_or_else(|| {
                directories::UserDirs::new().map_or_else(
                    || PathBuf::from(".zeroai"),
                    |dirs| dirs.home_dir().join(".zeroai"),
                )
            });
            let auth_service = AuthService::new(&state_dir, options.secrets_encrypt);
            Ok(Box::new(
                gemini::GeminiProvider::new_with_auth(
                    key,
                    auth_service,
                    options.auth_profile_override.clone(),
                )
                .with_custom_headers(headers),
            ))
        }

        "openrouter" => {
            let mut merged_headers = headers.unwrap_or_default();
            merged_headers
                .entry("HTTP-Referer".to_string())
                .or_insert_with(|| "https://zeroai.app".to_string());
            merged_headers
                .entry("X-OpenRouter-Title".to_string())
                .or_insert_with(|| "ZeroAI".to_string());
            Ok(Box::new(
                openai::OpenAiProvider::with_base_url(
                    Some("https://openrouter.ai/api/v1"),
                    key,
                )
                .with_custom_headers(Some(merged_headers)),
            ))
        }

        "xai" | "grok" => Ok(Box::new(
            xai::XaiProvider::new(key).with_custom_headers(headers),
        )),

        "deepseek" => Ok(Box::new(
            deepseek::DeepSeekProvider::new(key).with_custom_headers(headers),
        )),
        "qwen" | "dashscope" => Ok(Box::new(
            qwen::QwenProvider::new(qwen::QwenRegion::International, key)
                .with_custom_headers(headers),
        )),
        "qwen-cn" | "dashscope-cn" => Ok(Box::new(
            qwen::QwenProvider::new(qwen::QwenRegion::China, key)
                .with_custom_headers(headers),
        )),
        "qwen-us" | "dashscope-us" => Ok(Box::new(
            qwen::QwenProvider::new(qwen::QwenRegion::Us, key)
                .with_custom_headers(headers),
        )),

        name if name.starts_with("custom:") => {
            let base_url = parse_custom_provider_url(
                name.strip_prefix("custom:").unwrap_or(""),
                "Custom provider",
                "custom:https://your-api.com",
            )?;
            Ok(Box::new(
                openai::OpenAiProvider::with_base_url(Some(base_url.as_str()), key)
                    .with_custom_headers(headers),
            ))
        }

        name if name.starts_with("anthropic-custom:") => {
            let base_url = parse_custom_provider_url(
                name.strip_prefix("anthropic-custom:").unwrap_or(""),
                "Anthropic-custom provider",
                "anthropic-custom:https://your-api.com",
            )?;
            Ok(Box::new(
                anthropic::AnthropicProvider::with_base_url(key, Some(&base_url))
                    .with_custom_headers(headers),
            ))
        }

        _ => anyhow::bail!(
            "Unknown provider: {name}. Supported: openai, anthropic, gemini, ollama, openrouter, xai, deepseek, qwen.\n\
             Tip: Use \"custom:https://your-api.com\" for OpenAI-compatible endpoints.\n\
             Tip: Use \"anthropic-custom:https://your-api.com\" for Anthropic-compatible endpoints."
        ),
    }
}

/// Parse `"provider:profile"` syntax for fallback entries.
fn parse_provider_profile(s: &str) -> (&str, Option<&str>) {
    if s.starts_with("custom:") || s.starts_with("anthropic-custom:") {
        return (s, None);
    }
    match s.split_once(':') {
        Some((provider, profile)) if !profile.is_empty() => (provider, Some(profile)),
        _ => (s, None),
    }
}

/// Create provider with retry and fallback behavior.
///
/// Without the resilient provider wrapper, this creates the primary provider
/// and attempts fallback providers sequentially on failure.
pub fn create_resilient_provider(
    primary_name: &str,
    api_key: Option<&str>,
    api_url: Option<&str>,
    _reliability: &crate::config::ReliabilityConfig,
) -> anyhow::Result<Box<dyn Provider>> {
    create_resilient_provider_with_options(
        primary_name,
        api_key,
        api_url,
        _reliability,
        None,
        &ProviderRuntimeOptions::default(),
    )
}

/// Create provider with retry/fallback behavior and auth runtime options.
///
/// When `fallback_override` is `Some`, the given slice is used as the
/// cascade fallback list instead of `reliability.fallback_providers`.
/// This lets callers feed the full routing-tier tail (positions 1..N)
/// so that every provider in the tier list participates in fallback.
pub fn create_resilient_provider_with_options(
    primary_name: &str,
    api_key: Option<&str>,
    api_url: Option<&str>,
    reliability: &crate::config::ReliabilityConfig,
    fallback_override: Option<&[String]>,
    options: &ProviderRuntimeOptions,
) -> anyhow::Result<Box<dyn Provider>> {
    let fallbacks = fallback_override.unwrap_or(&reliability.fallback_providers);
    cascade::create_cascading_provider(
        primary_name,
        fallbacks,
        api_key,
        api_url,
        reliability,
        options,
    )
}

/// Information about a supported provider for display purposes.
pub struct ProviderInfo {
    /// Canonical name used in config
    pub name: &'static str,
    /// Human-readable display name
    pub display_name: &'static str,
    /// Alternative names accepted in config
    pub aliases: &'static [&'static str],
    /// Whether the provider runs locally (no API key required)
    pub local: bool,
}

/// Return the list of all known providers.
pub fn list_providers() -> Vec<ProviderInfo> {
    vec![
        ProviderInfo {
            name: "anthropic",
            display_name: "Anthropic",
            aliases: &[],
            local: false,
        },
        ProviderInfo {
            name: "openai",
            display_name: "OpenAI",
            aliases: &[],
            local: false,
        },
        ProviderInfo {
            name: "gemini",
            display_name: "Google Gemini",
            aliases: &["google", "google-gemini"],
            local: false,
        },
        ProviderInfo {
            name: "openrouter",
            display_name: "OpenRouter",
            aliases: &[],
            local: false,
        },
        ProviderInfo {
            name: "xai",
            display_name: "xAI (Grok)",
            aliases: &["grok"],
            local: false,
        },
        ProviderInfo {
            name: "ollama",
            display_name: "Ollama",
            aliases: &[],
            local: true,
        },
        ProviderInfo {
            name: "deepseek",
            display_name: "DeepSeek",
            aliases: &[],
            local: false,
        },
        ProviderInfo {
            name: "qwen",
            display_name: "Qwen (Alibaba)",
            aliases: &["dashscope", "qwen-cn", "qwen-us", "dashscope-cn", "dashscope-us"],
            local: false,
        },
    ]
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn resolve_provider_credential_prefers_explicit_argument() {
        let resolved = resolve_provider_credential("openai", Some("  explicit-key  "));
        assert_eq!(resolved, Some("explicit-key".to_string()));
    }

    #[test]
    fn unknown_provider_returns_error() {
        let result = create_provider("nonexistent", Some("key"));
        assert!(result.is_err());
    }

    #[test]
    fn list_providers_returns_eight_entries() {
        let providers = list_providers();
        assert_eq!(providers.len(), 8);
        assert!(providers.iter().any(|p| p.name == "anthropic"));
        assert!(providers.iter().any(|p| p.name == "openai"));
        assert!(providers.iter().any(|p| p.name == "gemini"));
        assert!(providers.iter().any(|p| p.name == "openrouter"));
        assert!(providers.iter().any(|p| p.name == "ollama"));
        assert!(providers.iter().any(|p| p.name == "xai"));
        assert!(providers.iter().any(|p| p.name == "deepseek"));
        assert!(providers.iter().any(|p| p.name == "qwen"));
    }

    #[test]
    fn scrub_secret_patterns_redacts_sk_prefix() {
        let input = "Error: invalid key sk-abc123xyz";
        let result = scrub_secret_patterns(input);
        assert!(!result.contains("sk-abc123xyz"));
        assert!(result.contains("[REDACTED]"));
    }

    #[test]
    fn scrub_secret_patterns_redacts_xai_prefix() {
        let input = "Error: invalid key xai-abc123xyz456";
        let result = scrub_secret_patterns(input);
        assert!(!result.contains("xai-abc123xyz456"));
        assert!(result.contains("[REDACTED]"));
    }

    #[test]
    fn scrub_secret_patterns_redacts_deepseek_sk_key() {
        let input = "Error: invalid key sk-d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6";
        let result = scrub_secret_patterns(input);
        assert!(!result.contains("sk-d1e2f3"));
    }

    #[test]
    fn sanitize_api_error_truncates_long_messages() {
        let long_msg = "x".repeat(500);
        let result = sanitize_api_error(&long_msg);
        assert!(result.len() < 500);
        assert!(result.ends_with("..."));
    }

    #[test]
    fn openrouter_creates_provider_successfully() {
        let result = create_provider("openrouter", Some("sk-or-v1-test-key"));
        assert!(result.is_ok(), "openrouter should create a provider");
    }
}
