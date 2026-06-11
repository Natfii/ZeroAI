// Copyright (c) 2026 @Natfii. All rights reserved.

//! Generic engine executor: turns an [`EngineSpec`] plus a query into
//! normalized search results.
//!
//! All HTML and JSON parsing happens in synchronous helpers so the async
//! request future stays `Send` (`scraper::Html` is `!Send` and must never
//! live across an await point).

use super::spec::{EngineSpec, HtmlResponseSpec, JsonResponseSpec, ResponseKind, TokenSpec};
use std::sync::OnceLock;
use std::time::Duration;

/// Default User-Agent for HTML engines that block non-browser clients.
pub const DEFAULT_BROWSER_USER_AGENT: &str = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

/// Minimum response body size for a zero-result parse to be classified as a
/// layout break ([`EngineFailure::ZeroParse`]) instead of a legitimately
/// empty result page.
pub const MIN_LAYOUT_BREAK_BODY_BYTES: usize = 2048;

/// One extracted search result, before cross-engine fusion.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EngineResult {
    /// Result title, tag-stripped and whitespace-collapsed.
    pub title: String,
    /// Absolute http(s) result URL, redirect-unwrapped.
    pub url: String,
    /// Result snippet; may be empty when the engine provides none.
    pub snippet: String,
}

/// Classified engine failure. The distinction drives health bookkeeping:
/// only [`EngineFailure::ZeroParse`] is evidence of a layout break that the
/// repair pipeline can fix; the others are transient or configuration issues.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum EngineFailure {
    /// Connection, timeout, or non-block HTTP error.
    Network(String),
    /// Anti-bot rejection: HTTP 403/429 or a block-marker match in the body.
    Blocked(String),
    /// Healthy-looking response that yielded zero results — the layout-break
    /// signature (also covers token pages whose extract pattern stopped
    /// matching and JSON bodies that no longer parse).
    ZeroParse {
        /// Size of the body that failed to parse.
        body_bytes: usize,
    },
    /// The spec itself is unusable (should be caught by load-time validation).
    BadSpec(String),
}

impl std::fmt::Display for EngineFailure {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Network(detail) => write!(f, "network error: {detail}"),
            Self::Blocked(detail) => write!(f, "blocked by engine: {detail}"),
            Self::ZeroParse { body_bytes } => {
                write!(f, "no results parsed from a {body_bytes}-byte response")
            }
            Self::BadSpec(detail) => write!(f, "invalid engine spec: {detail}"),
        }
    }
}

/// Runs one engine end-to-end: optional token pre-fetch, search request,
/// block detection, and result extraction.
pub async fn run_engine(
    spec: &EngineSpec,
    query: &str,
    max_results: usize,
    timeout_secs: u64,
) -> Result<Vec<EngineResult>, EngineFailure> {
    let client = build_client(spec, timeout_secs)?;
    let token = match &spec.request.token {
        Some(token_spec) => Some(fetch_token(&client, token_spec, query).await?),
        None => None,
    };
    let url = build_request_url(&spec.request.url, query, max_results, token.as_deref());

    let response = client
        .get(&url)
        .send()
        .await
        .map_err(|e| EngineFailure::Network(e.to_string()))?;
    let status = response.status();
    if status == reqwest::StatusCode::FORBIDDEN || status == reqwest::StatusCode::TOO_MANY_REQUESTS
    {
        return Err(EngineFailure::Blocked(format!("HTTP {status}")));
    }
    if !status.is_success() {
        return Err(EngineFailure::Network(format!("HTTP {status}")));
    }

    let body = response
        .text()
        .await
        .map_err(|e| EngineFailure::Network(e.to_string()))?;
    for marker in &spec.block_markers {
        if contains_ascii_case_insensitive(&body, marker) {
            return Err(EngineFailure::Blocked(format!("block marker '{marker}'")));
        }
    }

    let results = parse_response(spec, &body, max_results)?;
    if results.is_empty() && body.len() >= MIN_LAYOUT_BREAK_BODY_BYTES {
        return Err(EngineFailure::ZeroParse {
            body_bytes: body.len(),
        });
    }
    Ok(results)
}

/// Extracts results from a response body according to the spec's kind.
/// Synchronous on purpose — see the module docs on `Send`.
pub fn parse_response(
    spec: &EngineSpec,
    body: &str,
    max_results: usize,
) -> Result<Vec<EngineResult>, EngineFailure> {
    match spec.response.kind {
        ResponseKind::Html => {
            let html = spec.response.html.as_ref().ok_or_else(|| {
                EngineFailure::BadSpec("kind is html but [response.html] missing".into())
            })?;
            parse_html_response(html, body, max_results)
        }
        ResponseKind::Json => {
            let json = spec.response.json.as_ref().ok_or_else(|| {
                EngineFailure::BadSpec("kind is json but [response.json] missing".into())
            })?;
            parse_json_response(json, body, max_results)
        }
    }
}

fn build_client(spec: &EngineSpec, timeout_secs: u64) -> Result<reqwest::Client, EngineFailure> {
    let user_agent = spec
        .request
        .user_agent
        .as_deref()
        .unwrap_or(DEFAULT_BROWSER_USER_AGENT);
    let mut headers = reqwest::header::HeaderMap::new();
    for (name, value) in &spec.request.headers {
        let name = reqwest::header::HeaderName::from_bytes(name.as_bytes())
            .map_err(|e| EngineFailure::BadSpec(format!("header name '{name}': {e}")))?;
        let value = reqwest::header::HeaderValue::from_str(value)
            .map_err(|e| EngineFailure::BadSpec(format!("header value for {name}: {e}")))?;
        headers.insert(name, value);
    }
    let builder = reqwest::Client::builder()
        .timeout(Duration::from_secs(timeout_secs.max(1)))
        .user_agent(user_agent)
        .default_headers(headers);
    let builder =
        zeroclaw_config::schema::apply_runtime_proxy_to_builder(builder, "tool.web_search");
    builder
        .build()
        .map_err(|e| EngineFailure::Network(e.to_string()))
}

/// Fetches the token page and regex-extracts the anti-bot token.
async fn fetch_token(
    client: &reqwest::Client,
    token_spec: &TokenSpec,
    query: &str,
) -> Result<String, EngineFailure> {
    let url = token_spec
        .url
        .replace("{query}", &urlencoding::encode(query));
    let response = client
        .get(&url)
        .send()
        .await
        .map_err(|e| EngineFailure::Network(e.to_string()))?;
    if !response.status().is_success() {
        return Err(EngineFailure::Network(format!(
            "token fetch HTTP {}",
            response.status()
        )));
    }
    let body = response
        .text()
        .await
        .map_err(|e| EngineFailure::Network(e.to_string()))?;
    let pattern = regex::Regex::new(&token_spec.extract_pattern)
        .map_err(|e| EngineFailure::BadSpec(format!("token extract_pattern: {e}")))?;
    let captures = pattern.captures(&body).ok_or(EngineFailure::ZeroParse {
        body_bytes: body.len(),
    })?;
    let token = captures
        .get(1)
        .map(|m| m.as_str().to_owned())
        .ok_or_else(|| EngineFailure::BadSpec("token pattern lacks capture group".into()))?;
    Ok(token)
}

fn build_request_url(
    template: &str,
    query: &str,
    max_results: usize,
    token: Option<&str>,
) -> String {
    let mut url = template
        .replace("{query}", &urlencoding::encode(query))
        .replace("{max_results}", &max_results.to_string());
    if let Some(token) = token {
        url = url.replace("{token}", &urlencoding::encode(token));
    }
    url
}

fn parse_html_response(
    spec: &HtmlResponseSpec,
    body: &str,
    max_results: usize,
) -> Result<Vec<EngineResult>, EngineFailure> {
    let result_selector = parse_selector(&spec.result_selector)?;
    let title_selector = parse_selector(&spec.title_selector)?;
    let url_selector = parse_selector(&spec.url_selector)?;
    let snippet_selector = spec
        .snippet_selector
        .as_deref()
        .map(parse_selector)
        .transpose()?;

    let document = scraper::Html::parse_document(body);
    let mut results = Vec::new();
    for element in document.select(&result_selector) {
        let Some(title_element) = element.select(&title_selector).next() else {
            continue;
        };
        let title = collapse_whitespace(&title_element.text().collect::<String>());
        let Some(raw_url) = element
            .select(&url_selector)
            .next()
            .and_then(|link| link.value().attr(spec.url_attribute.as_str()))
        else {
            continue;
        };
        let url = normalize_extracted_url(raw_url, spec.url_unwrap_param.as_deref());
        if title.is_empty() || !is_http_url(&url) {
            continue;
        }
        let snippet = snippet_selector
            .as_ref()
            .and_then(|selector| element.select(selector).next())
            .map(|node| collapse_whitespace(&node.text().collect::<String>()))
            .unwrap_or_default();
        results.push(EngineResult {
            title,
            url,
            snippet,
        });
        if results.len() >= max_results {
            break;
        }
    }
    Ok(results)
}

fn parse_selector(raw: &str) -> Result<scraper::Selector, EngineFailure> {
    scraper::Selector::parse(raw)
        .map_err(|e| EngineFailure::BadSpec(format!("selector '{raw}': {e}")))
}

fn parse_json_response(
    spec: &JsonResponseSpec,
    body: &str,
    max_results: usize,
) -> Result<Vec<EngineResult>, EngineFailure> {
    let root: serde_json::Value =
        serde_json::from_str(body).map_err(|_| EngineFailure::ZeroParse {
            body_bytes: body.len(),
        })?;
    let items = root
        .pointer(&spec.results_pointer)
        .and_then(|v| v.as_array())
        .ok_or(EngineFailure::ZeroParse {
            body_bytes: body.len(),
        })?;

    let mut results = Vec::new();
    for item in items {
        let Some(title) = item.pointer(&spec.title_pointer).and_then(|v| v.as_str()) else {
            continue;
        };
        let title = collapse_whitespace(&strip_tags(title));
        let url = match (&spec.url_pointer, &spec.url_template) {
            (Some(pointer), _) => item
                .pointer(pointer)
                .and_then(|v| v.as_str())
                .map(str::to_owned),
            (None, Some(template)) => spec
                .url_template_value_pointer
                .as_ref()
                .and_then(|pointer| item.pointer(pointer))
                .and_then(|v| v.as_str())
                .map(|value| template.replace("{value}", value)),
            (None, None) => None,
        };
        let Some(url) = url else { continue };
        if title.is_empty() || !is_http_url(&url) {
            continue;
        }
        let snippet = spec
            .snippet_pointer
            .as_ref()
            .and_then(|pointer| item.pointer(pointer))
            .and_then(|v| v.as_str())
            .map(|raw| collapse_whitespace(&strip_tags(raw)))
            .unwrap_or_default();
        results.push(EngineResult {
            title,
            url,
            snippet,
        });
        if results.len() >= max_results {
            break;
        }
    }
    Ok(results)
}

/// Unwraps redirect-style hrefs (`/l/?uddg=<real-url>`) and fixes
/// scheme-relative URLs.
fn normalize_extracted_url(raw: &str, unwrap_param: Option<&str>) -> String {
    let unwrapped = match unwrap_param {
        Some(param) => unwrap_redirect_param(raw, param),
        None => raw.to_owned(),
    };
    if let Some(rest) = unwrapped.strip_prefix("//") {
        return format!("https://{rest}");
    }
    unwrapped
}

fn unwrap_redirect_param(raw_url: &str, param: &str) -> String {
    let needle = format!("{param}=");
    if let Some(index) = raw_url.find(&needle) {
        let encoded = &raw_url[index + needle.len()..];
        let encoded = encoded.split('&').next().unwrap_or(encoded);
        if let Ok(decoded) = urlencoding::decode(encoded) {
            return decoded.into_owned();
        }
    }
    raw_url.to_owned()
}

fn is_http_url(url: &str) -> bool {
    url.starts_with("https://") || url.starts_with("http://")
}

fn collapse_whitespace(raw: &str) -> String {
    raw.split_whitespace().collect::<Vec<_>>().join(" ")
}

fn strip_tags(raw: &str) -> String {
    static TAG_PATTERN: OnceLock<regex::Regex> = OnceLock::new();
    let pattern = TAG_PATTERN
        .get_or_init(|| regex::Regex::new(r"<[^>]+>").expect("literal regex pattern must compile"));
    pattern.replace_all(raw, "").into_owned()
}

/// Case-insensitive ASCII substring scan without allocating a lowered copy.
pub(crate) fn contains_ascii_case_insensitive(haystack: &str, needle: &str) -> bool {
    !needle.is_empty()
        && haystack
            .as_bytes()
            .windows(needle.len())
            .any(|window| window.eq_ignore_ascii_case(needle.as_bytes()))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::metasearch::spec::bundled_specs;

    const DDG_FIXTURE: &str = include_str!("fixtures/ddg_html.html");
    const DDG_BLOCK_FIXTURE: &str = include_str!("fixtures/ddg_anomaly_block.html");
    const MOJEEK_FIXTURE: &str = include_str!("fixtures/mojeek.html");
    const WIKIPEDIA_FIXTURE: &str = include_str!("fixtures/wikipedia.json");
    const MARGINALIA_FIXTURE: &str = include_str!("fixtures/marginalia.json");

    fn spec(id: &str) -> EngineSpec {
        bundled_specs()
            .unwrap()
            .into_iter()
            .find(|s| s.id == id)
            .unwrap()
    }

    #[test]
    fn ddg_fixture_parses_with_bundled_spec() {
        let results = parse_response(&spec("ddg_html"), DDG_FIXTURE, 10).unwrap();
        assert!(results.len() >= 5, "got {} results", results.len());
        assert!(results.iter().all(|r| is_http_url(&r.url)));
        assert!(results.iter().all(|r| !r.url.contains("uddg=")));
        assert!(results.iter().any(|r| r.url.contains("rust-lang.org")));
        assert!(results.iter().any(|r| !r.snippet.is_empty()));
    }

    #[test]
    fn mojeek_fixture_parses_with_bundled_spec() {
        let results = parse_response(&spec("mojeek"), MOJEEK_FIXTURE, 10).unwrap();
        assert!(results.len() >= 5, "got {} results", results.len());
        assert!(results.iter().any(|r| r.url.contains("rust-lang.org")));
        assert!(results.iter().any(|r| !r.snippet.is_empty()));
    }

    #[test]
    fn wikipedia_fixture_parses_with_bundled_spec() {
        let results = parse_response(&spec("wikipedia"), WIKIPEDIA_FIXTURE, 10).unwrap();
        assert_eq!(results.len(), 5);
        assert!(
            results
                .iter()
                .all(|r| r.url.starts_with("https://en.wikipedia.org/wiki/"))
        );
        assert!(
            results.iter().all(|r| !r.snippet.contains('<')),
            "excerpt highlight spans must be stripped"
        );
    }

    #[test]
    fn marginalia_fixture_parses_with_bundled_spec() {
        let results = parse_response(&spec("marginalia"), MARGINALIA_FIXTURE, 10).unwrap();
        assert_eq!(results.len(), 5);
        assert!(results.iter().all(|r| r.url.starts_with("https://")));
        assert!(results.iter().all(|r| !r.title.is_empty()));
    }

    #[test]
    fn anomaly_fixture_contains_bundled_block_marker() {
        let ddg = spec("ddg_html");
        assert!(
            ddg.block_markers
                .iter()
                .any(|marker| contains_ascii_case_insensitive(DDG_BLOCK_FIXTURE, marker)),
            "the captured anomaly page must trip a bundled block marker"
        );
    }

    #[test]
    fn zero_parse_requires_substantial_body() {
        let tiny = "<html><body>nothing</body></html>";
        let results = parse_response(&spec("ddg_html"), tiny, 10).unwrap();
        assert!(results.is_empty());
        assert!(tiny.len() < MIN_LAYOUT_BREAK_BODY_BYTES);
    }

    #[test]
    fn unwrap_redirect_param_decodes_nested_url() {
        let raw = "//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fpath%3Fa%3D1&rut=abc";
        assert_eq!(
            unwrap_redirect_param(raw, "uddg"),
            "https://example.com/path?a=1"
        );
    }

    #[test]
    fn normalize_extracted_url_fixes_scheme_relative() {
        assert_eq!(
            normalize_extracted_url("//example.com/page", None),
            "https://example.com/page"
        );
    }

    #[test]
    fn strip_tags_removes_highlight_spans() {
        assert_eq!(
            strip_tags(r#"<span class="searchmatch">Rust</span> is great"#),
            "Rust is great"
        );
    }

    #[test]
    fn build_request_url_substitutes_placeholders() {
        let url = build_request_url(
            "https://example.com/search?q={query}&n={max_results}&v={token}",
            "rust lang",
            5,
            Some("123-456"),
        );
        assert_eq!(
            url,
            "https://example.com/search?q=rust%20lang&n=5&v=123-456"
        );
    }

    fn mock_html_spec(server_url: &str) -> EngineSpec {
        let mut spec = spec("ddg_html");
        spec.request.url = format!("{server_url}/html/?q={{query}}");
        spec
    }

    #[tokio::test]
    async fn run_engine_classifies_http_403_as_blocked() {
        use wiremock::matchers::{method, path};
        use wiremock::{Mock, MockServer, ResponseTemplate};

        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/html/"))
            .respond_with(ResponseTemplate::new(403))
            .mount(&server)
            .await;

        let spec = mock_html_spec(&server.uri());
        let err = run_engine(&spec, "test", 5, 5).await.unwrap_err();
        assert!(matches!(err, EngineFailure::Blocked(_)), "got {err:?}");
    }

    #[tokio::test]
    async fn run_engine_classifies_block_marker_body_as_blocked() {
        use wiremock::matchers::{method, path};
        use wiremock::{Mock, MockServer, ResponseTemplate};

        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/html/"))
            .respond_with(ResponseTemplate::new(202).set_body_string(DDG_BLOCK_FIXTURE))
            .mount(&server)
            .await;

        let spec = mock_html_spec(&server.uri());
        let err = run_engine(&spec, "test", 5, 5).await.unwrap_err();
        assert!(matches!(err, EngineFailure::Blocked(_)), "got {err:?}");
    }

    #[tokio::test]
    async fn run_engine_classifies_big_unparseable_body_as_zero_parse() {
        use wiremock::matchers::{method, path};
        use wiremock::{Mock, MockServer, ResponseTemplate};

        let server = MockServer::start().await;
        let redesigned = format!(
            "<html><body>{}</body></html>",
            "<div class=\"totally-new-layout\">entry</div>".repeat(200)
        );
        Mock::given(method("GET"))
            .and(path("/html/"))
            .respond_with(ResponseTemplate::new(200).set_body_string(redesigned))
            .mount(&server)
            .await;

        let spec = mock_html_spec(&server.uri());
        let err = run_engine(&spec, "test", 5, 5).await.unwrap_err();
        assert!(matches!(err, EngineFailure::ZeroParse { .. }), "got {err:?}");
    }

    #[tokio::test]
    async fn run_engine_resolves_token_before_search() {
        use wiremock::matchers::{method, path, query_param};
        use wiremock::{Mock, MockServer, ResponseTemplate};

        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/token"))
            .respond_with(ResponseTemplate::new(200).set_body_string("vqd=4-987654321&other"))
            .mount(&server)
            .await;
        Mock::given(method("GET"))
            .and(path("/html/"))
            .and(query_param("vqd", "4-987654321"))
            .respond_with(ResponseTemplate::new(200).set_body_string(DDG_FIXTURE))
            .mount(&server)
            .await;

        let mut spec = spec("ddg_html");
        spec.request.url = format!("{}/html/?q={{query}}&vqd={{token}}", server.uri());
        spec.request.token = Some(TokenSpec {
            url: format!("{}/token", server.uri()),
            extract_pattern: r"vqd=([\d-]+)".into(),
        });

        let results = run_engine(&spec, "test", 5, 5).await.unwrap();
        assert!(!results.is_empty());
    }
}
