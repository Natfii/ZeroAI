// Copyright (c) 2026 @Natfii. All rights reserved.

//! Discord archive FFI types and inner implementations.
//!
//! Provides typed records for UniFFI binding generation and the inner
//! (non-`#[uniffi::export]`) functions called by the public FFI exports
//! in `lib.rs`. Each inner function is wrapped in `catch_unwind` at the
//! call site to prevent panics from crossing the JNI boundary.

use crate::error::FfiError;
use crate::runtime::{get_or_create_runtime, lock_daemon};
use std::sync::Arc;

// ── FFI record types ─────────────────────────────────────────────────

/// A Discord guild (server) the bot is a member of.
#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiDiscordGuild {
    /// Guild snowflake ID.
    pub id: String,
    /// Guild name.
    pub name: String,
    /// Guild icon hash, or `None` if no icon.
    pub icon: Option<String>,
}

/// A Discord guild text channel for the channel picker.
#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiDiscordChannel {
    /// Channel snowflake ID.
    pub id: String,
    /// Human-readable channel name.
    pub name: String,
    /// Discord channel type integer (0 = text).
    pub channel_type: i32,
}

/// Validated Discord user info.
#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiDiscordUser {
    /// User snowflake ID.
    pub id: String,
    /// Username (handle).
    pub username: String,
    /// Full avatar URL, or `None` if the user has no custom avatar.
    pub avatar_url: Option<String>,
}

/// A search result from the Discord archive.
#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiDiscordSearchResult {
    /// Author display name at time of message.
    pub author: String,
    /// Message text content.
    pub content: String,
    /// Channel snowflake ID where the message was posted.
    pub channel_id: String,
    /// Unix timestamp (seconds since epoch).
    pub timestamp: i64,
}

/// Sync status for a single archived channel.
#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiDiscordSyncStatus {
    /// Channel snowflake ID.
    pub channel_id: String,
    /// Unix timestamp of last sync, or `None` if never synced.
    pub last_sync: Option<i64>,
    /// Whether historical backfill has completed.
    pub backfill_done: bool,
    /// Total messages archived for this channel.
    pub message_count: i64,
}

// ── Inner implementations ────────────────────────────────────────────

/// Fetches the guilds the bot is a member of from the Discord REST API.
///
/// Calls `GET /users/@me/guilds` with Bot authorization and returns a
/// list of partial guild objects (id, name, icon).
pub(crate) fn discord_fetch_bot_guilds_inner(
    bot_token: String,
) -> Result<Vec<FfiDiscordGuild>, FfiError> {
    if bot_token.is_empty() {
        return Err(FfiError::InvalidArgument {
            detail: "bot_token must not be empty".into(),
        });
    }

    let handle = get_or_create_runtime()?;
    handle.block_on(async {
        let client = reqwest::Client::new();
        let response = client
            .get("https://discord.com/api/v10/users/@me/guilds")
            .header("Authorization", format!("Bot {bot_token}"))
            .send()
            .await
            .map_err(|e| FfiError::SpawnError {
                detail: format!("failed to fetch bot guilds: {e}"),
            })?;

        let status = response.status();
        if !status.is_success() {
            let body = response
                .text()
                .await
                .unwrap_or_else(|_| "<unreadable>".into());
            return Err(FfiError::SpawnError {
                detail: format!("Discord API returned {status} for /users/@me/guilds: {body}"),
            });
        }

        let json: serde_json::Value = response.json().await.map_err(|e| FfiError::SpawnError {
            detail: format!("failed to parse guilds JSON: {e}"),
        })?;

        let array = json.as_array().ok_or_else(|| FfiError::SpawnError {
            detail: "expected JSON array from guilds endpoint".into(),
        })?;

        let guilds: Vec<FfiDiscordGuild> = array
            .iter()
            .filter_map(|g| {
                let id = g["id"].as_str().unwrap_or_default();
                let name = g["name"].as_str().unwrap_or_default();
                if id.is_empty() {
                    return None;
                }
                let icon = g["icon"].as_str().map(String::from);
                Some(FfiDiscordGuild {
                    id: id.to_string(),
                    name: name.to_string(),
                    icon,
                })
            })
            .collect();

        Ok(guilds)
    })
}

/// Fetches guild text channels from the Discord REST API.
///
/// Calls `GET /guilds/{guild_id}/channels` with Bot authorization,
/// filters to channel type 0 (text), and maps to [`FfiDiscordChannel`].
pub(crate) fn discord_fetch_guild_channels_inner(
    bot_token: String,
    guild_id: String,
) -> Result<Vec<FfiDiscordChannel>, FfiError> {
    if bot_token.is_empty() {
        return Err(FfiError::InvalidArgument {
            detail: "bot_token must not be empty".into(),
        });
    }
    if guild_id.is_empty() {
        return Err(FfiError::InvalidArgument {
            detail: "guild_id must not be empty".into(),
        });
    }

    let handle = get_or_create_runtime()?;
    handle.block_on(async {
        let client = reqwest::Client::new();
        let url = format!("https://discord.com/api/v10/guilds/{guild_id}/channels");

        let response = client
            .get(&url)
            .header("Authorization", format!("Bot {bot_token}"))
            .send()
            .await
            .map_err(|e| FfiError::SpawnError {
                detail: format!("failed to fetch guild channels: {e}"),
            })?;

        let status = response.status();
        if !status.is_success() {
            let body = response
                .text()
                .await
                .unwrap_or_else(|_| "<unreadable>".into());
            return Err(FfiError::SpawnError {
                detail: format!("Discord API returned {status} for guild {guild_id}: {body}"),
            });
        }

        let json: serde_json::Value = response.json().await.map_err(|e| FfiError::SpawnError {
            detail: format!("failed to parse guild channels JSON: {e}"),
        })?;

        let array = json.as_array().ok_or_else(|| FfiError::SpawnError {
            detail: "expected JSON array from guild channels endpoint".into(),
        })?;

        let channels: Vec<FfiDiscordChannel> = array
            .iter()
            .filter_map(|ch| {
                let ch_type = ch["type"].as_i64().unwrap_or(-1);
                // Type 0 = guild text channel
                if ch_type != 0 {
                    return None;
                }
                let id = ch["id"].as_str().unwrap_or_default();
                let name = ch["name"].as_str().unwrap_or_default();
                if id.is_empty() {
                    return None;
                }
                #[allow(clippy::cast_possible_truncation)]
                Some(FfiDiscordChannel {
                    id: id.to_string(),
                    name: name.to_string(),
                    channel_type: ch_type as i32,
                })
            })
            .collect();

        Ok(channels)
    })
}

/// Validates a Discord user by fetching their profile from the REST API.
///
/// Calls `GET /users/{user_id}` with Bot authorization and extracts the
/// username and avatar URL.
pub(crate) fn discord_validate_user_inner(
    bot_token: String,
    user_id: String,
) -> Result<FfiDiscordUser, FfiError> {
    if bot_token.is_empty() {
        return Err(FfiError::InvalidArgument {
            detail: "bot_token must not be empty".into(),
        });
    }
    if user_id.is_empty() {
        return Err(FfiError::InvalidArgument {
            detail: "user_id must not be empty".into(),
        });
    }

    let handle = get_or_create_runtime()?;
    handle.block_on(async {
        let client = reqwest::Client::new();
        let url = format!("https://discord.com/api/v10/users/{user_id}");

        let response = client
            .get(&url)
            .header("Authorization", format!("Bot {bot_token}"))
            .send()
            .await
            .map_err(|e| FfiError::SpawnError {
                detail: format!("failed to fetch user {user_id}: {e}"),
            })?;

        let status = response.status();
        if !status.is_success() {
            let body = response
                .text()
                .await
                .unwrap_or_else(|_| "<unreadable>".into());
            return Err(FfiError::SpawnError {
                detail: format!("Discord API returned {status} for user {user_id}: {body}"),
            });
        }

        let json: serde_json::Value = response.json().await.map_err(|e| FfiError::SpawnError {
            detail: format!("failed to parse user JSON: {e}"),
        })?;

        let id = json["id"].as_str().unwrap_or_default().to_string();
        let username = json["username"].as_str().unwrap_or_default().to_string();
        let avatar_hash = json["avatar"].as_str();

        let avatar_url = avatar_hash.map(|hash| {
            let ext = if hash.starts_with("a_") { "gif" } else { "png" };
            format!("https://cdn.discordapp.com/avatars/{id}/{hash}.{ext}?size=256")
        });

        Ok(FfiDiscordUser {
            id,
            username,
            avatar_url,
        })
    })
}

/// Configures a channel for archiving in the daemon's Discord archive.
///
/// Delegates to [`DiscordArchive::configure_channel`] via daemon state.
pub(crate) fn discord_configure_channel_inner(
    channel_id: String,
    guild_id: String,
    channel_name: String,
    backfill_depth: String,
) -> Result<(), FfiError> {
    let archive = get_archive()?;
    archive
        .configure_channel(&channel_id, &guild_id, &channel_name, &backfill_depth)
        .map_err(|e| FfiError::SpawnError {
            detail: format!("failed to configure channel: {e}"),
        })
}

/// Removes a channel and all its archived data.
///
/// Delegates to [`DiscordArchive::remove_channel`] via daemon state.
pub(crate) fn discord_remove_channel_inner(channel_id: String) -> Result<(), FfiError> {
    let archive = get_archive()?;
    archive
        .remove_channel(&channel_id)
        .map_err(|e| FfiError::SpawnError {
            detail: format!("failed to remove channel: {e}"),
        })
}

/// Links a Discord DM user ID in runtime state.
///
/// Stores the user ID in the shared Discord DM-link state used by live
/// channel routing. The Kotlin layer persists this separately so the
/// link can be replayed after process restarts.
pub(crate) fn discord_link_dm_user_inner(user_id: String) -> Result<(), FfiError> {
    if user_id.is_empty() {
        return Err(FfiError::InvalidArgument {
            detail: "user_id must not be empty".into(),
        });
    }
    zeroclaw::channels::set_linked_discord_dm_user(Some(user_id));
    Ok(())
}

/// Clears the linked Discord DM user from runtime state.
///
/// Safe to call whether or not the daemon is currently running.
#[allow(clippy::unnecessary_wraps)]
pub(crate) fn discord_unlink_dm_user_inner() -> Result<(), FfiError> {
    zeroclaw::channels::set_linked_discord_dm_user(None);
    Ok(())
}

/// Searches the Discord archive using FTS5 full-text search.
///
/// Delegates to [`DiscordArchive::search`] and maps results to
/// [`FfiDiscordSearchResult`].
pub(crate) fn discord_search_history_inner(
    query: String,
    channel_id: Option<String>,
    days_back: Option<i64>,
    limit: Option<u32>,
) -> Result<Vec<FfiDiscordSearchResult>, FfiError> {
    if query.is_empty() {
        return Err(FfiError::InvalidArgument {
            detail: "search query must not be empty".into(),
        });
    }

    let archive = get_archive()?;

    #[allow(clippy::cast_possible_truncation, clippy::cast_sign_loss)]
    let days_back_u32 = days_back.map(|d| d.max(0) as u32);
    let limit_usize = limit.unwrap_or(50) as usize;

    let results = archive
        .search(&query, channel_id.as_deref(), days_back_u32, limit_usize)
        .map_err(|e| FfiError::SpawnError {
            detail: format!("archive search failed: {e}"),
        })?;

    Ok(results
        .into_iter()
        .map(|m| FfiDiscordSearchResult {
            author: m.author_name,
            content: m.content,
            channel_id: m.channel_id,
            timestamp: m.timestamp,
        })
        .collect())
}

/// Returns sync status for a specific archived channel.
///
/// Combines [`DiscordArchive::get_sync_state`] and
/// [`DiscordArchive::message_count`].
pub(crate) fn discord_get_sync_status_inner(
    channel_id: String,
) -> Result<FfiDiscordSyncStatus, FfiError> {
    let archive = get_archive()?;

    let sync_state = archive
        .get_sync_state(&channel_id)
        .map_err(|e| FfiError::SpawnError {
            detail: format!("failed to get sync state: {e}"),
        })?;

    let message_count = archive
        .message_count(&channel_id)
        .map_err(|e| FfiError::SpawnError {
            detail: format!("failed to count messages: {e}"),
        })?;

    #[allow(clippy::cast_possible_wrap)]
    let count_i64 = message_count as i64;

    match sync_state {
        Some(state) => Ok(FfiDiscordSyncStatus {
            channel_id: state.channel_id,
            last_sync: state.last_sync,
            backfill_done: state.backfill_done,
            message_count: count_i64,
        }),
        None => Ok(FfiDiscordSyncStatus {
            channel_id,
            last_sync: None,
            backfill_done: false,
            message_count: count_i64,
        }),
    }
}

/// Triggers a background backfill task for a specific channel.
///
/// Reads the channel config from the archive to determine the backfill
/// depth, then spawns [`discord_backfill::run_backfill`] on the tokio
/// runtime. The task runs until completion or daemon shutdown.
pub(crate) fn discord_trigger_backfill_inner(channel_id: String) -> Result<(), FfiError> {
    let archive = get_archive()?;

    // Read channel config to determine backfill depth.
    let configs = archive
        .list_channel_configs()
        .map_err(|e| FfiError::SpawnError {
            detail: format!("failed to list channel configs: {e}"),
        })?;

    let channel_config = configs
        .iter()
        .find(|c| c.channel_id == channel_id)
        .ok_or_else(|| FfiError::ConfigError {
            detail: format!("channel {channel_id} is not configured for archiving"),
        })?;

    let depth = channel_config.backfill_depth.clone();

    let cutoff = zeroclaw::channels::discord_backfill::depth_to_cutoff(&depth).ok_or_else(
        || FfiError::ConfigError {
            detail: format!(
                "backfill depth '{depth}' does not require backfill (set to 'none' or unrecognised)"
            ),
        },
    )?;

    // Get bot token from daemon config.
    let bot_token = crate::runtime::with_daemon_config(|config| {
        config
            .channels_config
            .discord
            .as_ref()
            .map(|dc| dc.bot_token.clone())
    })?
    .ok_or_else(|| FfiError::ConfigError {
        detail: "Discord channel is not configured in daemon".into(),
    })?;

    let handle = get_or_create_runtime()?;
    let channel_id_clone = channel_id.clone();

    handle.spawn(async move {
        let client = reqwest::Client::new();
        let result = zeroclaw::channels::discord_backfill::run_backfill(
            &client,
            &bot_token,
            &archive,
            &channel_id_clone,
            cutoff,
            || true, // No battery guard from FFI; Android manages via WorkManager
        )
        .await;

        match result {
            Ok(()) => {
                tracing::info!(
                    channel_id = %channel_id_clone,
                    "FFI-triggered backfill completed"
                );
            }
            Err(e) => {
                tracing::error!(
                    channel_id = %channel_id_clone,
                    error = %e,
                    "FFI-triggered backfill failed"
                );
            }
        }
    });

    Ok(())
}

// ── Helpers ──────────────────────────────────────────────────────────

/// Extracts the `Arc<DiscordArchive>` from daemon state.
///
/// Returns [`FfiError::StateError`] if the daemon is not running or the
/// archive was not initialised (Discord not configured at startup).
fn get_archive() -> Result<Arc<zeroclaw::memory::discord_archive::DiscordArchive>, FfiError> {
    let guard = lock_daemon();
    let state = guard.as_ref().ok_or_else(|| FfiError::StateError {
        detail: "daemon not running".into(),
    })?;
    state.archive.clone().ok_or_else(|| FfiError::StateError {
        detail: "Discord archive not available (Discord not configured)".into(),
    })
}
