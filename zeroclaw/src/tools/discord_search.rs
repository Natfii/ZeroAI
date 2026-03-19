// Copyright (c) 2026 Zeroclaw Labs. All rights reserved.

//! Agent tool for searching archived Discord message history.
//!
//! Exposes `search_discord_history` to the LLM tool registry so the agent
//! can perform deep FTS5-backed searches across the [`DiscordArchive`].

use anyhow::{bail, Result};
use serde::{Deserialize, Serialize};
use serde_json::json;

use crate::memory::discord_archive::DiscordArchive;

/// Maximum number of results the tool will return regardless of the
/// caller-requested limit.
const MAX_LIMIT: usize = 25;

/// Default number of results when the caller omits `limit`.
fn default_limit() -> usize {
    10
}

/// Arguments accepted by the `search_discord_history` tool.
#[derive(Debug, Deserialize)]
pub struct DiscordSearchArgs {
    /// Full-text search query string.
    pub query: String,
    /// Optional channel ID to restrict the search to.
    #[serde(default)]
    pub channel: Option<String>,
    /// Only search messages from the last N days.
    #[serde(default)]
    pub days_back: Option<i64>,
    /// Maximum number of results to return (default 10, capped at 25).
    #[serde(default = "default_limit")]
    pub limit: usize,
}

impl DiscordSearchArgs {
    /// Deserialise from a [`serde_json::Value`] (the format the agent
    /// runtime passes tool arguments in).
    pub fn from_value(value: &serde_json::Value) -> Result<Self> {
        serde_json::from_value(value.clone()).map_err(|e| anyhow::anyhow!("{e}"))
    }
}

/// A single search result returned to the agent.
#[derive(Debug, Serialize)]
pub struct DiscordSearchResult {
    /// Display name of the message author.
    pub author: String,
    /// Message text content.
    pub content: String,
    /// Channel snowflake ID where the message was posted.
    pub channel_id: String,
    /// Unix timestamp (seconds since epoch).
    pub timestamp: i64,
}

/// Execute a discord archive search with the given arguments.
///
/// Delegates to [`DiscordArchive::search`], capping the limit at
/// [`MAX_LIMIT`] and converting `days_back` from `i64` to `u32`.
pub fn execute(
    archive: &DiscordArchive,
    args: &DiscordSearchArgs,
) -> Result<Vec<DiscordSearchResult>> {
    if args.query.is_empty() {
        bail!("query must not be empty");
    }

    let limit = args.limit.min(MAX_LIMIT);

    #[allow(clippy::cast_sign_loss, clippy::cast_possible_truncation)]
    let days_back = args.days_back.map(|d| {
        if d <= 0 {
            1u32
        } else {
            d.min(i64::from(u32::MAX)) as u32
        }
    });

    let messages = archive.search(&args.query, args.channel.as_deref(), days_back, limit)?;

    let results = messages
        .into_iter()
        .map(|m| DiscordSearchResult {
            author: m.author_name,
            content: m.content,
            channel_id: m.channel_id,
            timestamp: m.timestamp,
        })
        .collect();

    Ok(results)
}

/// Returns the JSON tool definition for the agent's tool registry.
///
/// This schema is presented to the LLM so it knows the tool's name,
/// purpose, and accepted parameters.
pub fn tool_definition() -> serde_json::Value {
    json!({
        "name": "search_discord_history",
        "description": "Search the archived Discord message history for relevant conversations.",
        "parameters": {
            "type": "object",
            "properties": {
                "query": {
                    "type": "string",
                    "description": "Search text"
                },
                "channel": {
                    "type": "string",
                    "description": "Optional channel ID filter"
                },
                "days_back": {
                    "type": "integer",
                    "description": "Only search last N days. Default: 30"
                },
                "limit": {
                    "type": "integer",
                    "description": "Max results. Default: 10, max: 25"
                }
            },
            "required": ["query"]
        }
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_tool_args() {
        let args = serde_json::json!({
            "query": "deploy staging",
            "channel": "c1",
            "days_back": 7,
            "limit": 5
        });
        let parsed = DiscordSearchArgs::from_value(&args).unwrap();
        assert_eq!(parsed.query, "deploy staging");
        assert_eq!(parsed.channel, Some("c1".to_string()));
        assert_eq!(parsed.days_back, Some(7));
        assert_eq!(parsed.limit, 5);
    }

    #[test]
    fn test_parse_minimal_args() {
        let args = serde_json::json!({"query": "hello"});
        let parsed = DiscordSearchArgs::from_value(&args).unwrap();
        assert_eq!(parsed.query, "hello");
        assert_eq!(parsed.channel, None);
        assert_eq!(parsed.days_back, None);
        assert_eq!(parsed.limit, 10);
    }

    #[test]
    fn test_execute_with_archive() {
        use crate::memory::discord_archive::{ArchiveMessage, DiscordArchive};

        let dir = tempfile::TempDir::new().unwrap();
        let archive = DiscordArchive::open(dir.path()).unwrap();
        archive
            .store_messages(&[ArchiveMessage {
                id: "1".into(),
                channel_id: "c1".into(),
                guild_id: "g1".into(),
                author_id: "u1".into(),
                author_name: "alice".into(),
                content: "deployment is broken".into(),
                timestamp: chrono::Utc::now().timestamp(),
            }])
            .unwrap();

        let args = DiscordSearchArgs {
            query: "deployment".into(),
            channel: None,
            days_back: None,
            limit: 10,
        };
        let results = execute(&archive, &args).unwrap();
        assert_eq!(results.len(), 1);
        assert_eq!(results[0].author, "alice");
    }

    #[test]
    fn test_tool_definition_has_required_fields() {
        let def = tool_definition();
        assert_eq!(def["name"], "search_discord_history");
        assert!(def["parameters"]["required"]
            .as_array()
            .unwrap()
            .contains(&serde_json::json!("query")));
    }

    #[test]
    fn test_limit_capped_at_max() {
        use crate::memory::discord_archive::{ArchiveMessage, DiscordArchive};

        let dir = tempfile::TempDir::new().unwrap();
        let archive = DiscordArchive::open(dir.path()).unwrap();

        let messages: Vec<ArchiveMessage> = (0..30)
            .map(|i| ArchiveMessage {
                id: format!("m{i}"),
                channel_id: "c1".into(),
                guild_id: "g1".into(),
                author_id: "u1".into(),
                author_name: "bot".into(),
                content: format!("test message {i}"),
                timestamp: chrono::Utc::now().timestamp() + i64::from(i),
            })
            .collect();
        archive.store_messages(&messages).unwrap();

        let args = DiscordSearchArgs {
            query: "test".into(),
            channel: None,
            days_back: None,
            limit: 100,
        };
        let results = execute(&archive, &args).unwrap();
        assert!(results.len() <= MAX_LIMIT);
    }

    #[test]
    fn test_empty_query_rejected() {
        use crate::memory::discord_archive::DiscordArchive;

        let dir = tempfile::TempDir::new().unwrap();
        let archive = DiscordArchive::open(dir.path()).unwrap();

        let args = DiscordSearchArgs {
            query: String::new(),
            channel: None,
            days_back: None,
            limit: 10,
        };
        let result = execute(&archive, &args);
        assert!(result.is_err());
    }
}
