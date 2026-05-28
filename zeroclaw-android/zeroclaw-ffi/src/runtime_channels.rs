// Copyright (c) 2026 @Natfii. All rights reserved.

//! Channel-related helpers for the daemon runtime.
//!
//! Constructs the channel objects used during `doctor_channels` health
//! checks, snapshots the configured channel set, and serves the
//! identity-binding and allowlist accessors used by the Android UI.

use std::sync::Arc;

use zeroclaw::Config;

use crate::error::FfiError;
use crate::runtime::{primary_alias, with_daemon_config};

/// Constructs all synchronous channels from the given config for health
/// checking.
///
/// Replicates the upstream `collect_configured_channels()` logic (which
/// is private and not accessible from the FFI crate). Only Telegram and
/// Discord remain after upstream channel pruning.
pub(crate) fn collect_channels(
    config: &Config,
) -> Vec<(&'static str, Arc<dyn zeroclaw::channels::Channel>)> {
    // Verified constructor signatures against upstream:
    //   TelegramChannel::new(bot_token, alias, peer_resolver, mention_only) -> Self
    //   DiscordChannel::new(bot_token, guild_ids, alias, peer_resolver, listen_to_bots, mention_only) -> Self
    use zeroclaw::channels::{DiscordChannel, TelegramChannel};

    let mut channels: Vec<(&'static str, Arc<dyn zeroclaw::channels::Channel>)> = Vec::new();

    if let Ok(tg) = primary_alias(&config.channels.telegram, "telegram") {
        // Upstream replaced `allowed_users` with a runtime peer resolver;
        // the Android UI doesn't surface a peer allowlist yet, so we pass
        // an empty resolver (= no allowlist filter).
        let allowed: Vec<String> = Vec::new();
        let resolver: Arc<dyn Fn() -> Vec<String> + Send + Sync> =
            Arc::new(move || allowed.clone());
        channels.push((
            "Telegram",
            Arc::new(TelegramChannel::new(
                tg.bot_token.clone(),
                "default",
                resolver,
                tg.mention_only,
            )),
        ));
    }

    if let Ok(dc) = primary_alias(&config.channels.discord, "discord") {
        let allowed: Vec<String> = Vec::new();
        let resolver: Arc<dyn Fn() -> Vec<String> + Send + Sync> =
            Arc::new(move || allowed.clone());
        let guild_ids = dc.guild_ids.clone();
        channels.push((
            "Discord",
            Arc::new(DiscordChannel::new(
                dc.bot_token.clone(),
                guild_ids,
                "default",
                resolver,
                dc.listen_to_bots,
                dc.mention_only,
            )),
        ));
    }

    channels
}

/// Returns `true` if any real-time channel is configured and needs
/// supervision.
///
/// Only Telegram and Discord remain after upstream channel pruning.
#[allow(dead_code)]
pub(crate) fn has_supervised_channels(config: &Config) -> bool {
    !config.channels.telegram.is_empty() || !config.channels.discord.is_empty()
}

/// Maps a channel name to the upstream allowlist field name for that
/// channel.
///
/// Returns `None` for unrecognised channel names. The returned string
/// matches the struct field name in the upstream `*Config` type
/// (e.g. `"allowed_users"` for Telegram and Discord).
pub(crate) fn allowlist_field_for_channel(channel: &str) -> Option<&'static str> {
    match channel {
        "telegram" | "discord" => Some("allowed_users"),
        _ => None,
    }
}

/// Returns a [`FfiError::ConfigError`] indicating that the given channel
/// is not configured in the running daemon.
fn not_configured(channel: &str) -> FfiError {
    FfiError::ConfigError {
        detail: format!("{channel} is not configured in the running daemon"),
    }
}

/// Appends `user_id` to the in-memory allowlist for `channel_name`.
///
/// Stubbed: `TelegramConfig.allowed_users` and `DiscordConfig.allowed_users`
/// were removed upstream — allowlists now live in a runtime peer
/// resolver. The Android UI doesn't have a peer-resolver surface yet,
/// so this is a no-op that validates the channel name and returns the
/// field name for backwards compatibility.
///
/// # Errors
///
/// Returns [`FfiError::ConfigError`] if `channel_name` is unknown or
/// `user_id` is empty after trimming. Returns
/// [`FfiError::StateError`] if the daemon is not running.
pub(crate) fn bind_channel_identity_inner(
    channel_name: String,
    user_id: String,
) -> Result<String, FfiError> {
    let field =
        allowlist_field_for_channel(&channel_name).ok_or_else(|| FfiError::ConfigError {
            detail: format!("unknown channel: {channel_name}"),
        })?;

    let trimmed = user_id.trim().to_string();
    if trimmed.is_empty() {
        return Err(FfiError::ConfigError {
            detail: "user identity must not be empty".to_string(),
        });
    }

    // `with_daemon_config` enforces the daemon-running invariant the
    // upstream allowlist mutation used to depend on.
    with_daemon_config(|_| Ok(field.to_string()))?
}

/// Returns the current allowlist for `channel_name` from the running
/// daemon's in-memory config.
///
/// Returns an empty `Vec` if the channel is configured but its
/// allowlist is empty.
///
/// # Errors
///
/// Returns [`FfiError::ConfigError`] if `channel_name` is unknown or
/// the channel is not configured. Returns [`FfiError::StateError`] if
/// the daemon is not running.
pub(crate) fn get_channel_allowlist_inner(channel_name: String) -> Result<Vec<String>, FfiError> {
    allowlist_field_for_channel(&channel_name).ok_or_else(|| FfiError::ConfigError {
        detail: format!("unknown channel: {channel_name}"),
    })?;

    with_daemon_config(|config| {
        let cc = &config.channels;
        match channel_name.as_str() {
            "telegram" => {
                if cc.telegram.is_empty() {
                    Err(not_configured(&channel_name))
                } else {
                    Ok(Vec::<String>::new())
                }
            }
            "discord" => {
                if cc.discord.is_empty() {
                    Err(not_configured(&channel_name))
                } else {
                    Ok(Vec::<String>::new())
                }
            }
            _ => Err(FfiError::ConfigError {
                detail: format!("unknown channel: {channel_name}"),
            }),
        }
    })?
}

/// Returns the names of all channels with non-null config sections in
/// the running daemon's parsed TOML.
///
/// Used by the Android UI for per-channel progress tracking during
/// daemon startup.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running, or
/// [`FfiError::StateCorrupted`] if the daemon mutex is poisoned.
pub(crate) fn get_configured_channel_names_inner() -> Result<Vec<String>, FfiError> {
    with_daemon_config(|config| {
        let mut names = Vec::new();
        if !config.channels.telegram.is_empty() {
            names.push("telegram".to_string());
        }
        if !config.channels.discord.is_empty() {
            names.push("discord".to_string());
        }
        names
    })
}
