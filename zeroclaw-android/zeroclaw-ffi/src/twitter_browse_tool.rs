// Copyright (c) 2026 @Natfii. All rights reserved.

//! Read-only X (Twitter) browsing tool.
//!
//! Replaces the upstream `tools::twitter_browse` that was deleted during
//! the zeroclaw workspace split. Hits X's public syndication endpoint
//! (`syndication.twitter.com/srv/timeline-profile/screen-name/<handle>`)
//! and parses the embedded `__NEXT_DATA__` JSON for recent tweets.
//!
//! The syndication endpoint serves the same payload as the embeddable
//! tweet widgets used across the web -- public-only, no user auth, and
//! considerably more stable than the cookie-backed GraphQL routes that
//! the original upstream tool walked. The `[twitter_browse]
//! cookie_string` config is still plumbed through `TwitterContributor`
//! and is intended for a future cookie-auth tier; v1 ignores it.
//!
//! Only one tool is exposed: `twitter_read_profile`. Add a separate
//! search tool later (it needs GraphQL + cookie auth).

use crate::FfiError;
use anyhow::Context;
use async_trait::async_trait;
use serde_json::json;
use std::time::Duration;
use zeroclaw::tools::{Tool, ToolResult};
use zeroclaw_api::attribution::{Attributable, Role, ToolKind};

/// Maximum tweets to return per call, regardless of caller request.
const MAX_TWEETS: usize = 50;

/// Default tweets to return when caller omits a count.
const DEFAULT_TWEETS: usize = 10;

/// HTTP request timeout for the syndication endpoint.
const SYNDICATION_TIMEOUT: Duration = Duration::from_secs(15);

/// Maximum response body size (1 MB) — syndication payloads are well
/// under 200 KB even for active accounts, but the cap protects against
/// a hostile or malformed upstream response.
const MAX_RESPONSE_BYTES: usize = 1_048_576;

/// Reads recent public tweets from a user's X profile.
pub(crate) struct TwitterReadProfileTool;

impl Attributable for TwitterReadProfileTool {
    fn role(&self) -> Role {
        Role::Tool(ToolKind::Plugin)
    }
    fn alias(&self) -> &str {
        "twitter_read_profile"
    }
}

#[async_trait]
impl Tool for TwitterReadProfileTool {
    fn name(&self) -> &str {
        "twitter_read_profile"
    }

    fn description(&self) -> &str {
        "Read recent public tweets from an X (Twitter) user's profile. \
         Returns a JSON array of tweets with text, timestamp, like count, \
         and repost count. Public profiles only -- protected accounts \
         return an empty list. Subject to X's public syndication endpoint \
         which may change without notice."
    }

    fn parameters_schema(&self) -> serde_json::Value {
        json!({
            "type": "object",
            "properties": {
                "handle": {
                    "type": "string",
                    "description": "X handle without the @ sign (e.g. \"NatfiiArt\")."
                },
                "limit": {
                    "type": "integer",
                    "description": "Number of recent tweets to return (1-50, default 10).",
                    "default": DEFAULT_TWEETS
                }
            },
            "required": ["handle"]
        })
    }

    async fn execute(&self, args: serde_json::Value) -> anyhow::Result<ToolResult> {
        let handle = args
            .get("handle")
            .and_then(|v| v.as_str())
            .map(str::trim)
            .map(|s| s.trim_start_matches('@'))
            .filter(|s| !s.is_empty())
            .map(str::to_string)
            .ok_or_else(|| anyhow::anyhow!("missing or empty `handle`"))?;
        if !is_valid_handle(&handle) {
            anyhow::bail!("invalid handle '{handle}': must be 1-15 chars, letters/digits/underscore only");
        }
        let limit = args
            .get("limit")
            .and_then(serde_json::Value::as_u64)
            .map(|n| n as usize)
            .unwrap_or(DEFAULT_TWEETS)
            .clamp(1, MAX_TWEETS);

        match fetch_profile_tweets(&handle, limit).await {
            Ok(tweets) => Ok(ToolResult {
                success: true,
                output: serde_json::to_string(&tweets)
                    .context("serialise tweets")?,
                error: None,
            }),
            Err(e) => Ok(ToolResult {
                success: false,
                output: String::new(),
                error: Some(format!("{e}")),
            }),
        }
    }
}

/// Returned `Vec<Box<dyn Tool>>` for injection via `session_registry`.
pub(crate) fn create_twitter_browse_tools() -> Vec<Box<dyn Tool>> {
    vec![Box::new(TwitterReadProfileTool)]
}

/// Validates that a handle matches X's screen-name rules.
fn is_valid_handle(handle: &str) -> bool {
    !handle.is_empty()
        && handle.len() <= 15
        && handle
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '_')
}

/// Calls the syndication endpoint and returns up to `limit` parsed tweets.
async fn fetch_profile_tweets(
    handle: &str,
    limit: usize,
) -> Result<Vec<SyndicationTweet>, FfiError> {
    let url = format!(
        "https://syndication.twitter.com/srv/timeline-profile/screen-name/{handle}?showReplies=false"
    );

    let client = reqwest::Client::builder()
        .timeout(SYNDICATION_TIMEOUT)
        .user_agent("Mozilla/5.0 (Linux; Android 15) ZeroAI/1.0")
        .build()
        .map_err(|e| FfiError::NetworkError {
            detail: format!("failed to build HTTP client: {e}"),
        })?;

    let resp = client
        .get(&url)
        .send()
        .await
        .map_err(|e| FfiError::NetworkError {
            detail: format!("syndication request failed: {e}"),
        })?;

    if !resp.status().is_success() {
        return Err(FfiError::NetworkError {
            detail: format!("syndication returned HTTP {}", resp.status()),
        });
    }

    let body = read_capped_text(resp).await?;
    let next_data = extract_next_data(&body).ok_or_else(|| FfiError::NetworkError {
        detail: "syndication response did not contain __NEXT_DATA__".into(),
    })?;
    let json: serde_json::Value =
        serde_json::from_str(next_data).map_err(|e| FfiError::NetworkError {
            detail: format!("invalid __NEXT_DATA__ JSON: {e}"),
        })?;

    Ok(parse_tweets(&json, limit))
}

/// Reads a response body capped at [`MAX_RESPONSE_BYTES`] as UTF-8 text.
async fn read_capped_text(resp: reqwest::Response) -> Result<String, FfiError> {
    let bytes = resp
        .bytes()
        .await
        .map_err(|e| FfiError::NetworkError {
            detail: format!("body read failed: {e}"),
        })?;
    if bytes.len() > MAX_RESPONSE_BYTES {
        return Err(FfiError::NetworkError {
            detail: format!(
                "syndication body exceeded {MAX_RESPONSE_BYTES} bytes ({} got)",
                bytes.len()
            ),
        });
    }
    String::from_utf8(bytes.to_vec()).map_err(|e| FfiError::NetworkError {
        detail: format!("non-UTF-8 body: {e}"),
    })
}

/// Extracts the inner JSON of the `<script id="__NEXT_DATA__">` tag.
fn extract_next_data(html: &str) -> Option<&str> {
    let needle = r#"id="__NEXT_DATA__" type="application/json">"#;
    let start_idx = html.find(needle)? + needle.len();
    let tail = &html[start_idx..];
    let end_idx = tail.find("</script>")?;
    Some(&tail[..end_idx])
}

/// Walks the syndication payload's known shape and returns up to `limit`
/// flattened tweets. Tolerant of missing fields — out-of-shape entries
/// are silently dropped.
fn parse_tweets(root: &serde_json::Value, limit: usize) -> Vec<SyndicationTweet> {
    let entries = root
        .pointer("/props/pageProps/timeline/entries")
        .and_then(|v| v.as_array());
    let Some(entries) = entries else {
        return Vec::new();
    };

    entries
        .iter()
        .filter_map(|e| e.get("content").and_then(|c| c.get("tweet")))
        .take(limit)
        .filter_map(|t| {
            let text = t
                .get("text")
                .and_then(|v| v.as_str())
                .map(str::to_string)?;
            let created_at = t
                .get("created_at")
                .and_then(|v| v.as_str())
                .map(str::to_string)
                .unwrap_or_default();
            let likes = t
                .get("favorite_count")
                .and_then(serde_json::Value::as_u64)
                .unwrap_or(0);
            let retweets = t
                .get("retweet_count")
                .and_then(serde_json::Value::as_u64)
                .unwrap_or(0);
            let replies = t
                .get("conversation_count")
                .and_then(serde_json::Value::as_u64)
                .unwrap_or(0);
            Some(SyndicationTweet {
                text,
                created_at,
                likes,
                retweets,
                replies,
            })
        })
        .collect()
}

#[derive(Debug, Clone, serde::Serialize)]
struct SyndicationTweet {
    text: String,
    created_at: String,
    likes: u64,
    retweets: u64,
    replies: u64,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn handle_validation_accepts_underscore_and_alnum() {
        assert!(is_valid_handle("NatfiiArt"));
        assert!(is_valid_handle("user_123"));
        assert!(is_valid_handle("a"));
    }

    #[test]
    fn handle_validation_rejects_invalid() {
        assert!(!is_valid_handle(""));
        assert!(!is_valid_handle("with space"));
        assert!(!is_valid_handle("hyphens-bad"));
        assert!(!is_valid_handle("dots.bad"));
        assert!(!is_valid_handle("toolongtoolongtoolong"));
    }

    #[test]
    fn extract_next_data_pulls_inner_payload() {
        let html = r#"<html><head></head><body>
            <script id="__NEXT_DATA__" type="application/json">{"hello":"world"}</script>
            </body></html>"#;
        assert_eq!(extract_next_data(html), Some(r#"{"hello":"world"}"#));
    }

    #[test]
    fn extract_next_data_returns_none_when_absent() {
        assert!(extract_next_data("<html>no script</html>").is_none());
    }

    #[test]
    fn parse_tweets_walks_real_shape() {
        let payload = serde_json::json!({
            "props": {
                "pageProps": {
                    "timeline": {
                        "entries": [
                            {
                                "content": {
                                    "tweet": {
                                        "text": "hello world",
                                        "created_at": "2026-05-27T10:00:00Z",
                                        "favorite_count": 42,
                                        "retweet_count": 7,
                                        "conversation_count": 3
                                    }
                                }
                            },
                            {
                                "content": {
                                    "tweet": {
                                        "text": "another tweet",
                                        "created_at": "2026-05-26T18:30:00Z"
                                    }
                                }
                            },
                            { "content": { "ad": { "blocked": true } } }
                        ]
                    }
                }
            }
        });
        let tweets = parse_tweets(&payload, 10);
        assert_eq!(tweets.len(), 2);
        assert_eq!(tweets[0].text, "hello world");
        assert_eq!(tweets[0].likes, 42);
        assert_eq!(tweets[1].text, "another tweet");
        assert_eq!(tweets[1].likes, 0);
    }

    #[test]
    fn parse_tweets_respects_limit() {
        let entries: Vec<_> = (0..20)
            .map(|i| {
                serde_json::json!({
                    "content": {
                        "tweet": {
                            "text": format!("tweet {i}"),
                            "created_at": "2026-05-27T10:00:00Z"
                        }
                    }
                })
            })
            .collect();
        let payload = serde_json::json!({
            "props": {"pageProps": {"timeline": {"entries": entries}}}
        });
        assert_eq!(parse_tweets(&payload, 5).len(), 5);
    }
}
