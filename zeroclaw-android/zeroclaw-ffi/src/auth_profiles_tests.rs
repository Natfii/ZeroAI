// Copyright (c) 2026 @Natfii. All rights reserved.

//! Unit tests for `crate::auth_profiles`.

#![allow(clippy::unwrap_used)]

use super::*;
use tempfile::TempDir;

#[test]
fn test_list_profiles_not_running() {
    let result = list_auth_profiles_inner();
    assert!(result.is_err());
    match result.unwrap_err() {
        FfiError::StateError { detail } => {
            assert!(detail.contains("not running"));
        }
        other => panic!("expected StateError, got {other:?}"),
    }
}

#[test]
fn test_remove_profile_not_running() {
    let result = remove_auth_profile_inner("openai".into(), "default".into());
    assert!(result.is_err());
    match result.unwrap_err() {
        FfiError::StateError { detail } => {
            assert!(detail.contains("not running"));
        }
        other => panic!("expected StateError, got {other:?}"),
    }
}

#[test]
fn test_standalone_write_encrypts_secrets_and_lists_profiles() {
    let dir = TempDir::new().unwrap();
    let data_dir = dir.path().to_str().unwrap().to_string();

    write_auth_profile_inner(
        data_dir.clone(),
        "gemini".into(),
        "default".into(),
        "access-token-secret".into(),
        Some("refresh-token-secret".into()),
        Some("header.payload.signature".into()),
        Some(1_750_000_000_000),
        Some("openid email profile".into()),
    )
    .unwrap();

    let auth_profiles = list_auth_profiles_standalone_inner(data_dir.clone()).unwrap();
    assert_eq!(auth_profiles.len(), 1);
    assert_eq!(auth_profiles[0].provider, "gemini");
    assert_eq!(auth_profiles[0].profile_name, "default");
    assert_eq!(
        auth_profiles[0].scopes.as_deref(),
        Some("openid email profile")
    );
    assert_eq!(auth_profiles[0].metadata_json, "{}");

    let persisted = std::fs::read_to_string(standalone_profiles_path(dir.path())).unwrap();
    assert!(!persisted.contains("access-token-secret"));
    assert!(!persisted.contains("refresh-token-secret"));
    assert!(persisted.contains("enc2:"));

    remove_auth_profile_standalone_inner(data_dir.clone(), "gemini".into(), "default".into())
        .unwrap();
    let removed = list_auth_profiles_standalone_inner(data_dir).unwrap();
    assert!(removed.is_empty());
}

#[test]
fn test_standalone_rejects_relative_data_dir() {
    let result = write_auth_profile_inner(
        ".".into(),
        "openai-codex".into(),
        "default".into(),
        "secret".into(),
        None,
        None,
        None,
        None,
    );
    assert!(matches!(result, Err(FfiError::InvalidArgument { .. })));
}

#[test]
fn test_standalone_rejects_parent_dir_segments() {
    let dir = TempDir::new().unwrap();
    let bad = dir
        .path()
        .join("..")
        .join("other")
        .to_string_lossy()
        .to_string();
    let result = list_auth_profiles_standalone_inner(bad);
    assert!(matches!(result, Err(FfiError::InvalidArgument { .. })));
}

#[test]
fn test_standalone_merges_profile_metadata() {
    let dir = TempDir::new().unwrap();
    let data_dir = dir.path().to_str().unwrap().to_string();

    write_auth_profile_inner(
        data_dir.clone(),
        "gemini".into(),
        "default".into(),
        "access-token-secret".into(),
        Some("refresh-token-secret".into()),
        None,
        None,
        Some("openid profile email".into()),
    )
    .unwrap();

    let mut metadata: BTreeMap<String, String> = BTreeMap::new();
    metadata.insert("google_capability_drive".into(), "enabled".into());
    metadata.insert("account_label".into(), "user@example.com".into());
    merge_auth_profile_metadata_standalone_inner(
        data_dir.clone(),
        "gemini".into(),
        "default".into(),
        serde_json::to_string(&metadata).unwrap(),
    )
    .unwrap();

    let profiles = list_auth_profiles_standalone_inner(data_dir.clone()).unwrap();
    let metadata_json: serde_json::Value =
        serde_json::from_str(&profiles[0].metadata_json).unwrap();
    assert_eq!(metadata_json["google_capability_drive"], "enabled");
    assert_eq!(metadata_json["account_label"], "user@example.com");

    let mut removal: BTreeMap<String, String> = BTreeMap::new();
    removal.insert("google_capability_drive".into(), String::new());
    merge_auth_profile_metadata_standalone_inner(
        data_dir,
        "gemini".into(),
        "default".into(),
        serde_json::to_string(&removal).unwrap(),
    )
    .unwrap();

    let profiles_after =
        list_auth_profiles_standalone_inner(dir.path().to_str().unwrap().to_string()).unwrap();
    let metadata_after: serde_json::Value =
        serde_json::from_str(&profiles_after[0].metadata_json).unwrap();
    assert!(metadata_after.get("google_capability_drive").is_none());
    assert_eq!(metadata_after["account_label"], "user@example.com");
}

#[test]
fn test_standalone_lists_profile_metadata_json() {
    let dir = TempDir::new().unwrap();
    let state_dir = dir.path();
    let runtime = shared_runtime().unwrap();
    let mut profile = AuthProfile::new_oauth(
        "gemini",
        "default",
        TokenSet {
            access_token: "access-token-secret".into(),
            refresh_token: Some("refresh-token-secret".into()),
            id_token: None,
            expires_at: None,
            token_type: Some("Bearer".into()),
            scope: Some("openid profile email".into()),
        },
    );
    profile
        .metadata
        .insert("google_capabilities".into(), "gemini,drive".into());
    profile
        .metadata
        .insert("account_label".into(), "user@example.com".into());

    runtime
        .block_on(build_store(state_dir).upsert_profile(profile, true))
        .unwrap();

    let auth_profiles =
        list_auth_profiles_standalone_inner(state_dir.to_string_lossy().to_string()).unwrap();
    assert_eq!(auth_profiles.len(), 1);
    assert_eq!(
        auth_profiles[0].scopes.as_deref(),
        Some("openid profile email")
    );

    let metadata: serde_json::Value =
        serde_json::from_str(&auth_profiles[0].metadata_json).unwrap();
    assert_eq!(metadata["google_capabilities"], "gemini,drive");
    assert_eq!(metadata["account_label"], "user@example.com");
}

#[test]
fn test_standalone_valid_gemini_access_token_returns_none_when_profile_missing() {
    let dir = TempDir::new().unwrap();
    let data_dir = dir.path().to_str().unwrap().to_string();

    let token = get_valid_gemini_access_token_standalone_inner(data_dir).unwrap();

    assert!(token.is_none());
}
