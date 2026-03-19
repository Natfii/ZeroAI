// Copyright (c) 2026 Zeroclaw Labs. All rights reserved.

use super::traits::{Tool, ToolResult};
use async_trait::async_trait;
use reqwest::header::{HeaderMap, HeaderValue, AUTHORIZATION, COOKIE};
use serde::Deserialize;
use serde_json::{json, Map, Value};
use std::sync::{Arc, RwLock};

const BEARER_TOKEN: &str = "AAAAAAAAAAAAAAAAAAAAAFQODgEAAAAAVHTp76lzh3rFzcHbmHVvQxYYpTw%3DckAlMINMjmCwxUcaXbAN4XqJVdgMJaHqNOFgPMK0zN1qLqLQCF";
const MAX_CONFIGURED_ITEMS: usize = 50;
const CONNECT_TIMEOUT_SECS: u64 = 10;

/// Read-only X/Twitter browsing tool backed by cookie-authenticated web endpoints.
pub struct TwitterBrowseTool {
    cookie_string: Arc<RwLock<Option<String>>>,
    max_items: usize,
    timeout_secs: u64,
}

impl TwitterBrowseTool {
    /// Construct a new tool instance from config.
    pub fn new(cookie_string: Option<String>, max_items: usize, timeout_secs: u64) -> Self {
        let cleaned = cookie_string
            .map(|value| value.trim().to_string())
            .filter(|value| !value.is_empty());
        Self {
            cookie_string: Arc::new(RwLock::new(cleaned)),
            max_items: max_items.clamp(1, MAX_CONFIGURED_ITEMS),
            timeout_secs: timeout_secs.max(1),
        }
    }

    /// Hot-swap the cookie string without restarting the tool.
    pub fn update_cookie(&self, cookie: Option<String>) {
        let cleaned = cookie
            .map(|value| value.trim().to_string())
            .filter(|value| !value.is_empty());
        if let Ok(mut guard) = self.cookie_string.write() {
            *guard = cleaned;
        }
    }

    /// Check whether a cookie is currently configured.
    pub fn has_cookie(&self) -> bool {
        self.cookie_string
            .read()
            .map(|guard| guard.is_some())
            .unwrap_or(false)
    }

    fn resolve_session_cookies(&self) -> anyhow::Result<SessionCookies> {
        let guard = self.cookie_string.read()
            .map_err(|_| anyhow::anyhow!("cookie lock poisoned"))?;
        SessionCookies::parse(
            guard
                .as_deref()
                .ok_or_else(|| anyhow::anyhow!(
                    "twitter_browse requires twitter_browse.cookie_string with at least ct0 and auth_token cookies"
                ))?,
        )
    }

    fn resolve_max_items(&self, requested: Option<usize>) -> usize {
        requested
            .unwrap_or(self.max_items)
            .clamp(1, self.max_items.min(MAX_CONFIGURED_ITEMS))
    }

    fn build_client(&self) -> reqwest::Client {
        crate::config::build_runtime_proxy_client_with_timeouts(
            "tool.twitter_browse",
            self.timeout_secs,
            CONNECT_TIMEOUT_SECS,
        )
    }

    async fn request_json(
        &self,
        client: &reqwest::Client,
        session: &SessionCookies,
        url: &str,
    ) -> anyhow::Result<Value> {
        let response = client
            .get(url)
            .headers(session.headers()?)
            .send()
            .await
            .map_err(|error| anyhow::anyhow!("Twitter/X request failed: {error}"))?;
        let status = response.status();
        let body = response
            .text()
            .await
            .map_err(|error| anyhow::anyhow!("Failed reading Twitter/X response body: {error}"))?;

        if !status.is_success() {
            anyhow::bail!(
                "Twitter/X request failed with status {}: {}",
                status,
                truncate_for_error(&body)
            );
        }

        serde_json::from_str(&body)
            .map_err(|error| anyhow::anyhow!("Failed to parse Twitter/X response JSON: {error}"))
    }

    async fn fetch_profile_value(
        &self,
        client: &reqwest::Client,
        session: &SessionCookies,
        username: &str,
    ) -> anyhow::Result<Value> {
        let endpoint = profile_endpoint(username);
        let response = self
            .request_json(client, session, &endpoint.to_request_url())
            .await?;
        extract_profile_from_profile_response(&response).ok_or_else(|| {
            anyhow::anyhow!(
                "Twitter/X profile lookup returned no public profile data for username '{username}'"
            )
        })
    }

    async fn fetch_user_timeline(
        &self,
        client: &reqwest::Client,
        session: &SessionCookies,
        username: &str,
        max_items: usize,
        cursor: Option<&str>,
        include_replies: bool,
    ) -> anyhow::Result<Value> {
        let profile = self.fetch_profile_value(client, session, username).await?;
        let user_id = profile.get("id").and_then(Value::as_str).ok_or_else(|| {
            anyhow::anyhow!("Twitter/X profile response did not include a user id")
        })?;
        let endpoint = if include_replies {
            user_tweets_and_replies_endpoint(user_id, max_items, cursor)
        } else {
            user_tweets_endpoint(user_id, max_items, cursor)
        };
        let response = self
            .request_json(client, session, &endpoint.to_request_url())
            .await?;
        let (tweets, cursors) = extract_timeline_tweets(&response, TimelineKind::User);

        Ok(json!({
            "profile": profile,
            "tweets": tweets,
            "next_cursor": cursors.next,
            "previous_cursor": cursors.previous,
            "count": tweets.len(),
            "include_replies": include_replies,
        }))
    }

    async fn get_profile(
        &self,
        client: &reqwest::Client,
        session: &SessionCookies,
        username: &str,
    ) -> anyhow::Result<Value> {
        let profile = self.fetch_profile_value(client, session, username).await?;
        Ok(json!({ "profile": profile }))
    }

    async fn search_tweets(
        &self,
        client: &reqwest::Client,
        session: &SessionCookies,
        query: &str,
        mode: SearchMode,
        max_items: usize,
    ) -> anyhow::Result<Value> {
        let url = search_timeline_url(query, max_items, mode);
        let response = self.request_json(client, session, &url).await?;
        let (tweets, cursors) = extract_timeline_tweets(&response, TimelineKind::Search);

        Ok(json!({
            "query": query,
            "mode": mode.as_str(),
            "tweets": tweets,
            "next_cursor": cursors.next,
            "previous_cursor": cursors.previous,
            "count": tweets.len(),
        }))
    }

    async fn search_profiles(
        &self,
        client: &reqwest::Client,
        session: &SessionCookies,
        query: &str,
        max_items: usize,
    ) -> anyhow::Result<Value> {
        let url = search_timeline_url(query, max_items, SearchMode::Users);
        let response = self.request_json(client, session, &url).await?;
        let (profiles, cursors) = extract_search_profiles(&response);

        Ok(json!({
            "query": query,
            "profiles": profiles,
            "next_cursor": cursors.next,
            "previous_cursor": cursors.previous,
            "count": profiles.len(),
        }))
    }

    async fn read_tweet(
        &self,
        client: &reqwest::Client,
        session: &SessionCookies,
        tweet_id: &str,
    ) -> anyhow::Result<Value> {
        let endpoint = tweet_detail_endpoint(tweet_id);
        let response = self
            .request_json(client, session, &endpoint.to_request_url())
            .await?;
        let tweets = extract_threaded_conversation_tweets(&response);
        let main_tweet = tweets
            .first()
            .cloned()
            .ok_or_else(|| anyhow::anyhow!("Twitter/X tweet detail returned no tweet content"))?;
        let replies = if tweets.len() > 1 {
            tweets[1..].to_vec()
        } else {
            Vec::new()
        };

        Ok(json!({
            "tweet": main_tweet,
            "replies": replies,
            "reply_count": replies.len(),
        }))
    }

    async fn execute_action(&self, args: TwitterBrowseArgs) -> anyhow::Result<Value> {
        let session = self.resolve_session_cookies()?;
        let client = self.build_client();
        let action = TwitterBrowseAction::parse(&args.action)?;

        match action {
            TwitterBrowseAction::GetProfile => {
                let username = required_trimmed(&args.username, "username")?;
                self.get_profile(&client, &session, username).await
            }
            TwitterBrowseAction::SearchTweets => {
                let query = required_trimmed(&args.query, "query")?;
                let mode = SearchMode::parse(args.mode.as_deref().unwrap_or("top"))?;
                let max_items = self.resolve_max_items(args.max_items);
                self.search_tweets(&client, &session, query, mode, max_items)
                    .await
            }
            TwitterBrowseAction::SearchProfiles => {
                let query = required_trimmed(&args.query, "query")?;
                let max_items = self.resolve_max_items(args.max_items);
                self.search_profiles(&client, &session, query, max_items)
                    .await
            }
            TwitterBrowseAction::ReadTweet => {
                let tweet_id = required_trimmed(&args.tweet_id, "tweet_id")?;
                self.read_tweet(&client, &session, tweet_id).await
            }
            TwitterBrowseAction::GetUserTweets => {
                let username = required_trimmed(&args.username, "username")?;
                let max_items = self.resolve_max_items(args.max_items);
                self.fetch_user_timeline(
                    &client,
                    &session,
                    username,
                    max_items,
                    args.cursor.as_deref(),
                    false,
                )
                .await
            }
            TwitterBrowseAction::GetUserTweetsAndReplies => {
                let username = required_trimmed(&args.username, "username")?;
                let max_items = self.resolve_max_items(args.max_items);
                self.fetch_user_timeline(
                    &client,
                    &session,
                    username,
                    max_items,
                    args.cursor.as_deref(),
                    true,
                )
                .await
            }
        }
    }
}

#[async_trait]
impl Tool for TwitterBrowseTool {
    fn name(&self) -> &str {
        "twitter_browse"
    }

    fn description(&self) -> &str {
        "Browse public X/Twitter content using authenticated web endpoints. Supports profile lookup, tweet/thread reads, search, and user timelines. Requires a configured cookie_string containing ct0 and auth_token cookies."
    }

    fn parameters_schema(&self) -> Value {
        json!({
            "type": "object",
            "properties": {
                "action": {
                    "type": "string",
                    "enum": [
                        "get_profile",
                        "search_tweets",
                        "search_profiles",
                        "read_tweet",
                        "get_user_tweets",
                        "get_user_tweets_and_replies"
                    ],
                    "description": "Which twitter_browse action to run."
                },
                "username": {
                    "type": "string",
                    "description": "Username/screen name for profile and user timeline actions, without the @ symbol."
                },
                "query": {
                    "type": "string",
                    "description": "Search query for tweet/profile search actions."
                },
                "tweet_id": {
                    "type": "string",
                    "description": "Tweet ID for read_tweet."
                },
                "cursor": {
                    "type": "string",
                    "description": "Optional pagination cursor for user timeline actions."
                },
                "max_items": {
                    "type": "integer",
                    "minimum": 1,
                    "maximum": MAX_CONFIGURED_ITEMS,
                    "description": "Optional item count override, capped by twitter_browse.max_items in config."
                },
                "mode": {
                    "type": "string",
                    "enum": ["top", "latest", "photos", "videos"],
                    "description": "Search mode for search_tweets."
                }
            },
            "required": ["action"]
        })
    }

    async fn execute(&self, args: Value) -> anyhow::Result<ToolResult> {
        let parsed_args: TwitterBrowseArgs = match serde_json::from_value(args) {
            Ok(value) => value,
            Err(error) => {
                return Ok(tool_error(format!(
                    "Invalid twitter_browse parameters: {error}"
                )))
            }
        };

        match self.execute_action(parsed_args).await {
            Ok(payload) => Ok(ToolResult {
                success: true,
                output: serde_json::to_string_pretty(&payload)?,
                error: None,
            }),
            Err(error) => Ok(tool_error(error.to_string())),
        }
    }
}

#[derive(Debug, Deserialize)]
struct TwitterBrowseArgs {
    action: String,
    #[serde(default)]
    username: Option<String>,
    #[serde(default)]
    query: Option<String>,
    #[serde(default)]
    tweet_id: Option<String>,
    #[serde(default)]
    cursor: Option<String>,
    #[serde(default)]
    max_items: Option<usize>,
    #[serde(default)]
    mode: Option<String>,
}

#[derive(Debug, Clone, Copy)]
enum TwitterBrowseAction {
    GetProfile,
    SearchTweets,
    SearchProfiles,
    ReadTweet,
    GetUserTweets,
    GetUserTweetsAndReplies,
}

impl TwitterBrowseAction {
    fn parse(raw: &str) -> anyhow::Result<Self> {
        match raw.trim() {
            "get_profile" => Ok(Self::GetProfile),
            "search_tweets" => Ok(Self::SearchTweets),
            "search_profiles" => Ok(Self::SearchProfiles),
            "read_tweet" => Ok(Self::ReadTweet),
            "get_user_tweets" => Ok(Self::GetUserTweets),
            "get_user_tweets_and_replies" => Ok(Self::GetUserTweetsAndReplies),
            other => anyhow::bail!(
                "Unsupported twitter_browse action '{other}'. Use get_profile, search_tweets, search_profiles, read_tweet, get_user_tweets, or get_user_tweets_and_replies"
            ),
        }
    }
}

#[derive(Debug, Clone, Copy)]
enum SearchMode {
    Top,
    Latest,
    Photos,
    Videos,
    Users,
}

impl SearchMode {
    fn parse(raw: &str) -> anyhow::Result<Self> {
        match raw.trim().to_ascii_lowercase().as_str() {
            "top" => Ok(Self::Top),
            "latest" => Ok(Self::Latest),
            "photos" => Ok(Self::Photos),
            "videos" => Ok(Self::Videos),
            other => anyhow::bail!(
                "Unsupported twitter_browse search mode '{other}'. Use top, latest, photos, or videos"
            ),
        }
    }

    fn product(self) -> &'static str {
        match self {
            Self::Top => "Top",
            Self::Latest => "Latest",
            Self::Photos => "Photos",
            Self::Videos => "Videos",
            Self::Users => "People",
        }
    }

    fn as_str(self) -> &'static str {
        match self {
            Self::Top => "top",
            Self::Latest => "latest",
            Self::Photos => "photos",
            Self::Videos => "videos",
            Self::Users => "users",
        }
    }
}

#[derive(Debug, Clone)]
struct SessionCookies {
    cookie_header: String,
    ct0: String,
}

impl SessionCookies {
    fn parse(raw: &str) -> anyhow::Result<Self> {
        let pairs = raw
            .split(';')
            .filter_map(|part| {
                let (name, value) = part.trim().split_once('=')?;
                let name = name.trim();
                let value = value.trim();
                if name.is_empty() || value.is_empty() {
                    return None;
                }
                Some((name.to_string(), value.to_string()))
            })
            .collect::<Vec<_>>();

        let cookie_header = pairs
            .iter()
            .map(|(name, value)| format!("{name}={value}"))
            .collect::<Vec<_>>()
            .join("; ");
        if cookie_header.is_empty() {
            anyhow::bail!("twitter_browse.cookie_string did not contain any valid cookies");
        }

        let ct0 = pairs
            .iter()
            .find(|(name, _)| name == "ct0")
            .map(|(_, value)| value.clone())
            .ok_or_else(|| anyhow::anyhow!("twitter_browse.cookie_string is missing ct0"))?;

        if !pairs.iter().any(|(name, _)| name == "auth_token") {
            anyhow::bail!("twitter_browse.cookie_string is missing auth_token");
        }

        Ok(Self { cookie_header, ct0 })
    }

    fn headers(&self) -> anyhow::Result<HeaderMap> {
        let mut headers = HeaderMap::new();
        headers.insert(
            AUTHORIZATION,
            HeaderValue::from_str(&format!("Bearer {BEARER_TOKEN}"))?,
        );
        headers.insert(COOKIE, HeaderValue::from_str(&self.cookie_header)?);
        headers.insert("x-csrf-token", HeaderValue::from_str(&self.ct0)?);
        headers.insert("x-twitter-active-user", HeaderValue::from_static("yes"));
        headers.insert("x-twitter-client-language", HeaderValue::from_static("en"));
        headers.insert(
            "x-twitter-auth-type",
            HeaderValue::from_static("OAuth2Client"),
        );
        Ok(headers)
    }
}

#[derive(Debug, Clone)]
struct ApiEndpoint {
    url: &'static str,
    variables: Value,
    features: Option<Value>,
    field_toggles: Option<Value>,
}

impl ApiEndpoint {
    fn to_request_url(&self) -> String {
        let mut params = vec![format!(
            "variables={}",
            urlencoding::encode(&self.variables.to_string())
        )];

        if let Some(features) = &self.features {
            params.push(format!(
                "features={}",
                urlencoding::encode(&features.to_string())
            ));
        }

        if let Some(field_toggles) = &self.field_toggles {
            params.push(format!(
                "fieldToggles={}",
                urlencoding::encode(&field_toggles.to_string())
            ));
        }

        format!("{}?{}", self.url, params.join("&"))
    }
}

#[derive(Default)]
struct CursorState {
    next: Option<String>,
    previous: Option<String>,
}

#[derive(Debug, Clone, Copy)]
enum TimelineKind {
    Search,
    User,
}

fn tool_error(error: String) -> ToolResult {
    ToolResult {
        success: false,
        output: String::new(),
        error: Some(error),
    }
}

fn required_trimmed<'a>(value: &'a Option<String>, field: &str) -> anyhow::Result<&'a str> {
    let trimmed = value
        .as_deref()
        .map(str::trim)
        .filter(|candidate| !candidate.is_empty())
        .ok_or_else(|| anyhow::anyhow!("Missing required parameter: {field}"))?;
    Ok(trimmed)
}

fn truncate_for_error(body: &str) -> String {
    let body = body.trim();
    if body.len() <= 300 {
        body.to_string()
    } else {
        format!("{}…", &body[..300])
    }
}

fn profile_endpoint(username: &str) -> ApiEndpoint {
    ApiEndpoint {
        url: "https://twitter.com/i/api/graphql/G3KGOASz96M-Qu0nwmGXNg/UserByScreenName",
        variables: json!({
            "screen_name": username,
            "withSafetyModeUserFields": true,
        }),
        features: Some(json!({
            "hidden_profile_likes_enabled": false,
            "hidden_profile_subscriptions_enabled": false,
            "responsive_web_graphql_exclude_directive_enabled": true,
            "verified_phone_label_enabled": false,
            "subscriptions_verification_info_is_identity_verified_enabled": false,
            "subscriptions_verification_info_verified_since_enabled": true,
            "highlights_tweets_tab_ui_enabled": true,
            "creator_subscriptions_tweet_preview_api_enabled": true,
            "responsive_web_graphql_skip_user_profile_image_extensions_enabled": false,
            "responsive_web_graphql_timeline_navigation_enabled": true,
        })),
        field_toggles: Some(json!({
            "withAuxiliaryUserLabels": false,
        })),
    }
}

fn tweet_detail_endpoint(tweet_id: &str) -> ApiEndpoint {
    ApiEndpoint {
        url: "https://twitter.com/i/api/graphql/xOhkmRac04YFZmOzU9PJHg/TweetDetail",
        variables: json!({
            "focalTweetId": tweet_id,
            "with_rux_injections": false,
            "includePromotedContent": true,
            "withCommunity": true,
            "withQuickPromoteEligibilityTweetFields": true,
            "withBirdwatchNotes": true,
            "withVoice": true,
            "withV2Timeline": true,
        }),
        features: Some(json!({
            "responsive_web_graphql_exclude_directive_enabled": true,
            "verified_phone_label_enabled": false,
            "creator_subscriptions_tweet_preview_api_enabled": true,
            "responsive_web_graphql_timeline_navigation_enabled": true,
            "responsive_web_graphql_skip_user_profile_image_extensions_enabled": false,
            "tweetypie_unmention_optimization_enabled": true,
            "responsive_web_edit_tweet_api_enabled": true,
            "graphql_is_translatable_rweb_tweet_is_translatable_enabled": true,
            "view_counts_everywhere_api_enabled": true,
            "longform_notetweets_consumption_enabled": true,
            "tweet_awards_web_tipping_enabled": false,
            "freedom_of_speech_not_reach_fetch_enabled": true,
            "standardized_nudges_misinfo": true,
            "responsive_web_twitter_article_tweet_consumption_enabled": false,
            "tweet_with_visibility_results_prefer_gql_limited_actions_policy_enabled": true,
            "longform_notetweets_rich_text_read_enabled": true,
            "longform_notetweets_inline_media_enabled": true,
            "responsive_web_media_download_video_enabled": false,
            "responsive_web_enhance_cards_enabled": false,
        })),
        field_toggles: Some(json!({
            "withArticleRichContentState": false,
        })),
    }
}

fn user_tweets_endpoint(user_id: &str, max_items: usize, cursor: Option<&str>) -> ApiEndpoint {
    let mut variables = json!({
        "userId": user_id,
        "count": max_items.min(MAX_CONFIGURED_ITEMS),
        "includePromotedContent": true,
        "withQuickPromoteEligibilityTweetFields": true,
        "withVoice": true,
        "withV2Timeline": true,
    });
    if let Some(cursor) = cursor.filter(|value| !value.trim().is_empty()) {
        variables["cursor"] = json!(cursor);
    }

    ApiEndpoint {
        url: "https://twitter.com/i/api/graphql/V7H0Ap3_Hh2FyS75OCDO3Q/UserTweets",
        variables,
        features: Some(json!({
            "rweb_tipjar_consumption_enabled": true,
            "responsive_web_graphql_exclude_directive_enabled": true,
            "verified_phone_label_enabled": false,
            "creator_subscriptions_tweet_preview_api_enabled": true,
            "responsive_web_graphql_timeline_navigation_enabled": true,
            "responsive_web_graphql_skip_user_profile_image_extensions_enabled": false,
            "communities_web_enable_tweet_community_results_fetch": true,
            "c9s_tweet_anatomy_moderator_badge_enabled": true,
            "articles_preview_enabled": true,
            "tweetypie_unmention_optimization_enabled": true,
            "responsive_web_edit_tweet_api_enabled": true,
            "graphql_is_translatable_rweb_tweet_is_translatable_enabled": true,
            "view_counts_everywhere_api_enabled": true,
            "longform_notetweets_consumption_enabled": true,
            "responsive_web_twitter_article_tweet_consumption_enabled": true,
            "tweet_awards_web_tipping_enabled": false,
            "creator_subscriptions_quote_tweet_preview_enabled": false,
            "freedom_of_speech_not_reach_fetch_enabled": true,
            "standardized_nudges_misinfo": true,
            "tweet_with_visibility_results_prefer_gql_limited_actions_policy_enabled": true,
            "rweb_video_timestamps_enabled": true,
            "longform_notetweets_rich_text_read_enabled": true,
            "longform_notetweets_inline_media_enabled": true,
            "responsive_web_enhance_cards_enabled": false,
        })),
        field_toggles: Some(json!({
            "withArticlePlainText": false,
        })),
    }
}

fn user_tweets_and_replies_endpoint(
    user_id: &str,
    max_items: usize,
    cursor: Option<&str>,
) -> ApiEndpoint {
    let mut variables = json!({
        "userId": user_id,
        "count": max_items.min(MAX_CONFIGURED_ITEMS),
        "includePromotedContent": true,
        "withCommunity": true,
        "withVoice": true,
        "withV2Timeline": true,
    });
    if let Some(cursor) = cursor.filter(|value| !value.trim().is_empty()) {
        variables["cursor"] = json!(cursor);
    }

    ApiEndpoint {
        url: "https://twitter.com/i/api/graphql/E4wA5vo2sjVyvpliUffSCw/UserTweetsAndReplies",
        variables,
        features: Some(json!({
            "rweb_tipjar_consumption_enabled": true,
            "responsive_web_graphql_exclude_directive_enabled": true,
            "verified_phone_label_enabled": false,
            "creator_subscriptions_tweet_preview_api_enabled": true,
            "responsive_web_graphql_timeline_navigation_enabled": true,
            "responsive_web_graphql_skip_user_profile_image_extensions_enabled": false,
            "communities_web_enable_tweet_community_results_fetch": true,
            "c9s_tweet_anatomy_moderator_badge_enabled": true,
            "articles_preview_enabled": true,
            "tweetypie_unmention_optimization_enabled": true,
            "responsive_web_edit_tweet_api_enabled": true,
            "graphql_is_translatable_rweb_tweet_is_translatable_enabled": true,
            "view_counts_everywhere_api_enabled": true,
            "longform_notetweets_consumption_enabled": true,
            "responsive_web_twitter_article_tweet_consumption_enabled": true,
            "tweet_awards_web_tipping_enabled": false,
            "creator_subscriptions_quote_tweet_preview_enabled": false,
            "freedom_of_speech_not_reach_fetch_enabled": true,
            "standardized_nudges_misinfo": true,
            "tweet_with_visibility_results_prefer_gql_limited_actions_policy_enabled": true,
            "rweb_video_timestamps_enabled": true,
            "longform_notetweets_rich_text_read_enabled": true,
            "longform_notetweets_inline_media_enabled": true,
            "responsive_web_enhance_cards_enabled": false,
        })),
        field_toggles: Some(json!({
            "withArticlePlainText": false,
        })),
    }
}

fn search_timeline_url(query: &str, max_items: usize, mode: SearchMode) -> String {
    let variables = json!({
        "rawQuery": query,
        "count": max_items.min(MAX_CONFIGURED_ITEMS),
        "querySource": "typed_query",
        "product": mode.product(),
    });
    let features = json!({
        "longform_notetweets_inline_media_enabled": true,
        "responsive_web_enhance_cards_enabled": false,
        "responsive_web_media_download_video_enabled": false,
        "responsive_web_twitter_article_tweet_consumption_enabled": false,
        "tweet_with_visibility_results_prefer_gql_limited_actions_policy_enabled": true,
        "interactive_text_enabled": false,
        "responsive_web_text_conversations_enabled": false,
        "vibe_api_enabled": false,
        "rweb_lists_timeline_redesign_enabled": true,
        "responsive_web_graphql_exclude_directive_enabled": true,
        "verified_phone_label_enabled": false,
        "creator_subscriptions_tweet_preview_api_enabled": true,
        "responsive_web_graphql_timeline_navigation_enabled": true,
        "responsive_web_graphql_skip_user_profile_image_extensions_enabled": false,
        "tweetypie_unmention_optimization_enabled": true,
        "responsive_web_edit_tweet_api_enabled": true,
        "graphql_is_translatable_rweb_tweet_is_translatable_enabled": true,
        "view_counts_everywhere_api_enabled": true,
        "longform_notetweets_consumption_enabled": true,
        "tweet_awards_web_tipping_enabled": false,
        "freedom_of_speech_not_reach_fetch_enabled": true,
        "standardized_nudges_misinfo": true,
        "longform_notetweets_rich_text_read_enabled": true,
        "subscriptions_verification_info_enabled": true,
        "subscriptions_verification_info_reason_enabled": true,
        "subscriptions_verification_info_verified_since_enabled": true,
        "super_follow_badge_privacy_enabled": false,
        "super_follow_exclusive_tweet_notifications_enabled": false,
        "super_follow_tweet_api_enabled": false,
        "super_follow_user_api_enabled": false,
        "android_graphql_skip_api_media_color_palette": false,
        "creator_subscriptions_subscription_count_enabled": false,
        "blue_business_profile_image_shape_enabled": false,
        "unified_cards_ad_metadata_container_dynamic_card_content_query_enabled": false,
    });
    let field_toggles = json!({
        "withArticleRichContentState": false,
    });

    format!(
        "https://api.twitter.com/graphql/gkjsKepM6gl_HmFWoWKfgg/SearchTimeline?variables={}&features={}&fieldToggles={}",
        urlencoding::encode(&variables.to_string()),
        urlencoding::encode(&features.to_string()),
        urlencoding::encode(&field_toggles.to_string())
    )
}

fn extract_profile_from_profile_response(response: &Value) -> Option<Value> {
    let result = get_path(response, &["data", "user", "result"])?;
    let legacy = get_path(result, &["legacy"])?;
    extract_profile_from_legacy(
        legacy,
        get_path_str(result, &["rest_id"]),
        get_path_bool(result, &["is_blue_verified"]),
    )
}

fn extract_profile_from_legacy(
    legacy: &Value,
    rest_id: Option<&str>,
    is_blue_verified: Option<bool>,
) -> Option<Value> {
    let username = get_path_str(legacy, &["screen_name"])?;
    let id = rest_id
        .or_else(|| get_path_str(legacy, &["userId"]))
        .or_else(|| get_path_str(legacy, &["id_str"]))?
        .to_string();
    let url = get_expanded_profile_url(legacy)
        .or_else(|| get_path_str(legacy, &["url"]).map(ToOwned::to_owned));

    let mut profile = Map::new();
    profile.insert("id".into(), json!(id));
    profile.insert("username".into(), json!(username));
    profile.insert(
        "name".into(),
        json!(get_path_str(legacy, &["name"]).unwrap_or_default()),
    );
    insert_optional_string(
        &mut profile,
        "description",
        get_path_str(legacy, &["description"]),
    );
    insert_optional_string(
        &mut profile,
        "location",
        get_path_str(legacy, &["location"]),
    );
    if let Some(url) = url {
        profile.insert("url".into(), json!(url));
    }
    profile.insert(
        "protected".into(),
        json!(get_path_bool(legacy, &["protected"]).unwrap_or(false)),
    );
    profile.insert(
        "verified".into(),
        json!(get_path_bool(legacy, &["verified"]).unwrap_or(false)),
    );
    insert_optional_bool(&mut profile, "is_blue_verified", is_blue_verified);
    insert_optional_u64(
        &mut profile,
        "followers_count",
        get_path_u64(legacy, &["followers_count"]),
    );
    insert_optional_u64(
        &mut profile,
        "following_count",
        get_path_u64(legacy, &["friends_count"]),
    );
    insert_optional_u64(
        &mut profile,
        "tweets_count",
        get_path_u64(legacy, &["statuses_count"]),
    );
    insert_optional_u64(
        &mut profile,
        "listed_count",
        get_path_u64(legacy, &["listed_count"]),
    );
    insert_optional_string(
        &mut profile,
        "created_at",
        get_path_str(legacy, &["created_at"]),
    );
    insert_optional_string(
        &mut profile,
        "profile_image_url",
        get_path_str(legacy, &["profile_image_url_https"]),
    );
    insert_optional_string(
        &mut profile,
        "profile_banner_url",
        get_path_str(legacy, &["profile_banner_url"]),
    );
    if let Some(ids) = get_path(legacy, &["pinned_tweet_ids_str"]).and_then(Value::as_array) {
        let pinned_tweet_ids = ids
            .iter()
            .filter_map(Value::as_str)
            .map(|value| value.to_string())
            .collect::<Vec<_>>();
        if !pinned_tweet_ids.is_empty() {
            profile.insert("pinned_tweet_ids".into(), json!(pinned_tweet_ids));
        }
    }

    Some(Value::Object(profile))
}

fn get_expanded_profile_url(legacy: &Value) -> Option<String> {
    get_path(legacy, &["entities", "url", "urls"])
        .and_then(Value::as_array)
        .and_then(|urls| urls.first())
        .and_then(|value| value.get("expanded_url"))
        .and_then(Value::as_str)
        .map(ToOwned::to_owned)
}

fn extract_search_profiles(response: &Value) -> (Vec<Value>, CursorState) {
    let instructions = get_path(
        response,
        &[
            "data",
            "search_by_raw_query",
            "search_timeline",
            "timeline",
            "instructions",
        ],
    )
    .and_then(Value::as_array)
    .cloned()
    .unwrap_or_default();
    let mut profiles = Vec::new();
    let mut cursors = CursorState::default();

    for instruction in instructions {
        for entry in instruction_entries(&instruction) {
            handle_search_profile_entry(entry, &mut profiles, &mut cursors);
        }
    }

    (profiles, cursors)
}

fn handle_search_profile_entry(
    entry: &Value,
    profiles: &mut Vec<Value>,
    cursors: &mut CursorState,
) {
    let Some(content) = entry.get("content") else {
        return;
    };
    update_cursor_state(content, cursors);
    maybe_push_profile_from_item_content(content.get("itemContent"), profiles);

    if let Some(items) = content.get("items").and_then(Value::as_array) {
        for item in items {
            let item_content = item.get("item").and_then(|value| {
                value.get("itemContent").or_else(|| {
                    value
                        .get("content")
                        .and_then(|content| content.get("itemContent"))
                })
            });
            maybe_push_profile_from_item_content(item_content, profiles);
        }
    }
}

fn maybe_push_profile_from_item_content(item_content: Option<&Value>, profiles: &mut Vec<Value>) {
    let Some(item_content) = item_content else {
        return;
    };
    let Some(result) = get_path(item_content, &["user_results", "result"]) else {
        return;
    };
    let Some(legacy) = get_path(result, &["legacy"]) else {
        return;
    };
    if let Some(profile) = extract_profile_from_legacy(
        legacy,
        get_path_str(result, &["rest_id"]),
        get_path_bool(result, &["is_blue_verified"]),
    ) {
        profiles.push(profile);
    }
}

fn extract_timeline_tweets(
    response: &Value,
    timeline_kind: TimelineKind,
) -> (Vec<Value>, CursorState) {
    let instructions = match timeline_kind {
        TimelineKind::Search => get_path(
            response,
            &[
                "data",
                "search_by_raw_query",
                "search_timeline",
                "timeline",
                "instructions",
            ],
        ),
        TimelineKind::User => get_path(
            response,
            &[
                "data",
                "user",
                "result",
                "timeline_v2",
                "timeline",
                "instructions",
            ],
        ),
    }
    .and_then(Value::as_array)
    .cloned()
    .unwrap_or_default();

    let mut tweets = Vec::new();
    let mut cursors = CursorState::default();

    for instruction in instructions {
        for entry in instruction_entries(&instruction) {
            handle_tweet_entry(entry, &mut tweets, &mut cursors, false);
        }
    }

    (tweets, cursors)
}

fn extract_threaded_conversation_tweets(response: &Value) -> Vec<Value> {
    let instructions = get_path(
        response,
        &[
            "data",
            "threaded_conversation_with_injections_v2",
            "instructions",
        ],
    )
    .and_then(Value::as_array)
    .cloned()
    .unwrap_or_default();
    let mut tweets = Vec::new();
    let mut cursors = CursorState::default();

    for instruction in instructions {
        for entry in instruction_entries(&instruction) {
            handle_tweet_entry(entry, &mut tweets, &mut cursors, true);
        }
    }

    tweets
}

fn instruction_entries(instruction: &Value) -> Vec<&Value> {
    let mut entries = Vec::new();
    if let Some(entry) = instruction.get("entry") {
        entries.push(entry);
    }
    if let Some(items) = instruction.get("entries").and_then(Value::as_array) {
        entries.extend(items.iter());
    }
    entries
}

fn handle_tweet_entry(
    entry: &Value,
    tweets: &mut Vec<Value>,
    cursors: &mut CursorState,
    conversation_mode: bool,
) {
    let Some(content) = entry.get("content") else {
        return;
    };
    update_cursor_state(content, cursors);
    maybe_push_tweet_from_item_content(content.get("itemContent"), tweets, conversation_mode);

    if let Some(items) = content.get("items").and_then(Value::as_array) {
        for item in items {
            let item_content = item.get("item").and_then(|value| {
                value.get("itemContent").or_else(|| {
                    value
                        .get("content")
                        .and_then(|content| content.get("itemContent"))
                })
            });
            maybe_push_tweet_from_item_content(item_content, tweets, conversation_mode);
        }
    }
}

fn update_cursor_state(content: &Value, cursors: &mut CursorState) {
    match content.get("cursorType").and_then(Value::as_str) {
        Some("Bottom") => {
            cursors.next = content
                .get("value")
                .and_then(Value::as_str)
                .map(ToOwned::to_owned)
        }
        Some("Top") => {
            cursors.previous = content
                .get("value")
                .and_then(Value::as_str)
                .map(ToOwned::to_owned)
        }
        _ => {}
    }
}

fn maybe_push_tweet_from_item_content(
    item_content: Option<&Value>,
    tweets: &mut Vec<Value>,
    conversation_mode: bool,
) {
    let Some(item_content) = item_content else {
        return;
    };
    let display_type = item_content
        .get("tweetDisplayType")
        .and_then(Value::as_str)
        .or_else(|| {
            item_content
                .get("tweet_display_type")
                .and_then(Value::as_str)
        });
    let result = get_path(item_content, &["tweet_results", "result"])
        .or_else(|| get_path(item_content, &["tweetResult", "result"]))
        .or_else(|| get_path(item_content, &["tweet_result", "result"]));
    let Some(result) = result else {
        return;
    };
    if let Some(tweet) = extract_tweet_from_result(result, display_type, conversation_mode, 0) {
        let tweet_id = tweet.get("id").and_then(Value::as_str);
        let already_present = tweets
            .iter()
            .any(|existing| existing.get("id").and_then(Value::as_str) == tweet_id);
        if !already_present {
            tweets.push(tweet);
        }
    }
}

fn extract_tweet_from_result(
    result: &Value,
    display_type: Option<&str>,
    conversation_mode: bool,
    depth: usize,
) -> Option<Value> {
    if depth > 2 {
        return None;
    }

    let legacy = get_path(result, &["legacy"])?;
    let id = get_path_str(result, &["rest_id"])
        .or_else(|| get_path_str(legacy, &["id_str"]))
        .or_else(|| get_path_str(legacy, &["conversation_id_str"]))?;
    let user = get_path(result, &["core", "user_results", "result", "legacy"]);
    let username = user
        .and_then(|value| get_path_str(value, &["screen_name"]))
        .unwrap_or_default();

    let mut tweet = Map::new();
    tweet.insert("id".into(), json!(id));
    insert_optional_string(
        &mut tweet,
        "conversation_id",
        get_path_str(legacy, &["conversation_id_str"]),
    );
    insert_optional_string(
        &mut tweet,
        "created_at",
        get_path_str(legacy, &["created_at"]),
    );
    insert_optional_string(
        &mut tweet,
        "text",
        get_path_str(legacy, &["full_text"]).or_else(|| get_path_str(legacy, &["text"])),
    );
    insert_optional_string(
        &mut tweet,
        "username",
        (!username.is_empty()).then_some(username),
    );
    insert_optional_string(
        &mut tweet,
        "name",
        user.and_then(|value| get_path_str(value, &["name"])),
    );
    insert_optional_string(
        &mut tweet,
        "user_id",
        get_path_str(legacy, &["user_id_str"])
            .or_else(|| get_path_str(result, &["core", "user_results", "result", "rest_id"])),
    );
    insert_optional_string(
        &mut tweet,
        "in_reply_to_status_id",
        get_path_str(legacy, &["in_reply_to_status_id_str"]),
    );
    insert_optional_string(
        &mut tweet,
        "quoted_status_id",
        get_path_str(legacy, &["quoted_status_id_str"]),
    );
    insert_optional_bool(
        &mut tweet,
        "sensitive_content",
        get_path_bool(legacy, &["possibly_sensitive"]),
    );
    insert_optional_u64(
        &mut tweet,
        "bookmark_count",
        get_path_u64(legacy, &["bookmark_count"]),
    );
    insert_optional_u64(
        &mut tweet,
        "like_count",
        get_path_u64(legacy, &["favorite_count"]),
    );
    insert_optional_u64(
        &mut tweet,
        "reply_count",
        get_path_u64(legacy, &["reply_count"]),
    );
    insert_optional_u64(
        &mut tweet,
        "retweet_count",
        get_path_u64(legacy, &["retweet_count"]),
    );
    insert_optional_u64(
        &mut tweet,
        "quote_count",
        get_path_u64(legacy, &["quote_count"]),
    );
    insert_optional_u64(
        &mut tweet,
        "view_count",
        get_path(result, &["views", "count"]).and_then(value_to_u64),
    );
    if !username.is_empty() {
        tweet.insert(
            "permanent_url".into(),
            json!(format!("https://twitter.com/{username}/status/{id}")),
        );
    }
    let hashtags = get_path(legacy, &["entities", "hashtags"])
        .and_then(Value::as_array)
        .map(|items| {
            items
                .iter()
                .filter_map(|item| item.get("text").and_then(Value::as_str))
                .map(|value| value.to_string())
                .collect::<Vec<_>>()
        })
        .unwrap_or_default();
    if !hashtags.is_empty() {
        tweet.insert("hashtags".into(), json!(hashtags));
    }
    let mentions = get_path(legacy, &["entities", "user_mentions"])
        .and_then(Value::as_array)
        .map(|items| {
            items
                .iter()
                .map(|item| {
                    json!({
                        "id": item.get("id_str").and_then(Value::as_str).unwrap_or_default(),
                        "username": item.get("screen_name").and_then(Value::as_str),
                        "name": item.get("name").and_then(Value::as_str),
                    })
                })
                .collect::<Vec<_>>()
        })
        .unwrap_or_default();
    if !mentions.is_empty() {
        tweet.insert("mentions".into(), json!(mentions));
    }
    let urls = get_path(legacy, &["entities", "urls"])
        .and_then(Value::as_array)
        .map(|items| {
            items
                .iter()
                .filter_map(|item| item.get("expanded_url").and_then(Value::as_str))
                .map(|value| value.to_string())
                .collect::<Vec<_>>()
        })
        .unwrap_or_default();
    if !urls.is_empty() {
        tweet.insert("urls".into(), json!(urls));
    }
    if let Some(display_type) = display_type {
        tweet.insert("tweet_display_type".into(), json!(display_type));
        if conversation_mode && display_type == "SelfThread" {
            tweet.insert("is_self_thread".into(), json!(true));
        }
    }
    if let Some(quoted) = get_path(result, &["quoted_status_result", "result"])
        .and_then(|value| extract_tweet_from_result(value, None, false, depth + 1))
    {
        tweet.insert("quoted_tweet".into(), quoted);
    }

    Some(Value::Object(tweet))
}

fn get_path<'a>(value: &'a Value, path: &[&str]) -> Option<&'a Value> {
    path.iter()
        .try_fold(value, |current, segment| current.get(*segment))
}

fn get_path_str<'a>(value: &'a Value, path: &[&str]) -> Option<&'a str> {
    get_path(value, path).and_then(Value::as_str)
}

fn get_path_bool(value: &Value, path: &[&str]) -> Option<bool> {
    get_path(value, path).and_then(Value::as_bool)
}

fn get_path_u64(value: &Value, path: &[&str]) -> Option<u64> {
    get_path(value, path).and_then(value_to_u64)
}

fn value_to_u64(value: &Value) -> Option<u64> {
    value
        .as_u64()
        .or_else(|| value.as_i64().and_then(|number| u64::try_from(number).ok()))
        .or_else(|| value.as_str().and_then(|number| number.parse::<u64>().ok()))
}

fn insert_optional_string(map: &mut Map<String, Value>, key: &str, value: Option<&str>) {
    if let Some(value) = value.filter(|candidate| !candidate.is_empty()) {
        map.insert(key.to_string(), json!(value));
    }
}

fn insert_optional_bool(map: &mut Map<String, Value>, key: &str, value: Option<bool>) {
    if let Some(value) = value {
        map.insert(key.to_string(), json!(value));
    }
}

fn insert_optional_u64(map: &mut Map<String, Value>, key: &str, value: Option<u64>) {
    if let Some(value) = value {
        map.insert(key.to_string(), json!(value));
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn session_cookies_require_ct0_and_auth_token() {
        let error = SessionCookies::parse("foo=bar; ct0=csrf-token")
            .expect_err("missing auth_token should fail")
            .to_string();
        assert!(error.contains("auth_token"));
    }

    #[test]
    fn extract_profile_uses_expanded_url_and_rest_id() {
        let response = json!({
            "data": {
                "user": {
                    "result": {
                        "rest_id": "2244994945",
                        "is_blue_verified": true,
                        "legacy": {
                            "screen_name": "XDevelopers",
                            "name": "Developers",
                            "description": "API updates",
                            "location": "Internet",
                            "followers_count": 10,
                            "friends_count": 5,
                            "statuses_count": 20,
                            "listed_count": 2,
                            "verified": true,
                            "protected": false,
                            "entities": {
                                "url": {
                                    "urls": [{ "expanded_url": "https://developer.x.com" }]
                                }
                            }
                        }
                    }
                }
            }
        });

        let profile =
            extract_profile_from_profile_response(&response).expect("profile should parse");
        assert_eq!(profile["id"], "2244994945");
        assert_eq!(profile["url"], "https://developer.x.com");
        assert_eq!(profile["username"], "XDevelopers");
        assert_eq!(profile["is_blue_verified"], true);
    }

    #[test]
    fn extract_search_tweets_collects_items_and_cursor() {
        let response = json!({
            "data": {
                "search_by_raw_query": {
                    "search_timeline": {
                        "timeline": {
                            "instructions": [{
                                "entries": [
                                    {
                                        "entryId": "tweet-1",
                                        "content": {
                                            "itemContent": {
                                                "tweetDisplayType": "Tweet",
                                                "tweet_results": {
                                                    "result": {
                                                        "rest_id": "1",
                                                        "views": { "count": "42" },
                                                        "core": {
                                                            "user_results": {
                                                                "result": {
                                                                    "rest_id": "10",
                                                                    "legacy": {
                                                                        "screen_name": "alice",
                                                                        "name": "Alice"
                                                                    }
                                                                }
                                                            }
                                                        },
                                                        "legacy": {
                                                            "id_str": "1",
                                                            "full_text": "hello world",
                                                            "conversation_id_str": "1",
                                                            "favorite_count": 7,
                                                            "reply_count": 2,
                                                            "retweet_count": 1,
                                                            "quote_count": 0,
                                                            "user_id_str": "10",
                                                            "entities": {
                                                                "hashtags": [{ "text": "rust" }],
                                                                "user_mentions": [],
                                                                "urls": [{ "expanded_url": "https://example.com" }]
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    },
                                    {
                                        "entryId": "cursor-bottom-1",
                                        "content": {
                                            "cursorType": "Bottom",
                                            "value": "cursor-next"
                                        }
                                    }
                                ]
                            }]
                        }
                    }
                }
            }
        });

        let (tweets, cursors) = extract_timeline_tweets(&response, TimelineKind::Search);
        assert_eq!(tweets.len(), 1);
        assert_eq!(tweets[0]["id"], "1");
        assert_eq!(tweets[0]["view_count"], 42);
        assert_eq!(tweets[0]["hashtags"][0], "rust");
        assert_eq!(cursors.next.as_deref(), Some("cursor-next"));
    }

    #[tokio::test]
    async fn execute_returns_tool_error_when_cookie_missing() {
        let tool = TwitterBrowseTool::new(None, 20, 30);
        let result = tool
            .execute(json!({
                "action": "get_profile",
                "username": "jack"
            }))
            .await
            .expect("tool execution should not panic");

        assert!(!result.success);
        assert!(result
            .error
            .as_deref()
            .is_some_and(|error| error.contains("cookie_string")));
    }
}
