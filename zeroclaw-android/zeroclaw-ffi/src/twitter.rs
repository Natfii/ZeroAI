// Copyright (c) 2026 @Natfii. All rights reserved.

//! Twitter browse tool FFI records and inner functions.
//!
//! Exposes the daemon's `TwitterBrowseConfig` to Kotlin via UniFFI
//! records, and provides inner functions for reading, updating, and
//! verifying Twitter cookie-based authentication.

use crate::error::FfiError;
use crate::runtime;

/// Twitter browse tool configuration state exposed to Kotlin.
#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiTwitterBrowseConfig {
    /// Whether the twitter browse tool is enabled.
    pub enabled: bool,
    /// Whether a non-empty cookie string is configured.
    pub has_cookie: bool,
    /// Maximum items returned per browse call.
    pub max_items: u32,
    /// Request timeout in seconds.
    pub timeout_secs: u32,
}

/// Twitter user info returned after verifying a connection.
#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiTwitterUser {
    /// Twitter handle or numeric user ID extracted from the cookie.
    pub handle: String,
    /// Display name (empty until a live profile fetch is implemented).
    pub display_name: String,
}

/// Reads the current twitter browse config from the daemon.
pub(crate) fn get_twitter_browse_config_inner() -> Result<FfiTwitterBrowseConfig, FfiError> {
    runtime::with_daemon_config(|config| FfiTwitterBrowseConfig {
        enabled: config.twitter_browse.enabled,
        has_cookie: config
            .twitter_browse
            .cookie_string
            .as_ref()
            .is_some_and(|s| !s.is_empty()),
        max_items: u32::try_from(config.twitter_browse.max_items.min(u32::MAX as usize))
            .unwrap_or(u32::MAX),
        timeout_secs: u32::try_from(config.twitter_browse.timeout_secs.min(u64::from(u32::MAX)))
            .unwrap_or(u32::MAX),
    })
}

/// Hot-swaps the cookie string on the daemon's in-memory config.
pub(crate) fn set_twitter_browse_cookie_inner(cookie_string: String) -> Result<(), FfiError> {
    runtime::with_daemon_config_mut(|config| {
        config.twitter_browse.cookie_string = Some(cookie_string);
    })
}

/// Clears the cookie from the daemon's in-memory config.
pub(crate) fn clear_twitter_browse_cookie_inner() -> Result<(), FfiError> {
    runtime::with_daemon_config_mut(|config| {
        config.twitter_browse.cookie_string = None;
    })
}

/// Hot-updates the twitter browse tool config in the daemon's memory.
pub(crate) fn update_twitter_browse_config_inner(
    enabled: bool,
    max_items: u32,
    timeout_secs: u32,
) -> Result<(), FfiError> {
    runtime::with_daemon_config_mut(|config| {
        config.twitter_browse.enabled = enabled;
        config.twitter_browse.max_items = max_items as usize;
        config.twitter_browse.timeout_secs = u64::from(timeout_secs);
    })
}

/// Verifies a Twitter cookie string by checking for required cookies.
///
/// Validates that the cookie string contains `ct0` and `auth_token`,
/// and extracts the user ID from the `twid` cookie if present.
pub(crate) fn verify_twitter_connection_inner(
    cookie_string: String,
) -> Result<FfiTwitterUser, FfiError> {
    let cookies: std::collections::HashMap<String, String> = cookie_string
        .split(';')
        .filter_map(|pair| {
            let mut parts = pair.trim().splitn(2, '=');
            let key = parts.next()?.trim().to_string();
            let value = parts.next()?.trim().to_string();
            Some((key, value))
        })
        .collect();

    if !cookies.contains_key("ct0") || !cookies.contains_key("auth_token") {
        return Err(FfiError::InvalidArgument {
            detail: "cookie string must contain ct0 and auth_token".into(),
        });
    }

    let handle = cookies
        .get("twid")
        .and_then(|twid| {
            twid.strip_prefix("u%3D")
                .or_else(|| twid.strip_prefix("u="))
        })
        .unwrap_or("unknown")
        .to_string();

    Ok(FfiTwitterUser {
        handle,
        display_name: String::new(),
    })
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    #[test]
    fn test_verify_missing_ct0() {
        let result = verify_twitter_connection_inner("auth_token=abc123".into());
        assert!(result.is_err());
        match result.unwrap_err() {
            FfiError::InvalidArgument { detail } => {
                assert!(detail.contains("ct0"));
            }
            other => panic!("expected InvalidArgument, got {other:?}"),
        }
    }

    #[test]
    fn test_verify_missing_auth_token() {
        let result = verify_twitter_connection_inner("ct0=csrf_value".into());
        assert!(result.is_err());
        match result.unwrap_err() {
            FfiError::InvalidArgument { detail } => {
                assert!(detail.contains("auth_token"));
            }
            other => panic!("expected InvalidArgument, got {other:?}"),
        }
    }

    #[test]
    fn test_verify_valid_with_twid() {
        let cookie = "ct0=csrf; auth_token=session; twid=u%3D12345";
        let user = verify_twitter_connection_inner(cookie.into()).unwrap();
        assert_eq!(user.handle, "12345");
        assert!(user.display_name.is_empty());
    }

    #[test]
    fn test_verify_valid_without_twid() {
        let cookie = "ct0=csrf; auth_token=session";
        let user = verify_twitter_connection_inner(cookie.into()).unwrap();
        assert_eq!(user.handle, "unknown");
    }

    #[test]
    fn test_verify_twid_plain_prefix() {
        let cookie = "ct0=csrf; auth_token=session; twid=u=67890";
        let user = verify_twitter_connection_inner(cookie.into()).unwrap();
        assert_eq!(user.handle, "67890");
    }

    #[test]
    fn test_get_config_no_daemon() {
        let result = get_twitter_browse_config_inner();
        assert!(result.is_err());
        match result.unwrap_err() {
            FfiError::StateError { detail } => {
                assert!(detail.contains("not running"));
            }
            other => panic!("expected StateError, got {other:?}"),
        }
    }
}
