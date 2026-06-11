use super::web_search_provider_routing::{WebSearchProviderRoute, resolve_web_search_provider};
use crate::metasearch::{DDG_ENGINE_ID, MetaSearcher};
use async_trait::async_trait;
use serde_json::json;
use std::path::{Path, PathBuf};
use std::time::Duration;
use zeroclaw_api::tool::{Tool, ToolResult};

/// Web search tool for searching the internet.
/// Supports multiple model_providers: Meta (default; keyless on-device
/// multi-engine search), DuckDuckGo (the bundled meta engine running solo,
/// with the same health backoff and self-repair), Brave (requires API key),
/// Tavily (requires API key), SearXNG (self-hosted, requires instance URL).
///
/// API keys are resolved lazily at execution time: if the boot-time key
/// is missing or still encrypted, the tool re-reads `config.toml`, decrypts the
/// corresponding `[web_search]` field, and uses the result. This ensures that
/// keys set or rotated after boot, and encrypted keys, are correctly picked up.
pub struct WebSearchTool {
    /// ModelProvider selector as configured by user. Routed via model_provider aliases at runtime.
    model_provider: String,
    /// Boot-time key snapshot (may be `None` if not yet configured at startup).
    boot_brave_api_key: Option<String>,
    /// Boot-time Tavily key snapshot.
    boot_tavily_api_key: Option<String>,
    /// SearXNG instance base URL (e.g. `"https://searx.example.com"`).
    searxng_instance_url: Option<String>,
    max_results: usize,
    timeout_secs: u64,
    /// Path to `config.toml` for lazy re-read of keys at execution time.
    config_path: PathBuf,
    /// Whether secret encryption is enabled (needed to create a `SecretStore`).
    secrets_encrypt: bool,
    /// On-device multi-engine searcher backing the default `meta` provider.
    meta: MetaSearcher,
}

/// Fallback meta-search rate limit for the legacy [`WebSearchTool::new`]
/// constructor; mirrors the `[web_search] max_requests_per_minute` default.
const DEFAULT_META_REQUESTS_PER_MINUTE: u32 = 10;

impl WebSearchTool {
    pub fn new(
        model_provider: String,
        brave_api_key: Option<String>,
        max_results: usize,
        timeout_secs: u64,
    ) -> Self {
        Self {
            model_provider: model_provider.trim().to_lowercase(),
            boot_brave_api_key: brave_api_key,
            boot_tavily_api_key: None,
            searxng_instance_url: None,
            max_results: max_results.clamp(1, 10),
            timeout_secs: timeout_secs.max(1),
            config_path: PathBuf::new(),
            secrets_encrypt: false,
            meta: MetaSearcher::new(
                None,
                DEFAULT_META_REQUESTS_PER_MINUTE,
                timeout_secs.max(1),
                None,
            ),
        }
    }

    /// Create a `WebSearchTool` with config-reload and decryption support.
    ///
    /// `config_path` is the path to `config.toml` so the tool can re-read API
    /// keys at execution time. `secrets_encrypt` controls whether the keys are
    /// decrypted via `SecretStore`. `max_requests_per_minute` throttles the
    /// `meta` backend (0 = unlimited), `metasearch_overlay_dir` is where
    /// self-repaired engine specs live, and `metasearch_repair_model`
    /// enables the model rung of the repair ladder.
    #[allow(clippy::too_many_arguments)]
    pub fn new_with_config(
        model_provider: String,
        brave_api_key: Option<String>,
        tavily_api_key: Option<String>,
        searxng_instance_url: Option<String>,
        max_results: usize,
        timeout_secs: u64,
        config_path: PathBuf,
        secrets_encrypt: bool,
        max_requests_per_minute: u32,
        metasearch_overlay_dir: Option<PathBuf>,
        metasearch_repair_model: Option<crate::metasearch::RepairModelConfig>,
    ) -> Self {
        Self {
            model_provider: model_provider.trim().to_lowercase(),
            boot_brave_api_key: brave_api_key,
            boot_tavily_api_key: tavily_api_key,
            searxng_instance_url,
            max_results: max_results.clamp(1, 10),
            timeout_secs: timeout_secs.max(1),
            config_path,
            secrets_encrypt,
            meta: MetaSearcher::new(
                metasearch_overlay_dir,
                max_requests_per_minute,
                timeout_secs.max(1),
                metasearch_repair_model,
            ),
        }
    }

    /// Re-reads `config.toml` and extracts one `[web_search]` value, shared
    /// by every provider-credential resolver.
    ///
    /// `label` names the value in error messages and logs (e.g. "Brave API
    /// key"); `not_configured` is the provider-specific error for a missing
    /// or empty field. When `decrypt` is set and the stored value is an
    /// encrypted blob it is decrypted via [`SecretStore`].
    ///
    /// [`SecretStore`]: zeroclaw_config::secrets::SecretStore
    fn reload_web_search_value(
        &self,
        log_provider: &str,
        label: &str,
        not_configured: &str,
        decrypt: bool,
        extract: fn(zeroclaw_config::schema::WebSearchConfig) -> Option<String>,
    ) -> anyhow::Result<String> {
        let contents = std::fs::read_to_string(&self.config_path).map_err(|e| {
            ::zeroclaw_log::record!(
                ERROR,
                ::zeroclaw_log::Event::new(module_path!(), ::zeroclaw_log::Action::Fail)
                    .with_outcome(::zeroclaw_log::EventOutcome::Failure)
                    .with_attrs(::serde_json::json!({
                        "path": self.config_path.display().to_string(),
                        "search_provider": log_provider,
                        "error": format!("{}", e),
                    })),
                &format!("web_search: failed to read config for {label}")
            );
            anyhow::Error::msg(format!(
                "Failed to read config file {} for {label}: {e}",
                self.config_path.display()
            ))
        })?;

        let config: zeroclaw_config::schema::Config = toml::from_str(&contents).map_err(|e| {
            ::zeroclaw_log::record!(
                ERROR,
                ::zeroclaw_log::Event::new(module_path!(), ::zeroclaw_log::Action::Fail)
                    .with_outcome(::zeroclaw_log::EventOutcome::Failure)
                    .with_attrs(::serde_json::json!({
                        "path": self.config_path.display().to_string(),
                        "search_provider": log_provider,
                        "error": format!("{}", e),
                    })),
                &format!("web_search: failed to parse config for {label}")
            );
            anyhow::Error::msg(format!(
                "Failed to parse config file {} for {label}: {e}",
                self.config_path.display()
            ))
        })?;

        let raw_value = extract(config.web_search)
            .filter(|value| !value.is_empty())
            .ok_or_else(|| {
                ::zeroclaw_log::record!(
                    ERROR,
                    ::zeroclaw_log::Event::new(module_path!(), ::zeroclaw_log::Action::Reject)
                        .with_outcome(::zeroclaw_log::EventOutcome::Failure)
                        .with_attrs(::serde_json::json!({"search_provider": log_provider})),
                    &format!("web_search: {label} not configured")
                );
                anyhow::Error::msg(not_configured.to_string())
            })?;

        if decrypt && zeroclaw_config::secrets::SecretStore::is_encrypted(&raw_value) {
            let zeroclaw_dir = self.config_path.parent().unwrap_or_else(|| Path::new("."));
            let store =
                zeroclaw_config::secrets::SecretStore::new(zeroclaw_dir, self.secrets_encrypt);
            let plaintext = store.decrypt(&raw_value)?;
            if plaintext.is_empty() {
                anyhow::bail!("{label} not configured (decrypted value is empty)");
            }
            Ok(plaintext)
        } else {
            Ok(raw_value)
        }
    }

    /// Resolve the Brave API key, preferring the boot-time value but falling
    /// back to a fresh config read + decryption when the boot-time value is
    /// absent or still encrypted.
    fn resolve_brave_api_key(&self) -> anyhow::Result<String> {
        if let Some(ref key) = self.boot_brave_api_key
            && !key.is_empty()
            && !zeroclaw_config::secrets::SecretStore::is_encrypted(key)
        {
            return Ok(key.clone());
        }
        self.reload_web_search_value(
            "brave",
            "Brave API key",
            "Brave API key not configured",
            true,
            |web_search| web_search.brave_api_key,
        )
    }

    async fn search_brave(&self, query: &str) -> anyhow::Result<String> {
        let api_key = self.resolve_brave_api_key()?;

        let encoded_query = urlencoding::encode(query);
        let search_url = format!(
            "https://api.search.brave.com/res/v1/web/search?q={}&count={}",
            encoded_query, self.max_results
        );

        let builder = reqwest::Client::builder().timeout(Duration::from_secs(self.timeout_secs));
        let builder =
            zeroclaw_config::schema::apply_runtime_proxy_to_builder(builder, "tool.web_search");
        let client = builder.build()?;

        let response = client
            .get(&search_url)
            .header("Accept", "application/json")
            .header("X-Subscription-Token", &api_key)
            .send()
            .await?;

        if !response.status().is_success() {
            anyhow::bail!("Brave search failed with status: {}", response.status());
        }

        let json: serde_json::Value = response.json().await?;
        self.parse_brave_results(&json, query)
    }

    /// Resolve the Tavily API key from the boot-time snapshot, falling back
    /// to a fresh config read + decryption when the boot-time value is absent
    /// or still encrypted.
    fn resolve_tavily_api_key(&self) -> anyhow::Result<String> {
        if let Some(ref key) = self.boot_tavily_api_key
            && !key.is_empty()
            && !zeroclaw_config::secrets::SecretStore::is_encrypted(key)
        {
            return Ok(key.clone());
        }
        self.reload_web_search_value(
            "tavily",
            "Tavily API key",
            "Tavily API key not configured",
            true,
            |web_search| web_search.tavily_api_key,
        )
    }

    async fn search_tavily(&self, query: &str) -> anyhow::Result<String> {
        let client = self.build_tavily_client()?;
        self.search_tavily_with_client(&client, "https://api.tavily.com/search", query)
            .await
    }

    /// Build the production HTTP client for Tavily, wired through the
    /// process-global runtime proxy state. Extracted so the
    /// `search_tavily_with_client` test path can substitute a fresh
    /// client and stay isolated from concurrent tests that mutate
    /// `RUNTIME_PROXY_CONFIG` (a request built off a stale "enabled"
    /// proxy snapshot otherwise routes through a non-existent proxy
    /// and the wiremock connection fails).
    fn build_tavily_client(&self) -> anyhow::Result<reqwest::Client> {
        let builder = reqwest::Client::builder().timeout(Duration::from_secs(self.timeout_secs));
        let builder =
            zeroclaw_config::schema::apply_runtime_proxy_to_builder(builder, "tool.web_search");
        Ok(builder.build()?)
    }

    /// Inner Tavily request implementation, parameterized on the HTTP
    /// client and endpoint URL so request-shape tests can target a local
    /// mock server with a client that doesn't read process-global proxy
    /// state. Production calls always go through [`Self::search_tavily`].
    async fn search_tavily_with_client(
        &self,
        client: &reqwest::Client,
        url: &str,
        query: &str,
    ) -> anyhow::Result<String> {
        let api_key = self.resolve_tavily_api_key()?;

        // Tavily authenticates via `Authorization: Bearer <key>` per
        // https://docs.tavily.com/documentation/api-reference/endpoint/search
        // (the API also tolerates `api_key` in the body for legacy clients,
        // but bearer-header is the documented contract).
        let body = serde_json::json!({
            "query": query,
            "max_results": self.max_results,
            "search_depth": "basic",
            "include_answer": false,
            "include_raw_content": false,
        });

        let response = client
            .post(url)
            .bearer_auth(&api_key)
            .json(&body)
            .send()
            .await?;

        if !response.status().is_success() {
            anyhow::bail!("Tavily search failed with status: {}", response.status());
        }

        let json: serde_json::Value = response.json().await?;
        self.parse_tavily_results(&json, query)
    }

    fn parse_tavily_results(
        &self,
        json: &serde_json::Value,
        query: &str,
    ) -> anyhow::Result<String> {
        let results = json
            .get("results")
            .and_then(|r| r.as_array())
            .ok_or_else(|| {
                ::zeroclaw_log::record!(
                    ERROR,
                    ::zeroclaw_log::Event::new(module_path!(), ::zeroclaw_log::Action::Fail)
                        .with_outcome(::zeroclaw_log::EventOutcome::Failure)
                        .with_attrs(::serde_json::json!({"search_provider": "tavily"})),
                    "web_search: invalid Tavily response"
                );
                anyhow::Error::msg("Invalid Tavily API response")
            })?;

        if results.is_empty() {
            return Ok(format!("No results found for: {}", query));
        }

        let mut lines = vec![format!("Search results for: {} (via Tavily)", query)];

        for (i, result) in results.iter().take(self.max_results).enumerate() {
            let title = result
                .get("title")
                .and_then(|t| t.as_str())
                .unwrap_or("No title");
            let url = result.get("url").and_then(|u| u.as_str()).unwrap_or("");
            // Tavily returns a pre-cleaned `content` field (not just a snippet),
            // so it doubles as the description for the LLM caller.
            let content = result.get("content").and_then(|c| c.as_str()).unwrap_or("");

            lines.push(format!("{}. {}", i + 1, title));
            lines.push(format!("   {}", url));
            if !content.is_empty() {
                lines.push(format!("   {}", content));
            }
        }

        Ok(lines.join("\n"))
    }

    fn parse_brave_results(&self, json: &serde_json::Value, query: &str) -> anyhow::Result<String> {
        let results = json
            .get("web")
            .and_then(|w| w.get("results"))
            .and_then(|r| r.as_array())
            .ok_or_else(|| {
                ::zeroclaw_log::record!(
                    ERROR,
                    ::zeroclaw_log::Event::new(module_path!(), ::zeroclaw_log::Action::Fail)
                        .with_outcome(::zeroclaw_log::EventOutcome::Failure)
                        .with_attrs(::serde_json::json!({"search_provider": "brave"})),
                    "web_search: invalid Brave response"
                );
                anyhow::Error::msg("Invalid Brave API response")
            })?;

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

    /// Resolve the SearXNG instance URL from the boot-time config or by
    /// re-reading `config.toml` at runtime.
    fn resolve_searxng_instance_url(&self) -> anyhow::Result<String> {
        if let Some(ref url) = self.searxng_instance_url
            && !url.is_empty()
        {
            return Ok(url.clone());
        }
        self.reload_web_search_value(
            "searxng",
            "SearXNG instance URL",
            "SearXNG instance URL not configured. Set [web_search] searxng_instance_url \
             in config.toml or the SEARXNG_INSTANCE_URL environment variable.",
            false,
            |web_search| web_search.searxng_instance_url,
        )
    }

    async fn search_searxng(&self, query: &str) -> anyhow::Result<String> {
        let instance_url = self.resolve_searxng_instance_url()?;
        let base_url = instance_url.trim_end_matches('/');

        let encoded_query = urlencoding::encode(query);
        let search_url = format!(
            "{}/search?q={}&format=json&pageno=1",
            base_url, encoded_query
        );

        let builder = reqwest::Client::builder()
            .timeout(Duration::from_secs(self.timeout_secs))
            .user_agent("ZeroClaw/1.0");
        let builder =
            zeroclaw_config::schema::apply_runtime_proxy_to_builder(builder, "tool.web_search");
        let client = builder.build()?;

        let response = client
            .get(&search_url)
            .header("Accept", "application/json")
            .send()
            .await?;

        if !response.status().is_success() {
            anyhow::bail!("SearXNG search failed with status: {}", response.status());
        }

        let json: serde_json::Value = response.json().await?;
        self.parse_searxng_results(&json, query)
    }

    fn parse_searxng_results(
        &self,
        json: &serde_json::Value,
        query: &str,
    ) -> anyhow::Result<String> {
        let results = json
            .get("results")
            .and_then(|r| r.as_array())
            .ok_or_else(|| {
                ::zeroclaw_log::record!(
                    ERROR,
                    ::zeroclaw_log::Event::new(module_path!(), ::zeroclaw_log::Action::Fail)
                        .with_outcome(::zeroclaw_log::EventOutcome::Failure)
                        .with_attrs(::serde_json::json!({"search_provider": "searxng"})),
                    "web_search: invalid SearXNG response"
                );
                anyhow::Error::msg("Invalid SearXNG API response")
            })?;

        if results.is_empty() {
            return Ok(format!("No results found for: {}", query));
        }

        let mut lines = vec![format!("Search results for: {} (via SearXNG)", query)];

        for (i, result) in results.iter().take(self.max_results).enumerate() {
            let title = result
                .get("title")
                .and_then(|t| t.as_str())
                .unwrap_or("No title");
            let url = result.get("url").and_then(|u| u.as_str()).unwrap_or("");
            let content = result.get("content").and_then(|c| c.as_str()).unwrap_or("");

            lines.push(format!("{}. {}", i + 1, title));
            lines.push(format!("   {}", url));
            if !content.is_empty() {
                lines.push(format!("   {}", content));
            }
        }

        Ok(lines.join("\n"))
    }
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
        let query = args.get("query").and_then(|q| q.as_str()).ok_or_else(|| {
            ::zeroclaw_log::record!(
                WARN,
                ::zeroclaw_log::Event::new(module_path!(), ::zeroclaw_log::Action::Reject)
                    .with_outcome(::zeroclaw_log::EventOutcome::Failure)
                    .with_attrs(::serde_json::json!({"param": "query"})),
                "web_search: missing query parameter"
            );
            anyhow::Error::msg("Missing required parameter: query")
        })?;

        if query.trim().is_empty() {
            anyhow::bail!("Search query cannot be empty");
        }

        ::zeroclaw_log::record!(
            INFO,
            ::zeroclaw_log::Event::new(module_path!(), ::zeroclaw_log::Action::Note),
            &format!("Searching web for: {}", query)
        );

        let resolution = resolve_web_search_provider(&self.model_provider);
        if resolution.used_fallback {
            ::zeroclaw_log::record!(
                WARN,
                ::zeroclaw_log::Event::new(module_path!(), ::zeroclaw_log::Action::Note)
                    .with_outcome(::zeroclaw_log::EventOutcome::Unknown),
                &format!(
                    "Unknown web search model_provider '{}'; falling back to '{}'",
                    self.model_provider, resolution.canonical_provider
                )
            );
        }

        let result = match resolution.route {
            WebSearchProviderRoute::Meta => self.meta.search(query, self.max_results).await?,
            WebSearchProviderRoute::DuckDuckGo => {
                self.meta
                    .search_engine(DDG_ENGINE_ID, query, self.max_results)
                    .await?
            }
            WebSearchProviderRoute::Brave => self.search_brave(query).await?,
            WebSearchProviderRoute::Tavily => self.search_tavily(query).await?,
            WebSearchProviderRoute::SearXNG => self.search_searxng(query).await?,
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

    #[test]
    fn test_tool_name() {
        let tool = WebSearchTool::new("duckduckgo".to_string(), None, 5, 15);
        assert_eq!(tool.name(), "web_search_tool");
    }

    #[test]
    fn test_tool_description() {
        let tool = WebSearchTool::new("duckduckgo".to_string(), None, 5, 15);
        assert!(tool.description().contains("Search the web"));
    }

    #[test]
    fn test_parameters_schema() {
        let tool = WebSearchTool::new("duckduckgo".to_string(), None, 5, 15);
        let schema = tool.parameters_schema();
        assert_eq!(schema["type"], "object");
        assert!(schema["properties"]["query"].is_object());
    }

    /// Builds a tool whose `duckduckgo` route resolves against a mock
    /// engine spec carrying the bundled DDG engine id, pointed at `server_url`.
    fn tool_with_mock_ddg_engine(server_url: &str) -> WebSearchTool {
        use crate::metasearch::spec::{
            EngineSpec, GoldenSpec, HtmlResponseSpec, RequestSpec, ResponseKind, ResponseSpec,
        };
        use std::collections::BTreeMap;

        let ddg_spec = EngineSpec {
            id: DDG_ENGINE_ID.into(),
            display_name: "DuckDuckGo".into(),
            weight: 1.0,
            block_markers: vec![],
            request: RequestSpec {
                url: format!("{server_url}/search?q={{query}}"),
                user_agent: None,
                headers: BTreeMap::new(),
                token: None,
            },
            response: ResponseSpec {
                kind: ResponseKind::Html,
                html: Some(HtmlResponseSpec {
                    result_selector: "div.result".into(),
                    title_selector: "a.title".into(),
                    url_selector: "a.title".into(),
                    url_attribute: "href".into(),
                    snippet_selector: Some("p.snippet".into()),
                    url_unwrap_param: None,
                }),
                json: None,
            },
            golden: GoldenSpec {
                query: "wikipedia".into(),
                expected_domain: "wikipedia.org".into(),
            },
        };
        WebSearchTool {
            model_provider: "duckduckgo".into(),
            boot_brave_api_key: None,
            boot_tavily_api_key: None,
            searxng_instance_url: None,
            max_results: 5,
            timeout_secs: 15,
            config_path: PathBuf::new(),
            secrets_encrypt: false,
            meta: MetaSearcher::with_specs(vec![ddg_spec], 0, 5),
        }
    }

    #[tokio::test]
    async fn test_duckduckgo_route_runs_the_meta_spec_engine() {
        use wiremock::matchers::{method, path};
        use wiremock::{Mock, MockServer, ResponseTemplate};

        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/search"))
            .respond_with(ResponseTemplate::new(200).set_body_string(
                r#"<html><body>
                    <div class="result">
                        <a class="title" href="https://example.com/one">First Result</a>
                        <p class="snippet">First snippet</p>
                    </div>
                </body></html>"#,
            ))
            .mount(&server)
            .await;

        let tool = tool_with_mock_ddg_engine(&server.uri());
        let result = tool.execute(json!({"query": "test"})).await.unwrap();
        assert!(result.success);
        assert!(result.output.contains("via DuckDuckGo"));
        assert!(result.output.contains("First Result"));
        assert!(result.output.contains("https://example.com/one"));
    }

    #[test]
    fn test_constructor_clamps_web_search_limits() {
        let tool = WebSearchTool::new("duckduckgo".to_string(), None, 0, 0);
        assert_eq!(tool.max_results, 1);
        assert_eq!(tool.timeout_secs, 1);
    }

    #[tokio::test]
    async fn test_execute_missing_query() {
        let tool = WebSearchTool::new("duckduckgo".to_string(), None, 5, 15);
        let result = tool.execute(json!({})).await;
        assert!(result.is_err());
    }

    #[tokio::test]
    async fn test_execute_empty_query() {
        let tool = WebSearchTool::new("duckduckgo".to_string(), None, 5, 15);
        let result = tool.execute(json!({"query": ""})).await;
        assert!(result.is_err());
    }

    #[tokio::test]
    async fn test_execute_brave_without_api_key() {
        let tool = WebSearchTool::new("brave".to_string(), None, 5, 15);
        let result = tool.execute(json!({"query": "test"})).await;
        assert!(result.is_err());
        assert!(result.unwrap_err().to_string().contains("API key"));
    }

    #[test]
    fn test_resolve_brave_api_key_uses_boot_key() {
        let tool = WebSearchTool::new(
            "brave".to_string(),
            Some("sk-plaintext-key".to_string()),
            5,
            15,
        );
        let key = tool.resolve_brave_api_key().unwrap();
        assert_eq!(key, "sk-plaintext-key");
    }

    #[test]
    fn test_resolve_brave_api_key_reloads_from_config() {
        let tmp = tempfile::TempDir::new().unwrap();
        let config_path = tmp.path().join("config.toml");
        std::fs::write(
            &config_path,
            "[web_search]\nbrave_api_key = \"fresh-key-from-disk\"\n",
        )
        .unwrap();

        // No boot key -- forces reload from config
        let tool = WebSearchTool::new_with_config(
            "brave".to_string(),
            None,
            None,
            None,
            5,
            15,
            config_path,
            false,
            10,
            None,
            None,
        );
        let key = tool.resolve_brave_api_key().unwrap();
        assert_eq!(key, "fresh-key-from-disk");
    }

    #[test]
    fn test_resolve_brave_api_key_decrypts_encrypted_key() {
        let tmp = tempfile::TempDir::new().unwrap();
        let store = zeroclaw_config::secrets::SecretStore::new(tmp.path(), true);
        let encrypted = store.encrypt("brave-secret-key").unwrap();

        let config_path = tmp.path().join("config.toml");
        std::fs::write(
            &config_path,
            format!("[web_search]\nbrave_api_key = \"{}\"\n", encrypted),
        )
        .unwrap();

        // Boot key is the encrypted blob -- should trigger reload + decrypt
        let tool = WebSearchTool::new_with_config(
            "brave".to_string(),
            Some(encrypted),
            None,
            None,
            5,
            15,
            config_path,
            true,
            10,
            None,
            None,
        );
        let key = tool.resolve_brave_api_key().unwrap();
        assert_eq!(key, "brave-secret-key");
    }

    #[tokio::test]
    async fn test_execute_searxng_without_instance_url() {
        let tmp = tempfile::TempDir::new().unwrap();
        let config_path = tmp.path().join("config.toml");
        std::fs::write(&config_path, "[web_search]\n").unwrap();

        let tool = WebSearchTool::new_with_config(
            "searxng".to_string(),
            None,
            None,
            None,
            5,
            15,
            config_path,
            false,
            10,
            None,
            None,
        );
        let result = tool.execute(json!({"query": "test"})).await;
        assert!(result.is_err());
        assert!(
            result
                .unwrap_err()
                .to_string()
                .contains("SearXNG instance URL not configured")
        );
    }

    #[test]
    fn test_parse_tavily_results_empty() {
        let tool = WebSearchTool::new("tavily".to_string(), None, 5, 15);
        let json = serde_json::json!({"results": []});
        let result = tool.parse_tavily_results(&json, "test").unwrap();
        assert!(result.contains("No results found"));
    }

    #[test]
    fn test_parse_tavily_results_with_data() {
        let tool = WebSearchTool::new("tavily".to_string(), None, 5, 15);
        let json = serde_json::json!({
            "query": "test",
            "results": [
                {
                    "title": "Tavily Example",
                    "url": "https://example.com",
                    "content": "Pre-cleaned summary content from Tavily",
                    "score": 0.91
                },
                {
                    "title": "Another Result",
                    "url": "https://example.org",
                    "content": "Second result body"
                }
            ]
        });
        let result = tool.parse_tavily_results(&json, "test").unwrap();
        assert!(result.contains("Tavily Example"));
        assert!(result.contains("https://example.com"));
        assert!(result.contains("Pre-cleaned summary content from Tavily"));
        assert!(result.contains("via Tavily"));
    }

    #[test]
    fn test_parse_tavily_results_invalid_response() {
        let tool = WebSearchTool::new("tavily".to_string(), None, 5, 15);
        let json = serde_json::json!({"error": "bad api key"});
        let result = tool.parse_tavily_results(&json, "test");
        assert!(result.is_err());
        assert!(
            result
                .unwrap_err()
                .to_string()
                .contains("Invalid Tavily API response")
        );
    }

    #[tokio::test]
    async fn test_execute_tavily_without_api_key() {
        // No boot key + no config field → resolve_tavily_api_key must error
        // before any network call is attempted.
        let tmp = tempfile::tempdir().unwrap();
        let config_path = tmp.path().join("config.toml");
        std::fs::write(&config_path, "[web_search]\n").unwrap();

        let tool = WebSearchTool::new_with_config(
            "tavily".to_string(),
            None,
            None,
            None,
            5,
            15,
            config_path,
            false,
            10,
            None,
            None,
        );
        let result = tool.execute(json!({"query": "test"})).await;
        assert!(result.is_err());
        assert!(
            result
                .unwrap_err()
                .to_string()
                .contains("Tavily API key not configured")
        );
    }

    #[test]
    fn test_resolve_tavily_api_key_uses_boot_key() {
        let tool = WebSearchTool::new_with_config(
            "tavily".to_string(),
            None,
            Some("tvly-boot-key".to_string()),
            None,
            5,
            15,
            PathBuf::new(),
            false,
            10,
            None,
            None,
        );
        let key = tool.resolve_tavily_api_key().unwrap();
        assert_eq!(key, "tvly-boot-key");
    }

    #[test]
    fn test_resolve_tavily_api_key_reloads_from_config() {
        let tmp = tempfile::tempdir().unwrap();
        let config_path = tmp.path().join("config.toml");
        std::fs::write(
            &config_path,
            "[web_search]\ntavily_api_key = \"tvly-fresh-from-disk\"\n",
        )
        .unwrap();

        // No boot key — forces reload from config
        let tool = WebSearchTool::new_with_config(
            "tavily".to_string(),
            None,
            None,
            None,
            5,
            15,
            config_path,
            false,
            10,
            None,
            None,
        );
        let key = tool.resolve_tavily_api_key().unwrap();
        assert_eq!(key, "tvly-fresh-from-disk");
    }

    #[test]
    fn test_resolve_tavily_api_key_decrypts_encrypted_key() {
        let tmp = tempfile::TempDir::new().unwrap();
        let store = zeroclaw_config::secrets::SecretStore::new(tmp.path(), true);
        let encrypted = store.encrypt("tvly-secret-key").unwrap();

        let config_path = tmp.path().join("config.toml");
        std::fs::write(
            &config_path,
            format!("[web_search]\ntavily_api_key = \"{}\"\n", encrypted),
        )
        .unwrap();

        // Boot key is the encrypted blob -- should trigger reload + decrypt
        let tool = WebSearchTool::new_with_config(
            "tavily".to_string(),
            None,
            Some(encrypted),
            None,
            5,
            15,
            config_path,
            true,
            10,
            None,
            None,
        );
        let key = tool.resolve_tavily_api_key().unwrap();
        assert_eq!(key, "tvly-secret-key");
    }

    /// Regression: Tavily auth must travel as `Authorization: Bearer <key>`
    /// (the documented contract per
    /// https://docs.tavily.com/documentation/api-reference/endpoint/search),
    /// NOT as an `api_key` field in the JSON body. The previous shape worked
    /// against the live service for legacy reasons, but the docs identify
    /// bearer-header as the canonical method.
    #[tokio::test]
    async fn test_tavily_request_uses_bearer_auth_header_not_body_field() {
        use wiremock::matchers::{header, method, path};
        use wiremock::{Mock, MockServer, ResponseTemplate};

        let server = MockServer::start().await;

        Mock::given(method("POST"))
            .and(path("/search"))
            .and(header("authorization", "Bearer tvly-test-key"))
            .and(header("content-type", "application/json"))
            .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "query": "what is rust",
                "results": []
            })))
            .mount(&server)
            .await;

        let tool = WebSearchTool::new_with_config(
            "tavily".to_string(),
            None,
            Some("tvly-test-key".to_string()),
            None,
            5,
            15,
            PathBuf::new(),
            false,
            10,
            None,
            None,
        );

        // Isolated client so the request shape under test isn't affected
        // by `RUNTIME_PROXY_CONFIG` mutations from sibling proxy_config
        // tests running concurrently in the same process.
        let client = reqwest::Client::builder()
            .timeout(Duration::from_secs(15))
            .build()
            .expect("client builder should succeed without a proxy");
        let result = tool
            .search_tavily_with_client(&client, &format!("{}/search", server.uri()), "what is rust")
            .await
            .expect("request should succeed against the mock");
        assert!(
            result.contains("No results found"),
            "parser should report empty results: {result}"
        );

        let recorded = server
            .received_requests()
            .await
            .expect("wiremock should have captured the request");
        assert_eq!(recorded.len(), 1, "expected exactly one POST /search");

        let body: serde_json::Value =
            serde_json::from_slice(&recorded[0].body).expect("body should be JSON");

        // Auth must NOT leak into the body — bearer header is the only auth channel.
        assert!(
            body.get("api_key").is_none(),
            "api_key must not appear in the request body; got: {body}"
        );

        // The documented body fields must still be present so the search
        // contract continues to match the upstream API spec.
        assert_eq!(body["query"], "what is rust");
        assert_eq!(body["search_depth"], "basic");
        assert_eq!(body["max_results"], 5);
        assert_eq!(body["include_answer"], false);
        assert_eq!(body["include_raw_content"], false);
    }

    #[test]
    fn test_parse_searxng_results_empty() {
        let tool = WebSearchTool::new("searxng".to_string(), None, 5, 15);
        let json = serde_json::json!({"results": []});
        let result = tool.parse_searxng_results(&json, "test").unwrap();
        assert!(result.contains("No results found"));
    }

    #[test]
    fn test_parse_searxng_results_with_data() {
        let tool = WebSearchTool::new("searxng".to_string(), None, 5, 15);
        let json = serde_json::json!({
            "results": [
                {
                    "title": "SearXNG Example",
                    "url": "https://example.com",
                    "content": "A privacy-respecting metasearch engine"
                },
                {
                    "title": "Another Result",
                    "url": "https://example.org",
                    "content": "More information here"
                }
            ]
        });
        let result = tool.parse_searxng_results(&json, "test").unwrap();
        assert!(result.contains("SearXNG Example"));
        assert!(result.contains("https://example.com"));
        assert!(result.contains("A privacy-respecting metasearch engine"));
        assert!(result.contains("via SearXNG"));
    }

    #[test]
    fn test_parse_searxng_results_invalid_response() {
        let tool = WebSearchTool::new("searxng".to_string(), None, 5, 15);
        let json = serde_json::json!({"error": "bad request"});
        let result = tool.parse_searxng_results(&json, "test");
        assert!(result.is_err());
        assert!(
            result
                .unwrap_err()
                .to_string()
                .contains("Invalid SearXNG API response")
        );
    }

    #[test]
    fn test_resolve_searxng_instance_url_from_boot() {
        let tool = WebSearchTool {
            model_provider: "searxng".into(),
            boot_brave_api_key: None,
            boot_tavily_api_key: None,
            searxng_instance_url: Some("https://searx.example.com".to_string()),
            max_results: 5,
            timeout_secs: 15,
            config_path: PathBuf::new(),
            secrets_encrypt: false,
            meta: MetaSearcher::new(None, 10, 15, None),
        };
        let url = tool.resolve_searxng_instance_url().unwrap();
        assert_eq!(url, "https://searx.example.com");
    }

    #[test]
    fn test_resolve_searxng_instance_url_reloads_from_config() {
        let tmp = tempfile::TempDir::new().unwrap();
        let config_path = tmp.path().join("config.toml");
        std::fs::write(
            &config_path,
            "[web_search]\nsearxng_instance_url = \"https://search.local\"\n",
        )
        .unwrap();

        let tool = WebSearchTool::new_with_config(
            "searxng".to_string(),
            None,
            None,
            None,
            5,
            15,
            config_path,
            false,
            10,
            None,
            None,
        );
        let url = tool.resolve_searxng_instance_url().unwrap();
        assert_eq!(url, "https://search.local");
    }

    #[test]
    fn test_resolve_brave_api_key_picks_up_runtime_update() {
        let tmp = tempfile::TempDir::new().unwrap();
        let config_path = tmp.path().join("config.toml");

        // Start with no key in config
        std::fs::write(&config_path, "[web_search]\n").unwrap();

        let tool = WebSearchTool::new_with_config(
            "brave".to_string(),
            None,
            None,
            None,
            5,
            15,
            config_path.clone(),
            false,
            10,
            None,
            None,
        );

        // Key not configured yet -- should fail
        assert!(tool.resolve_brave_api_key().is_err());

        // Simulate runtime config update (e.g. via web_search_config set)
        std::fs::write(
            &config_path,
            "[web_search]\nbrave_api_key = \"runtime-updated-key\"\n",
        )
        .unwrap();

        // Now should succeed with the updated key
        let key = tool.resolve_brave_api_key().unwrap();
        assert_eq!(key, "runtime-updated-key");
    }
}
