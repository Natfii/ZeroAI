use super::traits::{Tool, ToolResult};
use crate::security::SecurityPolicy;
use async_trait::async_trait;
use futures_util::StreamExt;
use serde_json::json;
use std::sync::{Arc, OnceLock};
use std::time::Duration;

/// Trait for WebView-based page rendering, used as a fallback when
/// plain HTTP requests are blocked by bot detection.
///
/// The FFI layer implements this via a UniFFI callback interface.
/// The trait lives in the engine crate so `WebFetchTool` has no
/// dependency on the FFI crate.
pub trait WebViewFallback: Send + Sync {
    /// Renders the URL in a real browser and returns extracted text.
    fn render_page(&self, url: &str, timeout_ms: u64) -> Result<String, String>;
}

/// Global fallback slot for the WebView renderer.
///
/// Set once at process startup by the FFI layer via [`set_global_webview_fallback`].
/// Read by [`super::all_tools_with_runtime`] when constructing the tool registry
/// without an explicit fallback parameter (i.e. when the caller is an engine-internal
/// path that doesn't have direct access to the FFI adapter).
static GLOBAL_WEBVIEW_FALLBACK: OnceLock<Arc<dyn WebViewFallback>> = OnceLock::new();

/// Global User-Agent string slot.
///
/// Set once at process startup by the FFI layer via [`set_global_user_agent`].
/// Read by the reqwest client builder in [`WebFetchTool::execute`].
static GLOBAL_USER_AGENT: OnceLock<String> = OnceLock::new();

/// Fallback User-Agent when no device-authentic UA has been registered.
const FALLBACK_USER_AGENT: &str =
    "Mozilla/5.0 (Linux; Android 15; Pixel) AppleWebKit/537.36 \
     (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";

/// Registers a device-authentic User-Agent string.
///
/// Called once during FFI daemon startup from Kotlin. Subsequent calls are
/// ignored (first-writer-wins, matching `OnceLock` semantics).
pub fn set_global_user_agent(ua: String) {
    let _ = GLOBAL_USER_AGENT.set(ua);
}

/// Returns the effective User-Agent string for reqwest requests.
fn effective_user_agent() -> &'static str {
    GLOBAL_USER_AGENT
        .get()
        .map(|s| s.as_str())
        .unwrap_or(FALLBACK_USER_AGENT)
}

/// Registers a process-wide [`WebViewFallback`] implementation.
///
/// Called once during FFI daemon startup. Subsequent calls are ignored
/// (first-writer-wins, matching `OnceLock` semantics).
pub fn set_global_webview_fallback(fallback: Arc<dyn WebViewFallback>) {
    let _ = GLOBAL_WEBVIEW_FALLBACK.set(fallback);
}

/// Returns the global [`WebViewFallback`], if one has been registered.
pub fn global_webview_fallback() -> Option<Arc<dyn WebViewFallback>> {
    GLOBAL_WEBVIEW_FALLBACK.get().cloned()
}

/// Web fetch tool: fetches a web page and converts HTML to plain text for LLM consumption.
///
/// Unlike `http_request` (an API client returning raw responses), this tool:
/// - Only supports GET
/// - Follows redirects (up to 10)
/// - Converts HTML to Markdown via `htmd` (headings, lists, links, code preserved)
/// - Passes through text/plain, text/markdown, and application/json as-is
/// - Sets a descriptive User-Agent
pub struct WebFetchTool {
    security: Arc<SecurityPolicy>,
    allowed_domains: Vec<String>,
    blocked_domains: Vec<String>,
    max_response_size: usize,
    timeout_secs: u64,
    webview_fallback: Option<Arc<dyn WebViewFallback>>,
}

impl WebFetchTool {
    pub fn new(
        security: Arc<SecurityPolicy>,
        allowed_domains: Vec<String>,
        blocked_domains: Vec<String>,
        max_response_size: usize,
        timeout_secs: u64,
    ) -> Self {
        Self {
            security,
            allowed_domains: normalize_allowed_domains(allowed_domains),
            blocked_domains: normalize_allowed_domains(blocked_domains),
            max_response_size,
            timeout_secs,
            webview_fallback: None,
        }
    }

    /// Sets the WebView fallback renderer.
    pub fn with_webview_fallback(mut self, fallback: Arc<dyn WebViewFallback>) -> Self {
        self.webview_fallback = Some(fallback);
        self
    }

    fn validate_url(&self, raw_url: &str) -> anyhow::Result<String> {
        validate_target_url(
            raw_url,
            &self.allowed_domains,
            &self.blocked_domains,
            "web_fetch",
        )
    }

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

    async fn read_response_text_limited(
        &self,
        response: reqwest::Response,
    ) -> anyhow::Result<String> {
        let mut bytes_stream = response.bytes_stream();
        let hard_cap = self.max_response_size.saturating_add(1);
        let mut bytes = Vec::new();

        while let Some(chunk_result) = bytes_stream.next().await {
            let chunk = chunk_result?;
            if append_chunk_with_cap(&mut bytes, &chunk, hard_cap) {
                break;
            }
        }

        Ok(String::from_utf8_lossy(&bytes).into_owned())
    }
}

#[async_trait]
impl Tool for WebFetchTool {
    fn name(&self) -> &str {
        "web_fetch"
    }

    fn description(&self) -> &str {
        "Fetch a web page and return its content as clean Markdown. \
         HTML pages are automatically converted to structured Markdown. \
         JSON and plain text responses are returned as-is. \
         Only GET requests; follows redirects. \
         Security: allowlist-only domains, no local/private hosts."
    }

    fn parameters_schema(&self) -> serde_json::Value {
        json!({
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
        let url = args
            .get("url")
            .and_then(|v| v.as_str())
            .ok_or_else(|| anyhow::anyhow!("Missing 'url' parameter"))?;

        if !self.security.can_act() {
            return Ok(ToolResult {
                success: false,
                output: String::new(),
                error: Some("Action blocked: autonomy is read-only".into()),
            });
        }

        if !self.security.record_action() {
            return Ok(ToolResult {
                success: false,
                output: String::new(),
                error: Some("Action blocked: rate limit exceeded".into()),
            });
        }

        let url = match self.validate_url(url) {
            Ok(v) => v,
            Err(e) => {
                return Ok(ToolResult {
                    success: false,
                    output: String::new(),
                    error: Some(e.to_string()),
                })
            }
        };

        let timeout_secs = if self.timeout_secs == 0 {
            tracing::warn!("web_fetch: timeout_secs is 0, using safe default of 30s");
            30
        } else {
            self.timeout_secs
        };

        // Primary path: reqwest GET with browser headers. If bot-blocked,
        // escalate to WebView fallback (if registered).
        let allowed_domains = self.allowed_domains.clone();
        let blocked_domains = self.blocked_domains.clone();
        let redirect_policy = reqwest::redirect::Policy::custom(move |attempt| {
            if attempt.previous().len() >= 10 {
                return attempt.error(std::io::Error::other("Too many redirects (max 10)"));
            }

            if let Err(err) = validate_target_url(
                attempt.url().as_str(),
                &allowed_domains,
                &blocked_domains,
                "web_fetch",
            ) {
                return attempt.error(std::io::Error::new(
                    std::io::ErrorKind::PermissionDenied,
                    format!("Blocked redirect target: {err}"),
                ));
            }

            attempt.follow()
        });

        let builder = reqwest::Client::builder()
            .timeout(Duration::from_secs(timeout_secs))
            .connect_timeout(Duration::from_secs(10))
            .redirect(redirect_policy)
            .user_agent(effective_user_agent());
        let builder = crate::config::apply_runtime_proxy_to_builder(builder, "tool.web_fetch");
        let client = match builder.build() {
            Ok(c) => c,
            Err(e) => {
                return Ok(ToolResult {
                    success: false,
                    output: String::new(),
                    error: Some(format!("Failed to build HTTP client: {e}")),
                })
            }
        };

        let response = match client
            .get(&url)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "none")
            .header("Sec-Fetch-User", "?1")
            .send()
            .await
        {
            Ok(r) => r,
            Err(e) => {
                return Ok(ToolResult {
                    success: false,
                    output: String::new(),
                    error: Some(format!("HTTP request failed: {e}")),
                })
            }
        };

        let status = response.status();
        let status_code = status.as_u16();

        let header_pairs: Vec<(String, String)> = response
            .headers()
            .iter()
            .map(|(name, value)| {
                (
                    name.as_str().to_string(),
                    value.to_str().unwrap_or("").to_string(),
                )
            })
            .collect();

        let content_type = response
            .headers()
            .get(reqwest::header::CONTENT_TYPE)
            .and_then(|v| v.to_str().ok())
            .unwrap_or("")
            .to_lowercase();

        let is_escalation_candidate = status_code == 403 || status_code == 503;

        if !status.is_success() && !is_escalation_candidate {
            return Ok(ToolResult {
                success: false,
                output: String::new(),
                error: Some(format!(
                    "HTTP {} {}",
                    status_code,
                    status.canonical_reason().unwrap_or("Unknown")
                )),
            });
        }

        let body_mode = if content_type.contains("text/html") || content_type.is_empty() {
            "html"
        } else if content_type.contains("text/plain")
            || content_type.contains("text/markdown")
            || content_type.contains("application/json")
        {
            "plain"
        } else if is_escalation_candidate {
            "html"
        } else {
            return Ok(ToolResult {
                success: false,
                output: String::new(),
                error: Some(format!(
                    "Unsupported content type: {content_type}. \
                     web_fetch supports text/html, text/plain, text/markdown, and application/json."
                )),
            });
        };

        let body = match self.read_response_text_limited(response).await {
            Ok(t) => t,
            Err(e) => {
                return Ok(ToolResult {
                    success: false,
                    output: String::new(),
                    error: Some(format!("Failed to read response body: {e}")),
                })
            }
        };

        if needs_webview_escalation(status_code, &header_pairs, &body) {
            if let Some(fallback) = &self.webview_fallback {
                let timeout_ms = timeout_secs.saturating_mul(1000);
                return match fallback.render_page(&url, timeout_ms) {
                    Ok(rendered) => {
                        let output = self.truncate_response(&rendered);
                        Ok(ToolResult {
                            success: true,
                            output,
                            error: None,
                        })
                    }
                    Err(e) => Ok(ToolResult {
                        success: false,
                        output: String::new(),
                        error: Some(format!("WebView render failed: {e}")),
                    }),
                };
            }
            return Ok(ToolResult {
                success: false,
                output: String::new(),
                error: Some(
                    "Page blocked by bot detection. No WebView renderer available.".into(),
                ),
            });
        }

        if !status.is_success() {
            return Ok(ToolResult {
                success: false,
                output: String::new(),
                error: Some(format!(
                    "HTTP {} {}",
                    status_code,
                    status.canonical_reason().unwrap_or("Unknown")
                )),
            });
        }

        let text = if body_mode == "html" {
            let converter = htmd::HtmlToMarkdown::builder()
                .skip_tags(vec![
                    "script", "style", "noscript", "nav", "footer", "header",
                    "iframe", "ins", "svg",
                ])
                .build();
            converter.convert(&body).unwrap_or_else(|_| body.clone())
        } else {
            body
        };

        let output = self.truncate_response(&text);

        Ok(ToolResult {
            success: true,
            output,
            error: None,
        })
    }
}

fn validate_target_url(
    raw_url: &str,
    allowed_domains: &[String],
    blocked_domains: &[String],
    tool_name: &str,
) -> anyhow::Result<String> {
    let url = raw_url.trim();

    if url.is_empty() {
        anyhow::bail!("URL cannot be empty");
    }

    if url.chars().any(char::is_whitespace) {
        anyhow::bail!("URL cannot contain whitespace");
    }

    if !url.starts_with("http://") && !url.starts_with("https://") {
        anyhow::bail!("Only http:// and https:// URLs are allowed");
    }

    if allowed_domains.is_empty() {
        anyhow::bail!(
            "{tool_name} tool is enabled but no allowed_domains are configured. \
             Add [{tool_name}].allowed_domains in config.toml"
        );
    }

    let host = super::extract_host(url)?;

    if is_private_or_local_host(&host) {
        anyhow::bail!("Blocked local/private host: {host}");
    }

    if host_matches_allowlist(&host, blocked_domains) {
        anyhow::bail!("Host '{host}' is in {tool_name}.blocked_domains");
    }

    if !host_matches_allowlist(&host, allowed_domains) {
        anyhow::bail!("Host '{host}' is not in {tool_name}.allowed_domains");
    }

    validate_resolved_host_is_public(&host)?;

    Ok(url.to_string())
}

fn append_chunk_with_cap(buffer: &mut Vec<u8>, chunk: &[u8], hard_cap: usize) -> bool {
    if buffer.len() >= hard_cap {
        return true;
    }

    let remaining = hard_cap - buffer.len();
    if chunk.len() > remaining {
        buffer.extend_from_slice(&chunk[..remaining]);
        return true;
    }

    buffer.extend_from_slice(chunk);
    buffer.len() >= hard_cap
}

fn normalize_allowed_domains(domains: Vec<String>) -> Vec<String> {
    let mut normalized = domains
        .into_iter()
        .filter_map(|d| normalize_domain(&d))
        .collect::<Vec<_>>();
    normalized.sort_unstable();
    normalized.dedup();
    normalized
}

fn normalize_domain(raw: &str) -> Option<String> {
    let mut d = raw.trim().to_lowercase();
    if d.is_empty() {
        return None;
    }

    if let Some(stripped) = d.strip_prefix("https://") {
        d = stripped.to_string();
    } else if let Some(stripped) = d.strip_prefix("http://") {
        d = stripped.to_string();
    }

    if let Some((host, _)) = d.split_once('/') {
        d = host.to_string();
    }

    d = d.trim_start_matches('.').trim_end_matches('.').to_string();

    if let Some((host, _)) = d.split_once(':') {
        d = host.to_string();
    }

    if d.is_empty() || d.chars().any(char::is_whitespace) {
        return None;
    }

    Some(d)
}

fn host_matches_allowlist(host: &str, allowed_domains: &[String]) -> bool {
    if allowed_domains.iter().any(|domain| domain == "*") {
        return true;
    }

    allowed_domains.iter().any(|domain| {
        host == domain
            || host
                .strip_suffix(domain)
                .is_some_and(|prefix| prefix.ends_with('.'))
    })
}

fn is_private_or_local_host(host: &str) -> bool {
    let bare = host
        .strip_prefix('[')
        .and_then(|h| h.strip_suffix(']'))
        .unwrap_or(host);

    let has_local_tld = bare
        .rsplit('.')
        .next()
        .is_some_and(|label| label == "local");

    if bare == "localhost" || bare.ends_with(".localhost") || has_local_tld {
        return true;
    }

    if let Ok(ip) = bare.parse::<std::net::IpAddr>() {
        return match ip {
            std::net::IpAddr::V4(v4) => is_non_global_v4(v4),
            std::net::IpAddr::V6(v6) => is_non_global_v6(v6),
        };
    }

    false
}

#[cfg(not(test))]
fn validate_resolved_host_is_public(host: &str) -> anyhow::Result<()> {
    use std::net::ToSocketAddrs;

    let ips = (host, 0)
        .to_socket_addrs()
        .map_err(|e| anyhow::anyhow!("Failed to resolve host '{host}': {e}"))?
        .map(|addr| addr.ip())
        .collect::<Vec<_>>();

    validate_resolved_ips_are_public(host, &ips)
}

#[cfg(test)]
fn validate_resolved_host_is_public(_host: &str) -> anyhow::Result<()> {
    Ok(())
}

fn validate_resolved_ips_are_public(host: &str, ips: &[std::net::IpAddr]) -> anyhow::Result<()> {
    if ips.is_empty() {
        anyhow::bail!("Failed to resolve host '{host}'");
    }

    for ip in ips {
        let non_global = match ip {
            std::net::IpAddr::V4(v4) => is_non_global_v4(*v4),
            std::net::IpAddr::V6(v6) => is_non_global_v6(*v6),
        };
        if non_global {
            anyhow::bail!("Blocked host '{host}' resolved to non-global address {ip}");
        }
    }

    Ok(())
}

fn is_non_global_v4(v4: std::net::Ipv4Addr) -> bool {
    let [a, b, c, _] = v4.octets();
    v4.is_loopback()
        || v4.is_private()
        || v4.is_link_local()
        || v4.is_unspecified()
        || v4.is_broadcast()
        || v4.is_multicast()
        || (a == 100 && (64..=127).contains(&b))
        || a >= 240
        || (a == 192 && b == 0 && (c == 0 || c == 2))
        || (a == 198 && b == 51)
        || (a == 203 && b == 0)
        || (a == 198 && (18..=19).contains(&b))
}

fn is_non_global_v6(v6: std::net::Ipv6Addr) -> bool {
    let segs = v6.segments();
    v6.is_loopback()
        || v6.is_unspecified()
        || v6.is_multicast()
        || (segs[0] & 0xfe00) == 0xfc00
        || (segs[0] & 0xffc0) == 0xfe80
        || (segs[0] == 0x2001 && segs[1] == 0x0db8)
        || v6.to_ipv4_mapped().is_some_and(is_non_global_v4)
}

/// Body markers that indicate a CAPTCHA or JavaScript challenge page.
const CHALLENGE_MARKERS: &[&str] = &[
    "cf-chl",
    "challenge-platform",
    "_cf_chl",
    "hcaptcha",
    "h-captcha",
    "g-recaptcha",
    "recaptcha",
    "turnstile",
];

/// Cloudflare-specific body keywords found in interstitial block pages.
const CF_BODY_KEYWORDS: &[&str] = &[
    "just a moment",
    "checking your browser",
    "cloudflare",
    "cf-browser-verification",
];

/// Detects bot-blocking responses that require a real browser (WebView) to solve.
///
/// Returns `true` when the combination of HTTP status, response headers, and
/// body content suggests the server returned a CAPTCHA or JavaScript challenge
/// instead of the real page content.
pub(crate) fn needs_webview_escalation(
    status: u16,
    headers: &[(String, String)],
    body: &str,
) -> bool {
    let body_lower = body.to_lowercase();

    if status == 403 && CF_BODY_KEYWORDS.iter().any(|kw| body_lower.contains(kw)) {
        return true;
    }

    if status == 503
        && headers
            .iter()
            .any(|(name, _)| name.to_lowercase().starts_with("cf-"))
    {
        return true;
    }

    if CHALLENGE_MARKERS
        .iter()
        .any(|marker| body_lower.contains(marker))
    {
        return true;
    }

    let plain = nanohtml2text::html2text(body);
    let visible_chars: usize = plain.chars().filter(|c| !c.is_whitespace()).count();
    let has_script_or_noscript = body_lower.contains("<script") || body_lower.contains("<noscript");
    if has_script_or_noscript && visible_chars < 50 {
        return true;
    }

    false
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::security::{AutonomyLevel, SecurityPolicy};

    fn test_tool(allowed_domains: Vec<&str>) -> WebFetchTool {
        test_tool_with_blocklist(allowed_domains, vec![])
    }

    fn test_tool_with_blocklist(
        allowed_domains: Vec<&str>,
        blocked_domains: Vec<&str>,
    ) -> WebFetchTool {
        let security = Arc::new(SecurityPolicy {
            autonomy: AutonomyLevel::Supervised,
            ..SecurityPolicy::default()
        });
        WebFetchTool::new(
            security,
            allowed_domains.into_iter().map(String::from).collect(),
            blocked_domains.into_iter().map(String::from).collect(),
            500_000,
            30,
        )
    }

    #[test]
    fn name_is_web_fetch() {
        let tool = test_tool(vec!["example.com"]);
        assert_eq!(tool.name(), "web_fetch");
    }

    #[test]
    fn parameters_schema_requires_url() {
        let tool = test_tool(vec!["example.com"]);
        let schema = tool.parameters_schema();
        assert!(schema["properties"]["url"].is_object());
        let required = schema["required"].as_array().unwrap();
        assert!(required.iter().any(|v| v.as_str() == Some("url")));
    }

    #[test]
    fn html_to_text_conversion() {
        let html = "<html><body><h1>Title</h1><p>Hello <b>world</b></p></body></html>";
        let text = nanohtml2text::html2text(html);
        assert!(text.contains("Title"));
        assert!(text.contains("Hello"));
        assert!(text.contains("world"));
        assert!(!text.contains("<h1>"));
        assert!(!text.contains("<p>"));
    }

    #[test]
    fn html_to_markdown_conversion() {
        let html = "<html><body><h1>Title</h1><p>Hello <b>world</b></p><ul><li>one</li><li>two</li></ul></body></html>";
        let converter = htmd::HtmlToMarkdown::builder()
            .skip_tags(vec![
                "script", "style", "noscript", "nav", "footer", "header",
                "iframe", "ins", "svg",
            ])
            .build();
        let md = converter.convert(html).unwrap();
        assert!(md.contains("# Title"), "Expected markdown heading, got: {md}");
        assert!(md.contains("**world**"), "Expected bold markdown, got: {md}");
        assert!(md.contains("one"), "Expected list item, got: {md}");
    }

    #[test]
    fn html_to_markdown_strips_nav_and_ads() {
        let html = r#"<html><body>
            <nav>Menu</nav>
            <h1>Article</h1>
            <p>Content here</p>
            <ins class="adsbygoogle">Ad</ins>
            <footer>Footer</footer>
        </body></html>"#;
        let converter = htmd::HtmlToMarkdown::builder()
            .skip_tags(vec![
                "script", "style", "noscript", "nav", "footer", "header",
                "iframe", "ins", "svg",
            ])
            .build();
        let md = converter.convert(html).unwrap();
        assert!(md.contains("Article"), "Expected article heading, got: {md}");
        assert!(md.contains("Content here"), "Expected content, got: {md}");
        assert!(!md.contains("Menu"), "Nav should be stripped, got: {md}");
        assert!(!md.contains("Footer"), "Footer should be stripped, got: {md}");
        assert!(!md.contains("Ad"), "Ad should be stripped, got: {md}");
    }

    #[test]
    fn validate_accepts_exact_domain() {
        let tool = test_tool(vec!["example.com"]);
        let got = tool.validate_url("https://example.com/page").unwrap();
        assert_eq!(got, "https://example.com/page");
    }

    #[test]
    fn validate_accepts_subdomain() {
        let tool = test_tool(vec!["example.com"]);
        assert!(tool.validate_url("https://docs.example.com/guide").is_ok());
    }

    #[test]
    fn validate_accepts_wildcard() {
        let tool = test_tool(vec!["*"]);
        assert!(tool.validate_url("https://news.ycombinator.com").is_ok());
    }

    #[test]
    fn validate_rejects_empty_url() {
        let tool = test_tool(vec!["example.com"]);
        let err = tool.validate_url("").unwrap_err().to_string();
        assert!(err.contains("empty"));
    }

    #[test]
    fn validate_rejects_missing_url() {
        let tool = test_tool(vec!["example.com"]);
        let err = tool.validate_url("  ").unwrap_err().to_string();
        assert!(err.contains("empty"));
    }

    #[test]
    fn validate_rejects_ftp_scheme() {
        let tool = test_tool(vec!["example.com"]);
        let err = tool
            .validate_url("ftp://example.com")
            .unwrap_err()
            .to_string();
        assert!(err.contains("http://") || err.contains("https://"));
    }

    #[test]
    fn validate_rejects_allowlist_miss() {
        let tool = test_tool(vec!["example.com"]);
        let err = tool
            .validate_url("https://google.com")
            .unwrap_err()
            .to_string();
        assert!(err.contains("allowed_domains"));
    }

    #[test]
    fn validate_requires_allowlist() {
        let security = Arc::new(SecurityPolicy::default());
        let tool = WebFetchTool::new(security, vec![], vec![], 500_000, 30);
        let err = tool
            .validate_url("https://example.com")
            .unwrap_err()
            .to_string();
        assert!(err.contains("allowed_domains"));
    }

    #[test]
    fn ssrf_blocks_localhost() {
        let tool = test_tool(vec!["localhost"]);
        let err = tool
            .validate_url("https://localhost:8080")
            .unwrap_err()
            .to_string();
        assert!(err.contains("local/private"));
    }

    #[test]
    fn ssrf_blocks_private_ipv4() {
        let tool = test_tool(vec!["192.168.1.5"]);
        let err = tool
            .validate_url("https://192.168.1.5")
            .unwrap_err()
            .to_string();
        assert!(err.contains("local/private"));
    }

    #[test]
    fn ssrf_blocks_loopback() {
        assert!(is_private_or_local_host("127.0.0.1"));
        assert!(is_private_or_local_host("127.0.0.2"));
    }

    #[test]
    fn ssrf_blocks_rfc1918() {
        assert!(is_private_or_local_host("10.0.0.1"));
        assert!(is_private_or_local_host("172.16.0.1"));
        assert!(is_private_or_local_host("192.168.1.1"));
    }

    #[test]
    fn ssrf_wildcard_still_blocks_private() {
        let tool = test_tool(vec!["*"]);
        let err = tool
            .validate_url("https://localhost:8080")
            .unwrap_err()
            .to_string();
        assert!(err.contains("local/private"));
    }

    #[test]
    fn redirect_target_validation_allows_permitted_host() {
        let allowed = vec!["example.com".to_string()];
        let blocked = vec![];
        assert!(validate_target_url(
            "https://docs.example.com/page",
            &allowed,
            &blocked,
            "web_fetch"
        )
        .is_ok());
    }

    #[test]
    fn redirect_target_validation_blocks_private_host() {
        let allowed = vec!["example.com".to_string()];
        let blocked = vec![];
        let err = validate_target_url("https://127.0.0.1/admin", &allowed, &blocked, "web_fetch")
            .unwrap_err()
            .to_string();
        assert!(err.contains("local/private"));
    }

    #[test]
    fn redirect_target_validation_blocks_blocklisted_host() {
        let allowed = vec!["*".to_string()];
        let blocked = vec!["evil.com".to_string()];
        let err = validate_target_url("https://evil.com/phish", &allowed, &blocked, "web_fetch")
            .unwrap_err()
            .to_string();
        assert!(err.contains("blocked_domains"));
    }

    #[tokio::test]
    async fn blocks_readonly_mode() {
        let security = Arc::new(SecurityPolicy {
            autonomy: AutonomyLevel::ReadOnly,
            ..SecurityPolicy::default()
        });
        let tool = WebFetchTool::new(security, vec!["example.com".into()], vec![], 500_000, 30);
        let result = tool
            .execute(json!({"url": "https://example.com"}))
            .await
            .unwrap();
        assert!(!result.success);
        assert!(result.error.unwrap().contains("read-only"));
    }

    #[tokio::test]
    async fn blocks_rate_limited() {
        let security = Arc::new(SecurityPolicy {
            max_actions_per_hour: 0,
            ..SecurityPolicy::default()
        });
        let tool = WebFetchTool::new(security, vec!["example.com".into()], vec![], 500_000, 30);
        let result = tool
            .execute(json!({"url": "https://example.com"}))
            .await
            .unwrap();
        assert!(!result.success);
        assert!(result.error.unwrap().contains("rate limit"));
    }

    #[test]
    fn truncate_within_limit() {
        let tool = test_tool(vec!["example.com"]);
        let text = "hello world";
        assert_eq!(tool.truncate_response(text), "hello world");
    }

    #[test]
    fn truncate_over_limit() {
        let tool = WebFetchTool::new(
            Arc::new(SecurityPolicy::default()),
            vec!["example.com".into()],
            vec![],
            10,
            30,
        );
        let text = "hello world this is long";
        let truncated = tool.truncate_response(text);
        assert!(truncated.contains("[Response truncated"));
    }

    #[test]
    fn normalize_domain_strips_scheme_and_case() {
        let got = normalize_domain("  HTTPS://Docs.Example.com/path ").unwrap();
        assert_eq!(got, "docs.example.com");
    }

    #[test]
    fn normalize_deduplicates() {
        let got = normalize_allowed_domains(vec![
            "example.com".into(),
            "EXAMPLE.COM".into(),
            "https://example.com/".into(),
        ]);
        assert_eq!(got, vec!["example.com".to_string()]);
    }

    #[test]
    fn blocklist_rejects_exact_match() {
        let tool = test_tool_with_blocklist(vec!["*"], vec!["evil.com"]);
        let err = tool
            .validate_url("https://evil.com/page")
            .unwrap_err()
            .to_string();
        assert!(err.contains("blocked_domains"));
    }

    #[test]
    fn blocklist_rejects_subdomain() {
        let tool = test_tool_with_blocklist(vec!["*"], vec!["evil.com"]);
        let err = tool
            .validate_url("https://api.evil.com/v1")
            .unwrap_err()
            .to_string();
        assert!(err.contains("blocked_domains"));
    }

    #[test]
    fn blocklist_wins_over_allowlist() {
        let tool = test_tool_with_blocklist(vec!["evil.com"], vec!["evil.com"]);
        let err = tool
            .validate_url("https://evil.com")
            .unwrap_err()
            .to_string();
        assert!(err.contains("blocked_domains"));
    }

    #[test]
    fn blocklist_allows_non_blocked() {
        let tool = test_tool_with_blocklist(vec!["*"], vec!["evil.com"]);
        assert!(tool.validate_url("https://example.com").is_ok());
    }

    #[test]
    fn append_chunk_with_cap_truncates_and_stops() {
        let mut buffer = Vec::new();
        assert!(!append_chunk_with_cap(&mut buffer, b"hello", 8));
        assert!(append_chunk_with_cap(&mut buffer, b"world", 8));
        assert_eq!(buffer, b"hellowor");
    }

    #[test]
    fn resolved_private_ip_is_rejected() {
        let ips = vec!["127.0.0.1".parse().unwrap()];
        let err = validate_resolved_ips_are_public("example.com", &ips)
            .unwrap_err()
            .to_string();
        assert!(err.contains("non-global address"));
    }

    #[test]
    fn resolved_mixed_ips_are_rejected() {
        let ips = vec![
            "93.184.216.34".parse().unwrap(),
            "10.0.0.1".parse().unwrap(),
        ];
        let err = validate_resolved_ips_are_public("example.com", &ips)
            .unwrap_err()
            .to_string();
        assert!(err.contains("non-global address"));
    }

    #[test]
    fn resolved_public_ips_are_allowed() {
        let ips = vec!["93.184.216.34".parse().unwrap(), "1.1.1.1".parse().unwrap()];
        assert!(validate_resolved_ips_are_public("example.com", &ips).is_ok());
    }

    #[test]
    fn detects_cloudflare_403() {
        let body = r#"<html><head><title>Just a moment...</title></head>
            <body><div class="cf-chl-widget">Please wait</div></body></html>"#;
        assert!(needs_webview_escalation(403, &[], body));
    }

    #[test]
    fn detects_cloudflare_503() {
        let headers = vec![
            ("cf-ray".to_string(), "abc123-IAD".to_string()),
            ("content-type".to_string(), "text/html".to_string()),
        ];
        let body = "<html><body>Service Temporarily Unavailable</body></html>";
        assert!(needs_webview_escalation(503, &headers, body));
    }

    #[test]
    fn detects_js_challenge_page() {
        let body = r#"<html><head></head><body>
            <script>document.cookie="__cf_bm=abc";</script>
            <noscript>Enable JavaScript to continue.</noscript>
            </body></html>"#;
        assert!(needs_webview_escalation(200, &[], body));
    }

    #[test]
    fn detects_captcha_markers() {
        let body = r#"<html><body>
            <div class="h-captcha" data-sitekey="abc123"></div>
            </body></html>"#;
        assert!(needs_webview_escalation(200, &[], body));
    }

    #[test]
    fn no_escalation_for_normal_page() {
        let body = r#"<html><head><title>My Blog</title></head>
            <body><h1>Welcome to my blog</h1>
            <p>This is a perfectly normal web page with plenty of visible content
            that should not trigger any escalation detection whatsoever.</p>
            </body></html>"#;
        assert!(!needs_webview_escalation(200, &[], body));
    }

    #[test]
    fn no_escalation_for_normal_403() {
        let body = r#"<html><body>
            <h1>403 Forbidden</h1>
            <p>You don't have permission to access this resource.
            Please contact the administrator if you believe this is an error.</p>
            </body></html>"#;
        assert!(!needs_webview_escalation(403, &[], body));
    }

    /// Mock WebView fallback that returns a fixed string.
    struct MockFallback(String);

    impl WebViewFallback for MockFallback {
        fn render_page(&self, _url: &str, _timeout_ms: u64) -> Result<String, String> {
            Ok(self.0.clone())
        }
    }

    /// Mock fallback that always fails.
    struct FailingFallback;

    impl WebViewFallback for FailingFallback {
        fn render_page(&self, _url: &str, _timeout_ms: u64) -> Result<String, String> {
            Err("CAPTCHA required".to_string())
        }
    }

    #[test]
    fn webview_fallback_field_defaults_to_none() {
        let tool = test_tool(vec!["example.com"]);
        assert!(tool.webview_fallback.is_none());
    }

    #[test]
    fn with_webview_fallback_sets_field() {
        let tool = test_tool(vec!["example.com"])
            .with_webview_fallback(Arc::new(MockFallback("hello".into())));
        assert!(tool.webview_fallback.is_some());
    }

    #[test]
    fn effective_user_agent_returns_fallback_when_unset() {
        // GLOBAL_USER_AGENT is an OnceLock — can only be set once per process.
        // In tests it may already be set by another test. Just verify it returns
        // a non-empty string.
        let ua = effective_user_agent();
        assert!(!ua.is_empty());
        assert!(ua.contains("Mozilla/5.0"));
    }
}
