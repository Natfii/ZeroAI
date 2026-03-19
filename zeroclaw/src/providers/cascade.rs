// Copyright (c) 2026 @Natfii. All rights reserved.

//! Provider cascade: retry with backoff, then fail over to fallback providers.
//!
//! The cascade wraps a primary provider creation function and an ordered list
//! of fallback provider names. On a cascadable error (rate-limit, transient),
//! it tries the next provider. Auth errors are never cascaded — they indicate
//! a configuration problem the user must fix.

use crate::config::ReliabilityConfig;
use crate::providers::traits::Provider;
use crate::providers::{create_provider_with_url_and_options, ProviderRuntimeOptions};

/// Categorizes provider errors to decide whether to cascade.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ProviderErrorKind {
    /// Authentication failure (401, 403, invalid key). Do NOT cascade.
    Auth,
    /// Rate limit hit (429). Cascade to next provider.
    RateLimit,
    /// Transient server error (500, 502, 503, 504, timeout). Cascade.
    Transient,
    /// Unrecognized error. Cascade as a safety measure.
    Unknown,
}

impl ProviderErrorKind {
    /// Whether this error type should trigger a cascade to the next provider.
    pub fn is_cascadable(&self) -> bool {
        !matches!(self, Self::Auth)
    }
}

/// Classifies a provider error string into a [`ProviderErrorKind`].
///
/// Uses substring matching on common HTTP status codes and error patterns.
/// This is intentionally simple — provider error formats vary, and false
/// positives (cascading when we shouldn't) are cheaper than false negatives
/// (failing when we could have cascaded).
pub fn classify_provider_error(error: &str) -> ProviderErrorKind {
    let lower = error.to_lowercase();

    if lower.contains("401")
        || lower.contains("403")
        || lower.contains("unauthorized")
        || lower.contains("forbidden")
        || lower.contains("invalid api key")
        || lower.contains("invalid_api_key")
        || lower.contains("authentication")
    {
        return ProviderErrorKind::Auth;
    }

    if lower.contains("429")
        || lower.contains("rate limit")
        || lower.contains("rate_limit")
        || lower.contains("too many requests")
        || lower.contains("quota")
    {
        return ProviderErrorKind::RateLimit;
    }

    if lower.contains("500")
        || lower.contains("502")
        || lower.contains("503")
        || lower.contains("504")
        || lower.contains("service unavailable")
        || lower.contains("bad gateway")
        || lower.contains("gateway timeout")
        || lower.contains("timeout")
        || lower.contains("timed out")
        || lower.contains("connection refused")
        || lower.contains("connection reset")
    {
        return ProviderErrorKind::Transient;
    }

    ProviderErrorKind::Unknown
}

/// Attempts to create a provider, cascading through fallbacks on failure.
///
/// Tries `primary_name` first. If creation or warmup fails with a
/// cascadable error, tries each name in `fallback_chain` in order.
/// Returns the first provider that successfully initializes.
///
/// # Arguments
///
/// * `primary_name` — The preferred provider name.
/// * `fallback_chain` — Ordered list of fallback provider names.
/// * `api_key` — API key (used for all providers; per-provider keys
///   are resolved inside the factory).
/// * `api_url` — Optional base URL override.
/// * `reliability` — Retry/backoff configuration.
/// * `options` — Provider runtime options.
///
/// # Errors
///
/// Returns the last error if all providers (primary + fallbacks) fail.
pub fn create_cascading_provider(
    primary_name: &str,
    fallback_chain: &[String],
    api_key: Option<&str>,
    api_url: Option<&str>,
    reliability: &ReliabilityConfig,
    options: &ProviderRuntimeOptions,
) -> anyhow::Result<Box<dyn Provider>> {
    let mut last_error: Option<anyhow::Error>;

    // Try primary first.
    match try_create_with_retries(primary_name, api_key, api_url, reliability, options) {
        Ok(provider) => return Ok(provider),
        Err(e) => {
            let kind = classify_provider_error(&e.to_string());
            tracing::warn!(
                provider = primary_name,
                error_kind = ?kind,
                "Primary provider failed: {e}"
            );
            if !kind.is_cascadable() {
                return Err(e);
            }
            last_error = Some(e);
        }
    }

    // Try each fallback in order.
    for fallback_name in fallback_chain {
        if fallback_name == primary_name {
            continue;
        }
        tracing::info!(provider = fallback_name.as_str(), "Cascading to fallback provider");
        match try_create_with_retries(fallback_name, api_key, None, reliability, options) {
            Ok(provider) => return Ok(provider),
            Err(e) => {
                let kind = classify_provider_error(&e.to_string());
                tracing::warn!(
                    provider = fallback_name.as_str(),
                    error_kind = ?kind,
                    "Fallback provider failed: {e}"
                );
                last_error = Some(e);
            }
        }
    }

    Err(last_error.unwrap_or_else(|| anyhow::anyhow!("no providers configured")))
}

/// Tries to create a provider with retries and exponential backoff.
fn try_create_with_retries(
    name: &str,
    api_key: Option<&str>,
    api_url: Option<&str>,
    reliability: &ReliabilityConfig,
    options: &ProviderRuntimeOptions,
) -> anyhow::Result<Box<dyn Provider>> {
    let max_attempts = reliability.provider_retries.max(1);
    let base_backoff_ms = reliability.provider_backoff_ms;
    let mut last_error = None;

    for attempt in 0..max_attempts {
        match create_provider_with_url_and_options(name, api_key, api_url, options) {
            Ok(provider) => return Ok(provider),
            Err(e) => {
                let kind = classify_provider_error(&e.to_string());
                if !kind.is_cascadable() || attempt + 1 >= max_attempts {
                    return Err(e);
                }
                let backoff = base_backoff_ms * 2u64.saturating_pow(attempt);
                tracing::debug!(
                    provider = name,
                    attempt = attempt + 1,
                    backoff_ms = backoff,
                    "Retrying provider creation"
                );
                std::thread::sleep(std::time::Duration::from_millis(backoff));
                last_error = Some(e);
            }
        }
    }

    Err(last_error.unwrap_or_else(|| anyhow::anyhow!("no attempts made")))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn classify_auth_error() {
        let err = "401 Unauthorized: invalid API key";
        assert_eq!(classify_provider_error(err), ProviderErrorKind::Auth);
    }

    #[test]
    fn classify_rate_limit_error() {
        let err = "429 Too Many Requests";
        assert_eq!(classify_provider_error(err), ProviderErrorKind::RateLimit);
    }

    #[test]
    fn classify_transient_error() {
        let err = "503 Service Unavailable";
        assert_eq!(classify_provider_error(err), ProviderErrorKind::Transient);
    }

    #[test]
    fn classify_unknown_error() {
        let err = "something weird happened";
        assert_eq!(classify_provider_error(err), ProviderErrorKind::Unknown);
    }

    #[test]
    fn auth_is_not_cascadable() {
        assert!(!ProviderErrorKind::Auth.is_cascadable());
    }

    #[test]
    fn rate_limit_is_cascadable() {
        assert!(ProviderErrorKind::RateLimit.is_cascadable());
    }

    #[test]
    fn transient_is_cascadable() {
        assert!(ProviderErrorKind::Transient.is_cascadable());
    }

    #[test]
    fn unknown_is_cascadable() {
        assert!(ProviderErrorKind::Unknown.is_cascadable());
    }
}
