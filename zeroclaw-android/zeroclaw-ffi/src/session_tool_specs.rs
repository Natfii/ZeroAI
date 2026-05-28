// Copyright (c) 2026 @Natfii. All rights reserved.

//! Tool-spec helpers for the live agent session.
//!
//! Pure helpers that translate Android-specific tool metadata into the
//! [`ToolSpec`] format providers expect, plus a prompt-side renderer for
//! providers that do not support native tool calling.

use std::fmt::Write;

use zeroclaw::tools::{Tool, ToolSpec};

/// Generates [`ToolSpec`] metadata from a runtime tools registry.
///
/// Uses each tool's [`Tool::spec`] method to produce the name,
/// description, and JSON parameter schema that the provider uses for
/// native tool calling.
pub(crate) fn tool_specs_from_registry(tools: &[Box<dyn Tool>]) -> Vec<ToolSpec> {
    tools.iter().map(|t| t.spec()).collect()
}

/// Builds tool specifications for the Android-appropriate tool set.
///
/// These specs are passed to `provider.chat()` so the LLM is aware of
/// available tools. Because upstream's `SecurityPolicy` is `pub(crate)`,
/// we cannot instantiate actual tool objects here; these specs serve
/// only as metadata for the provider's native tool calling protocol.
pub(crate) fn build_android_tool_specs(config: &zeroclaw::Config) -> Vec<ToolSpec> {
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
/// When the provider does not support native tool calling, the model
/// needs explicit instructions on how to emit tool calls using
/// `<tool_call>` XML tags. Mirrors upstream
/// `build_tool_instructions_from_specs()` in `agent/loop_.rs` but works
/// with the FFI session's tool registry and static tool descriptions.
pub(crate) fn build_tool_use_protocol(
    tools_registry: &[Box<dyn Tool>],
    config: &zeroclaw::Config,
) -> String {
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

    for tool in tools_registry {
        let spec = tool.spec();
        let _ = writeln!(
            out,
            "**{}**: {}\nParameters: `{}`\n",
            spec.name, spec.description, spec.parameters
        );
    }

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

/// Static descriptions for tools the Android agent advertises.
///
/// Includes the always-on memory/cron tools plus optional web tools
/// gated on config flags.
pub(crate) fn build_android_tool_descs(config: &zeroclaw::Config) -> Vec<(String, String)> {
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
            "memory_search".into(),
            "Search long-term memory with scored ranking. Use before \
             storing to check for duplicates. Returns facts sorted by \
             relevance, recency, and access frequency."
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

    descs
}
