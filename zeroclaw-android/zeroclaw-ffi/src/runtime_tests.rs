// Copyright (c) 2026 @Natfii. All rights reserved.

//! Unit tests for `crate::runtime`.

#![allow(clippy::unwrap_used)]

use super::*;
use crate::runtime_channels::allowlist_field_for_channel;

#[test]
fn test_allowlist_field_telegram() {
    assert_eq!(
        allowlist_field_for_channel("telegram"),
        Some("allowed_users")
    );
}

#[test]
fn test_allowlist_field_discord() {
    assert_eq!(
        allowlist_field_for_channel("discord"),
        Some("allowed_users")
    );
}

#[test]
fn test_allowlist_field_unknown() {
    assert_eq!(allowlist_field_for_channel("carrier_pigeon"), None);
}

#[test]
fn test_bind_channel_no_daemon() {
    let result = bind_channel_identity_inner("telegram".into(), "alice".into());
    assert!(result.is_err());
    match result.unwrap_err() {
        FfiError::StateError { detail } => {
            assert!(detail.contains("not running"));
        }
        other => panic!("expected StateError, got {other:?}"),
    }
}

#[test]
fn test_bind_channel_unknown() {
    let result = bind_channel_identity_inner("carrier_pigeon".into(), "alice".into());
    assert!(result.is_err());
    match result.unwrap_err() {
        FfiError::ConfigError { detail } => {
            assert!(detail.contains("unknown channel"));
        }
        other => panic!("expected ConfigError, got {other:?}"),
    }
}

#[test]
fn test_bind_channel_empty_identity() {
    let result = bind_channel_identity_inner("telegram".into(), "   ".into());
    assert!(result.is_err());
    match result.unwrap_err() {
        FfiError::ConfigError { detail } => {
            assert!(detail.contains("must not be empty"));
        }
        other => panic!("expected ConfigError, got {other:?}"),
    }
}

#[test]
fn test_get_allowlist_no_daemon() {
    let result = get_channel_allowlist_inner("telegram".into());
    assert!(result.is_err());
    match result.unwrap_err() {
        FfiError::StateError { detail } => {
            assert!(detail.contains("not running"));
        }
        other => panic!("expected StateError, got {other:?}"),
    }
}

#[test]
fn test_get_allowlist_unknown_channel() {
    let result = get_channel_allowlist_inner("carrier_pigeon".into());
    assert!(result.is_err());
    match result.unwrap_err() {
        FfiError::ConfigError { detail } => {
            assert!(detail.contains("unknown channel"));
        }
        other => panic!("expected ConfigError, got {other:?}"),
    }
}

#[test]
fn test_swap_provider_no_daemon() {
    let result = swap_provider_inner("anthropic".into(), "claude-sonnet-4".into(), None);
    assert!(result.is_err());
    match result.unwrap_err() {
        FfiError::StateError { detail } => {
            assert!(detail.contains("not running"));
        }
        other => panic!("expected StateError, got {other:?}"),
    }
}

#[test]
fn test_collect_channels_empty_config() {
    let config: Config = toml::from_str("default_temperature = 0.7").unwrap();
    let channels = collect_channels(&config);
    assert!(
        channels.is_empty(),
        "expected no channels from default config, got {}",
        channels.len()
    );
}

#[test]
fn test_collect_channels_with_telegram() {
    let toml_str = r#"
default_temperature = 0.7

[channels]
cli = true

[channels_config.telegram]
bot_token = "fake:token"
allowed_users = ["123"]
mention_only = false
"#;
    let config: Config = toml::from_str(toml_str).unwrap();
    let channels = collect_channels(&config);
    assert_eq!(channels.len(), 1);
    assert_eq!(channels[0].0, "Telegram");
}

#[test]
fn test_collect_channels_multiple() {
    let toml_str = r#"
default_temperature = 0.7

[channels]
cli = true

[channels_config.telegram]
bot_token = "fake:token"
allowed_users = []
mention_only = false

[channels_config.discord]
bot_token = "fake_discord_token"
allowed_users = []
listen_to_bots = false
mention_only = false
"#;
    let config: Config = toml::from_str(toml_str).unwrap();
    let channels = collect_channels(&config);
    assert_eq!(channels.len(), 2);
    let names: Vec<&str> = channels.iter().map(|(n, _)| *n).collect();
    assert!(names.contains(&"Telegram"));
    assert!(names.contains(&"Discord"));
}

#[test]
fn test_doctor_channels_no_daemon_empty_toml() {
    let result = doctor_channels_inner(String::new(), "/tmp/test".into());
    assert!(result.is_err());
    match result.unwrap_err() {
        FfiError::StateError { detail } => {
            assert!(detail.contains("not running"));
        }
        other => panic!("expected StateError, got {other:?}"),
    }
}

#[test]
fn test_doctor_channels_no_channels_configured() {
    let toml_str = "default_temperature = 0.7\n";
    let result = doctor_channels_inner(toml_str.to_string(), "/tmp/test".into());
    let json_str = result.unwrap();
    let arr: Vec<serde_json::Value> = serde_json::from_str(&json_str).unwrap();
    assert_eq!(arr.len(), 1);
    assert_eq!(arr[0]["name"], "channels");
    assert_eq!(arr[0]["status"], "healthy");
    assert_eq!(arr[0]["detail"], "No channels configured");
}
