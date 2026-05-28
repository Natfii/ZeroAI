// Copyright (c) 2026 @Natfii. All rights reserved.

//! Constructs the live agent session's tools registry.
//!
//! Returns a `Vec<Box<dyn Tool>>` made up of FFI tool wrappers (memory,
//! web search, web fetch, http request, shared folder), upstream cron
//! tools, plugin script tools, and the messages bridge tool when its
//! store is available. The list is capped at
//! [`MAX_SESSION_TOOLS`](crate::session::MAX_SESSION_TOOLS) entries to
//! protect the LLM's tool-spec budget.

use std::sync::Arc;
use std::time::Duration;

use zeroclaw::memory::Memory;
use zeroclaw::tools::Tool;

use crate::session::DEFAULT_USER_AGENT;

/// Maximum number of tools registered in a single session.
///
/// Prevents excessive token consumption when many plugins are enabled.
/// The LLM receives tool specs as part of the system prompt; each tool
/// costs 200-500 tokens. Beyond this limit, lower-priority tools are
/// silently dropped and a warning is logged.
const MAX_SESSION_TOOLS: usize = 20;
use crate::session_tools::{
    FfiHttpRequestTool, FfiMemoryForgetTool, FfiMemoryStoreTool, FfiWebFetchTool, FfiWebSearchTool,
    resolve_ffi_provider,
};
use crate::url_helpers;

#[allow(clippy::too_many_lines)]
pub(crate) fn build_tools_registry(
    config: &zeroclaw::Config,
    memory: Arc<dyn Memory>,
) -> Vec<Box<dyn Tool>> {
    let config_arc = Arc::new(config.clone());
    let mut tools: Vec<Box<dyn Tool>> = vec![
        Box::new(FfiMemoryStoreTool {
            memory: memory.clone(),
        }),
        Box::new(zeroclaw::tools::MemoryRecallTool::new(memory.clone())),
        // `MemorySearchTool` was removed upstream during the memory
        // crate split; `MemoryRecallTool` covers the common case for
        // the Android agent.
        Box::new(FfiMemoryForgetTool { memory }),
        Box::new(zeroclaw::tools::CronListTool::new(config_arc.clone())),
        Box::new(zeroclaw::tools::CronRunsTool::new(config_arc)),
    ];
    tools.push(Box::new(crate::eval_script_tool::EvalScriptTool::new()));

    if config.web_search.enabled {
        // Upstream renamed `WebSearchConfig.provider` → `search_provider`
        // (already wired below) and replaced Google CSE
        // (`google_api_key` / `google_cx`) with Tavily and SearXNG.
        // The Tavily/SearXNG keys exist on the upstream config
        // (`tavily_api_key`, `searxng_instance_url`) but the Android
        // settings UI does not surface them yet, so this site still
        // only routes Brave.
        let provider = resolve_ffi_provider(
            &config.web_search.search_provider,
            config.web_search.brave_api_key.as_ref(),
            None,
            None,
        );
        match reqwest::Client::builder()
            .timeout(Duration::from_secs(config.web_search.timeout_secs))
            .build()
        {
            Ok(client) => {
                tools.push(Box::new(FfiWebSearchTool {
                    provider,
                    brave_api_key: config.web_search.brave_api_key.clone(),
                    google_api_key: None,
                    google_cx: None,
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

    // Android-embedder-local tools: Twitter syndication reader, the
    // SAF-backed shared-folder shim, and the Google Messages bridge.
    // The same helper is used by the `EXTRA_TOOLS_FACTORY` factory in
    // `runtime.rs` so the channel orchestrator, gateway, and agent
    // loops see the same tools the Terminal session sees — see
    // `android_local_tools.rs` for the single source of truth.
    tools.extend(crate::android_local_tools::android_local_tools());

    // Disabled: the entire `tools::email` module (EmailConfig +
    // EmailClient + check/read/reply/compose/search/delete tools) was
    // removed upstream during the workspace split. The channel-side
    // `zeroclaw_config::scattered_types::EmailConfig` still exists for
    // when email channels are needed.

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
