/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

//! Auth profile management for reading, writing, and removing OAuth/token profiles.
//! All persistence goes through ZeroClaw's encrypted [`AuthProfilesStore`].

use std::collections::BTreeMap;
use std::path::{Component, Path, PathBuf};

use zeroai::auth_exports::{
    AuthProfile, AuthProfilesData, AuthProfilesStore, AuthService, TokenSet,
    extract_account_email_from_id_token, extract_account_id_from_jwt, profile_id,
    state_dir_from_config,
};

use crate::error::FfiError;
use crate::runtime;

/// A single auth profile entry exposed to Kotlin via UniFFI.
#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiAuthProfile {
    /// Profile ID in `"provider:profile_name"` format.
    pub id: String,
    /// Provider name (e.g. `"openai-codex"`, `"gemini"`).
    pub provider: String,
    /// Human-readable profile display name.
    pub profile_name: String,
    /// Profile kind: `"oauth"` or `"token"`.
    pub kind: String,
    /// Whether this is the active profile for its provider.
    pub is_active: bool,
    /// Token expiry as epoch milliseconds, if available.
    pub expires_at_ms: Option<i64>,
    /// Account identifier or email derived from the provider token set, if available.
    pub account_id: Option<String>,
    /// OAuth scopes granted to this profile as a space-delimited string.
    pub scopes: Option<String>,
    /// JSON object containing non-secret profile metadata.
    pub metadata_json: String,
    /// Profile creation time as epoch milliseconds.
    pub created_at_ms: i64,
    /// Last update time as epoch milliseconds.
    pub updated_at_ms: i64,
}

fn build_store(state_dir: &Path) -> AuthProfilesStore {
    AuthProfilesStore::new(state_dir, true)
}

fn provider_account_id(
    provider: &str,
    access_token: &str,
    id_token: Option<&str>,
) -> Option<String> {
    match provider {
        "gemini" | "google" | "vertex" => id_token.and_then(extract_account_email_from_id_token),
        "openai" | "openai-codex" => extract_account_id_from_jwt(access_token),
        _ => None,
    }
}

fn shared_runtime() -> Result<tokio::runtime::Runtime, FfiError> {
    tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .map_err(|e| FfiError::SpawnError {
            detail: format!("failed to create auth profile runtime: {e}"),
        })
}

fn validate_standalone_state_dir(data_dir: String) -> Result<PathBuf, FfiError> {
    let trimmed = data_dir.trim();
    if trimmed.is_empty() {
        return Err(FfiError::InvalidArgument {
            detail: "data_dir must not be empty".into(),
        });
    }

    let raw_path = PathBuf::from(trimmed);
    if !raw_path.is_absolute() {
        return Err(FfiError::InvalidArgument {
            detail: "data_dir must be an absolute path".into(),
        });
    }

    if raw_path
        .components()
        .any(|component| matches!(component, Component::ParentDir))
    {
        return Err(FfiError::InvalidArgument {
            detail: "data_dir must not contain '..' segments".into(),
        });
    }

    let canonical = std::fs::canonicalize(&raw_path).map_err(|e| FfiError::InvalidArgument {
        detail: format!("data_dir must reference an existing directory: {e}"),
    })?;

    let metadata = std::fs::metadata(&canonical).map_err(|e| FfiError::InvalidArgument {
        detail: format!("failed to inspect data_dir: {e}"),
    })?;
    if !metadata.is_dir() {
        return Err(FfiError::InvalidArgument {
            detail: "data_dir must resolve to a directory".into(),
        });
    }

    Ok(canonical)
}

fn ffi_profile(
    profile: &AuthProfile,
    active_profiles: &BTreeMap<String, String>,
) -> FfiAuthProfile {
    FfiAuthProfile {
        id: profile.id.clone(),
        provider: profile.provider.clone(),
        profile_name: profile.profile_name.clone(),
        kind: serde_json::to_value(profile.kind)
            .ok()
            .and_then(|value| value.as_str().map(ToOwned::to_owned))
            .unwrap_or_else(|| "unknown".to_string()),
        is_active: active_profiles
            .get(&profile.provider)
            .is_some_and(|active_id| active_id == &profile.id),
        expires_at_ms: profile
            .token_set
            .as_ref()
            .and_then(|tokens| tokens.expires_at.as_ref())
            .map(chrono::DateTime::timestamp_millis),
        account_id: profile.account_id.clone(),
        scopes: profile
            .token_set
            .as_ref()
            .and_then(|tokens| tokens.scope.clone()),
        metadata_json: serde_json::to_string(&profile.metadata)
            .unwrap_or_else(|_| "{}".to_string()),
        created_at_ms: profile.created_at.timestamp_millis(),
        updated_at_ms: profile.updated_at.timestamp_millis(),
    }
}

fn ffi_profiles(data: AuthProfilesData) -> Vec<FfiAuthProfile> {
    data.profiles
        .values()
        .map(|profile| ffi_profile(profile, &data.active_profiles))
        .collect()
}

#[cfg(test)]
fn standalone_profiles_path(data_dir: &Path) -> PathBuf {
    data_dir.join("auth-profiles.json")
}

fn build_oauth_profile(
    provider: String,
    profile_name: String,
    access_token: String,
    refresh_token: Option<String>,
    id_token: Option<String>,
    expires_at_ms: Option<i64>,
    scopes: Option<String>,
) -> Result<AuthProfile, FfiError> {
    let provider = provider.trim().to_string();
    let profile_name = profile_name.trim().to_string();
    let access_token = access_token.trim().to_string();

    if provider.is_empty() {
        return Err(FfiError::InvalidArgument {
            detail: "provider must not be empty".into(),
        });
    }
    if profile_name.is_empty() {
        return Err(FfiError::InvalidArgument {
            detail: "profile_name must not be empty".into(),
        });
    }
    if access_token.is_empty() {
        return Err(FfiError::InvalidArgument {
            detail: "access_token must not be empty".into(),
        });
    }

    let expires_at = expires_at_ms.and_then(chrono::DateTime::<chrono::Utc>::from_timestamp_millis);
    let mut profile = AuthProfile::new_oauth(
        &provider,
        &profile_name,
        TokenSet {
            access_token: access_token.clone(),
            refresh_token: refresh_token.filter(|token| !token.trim().is_empty()),
            id_token: id_token.filter(|token| !token.trim().is_empty()),
            expires_at,
            token_type: Some("Bearer".to_string()),
            scope: scopes.filter(|value| !value.trim().is_empty()),
        },
    );
    profile.account_id = provider_account_id(
        &provider,
        &access_token,
        profile
            .token_set
            .as_ref()
            .and_then(|tokens| tokens.id_token.as_deref()),
    );
    Ok(profile)
}

// ── FFI exports ────────────────────────────────────────────────────────────

crate::ffi_export!(
    /// Lists all auth profiles from the daemon's workspace.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::StateError`] if the daemon is not running,
    /// [`crate::FfiError::SpawnError`] on I/O or parse failure, or
    /// [`crate::FfiError::InternalPanic`] if native code panics.
    fn list_auth_profiles() -> Vec<FfiAuthProfile> = list_auth_profiles_inner
);

crate::ffi_export!(
    /// Lists all auth profiles from a standalone app-owned files directory.
    ///
    /// Does not require the daemon to be running. Used by Android UI flows.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::InvalidArgument`] when `data_dir` is invalid,
    /// [`crate::FfiError::SpawnError`] on I/O failure, or
    /// [`crate::FfiError::InternalPanic`] if native code panics.
    fn list_auth_profiles_standalone(data_dir: String) -> Vec<FfiAuthProfile> = list_auth_profiles_standalone_inner
);

crate::ffi_export!(
    /// Returns the Anthropic bearer token from the standalone auth-profile store.
    ///
    /// Anthropic OAuth tokens are long-lived and do not need refresh.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::InvalidArgument`] when `data_dir` is invalid,
    /// [`crate::FfiError::SpawnError`] on I/O failure, or
    /// [`crate::FfiError::InternalPanic`] if native code panics.
    fn get_anthropic_access_token_standalone(data_dir: String) -> Option<String> = get_anthropic_access_token_standalone_inner
);

crate::ffi_export!(
    /// Returns a valid OpenAI access token, refreshing if needed.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::InvalidArgument`] when `data_dir` is invalid,
    /// [`crate::FfiError::SpawnError`] on I/O or token-refresh failure, or
    /// [`crate::FfiError::InternalPanic`] if native code panics.
    fn get_openai_access_token_standalone(data_dir: String) -> Option<String> = get_valid_openai_access_token_standalone_inner
);

crate::ffi_export!(
    /// Returns a valid Gemini access token, refreshing if needed.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::InvalidArgument`] when `data_dir` is invalid,
    /// [`crate::FfiError::SpawnError`] on I/O or token-refresh failure, or
    /// [`crate::FfiError::InternalPanic`] if native code panics.
    fn get_valid_gemini_access_token_standalone(data_dir: String) -> Option<String> = get_valid_gemini_access_token_standalone_inner
);

crate::ffi_export!(
    /// Removes an auth profile from the running daemon's workspace.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::StateError`] if the daemon is not running or
    /// the auth-profiles file does not exist, [`crate::FfiError::SpawnError`]
    /// on I/O, parse, or serialisation failure, or
    /// [`crate::FfiError::InternalPanic`] if native code panics.
    fn remove_auth_profile(provider: String, profile_name: String) -> () = remove_auth_profile_inner
);

crate::ffi_export!(
    /// Writes or updates an OAuth profile in the encrypted Rust-owned auth profile store.
    ///
    /// Does not require the daemon to be running.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::InvalidArgument`] when arguments are invalid,
    /// [`crate::FfiError::SpawnError`] on I/O or serialisation failure, or
    /// [`crate::FfiError::InternalPanic`] if native code panics.
    fn write_auth_profile(
        data_dir: String,
        provider: String,
        profile_name: String,
        access_token: String,
        refresh_token: Option<String>,
        id_token: Option<String>,
        expires_at_ms: Option<i64>,
        scopes: Option<String>,
    ) -> () = write_auth_profile_inner
);

crate::ffi_export!(
    /// Removes an OAuth profile from the encrypted store without requiring the daemon.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::SpawnError`] on I/O or parse failure, or
    /// [`crate::FfiError::InternalPanic`] if native code panics.
    fn remove_auth_profile_standalone(data_dir: String, provider: String, profile_name: String) -> () = remove_auth_profile_standalone_inner
);

crate::ffi_export!(
    /// Merges non-secret metadata entries into a standalone auth profile.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::InvalidArgument`] when arguments are invalid,
    /// [`crate::FfiError::SpawnError`] on I/O or persistence failure, or
    /// [`crate::FfiError::InternalPanic`] if native code panics.
    fn merge_auth_profile_metadata_standalone(data_dir: String, provider: String, profile_name: String, metadata_json: String) -> () = merge_auth_profile_metadata_standalone_inner
);

crate::ffi_export!(
    /// Returns the stored access token for any provider from the standalone store.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::InvalidArgument`] when `data_dir` is invalid,
    /// [`crate::FfiError::SpawnError`] on I/O or read failure, or
    /// [`crate::FfiError::InternalPanic`] if native code panics.
    fn get_access_token_standalone(data_dir: String, provider: String, profile_name: String) -> Option<String> = get_access_token_standalone_inner
);

// ── Inner implementations ──────────────────────────────────────────────────

/// Writes or updates an OAuth profile in the standalone encrypted auth profile store.
#[allow(clippy::too_many_arguments)]
pub(crate) fn write_auth_profile_inner(
    data_dir: String,
    provider: String,
    profile_name: String,
    access_token: String,
    refresh_token: Option<String>,
    id_token: Option<String>,
    expires_at_ms: Option<i64>,
    scopes: Option<String>,
) -> Result<(), FfiError> {
    let state_dir = validate_standalone_state_dir(data_dir)?;
    let profile = build_oauth_profile(
        provider,
        profile_name,
        access_token,
        refresh_token,
        id_token,
        expires_at_ms,
        scopes,
    )?;
    let runtime = shared_runtime()?;
    runtime
        .block_on(build_store(&state_dir).upsert_profile(profile, true))
        .map_err(|e| FfiError::SpawnError {
            detail: format!("failed to persist auth profile: {e}"),
        })
}

/// Removes an OAuth profile from the standalone encrypted auth profile store.
pub(crate) fn remove_auth_profile_standalone_inner(
    data_dir: String,
    provider: String,
    profile_name: String,
) -> Result<(), FfiError> {
    let state_dir = validate_standalone_state_dir(data_dir)?;
    let provider = provider.trim();
    let profile_name = profile_name.trim();
    if provider.is_empty() || profile_name.is_empty() {
        return Err(FfiError::InvalidArgument {
            detail: "provider and profile_name must not be empty".into(),
        });
    }

    let runtime = shared_runtime()?;
    runtime
        .block_on(build_store(&state_dir).remove_profile(&profile_id(provider, profile_name)))
        .map(|_| ())
        .map_err(|e| FfiError::SpawnError {
            detail: format!("failed to remove auth profile: {e}"),
        })
}

/// Merges metadata entries into a standalone encrypted auth profile.
///
/// Blank metadata values remove the key from the stored profile.
pub(crate) fn merge_auth_profile_metadata_standalone_inner(
    data_dir: String,
    provider: String,
    profile_name: String,
    metadata_json: String,
) -> Result<(), FfiError> {
    let state_dir = validate_standalone_state_dir(data_dir)?;
    let provider = provider.trim().to_string();
    let profile_name = profile_name.trim().to_string();
    if provider.is_empty() || profile_name.is_empty() {
        return Err(FfiError::InvalidArgument {
            detail: "provider and profile_name must not be empty".into(),
        });
    }

    let metadata: BTreeMap<String, String> =
        serde_json::from_str(metadata_json.trim()).map_err(|e| FfiError::InvalidArgument {
            detail: format!("metadata_json must be a JSON object: {e}"),
        })?;

    let mut normalized = BTreeMap::new();
    for (key, value) in metadata {
        let trimmed_key = key.trim();
        if trimmed_key.is_empty() {
            return Err(FfiError::InvalidArgument {
                detail: "metadata keys must not be blank".into(),
            });
        }
        normalized.insert(trimmed_key.to_string(), value.trim().to_string());
    }

    let runtime = shared_runtime()?;
    runtime
        .block_on(build_store(&state_dir).update_profile(
            &profile_id(&provider, &profile_name),
            move |profile| {
                for (key, value) in &normalized {
                    if value.is_empty() {
                        profile.metadata.remove(key);
                    } else {
                        profile.metadata.insert(key.clone(), value.clone());
                    }
                }
                Ok(())
            },
        ))
        .map(|_| ())
        .map_err(|e| FfiError::SpawnError {
            detail: format!("failed to merge auth profile metadata: {e}"),
        })
}

/// Lists all auth profiles from the daemon-owned encrypted auth profile store.
pub(crate) fn list_auth_profiles_inner() -> Result<Vec<FfiAuthProfile>, FfiError> {
    let state_dir = runtime::with_daemon_config(state_dir_from_config)?;
    let runtime = shared_runtime()?;
    let data = runtime
        .block_on(build_store(&state_dir).load())
        .map_err(|e| FfiError::SpawnError {
            detail: format!("failed to load auth profiles: {e}"),
        })?;
    Ok(ffi_profiles(data))
}

/// Lists auth profiles directly from the standalone encrypted auth profile store.
pub(crate) fn list_auth_profiles_standalone_inner(
    data_dir: String,
) -> Result<Vec<FfiAuthProfile>, FfiError> {
    let state_dir = validate_standalone_state_dir(data_dir)?;
    let runtime = shared_runtime()?;
    let data = runtime
        .block_on(build_store(&state_dir).load())
        .map_err(|e| FfiError::SpawnError {
            detail: format!("failed to load auth profiles: {e}"),
        })?;
    Ok(ffi_profiles(data))
}

/// Returns the Anthropic bearer token from the standalone encrypted auth-profile store.
///
/// Anthropic OAuth tokens (`sk-ant-oat01-...`) are long-lived and do not need
/// refresh. This simply loads the stored access token so the Android daemon
/// service can inject it into the TOML `api_key` field.
pub(crate) fn get_anthropic_access_token_standalone_inner(
    data_dir: String,
) -> Result<Option<String>, FfiError> {
    let state_dir = validate_standalone_state_dir(data_dir)?;
    let runtime = shared_runtime()?;
    runtime
        .block_on(async move {
            let auth_service = AuthService::new(&state_dir, true);
            auth_service
                .get_provider_bearer_token("anthropic", None)
                .await
        })
        .map_err(|e| FfiError::SpawnError {
            detail: format!("failed to read Anthropic access token: {e}"),
        })
}

/// Returns a valid OpenAI access token from the standalone encrypted auth-profile store.
///
/// OpenAI OAuth tokens are short-lived JWTs that expire quickly. This calls
/// [`AuthService::get_valid_openai_access_token`] which automatically refreshes
/// the token (with backoff and retry) when it is expiring within 90 seconds.
/// The refreshed token is persisted back to the encrypted store.
pub(crate) fn get_valid_openai_access_token_standalone_inner(
    data_dir: String,
) -> Result<Option<String>, FfiError> {
    let state_dir = validate_standalone_state_dir(data_dir)?;
    let runtime = shared_runtime()?;
    runtime
        .block_on(async move {
            let auth_service = AuthService::new(&state_dir, true);
            auth_service.get_valid_openai_access_token(None).await
        })
        .map_err(|e| FfiError::SpawnError {
            detail: format!("failed to read OpenAI access token: {e}"),
        })
}

/// Returns a valid Gemini access token from the standalone encrypted auth-profile store.
///
/// This mirrors the daemon-side managed Gemini token lookup so Android UI flows
/// can call Google Workspace APIs without duplicating token ownership outside
/// the Rust auth-profile store.
pub(crate) fn get_valid_gemini_access_token_standalone_inner(
    data_dir: String,
) -> Result<Option<String>, FfiError> {
    let state_dir = validate_standalone_state_dir(data_dir)?;
    let runtime = shared_runtime()?;
    runtime
        .block_on(async move {
            let auth_service = AuthService::new(&state_dir, true);
            auth_service.get_valid_gemini_access_token(None).await
        })
        .map_err(|e| FfiError::SpawnError {
            detail: format!("failed to read Gemini access token: {e}"),
        })
}

/// Returns the stored access token for any provider from the standalone encrypted
/// auth-profile store.
///
/// The `profile_name` parameter selects a specific profile within the provider
/// namespace. Pass `"default"` to retrieve the default profile.
pub(crate) fn get_access_token_standalone_inner(
    data_dir: String,
    provider: String,
    profile_name: String,
) -> Result<Option<String>, FfiError> {
    let state_dir = validate_standalone_state_dir(data_dir)?;
    let provider = provider.trim().to_string();
    let profile_name_opt = {
        let trimmed = profile_name.trim();
        if trimmed.is_empty() || trimmed == "default" {
            None
        } else {
            Some(trimmed.to_string())
        }
    };
    let runtime = shared_runtime()?;
    runtime
        .block_on(async move {
            let auth_service = AuthService::new(&state_dir, true);
            auth_service
                .get_provider_bearer_token(&provider, profile_name_opt.as_deref())
                .await
        })
        .map_err(|e| FfiError::SpawnError {
            detail: format!("failed to read access token: {e}"),
        })
}

/// Removes an auth profile identified by provider and profile name.
pub(crate) fn remove_auth_profile_inner(
    provider: String,
    profile_name: String,
) -> Result<(), FfiError> {
    let state_dir = runtime::with_daemon_config(state_dir_from_config)?;
    let provider = provider.trim().to_string();
    let profile_name = profile_name.trim().to_string();
    if provider.is_empty() || profile_name.is_empty() {
        return Err(FfiError::InvalidArgument {
            detail: "provider and profile_name must not be empty".into(),
        });
    }

    let runtime = shared_runtime()?;
    runtime
        .block_on(build_store(&state_dir).remove_profile(&profile_id(&provider, &profile_name)))
        .map(|_| ())
        .map_err(|e| FfiError::SpawnError {
            detail: format!("failed to remove auth profile: {e}"),
        })
}


#[cfg(test)]
#[path = "auth_profiles_tests.rs"]
mod tests;
