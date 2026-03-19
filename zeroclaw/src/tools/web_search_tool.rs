// Copyright (c) 2026 @Natfii. All rights reserved.

use super::traits::{Tool, ToolResult};
use crate::config::schema::WebSearchConfig;
use async_trait::async_trait;
use serde_json::json;
use std::time::Duration;

/// Web search tool for searching the internet.
///
/// Supports two JSON-API providers: Brave Search and Google Custom Search Engine (CSE).
/// Set `provider = "auto"` to pick the first configured provider, `"brave"` or `"google"`
/// to force a specific one. When no provider is configured the tool returns a clear error.
pub struct WebSearchTool {
    provider: String,
    brave_api_key: Option<String>,
    google_api_key: Option<String>,
    google_cx: Option<String>,
    max_results: usize,
    timeout_secs: u64,
}

impl WebSearchTool {
    /// Constructs a [`WebSearchTool`] from a [`WebSearchConfig`].
    ///
    /// The effective provider is resolved by [`resolve_provider`] — when `provider`
    /// is `"auto"` the first key-bearing provider wins (brave > google). `max_results`
    /// is clamped to [1, 10] and `timeout_secs` is floored at 1.
    pub fn new(config: &WebSearchConfig) -> Self {
        let provider = resolve_provider(
            &config.provider,
            &config.brave_api_key,
            &config.google_api_key,
            &config.google_cx,
        );
        Self {
            provider,
            brave_api_key: config.brave_api_key.clone(),
            google_api_key: config.google_api_key.clone(),
            google_cx: config.google_cx.clone(),
            max_results: config.max_results.clamp(1, 10),
            timeout_secs: config.timeout_secs.max(1),
        }
    }

    async fn search_brave(&self, query: &str) -> anyhow::Result<String> {
        let api_key = self
            .brave_api_key
            .as_ref()
            .ok_or_else(|| anyhow::anyhow!("Brave API key not configured"))?;

        let encoded_query = urlencoding::encode(query);
        let search_url = format!(
            "https://api.search.brave.com/res/v1/web/search?q={}&count={}",
            encoded_query, self.max_results
        );

        let client = reqwest::Client::builder()
            .timeout(Duration::from_secs(self.timeout_secs))
            .build()?;

        let response = client
            .get(&search_url)
            .header("Accept", "application/json")
            .header("X-Subscription-Token", api_key)
            .send()
            .await?;

        let status = response.status();
        if !status.is_success() {
            match status.as_u16() {
                401 => anyhow::bail!("Brave search failed: invalid API key (401 Unauthorized)"),
                429 => anyhow::bail!("Brave search failed: rate limit exceeded (429 Too Many Requests)"),
                _ => anyhow::bail!("Brave search failed with status: {}", status),
            }
        }

        let json: serde_json::Value = response.json().await?;
        self.parse_brave_results(&json, query)
    }

    fn parse_brave_results(&self, json: &serde_json::Value, query: &str) -> anyhow::Result<String> {
        let results = json
            .get("web")
            .and_then(|w| w.get("results"))
            .and_then(|r| r.as_array())
            .ok_or_else(|| anyhow::anyhow!("Invalid Brave API response"))?;

        if results.is_empty() {
            return Ok(format!("No results found for: {}", query));
        }

        let mut lines = vec![format!("Search results for: {} (via Brave)", query)];

        for (i, result) in results.iter().take(self.max_results).enumerate() {
            let title = result
                .get("title")
                .and_then(|t| t.as_str())
                .unwrap_or("No title");
            let url = result.get("url").and_then(|u| u.as_str()).unwrap_or("");
            let description = result
                .get("description")
                .and_then(|d| d.as_str())
                .unwrap_or("");

            lines.push(format!("{}. {}", i + 1, title));
            lines.push(format!("   {}", url));
            if !description.is_empty() {
                lines.push(format!("   {}", description));
            }
        }

        Ok(lines.join("\n"))
    }

    async fn search_google(&self, query: &str) -> anyhow::Result<String> {
        let api_key = self
            .google_api_key
            .as_ref()
            .ok_or_else(|| anyhow::anyhow!("Google API key not configured"))?;

        let cx = self
            .google_cx
            .as_ref()
            .ok_or_else(|| anyhow::anyhow!("Google Custom Search Engine ID (cx) not configured"))?;

        let encoded_query = urlencoding::encode(query);
        let search_url = format!(
            "https://www.googleapis.com/customsearch/v1?key={}&cx={}&q={}&num={}",
            api_key, cx, encoded_query, self.max_results
        );

        let client = reqwest::Client::builder()
            .timeout(Duration::from_secs(self.timeout_secs))
            .build()?;

        let response = client
            .get(&search_url)
            .header("Accept", "application/json")
            .send()
            .await?;

        let status = response.status();
        if !status.is_success() {
            let body: serde_json::Value = response.json().await.unwrap_or(serde_json::Value::Null);
            match status.as_u16() {
                400 => anyhow::bail!(
                    "Google search failed: bad request — check your CX value (400 Bad Request)"
                ),
                403 => {
                    let reason = body
                        .pointer("/error/errors/0/reason")
                        .and_then(|r| r.as_str())
                        .unwrap_or("");
                    if reason == "dailyLimitExceeded" {
                        anyhow::bail!(
                            "Google search failed: daily quota exceeded (403 Forbidden)"
                        );
                    }
                    anyhow::bail!("Google search failed: forbidden — check your API key (403 Forbidden)");
                }
                429 => anyhow::bail!(
                    "Google search failed: rate limit exceeded (429 Too Many Requests)"
                ),
                _ => anyhow::bail!("Google search failed with status: {}", status),
            }
        }

        let json: serde_json::Value = response.json().await?;
        self.parse_google_results(&json, query)
    }

    fn parse_google_results(&self, json: &serde_json::Value, query: &str) -> anyhow::Result<String> {
        let items = match json.get("items").and_then(|i| i.as_array()) {
            Some(arr) if !arr.is_empty() => arr,
            _ => return Ok(format!("No results found for: {}", query)),
        };

        let mut lines = vec![format!("Search results for: {} (via Google)", query)];

        for (i, item) in items.iter().take(self.max_results).enumerate() {
            let title = item
                .get("title")
                .and_then(|t| t.as_str())
                .unwrap_or("No title");
            let link = item.get("link").and_then(|l| l.as_str()).unwrap_or("");
            let snippet = item
                .get("snippet")
                .and_then(|s| s.as_str())
                .unwrap_or("");

            lines.push(format!("{}. {}", i + 1, title));
            lines.push(format!("   {}", link));
            if !snippet.is_empty() {
                lines.push(format!("   {}", snippet));
            }
        }

        Ok(lines.join("\n"))
    }
}

/// Resolves the effective search provider from config.
///
/// When `provider` is `"auto"`, picks the first available provider:
/// brave (if `brave_key` is `Some`) → google (if both `google_key` and `google_cx`
/// are `Some`) → `"none"` (no providers configured). Any other value is returned
/// lowercased and trimmed as-is.
fn resolve_provider(
    provider: &str,
    brave_key: &Option<String>,
    google_key: &Option<String>,
    google_cx: &Option<String>,
) -> String {
    let p = provider.trim().to_lowercase();
    if p != "auto" {
        return p;
    }
    if brave_key.is_some() {
        return "brave".into();
    }
    if google_key.is_some() && google_cx.is_some() {
        return "google".into();
    }
    "none".into()
}

#[async_trait]
impl Tool for WebSearchTool {
    fn name(&self) -> &str {
        "web_search_tool"
    }

    fn description(&self) -> &str {
        "Search the web for information. Returns relevant search results with titles, URLs, and descriptions. Use this to find current information, news, or research topics."
    }

    fn parameters_schema(&self) -> serde_json::Value {
        json!({
            "type": "object",
            "properties": {
                "query": {
                    "type": "string",
                    "description": "The search query. Be specific for better results."
                }
            },
            "required": ["query"]
        })
    }

    async fn execute(&self, args: serde_json::Value) -> anyhow::Result<ToolResult> {
        let query = args
            .get("query")
            .and_then(|q| q.as_str())
            .ok_or_else(|| anyhow::anyhow!("Missing required parameter: query"))?;

        if query.trim().is_empty() {
            anyhow::bail!("Search query cannot be empty");
        }

        tracing::info!("Searching web for: {}", query);

        let result = match self.provider.as_str() {
            "brave" => self.search_brave(query).await?,
            "google" => self.search_google(query).await?,
            "none" => anyhow::bail!(
                "No search provider configured. Set brave_api_key or google_api_key + google_cx in [web_search] config"
            ),
            _ => anyhow::bail!(
                "Unknown search provider: '{}'. Set web_search.provider to 'auto', 'brave', or 'google'",
                self.provider
            ),
        };

        Ok(ToolResult {
            success: true,
            output: result,
            error: None,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn config_with(
        provider: &str,
        brave: Option<&str>,
        google_key: Option<&str>,
        google_cx: Option<&str>,
    ) -> WebSearchConfig {
        WebSearchConfig {
            enabled: true,
            provider: provider.to_string(),
            brave_api_key: brave.map(|s| s.to_string()),
            google_api_key: google_key.map(|s| s.to_string()),
            google_cx: google_cx.map(|s| s.to_string()),
            max_results: 5,
            timeout_secs: 15,
        }
    }

    // --- resolve_provider tests ---

    #[test]
    fn test_resolve_auto_brave_wins() {
        let result = resolve_provider("auto", &Some("key".into()), &None, &None);
        assert_eq!(result, "brave");
    }

    #[test]
    fn test_resolve_auto_google_wins_when_no_brave() {
        let result = resolve_provider(
            "auto",
            &None,
            &Some("key".into()),
            &Some("cx".into()),
        );
        assert_eq!(result, "google");
    }

    #[test]
    fn test_resolve_auto_brave_wins_over_google() {
        let result = resolve_provider(
            "auto",
            &Some("bkey".into()),
            &Some("gkey".into()),
            &Some("cx".into()),
        );
        assert_eq!(result, "brave");
    }

    #[test]
    fn test_resolve_auto_none_when_no_keys() {
        let result = resolve_provider("auto", &None, &None, &None);
        assert_eq!(result, "none");
    }

    #[test]
    fn test_resolve_auto_google_no_cx_yields_none() {
        let result = resolve_provider("auto", &None, &Some("gkey".into()), &None);
        assert_eq!(result, "none");
    }

    #[test]
    fn test_resolve_explicit_brave() {
        let result = resolve_provider("brave", &None, &None, &None);
        assert_eq!(result, "brave");
    }

    #[test]
    fn test_resolve_explicit_google() {
        let result = resolve_provider("google", &None, &None, &None);
        assert_eq!(result, "google");
    }

    // --- parse_google_results tests ---

    #[test]
    fn test_parse_google_valid_results() {
        let tool = WebSearchTool::new(&config_with("google", None, Some("key"), Some("cx")));
        let json = json!({
            "items": [
                {"title": "Rust Lang", "link": "https://rust-lang.org", "snippet": "A systems language."},
                {"title": "Rust Book", "link": "https://doc.rust-lang.org/book/", "snippet": "Learn Rust."}
            ]
        });
        let result = tool.parse_google_results(&json, "rust").unwrap();
        assert!(result.contains("via Google"));
        assert!(result.contains("Rust Lang"));
        assert!(result.contains("https://rust-lang.org"));
        assert!(result.contains("A systems language."));
        assert!(result.contains("Rust Book"));
    }

    #[test]
    fn test_parse_google_empty_items() {
        let tool = WebSearchTool::new(&config_with("google", None, Some("key"), Some("cx")));
        let json = json!({"items": []});
        let result = tool.parse_google_results(&json, "noresult").unwrap();
        assert!(result.contains("No results found"));
    }

    #[test]
    fn test_parse_google_missing_items_key() {
        let tool = WebSearchTool::new(&config_with("google", None, Some("key"), Some("cx")));
        let json = json!({"kind": "customsearch#search"});
        let result = tool.parse_google_results(&json, "noresult").unwrap();
        assert!(result.contains("No results found"));
    }

    #[test]
    fn test_parse_google_missing_fields_per_item() {
        let tool = WebSearchTool::new(&config_with("google", None, Some("key"), Some("cx")));
        let json = json!({
            "items": [
                {}
            ]
        });
        let result = tool.parse_google_results(&json, "test").unwrap();
        assert!(result.contains("No title"));
        assert!(result.contains("via Google"));
    }

    #[test]
    fn test_parse_google_respects_max_results() {
        let mut cfg = config_with("google", None, Some("key"), Some("cx"));
        cfg.max_results = 2;
        let tool = WebSearchTool::new(&cfg);
        let json = json!({
            "items": [
                {"title": "One", "link": "https://one.com", "snippet": ""},
                {"title": "Two", "link": "https://two.com", "snippet": ""},
                {"title": "Three", "link": "https://three.com", "snippet": ""}
            ]
        });
        let result = tool.parse_google_results(&json, "test").unwrap();
        assert!(result.contains("https://one.com"));
        assert!(result.contains("https://two.com"));
        assert!(!result.contains("https://three.com"));
    }

    // --- Tool trait tests ---

    #[test]
    fn test_tool_name() {
        let tool = WebSearchTool::new(&config_with("brave", Some("key"), None, None));
        assert_eq!(tool.name(), "web_search_tool");
    }

    #[test]
    fn test_tool_description() {
        let tool = WebSearchTool::new(&config_with("brave", Some("key"), None, None));
        assert!(tool.description().contains("Search the web"));
    }

    #[test]
    fn test_parameters_schema() {
        let tool = WebSearchTool::new(&config_with("brave", Some("key"), None, None));
        let schema = tool.parameters_schema();
        assert_eq!(schema["type"], "object");
        assert!(schema["properties"]["query"].is_object());
        let required = schema["required"].as_array().unwrap();
        assert!(required.iter().any(|v| v.as_str() == Some("query")));
    }

    // --- execute error tests ---

    #[tokio::test]
    async fn test_execute_missing_query() {
        let tool = WebSearchTool::new(&config_with("none", None, None, None));
        let result = tool.execute(json!({})).await;
        assert!(result.is_err());
        assert!(result.unwrap_err().to_string().contains("Missing required parameter"));
    }

    #[tokio::test]
    async fn test_execute_empty_query() {
        let tool = WebSearchTool::new(&config_with("none", None, None, None));
        let result = tool.execute(json!({"query": "   "})).await;
        assert!(result.is_err());
        assert!(result.unwrap_err().to_string().contains("cannot be empty"));
    }

    #[tokio::test]
    async fn test_execute_no_provider() {
        let tool = WebSearchTool::new(&config_with("none", None, None, None));
        let result = tool.execute(json!({"query": "rust"})).await;
        assert!(result.is_err());
        assert!(result.unwrap_err().to_string().contains("No search provider configured"));
    }

    #[tokio::test]
    async fn test_execute_brave_without_key() {
        let tool = WebSearchTool::new(&config_with("brave", None, None, None));
        let result = tool.execute(json!({"query": "rust"})).await;
        assert!(result.is_err());
        assert!(result.unwrap_err().to_string().contains("API key"));
    }

    #[tokio::test]
    async fn test_execute_google_without_key() {
        let tool = WebSearchTool::new(&config_with("google", None, None, Some("cx123")));
        let result = tool.execute(json!({"query": "rust"})).await;
        assert!(result.is_err());
        assert!(result.unwrap_err().to_string().contains("API key"));
    }

    #[tokio::test]
    async fn test_execute_google_without_cx() {
        let tool = WebSearchTool::new(&config_with("google", None, Some("gkey"), None));
        let result = tool.execute(json!({"query": "rust"})).await;
        assert!(result.is_err());
        assert!(result.unwrap_err().to_string().contains("cx"));
    }

    // --- constructor tests ---

    #[test]
    fn test_constructor_clamps_max_results() {
        let mut cfg = config_with("auto", None, None, None);
        cfg.max_results = 0;
        cfg.timeout_secs = 0;
        let tool = WebSearchTool::new(&cfg);
        assert_eq!(tool.max_results, 1);
        assert_eq!(tool.timeout_secs, 1);
    }

    #[test]
    fn test_constructor_clamps_max_results_high() {
        let mut cfg = config_with("auto", None, None, None);
        cfg.max_results = 99;
        let tool = WebSearchTool::new(&cfg);
        assert_eq!(tool.max_results, 10);
    }

    // --- parse_brave_results tests ---

    #[test]
    fn test_parse_brave_empty_results() {
        let tool = WebSearchTool::new(&config_with("brave", Some("key"), None, None));
        let json = json!({"web": {"results": []}});
        let result = tool.parse_brave_results(&json, "test").unwrap();
        assert!(result.contains("No results found"));
    }

    #[test]
    fn test_parse_brave_valid_results() {
        let tool = WebSearchTool::new(&config_with("brave", Some("key"), None, None));
        let json = json!({
            "web": {
                "results": [
                    {"title": "Rust", "url": "https://rust-lang.org", "description": "Systems language"},
                    {"title": "Cargo", "url": "https://crates.io", "description": "Package registry"}
                ]
            }
        });
        let result = tool.parse_brave_results(&json, "rust").unwrap();
        assert!(result.contains("via Brave"));
        assert!(result.contains("Rust"));
        assert!(result.contains("https://rust-lang.org"));
        assert!(result.contains("Systems language"));
    }

    #[test]
    fn test_parse_brave_invalid_response() {
        let tool = WebSearchTool::new(&config_with("brave", Some("key"), None, None));
        let json = json!({"unexpected": "format"});
        let result = tool.parse_brave_results(&json, "test");
        assert!(result.is_err());
        assert!(result.unwrap_err().to_string().contains("Invalid Brave API response"));
    }
}
