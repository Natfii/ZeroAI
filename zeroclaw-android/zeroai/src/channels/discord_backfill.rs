// Copyright (c) 2026 Zeroclaw Labs. All rights reserved.

//! Discord historical message backfill engine.
//!
//! Fetches historical messages from the Discord REST API using backward
//! pagination (`before` parameter). Respects Discord rate limits by reading
//! `x-ratelimit-remaining` / `x-ratelimit-reset-after` response headers, and
//! enforces a minimum 2-second inter-page delay to conserve battery on mobile.
//!
//! The main entry point is [`run_backfill`], which loops through message pages,
//! stores them into a [`DiscordArchive`], and updates sync cursors until the
//! configured cutoff timestamp is reached or all history has been retrieved.

use crate::memory::discord_archive::{ArchiveMessage, DiscordArchive};
use anyhow::{bail, Context, Result};
use chrono::Utc;
use std::fmt::Write as _;
use std::time::Duration;

/// Discord REST API base URL for channel messages.
const MESSAGES_ENDPOINT: &str = "https://discord.com/api/v10/channels";

/// Maximum messages per page (Discord API limit).
const PAGE_LIMIT: u32 = 100;

/// Minimum delay between page fetches to conserve battery.
const MIN_PAGE_DELAY: Duration = Duration::from_secs(2);

/// Parse a JSON array from Discord's `GET /channels/{id}/messages` response
/// into a `Vec<ArchiveMessage>`.
///
/// Each element is expected to contain `id`, `channel_id`, `author.id`,
/// `author.username`, `content`, and `timestamp` fields. Messages with empty
/// IDs are silently skipped.
///
/// # Errors
///
/// Returns an error if the top-level value is not a JSON array.
pub fn parse_message_page(json: &serde_json::Value) -> Result<Vec<ArchiveMessage>> {
    let array = json
        .as_array()
        .context("expected JSON array from Discord messages endpoint")?;

    let mut messages = Vec::with_capacity(array.len());

    for obj in array {
        let id = obj["id"].as_str().unwrap_or_default();
        if id.is_empty() {
            continue;
        }

        let channel_id = obj["channel_id"].as_str().unwrap_or_default();
        let guild_id = obj["guild_id"].as_str().unwrap_or_default();
        let author_id = obj["author"]["id"].as_str().unwrap_or_default();
        let author_name = obj["author"]["username"].as_str().unwrap_or_default();
        let content = obj["content"].as_str().unwrap_or_default();
        let timestamp_str = obj["timestamp"].as_str().unwrap_or_default();

        let timestamp = if timestamp_str.is_empty() {
            0
        } else {
            chrono::DateTime::parse_from_rfc3339(timestamp_str)
                .with_context(|| format!("invalid timestamp in message {id}: {timestamp_str}"))?
                .timestamp()
        };

        messages.push(ArchiveMessage {
            id: id.to_string(),
            channel_id: channel_id.to_string(),
            guild_id: guild_id.to_string(),
            author_id: author_id.to_string(),
            author_name: author_name.to_string(),
            content: content.to_string(),
            timestamp,
        });
    }

    Ok(messages)
}

/// Convert a human-readable backfill depth string to a Unix timestamp cutoff.
///
/// Returns `None` for `"none"` or unrecognised values (meaning no backfill).
/// Returns `Some(0)` for `"all"` (fetch the entire history).
pub fn depth_to_cutoff(depth: &str) -> Option<i64> {
    let now = Utc::now().timestamp();
    match depth {
        "3d" => Some(now - 3 * 86_400),
        "7d" => Some(now - 7 * 86_400),
        "30d" => Some(now - 30 * 86_400),
        "90d" => Some(now - 90 * 86_400),
        "all" => Some(0),
        _ => None,
    }
}

/// Fetch a single page of messages from the Discord REST API.
///
/// Calls `GET /channels/{channel_id}/messages?limit=100[&before={id}]` with a
/// `Bot` authorization header. If `x-ratelimit-remaining` is `<= 1`, reads
/// `x-ratelimit-reset-after` and returns it as a suggested wait [`Duration`].
///
/// # Returns
///
/// A tuple of `(messages, optional_rate_limit_wait)`.
///
/// # Errors
///
/// Returns an error on HTTP failures, non-2xx status codes, or JSON parse errors.
pub async fn fetch_message_page(
    client: &reqwest::Client,
    bot_token: &str,
    channel_id: &str,
    before: Option<&str>,
) -> Result<(Vec<ArchiveMessage>, Option<Duration>)> {
    let mut url = format!("{MESSAGES_ENDPOINT}/{channel_id}/messages?limit={PAGE_LIMIT}");
    if let Some(before_id) = before {
        let _ = write!(url, "&before={before_id}");
    }

    let response = client
        .get(&url)
        .header("Authorization", format!("Bot {bot_token}"))
        .send()
        .await
        .context("failed to send Discord messages request")?;

    let status = response.status();
    if !status.is_success() {
        let body = response
            .text()
            .await
            .unwrap_or_else(|_| "<unreadable>".to_string());
        bail!("Discord API returned {status} for channel {channel_id}: {body}");
    }

    let rate_limit_remaining: Option<u32> = response
        .headers()
        .get("x-ratelimit-remaining")
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.parse().ok());

    let rate_limit_reset_after: Option<f64> = response
        .headers()
        .get("x-ratelimit-reset-after")
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.parse().ok());

    let wait = if rate_limit_remaining.map_or(false, |r| r <= 1) {
        rate_limit_reset_after.map(|secs| Duration::from_secs_f64(secs + 0.5))
    } else {
        None
    };

    let json: serde_json::Value = response
        .json()
        .await
        .context("failed to parse Discord messages JSON")?;

    let messages = parse_message_page(&json)?;

    Ok((messages, wait))
}

/// Run backward-pagination backfill for a single Discord channel.
///
/// Fetches message pages from the Discord REST API, stores them into the
/// [`DiscordArchive`], and updates sync cursors until:
/// - an empty page is returned (all history fetched),
/// - the oldest message timestamp is at or before the `cutoff_timestamp`, or
/// - `should_continue` returns `false` (battery guard / shutdown signal).
///
/// The loop respects rate limits from the API and enforces a minimum 2-second
/// delay between pages to conserve battery.
///
/// # Arguments
///
/// * `client` - Shared HTTP client.
/// * `bot_token` - Discord bot token for authentication.
/// * `archive` - Destination archive for persisting messages.
/// * `channel_id` - Discord channel snowflake ID to backfill.
/// * `cutoff_timestamp` - Unix timestamp; stop when messages are this old or older.
/// * `should_continue` - Callback checked before each page fetch; return `false`
///   to pause the backfill (e.g. battery guard).
///
/// # Errors
///
/// Returns an error on HTTP failures or archive write failures.
pub async fn run_backfill<F>(
    client: &reqwest::Client,
    bot_token: &str,
    archive: &DiscordArchive,
    channel_id: &str,
    cutoff_timestamp: i64,
    should_continue: F,
) -> Result<()>
where
    F: Fn() -> bool,
{
    let sync_state = archive.get_sync_state(channel_id)?;
    let mut before_id: Option<String> = sync_state.as_ref().and_then(|s| s.oldest_id.clone());

    tracing::info!(
        channel_id,
        ?before_id,
        cutoff_timestamp,
        "starting backfill"
    );

    let mut total_stored: u64 = 0;

    loop {
        if !should_continue() {
            tracing::info!(channel_id, total_stored, "backfill paused by battery guard");
            return Ok(());
        }

        let (messages, rate_limit_wait) =
            fetch_message_page(client, bot_token, channel_id, before_id.as_deref()).await?;

        if messages.is_empty() {
            tracing::info!(
                channel_id,
                total_stored,
                "backfill complete — no more messages"
            );
            archive.update_sync_state(channel_id, before_id.as_deref(), None, true)?;
            return Ok(());
        }

        let page_count = messages.len();

        let oldest_in_page = messages
            .iter()
            .min_by_key(|m| m.timestamp)
            .expect("non-empty page has at least one message");

        let oldest_id_in_page = oldest_in_page.id.clone();
        let oldest_ts_in_page = oldest_in_page.timestamp;

        archive.store_messages(&messages)?;
        #[allow(clippy::cast_possible_truncation)]
        {
            total_stored += page_count as u64;
        }

        let reached_cutoff = oldest_ts_in_page <= cutoff_timestamp;

        archive.update_sync_state(channel_id, Some(&oldest_id_in_page), None, reached_cutoff)?;

        tracing::info!(
            channel_id,
            page_count,
            total_stored,
            oldest_ts_in_page,
            reached_cutoff,
            "backfill page stored"
        );

        if reached_cutoff {
            tracing::info!(
                channel_id,
                total_stored,
                "backfill complete — cutoff reached"
            );
            return Ok(());
        }

        before_id = Some(oldest_id_in_page);

        let delay = rate_limit_wait
            .map(|rl| rl.max(MIN_PAGE_DELAY))
            .unwrap_or(MIN_PAGE_DELAY);

        tokio::time::sleep(delay).await;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_messages_response() {
        let json = serde_json::json!([{
            "id": "123", "channel_id": "c1", "guild_id": "g1",
            "author": {"id": "u1", "username": "alice"},
            "content": "hello",
            "timestamp": "2026-03-09T10:00:00+00:00"
        }]);
        let msgs = parse_message_page(&json).unwrap();
        assert_eq!(msgs.len(), 1);
        assert_eq!(msgs[0].author_name, "alice");
        assert_eq!(msgs[0].id, "123");
        assert_eq!(msgs[0].channel_id, "c1");
        assert_eq!(msgs[0].guild_id, "g1");
        assert_eq!(msgs[0].author_id, "u1");
        assert_eq!(msgs[0].content, "hello");
        assert!(msgs[0].timestamp > 0);
    }

    #[test]
    fn test_parse_empty_page_signals_done() {
        let json = serde_json::json!([]);
        assert!(parse_message_page(&json).unwrap().is_empty());
    }

    #[test]
    fn test_parse_skips_empty_ids() {
        let json = serde_json::json!([
            {
                "id": "", "channel_id": "c1", "guild_id": "g1",
                "author": {"id": "u1", "username": "ghost"},
                "content": "invisible",
                "timestamp": "2026-01-01T00:00:00+00:00"
            },
            {
                "id": "456", "channel_id": "c1", "guild_id": "g1",
                "author": {"id": "u2", "username": "real"},
                "content": "visible",
                "timestamp": "2026-01-02T00:00:00+00:00"
            }
        ]);
        let msgs = parse_message_page(&json).unwrap();
        assert_eq!(msgs.len(), 1);
        assert_eq!(msgs[0].id, "456");
    }

    #[test]
    fn test_parse_dm_without_guild_id() {
        let json = serde_json::json!([{
            "id": "789", "channel_id": "dm1",
            "author": {"id": "u1", "username": "dm_user"},
            "content": "direct message",
            "timestamp": "2026-03-09T12:00:00+00:00"
        }]);
        let msgs = parse_message_page(&json).unwrap();
        assert_eq!(msgs.len(), 1);
        assert_eq!(msgs[0].guild_id, "");
    }

    #[test]
    fn test_parse_rejects_non_array() {
        let json = serde_json::json!({"error": "not an array"});
        assert!(parse_message_page(&json).is_err());
    }

    #[test]
    fn test_backfill_depth_to_cutoff() {
        let now = Utc::now().timestamp();
        assert_eq!(depth_to_cutoff("none"), None);
        assert!(depth_to_cutoff("3d").unwrap() > now - (4 * 86_400));
        assert!(depth_to_cutoff("7d").unwrap() > now - (8 * 86_400));
        assert!(depth_to_cutoff("30d").unwrap() > now - (31 * 86_400));
        assert!(depth_to_cutoff("90d").unwrap() > now - (91 * 86_400));
        assert_eq!(depth_to_cutoff("all"), Some(0));
        assert_eq!(depth_to_cutoff("unknown"), None);
        assert_eq!(depth_to_cutoff(""), None);
    }
}
