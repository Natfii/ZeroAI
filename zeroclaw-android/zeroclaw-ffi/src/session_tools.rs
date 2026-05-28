// Copyright (c) 2026 @Natfii. All rights reserved.

//! FFI-side tool implementations for the live agent session.
//!
//! Upstream `SecurityPolicy` is `pub(crate)`, so `MemoryStoreTool`,
//! `MemoryForgetTool`, `WebSearchTool`, `WebFetchTool`, and
//! `HttpRequestTool` cannot be constructed from outside the upstream
//! crate. The wrappers below replicate that logic for Android, where the
//! user directly initiates all agent actions and the upstream read-only
//! / rate-limit checks are unnecessary.

use std::sync::Arc;

use async_trait::async_trait;
use futures_util::StreamExt;
use zeroclaw::memory::{Memory, MemoryCategory};
use zeroclaw::tools::{Tool, ToolResult};
use zeroclaw_api::attribution::{Attributable, Role, ToolKind};

use crate::url_helpers;

// ── FFI tool implementations ────────────────────────────────────────────
//
// Upstream `SecurityPolicy` is `pub(crate)`, so `MemoryStoreTool` and
// `MemoryForgetTool` cannot be constructed from the FFI crate. The
// wrappers below replicate the upstream logic without the security
// check. On Android the user directly initiates all agent actions, so
// the upstream read-only / rate-limit checks are unnecessary.

/// FFI-specific memory store tool that bypasses `SecurityPolicy`.
///
/// On Android the user directly initiates all agent actions, so the
/// upstream read-only / rate-limit checks are unnecessary. The tool
/// delegates directly to the [`Memory`] backend.
pub(crate) struct FfiMemoryStoreTool {
    /// The memory backend shared with the daemon.
    pub(crate) memory: Arc<dyn Memory>,
}

impl Attributable for FfiMemoryStoreTool {
    fn role(&self) -> Role { Role::Tool(ToolKind::Memory) }
    fn alias(&self) -> &str { "memory_store" }
}

#[async_trait]
impl Tool for FfiMemoryStoreTool {
    fn name(&self) -> &'static str {
        "memory_store"
    }

    fn description(&self) -> &'static str {
        "Store a fact, preference, or note in long-term memory. \
         Use category 'core' for permanent facts, 'daily' for session notes, \
         'conversation' for chat context, or a custom category name."
    }

    fn parameters_schema(&self) -> serde_json::Value {
        serde_json::json!({
            "type": "object",
            "properties": {
                "key": {
                    "type": "string",
                    "description": "Unique key for this memory (e.g. 'user_lang', 'project_stack')"
                },
                "content": {
                    "type": "string",
                    "description": "The information to remember"
                },
                "category": {
                    "type": "string",
                    "description": "Memory category: 'core' (permanent), 'daily' (session), \
                                    'conversation' (chat), or a custom category name. \
                                    Defaults to 'core'."
                }
            },
            "required": ["key", "content"]
        })
    }

    async fn execute(&self, args: serde_json::Value) -> anyhow::Result<ToolResult> {
        let key = args
            .get("key")
            .and_then(|v| v.as_str())
            .ok_or_else(|| anyhow::anyhow!("Missing 'key' parameter"))?;

        let content = args
            .get("content")
            .and_then(|v| v.as_str())
            .ok_or_else(|| anyhow::anyhow!("Missing 'content' parameter"))?;

        let category = match args.get("category").and_then(|v| v.as_str()) {
            Some("core") | None => MemoryCategory::Core,
            Some("daily") => MemoryCategory::Daily,
            Some("conversation") => MemoryCategory::Conversation,
            Some(other) => MemoryCategory::Custom(other.to_string()),
        };

        match self.memory.store(key, content, category, None).await {
            Ok(()) => Ok(ToolResult {
                success: true,
                output: format!("Stored memory: {key}"),
                error: None,
            }),
            Err(e) => Ok(ToolResult {
                success: false,
                output: String::new(),
                error: Some(format!("Failed to store memory: {e}")),
            }),
        }
    }
}

/// FFI-specific memory forget tool that bypasses `SecurityPolicy`.
///
/// See [`FfiMemoryStoreTool`] for rationale on skipping security checks.
pub(crate) struct FfiMemoryForgetTool {
    /// The memory backend shared with the daemon.
    pub(crate) memory: Arc<dyn Memory>,
}

impl Attributable for FfiMemoryForgetTool {
    fn role(&self) -> Role { Role::Tool(ToolKind::Memory) }
    fn alias(&self) -> &str { "memory_forget" }
}

#[async_trait]
impl Tool for FfiMemoryForgetTool {
    fn name(&self) -> &'static str {
        "memory_forget"
    }

    fn description(&self) -> &'static str {
        "Remove a memory by key. Use to delete outdated facts or sensitive \
         data. Returns whether the memory was found and removed."
    }

    fn parameters_schema(&self) -> serde_json::Value {
        serde_json::json!({
            "type": "object",
            "properties": {
                "key": {
                    "type": "string",
                    "description": "The key of the memory to forget"
                }
            },
            "required": ["key"]
        })
    }

    async fn execute(&self, args: serde_json::Value) -> anyhow::Result<ToolResult> {
        let key = args
            .get("key")
            .and_then(|v| v.as_str())
            .ok_or_else(|| anyhow::anyhow!("Missing 'key' parameter"))?;

        match self.memory.forget(key).await {
            Ok(true) => Ok(ToolResult {
                success: true,
                output: format!("Forgot memory: {key}"),
                error: None,
            }),
            Ok(false) => Ok(ToolResult {
                success: true,
                output: format!("No memory found with key: {key}"),
                error: None,
            }),
            Err(e) => Ok(ToolResult {
                success: false,
                output: String::new(),
                error: Some(format!("Failed to forget memory: {e}")),
            }),
        }
    }
}

/// FFI-specific web search tool using Brave Search or Google Custom Search Engine JSON APIs.
///
/// Upstream [`zeroclaw::tools::WebSearchTool`] requires `Arc<SecurityPolicy>`,
/// which is `pub(crate)` and inaccessible from external crates. This standalone
/// implementation provides equivalent Brave + Google CSE support for the FFI layer.
///
/// Uses the name `"web_search"` (not upstream's `"web_search_tool"`) because models
/// fine-tuned for function calling generate calls to `"web_search"`.
pub(crate) struct FfiWebSearchTool {
    /// Resolved search provider: `"brave"`, `"google"`, or `"none"`.
    pub(crate) provider: String,
    /// Brave Search API subscription token.
    pub(crate) brave_api_key: Option<String>,
    /// Google Custom Search JSON API key.
    pub(crate) google_api_key: Option<String>,
    /// Google Custom Search Engine ID.
    pub(crate) google_cx: Option<String>,
    /// Maximum search results to return (1-10).
    pub(crate) max_results: usize,
    /// Shared HTTP client (reuses TLS sessions and connection pools).
    pub(crate) client: reqwest::Client,
}

/// Resolves the effective FFI search provider from the configured value and available keys.
///
/// When `provider` is `"auto"`, picks the first available provider:
/// brave (if `brave_key` is `Some`) → google (if both `google_key` and `google_cx`
/// are `Some`) → `"none"` (no providers configured). Any other value is returned
/// lowercased and trimmed as-is.
pub(crate) fn resolve_ffi_provider(
    provider: &str,
    brave_key: Option<&String>,
    google_key: Option<&String>,
    google_cx: Option<&String>,
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

impl FfiWebSearchTool {
    /// Issue a Brave Search API query and return formatted results.
    ///
    /// Uses `X-Subscription-Token` header. Maps 401 to invalid-key error and
    /// 429 to rate-limit error for clear operator-facing messages.
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

        let response = self
            .client
            .get(&search_url)
            .header("Accept", "application/json")
            .header("X-Subscription-Token", api_key)
            .send()
            .await?;

        let status = response.status();
        if !status.is_success() {
            match status.as_u16() {
                401 => anyhow::bail!("Brave search failed: invalid API key (401 Unauthorized)"),
                429 => anyhow::bail!(
                    "Brave search failed: rate limit exceeded (429 Too Many Requests)"
                ),
                _ => anyhow::bail!("Brave search failed with status: {status}"),
            }
        }

        let json: serde_json::Value = response.json().await?;
        self.parse_brave_results(&json, query)
    }

    /// Parse Brave Search API JSON response into a formatted result string.
    ///
    /// Extracts `web.results[].{title, url, description}` from the response object.
    fn parse_brave_results(&self, json: &serde_json::Value, query: &str) -> anyhow::Result<String> {
        let results = json
            .get("web")
            .and_then(|w| w.get("results"))
            .and_then(|r| r.as_array())
            .ok_or_else(|| anyhow::anyhow!("Invalid Brave API response"))?;

        if results.is_empty() {
            return Ok(format!("No results found for: {query}"));
        }

        let mut lines = vec![format!("Search results for: {query} (via Brave)")];

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

            lines.push(format!("{}. {title}", i + 1));
            lines.push(format!("   {url}"));
            if !description.is_empty() {
                lines.push(format!("   {description}"));
            }
        }

        Ok(lines.join("\n"))
    }

    /// Issue a Google Custom Search Engine JSON API query and return formatted results.
    ///
    /// Maps 403+dailyLimitExceeded to quota error, other 403 to forbidden, 400 to bad-cx,
    /// and 429 to rate-limit error for clear operator-facing messages.
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

        let response = self
            .client
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
                        anyhow::bail!("Google search failed: daily quota exceeded (403 Forbidden)");
                    }
                    anyhow::bail!(
                        "Google search failed: forbidden — check your API key (403 Forbidden)"
                    );
                }
                429 => anyhow::bail!(
                    "Google search failed: rate limit exceeded (429 Too Many Requests)"
                ),
                _ => anyhow::bail!("Google search failed with status: {status}"),
            }
        }

        let json: serde_json::Value = response.json().await?;
        Ok(self.parse_google_results(&json, query))
    }

    /// Parse Google CSE JSON response into a formatted result string.
    ///
    /// Extracts `items[].{title, link, snippet}` from the response object.
    fn parse_google_results(&self, json: &serde_json::Value, query: &str) -> String {
        let items = match json.get("items").and_then(|i| i.as_array()) {
            Some(arr) if !arr.is_empty() => arr,
            _ => return format!("No results found for: {query}"),
        };

        let mut lines = vec![format!("Search results for: {query} (via Google)")];

        for (i, item) in items.iter().take(self.max_results).enumerate() {
            let title = item
                .get("title")
                .and_then(|t| t.as_str())
                .unwrap_or("No title");
            let link = item.get("link").and_then(|l| l.as_str()).unwrap_or("");
            let snippet = item.get("snippet").and_then(|s| s.as_str()).unwrap_or("");

            lines.push(format!("{}. {title}", i + 1));
            lines.push(format!("   {link}"));
            if !snippet.is_empty() {
                lines.push(format!("   {snippet}"));
            }
        }

        lines.join("\n")
    }
}

impl Attributable for FfiWebSearchTool {
    fn role(&self) -> Role { Role::Tool(ToolKind::Search) }
    fn alias(&self) -> &str { "web_search" }
}

#[async_trait]
impl Tool for FfiWebSearchTool {
    fn name(&self) -> &'static str {
        "web_search"
    }

    fn description(&self) -> &'static str {
        "Search the web for information. Returns result titles, URLs, \
         and snippets. Use when: finding current information, news, or \
         researching a topic. Do NOT use this to fetch a known URL \
         (use web_fetch)."
    }

    fn parameters_schema(&self) -> serde_json::Value {
        serde_json::json!({
            "type": "object",
            "properties": {
                "query": {
                    "type": "string",
                    "description": "The search query"
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

        tracing::info!(
            query_len = query.len(),
            provider = %self.provider,
            "web_search: executing"
        );

        let output = match self.provider.as_str() {
            "brave" => self.search_brave(query).await?,
            "google" => self.search_google(query).await?,
            "none" => {
                return Ok(ToolResult {
                    success: false,
                    output: String::new(),
                    error: Some(
                        "No search provider configured. Set brave_api_key or \
                         google_api_key + google_cx in [web_search] config."
                            .to_string(),
                    ),
                });
            }
            other => {
                return Ok(ToolResult {
                    success: false,
                    output: String::new(),
                    error: Some(format!(
                        "Unknown search provider: \"{other}\". \
                         Valid values: \"brave\", \"google\", \"auto\"."
                    )),
                });
            }
        };

        Ok(ToolResult {
            success: true,
            output,
            error: None,
        })
    }
}

/// FFI-specific web fetch tool that bypasses `SecurityPolicy`.
///
/// Fetches a web page and converts HTML to clean plain text for LLM
/// consumption. Follows redirects (up to 10), validating each redirect
/// target against the domain allowlist and blocklist. Non-HTML content
/// types (plain text, markdown, JSON) are passed through as-is.
///
/// See [`FfiMemoryStoreTool`] for rationale on skipping security checks.
pub(crate) struct FfiWebFetchTool {
    /// Allowed domains for URL validation (exact or subdomain match).
    pub(crate) allowed_domains: Vec<String>,
    /// Blocked domains that override the allowlist.
    pub(crate) blocked_domains: Vec<String>,
    /// Maximum response body size in bytes before truncation.
    pub(crate) max_response_size: usize,
    /// Shared HTTP client (reuses TLS sessions and connection pools).
    pub(crate) client: reqwest::Client,
}

impl FfiWebFetchTool {
    /// Truncates text to the configured maximum size, appending a
    /// marker when content is cut off.
    fn truncate_response(&self, text: &str) -> String {
        if text.len() > self.max_response_size {
            let mut truncated = text
                .chars()
                .take(self.max_response_size)
                .collect::<String>();
            truncated.push_str("\n\n... [Response truncated due to size limit] ...");
            truncated
        } else {
            text.to_string()
        }
    }

    /// Reads the response body as a byte stream with a hard cap to
    /// prevent unbounded memory allocation from very large pages.
    async fn read_response_text_limited(
        &self,
        response: reqwest::Response,
    ) -> anyhow::Result<String> {
        let mut bytes_stream = response.bytes_stream();
        let hard_cap = self.max_response_size.saturating_add(1);
        let mut bytes = Vec::new();

        while let Some(chunk_result) = bytes_stream.next().await {
            let chunk = chunk_result?;
            let remaining = hard_cap.saturating_sub(bytes.len());
            if remaining == 0 {
                break;
            }
            if chunk.len() > remaining {
                bytes.extend_from_slice(&chunk[..remaining]);
                break;
            }
            bytes.extend_from_slice(&chunk);
        }

        Ok(String::from_utf8_lossy(&bytes).into_owned())
    }

    /// Determines the processing strategy for the response based on
    /// its `Content-Type` header. Returns `"html"`, `"plain"`, or an
    /// error for unsupported types.
    fn classify_content_type(response: &reqwest::Response) -> Result<&'static str, String> {
        let content_type = response
            .headers()
            .get(reqwest::header::CONTENT_TYPE)
            .and_then(|v| v.to_str().ok())
            .unwrap_or("")
            .to_lowercase();

        if content_type.contains("text/html") || content_type.is_empty() {
            Ok("html")
        } else if content_type.contains("text/plain")
            || content_type.contains("text/markdown")
            || content_type.contains("application/json")
        {
            Ok("plain")
        } else {
            Err(format!(
                "Unsupported content type: {content_type}. \
                 web_fetch supports text/html, text/plain, text/markdown, \
                 and application/json."
            ))
        }
    }
}

/// Constructs a failed [`ToolResult`] with the given error message.
pub(crate) fn fail_result(error: String) -> ToolResult {
    ToolResult {
        success: false,
        output: String::new(),
        error: Some(error),
    }
}

impl Attributable for FfiWebFetchTool {
    fn role(&self) -> Role { Role::Tool(ToolKind::FetchUrl) }
    fn alias(&self) -> &str { "web_fetch" }
}

#[async_trait]
impl Tool for FfiWebFetchTool {
    fn name(&self) -> &'static str {
        "web_fetch"
    }

    fn description(&self) -> &'static str {
        "Fetch a web page and return its content as clean plain text. \
         HTML pages are automatically converted to readable text. \
         JSON and plain text responses are returned as-is. \
         Only GET requests; follows redirects. \
         Security: allowlist-only domains, no local/private hosts."
    }

    fn parameters_schema(&self) -> serde_json::Value {
        serde_json::json!({
            "type": "object",
            "properties": {
                "url": {
                    "type": "string",
                    "description": "The HTTP or HTTPS URL to fetch"
                }
            },
            "required": ["url"]
        })
    }

    async fn execute(&self, args: serde_json::Value) -> anyhow::Result<ToolResult> {
        let raw_url = args
            .get("url")
            .and_then(|v| v.as_str())
            .ok_or_else(|| anyhow::anyhow!("Missing 'url' parameter"))?;

        let url = match url_helpers::validate_target_url_with_dns(
            raw_url,
            &self.allowed_domains,
            &self.blocked_domains,
            "web_fetch",
        )
        .await
        {
            Ok(v) => v,
            Err(e) => return Ok(fail_result(e)),
        };

        let response = match self.client.get(&url).send().await {
            Ok(r) => r,
            Err(e) => return Ok(fail_result(format!("HTTP request failed: {e}"))),
        };

        let status = response.status();
        if !status.is_success() {
            let reason = status.canonical_reason().unwrap_or("Unknown");
            return Ok(fail_result(format!("HTTP {} {reason}", status.as_u16())));
        }

        let body_mode = match Self::classify_content_type(&response) {
            Ok(m) => m,
            Err(e) => return Ok(fail_result(e)),
        };

        let body = match self.read_response_text_limited(response).await {
            Ok(t) => t,
            Err(e) => return Ok(fail_result(format!("Failed to read response body: {e}"))),
        };

        let text = if body_mode == "html" {
            nanohtml2text::html2text(&body)
        } else {
            body
        };

        Ok(ToolResult {
            success: true,
            output: self.truncate_response(&text),
            error: None,
        })
    }
}

/// FFI-specific HTTP request tool that bypasses `SecurityPolicy`.
///
/// Supports multiple HTTP methods (GET, POST, PUT, DELETE, PATCH, HEAD,
/// OPTIONS) with custom headers and request body. Unlike [`FfiWebFetchTool`],
/// this tool returns the raw response including status line and headers,
/// does not follow redirects, and does not convert HTML.
///
/// See [`FfiMemoryStoreTool`] for rationale on skipping security checks.
pub(crate) struct FfiHttpRequestTool {
    /// Allowed domains for URL validation (exact or subdomain match).
    pub(crate) allowed_domains: Vec<String>,
    /// Maximum response body size in bytes before truncation (0 = unlimited).
    pub(crate) max_response_size: usize,
    /// Shared HTTP client (reuses TLS sessions and connection pools).
    pub(crate) client: reqwest::Client,
}

impl FfiHttpRequestTool {
    /// Validates an HTTP method string and returns the corresponding
    /// [`reqwest::Method`], or an error for unsupported methods.
    fn validate_method(method: &str) -> Result<reqwest::Method, String> {
        match method.to_uppercase().as_str() {
            "GET" => Ok(reqwest::Method::GET),
            "POST" => Ok(reqwest::Method::POST),
            "PUT" => Ok(reqwest::Method::PUT),
            "DELETE" => Ok(reqwest::Method::DELETE),
            "PATCH" => Ok(reqwest::Method::PATCH),
            "HEAD" => Ok(reqwest::Method::HEAD),
            "OPTIONS" => Ok(reqwest::Method::OPTIONS),
            _ => Err(format!(
                "Unsupported HTTP method: {method}. \
                 Supported: GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS"
            )),
        }
    }

    /// Parses a JSON object of header key-value pairs into a `Vec` of
    /// string tuples. Non-string values are silently skipped.
    fn parse_headers(headers: &serde_json::Value) -> Vec<(String, String)> {
        let mut result = Vec::new();
        if let Some(obj) = headers.as_object() {
            for (key, value) in obj {
                if let Some(str_val) = value.as_str() {
                    result.push((key.clone(), str_val.to_string()));
                }
            }
        }
        result
    }

    /// Returns a copy of the headers with sensitive values replaced by
    /// `***REDACTED***` for safe logging.
    fn redact_headers_for_display(headers: &[(String, String)]) -> Vec<(String, String)> {
        headers
            .iter()
            .map(|(key, value)| {
                let lower = key.to_lowercase();
                let is_sensitive = lower.contains("authorization")
                    || lower.contains("api-key")
                    || lower.contains("apikey")
                    || lower.contains("token")
                    || lower.contains("secret");
                if is_sensitive {
                    (key.clone(), "***REDACTED***".into())
                } else {
                    (key.clone(), value.clone())
                }
            })
            .collect()
    }

    /// Truncates text to the configured maximum size.
    ///
    /// A `max_response_size` of 0 means unlimited (no truncation).
    fn truncate_response(&self, text: &str) -> String {
        if self.max_response_size == 0 {
            return text.to_string();
        }
        if text.len() > self.max_response_size {
            let mut truncated = text
                .chars()
                .take(self.max_response_size)
                .collect::<String>();
            truncated.push_str("\n\n... [Response truncated due to size limit] ...");
            truncated
        } else {
            text.to_string()
        }
    }

    /// Formats a successful response into the canonical output string
    /// including status line, headers, and (possibly truncated) body.
    async fn format_response(&self, response: reqwest::Response) -> ToolResult {
        let status = response.status();
        let status_code = status.as_u16();

        let headers_text = response
            .headers()
            .iter()
            .map(|(k, v)| {
                let key = k.as_str();
                if key.to_lowercase().contains("set-cookie") {
                    format!("{key}: ***REDACTED***")
                } else {
                    format!("{key}: {}", v.to_str().unwrap_or("<non-utf8>"))
                }
            })
            .collect::<Vec<_>>()
            .join(", ");

        let response_text = match response.text().await {
            Ok(text) => self.truncate_response(&text),
            Err(e) => format!("[Failed to read response body: {e}]"),
        };

        let reason = status.canonical_reason().unwrap_or("Unknown");
        let output = format!(
            "Status: {status_code} {reason}\n\
             Response Headers: {headers_text}\n\n\
             Response Body:\n{response_text}"
        );

        ToolResult {
            success: status.is_success(),
            output,
            error: if status.is_client_error() || status.is_server_error() {
                Some(format!("HTTP {status_code}"))
            } else {
                None
            },
        }
    }
}

impl Attributable for FfiHttpRequestTool {
    fn role(&self) -> Role { Role::Tool(ToolKind::HttpRequest) }
    fn alias(&self) -> &str { "http_request" }
}

#[async_trait]
impl Tool for FfiHttpRequestTool {
    fn name(&self) -> &'static str {
        "http_request"
    }

    fn description(&self) -> &'static str {
        "Make HTTP requests to external APIs. Supports GET, POST, PUT, DELETE, \
         PATCH, HEAD, OPTIONS methods. Returns status line, response headers, \
         and body. Security: allowlist-only domains, no local/private hosts, \
         configurable timeout and response size limits."
    }

    fn parameters_schema(&self) -> serde_json::Value {
        serde_json::json!({
            "type": "object",
            "properties": {
                "url": {
                    "type": "string",
                    "description": "HTTP or HTTPS URL to request"
                },
                "method": {
                    "type": "string",
                    "description": "HTTP method (GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS)",
                    "enum": ["GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"],
                    "default": "GET"
                },
                "headers": {
                    "type": "object",
                    "description": "Optional HTTP headers as key-value pairs"
                },
                "body": {
                    "type": "string",
                    "description": "Optional request body (for POST, PUT, PATCH requests)"
                }
            },
            "required": ["url"]
        })
    }

    async fn execute(&self, args: serde_json::Value) -> anyhow::Result<ToolResult> {
        let raw_url = args
            .get("url")
            .and_then(|v| v.as_str())
            .ok_or_else(|| anyhow::anyhow!("Missing 'url' parameter"))?;
        let method_str = args.get("method").and_then(|v| v.as_str()).unwrap_or("GET");
        let headers_val = args
            .get("headers")
            .cloned()
            .unwrap_or_else(|| serde_json::json!({}));
        let body = args.get("body").and_then(|v| v.as_str());

        let url = match url_helpers::validate_target_url_with_dns(
            raw_url,
            &self.allowed_domains,
            &[],
            "http_request",
        )
        .await
        {
            Ok(v) => v,
            Err(e) => return Ok(fail_result(e)),
        };

        let method = match Self::validate_method(method_str) {
            Ok(m) => m,
            Err(e) => return Ok(fail_result(e)),
        };

        let request_headers = Self::parse_headers(&headers_val);
        let redacted = Self::redact_headers_for_display(&request_headers);
        tracing::debug!(url = %url, method = %method, headers = ?redacted, "http_request: dispatching");

        let client = &self.client;

        let mut request = client.request(method, &url);
        for (key, value) in request_headers {
            request = request.header(&key, &value);
        }
        if let Some(body_str) = body {
            request = request.body(body_str.to_string());
        }

        match request.send().await {
            Ok(response) => Ok(self.format_response(response).await),
            Err(e) => Ok(fail_result(format!("HTTP request failed: {e}"))),
        }
    }
}
