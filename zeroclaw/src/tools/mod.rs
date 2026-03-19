//! Tool subsystem for agent-callable capabilities.
//!
//! This module implements the tool execution surface exposed to the LLM during
//! agentic loops. Each tool implements the [`Tool`] trait defined in [`traits`],
//! which requires a name, description, JSON parameter schema, and an async
//! `execute` method returning a structured [`ToolResult`].
//!
//! Tools are assembled into registries by [`default_tools`] (shell, file read/write)
//! and [`all_tools`] (full set including memory, browser, cron, HTTP, delegation,
//! and optional integrations). Security policy enforcement is injected via
//! [`SecurityPolicy`](crate::security::SecurityPolicy) at construction time.
//!
//! # Extension
//!
//! To add a new tool, implement [`Tool`] in a new submodule and register it in
//! [`all_tools_with_runtime`]. See `AGENTS.md` §7.3 for the full change playbook.

pub mod browser;
pub mod browser_open;
pub mod cli_discovery;
pub mod composio;
pub mod content_search;
pub mod cron_add;
pub mod cron_list;
pub mod cron_remove;
pub mod cron_run;
pub mod cron_runs;
pub mod cron_update;
pub mod delegate;
pub mod discord_search;
pub mod email;
pub mod file_edit;
pub mod file_read;
pub mod file_write;
pub mod git_operations;
pub mod glob_search;
#[cfg(feature = "hardware")]
pub mod hardware_board_info;
#[cfg(feature = "hardware")]
pub mod hardware_memory_map;
#[cfg(feature = "hardware")]
pub mod hardware_memory_read;
pub mod http_request;
pub mod image_info;
pub mod memory_forget;
pub mod memory_recall;
pub mod memory_store;
pub mod model_routing_config;
pub mod pdf_read;
pub mod proxy_config;
pub mod pushover;
pub mod schedule;
pub mod schema;
pub mod screenshot;
pub mod shell;
pub mod traits;
pub mod twitter_browse;
pub mod web_fetch;
pub mod web_search_tool;

pub use browser::{BrowserTool, ComputerUseConfig};
pub use browser_open::BrowserOpenTool;
pub use composio::ComposioTool;
pub use content_search::ContentSearchTool;
pub use cron_add::CronAddTool;
pub use cron_list::CronListTool;
pub use cron_remove::CronRemoveTool;
pub use cron_run::CronRunTool;
pub use cron_runs::CronRunsTool;
pub use cron_update::CronUpdateTool;
pub use delegate::DelegateTool;
pub use file_edit::FileEditTool;
pub use file_read::FileReadTool;
pub use file_write::FileWriteTool;
pub use git_operations::GitOperationsTool;
pub use glob_search::GlobSearchTool;
#[cfg(feature = "hardware")]
pub use hardware_board_info::HardwareBoardInfoTool;
#[cfg(feature = "hardware")]
pub use hardware_memory_map::HardwareMemoryMapTool;
#[cfg(feature = "hardware")]
pub use hardware_memory_read::HardwareMemoryReadTool;
pub use http_request::HttpRequestTool;
pub use image_info::ImageInfoTool;
pub use memory_forget::MemoryForgetTool;
pub use memory_recall::MemoryRecallTool;
pub use memory_store::MemoryStoreTool;
pub use model_routing_config::ModelRoutingConfigTool;
pub use pdf_read::PdfReadTool;
pub use proxy_config::ProxyConfigTool;
pub use pushover::PushoverTool;
pub use schedule::ScheduleTool;
#[allow(unused_imports)]
pub use schema::{CleaningStrategy, SchemaCleanr};
pub use screenshot::ScreenshotTool;
pub use shell::ShellTool;
pub use traits::Tool;
#[allow(unused_imports)]
pub use traits::{ToolResult, ToolSpec};
pub use twitter_browse::TwitterBrowseTool;
pub use web_fetch::WebFetchTool;
pub use web_search_tool::WebSearchTool;

use crate::config::{Config, DelegateAgentConfig};
use crate::memory::Memory;
use crate::runtime::{NativeRuntime, RuntimeAdapter};
use crate::security::SecurityPolicy;
use async_trait::async_trait;
use std::collections::HashMap;
use std::sync::{Arc, Mutex, OnceLock};

/// Extracts the hostname from an HTTP or HTTPS URL.
///
/// Returns the lowercase hostname without port, trailing dots, or userinfo.
/// Rejects non-HTTP schemes, URLs with userinfo (`user:pass@`), and IPv6 hosts.
pub(crate) fn extract_host(url: &str) -> anyhow::Result<String> {
    let rest = url
        .strip_prefix("http://")
        .or_else(|| url.strip_prefix("https://"))
        .ok_or_else(|| anyhow::anyhow!("Only http:// and https:// URLs are allowed"))?;

    let authority = rest
        .split(['/', '?', '#'])
        .next()
        .ok_or_else(|| anyhow::anyhow!("Invalid URL"))?;

    if authority.is_empty() {
        anyhow::bail!("URL must include a host");
    }

    if authority.contains('@') {
        anyhow::bail!("URL userinfo is not allowed");
    }

    if authority.starts_with('[') {
        anyhow::bail!("IPv6 hosts are not supported");
    }

    let host = authority
        .split(':')
        .next()
        .unwrap_or_default()
        .trim()
        .trim_end_matches('.')
        .to_lowercase();

    if host.is_empty() {
        anyhow::bail!("URL must include a valid host");
    }

    Ok(host)
}

#[derive(Clone)]
struct ArcDelegatingTool {
    inner: Arc<dyn Tool>,
}

impl ArcDelegatingTool {
    fn boxed(inner: Arc<dyn Tool>) -> Box<dyn Tool> {
        Box::new(Self { inner })
    }
}

#[async_trait]
impl Tool for ArcDelegatingTool {
    fn name(&self) -> &str {
        self.inner.name()
    }

    fn description(&self) -> &str {
        self.inner.description()
    }

    fn parameters_schema(&self) -> serde_json::Value {
        self.inner.parameters_schema()
    }

    async fn execute(&self, args: serde_json::Value) -> anyhow::Result<ToolResult> {
        self.inner.execute(args).await
    }
}

fn boxed_registry_from_arcs(tools: Vec<Arc<dyn Tool>>) -> Vec<Box<dyn Tool>> {
    tools.into_iter().map(ArcDelegatingTool::boxed).collect()
}

/// Factory function type for creating extra tools on demand.
type ExtraToolsFactory = dyn Fn() -> Vec<Box<dyn Tool>> + Send + Sync;

/// Global factory for extra tools injected from outside the crate (e.g. Kotlin-bridged tools).
static EXTRA_TOOLS_FACTORY: OnceLock<Mutex<Option<Box<ExtraToolsFactory>>>> = OnceLock::new();

/// Registers a factory function that creates extra tools on each registry build.
///
/// Unlike storing tool instances directly, the factory is called every time
/// [`all_tools_with_runtime`] builds a registry. This ensures tools are
/// available across multiple callers (channels, agent loop, gateway).
pub fn set_global_extra_tools_factory(factory: Box<ExtraToolsFactory>) {
    let slot = EXTRA_TOOLS_FACTORY.get_or_init(|| Mutex::new(None));
    *slot.lock().unwrap_or_else(|e| e.into_inner()) = Some(factory);
}

/// Convenience wrapper: registers a simple tool list (re-created on each call).
pub fn set_global_extra_tools(tools_fn: fn() -> Vec<Box<dyn Tool>>) {
    set_global_extra_tools_factory(Box::new(tools_fn));
}

/// Creates and returns extra tools from the registered factory, if any.
fn create_global_extra_tools() -> Vec<Box<dyn Tool>> {
    let tools = EXTRA_TOOLS_FACTORY
        .get()
        .and_then(|m| {
            let guard = m.lock().unwrap_or_else(|e| e.into_inner());
            guard.as_ref().map(|f| f())
        })
        .unwrap_or_default();
    if !tools.is_empty() {
        let names: Vec<&str> = tools.iter().map(|t| t.name()).collect();
        tracing::info!(?names, "Injected {} extra tools into registry", tools.len());
    }
    tools
}

/// Create the default tool registry
pub fn default_tools(security: Arc<SecurityPolicy>) -> Vec<Box<dyn Tool>> {
    default_tools_with_runtime(security, Arc::new(NativeRuntime::new()))
}

/// Create the default tool registry with explicit runtime adapter.
pub fn default_tools_with_runtime(
    security: Arc<SecurityPolicy>,
    runtime: Arc<dyn RuntimeAdapter>,
) -> Vec<Box<dyn Tool>> {
    vec![
        Box::new(ShellTool::new(security.clone(), runtime)),
        Box::new(FileReadTool::new(security.clone())),
        Box::new(FileWriteTool::new(security.clone())),
        Box::new(FileEditTool::new(security.clone())),
        Box::new(GlobSearchTool::new(security.clone())),
        Box::new(ContentSearchTool::new(security)),
    ]
}

/// Create full tool registry including memory tools and optional Composio
#[allow(clippy::implicit_hasher, clippy::too_many_arguments)]
pub fn all_tools(
    config: Arc<Config>,
    security: &Arc<SecurityPolicy>,
    memory: Arc<dyn Memory>,
    composio_key: Option<&str>,
    composio_entity_id: Option<&str>,
    browser_config: &crate::config::BrowserConfig,
    http_config: &crate::config::HttpRequestConfig,
    web_fetch_config: &crate::config::WebFetchConfig,
    workspace_dir: &std::path::Path,
    agents: &HashMap<String, DelegateAgentConfig>,
    fallback_api_key: Option<&str>,
    root_config: &crate::config::Config,
) -> Vec<Box<dyn Tool>> {
    all_tools_with_runtime(
        config,
        security,
        Arc::new(NativeRuntime::new()),
        memory,
        composio_key,
        composio_entity_id,
        browser_config,
        http_config,
        web_fetch_config,
        workspace_dir,
        agents,
        fallback_api_key,
        root_config,
        None,
        &[],
    )
}

/// Create full tool registry including memory tools and optional Composio.
///
/// When `webview_fallback` is `Some`, the [`WebFetchTool`] is constructed
/// with the given fallback renderer. When `None`, the function checks for
/// a process-wide global set via
/// [`web_fetch::set_global_webview_fallback`](crate::tools::web_fetch::set_global_webview_fallback).
#[allow(clippy::implicit_hasher, clippy::too_many_arguments)]
pub fn all_tools_with_runtime(
    config: Arc<Config>,
    security: &Arc<SecurityPolicy>,
    runtime: Arc<dyn RuntimeAdapter>,
    memory: Arc<dyn Memory>,
    composio_key: Option<&str>,
    composio_entity_id: Option<&str>,
    browser_config: &crate::config::BrowserConfig,
    http_config: &crate::config::HttpRequestConfig,
    web_fetch_config: &crate::config::WebFetchConfig,
    workspace_dir: &std::path::Path,
    agents: &HashMap<String, DelegateAgentConfig>,
    fallback_api_key: Option<&str>,
    root_config: &crate::config::Config,
    webview_fallback: Option<Arc<dyn crate::tools::web_fetch::WebViewFallback>>,
    skills: &[crate::skills::Skill],
) -> Vec<Box<dyn Tool>> {
    // Collect extra domains from skill api_base metadata so that http_request
    // and web_fetch can reach skill APIs without explicit allowlist entries.
    let skill_domains: Vec<String> = skills
        .iter()
        .filter_map(|s| s.api_base())
        .filter_map(|base| extract_host(base).ok())
        .collect();
    let mut tool_arcs: Vec<Arc<dyn Tool>> = vec![
        Arc::new(ShellTool::new(security.clone(), runtime)),
        Arc::new(FileReadTool::new(security.clone())),
        Arc::new(FileWriteTool::new(security.clone())),
        Arc::new(FileEditTool::new(security.clone())),
        Arc::new(GlobSearchTool::new(security.clone())),
        Arc::new(ContentSearchTool::new(security.clone())),
        Arc::new(CronAddTool::new(config.clone(), security.clone())),
        Arc::new(CronListTool::new(config.clone())),
        Arc::new(CronRemoveTool::new(config.clone(), security.clone())),
        Arc::new(CronUpdateTool::new(config.clone(), security.clone())),
        Arc::new(CronRunTool::new(config.clone(), security.clone())),
        Arc::new(CronRunsTool::new(config.clone())),
        Arc::new(MemoryStoreTool::new(memory.clone(), security.clone())),
        Arc::new(MemoryRecallTool::new(memory.clone())),
        Arc::new(MemoryForgetTool::new(memory, security.clone())),
        Arc::new(ScheduleTool::new(security.clone(), root_config.clone())),
        Arc::new(ModelRoutingConfigTool::new(
            config.clone(),
            security.clone(),
        )),
        Arc::new(ProxyConfigTool::new(config.clone(), security.clone())),
        Arc::new(GitOperationsTool::new(
            security.clone(),
            workspace_dir.to_path_buf(),
        )),
        Arc::new(PushoverTool::new(
            security.clone(),
            workspace_dir.to_path_buf(),
        )),
    ];

    if browser_config.enabled {
        tool_arcs.push(Arc::new(BrowserOpenTool::new(
            security.clone(),
            browser_config.allowed_domains.clone(),
        )));
        tool_arcs.push(Arc::new(BrowserTool::new_with_backend(
            security.clone(),
            browser_config.allowed_domains.clone(),
            browser_config.session_name.clone(),
            browser_config.backend.clone(),
            browser_config.native_headless,
            browser_config.native_webdriver_url.clone(),
            browser_config.native_chrome_path.clone(),
            ComputerUseConfig {
                endpoint: browser_config.computer_use.endpoint.clone(),
                api_key: browser_config.computer_use.api_key.clone(),
                timeout_ms: browser_config.computer_use.timeout_ms,
                allow_remote_endpoint: browser_config.computer_use.allow_remote_endpoint,
                window_allowlist: browser_config.computer_use.window_allowlist.clone(),
                max_coordinate_x: browser_config.computer_use.max_coordinate_x,
                max_coordinate_y: browser_config.computer_use.max_coordinate_y,
            },
        )));
    }

    if http_config.enabled {
        let mut http_allowed = http_config.allowed_domains.clone();
        http_allowed.extend(skill_domains.iter().cloned());

        // Build skill credential entries from auth profiles with "skill::" prefix.
        //
        // AuthProfilesStore::load() is async (requires file I/O with lock
        // acquisition), but this function is synchronous and may not be called
        // inside a tokio runtime context. We attempt a blocking load on a
        // fresh runtime; if that fails (e.g. nested runtime), we fall back to
        // an empty vec so the tool still registers without auto-injection.
        let skill_credentials: Vec<http_request::SkillCredentialEntry> = {
            let state_dir = workspace_dir
                .parent()
                .unwrap_or(workspace_dir);
            let store = crate::auth::profiles::AuthProfilesStore::new(
                state_dir,
                root_config.secrets.encrypt,
            );
            tokio::runtime::Handle::try_current()
                .ok()
                .and_then(|handle| {
                    std::thread::scope(|s| {
                        s.spawn(|| {
                            handle.block_on(store.load()).ok()
                        }).join().ok().flatten()
                    })
                })
                .map(|data| {
                    data.profiles
                        .values()
                        .filter(|p| p.provider.starts_with("skill::"))
                        .filter_map(|p| {
                            let api_base = p.metadata.get("api_base_url")?;
                            let domain = extract_host(api_base).ok()?;
                            let token = match &p.kind {
                                crate::auth::profiles::AuthProfileKind::Token => {
                                    p.token.as_ref()
                                }
                                crate::auth::profiles::AuthProfileKind::OAuth => {
                                    p.token_set.as_ref().map(|ts| &ts.access_token)
                                }
                            }?;
                            if token.trim().is_empty() {
                                return None;
                            }
                            Some(http_request::SkillCredentialEntry {
                                domain,
                                auth_header_value: format!("Bearer {token}"),
                            })
                        })
                        .collect()
                })
                .unwrap_or_default()
        };

        tool_arcs.push(Arc::new(HttpRequestTool::new(
            security.clone(),
            http_allowed,
            http_config.max_response_size,
            http_config.timeout_secs,
            skill_credentials,
        )));
    }

    if web_fetch_config.enabled {
        let mut wf_allowed = web_fetch_config.allowed_domains.clone();
        wf_allowed.extend(skill_domains.iter().cloned());
        let mut tool = WebFetchTool::new(
            security.clone(),
            wf_allowed,
            web_fetch_config.blocked_domains.clone(),
            web_fetch_config.max_response_size,
            web_fetch_config.timeout_secs,
        );
        let resolved_fallback = webview_fallback
            .as_ref()
            .cloned()
            .or_else(web_fetch::global_webview_fallback);
        if let Some(fallback) = resolved_fallback {
            tool = tool.with_webview_fallback(fallback);
        }
        tool_arcs.push(Arc::new(tool));
    }

    if root_config.web_search.enabled {
        let search_tool = WebSearchTool::new(&root_config.web_search);
        tool_arcs.push(Arc::new(search_tool));
    }

    if root_config.twitter_browse.enabled {
        tool_arcs.push(Arc::new(TwitterBrowseTool::new(
            root_config.twitter_browse.cookie_string.clone(),
            root_config.twitter_browse.max_items,
            root_config.twitter_browse.timeout_secs,
        )));
    }

    tool_arcs.push(Arc::new(PdfReadTool::new(security.clone())));

    tool_arcs.push(Arc::new(ScreenshotTool::new(security.clone())));
    tool_arcs.push(Arc::new(ImageInfoTool::new(security.clone())));

    if let Some(key) = composio_key {
        if !key.is_empty() {
            tool_arcs.push(Arc::new(ComposioTool::new(
                key,
                composio_entity_id,
                security.clone(),
            )));
        }
    }

    if !agents.is_empty() {
        let delegate_agents: HashMap<String, DelegateAgentConfig> = agents
            .iter()
            .map(|(name, cfg)| (name.clone(), cfg.clone()))
            .collect();
        let delegate_fallback_credential = fallback_api_key.and_then(|value| {
            let trimmed_value = value.trim();
            (!trimmed_value.is_empty()).then(|| trimmed_value.to_owned())
        });
        let parent_tools = Arc::new(tool_arcs.clone());
        let delegate_tool = DelegateTool::new_with_options(
            delegate_agents,
            delegate_fallback_credential,
            security.clone(),
            crate::providers::ProviderRuntimeOptions {
                auth_profile_override: None,
                provider_api_url: root_config.api_url.clone(),
                zeroclaw_dir: root_config
                    .config_path
                    .parent()
                    .map(std::path::PathBuf::from),
                secrets_encrypt: root_config.secrets.encrypt,
                reasoning_enabled: root_config.runtime.reasoning_enabled,
                reasoning_effort: root_config.runtime.reasoning_effort,
                custom_headers: root_config
                    .default_provider
                    .as_deref()
                    .and_then(|name| root_config.model_providers.get(name))
                    .and_then(|p| p.custom_headers.clone()),
            },
        )
        .with_parent_tools(parent_tools)
        .with_multimodal_config(root_config.multimodal.clone());
        tool_arcs.push(Arc::new(delegate_tool));
    }

    // Email tools — registered when email config is present and enabled
    if let Some(ref email_config) = root_config.email.as_ref().filter(|c| c.enabled) {
        let email_client = Arc::new(email::client::EmailClient::from_config(email_config));
        tool_arcs.push(Arc::new(email::check::EmailCheckTool::new(email_client.clone())));
        tool_arcs.push(Arc::new(email::read::EmailReadTool::new(email_client.clone())));
        tool_arcs.push(Arc::new(email::reply::EmailReplyTool::new(email_client.clone())));
        tool_arcs.push(Arc::new(email::compose::EmailComposeTool::new(email_client.clone())));
        tool_arcs.push(Arc::new(email::search::EmailSearchTool::new(email_client.clone())));
        tool_arcs.push(Arc::new(email::delete::EmailDeleteTool::new(email_client.clone())));
        tool_arcs.push(Arc::new(email::empty_trash::EmailEmptyTrashTool::new(email_client.clone())));
    }

    // Google Messages bridge tool (store resolved lazily at execution time).
    tool_arcs.push(Arc::new(crate::messages_bridge::tool::ReadMessagesTool::new_lazy()));

    let mut boxed = boxed_registry_from_arcs(tool_arcs);
    boxed.extend(create_global_extra_tools());
    boxed
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::{BrowserConfig, Config, MemoryConfig};
    use tempfile::TempDir;

    fn test_config(tmp: &TempDir) -> Config {
        Config {
            workspace_dir: tmp.path().join("workspace"),
            config_path: tmp.path().join("config.toml"),
            ..Config::default()
        }
    }

    #[test]
    fn default_tools_has_expected_count() {
        let security = Arc::new(SecurityPolicy::default());
        let tools = default_tools(security);
        assert_eq!(tools.len(), 6);
    }

    #[test]
    fn all_tools_excludes_browser_when_disabled() {
        let tmp = TempDir::new().unwrap();
        let security = Arc::new(SecurityPolicy::default());
        let mem_cfg = MemoryConfig {
            backend: "markdown".into(),
            ..MemoryConfig::default()
        };
        let mem: Arc<dyn Memory> =
            Arc::from(crate::memory::create_memory(&mem_cfg, tmp.path(), None).unwrap());

        let browser = BrowserConfig {
            enabled: false,
            allowed_domains: vec!["example.com".into()],
            session_name: None,
            ..BrowserConfig::default()
        };
        let http = crate::config::HttpRequestConfig::default();
        let cfg = test_config(&tmp);

        let tools = all_tools(
            Arc::new(Config::default()),
            &security,
            mem,
            None,
            None,
            &browser,
            &http,
            &crate::config::WebFetchConfig::default(),
            tmp.path(),
            &HashMap::new(),
            None,
            &cfg,
        );
        let names: Vec<&str> = tools.iter().map(|t| t.name()).collect();
        assert!(!names.contains(&"browser_open"));
        assert!(names.contains(&"schedule"));
        assert!(names.contains(&"model_routing_config"));
        assert!(names.contains(&"pushover"));
        assert!(names.contains(&"proxy_config"));
    }

    #[test]
    fn all_tools_includes_browser_when_enabled() {
        let tmp = TempDir::new().unwrap();
        let security = Arc::new(SecurityPolicy::default());
        let mem_cfg = MemoryConfig {
            backend: "markdown".into(),
            ..MemoryConfig::default()
        };
        let mem: Arc<dyn Memory> =
            Arc::from(crate::memory::create_memory(&mem_cfg, tmp.path(), None).unwrap());

        let browser = BrowserConfig {
            enabled: true,
            allowed_domains: vec!["example.com".into()],
            session_name: None,
            ..BrowserConfig::default()
        };
        let http = crate::config::HttpRequestConfig::default();
        let cfg = test_config(&tmp);

        let tools = all_tools(
            Arc::new(Config::default()),
            &security,
            mem,
            None,
            None,
            &browser,
            &http,
            &crate::config::WebFetchConfig::default(),
            tmp.path(),
            &HashMap::new(),
            None,
            &cfg,
        );
        let names: Vec<&str> = tools.iter().map(|t| t.name()).collect();
        assert!(names.contains(&"browser_open"));
        assert!(names.contains(&"content_search"));
        assert!(names.contains(&"model_routing_config"));
        assert!(names.contains(&"pushover"));
        assert!(names.contains(&"proxy_config"));
    }

    #[test]
    fn default_tools_names() {
        let security = Arc::new(SecurityPolicy::default());
        let tools = default_tools(security);
        let names: Vec<&str> = tools.iter().map(|t| t.name()).collect();
        assert!(names.contains(&"shell"));
        assert!(names.contains(&"file_read"));
        assert!(names.contains(&"file_write"));
        assert!(names.contains(&"file_edit"));
        assert!(names.contains(&"glob_search"));
        assert!(names.contains(&"content_search"));
    }

    #[test]
    fn default_tools_all_have_descriptions() {
        let security = Arc::new(SecurityPolicy::default());
        let tools = default_tools(security);
        for tool in &tools {
            assert!(
                !tool.description().is_empty(),
                "Tool {} has empty description",
                tool.name()
            );
        }
    }

    #[test]
    fn default_tools_all_have_schemas() {
        let security = Arc::new(SecurityPolicy::default());
        let tools = default_tools(security);
        for tool in &tools {
            let schema = tool.parameters_schema();
            assert!(
                schema.is_object(),
                "Tool {} schema is not an object",
                tool.name()
            );
            assert!(
                schema["properties"].is_object(),
                "Tool {} schema has no properties",
                tool.name()
            );
        }
    }

    #[test]
    fn tool_spec_generation() {
        let security = Arc::new(SecurityPolicy::default());
        let tools = default_tools(security);
        for tool in &tools {
            let spec = tool.spec();
            assert_eq!(spec.name, tool.name());
            assert_eq!(spec.description, tool.description());
            assert!(spec.parameters.is_object());
        }
    }

    #[test]
    fn tool_result_serde() {
        let result = ToolResult {
            success: true,
            output: "hello".into(),
            error: None,
        };
        let json = serde_json::to_string(&result).unwrap();
        let parsed: ToolResult = serde_json::from_str(&json).unwrap();
        assert!(parsed.success);
        assert_eq!(parsed.output, "hello");
        assert!(parsed.error.is_none());
    }

    #[test]
    fn tool_result_with_error_serde() {
        let result = ToolResult {
            success: false,
            output: String::new(),
            error: Some("boom".into()),
        };
        let json = serde_json::to_string(&result).unwrap();
        let parsed: ToolResult = serde_json::from_str(&json).unwrap();
        assert!(!parsed.success);
        assert_eq!(parsed.error.as_deref(), Some("boom"));
    }

    #[test]
    fn tool_spec_serde() {
        let spec = ToolSpec {
            name: "test".into(),
            description: "A test tool".into(),
            parameters: serde_json::json!({"type": "object"}),
        };
        let json = serde_json::to_string(&spec).unwrap();
        let parsed: ToolSpec = serde_json::from_str(&json).unwrap();
        assert_eq!(parsed.name, "test");
        assert_eq!(parsed.description, "A test tool");
    }

    #[test]
    fn all_tools_includes_delegate_when_agents_configured() {
        let tmp = TempDir::new().unwrap();
        let security = Arc::new(SecurityPolicy::default());
        let mem_cfg = MemoryConfig {
            backend: "markdown".into(),
            ..MemoryConfig::default()
        };
        let mem: Arc<dyn Memory> =
            Arc::from(crate::memory::create_memory(&mem_cfg, tmp.path(), None).unwrap());

        let browser = BrowserConfig::default();
        let http = crate::config::HttpRequestConfig::default();
        let cfg = test_config(&tmp);

        let mut agents = HashMap::new();
        agents.insert(
            "researcher".to_string(),
            DelegateAgentConfig {
                provider: "ollama".to_string(),
                model: "llama3".to_string(),
                system_prompt: None,
                api_key: None,
                temperature: None,
                max_depth: 3,
                agentic: false,
                allowed_tools: Vec::new(),
                max_iterations: 10,
            },
        );

        let tools = all_tools(
            Arc::new(Config::default()),
            &security,
            mem,
            None,
            None,
            &browser,
            &http,
            &crate::config::WebFetchConfig::default(),
            tmp.path(),
            &agents,
            Some("delegate-test-credential"),
            &cfg,
        );
        let names: Vec<&str> = tools.iter().map(|t| t.name()).collect();
        assert!(names.contains(&"delegate"));
    }

    #[test]
    fn all_tools_excludes_delegate_when_no_agents() {
        let tmp = TempDir::new().unwrap();
        let security = Arc::new(SecurityPolicy::default());
        let mem_cfg = MemoryConfig {
            backend: "markdown".into(),
            ..MemoryConfig::default()
        };
        let mem: Arc<dyn Memory> =
            Arc::from(crate::memory::create_memory(&mem_cfg, tmp.path(), None).unwrap());

        let browser = BrowserConfig::default();
        let http = crate::config::HttpRequestConfig::default();
        let cfg = test_config(&tmp);

        let tools = all_tools(
            Arc::new(Config::default()),
            &security,
            mem,
            None,
            None,
            &browser,
            &http,
            &crate::config::WebFetchConfig::default(),
            tmp.path(),
            &HashMap::new(),
            None,
            &cfg,
        );
        let names: Vec<&str> = tools.iter().map(|t| t.name()).collect();
        assert!(!names.contains(&"delegate"));
    }
}

#[cfg(test)]
mod extract_host_tests {
    use super::extract_host;

    #[test]
    fn extracts_domain_from_https() {
        assert_eq!(
            extract_host("https://example.com/path").unwrap(),
            "example.com"
        );
    }

    #[test]
    fn extracts_domain_with_port() {
        assert_eq!(
            extract_host("https://api.example.com:8443/v1").unwrap(),
            "api.example.com"
        );
    }

    #[test]
    fn rejects_ftp_scheme() {
        assert!(extract_host("ftp://example.com").is_err());
    }

    #[test]
    fn rejects_userinfo() {
        assert!(extract_host("https://user:pass@example.com").is_err());
    }
}
