/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

//! Live agent session management with streaming tool-call loop integration.
//!
//! A session represents a single multi-turn conversation with the `ZeroClaw`
//! agent loop. The lifecycle follows a strict state machine:
//!
//! 1. **Start** -- [`session_start`](crate::session_start) creates a new
//!    session, parsing daemon config and building the system prompt.
//! 2. **Seed** -- optional: inject prior context via
//!    [`session_seed_history`](crate::session_seed_history).
//! 3. **Send** -- [`session_send`](crate::session_send) runs the full
//!    tool-call loop, streaming progress deltas through an
//!    [`FfiSessionListener`] callback.
//! 4. **Cancel / Clear** -- abort the current send or wipe history.
//! 5. **History** -- [`session_history`](crate::session_history) returns
//!    the conversation transcript.
//! 6. **Destroy** -- [`session_destroy`](crate::session_destroy) tears
//!    down the session and releases all resources.
//!
//! Only one session exists at a time (guarded by the [`SESSION`] mutex).

use std::fmt::Write;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use async_trait::async_trait;
use futures_util::StreamExt;
use tokio_util::sync::CancellationToken;
use zeroclaw::memory::{Memory, MemoryCategory};
use zeroclaw::providers::{ChatMessage, ChatRequest, Provider, ToolCall};
use zeroclaw::tools::{Tool, ToolResult, ToolSpec};

use crate::error::FfiError;
use crate::runtime::{clone_daemon_config, clone_daemon_memory};
use crate::url_helpers;

/// Maximum user message size in bytes (1 MiB).
const MAX_MESSAGE_BYTES: usize = 1_048_576;

/// Default HTTP user-agent for web search, web fetch, and HTTP request tools.
const DEFAULT_USER_AGENT: &str = "ZeroClaw/1.0 (Android)";

/// Default maximum agentic tool-use iterations per user message.
const DEFAULT_MAX_TOOL_ITERATIONS: usize = 10;

/// Maximum number of tool calls to execute from a single model response.
///
/// Prompt-guided models (e.g. Codex) sometimes emit dozens of
/// `<tool_call>` tags in one response. Executing all of them wastes
/// tokens and fills the thinking card with noise. Excess calls are
/// dropped with a warning.
const MAX_TOOL_CALLS_PER_RESPONSE: usize = 5;

/// Non-system message count threshold that triggers auto-compaction.
const DEFAULT_MAX_HISTORY_MESSAGES: usize = 50;

/// Number of most-recent non-system messages to keep after compaction.
const COMPACTION_KEEP_RECENT: usize = 20;

/// Safety cap for the compaction source transcript sent to the summariser.
const COMPACTION_MAX_SOURCE_CHARS: usize = 12_000;

/// Maximum characters retained in the stored compaction summary.
const COMPACTION_MAX_SUMMARY_CHARS: usize = 2_000;

/// Minimum characters per chunk when streaming the final response text.
const STREAM_CHUNK_MIN_CHARS: usize = 80;

/// Maximum number of seed messages accepted by [`session_seed_inner`].
const MAX_SEED_MESSAGES: usize = 20;

/// The global singleton session slot.
///
/// At most one [`Session`] is active at any time. Operations that require
/// a running session acquire this mutex and return
/// [`FfiError::StateError`] when the slot is `None`.
static SESSION: Mutex<Option<Session>> = Mutex::new(None);

/// The cancellation token for the currently active [`session_send_inner`] call.
///
/// Set at the start of `session_send_inner`, cleared on exit. Calling
/// [`session_cancel_inner`] cancels the token, causing the agent loop
/// to abort at the next check point.
static CANCEL_TOKEN: Mutex<Option<CancellationToken>> = Mutex::new(None);

/// Locks the [`SESSION`] mutex, recovering from poison if a prior holder panicked.
///
/// See [`crate::runtime::lock_daemon`] for the rationale behind poison recovery.
fn lock_session() -> std::sync::MutexGuard<'static, Option<Session>> {
    SESSION.lock().unwrap_or_else(|e| {
        tracing::warn!("Session mutex was poisoned; recovering: {e}");
        e.into_inner()
    })
}

/// Locks the [`CANCEL_TOKEN`] mutex, recovering from poison.
fn lock_cancel_token() -> std::sync::MutexGuard<'static, Option<CancellationToken>> {
    CANCEL_TOKEN.lock().unwrap_or_else(|e| {
        tracing::warn!("Cancel token mutex was poisoned; recovering: {e}");
        e.into_inner()
    })
}

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
struct FfiMemoryStoreTool {
    /// The memory backend shared with the daemon.
    memory: Arc<dyn Memory>,
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
struct FfiMemoryForgetTool {
    /// The memory backend shared with the daemon.
    memory: Arc<dyn Memory>,
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
struct FfiWebSearchTool {
    /// Resolved search provider: `"brave"`, `"google"`, or `"none"`.
    provider: String,
    /// Brave Search API subscription token.
    brave_api_key: Option<String>,
    /// Google Custom Search JSON API key.
    google_api_key: Option<String>,
    /// Google Custom Search Engine ID.
    google_cx: Option<String>,
    /// Maximum search results to return (1-10).
    max_results: usize,
    /// Shared HTTP client (reuses TLS sessions and connection pools).
    client: reqwest::Client,
}

/// Resolves the effective FFI search provider from the configured value and available keys.
///
/// When `provider` is `"auto"`, picks the first available provider:
/// brave (if `brave_key` is `Some`) → google (if both `google_key` and `google_cx`
/// are `Some`) → `"none"` (no providers configured). Any other value is returned
/// lowercased and trimmed as-is.
fn resolve_ffi_provider(
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
struct FfiWebFetchTool {
    /// Allowed domains for URL validation (exact or subdomain match).
    allowed_domains: Vec<String>,
    /// Blocked domains that override the allowlist.
    blocked_domains: Vec<String>,
    /// Maximum response body size in bytes before truncation.
    max_response_size: usize,
    /// Shared HTTP client (reuses TLS sessions and connection pools).
    client: reqwest::Client,
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
fn fail_result(error: String) -> ToolResult {
    ToolResult {
        success: false,
        output: String::new(),
        error: Some(error),
    }
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
struct FfiHttpRequestTool {
    /// Allowed domains for URL validation (exact or subdomain match).
    allowed_domains: Vec<String>,
    /// Maximum response body size in bytes before truncation (0 = unlimited).
    max_response_size: usize,
    /// Shared HTTP client (reuses TLS sessions and connection pools).
    client: reqwest::Client,
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

/// Builds the tools registry for the Android agent session.
///
/// Constructs tools that are available without upstream's `SecurityPolicy`:
/// - Memory tools (store, recall, forget) via FFI wrappers and upstream
/// - Cron listing tools via upstream constructors
/// - Web search via upstream constructor (when enabled in config)
/// - Web fetch via FFI wrapper (when enabled in config)
/// - HTTP request via FFI wrapper (when enabled in config)
///
/// Tools that require `SecurityPolicy` (shell, file I/O, git, browser) are
/// excluded because the upstream security module is `pub(crate)`. These
/// tools are also less relevant on Android where the OS sandbox provides
/// security boundaries.
/// Maximum number of tools registered in a single session.
///
/// Prevents excessive token consumption when many plugins are enabled.
/// The LLM receives tool specs as part of the system prompt; each tool
/// costs 200-500 tokens. Beyond this limit, lower-priority tools are
/// silently dropped and a warning is logged.
const MAX_SESSION_TOOLS: usize = 20;

#[allow(clippy::too_many_lines)]
fn build_tools_registry(config: &zeroclaw::Config, memory: Arc<dyn Memory>) -> Vec<Box<dyn Tool>> {
    let config_arc = Arc::new(config.clone());
    let mut tools: Vec<Box<dyn Tool>> = vec![
        Box::new(FfiMemoryStoreTool {
            memory: memory.clone(),
        }),
        Box::new(zeroclaw::tools::MemoryRecallTool::new(memory.clone())),
        Box::new(FfiMemoryForgetTool { memory }),
        Box::new(zeroclaw::tools::CronListTool::new(config_arc.clone())),
        Box::new(zeroclaw::tools::CronRunsTool::new(config_arc)),
    ];

    if config.web_search.enabled {
        let provider = resolve_ffi_provider(
            &config.web_search.provider,
            config.web_search.brave_api_key.as_ref(),
            config.web_search.google_api_key.as_ref(),
            config.web_search.google_cx.as_ref(),
        );
        match reqwest::Client::builder()
            .timeout(Duration::from_secs(config.web_search.timeout_secs))
            .build()
        {
            Ok(client) => {
                tools.push(Box::new(FfiWebSearchTool {
                    provider,
                    brave_api_key: config.web_search.brave_api_key.clone(),
                    google_api_key: config.web_search.google_api_key.clone(),
                    google_cx: config.web_search.google_cx.clone(),
                    max_results: config.web_search.max_results,
                    client,
                }));
            }
            Err(e) => {
                tracing::error!("Failed to build web_search HTTP client: {e}; tool disabled");
            }
        }
    }

    if config.web_fetch.enabled {
        let fetch_allowed =
            url_helpers::normalize_allowed_domains(config.web_fetch.allowed_domains.clone());
        let fetch_blocked =
            url_helpers::normalize_allowed_domains(config.web_fetch.blocked_domains.clone());
        let timeout_secs = if config.web_fetch.timeout_secs == 0 {
            30
        } else {
            config.web_fetch.timeout_secs
        };
        let allowed_for_redirect = fetch_allowed.clone();
        let blocked_for_redirect = fetch_blocked.clone();
        let redirect_policy = reqwest::redirect::Policy::custom(move |attempt| {
            if attempt.previous().len() >= 10 {
                return attempt.error(std::io::Error::other("Too many redirects (max 10)"));
            }
            if let Err(err) = url_helpers::validate_target_url(
                attempt.url().as_str(),
                &allowed_for_redirect,
                &blocked_for_redirect,
                "web_fetch",
            ) {
                return attempt.error(std::io::Error::new(
                    std::io::ErrorKind::PermissionDenied,
                    format!("Blocked redirect target: {err}"),
                ));
            }
            attempt.follow()
        });
        match reqwest::Client::builder()
            .timeout(Duration::from_secs(timeout_secs))
            .connect_timeout(Duration::from_secs(10))
            .redirect(redirect_policy)
            .user_agent(DEFAULT_USER_AGENT)
            .build()
        {
            Ok(client) => {
                tools.push(Box::new(FfiWebFetchTool {
                    allowed_domains: fetch_allowed,
                    blocked_domains: fetch_blocked,
                    max_response_size: config.web_fetch.max_response_size,
                    client,
                }));
            }
            Err(e) => {
                tracing::error!("Failed to build web_fetch HTTP client: {e}; tool disabled");
            }
        }
    }

    if config.http_request.enabled {
        let timeout_secs = if config.http_request.timeout_secs == 0 {
            30
        } else {
            config.http_request.timeout_secs
        };
        match reqwest::Client::builder()
            .timeout(Duration::from_secs(timeout_secs))
            .connect_timeout(Duration::from_secs(10))
            .redirect(reqwest::redirect::Policy::none())
            .build()
        {
            Ok(client) => {
                tools.push(Box::new(FfiHttpRequestTool {
                    allowed_domains: url_helpers::normalize_allowed_domains(
                        config.http_request.allowed_domains.clone(),
                    ),
                    max_response_size: config.http_request.max_response_size,
                    client,
                }));
            }
            Err(e) => {
                tracing::error!("Failed to build http_request HTTP client: {e}; tool disabled");
            }
        }
    }

    if config.twitter_browse.enabled {
        tools.push(Box::new(zeroclaw::tools::TwitterBrowseTool::new(
            config.twitter_browse.cookie_string.clone(),
            config.twitter_browse.max_items,
            config.twitter_browse.timeout_secs,
        )));
    }

    if config.shared_folder.enabled {
        tools.extend(crate::shared_folder::create_shared_folder_tools());
    }

    if let Some(email_config) = config.email.as_ref().filter(|c| c.enabled) {
        let email_client = Arc::new(zeroclaw::tools::email::client::EmailClient::from_config(
            email_config,
        ));
        tools.push(Box::new(
            zeroclaw::tools::email::check::EmailCheckTool::new(email_client.clone()),
        ));
        tools.push(Box::new(zeroclaw::tools::email::read::EmailReadTool::new(
            email_client.clone(),
        )));
        tools.push(Box::new(
            zeroclaw::tools::email::reply::EmailReplyTool::new(email_client.clone()),
        ));
        tools.push(Box::new(
            zeroclaw::tools::email::compose::EmailComposeTool::new(email_client.clone()),
        ));
        tools.push(Box::new(
            zeroclaw::tools::email::search::EmailSearchTool::new(email_client.clone()),
        ));
        tools.push(Box::new(
            zeroclaw::tools::email::delete::EmailDeleteTool::new(email_client.clone()),
        ));
        tools.push(Box::new(
            zeroclaw::tools::email::empty_trash::EmailEmptyTrashTool::new(email_client),
        ));
    }

    if tools.len() > MAX_SESSION_TOOLS {
        tracing::warn!(
            total = tools.len(),
            limit = MAX_SESSION_TOOLS,
            "Session tool count exceeds budget; truncating to {MAX_SESSION_TOOLS} tools",
        );
        tools.truncate(MAX_SESSION_TOOLS);
    }

    tools
}

/// Generates [`ToolSpec`] metadata from the tools registry.
///
/// Uses each tool's [`Tool::spec`] method to produce the name, description,
/// and JSON parameter schema that the provider uses for native tool calling.
fn tool_specs_from_registry(tools: &[Box<dyn Tool>]) -> Vec<ToolSpec> {
    tools.iter().map(|t| t.spec()).collect()
}

/// Internal session state holding conversation history and provider config.
///
/// Not exposed across the FFI boundary -- Kotlin interacts exclusively
/// through exported free functions and the [`FfiSessionListener`] callback.
struct Session {
    /// Accumulated conversation messages (user + assistant turns).
    history: Vec<ChatMessage>,
    /// Parsed daemon configuration snapshot taken at session creation.
    config: zeroclaw::Config,
    /// Assembled system prompt (identity + workspace files).
    system_prompt: String,
    /// Model identifier passed to the provider (e.g. `"gpt-4o"`).
    model: String,
    /// Sampling temperature for the provider.
    temperature: f64,
    /// Provider name used to create the provider instance (e.g. `"openai"`).
    provider_name: String,
    /// Tools registry built from available upstream tools and FFI wrappers.
    tools_registry: Vec<Box<dyn Tool>>,
}

/// RAII guard that ensures taken-out session state (history + tools) is
/// always restored, even if a panic occurs during processing.
///
/// When [`session_send_inner`] takes history and tools out of the
/// [`SESSION`] mutex for processing, a panic between take and put-back
/// would leave the session in a zombified state (active but empty).
/// RAII guard that restores session state (history + tools) on drop,
/// even if a panic occurs during processing.
///
/// # Invariants
///
/// **Must** only be constructed after the [`SESSION`] mutex has been
/// released (i.e. after `history` and `tools` are moved out and the
/// guard is dropped). The [`Drop`] impl re-acquires both [`SESSION`]
/// and [`CANCEL_TOKEN`] via their poison-recovering helpers.
///
/// Constructing this guard while holding either lock **will deadlock**
/// during stack unwinding.
///
/// This guard's [`Drop`] implementation puts the state back
/// automatically during stack unwinding, preventing permanent data loss.
///
/// Call [`SessionStateGuard::defuse`] after a successful put-back to
/// prevent a redundant restore.
struct SessionStateGuard {
    /// Conversation history taken from the session. `None` once defused.
    history: Option<Vec<ChatMessage>>,
    /// Tools registry taken from the session. `None` once defused.
    tools: Option<Vec<Box<dyn Tool>>>,
}

impl SessionStateGuard {
    /// Creates a new guard holding the taken-out session state.
    fn new(history: Vec<ChatMessage>, tools: Vec<Box<dyn Tool>>) -> Self {
        Self {
            history: Some(history),
            tools: Some(tools),
        }
    }

    /// Returns mutable references to the held history and tools.
    ///
    /// # Errors
    ///
    /// Returns [`FfiError::StateCorrupted`] if called after [`take`](Self::take)
    /// or [`defuse`](Self::defuse) already consumed the held state.
    #[allow(clippy::type_complexity)]
    fn state_mut(&mut self) -> Result<(&mut Vec<ChatMessage>, &[Box<dyn Tool>]), FfiError> {
        debug_assert!(
            self.history.is_some() && self.tools.is_some(),
            "SessionStateGuard::state_mut called after take/defuse"
        );
        let history = self
            .history
            .as_mut()
            .ok_or_else(|| FfiError::StateCorrupted {
                detail: "SessionStateGuard::state_mut called after take/defuse (history)".into(),
            })?;
        let tools = self
            .tools
            .as_deref()
            .ok_or_else(|| FfiError::StateCorrupted {
                detail: "SessionStateGuard::state_mut called after take/defuse (tools)".into(),
            })?;
        Ok((history, tools))
    }

    /// Consumes the held state, returning ownership to the caller.
    ///
    /// After this call the guard's [`Drop`] is a no-op.
    ///
    /// # Errors
    ///
    /// Returns [`FfiError::StateCorrupted`] if called after a previous
    /// [`take`](Self::take) or [`defuse`](Self::defuse) already consumed
    /// the held state.
    #[allow(clippy::type_complexity)]
    fn take(mut self) -> Result<(Vec<ChatMessage>, Vec<Box<dyn Tool>>), FfiError> {
        debug_assert!(
            self.history.is_some() && self.tools.is_some(),
            "SessionStateGuard::take called after take/defuse"
        );
        match (self.history.take(), self.tools.take()) {
            (Some(history), Some(tools)) => Ok((history, tools)),
            _ => Err(FfiError::StateCorrupted {
                detail: "SessionStateGuard::take called after take/defuse".into(),
            }),
        }
    }
}

impl Drop for SessionStateGuard {
    /// Restore session state when the guard is dropped during a panic unwind.
    ///
    /// # Safety — Mutex Reentrancy
    ///
    /// This acquires `SESSION` and `CANCEL_TOKEN` mutexes during `drop`, which
    /// may run inside a `catch_unwind` unwind. This is safe because:
    /// - `run_agent_loop` (the only call-site that creates a guard) takes
    ///   `history` and `tools` **out** of the session before entering the loop,
    ///   so it does **not** hold the `SESSION` lock when panic occurs.
    /// - `lock_session()` / `lock_cancel_token()` use poison-recovering helpers,
    ///   so a previously-panicked thread cannot deadlock this path.
    fn drop(&mut self) {
        let Some(history) = self.history.take() else {
            return;
        };
        let Some(tools) = self.tools.take() else {
            return;
        };

        tracing::warn!("SessionStateGuard::drop restoring state after panic");
        let mut guard = lock_session();
        if let Some(session) = guard.as_mut() {
            session.history = history;
            session.tools_registry = tools;
        }
        // Also clear cancel token to prevent stale state.
        *lock_cancel_token() = None;
    }
}

/// A single conversation message exchanged over the FFI boundary.
///
/// Mirrors [`zeroclaw::providers::ChatMessage`] but uses UniFFI-compatible
/// types. The `role` field is one of `"system"`, `"user"`, or `"assistant"`.
#[derive(uniffi::Record, Clone, Debug)]
pub struct SessionMessage {
    /// The message role: `"system"`, `"user"`, or `"assistant"`.
    pub role: String,
    /// The text content of the message.
    pub content: String,
}

/// Typed progress phases sent from the agent loop to the Kotlin UI.
///
/// Each variant maps to a distinct visual state in the thinking card.
/// Replaces the previous freeform `on_progress(String)` callback.
#[derive(Debug, Clone, uniffi::Enum)]
pub enum FfiProgressPhase {
    /// Building memory context from the vector store.
    SearchingMemory,
    /// Sending the prompt to the LLM provider.
    ///
    /// `round` is 1-based; round 1 is the initial call, round 2+ are
    /// tool-loop iterations.
    CallingProvider { round: u32 },
    /// The LLM returned tool call requests.
    ///
    /// `count` is the number of tool calls, `llm_duration_secs` is the
    /// wall-clock time for the LLM response.
    GotToolCalls { count: u32, llm_duration_secs: u64 },
    /// The agent is now streaming the final response text.
    StreamingResponse,
    /// The conversation history is being compacted.
    Compacting,
    /// No active progress (clears any displayed status).
    Idle,
    /// Raw progress message from the upstream agent loop.
    ///
    /// Used when `dispatch_delta` receives an informational message that
    /// doesn't map to a specific typed phase.
    Raw { message: String },
}

/// Callback interface that Kotlin implements to receive live agent session events.
///
/// Events are dispatched from the tokio runtime thread during
/// [`session_send`](crate::session_send). Implementations must be
/// thread-safe (`Send + Sync`). Each callback corresponds to a distinct
/// phase of the agent's tool-call loop execution.
#[uniffi::export(callback_interface)]
pub trait FfiSessionListener: Send + Sync {
    /// The agent is producing internal reasoning (thinking/planning).
    ///
    /// Called with progressive text chunks as the agent reasons about
    /// which tools to invoke or how to answer.
    fn on_thinking(&self, text: String);

    /// A chunk of the agent's final response text has arrived.
    ///
    /// Called incrementally as the provider streams response tokens.
    /// Concatenating all chunks yields the full response.
    fn on_response_chunk(&self, text: String);

    /// The agent is about to invoke a tool.
    ///
    /// `name` is the tool identifier (e.g. `"read_file"`).
    /// `arguments_hint` is a short summary of the arguments, which may
    /// be empty if no hint is available.
    fn on_tool_start(&self, name: String, arguments_hint: String);

    /// A tool invocation has completed.
    ///
    /// `name` is the tool identifier, `success` indicates whether the
    /// tool returned a result or an error, and `duration_secs` is the
    /// wall-clock execution time rounded to whole seconds.
    fn on_tool_result(&self, name: String, success: bool, duration_secs: u64);

    /// Raw tool output text for display in a collapsible detail section.
    ///
    /// Called after [`on_tool_result`](FfiSessionListener::on_tool_result)
    /// with the full stdout/stderr captured from the tool execution.
    fn on_tool_output(&self, name: String, output: String);

    /// A typed progress phase from the agent loop.
    ///
    /// Each [`FfiProgressPhase`] variant maps to a distinct visual state
    /// in the thinking card. Replaces the previous freeform string callback.
    fn on_progress(&self, phase: FfiProgressPhase);

    /// Clears any displayed progress status.
    ///
    /// Called when the agent transitions out of a progress phase (e.g.
    /// before streaming the final response text).
    fn on_progress_clear(&self);

    /// The conversation history was compacted to fit the context window.
    ///
    /// `summary` contains the AI-generated summary that replaced older
    /// messages. The UI should display this as a fold/expansion point.
    fn on_compaction(&self, summary: String);

    /// The agent loop has finished and the full response is available.
    ///
    /// `full_response` contains the concatenated final answer. This is
    /// always the last callback for a successful send.
    fn on_complete(&self, full_response: String);

    /// An unrecoverable error occurred during the agent loop.
    ///
    /// `error` contains a human-readable description. The session
    /// remains valid and the caller may retry with a new send.
    fn on_error(&self, error: String);

    /// The current send was cancelled by the user.
    ///
    /// The session remains valid; the caller may issue a new send.
    fn on_cancelled(&self);
}

// ── Session lifecycle ───────────────────────────────────────────────────

/// Creates a new live agent session from the running daemon's configuration.
///
/// Mirrors the setup phase of upstream `zeroclaw::agent::run()`:
///
/// 1. Clones the daemon config snapshot.
/// 2. Resolves provider name, model, and temperature.
/// 3. Loads workspace and community skills.
/// 4. Builds tool description metadata for the system prompt.
/// 5. Creates a temporary provider to query native tool support.
/// 6. Builds the full system prompt via
///    [`zeroclaw::channels::build_system_prompt_with_mode`].
/// 7. Seeds the conversation history with the system prompt.
/// 8. Stores the [`Session`] in the global [`SESSION`] mutex.
///
/// Only one session may exist at a time. Calling this while a session is
/// already active returns [`FfiError::StateError`].
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if a session is already active or
/// the daemon is not running, [`FfiError::StateCorrupted`] if the session
/// mutex is poisoned, or [`FfiError::SpawnError`] if provider creation fails.
pub(crate) fn session_start_inner() -> Result<(), FfiError> {
    let config = clone_daemon_config()?;

    let provider_name = config
        .default_provider
        .as_deref()
        .unwrap_or("anthropic")
        .to_string();

    let model = config
        .default_model
        .as_deref()
        .unwrap_or("anthropic/claude-sonnet-4")
        .to_string();

    let temperature = config.default_temperature;

    // Build tools registry from daemon memory + config.
    let tools_registry = if let Ok(mem) = clone_daemon_memory() {
        build_tools_registry(&config, mem)
    } else {
        tracing::warn!("Memory backend unavailable; session tools will be limited");
        Vec::new()
    };

    // Generate tool descriptions from the real tools registry, plus
    // static descriptions for tools the LLM should know about but that
    // cannot be constructed from the FFI crate.
    let mut tool_descs = build_android_tool_descs(&config);
    for tool in &tools_registry {
        let name = tool.name().to_string();
        if !tool_descs.iter().any(|(n, _)| n == &name) {
            tool_descs.push((name, tool.description().to_string()));
        }
    }

    let tool_desc_refs: Vec<(&str, &str)> = tool_descs
        .iter()
        .map(|(name, desc)| (name.as_str(), desc.as_str()))
        .collect();

    let bootstrap_max_chars = if config.agent.compact_context {
        Some(6000)
    } else {
        None
    };

    let native_tools = {
        let provider =
            zeroclaw::providers::create_provider(&provider_name, config.api_key.as_deref())
                .map_err(|e| FfiError::SpawnError {
                    detail: format!("failed to create provider for native-tools check: {e}"),
                })?;
        provider.supports_native_tools()
    };

    let skills = zeroclaw::skills::load_skills_with_config(&config.workspace_dir, &config);
    let mut system_prompt = zeroclaw::channels::build_system_prompt_with_mode(
        &config.workspace_dir,
        &model,
        &tool_desc_refs,
        &skills,
        Some(&config.identity),
        bootstrap_max_chars,
        native_tools,
        config.skills.prompt_injection_mode,
        config.email.as_ref(),
        config.system_prompt.hub_app_context.as_deref(),
    );

    // When the provider does not support native tool calling, append the
    // full Tool Use Protocol to the system prompt so the model knows to
    // emit <tool_call> XML tags. Without this, the model will answer
    // directly instead of using tools.
    //
    // When native tools ARE supported, append a condensed XML fallback
    // hint. Some models (Qwen, GLM distills, small Ollama models) receive
    // the native tool schema but cannot generate structured tool_calls in
    // the response. Without this hint they spiral: they see tools in the
    // prompt, fail to invoke them, and retry the same text-based attempt
    // every turn. The agent loop already parses <tool_call> XML as a
    // fallback even when native tools are enabled.
    if !tools_registry.is_empty() {
        if native_tools {
            system_prompt.push_str(
                "\n## Tool Use Fallback\n\n\
                 Your tools are available via the API's native function-calling mechanism.\n\
                 If you cannot emit structured tool_calls, you may instead wrap a JSON \
                 object in <tool_call></tool_call> tags:\n\n\
                 <tool_call>\n\
                 {\"name\": \"tool_name\", \"arguments\": {\"param\": \"value\"}}\n\
                 </tool_call>\n\n\
                 Never describe or simulate tool calls in prose. \
                 Either use native function calling or the XML tags above.\n",
            );
        } else {
            system_prompt.push_str(&build_tool_use_protocol(&tools_registry, &config));
        }
    }

    // Upstream AIEOS only renders agent identity fields; Android onboarding
    // also stores user_name, timezone, and communication_style inside the
    // identity JSON object. Extract and append them so the model knows who
    // it is talking to.
    append_android_identity_extras(&mut system_prompt, &config.identity);

    let history = vec![ChatMessage::system(&system_prompt)];

    let session = Session {
        history,
        config,
        system_prompt,
        model,
        temperature,
        provider_name,
        tools_registry,
    };

    let mut guard = lock_session();

    if guard.is_some() {
        return Err(FfiError::StateError {
            detail: "a session is already active; destroy it first".into(),
        });
    }

    *guard = Some(session);

    tracing::info!("Live agent session started");
    Ok(())
}

/// Maximum number of images per session send request.
const MAX_SESSION_IMAGES: usize = 5;

/// Sends a message through the live agent session's tool-call loop.
///
/// This is the core function that drives multi-turn agent interaction.
/// The flow is:
///
/// 1. Validate message size (max 1 MiB) and image arrays.
/// 2. Compose multimodal message with `[IMAGE:...]` markers if images
///    are present.
/// 3. Create a [`CancellationToken`] and store it in [`CANCEL_TOKEN`].
/// 4. Take the session's history out of the [`SESSION`] mutex.
/// 5. Build memory context by recalling relevant memories.
/// 6. Enrich the user message with memory context and a timestamp.
/// 7. Create a fresh provider and tools registry.
/// 8. Run the agent loop ([`run_agent_loop`]).
/// 9. On success: run compaction, put history back, fire `on_complete`.
/// 10. On cancel: keep partial history, put history back, fire
///     `on_cancelled`.
/// 11. On error: truncate history to pre-send state, put history back,
///     fire `on_error`.
/// 12. Clear [`CANCEL_TOKEN`].
///
/// # Errors
///
/// Returns [`FfiError::ConfigError`] for oversized messages or
/// mismatched image arrays, [`FfiError::StateError`] if no session is
/// active, [`FfiError::StateCorrupted`] if the session mutex is
/// poisoned, or [`FfiError::SpawnError`] if the agent loop or provider
/// creation fails.
#[allow(clippy::too_many_lines)]
pub(crate) fn session_send_inner(
    message: String,
    image_data: Vec<String>,
    mime_types: Vec<String>,
    listener: Arc<dyn FfiSessionListener>,
) -> Result<(), FfiError> {
    // Validate image arrays before composing the message.
    if image_data.len() != mime_types.len() {
        return Err(FfiError::ConfigError {
            detail: format!(
                "image_data length ({}) != mime_types length ({})",
                image_data.len(),
                mime_types.len()
            ),
        });
    }
    if image_data.len() > MAX_SESSION_IMAGES {
        return Err(FfiError::ConfigError {
            detail: format!(
                "too many images ({}, max {MAX_SESSION_IMAGES})",
                image_data.len()
            ),
        });
    }

    // Capture raw user text for classification before multimodal markers are added.
    let raw_message_text = message.clone();
    // Compose the final message text, embedding image markers if present.
    let message = compose_multimodal_message(&message, &image_data, &mime_types);

    if message.len() > MAX_MESSAGE_BYTES {
        return Err(FfiError::ConfigError {
            detail: format!(
                "message too large ({} bytes, max {MAX_MESSAGE_BYTES})",
                message.len()
            ),
        });
    }

    // ── ClawBoy trigger intercept ────────────────────────────────
    // Check if this message triggers a ClawBoy start/stop BEFORE
    // entering the agent loop. If matched, short-circuit with the
    // trigger response — the agent never sees the message.
    {
        let trigger_result = crate::clawboy::chat::check_trigger(
            &message, "cli", // Terminal REPL is always the "cli" channel
        );
        match trigger_result {
            crate::clawboy::chat::TriggerResult::StartResponse(response)
            | crate::clawboy::chat::TriggerResult::StopResponse(response) => {
                tracing::info!(
                    target: "clawboy::chat",
                    channel = "cli",
                    "trigger matched in session_send — short-circuiting"
                );
                listener.on_response_chunk(response.clone());
                listener.on_complete(response);
                return Ok(());
            }
            crate::clawboy::chat::TriggerResult::PassThrough => {
                // Not a trigger — continue to normal agent processing.
            }
        }
    }

    tracing::info!(
        len = message.len(),
        images = image_data.len(),
        "session_send: start"
    );

    let cancel_token = CancellationToken::new();
    {
        let mut ct_guard = lock_cancel_token();
        *ct_guard = Some(cancel_token.clone());
    }

    // Snapshot session state while holding the lock briefly.
    // Wrap in a SessionStateGuard so that a panic during processing
    // automatically restores history + tools via Drop.
    let (mut state_guard, config, model, temperature, provider_name, system_prompt) = {
        let mut guard = lock_session();
        let session = guard.as_mut().ok_or_else(|| FfiError::StateError {
            detail: "no active session; call session_start first".into(),
        })?;
        (
            SessionStateGuard::new(
                std::mem::take(&mut session.history),
                std::mem::take(&mut session.tools_registry),
            ),
            session.config.clone(),
            session.model.clone(),
            session.temperature,
            session.provider_name.clone(),
            session.system_prompt.clone(),
        )
    };

    let (history, tools) = state_guard.state_mut()?;
    let history_len_before = history.len();
    let handle = crate::runtime::get_or_create_runtime()?;

    // Clone the memory backend *before* entering block_on to avoid holding
    // the DAEMON mutex inside the async block, which could deadlock with a
    // concurrent stop_daemon call.
    let daemon_memory = clone_daemon_memory().ok();

    let result: Result<String, AgentLoopOutcome> = handle.block_on(async {
        // Build memory context (best-effort; skip if memory unavailable).
        let mem_context = match daemon_memory {
            Some(ref mem) => {
                listener.on_progress(FfiProgressPhase::SearchingMemory);
                build_memory_context(mem.as_ref(), &message).await
            }
            None => String::new(),
        };

        // Enrich the user message with memory context and timestamp.
        let timestamp = chrono::Utc::now().format("%Y-%m-%d %H:%M UTC");
        let enriched = if mem_context.is_empty() {
            format!("[{timestamp}] {message}")
        } else {
            format!("{mem_context}[{timestamp}] {message}")
        };

        history.push(ChatMessage::user(enriched));

        // Create provider.
        let provider_runtime_options = zeroclaw::providers::ProviderRuntimeOptions {
            auth_profile_override: None,
            provider_api_url: config.api_url.clone(),
            zeroclaw_dir: config.config_path.parent().map(std::path::PathBuf::from),
            secrets_encrypt: config.secrets.encrypt,
            reasoning_enabled: config.runtime.reasoning_enabled,
            reasoning_effort: config.runtime.reasoning_effort,
            custom_headers: config
                .model_providers
                .get(&provider_name)
                .and_then(|p| p.custom_headers.clone()),
        };

        // Classify the raw user text (not the multimodal-composed version)
        // and adjust provider selection based on routing config.
        // Capture the full tier tail as cascade fallback overrides.
        let (effective_provider_name, routing_fallbacks) = {
            let hint = zeroclaw::router::classify(&raw_message_text);
            let preferred = hint.preferred_providers(&config.routing);
            if preferred.is_empty() {
                (provider_name.clone(), None)
            } else {
                (preferred[0].clone(), Some(preferred[1..].to_vec()))
            }
        };

        let provider = zeroclaw::providers::create_resilient_provider_with_options(
            &effective_provider_name,
            config.api_key.as_deref(),
            config.api_url.as_deref(),
            &config.reliability,
            routing_fallbacks.as_deref(),
            &provider_runtime_options,
        )
        .map_err(|e| AgentLoopOutcome::Error(format!("failed to create provider: {e}")))?;

        // Build tool specs from the real tools registry plus static
        // descriptions for tools the LLM should know about.
        let mut tool_specs = tool_specs_from_registry(tools);
        for spec in build_android_tool_specs(&config) {
            if !tool_specs.iter().any(|s| s.name == spec.name) {
                tool_specs.push(spec);
            }
        }

        // Run the agent loop with real tool execution.
        run_agent_loop(
            provider.as_ref(),
            history,
            tools,
            &tool_specs,
            &model,
            temperature,
            &cancel_token,
            &listener,
        )
        .await
    });

    // Consume the guard (disarms Drop) and put state back explicitly.
    // If we reach this point, no panic occurred, so we handle all
    // three outcomes and restore state ourselves.
    let (mut history, tools) = state_guard.take()?;

    match result {
        Ok(full_response) => {
            tracing::info!(len = full_response.len(), "session_send: success");
            // Run compaction on the history (best-effort).
            if let Ok(true) = handle.block_on(async {
                let provider =
                    zeroclaw::providers::create_provider(&provider_name, config.api_key.as_deref())
                        .ok();
                if let Some(provider) = provider {
                    auto_compact_history(
                        &mut history,
                        provider.as_ref(),
                        &model,
                        DEFAULT_MAX_HISTORY_MESSAGES,
                    )
                    .await
                } else {
                    Ok(false)
                }
            }) {
                // Find the compaction summary (most recent assistant message
                // that starts with "[Compaction summary]").
                if let Some(summary_msg) = history.iter().rev().find(|m| {
                    m.role == "assistant" && m.content.starts_with("[Compaction summary]")
                }) {
                    listener.on_progress(FfiProgressPhase::Compacting);
                    listener.on_compaction(summary_msg.content.clone());
                }
            }

            put_session_state_back(history, tools, &system_prompt);
            clear_cancel_token();
            listener.on_progress(FfiProgressPhase::Idle);
            listener.on_complete(full_response);
            Ok(())
        }
        Err(AgentLoopOutcome::Cancelled) => {
            tracing::info!("session_send: cancelled");
            put_session_state_back(history, tools, &system_prompt);
            clear_cancel_token();
            listener.on_progress(FfiProgressPhase::Idle);
            listener.on_cancelled();
            Ok(())
        }
        Err(AgentLoopOutcome::Error(msg)) => {
            tracing::error!(error = %msg, "session_send: agent loop error");
            // Rollback history to pre-send state.
            history.truncate(history_len_before);
            put_session_state_back(history, tools, &system_prompt);
            clear_cancel_token();
            listener.on_progress(FfiProgressPhase::Idle);
            listener.on_error(msg.clone());
            Err(FfiError::SpawnError { detail: msg })
        }
    }
}

/// Injects seed messages into the active session's conversation history.
///
/// Used to restore prior context (e.g. from Room persistence) before the
/// first [`session_send_inner`] call. Messages are appended after the
/// system prompt in the order provided.
///
/// At most [`MAX_SEED_MESSAGES`] entries are accepted. The `role` field of
/// each [`SessionMessage`] must be `"user"` or `"assistant"`; system
/// messages are silently skipped to prevent system prompt corruption.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if no session is active, or
/// [`FfiError::StateCorrupted`] if the session mutex is poisoned.
pub(crate) fn session_seed_inner(messages: Vec<SessionMessage>) -> Result<(), FfiError> {
    let mut guard = lock_session();
    let session = guard.as_mut().ok_or_else(|| FfiError::StateError {
        detail: "no active session; call session_start first".into(),
    })?;

    let capped = if messages.len() > MAX_SEED_MESSAGES {
        tracing::warn!(
            count = messages.len(),
            max = MAX_SEED_MESSAGES,
            "Seed messages capped"
        );
        &messages[..MAX_SEED_MESSAGES]
    } else {
        &messages
    };

    for msg in capped {
        match msg.role.as_str() {
            "user" => session.history.push(ChatMessage::user(&msg.content)),
            "assistant" => session.history.push(ChatMessage::assistant(&msg.content)),
            "tool" => session.history.push(ChatMessage::tool(&msg.content)),
            _ => {
                // Skip system messages to protect the system prompt.
                tracing::debug!(role = %msg.role, "Skipping seed message with reserved role");
            }
        }
    }

    tracing::info!(count = capped.len(), "Seeded session history");
    Ok(())
}

/// Cancels the currently running [`session_send_inner`] call.
///
/// Sets the [`CANCEL_TOKEN`] to cancelled state. The agent loop checks
/// this token between iterations and tool executions, aborting with an
/// [`AgentLoopOutcome::Cancelled`] result. If no send is in progress,
/// this is a no-op.
pub(crate) fn session_cancel_inner() {
    let guard = lock_cancel_token();
    if let Some(token) = guard.as_ref() {
        token.cancel();
        tracing::info!("Session send cancelled");
    }
}

/// Clears the active session's conversation history, retaining only the
/// system prompt.
///
/// After this call the session behaves as if freshly started -- the
/// system prompt is preserved but all user/assistant/tool messages are
/// discarded.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if no session is active, or
/// [`FfiError::StateCorrupted`] if the session mutex is poisoned.
pub(crate) fn session_clear_inner() -> Result<(), FfiError> {
    let mut guard = lock_session();
    let session = guard.as_mut().ok_or_else(|| FfiError::StateError {
        detail: "no active session; call session_start first".into(),
    })?;

    let system_prompt = session.system_prompt.clone();
    session.history = vec![ChatMessage::system(&system_prompt)];

    tracing::info!("Session history cleared");
    Ok(())
}

/// Returns the current conversation history as a list of [`SessionMessage`]
/// records suitable for transfer across the FFI boundary.
///
/// The returned list includes the system prompt (role `"system"`) as the
/// first entry, followed by user, assistant, and tool messages in
/// chronological order.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if no session is active, or
/// [`FfiError::StateCorrupted`] if the session mutex is poisoned.
pub(crate) fn session_history_inner() -> Result<Vec<SessionMessage>, FfiError> {
    let guard = lock_session();
    let session = guard.as_ref().ok_or_else(|| FfiError::StateError {
        detail: "no active session; call session_start first".into(),
    })?;

    let messages = session
        .history
        .iter()
        .map(|m| SessionMessage {
            role: m.role.clone(),
            content: m.content.clone(),
        })
        .collect();

    Ok(messages)
}

/// Destroys the active session and releases all associated resources.
///
/// After this call, a new session may be created with
/// [`session_start_inner`]. Any in-flight [`session_send_inner`] call is
/// cancelled first via the [`CANCEL_TOKEN`].
///
/// # Thread Safety
///
/// The cancel and destroy operations are not atomic: the cancel token
/// is in a separate mutex from the session state. This is safe because
/// the Kotlin layer serialises all session lifecycle calls on a single
/// `Dispatchers.IO` coroutine (no concurrent callers).
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if no session is active.
pub(crate) fn session_destroy_inner() -> Result<(), FfiError> {
    // Cancel any in-flight send (separate mutex, no deadlock).
    session_cancel_inner();

    let mut guard = lock_session();
    if guard.take().is_none() {
        return Err(FfiError::StateError {
            detail: "no active session to destroy".into(),
        });
    }

    tracing::info!("Live agent session destroyed");
    Ok(())
}

// ── Agent loop ──────────────────────────────────────────────────────────

/// Outcome categories for the agent loop, used internally to distinguish
/// success, cancellation, and errors without mixing them into `FfiError`.
enum AgentLoopOutcome {
    /// The send was cancelled via [`CANCEL_TOKEN`].
    Cancelled,
    /// An unrecoverable error occurred during the loop.
    Error(String),
}

/// Runs the agent tool-call loop until the LLM produces a final text
/// response, the maximum iteration count is reached, or cancellation
/// is signalled.
///
/// For each iteration:
/// 1. Check the cancellation token.
/// 2. Fire `on_thinking` / `on_progress` via the listener.
/// 3. Call `provider.chat(...)` with the current history and tool specs.
/// 4. If no tool calls: stream the final response, append to history, return.
/// 5. If tool calls: execute tools that exist in the registry and report
///    results; tools not in the registry get a fallback "unavailable" message.
///
/// Tools with real implementations (memory, cron, web search) are executed
/// directly. Tools that require upstream's `pub(crate)` `SecurityPolicy`
/// (shell, file I/O, git, browser) are not in the registry and receive
/// an unavailability response so the LLM can answer without them.
///
/// The function returns the full response text on success, or an
/// [`AgentLoopOutcome`] on failure/cancellation.
#[allow(clippy::too_many_arguments, clippy::too_many_lines)]
async fn run_agent_loop(
    provider: &dyn Provider,
    history: &mut Vec<ChatMessage>,
    tools: &[Box<dyn Tool>],
    tool_specs: &[ToolSpec],
    model: &str,
    temperature: f64,
    cancel_token: &CancellationToken,
    listener: &Arc<dyn FfiSessionListener>,
) -> Result<String, AgentLoopOutcome> {
    let use_native_tools = provider.supports_native_tools() && !tool_specs.is_empty();
    let request_tools = if use_native_tools {
        Some(tool_specs)
    } else {
        None
    };

    tracing::info!(
        native_tools = use_native_tools,
        specs = tool_specs.len(),
        "agent_loop: start"
    );

    for iteration in 0..DEFAULT_MAX_TOOL_ITERATIONS {
        // Check cancellation before each iteration.
        if cancel_token.is_cancelled() {
            return Err(AgentLoopOutcome::Cancelled);
        }

        // Progress: calling provider.
        #[allow(clippy::cast_possible_truncation)] // iteration ≤ DEFAULT_MAX_TOOL_ITERATIONS (10)
        listener.on_progress(FfiProgressPhase::CallingProvider {
            round: (iteration + 1) as u32,
        });
        tracing::info!(
            iteration = iteration + 1,
            model,
            "agent_loop: calling provider"
        );

        // Call the provider.
        let llm_start_time = std::time::Instant::now();
        let chat_future = provider.chat(
            ChatRequest {
                messages: history,
                tools: request_tools,
            },
            model,
            temperature,
        );

        let chat_result = tokio::select! {
            () = cancel_token.cancelled() => return Err(AgentLoopOutcome::Cancelled),
            result = chat_future => result,
        };

        let mut response = chat_result.map_err(|e| {
            tracing::error!(error = %e, "agent_loop: provider chat failed");
            AgentLoopOutcome::Error(format!("provider chat failed: {e}"))
        })?;

        tracing::info!(
            tool_calls = response.tool_calls.len(),
            text_len = response.text_or_empty().len(),
            has_reasoning = response.reasoning_content.is_some(),
            elapsed_secs = llm_start_time.elapsed().as_secs(),
            "agent_loop: provider responded"
        );

        // No tool calls -- check for prompt-guided XML tool calls before
        // treating as a final response.
        if response.tool_calls.is_empty() {
            let raw_text = response.text_or_empty().to_string();

            // Forward API-level reasoning_content (o1, o3) to the
            // thinking card.
            if let Some(ref reasoning) = response.reasoning_content
                && !reasoning.is_empty()
            {
                listener.on_thinking(reasoning.clone());
            }

            // Extract inline thinking tags (DeepSeek-R1, Qwen, etc.)
            // from the text field and route them to the thinking card
            // so they don't appear as the visible response.
            let (clean_text, inline_thinking) = extract_thinking_from_text(&raw_text);
            if !inline_thinking.is_empty() {
                tracing::info!(
                    thinking_len = inline_thinking.len(),
                    "agent_loop: extracted inline thinking"
                );
                listener.on_thinking(inline_thinking);
            }

            // Parse <tool_call> XML tags from the response text as a
            // fallback. This is always attempted when the provider returned
            // no native tool_calls, even if native tools were offered in
            // the request. Some models (Qwen, GLM distills, small Ollama
            // models) receive the native tool schema but still emit tool
            // invocations as XML text instead of structured tool_calls.
            // Without this fallback those models spiral — they see tools
            // in the prompt, fail to invoke them natively, and retry the
            // same text-based attempt every turn.
            {
                let (text_without_calls, mut xml_calls) = parse_xml_tool_calls(&clean_text);
                if !xml_calls.is_empty() {
                    if use_native_tools {
                        tracing::warn!(
                            count = xml_calls.len(),
                            "agent_loop: model emitted XML tool calls despite native tools; \
                             falling back to prompt-guided parsing"
                        );
                    }

                    // Filter out tool names not in the registry to prevent
                    // prompt-injection from invoking arbitrary tool names.
                    let before = xml_calls.len();
                    xml_calls.retain(|c| tools.iter().any(|t| t.name() == c.name));
                    if xml_calls.len() < before {
                        tracing::warn!(
                            dropped = before - xml_calls.len(),
                            "agent_loop: filtered unrecognised XML tool calls"
                        );
                    }
                    tracing::info!(
                        count = xml_calls.len(),
                        "agent_loop: parsed prompt-guided <tool_call> tags"
                    );

                    // Promote the parsed XML calls into the response so
                    // the existing tool-dispatch logic handles them.
                    response.tool_calls = xml_calls;

                    // Replace text with the cleaned version (tags removed)
                    // so the assistant history doesn't contain raw XML.
                    response.text = Some(text_without_calls);

                    // Fall through to the tool-call execution block below.
                }
            }

            // If still no tool calls after XML parsing, this is truly
            // the final response.
            if response.tool_calls.is_empty() {
                tracing::info!(
                    raw_len = raw_text.len(),
                    clean_len = clean_text.len(),
                    "agent_loop: final response"
                );

                // Signal that we are now streaming the final response.
                listener.on_progress(FfiProgressPhase::StreamingResponse);
                listener.on_progress_clear();

                // Stream the cleaned response (thinking blocks removed).
                stream_response_text(&clean_text, listener, cancel_token)?;

                // Keep the full raw text in history so the model retains
                // its own reasoning context for follow-up turns.
                history.push(ChatMessage::assistant(&raw_text));
                return Ok(clean_text);
            }
        }

        // Has tool calls -- execute those we have and report unavailable
        // for the rest.
        //
        // Forward API-level reasoning_content and inline thinking tags
        // to the thinking card.
        if let Some(ref reasoning) = response.reasoning_content
            && !reasoning.is_empty()
        {
            listener.on_thinking(reasoning.clone());
        }

        let raw_assistant = response.text_or_empty().to_string();
        let (_clean_assistant, inline_thinking) = extract_thinking_from_text(&raw_assistant);
        if !inline_thinking.is_empty() {
            listener.on_thinking(inline_thinking);
        }

        // Cap excessive tool calls from a single response.
        if response.tool_calls.len() > MAX_TOOL_CALLS_PER_RESPONSE {
            tracing::warn!(
                total = response.tool_calls.len(),
                limit = MAX_TOOL_CALLS_PER_RESPONSE,
                "agent_loop: capping tool calls per response"
            );
            response.tool_calls.truncate(MAX_TOOL_CALLS_PER_RESPONSE);
        }

        let tool_call_count = response.tool_calls.len();
        listener.on_progress(FfiProgressPhase::GotToolCalls {
            count: u32::try_from(tool_call_count).unwrap_or(u32::MAX),
            llm_duration_secs: llm_start_time.elapsed().as_secs(),
        });

        // Push assistant message with tool calls context.
        // Keep raw text (with thinking tags) so the model retains its
        // reasoning context for subsequent iterations.
        let assistant_text = raw_assistant;
        if use_native_tools {
            let native_history = build_native_assistant_history(
                &assistant_text,
                &response.tool_calls,
                response.reasoning_content.as_deref(),
            );
            history.push(ChatMessage::assistant(native_history));
        } else {
            history.push(ChatMessage::assistant(&assistant_text));
        }

        // Execute or respond to each tool call.
        let mut tool_results_text = String::new();

        for call in &response.tool_calls {
            if cancel_token.is_cancelled() {
                return Err(AgentLoopOutcome::Cancelled);
            }

            let args_hint = truncate_tool_args_hint(&call.name, &call.arguments);
            listener.on_tool_start(call.name.clone(), args_hint);
            tracing::info!(tool = %call.name, "agent_loop: tool start");

            let start_time = std::time::Instant::now();

            // Find the tool by name in the registry.
            let tool = tools.iter().find(|t| t.name() == call.name);

            let (success, output) = if let Some(tool) = tool {
                let args: serde_json::Value =
                    serde_json::from_str(&call.arguments).unwrap_or(serde_json::json!({}));

                let exec_result = tokio::select! {
                    () = cancel_token.cancelled() => {
                        return Err(AgentLoopOutcome::Cancelled);
                    }
                    result = tool.execute(args) => result,
                };

                match exec_result {
                    Ok(result) => {
                        if result.success {
                            (true, result.output)
                        } else {
                            (
                                false,
                                result
                                    .error
                                    .unwrap_or_else(|| "Tool failed without error message".into()),
                            )
                        }
                    }
                    Err(e) => (false, format!("Tool execution error: {e}")),
                }
            } else {
                tracing::warn!(tool = %call.name, "agent_loop: tool not found in registry");
                (
                    false,
                    format!(
                        "Tool '{}' is not available in this session. \
                         Please answer directly without this tool.",
                        call.name
                    ),
                )
            };

            let duration_secs = start_time.elapsed().as_secs();
            let output_preview: String = output.chars().take(200).collect();
            tracing::info!(
                tool = %call.name,
                success,
                duration_secs,
                output_len = output.len(),
                output_preview,
                "agent_loop: tool done"
            );
            listener.on_tool_result(call.name.clone(), success, duration_secs);
            listener.on_tool_output(call.name.clone(), output.clone());

            if use_native_tools {
                let tool_msg = serde_json::json!({
                    "tool_call_id": call.id,
                    "content": output,
                });
                history.push(ChatMessage::tool(tool_msg.to_string()));
            } else {
                let _ = writeln!(
                    tool_results_text,
                    "<tool_result name=\"{}\">\n{output}\n</tool_result>",
                    call.name
                );
            }
        }

        // For prompt-guided mode, append collected tool results as a user message.
        if !use_native_tools && !tool_results_text.is_empty() {
            history.push(ChatMessage::user(format!(
                "[Tool results]\n{tool_results_text}"
            )));
        }
    }

    Err(AgentLoopOutcome::Error(format!(
        "Agent exceeded maximum tool iterations ({DEFAULT_MAX_TOOL_ITERATIONS})"
    )))
}

// ── Multimodal message composition ──────────────────────────────────────

/// Composes a user message with embedded `[IMAGE:...]` markers.
///
/// When `image_data` is empty the original `text` is returned unchanged.
/// Otherwise each base64-encoded image is appended as an `[IMAGE:data:
/// <mime>;base64,<payload>]` marker. The upstream provider's
/// `to_message_content` parser (in `compatible.rs`) recognises these
/// markers and converts them to multimodal content parts.
fn compose_multimodal_message(text: &str, image_data: &[String], mime_types: &[String]) -> String {
    if image_data.is_empty() {
        return text.to_string();
    }

    let mut buf =
        String::with_capacity(text.len() + image_data.iter().map(String::len).sum::<usize>() + 256);
    buf.push_str(text);

    for (data, mime) in image_data.iter().zip(mime_types.iter()) {
        buf.push_str("\n\n[IMAGE:data:");
        buf.push_str(mime);
        buf.push_str(";base64,");
        buf.push_str(data);
        buf.push(']');
    }

    buf
}

// ── Android identity extras ─────────────────────────────────────────────

/// Appends Android-specific identity fields to the system prompt.
///
/// The upstream AIEOS renderer only outputs agent identity (name, bio,
/// personality). Android onboarding also stores `user_name`, `timezone`,
/// and `communication_style` inside the `identity` JSON object. These
/// fields are silently dropped by serde because they don't exist in the
/// upstream `IdentitySection` struct.
///
/// This function parses the raw `aieos_inline` JSON, extracts those
/// extra fields, and appends a "## User Context" section to the prompt.
fn append_android_identity_extras(
    prompt: &mut String,
    identity_config: &zeroclaw::config::IdentityConfig,
) {
    use std::fmt::Write;

    let Some(ref inline) = identity_config.aieos_inline else {
        return;
    };

    let Ok(root) = serde_json::from_str::<serde_json::Value>(inline) else {
        return;
    };

    let identity_obj = match root.get("identity") {
        Some(v) => v,
        None => &root,
    };

    let user_name = identity_obj
        .get("user_name")
        .and_then(|v| v.as_str())
        .unwrap_or("");
    let timezone = identity_obj
        .get("timezone")
        .and_then(|v| v.as_str())
        .unwrap_or("");
    let comm_style = identity_obj
        .get("communication_style")
        .and_then(|v| v.as_str())
        .unwrap_or("");

    if user_name.is_empty() && timezone.is_empty() && comm_style.is_empty() {
        return;
    }

    prompt.push_str("\n## User Context\n\n");
    if !user_name.is_empty() {
        let _ = writeln!(prompt, "**User's name:** {user_name}");
    }
    if !timezone.is_empty() {
        let _ = writeln!(prompt, "**Timezone:** {timezone}");
    }
    if !comm_style.is_empty() {
        let _ = writeln!(prompt, "**Preferred communication style:** {comm_style}");
    }
}

// ── Memory context ──────────────────────────────────────────────────────

/// Queries the memory backend for entries relevant to the user message
/// and formats them as a context preamble string.
///
/// Mirrors upstream `build_context()` but simplified for the FFI session.
/// Entries whose key matches the assistant autosave pattern are skipped
/// to avoid injecting raw LLM output back as context.
///
/// Returns an empty string if no relevant memories are found or the
/// memory query fails.
async fn build_memory_context(mem: &dyn Memory, query: &str) -> String {
    let Ok(entries) = mem.recall(query, 5, None).await else {
        return String::new();
    };

    // Filter out autosave entries and low-relevance results.
    let relevant: Vec<_> = entries
        .iter()
        .filter(|e| !zeroclaw::memory::is_assistant_autosave_key(&e.key))
        .filter(|e| match e.score {
            Some(score) => score >= 0.3,
            None => true,
        })
        .collect();

    if relevant.is_empty() {
        return String::new();
    }

    let mut context = String::from("[Memory context]\n");
    for entry in &relevant {
        let _ = writeln!(context, "- {}: {}", entry.key, entry.content);
    }
    context.push('\n');

    context
}

// ── Compaction ───────────────────────────────────────────────────────────

/// Automatically compacts conversation history when it exceeds the
/// `max_history` threshold.
///
/// Mirrors upstream `auto_compact_history()`:
/// 1. Counts non-system messages.
/// 2. If count exceeds `max_history`, takes the oldest messages
///    (keeping [`COMPACTION_KEEP_RECENT`] recent ones).
/// 3. Builds a transcript of the compactable messages.
/// 4. Asks the provider to summarise the transcript.
/// 5. Replaces the compacted messages with a single
///    `[Compaction summary]` assistant message.
///
/// Returns `true` if compaction occurred, `false` if history was within
/// limits.
async fn auto_compact_history(
    history: &mut Vec<ChatMessage>,
    provider: &dyn Provider,
    model: &str,
    max_history: usize,
) -> Result<bool, AgentLoopOutcome> {
    let has_system = history.first().is_some_and(|m| m.role == "system");
    let non_system_count = if has_system {
        history.len().saturating_sub(1)
    } else {
        history.len()
    };

    if non_system_count <= max_history {
        return Ok(false);
    }

    let start = usize::from(has_system);
    let keep_recent = COMPACTION_KEEP_RECENT.min(non_system_count);
    let compact_count = non_system_count.saturating_sub(keep_recent);
    if compact_count == 0 {
        return Ok(false);
    }

    let compact_end = start + compact_count;
    let to_compact: Vec<ChatMessage> = history[start..compact_end].to_vec();
    let transcript = build_compaction_transcript(&to_compact);

    let summariser_system = "You are a conversation compaction engine. Summarize older chat \
        history into concise context for future turns. Preserve: user preferences, commitments, \
        decisions, unresolved tasks, key facts. Omit: filler, repeated chit-chat, verbose tool \
        logs. Output plain text bullet points only.";

    let summariser_user = format!(
        "Summarize the following conversation history for context preservation. \
         Keep it short (max 12 bullet points).\n\n{transcript}"
    );

    let summary_raw = provider
        .chat_with_system(Some(summariser_system), &summariser_user, model, 0.2)
        .await
        .unwrap_or_else(|_| {
            // Fallback to deterministic local truncation.
            truncate_chars(&transcript, COMPACTION_MAX_SUMMARY_CHARS)
        });

    let summary = truncate_chars(&summary_raw, COMPACTION_MAX_SUMMARY_CHARS);

    let summary_msg = ChatMessage::assistant(format!("[Compaction summary]\n{}", summary.trim()));
    history.splice(start..compact_end, std::iter::once(summary_msg));

    Ok(true)
}

/// Trims conversation history to prevent unbounded growth.
///
/// Preserves the system prompt (first message if role=system) and the most
/// recent `max_history` non-system messages, draining the oldest entries.
#[allow(dead_code)] // Reserved for future integration into session_send flow.
fn trim_history(history: &mut Vec<ChatMessage>, max_history: usize) {
    let has_system = history.first().is_some_and(|m| m.role == "system");
    let non_system_count = if has_system {
        history.len().saturating_sub(1)
    } else {
        history.len()
    };

    if non_system_count <= max_history {
        return;
    }

    let start = usize::from(has_system);
    let to_remove = non_system_count - max_history;
    history.drain(start..start + to_remove);
}

/// Builds a transcript of messages for the compaction summariser.
///
/// Each message is formatted as `"ROLE: content"` on its own line.
/// The output is capped at [`COMPACTION_MAX_SOURCE_CHARS`] characters.
fn build_compaction_transcript(messages: &[ChatMessage]) -> String {
    let mut transcript = String::new();
    for msg in messages {
        let role = msg.role.to_uppercase();
        let _ = writeln!(transcript, "{role}: {}", msg.content.trim());
    }

    if transcript.chars().count() > COMPACTION_MAX_SOURCE_CHARS {
        truncate_chars(&transcript, COMPACTION_MAX_SOURCE_CHARS)
    } else {
        transcript
    }
}

// ── Helpers ─────────────────────────────────────────────────────────────

/// Truncates a string to `max_chars` characters, appending `"..."` if truncated.
fn truncate_chars(s: &str, max_chars: usize) -> String {
    match s.char_indices().nth(max_chars) {
        Some((idx, _)) => {
            let truncated = &s[..idx];
            format!("{}...", truncated.trim_end())
        }
        None => s.to_string(),
    }
}

/// Builds tool specifications for the Android-appropriate tool set.
///
/// These specs are passed to `provider.chat()` so the LLM is aware of
/// available tools. Because upstream's `SecurityPolicy` is `pub(crate)`,
/// we cannot instantiate actual tool objects; these specs serve only as
/// metadata for the provider's native tool calling protocol.
fn build_android_tool_specs(config: &zeroclaw::Config) -> Vec<ToolSpec> {
    let descs = build_android_tool_descs(config);
    descs
        .into_iter()
        .map(|(name, description)| ToolSpec {
            name,
            description,
            parameters: serde_json::json!({
                "type": "object",
                "properties": {},
            }),
        })
        .collect()
}

/// Builds a `## Tool Use Protocol` section for the system prompt.
///
/// When the provider does not support native tool calling (e.g. OpenAI Codex),
/// the model needs explicit instructions on how to emit tool calls using
/// `<tool_call>` XML tags. This mirrors the upstream
/// `build_tool_instructions_from_specs()` in `agent/loop_.rs` but works with
/// the FFI session's tool registry and static tool descriptions.
///
/// The output includes:
/// - Format instructions with concrete examples
/// - A list of available tools with their parameter schemas
fn build_tool_use_protocol(tools_registry: &[Box<dyn Tool>], config: &zeroclaw::Config) -> String {
    use std::fmt::Write;

    let mut out = String::with_capacity(2048);
    out.push_str("\n## Tool Use Protocol\n\n");
    out.push_str("To use a tool, wrap a JSON object in <tool_call></tool_call> tags:\n\n");
    out.push_str(
        "```\n<tool_call>\n\
         {\"name\": \"tool_name\", \"arguments\": {\"param\": \"value\"}}\n\
         </tool_call>\n```\n\n",
    );
    out.push_str(
        "CRITICAL: Output actual <tool_call> tags\u{2014}\
         never describe steps or give examples.\n\n",
    );
    out.push_str(
        "When a tool is needed, emit a real call (not prose), for example:\n\
         <tool_call>\n\
         {\"name\":\"tool_name\",\"arguments\":{}}\n\
         </tool_call>\n\n",
    );
    out.push_str("You may use multiple tool calls in a single response. ");
    out.push_str("After tool execution, results appear in <tool_result> tags. ");
    out.push_str("Continue reasoning with the results until you can give a final answer.\n\n");
    out.push_str("### Available Tools\n\n");

    // First, list tools from the live registry (these have real parameter schemas).
    for tool in tools_registry {
        let spec = tool.spec();
        let _ = writeln!(
            out,
            "**{}**: {}\nParameters: `{}`\n",
            spec.name, spec.description, spec.parameters
        );
    }

    // Then, list static tool descriptions for tools not in the registry
    // (web_search, web_fetch, http_request — executed by daemon, not FFI).
    let registry_names: Vec<&str> = tools_registry.iter().map(|t| t.name()).collect();
    for (name, desc) in build_android_tool_descs(config) {
        if !registry_names.contains(&name.as_str()) {
            let params = match name.as_str() {
                "web_search" => serde_json::json!({
                    "type": "object",
                    "properties": {
                        "query": {
                            "type": "string",
                            "description": "The search query"
                        }
                    },
                    "required": ["query"]
                }),
                "web_fetch" => serde_json::json!({
                    "type": "object",
                    "properties": {
                        "url": {
                            "type": "string",
                            "description": "The URL to fetch"
                        }
                    },
                    "required": ["url"]
                }),
                "http_request" => serde_json::json!({
                    "type": "object",
                    "properties": {
                        "method": {
                            "type": "string",
                            "description": "HTTP method (GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS)"
                        },
                        "url": {
                            "type": "string",
                            "description": "The URL to request"
                        },
                        "headers": {
                            "type": "object",
                            "description": "Optional HTTP headers"
                        },
                        "body": {
                            "type": "string",
                            "description": "Optional request body"
                        }
                    },
                    "required": ["method", "url"]
                }),
                _ => serde_json::json!({
                    "type": "object",
                    "properties": {}
                }),
            };
            let _ = writeln!(out, "**{name}**: {desc}\nParameters: `{params}`\n");
        }
    }

    out
}

/// Extracts a short hint from tool call arguments for the progress display.
///
/// For `shell` tools, shows the `command` field. For file tools, shows the
/// `path` field. For other tools, shows the `action` or `query` field.
fn truncate_tool_args_hint(tool_name: &str, arguments_json: &str) -> String {
    let args: serde_json::Value =
        serde_json::from_str(arguments_json).unwrap_or(serde_json::json!({}));

    let hint = match tool_name {
        "shell" => args.get("command").and_then(|v| v.as_str()),
        "file_read" | "file_write" => args.get("path").and_then(|v| v.as_str()),
        _ => args
            .get("action")
            .and_then(|v| v.as_str())
            .or_else(|| args.get("query").and_then(|v| v.as_str())),
    };

    match hint {
        Some(s) => truncate_chars(s, 60),
        None => String::new(),
    }
}

/// Tag names treated as thinking/reasoning blocks.
///
/// Content inside these tags is extracted from the response `text` field and
/// forwarded to the thinking card via [`FfiSessionListener::on_thinking`]
/// instead of being streamed as visible response text.
///
/// Different models use different tag conventions:
/// - DeepSeek-R1, Qwen: `<think>...</think>`
/// - Some fine-tuned models: `<thinking>...</thinking>`
/// - Claude artifacts: `<analysis>`, `<reflection>`, `<inner_monologue>`
const THINKING_TAG_NAMES: &[&str] = &[
    "think",
    "thinking",
    "analysis",
    "reflection",
    "inner_monologue",
    "reasoning",
];

/// Extracts thinking/reasoning blocks from model response text.
///
/// Scans `text` for matched pairs of tags listed in [`THINKING_TAG_NAMES`],
/// collects their inner content, and returns a tuple of
/// `(clean_text, thinking_content)`.  The clean text has the tag blocks
/// removed (with surrounding whitespace collapsed), ready for streaming to
/// the user.  The thinking content is the concatenation of all extracted
/// blocks, suitable for [`FfiSessionListener::on_thinking`].
///
/// Matching is case-insensitive.  Nested or overlapping tags of the same
/// kind are handled greedily (the outermost pair wins).
pub(crate) fn extract_thinking_from_text(text: &str) -> (String, String) {
    let mut clean = text.to_string();
    let mut thinking = String::new();

    for tag in THINKING_TAG_NAMES {
        loop {
            let lower = clean.to_lowercase();
            let open_tag = format!("<{tag}>");
            let close_tag = format!("</{tag}>");

            let Some(open_start) = lower.find(&open_tag) else {
                break;
            };
            let content_start = open_start + open_tag.len();
            let Some(close_start) = lower[content_start..].find(&close_tag) else {
                break;
            };
            let close_end = content_start + close_start + close_tag.len();

            let inner = &clean[content_start..content_start + close_start];
            let trimmed = inner.trim();
            if !trimmed.is_empty() {
                if !thinking.is_empty() {
                    thinking.push('\n');
                }
                thinking.push_str(trimmed);
            }

            clean.replace_range(open_start..close_end, "");
        }
    }

    let clean = clean.trim().to_string();
    (clean, thinking)
}

/// Parses `<tool_call>` XML tags from prompt-guided model responses.
///
/// When the provider does not support native tool calling (e.g. OpenAI Codex),
/// upstream injects a `## Tool Use Protocol` section into the system prompt
/// that instructs the model to emit tool calls as:
///
/// ```text
/// <tool_call>
/// {"name": "web_search", "arguments": {"query": "latest news"}}
/// </tool_call>
/// ```
///
/// However, upstream's `Provider::chat()` default implementation returns
/// `tool_calls: Vec::new()` for prompt-guided mode — it never parses the
/// XML tags from the response text. This function fills that gap.
///
/// Returns a tuple of `(clean_text, parsed_tool_calls)` where `clean_text`
/// has the `<tool_call>` blocks removed, and `parsed_tool_calls` contains
/// the extracted [`ToolCall`] structs ready for execution.
fn parse_xml_tool_calls(text: &str) -> (String, Vec<ToolCall>) {
    let mut calls = Vec::new();
    let mut clean = text.to_string();
    let mut counter = 0u32;

    loop {
        let lower = clean.to_lowercase();
        let Some(open_idx) = lower.find("<tool_call>") else {
            break;
        };
        let Some(close_idx) = lower[open_idx..].find("</tool_call>") else {
            break;
        };
        let close_abs = open_idx + close_idx;
        let inner_start = open_idx + "<tool_call>".len();
        let inner = clean[inner_start..close_abs].trim();

        if let Ok(parsed) = serde_json::from_str::<serde_json::Value>(inner) {
            let name = parsed
                .get("name")
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .to_string();
            let arguments = match parsed.get("arguments") {
                Some(v) => v.to_string(),
                None => "{}".to_string(),
            };

            if !name.is_empty() {
                counter += 1;
                calls.push(ToolCall {
                    id: format!("xmltc_{counter}"),
                    name,
                    arguments,
                });
            }
        }

        // Remove the full <tool_call>...</tool_call> block from clean text.
        let end = close_abs + "</tool_call>".len();
        clean.replace_range(open_idx..end, "");
    }

    if !calls.is_empty() {
        clean = clean.trim().to_string();
    }

    (clean, calls)
}

/// Streams the final response text to the listener in chunks of at least
/// [`STREAM_CHUNK_MIN_CHARS`] characters, split on whitespace boundaries.
fn stream_response_text(
    text: &str,
    listener: &Arc<dyn FfiSessionListener>,
    cancel_token: &CancellationToken,
) -> Result<(), AgentLoopOutcome> {
    let mut chunk = String::new();
    for word in text.split_inclusive(char::is_whitespace) {
        if cancel_token.is_cancelled() {
            return Err(AgentLoopOutcome::Cancelled);
        }
        chunk.push_str(word);
        if chunk.len() >= STREAM_CHUNK_MIN_CHARS {
            listener.on_response_chunk(std::mem::take(&mut chunk));
        }
    }
    if !chunk.is_empty() {
        listener.on_response_chunk(chunk);
    }
    Ok(())
}

/// Builds a JSON-structured assistant history entry for native tool calling mode.
///
/// Preserves tool call IDs so subsequent `role=tool` messages can reference
/// the correct call. Also preserves `reasoning_content` from thinking models.
fn build_native_assistant_history(
    text: &str,
    tool_calls: &[zeroclaw::providers::ToolCall],
    reasoning_content: Option<&str>,
) -> String {
    // Each tool call entry carries both the flat ToolCall fields (id, name,
    // arguments) for the Anthropic provider's `parse_assistant_tool_call_message`
    // and the nested `function` object for the OpenAI provider's
    // `convert_messages`. Both providers attempt deserialization as
    // `Vec<ToolCall>` (id/name/arguments), while OpenAI also reads the
    // `function` wrapper. Serde's default mode ignores unknown fields, so
    // the extra keys are harmless in both directions.
    let calls_json: Vec<serde_json::Value> = tool_calls
        .iter()
        .map(|tc| {
            serde_json::json!({
                "id": tc.id,
                "name": tc.name,
                "arguments": tc.arguments,
                "type": "function",
                "function": {
                    "name": tc.name,
                    "arguments": tc.arguments,
                },
            })
        })
        .collect();

    let mut msg = serde_json::json!({
        "content": text,
        "tool_calls": calls_json,
    });

    if let Some(rc) = reasoning_content {
        msg["reasoning_content"] = serde_json::Value::String(rc.to_string());
    }

    msg.to_string()
}

/// Puts the working history and tools registry back into the [`SESSION`] mutex.
///
/// If the session was destroyed while the send was in progress, the
/// state is silently dropped (the session slot will be `None`).
fn put_session_state_back(
    history: Vec<ChatMessage>,
    tools: Vec<Box<dyn Tool>>,
    _system_prompt: &str,
) {
    let mut guard = lock_session();
    if let Some(session) = guard.as_mut() {
        session.history = history;
        session.tools_registry = tools;
    }
}

/// Clears the global [`CANCEL_TOKEN`].
fn clear_cancel_token() {
    *lock_cancel_token() = None;
}

/// Builds the Android-appropriate tool description list for the system prompt.
///
/// Returns `(tool_name, description)` pairs matching the subset of tools
/// available in an Android agent session. This is a strict subset of the
/// tools available via daemon channel routing.
///
/// Session tools include: memory (store/recall/forget), cron (list/runs),
/// and optionally web_search, web_fetch, and http_request.
///
/// Shell and file I/O tools (`shell`, `file_read`, `file_write`) are NOT
/// included here — those tools are only available via daemon channel tools
/// and cannot be executed within a session context.
///
/// Hardware peripherals, composio, and screenshot tools are excluded because
/// they require desktop-only capabilities.
///
/// Conditional tools (`web_search`, `web_fetch`, `http_request`) are
/// included only when their corresponding config sections are enabled.
///
/// Only tools with a backing executor in [`build_tools_registry`] are
/// listed.  Phantom specs (name-only, no executor) cause wasted tool-call
/// iterations: the LLM invokes the tool, the dispatch returns "not
/// available", and the model must retry or answer without it.
fn build_android_tool_descs(config: &zeroclaw::Config) -> Vec<(String, String)> {
    let mut descs: Vec<(String, String)> = vec![
        (
            "memory_store".into(),
            "Save to memory. Use when: preserving durable preferences, \
             decisions, key context. Don't use when: information is \
             transient/noisy/sensitive without need."
                .into(),
        ),
        (
            "memory_recall".into(),
            "Search memory. Use when: retrieving prior decisions, user \
             preferences, historical context. Don't use when: answer \
             is already in current context."
                .into(),
        ),
        (
            "memory_forget".into(),
            "Delete a memory entry. Use when: memory is incorrect/stale \
             or explicitly requested for removal. Don't use when: \
             impact is uncertain."
                .into(),
        ),
        (
            "cron_list".into(),
            "List all cron jobs with schedule, status, and metadata.".into(),
        ),
        (
            "cron_runs".into(),
            "Show recent and upcoming cron job executions with timestamps \
             and exit status."
                .into(),
        ),
    ];

    // ── Web tools: three distinct tools with clear boundaries ────────
    //
    // web_search   → search engine queries (Brave / Google CSE)
    // web_fetch    → GET a known URL, return page text
    // http_request → full HTTP client (any method, custom headers/body)

    if config.web_search.enabled {
        descs.push((
            "web_search".into(),
            "Search the web for information. Returns result titles, URLs, \
             and snippets. Use when: finding current information, news, or \
             researching a topic. Do NOT use this to fetch a known URL \
             (use web_fetch)."
                .into(),
        ));
    }

    if config.web_fetch.enabled {
        descs.push((
            "web_fetch".into(),
            "Fetch a specific URL and return its content as clean text. \
             HTML pages are automatically converted to readable text. \
             GET requests only; follows redirects; domain-allowlisted. \
             Use when: you already have a URL and need its content. \
             Do NOT use this to search (use web_search). \
             Do NOT use this to call an API with custom headers \
             (use http_request)."
                .into(),
        ));
    }

    if config.http_request.enabled {
        descs.push((
            "http_request".into(),
            "Make HTTP requests with custom methods and headers. \
             Supports GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS. \
             Returns raw response including status and headers. \
             Use when: calling REST APIs, webhooks, or services that \
             require authentication headers or request bodies. \
             Do NOT use this for web browsing (use web_fetch) or \
             searching (use web_search)."
                .into(),
        ));
    }

    if config.twitter_browse.enabled
        && config
            .twitter_browse
            .cookie_string
            .as_ref()
            .is_some_and(|s| !s.is_empty())
    {
        descs.push((
            "twitter_browse".into(),
            "Browse X/Twitter content. Actions: get_profile (by username), \
             search_tweets (by query), search_profiles (by query), \
             read_tweet (by tweet_id), get_user_tweets (by username), \
             get_user_tweets_and_replies (by username). Read-only. \
             Use when: looking up tweets, profiles, or searching X/Twitter."
                .into(),
        ));
    }

    descs
}

// ── Delta string parser ─────────────────────────────────────────────────
//
// Upstream `ZeroClaw`'s `run_tool_call_loop()` emits progress as plain
// strings with emoji prefixes. The parser below converts these strings
// into typed [`FfiSessionListener`] callbacks.

/// Sentinel value emitted by upstream to signal the transition from
/// tool-call progress lines to streamed response tokens.
///
/// After this sentinel, all subsequent deltas are response content
/// until the loop iteration ends.
#[allow(dead_code)] // Used by dispatch_delta; reserved for streaming integration.
const DRAFT_CLEAR_SENTINEL: &str = "\x00CLEAR\x00";

/// Dispatches a single progress delta string to the appropriate listener callback.
///
/// The upstream agent loop emits deltas in two phases:
///
/// 1. **Progress phase** -- emoji-prefixed status lines describing thinking,
///    tool starts, tool completions, and other progress.
/// 2. **Response phase** -- raw text chunks of the assistant's streamed reply,
///    entered after [`DRAFT_CLEAR_SENTINEL`] is received.
///
/// `streaming_response` tracks which phase we are in and is mutated when
/// the sentinel is encountered.
#[allow(dead_code)] // Reserved for streaming delta integration.
pub(crate) fn dispatch_delta(
    delta: &str,
    listener: &dyn FfiSessionListener,
    streaming_response: &mut bool,
) {
    if delta == DRAFT_CLEAR_SENTINEL {
        *streaming_response = true;
        listener.on_progress_clear();
        return;
    }

    if *streaming_response {
        listener.on_response_chunk(delta.to_string());
        return;
    }

    let trimmed = delta.trim_end_matches('\n');
    if trimmed.is_empty() {
        return;
    }

    let mut chars = trimmed.chars();
    if let Some(first) = chars.next() {
        let rest = chars.as_str();
        match first {
            '\u{1f914}' => {
                // Thinking / planning
                listener.on_thinking(rest.trim().to_string());
            }
            '\u{23f3}' => {
                // Tool start -- format: "tool_name: hint text"
                let rest = rest.trim();
                let (name, hint) = match rest.find(':') {
                    Some(pos) => (rest[..pos].trim(), rest[pos + 1..].trim()),
                    None => (rest, ""),
                };
                listener.on_tool_start(name.to_string(), hint.to_string());
            }
            '\u{2705}' => {
                // Tool success -- format: "tool_name (3s)"
                let (name, secs) = parse_tool_completion(rest.trim());
                listener.on_tool_result(name, true, secs);
            }
            '\u{274c}' => {
                // Tool failure -- format: "tool_name (2s)"
                let (name, secs) = parse_tool_completion(rest.trim());
                listener.on_tool_result(name, false, secs);
            }
            '\u{1f4ac}' => {
                // Informational progress
                listener.on_progress(FfiProgressPhase::Raw {
                    message: rest.trim().to_string(),
                });
            }
            _ => {
                // Unrecognised prefix -- treat as generic progress
                listener.on_progress(FfiProgressPhase::Raw {
                    message: trimmed.to_string(),
                });
            }
        }
    }
}

/// Parses a tool completion string into `(tool_name, duration_seconds)`.
///
/// Expected format: `"tool_name (Ns)"` where `N` is an integer.
/// If no parenthesised duration is found, returns `(input, 0)`.
///
/// # Examples
///
/// ```text
/// "read_file (3s)" -> ("read_file", 3)
/// "read_file"      -> ("read_file", 0)
/// ```
#[allow(dead_code)] // Called by dispatch_delta; reserved for streaming integration.
fn parse_tool_completion(s: &str) -> (String, u64) {
    if let Some(paren_start) = s.rfind('(') {
        let name = s[..paren_start].trim();
        let inside = &s[paren_start + 1..];
        let secs = inside
            .trim_end_matches(')')
            .trim()
            .trim_end_matches('s')
            .trim()
            .parse::<u64>()
            .unwrap_or(0);
        (name.to_string(), secs)
    } else {
        (s.to_string(), 0)
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;
    use std::sync::Mutex as StdMutex;

    /// A test listener that records all callback invocations as strings.
    ///
    /// Each event is formatted as `"callback_name:payload"` and pushed
    /// onto the internal vector for later assertion.
    struct RecordingListener {
        /// Accumulated event strings.
        events: StdMutex<Vec<String>>,
    }

    impl RecordingListener {
        /// Creates a new empty recording listener.
        fn new() -> Self {
            Self {
                events: StdMutex::new(Vec::new()),
            }
        }

        /// Returns a snapshot of all recorded events.
        fn events(&self) -> Vec<String> {
            self.events.lock().unwrap().clone()
        }
    }

    impl FfiSessionListener for RecordingListener {
        fn on_thinking(&self, text: String) {
            self.events.lock().unwrap().push(format!("thinking:{text}"));
        }

        fn on_response_chunk(&self, text: String) {
            self.events
                .lock()
                .unwrap()
                .push(format!("response_chunk:{text}"));
        }

        fn on_tool_start(&self, name: String, arguments_hint: String) {
            self.events
                .lock()
                .unwrap()
                .push(format!("tool_start:{name}:{arguments_hint}"));
        }

        fn on_tool_result(&self, name: String, success: bool, duration_secs: u64) {
            self.events
                .lock()
                .unwrap()
                .push(format!("tool_result:{name}:{success}:{duration_secs}"));
        }

        fn on_tool_output(&self, name: String, output: String) {
            self.events
                .lock()
                .unwrap()
                .push(format!("tool_output:{name}:{output}"));
        }

        fn on_progress(&self, phase: FfiProgressPhase) {
            self.events
                .lock()
                .unwrap()
                .push(format!("progress:{phase:?}"));
        }

        fn on_progress_clear(&self) {
            self.events
                .lock()
                .unwrap()
                .push("progress_clear".to_string());
        }

        fn on_compaction(&self, summary: String) {
            self.events
                .lock()
                .unwrap()
                .push(format!("compaction:{summary}"));
        }

        fn on_complete(&self, full_response: String) {
            self.events
                .lock()
                .unwrap()
                .push(format!("complete:{full_response}"));
        }

        fn on_error(&self, error: String) {
            self.events.lock().unwrap().push(format!("error:{error}"));
        }

        fn on_cancelled(&self) {
            self.events.lock().unwrap().push("cancelled".to_string());
        }
    }

    // ── dispatch_delta tests ────────────────────────────────────────

    #[test]
    fn test_dispatch_thinking_first_round() {
        let listener = RecordingListener::new();
        let mut streaming = false;
        dispatch_delta("\u{1f914} Planning next steps\n", &listener, &mut streaming);
        assert!(!streaming);
        assert_eq!(listener.events(), vec!["thinking:Planning next steps"]);
    }

    #[test]
    fn test_dispatch_thinking_round_n() {
        let listener = RecordingListener::new();
        let mut streaming = false;
        dispatch_delta(
            "\u{1f914} Re-evaluating approach\n",
            &listener,
            &mut streaming,
        );
        assert_eq!(listener.events(), vec!["thinking:Re-evaluating approach"]);
    }

    #[test]
    fn test_dispatch_tool_start_with_hint() {
        let listener = RecordingListener::new();
        let mut streaming = false;
        dispatch_delta(
            "\u{23f3} read_file: /src/main.rs\n",
            &listener,
            &mut streaming,
        );
        assert_eq!(listener.events(), vec!["tool_start:read_file:/src/main.rs"]);
    }

    #[test]
    fn test_dispatch_tool_start_no_hint() {
        let listener = RecordingListener::new();
        let mut streaming = false;
        dispatch_delta("\u{23f3} list_files\n", &listener, &mut streaming);
        assert_eq!(listener.events(), vec!["tool_start:list_files:"]);
    }

    #[test]
    fn test_dispatch_tool_success() {
        let listener = RecordingListener::new();
        let mut streaming = false;
        dispatch_delta("\u{2705} read_file (3s)\n", &listener, &mut streaming);
        assert_eq!(listener.events(), vec!["tool_result:read_file:true:3"]);
    }

    #[test]
    fn test_dispatch_tool_failure() {
        let listener = RecordingListener::new();
        let mut streaming = false;
        dispatch_delta(
            "\u{274c} execute_command (12s)\n",
            &listener,
            &mut streaming,
        );
        assert_eq!(
            listener.events(),
            vec!["tool_result:execute_command:false:12"]
        );
    }

    #[test]
    fn test_dispatch_got_tool_calls() {
        let listener = RecordingListener::new();
        let mut streaming = false;
        dispatch_delta("\u{1f4ac} Got 3 tool calls\n", &listener, &mut streaming);
        assert_eq!(
            listener.events(),
            vec!["progress:Raw { message: \"Got 3 tool calls\" }"]
        );
    }

    #[test]
    fn test_dispatch_sentinel_switches_to_response() {
        let listener = RecordingListener::new();
        let mut streaming = false;
        dispatch_delta(DRAFT_CLEAR_SENTINEL, &listener, &mut streaming);
        assert!(streaming);
        assert_eq!(listener.events(), vec!["progress_clear"]);
    }

    #[test]
    fn test_dispatch_response_chunks_after_sentinel() {
        let listener = RecordingListener::new();
        let mut streaming = false;

        dispatch_delta(DRAFT_CLEAR_SENTINEL, &listener, &mut streaming);
        assert!(streaming);

        dispatch_delta("Hello, ", &listener, &mut streaming);
        dispatch_delta("world!", &listener, &mut streaming);

        assert_eq!(
            listener.events(),
            vec![
                "progress_clear",
                "response_chunk:Hello, ",
                "response_chunk:world!",
            ]
        );
    }

    // ── parse_tool_completion tests ─────────────────────────────────

    #[test]
    fn test_parse_tool_completion_with_seconds() {
        let (name, secs) = parse_tool_completion("read_file (3s)");
        assert_eq!(name, "read_file");
        assert_eq!(secs, 3);
    }

    #[test]
    fn test_parse_tool_completion_no_parens() {
        let (name, secs) = parse_tool_completion("list_files");
        assert_eq!(name, "list_files");
        assert_eq!(secs, 0);
    }

    // ── truncate_chars tests ────────────────────────────────────────

    #[test]
    fn test_truncate_chars_short_string() {
        let result = truncate_chars("hello", 10);
        assert_eq!(result, "hello");
    }

    #[test]
    fn test_truncate_chars_long_string() {
        let input = "a".repeat(100);
        let result = truncate_chars(&input, 10);
        assert!(result.ends_with("..."));
        assert!(result.len() <= 14); // 10 chars + "..."
    }

    // ── trim_history tests ──────────────────────────────────────────

    #[test]
    fn test_trim_history_within_limit() {
        let mut history = vec![
            ChatMessage::system("system"),
            ChatMessage::user("hello"),
            ChatMessage::assistant("hi"),
        ];
        trim_history(&mut history, 10);
        assert_eq!(history.len(), 3);
    }

    #[test]
    fn test_trim_history_exceeds_limit() {
        let mut history = vec![ChatMessage::system("system")];
        for i in 0..10 {
            history.push(ChatMessage::user(format!("msg {i}")));
        }
        assert_eq!(history.len(), 11); // 1 system + 10 user

        trim_history(&mut history, 5);
        assert_eq!(history.len(), 6); // 1 system + 5 user
        assert_eq!(history[0].role, "system");
        assert_eq!(history[1].content, "msg 5");
    }

    #[test]
    fn test_trim_history_no_system_prompt() {
        let mut history: Vec<ChatMessage> = (0..10)
            .map(|i| ChatMessage::user(format!("msg {i}")))
            .collect();

        trim_history(&mut history, 3);
        assert_eq!(history.len(), 3);
        assert_eq!(history[0].content, "msg 7");
    }

    // ── build_compaction_transcript tests ────────────────────────────

    #[test]
    fn test_build_compaction_transcript_basic() {
        let messages = vec![
            ChatMessage::user("What is Rust?"),
            ChatMessage::assistant("Rust is a systems programming language."),
        ];
        let transcript = build_compaction_transcript(&messages);
        assert!(transcript.contains("USER: What is Rust?"));
        assert!(transcript.contains("ASSISTANT: Rust is a systems programming language."));
    }

    // ── truncate_tool_args_hint tests ───────────────────────────────

    #[test]
    fn test_truncate_tool_args_hint_shell() {
        let hint = truncate_tool_args_hint("shell", r#"{"command":"ls -la"}"#);
        assert_eq!(hint, "ls -la");
    }

    #[test]
    fn test_truncate_tool_args_hint_file_read() {
        let hint = truncate_tool_args_hint("file_read", r#"{"path":"/etc/hosts"}"#);
        assert_eq!(hint, "/etc/hosts");
    }

    #[test]
    fn test_truncate_tool_args_hint_unknown_tool() {
        let hint = truncate_tool_args_hint("unknown", r#"{"query":"search term"}"#);
        assert_eq!(hint, "search term");
    }

    #[test]
    fn test_truncate_tool_args_hint_invalid_json() {
        let hint = truncate_tool_args_hint("shell", "not json");
        assert!(hint.is_empty());
    }

    // ── build_native_assistant_history tests ─────────────────────────

    #[test]
    fn test_build_native_assistant_history_basic() {
        let calls = vec![zeroclaw::providers::ToolCall {
            id: "call_123".into(),
            name: "shell".into(),
            arguments: r#"{"command":"ls"}"#.into(),
        }];

        let result = build_native_assistant_history("Let me check", &calls, None);
        let parsed: serde_json::Value = serde_json::from_str(&result).unwrap();

        assert_eq!(parsed["content"], "Let me check");
        assert_eq!(parsed["tool_calls"][0]["id"], "call_123");
        assert_eq!(parsed["tool_calls"][0]["function"]["name"], "shell");
        assert!(parsed.get("reasoning_content").is_none());
    }

    #[test]
    fn test_build_native_assistant_history_with_reasoning() {
        let calls = vec![zeroclaw::providers::ToolCall {
            id: "call_456".into(),
            name: "file_read".into(),
            arguments: r#"{"path":"test.rs"}"#.into(),
        }];

        let result =
            build_native_assistant_history("Reading file", &calls, Some("thinking about it"));
        let parsed: serde_json::Value = serde_json::from_str(&result).unwrap();

        assert_eq!(parsed["reasoning_content"], "thinking about it");
    }

    // ── stream_response_text tests ──────────────────────────────────

    #[test]
    fn test_stream_response_text_short() {
        let recording = Arc::new(RecordingListener::new());
        let listener: Arc<dyn FfiSessionListener> = recording.clone();
        let token = CancellationToken::new();

        let result = stream_response_text("Hello world", &listener, &token);
        assert!(result.is_ok());

        let events = recording.events();
        assert_eq!(events.len(), 1);
        assert_eq!(events[0], "response_chunk:Hello world");
    }

    #[test]
    fn test_stream_response_text_cancelled() {
        let recording = Arc::new(RecordingListener::new());
        let listener: Arc<dyn FfiSessionListener> = recording.clone();
        let token = CancellationToken::new();
        token.cancel();

        let result = stream_response_text("Hello world", &listener, &token);
        assert!(result.is_err());
    }

    // ── session lifecycle unit tests (no daemon) ────────────────────

    #[test]
    fn test_session_send_no_session() {
        *lock_session() = None;
        let listener = Arc::new(RecordingListener::new());
        let result = session_send_inner("hello".into(), vec![], vec![], listener);
        assert!(result.is_err());
        match result.unwrap_err() {
            FfiError::StateError { detail } => {
                assert!(detail.contains("no active session"));
            }
            other => panic!("expected StateError, got {other:?}"),
        }
    }

    #[test]
    fn test_session_send_oversized_message() {
        let listener = Arc::new(RecordingListener::new());
        let big_message = "x".repeat(MAX_MESSAGE_BYTES + 1);
        let result = session_send_inner(big_message, vec![], vec![], listener);
        assert!(result.is_err());
        match result.unwrap_err() {
            FfiError::ConfigError { detail } => {
                assert!(detail.contains("too large"));
            }
            other => panic!("expected ConfigError, got {other:?}"),
        }
    }

    #[test]
    fn test_session_send_mismatched_image_arrays() {
        let listener = Arc::new(RecordingListener::new());
        let result = session_send_inner("hi".into(), vec!["base64data".into()], vec![], listener);
        assert!(result.is_err());
        match result.unwrap_err() {
            FfiError::ConfigError { detail } => {
                assert!(detail.contains("image_data length"));
            }
            other => panic!("expected ConfigError, got {other:?}"),
        }
    }

    #[test]
    fn test_session_send_too_many_images() {
        let listener = Arc::new(RecordingListener::new());
        let images = vec!["img".to_string(); MAX_SESSION_IMAGES + 1];
        let mimes = vec!["image/png".to_string(); MAX_SESSION_IMAGES + 1];
        let result = session_send_inner("hi".into(), images, mimes, listener);
        assert!(result.is_err());
        match result.unwrap_err() {
            FfiError::ConfigError { detail } => {
                assert!(detail.contains("too many images"));
            }
            other => panic!("expected ConfigError, got {other:?}"),
        }
    }

    #[test]
    fn test_compose_multimodal_message_no_images() {
        let result = compose_multimodal_message("hello world", &[], &[]);
        assert_eq!(result, "hello world");
    }

    #[test]
    fn test_compose_multimodal_message_with_images() {
        let result =
            compose_multimodal_message("describe this", &["abc123".into()], &["image/png".into()]);
        assert!(result.starts_with("describe this"));
        assert!(result.contains("[IMAGE:data:image/png;base64,abc123]"));
    }

    #[test]
    fn test_session_cancel_no_send() {
        session_cancel_inner();
    }

    #[test]
    fn test_session_clear_no_session() {
        *lock_session() = None;
        let result = session_clear_inner();
        assert!(result.is_err());
        match result.unwrap_err() {
            FfiError::StateError { detail } => {
                assert!(detail.contains("no active session"));
            }
            other => panic!("expected StateError, got {other:?}"),
        }
    }

    #[test]
    fn test_session_history_no_session() {
        *lock_session() = None;
        let result = session_history_inner();
        assert!(result.is_err());
        match result.unwrap_err() {
            FfiError::StateError { detail } => {
                assert!(detail.contains("no active session"));
            }
            other => panic!("expected StateError, got {other:?}"),
        }
    }

    #[test]
    fn test_session_destroy_no_session() {
        *lock_session() = None;
        let result = session_destroy_inner();
        assert!(result.is_err());
        match result.unwrap_err() {
            FfiError::StateError { detail } => {
                assert!(detail.contains("no active session"));
            }
            other => panic!("expected StateError, got {other:?}"),
        }
    }

    // ── append_android_identity_extras tests ────────────────────────

    #[test]
    fn test_android_identity_extras_user_name() {
        let config = zeroclaw::config::IdentityConfig {
            format: "aieos".into(),
            aieos_path: None,
            aieos_inline: Some(
                r#"{"identity":{"names":{"first":"Nova"},"user_name":"Alice","timezone":"US/Eastern","communication_style":"casual"}}"#.into(),
            ),
        };
        let mut prompt = String::from("## Identity\n\n**Name:** Nova\n");
        append_android_identity_extras(&mut prompt, &config);
        assert!(prompt.contains("**User's name:** Alice"));
        assert!(prompt.contains("**Timezone:** US/Eastern"));
        assert!(prompt.contains("**Preferred communication style:** casual"));
    }

    #[test]
    fn test_android_identity_extras_empty_inline() {
        let config = zeroclaw::config::IdentityConfig {
            format: "aieos".into(),
            aieos_path: None,
            aieos_inline: None,
        };
        let mut prompt = String::from("base prompt");
        append_android_identity_extras(&mut prompt, &config);
        assert_eq!(prompt, "base prompt");
    }

    #[test]
    fn test_android_identity_extras_no_extra_fields() {
        let config = zeroclaw::config::IdentityConfig {
            format: "aieos".into(),
            aieos_path: None,
            aieos_inline: Some(r#"{"identity":{"names":{"first":"Nova"}}}"#.into()),
        };
        let mut prompt = String::from("base prompt");
        append_android_identity_extras(&mut prompt, &config);
        assert_eq!(prompt, "base prompt");
    }

    // ── SessionStateGuard tests ────────────────────────────────────

    #[test]
    fn test_guard_take_disarms_drop() {
        let history = vec![ChatMessage::user("hello")];
        let guard = SessionStateGuard::new(history, vec![]);

        let (h, t) = guard.take().unwrap();
        assert_eq!(h.len(), 1);
        assert!(t.is_empty());
        // Drop runs here but is a no-op (defused).
    }

    #[test]
    fn test_guard_state_mut_provides_references() {
        let history = vec![ChatMessage::user("one")];
        let mut guard = SessionStateGuard::new(history, vec![]);

        let (h, _t) = guard.state_mut().unwrap();
        h.push(ChatMessage::assistant("two"));
        assert_eq!(h.len(), 2);

        let (taken_h, _) = guard.take().unwrap();
        assert_eq!(taken_h.len(), 2);
        assert_eq!(taken_h[1].content, "two");
    }

    #[test]
    fn test_guard_drop_without_take_keeps_state() {
        // Verify that dropping a guard without calling take() does NOT
        // consume the state (it's available for the Drop impl to use).
        // The actual SESSION restoration is tested implicitly through
        // session_send_inner's panic-safety.
        let history = vec![ChatMessage::user("preserved")];
        let guard = SessionStateGuard::new(history, vec![]);
        // Drop fires here — without a live SESSION it's a no-op,
        // but critically it does NOT panic.
        drop(guard);
    }

    #[test]
    #[ignore = "flaky under parallel execution due to shared SESSION mutex"]
    fn test_guard_drop_restores_session_on_panic() {
        *lock_session() = None;

        {
            let mut guard = lock_session();
            *guard = Some(Session {
                history: vec![ChatMessage::user("preserved")],
                config: zeroclaw::Config::default(),
                system_prompt: String::new(),
                model: String::new(),
                temperature: 0.7,
                provider_name: String::new(),
                tools_registry: vec![],
            });
        }

        let _panic_result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            let (history, tools) = {
                let mut guard = lock_session();
                let session = guard.as_mut().unwrap();
                (
                    std::mem::take(&mut session.history),
                    std::mem::take(&mut session.tools_registry),
                )
            };
            let _state_guard = SessionStateGuard::new(history, tools);
            panic!("simulated unwind");
        }));

        {
            let guard = lock_session();
            let session = guard.as_ref().expect("session should exist");
            assert_eq!(session.history.len(), 1);
        }

        *lock_session() = None;
    }

    #[test]
    fn test_poisoned_cancel_token_recovery() {
        let _panic_result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            let _guard = CANCEL_TOKEN.lock().unwrap();
            panic!("poison the mutex");
        }));

        let mut guard = lock_cancel_token();
        *guard = Some(CancellationToken::new());
        assert!(guard.is_some());
        *guard = None;
    }

    // ── extract_thinking_from_text tests ────────────────────────────

    #[test]
    fn test_extract_thinking_basic_think_tag() {
        let input = "<think>Planning my approach</think>Here is the answer.";
        let (clean, thinking) = extract_thinking_from_text(input);
        assert_eq!(clean, "Here is the answer.");
        assert_eq!(thinking, "Planning my approach");
    }

    #[test]
    fn test_extract_thinking_case_insensitive() {
        let input = "<THINK>Uppercase tags</THINK>Result text.";
        let (clean, thinking) = extract_thinking_from_text(input);
        assert_eq!(clean, "Result text.");
        assert_eq!(thinking, "Uppercase tags");
    }

    #[test]
    fn test_extract_thinking_mixed_case() {
        let input = "<Think>Mixed case</Think>Output.";
        let (clean, thinking) = extract_thinking_from_text(input);
        assert_eq!(clean, "Output.");
        assert_eq!(thinking, "Mixed case");
    }

    #[test]
    fn test_extract_thinking_multiple_blocks() {
        let input = "<think>First thought</think>Middle text<think>Second thought</think>End.";
        let (clean, thinking) = extract_thinking_from_text(input);
        assert_eq!(clean, "Middle textEnd.");
        assert_eq!(thinking, "First thought\nSecond thought");
    }

    #[test]
    fn test_extract_thinking_no_tags() {
        let input = "Plain response with no thinking tags.";
        let (clean, thinking) = extract_thinking_from_text(input);
        assert_eq!(clean, "Plain response with no thinking tags.");
        assert_eq!(thinking, "");
    }

    #[test]
    fn test_extract_thinking_empty_tag() {
        let input = "<think></think>Just the answer.";
        let (clean, thinking) = extract_thinking_from_text(input);
        assert_eq!(clean, "Just the answer.");
        assert_eq!(thinking, "");
    }

    #[test]
    fn test_extract_thinking_whitespace_only_tag() {
        let input = "<think>   \n  </think>Answer.";
        let (clean, thinking) = extract_thinking_from_text(input);
        assert_eq!(clean, "Answer.");
        assert_eq!(thinking, "");
    }

    #[test]
    fn test_extract_thinking_different_tag_types() {
        let input = "<thinking>Deep analysis</thinking>Response here.";
        let (clean, thinking) = extract_thinking_from_text(input);
        assert_eq!(clean, "Response here.");
        assert_eq!(thinking, "Deep analysis");
    }

    #[test]
    fn test_extract_thinking_reflection_tag() {
        let input = "<reflection>Checking my work</reflection>Final answer.";
        let (clean, thinking) = extract_thinking_from_text(input);
        assert_eq!(clean, "Final answer.");
        assert_eq!(thinking, "Checking my work");
    }

    #[test]
    fn test_extract_thinking_unclosed_tag_preserved() {
        let input = "<think>Unclosed thinking block without end tag";
        let (clean, thinking) = extract_thinking_from_text(input);
        assert_eq!(clean, "<think>Unclosed thinking block without end tag");
        assert_eq!(thinking, "");
    }

    #[test]
    fn test_extract_thinking_preserves_whitespace() {
        let input = "Before  <think>Thought</think>  After";
        let (clean, thinking) = extract_thinking_from_text(input);
        assert_eq!(clean, "Before    After");
        assert_eq!(thinking, "Thought");
    }

    #[test]
    fn test_extract_thinking_multiline_content() {
        let input = "<think>\nLine 1\nLine 2\nLine 3\n</think>The response.";
        let (clean, thinking) = extract_thinking_from_text(input);
        assert_eq!(clean, "The response.");
        assert_eq!(thinking, "Line 1\nLine 2\nLine 3");
    }

    #[test]
    fn extract_thinking_reasoning_tag() {
        let input = "<reasoning>Step 1: check input\nStep 2: validate</reasoning>Final answer.";
        let (clean, thinking) = extract_thinking_from_text(input);
        assert_eq!(clean, "Final answer.");
        assert_eq!(thinking, "Step 1: check input\nStep 2: validate");
    }

    // ── parse_xml_tool_calls tests ──────────────────────────────────

    #[test]
    fn test_parse_xml_single_tool_call() {
        let input =
            r#"<tool_call>{"name": "web_search", "arguments": {"query": "rust lang"}}</tool_call>"#;
        let (clean, calls) = parse_xml_tool_calls(input);
        assert_eq!(calls.len(), 1);
        assert_eq!(calls[0].name, "web_search");
        assert!(calls[0].arguments.contains("rust lang"));
        assert_eq!(calls[0].id, "xmltc_1");
        assert_eq!(clean.trim(), "");
    }

    #[test]
    fn test_parse_xml_multiple_tool_calls() {
        let input = concat!(
            r#"<tool_call>{"name": "web_search", "arguments": {"query": "a"}}</tool_call>"#,
            " ",
            r#"<tool_call>{"name": "web_fetch", "arguments": {"url": "https://example.com"}}</tool_call>"#,
        );
        let (clean, calls) = parse_xml_tool_calls(input);
        assert_eq!(calls.len(), 2);
        assert_eq!(calls[0].name, "web_search");
        assert_eq!(calls[0].id, "xmltc_1");
        assert_eq!(calls[1].name, "web_fetch");
        assert_eq!(calls[1].id, "xmltc_2");
        assert_eq!(clean.trim(), "");
    }

    #[test]
    fn test_parse_xml_no_tool_calls() {
        let input = "Just a normal response with no tool calls.";
        let (clean, calls) = parse_xml_tool_calls(input);
        assert!(calls.is_empty());
        assert_eq!(clean, input);
    }

    #[test]
    fn test_parse_xml_malformed_json_skipped() {
        let input = "<tool_call>this is not json</tool_call>";
        let (clean, calls) = parse_xml_tool_calls(input);
        assert!(calls.is_empty());
        assert_eq!(clean.trim(), "");
    }

    #[test]
    fn test_parse_xml_case_insensitive_tags() {
        let input =
            r#"<Tool_Call>{"name": "web_search", "arguments": {"query": "test"}}</TOOL_CALL>"#;
        let (clean, calls) = parse_xml_tool_calls(input);
        assert_eq!(calls.len(), 1);
        assert_eq!(calls[0].name, "web_search");
        assert_eq!(clean.trim(), "");
    }

    #[test]
    fn test_parse_xml_mixed_text_and_calls() {
        let input = concat!(
            "Let me search for that. ",
            r#"<tool_call>{"name": "web_search", "arguments": {"query": "weather"}}</tool_call>"#,
            " I found the results.",
        );
        let (clean, calls) = parse_xml_tool_calls(input);
        assert_eq!(calls.len(), 1);
        assert_eq!(calls[0].name, "web_search");
        assert_eq!(clean, "Let me search for that.  I found the results.");
    }

    #[test]
    fn test_parse_xml_missing_name_skipped() {
        let input = r#"<tool_call>{"arguments": {"key": "val"}}</tool_call>"#;
        let (_, calls) = parse_xml_tool_calls(input);
        assert!(calls.is_empty());
    }

    #[test]
    fn test_parse_xml_empty_name_skipped() {
        let input = r#"<tool_call>{"name": "", "arguments": {}}</tool_call>"#;
        let (_, calls) = parse_xml_tool_calls(input);
        assert!(calls.is_empty());
    }

    #[test]
    fn test_parse_xml_missing_arguments_defaults_to_empty() {
        let input = r#"<tool_call>{"name": "list_tools"}</tool_call>"#;
        let (_, calls) = parse_xml_tool_calls(input);
        assert_eq!(calls.len(), 1);
        assert_eq!(calls[0].name, "list_tools");
        assert_eq!(calls[0].arguments, "{}");
    }

    #[test]
    fn test_parse_xml_unclosed_tag_ignored() {
        let input = r#"<tool_call>{"name": "web_search", "arguments": {"q": "x"}}"#;
        let (clean, calls) = parse_xml_tool_calls(input);
        assert!(calls.is_empty());
        assert_eq!(clean, input);
    }

    #[test]
    fn test_parse_xml_multiline_call() {
        let input = "<tool_call>\n{\n  \"name\": \"recall_memory\",\n  \"arguments\": {\"query\": \"user prefs\"}\n}\n</tool_call>";
        let (_, calls) = parse_xml_tool_calls(input);
        assert_eq!(calls.len(), 1);
        assert_eq!(calls[0].name, "recall_memory");
        assert!(calls[0].arguments.contains("user prefs"));
    }

    /// Verifies that `build_system_prompt_with_mode` includes skill content
    /// when a non-empty skills slice is passed. This is the contract that
    /// `session_start_inner` relies on after loading skills from the
    /// workspace.
    #[test]
    fn test_system_prompt_includes_skills_when_provided() {
        let workspace = std::env::temp_dir().join("zeroclaw_test_prompt_skills");
        let _ = std::fs::create_dir_all(&workspace);

        let skill = zeroclaw::skills::Skill {
            name: "test-weather".into(),
            description: "Get weather data".into(),
            version: "1.0.0".into(),
            author: Some("tester".into()),
            tags: vec!["weather".into()],
            permissions: Vec::new(),
            tools: Vec::new(),
            scripts: Vec::new(),
            triggers: Vec::new(),
            prompts: vec!["Always check the forecast.".into()],
            api_base: None,
            location: None,
        };

        let prompt = zeroclaw::channels::build_system_prompt_with_mode(
            &workspace,
            "test-model",
            &[],
            &[skill],
            None,
            None,
            false,
            zeroclaw::config::SkillsPromptInjectionMode::Full,
            None,
            None,
        );

        assert!(
            prompt.contains("test-weather"),
            "system prompt should contain skill name, got:\n{prompt}"
        );
        assert!(
            prompt.contains("Always check the forecast"),
            "system prompt should contain skill instructions"
        );

        let _ = std::fs::remove_dir_all(&workspace);
    }

    /// Verifies that `build_system_prompt_with_mode` does NOT include skills
    /// when an empty slice is passed — this was the bug in session_start_inner.
    #[test]
    fn test_system_prompt_excludes_skills_when_empty() {
        let workspace = std::env::temp_dir().join("zeroclaw_test_prompt_no_skills");
        let _ = std::fs::create_dir_all(&workspace);

        let prompt = zeroclaw::channels::build_system_prompt_with_mode(
            &workspace,
            "test-model",
            &[],
            &[],
            None,
            None,
            false,
            zeroclaw::config::SkillsPromptInjectionMode::Full,
            None,
            None,
        );

        assert!(
            !prompt.contains("available_skills"),
            "system prompt should NOT contain skills section when slice is empty"
        );

        let _ = std::fs::remove_dir_all(&workspace);
    }

    /// Verifies that `load_skills_with_config` picks up a community SKILL.md
    /// from the workspace directory. This is the function now called by
    /// `session_start_inner`.
    #[test]
    fn test_load_skills_with_config_finds_community_skill() {
        let workspace = std::env::temp_dir().join("zeroclaw_test_load_skills_cfg");
        let _ = std::fs::remove_dir_all(&workspace);
        let skill_dir = workspace.join("skills").join("my-skill");
        std::fs::create_dir_all(&skill_dir).unwrap();
        std::fs::write(
            skill_dir.join("SKILL.md"),
            "---\nname: my-skill\ndescription: A test skill\nversion: \"1.0.0\"\n---\nDo the thing.",
        )
        .unwrap();

        let config = zeroclaw::Config::default();
        let skills = zeroclaw::skills::load_skills_with_config(&workspace, &config);

        assert_eq!(skills.len(), 1);
        assert_eq!(skills[0].name, "my-skill");
        assert!(
            !skills[0].prompts.is_empty(),
            "community skill should have prompts from body"
        );
        assert!(
            skills[0].prompts[0].contains("Do the thing"),
            "prompt content should contain SKILL.md body"
        );

        let _ = std::fs::remove_dir_all(&workspace);
    }
}
