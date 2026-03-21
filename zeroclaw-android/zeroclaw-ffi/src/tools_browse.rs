/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

//! Tool inventory browsing and invocation for the Android dashboard.
//!
//! Enumerates all available tools from the daemon config and installed
//! skills without instantiating the actual tool objects (which require
//! runtime dependencies like security policies and memory backends).
//!
//! Also provides [`invoke_tool_inner`] for executing tools by name from
//! the scripting runtime.

use once_cell::sync::OnceCell;

use crate::error::FfiError;

static SCRIPT_TOOL_RT: OnceCell<tokio::runtime::Runtime> = OnceCell::new();

/// Returns the dedicated single-threaded Tokio runtime used for script tool callbacks.
///
/// A separate `current_thread` runtime prevents thread-pool exhaustion deadlocks that
/// occur when `invoke_tool_inner` is called from inside a `spawn_blocking` task on the
/// main multi-thread scheduler (e.g. the cron scheduler). Each call reuses the same
/// lazily-initialised runtime for the lifetime of the process.
///
/// # Errors
///
/// Returns [`FfiError::SpawnError`] if the runtime cannot be created (OS resource
/// exhaustion or similar).
fn script_tool_runtime() -> Result<&'static tokio::runtime::Runtime, FfiError> {
    SCRIPT_TOOL_RT
        .get_or_try_init(|| {
            tokio::runtime::Builder::new_current_thread()
                .enable_all()
                .build()
        })
        .map_err(|e| FfiError::SpawnError {
            detail: format!("failed to create script tool runtime: {e}"),
        })
}

/// A tool specification suitable for display in the Android tools browser.
///
/// Contains metadata about a tool without the actual tool instance, making
/// it safe and lightweight for FFI transfer.
#[derive(Debug, Clone, serde::Serialize, uniffi::Record)]
pub struct FfiToolSpec {
    /// Unique tool name (e.g. `"shell"`, `"file_read"`).
    pub name: String,
    /// Human-readable description of the tool.
    pub description: String,
    /// Origin of the tool: `"built-in"` or the skill name.
    pub source: String,
    /// JSON schema for the tool parameters, or `"{}"` if unavailable.
    pub parameters_json: String,
    /// Whether the tool is usable in the current Android session.
    ///
    /// Session-available tools (memory, cron, web tools) are active.
    /// Tools requiring a `SecurityPolicy` (shell, file I/O, git) are
    /// inactive because they can only execute via daemon channel routing.
    pub is_active: bool,
    /// Human-readable reason the tool is inactive, or empty string when active.
    ///
    /// Common values:
    /// - `""` -- tool is active
    /// - `"Available via daemon channels only"` -- requires `SecurityPolicy`
    /// - `"Disabled in settings"` -- config flag is off
    pub inactive_reason: String,
}

/// Describes a built-in tool with a static name and description.
struct BuiltInTool {
    /// Tool name as registered in the tool registry.
    name: &'static str,
    /// Brief description of what the tool does.
    description: &'static str,
}

/// Static list of all core built-in tools.
///
/// These tools are always available when the daemon is running.
const CORE_TOOLS: &[BuiltInTool] = &[
    BuiltInTool {
        name: "shell",
        description: "Execute shell commands with security policy enforcement",
    },
    BuiltInTool {
        name: "file_read",
        description: "Read file contents with path validation",
    },
    BuiltInTool {
        name: "file_write",
        description: "Write content to files with path validation",
    },
    BuiltInTool {
        name: "memory_store",
        description: "Store a key-value pair in the memory backend",
    },
    BuiltInTool {
        name: "memory_recall",
        description: "Recall memories matching a keyword query",
    },
    BuiltInTool {
        name: "memory_forget",
        description: "Remove a memory entry by key",
    },
    BuiltInTool {
        name: "cron_list",
        description: "List all cron jobs with schedule, status, and metadata",
    },
    BuiltInTool {
        name: "cron_runs",
        description: "Show recent and upcoming cron job executions",
    },
    BuiltInTool {
        name: "schedule",
        description: "Schedule cron jobs and one-shot delayed tasks",
    },
    BuiltInTool {
        name: "git_operations",
        description: "Perform git operations in the workspace directory",
    },
    BuiltInTool {
        name: "screenshot",
        description: "Capture screenshots with security policy enforcement",
    },
    BuiltInTool {
        name: "image_info",
        description: "Extract metadata and dimensions from image files",
    },
];

/// Optional tools that depend on config flags.
const BROWSER_TOOLS: &[BuiltInTool] = &[
    BuiltInTool {
        name: "browser_open",
        description: "Open a URL in a headless or remote browser",
    },
    BuiltInTool {
        name: "browser",
        description: "Full browser automation (navigation, clicks, screenshots)",
    },
];

/// Web search tool (available when web search is enabled).
const WEB_SEARCH_TOOL: BuiltInTool = BuiltInTool {
    name: "web_search",
    description: "Search the web and return structured results",
};

/// HTTP request tool (available when HTTP is enabled).
const HTTP_TOOL: BuiltInTool = BuiltInTool {
    name: "http_request",
    description: "Make HTTP requests with domain allowlist enforcement",
};

/// Composio integration tool (available when Composio API key is set).
const COMPOSIO_TOOL: BuiltInTool = BuiltInTool {
    name: "composio",
    description: "Access Composio integrations for third-party APIs",
};

/// Delegate tool (available when agent delegation is configured).
const DELEGATE_TOOL: BuiltInTool = BuiltInTool {
    name: "delegate",
    description: "Delegate tasks to sub-agents with independent context",
};

/// Shared folder tools (available when shared folder plugin is enabled).
const SHARED_FOLDER_TOOLS: &[BuiltInTool] = &[
    BuiltInTool {
        name: "shared_folder_list",
        description: "List files and directories in the shared folder",
    },
    BuiltInTool {
        name: "shared_folder_read",
        description: "Read a file from the shared folder",
    },
    BuiltInTool {
        name: "shared_folder_write",
        description: "Write a file or create a directory in the shared folder",
    },
];

/// Google Messages bridge tool (available when paired).
const MESSAGES_BRIDGE_TOOLS: &[BuiltInTool] = &[BuiltInTool {
    name: "read_messages",
    description: "Read text messages from allowlisted Google Messages conversations",
}];

/// Tools available in the Android session without a [`SecurityPolicy`].
///
/// Memory and cron tools run directly in the FFI session and are always
/// active when the daemon is running.
const SESSION_TOOLS: &[&str] = &[
    "memory_store",
    "memory_recall",
    "memory_forget",
    "cron_list",
    "cron_runs",
];

/// Tools that require a [`SecurityPolicy`] and can only execute via daemon
/// channel routing (e.g. Telegram, Discord).
///
/// These are listed in the tool browser for visibility but cannot be
/// invoked from the Android session directly.
///
/// Used in tests to validate that every core tool is classified as either
/// a session tool or a security-policy tool.
#[cfg(test)]
const SECURITY_POLICY_TOOLS: &[&str] = &[
    "shell",
    "file_read",
    "file_write",
    "schedule",
    "git_operations",
    "screenshot",
    "image_info",
];

/// Tool names that are incompatible with Android and should be hidden
/// from the tools browser UI. These tools require desktop CLI binaries
/// or capabilities not available on Android.
const ANDROID_EXCLUDED_TOOLS: &[&str] = &["browser", "screenshot"];

/// Tools that scripts are never permitted to invoke, regardless of config.
///
/// This hard denylist blocks execution of privileged shell tools from the
/// scripting runtime. Even if the daemon config enables these tools for
/// channel routing, scripts must not be able to execute arbitrary shell
/// commands without a [`SecurityPolicy`] gate.
///
/// `shell_background` is included as a forward-looking guard for when
/// non-blocking shell execution is added.
const SCRIPT_TOOL_DENYLIST: &[&str] = &["shell", "shell_background"];

/// Inactive reason for tools that require daemon channel routing.
const REASON_DAEMON_ONLY: &str = "Available via daemon channels only";

/// Converts a [`BuiltInTool`] to an [`FfiToolSpec`] with `"built-in"` source.
///
/// The default active status is determined by whether the tool name appears
/// in [`SESSION_TOOLS`] (active) or [`SECURITY_POLICY_TOOLS`] (inactive).
/// Conditional tools (browser, HTTP, composio, delegate) default to inactive
/// and are overridden to active when added by [`list_tools_inner`].
fn builtin_to_spec(tool: &BuiltInTool) -> FfiToolSpec {
    let is_session = SESSION_TOOLS.contains(&tool.name);
    FfiToolSpec {
        name: tool.name.to_string(),
        description: tool.description.to_string(),
        source: "built-in".to_string(),
        parameters_json: "{}".to_string(),
        is_active: is_session,
        inactive_reason: if is_session {
            String::new()
        } else {
            REASON_DAEMON_ONLY.to_string()
        },
    }
}

/// Lists all available tools based on daemon configuration and installed skills.
///
/// Enumerates built-in tools that are always active, conditionally adds
/// browser/HTTP/Composio/delegate tools based on config flags, then
/// appends tools from all installed skills.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running.
pub(crate) fn list_tools_inner() -> Result<Vec<FfiToolSpec>, FfiError> {
    let (
        workspace_dir,
        browser_enabled,
        http_enabled,
        web_search_enabled,
        composio_key,
        has_agents,
        shared_folder_enabled,
    ) = crate::runtime::with_daemon_config(|config| {
        (
            config.workspace_dir.clone(),
            config.browser.enabled,
            config.http_request.enabled,
            config.web_search.enabled,
            config.composio.api_key.clone(),
            !config.agents.is_empty(),
            config.shared_folder.enabled,
        )
    })?;

    let mut specs: Vec<FfiToolSpec> = CORE_TOOLS
        .iter()
        .filter(|t| !ANDROID_EXCLUDED_TOOLS.contains(&t.name))
        .map(builtin_to_spec)
        .collect();

    if browser_enabled {
        specs.extend(
            BROWSER_TOOLS
                .iter()
                .filter(|t| !ANDROID_EXCLUDED_TOOLS.contains(&t.name))
                .map(|t| {
                    let mut s = builtin_to_spec(t);
                    s.is_active = true;
                    s.inactive_reason = String::new();
                    s
                }),
        );
    }

    if web_search_enabled {
        let mut s = builtin_to_spec(&WEB_SEARCH_TOOL);
        s.is_active = true;
        s.inactive_reason = String::new();
        specs.push(s);
    }

    if http_enabled {
        let mut s = builtin_to_spec(&HTTP_TOOL);
        s.is_active = true;
        s.inactive_reason = String::new();
        specs.push(s);
    }

    if composio_key.as_ref().is_some_and(|k| !k.is_empty()) {
        let mut s = builtin_to_spec(&COMPOSIO_TOOL);
        s.is_active = true;
        s.inactive_reason = String::new();
        specs.push(s);
    }

    if has_agents {
        let mut s = builtin_to_spec(&DELEGATE_TOOL);
        s.is_active = true;
        s.inactive_reason = String::new();
        specs.push(s);
    }

    if shared_folder_enabled {
        for tool in SHARED_FOLDER_TOOLS {
            let mut s = builtin_to_spec(tool);
            s.is_active = true;
            s.inactive_reason = String::new();
            specs.push(s);
        }
    }

    for tool in MESSAGES_BRIDGE_TOOLS {
        let mut s = builtin_to_spec(tool);
        s.is_active = zeroclaw::messages_bridge::session::get_store().is_some();
        if !s.is_active {
            s.inactive_reason = "Google Messages not paired".to_string();
        }
        specs.push(s);
    }

    let skills = crate::skills::load_skills_from_workspace(&workspace_dir);
    for loaded in &skills {
        for tool in &loaded.tools {
            specs.push(FfiToolSpec {
                name: tool.name.clone(),
                description: tool.description.clone(),
                source: loaded.manifest.name.clone(),
                parameters_json: "{}".to_string(),
                is_active: true,
                inactive_reason: String::new(),
            });
        }
    }

    Ok(specs)
}

/// Invokes a tool by name with the given JSON arguments.
///
/// Builds a fresh tools registry from the running daemon's config and
/// memory backend, locates the named tool, and executes it on the tokio
/// runtime. Returns the tool's output string on success.
///
/// # Errors
///
/// Returns [`FfiError::InvalidArgument`] if `args_json` is not valid JSON.
/// Returns [`FfiError::StateError`] if the daemon is not running, the
/// memory backend is unavailable, or no tool with the given name exists.
/// Returns [`FfiError::StateError`] if the tool execution itself fails.
pub(crate) fn invoke_tool_inner(name: &str, args_json: &str) -> Result<String, FfiError> {
    // Hard denylist: refuse execution of privileged shell tools from scripts
    // unconditionally, before any config or tool-registry lookup.
    if SCRIPT_TOOL_DENYLIST.contains(&name) {
        return Err(FfiError::InvalidArgument {
            detail: format!("tool '{name}' is not available to scripts"),
        });
    }

    // TODO: integrate SecurityPolicy::can_act() check when wired through.
    // Once SecurityPolicy is accessible here (e.g. via clone_daemon_security_policy()),
    // add: if !policy.can_act(name) { return Err(FfiError::InvalidArgument { ... }) }

    let args: serde_json::Value =
        serde_json::from_str(args_json).map_err(|e| FfiError::InvalidArgument {
            detail: format!("invalid tool arguments JSON: {e}"),
        })?;

    let config = crate::runtime::clone_daemon_config()?;
    let memory = crate::runtime::clone_daemon_memory()?;

    let tools = crate::session::build_tools_registry(&config, memory);

    let tool = tools
        .iter()
        .find(|t| t.name() == name)
        .ok_or_else(|| FfiError::StateError {
            detail: format!("tool not found: {name}"),
        })?;

    let rt = script_tool_runtime()?;
    let result = rt.block_on(tool.execute(args)).map_err(|e| FfiError::StateError {
        detail: format!("tool execution failed: {e}"),
    })?;

    if result.success {
        Ok(result.output)
    } else {
        Err(FfiError::StateError {
            detail: result
                .error
                .unwrap_or_else(|| "tool failed without error message".into()),
        })
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    #[test]
    fn test_list_tools_not_running() {
        let result = list_tools_inner();
        assert!(result.is_err());
        match result.unwrap_err() {
            FfiError::StateError { detail } => {
                assert!(detail.contains("not running"));
            }
            other => panic!("expected StateError, got {other:?}"),
        }
    }

    #[test]
    fn test_core_tools_count() {
        assert_eq!(CORE_TOOLS.len(), 12);
    }

    #[test]
    fn test_builtin_to_spec() {
        let tool = &CORE_TOOLS[0];
        let spec = builtin_to_spec(tool);
        assert_eq!(spec.name, "shell");
        assert_eq!(spec.source, "built-in");
        assert_eq!(spec.parameters_json, "{}");
        assert!(!spec.description.is_empty());
    }

    #[test]
    fn test_browser_tools_count() {
        assert_eq!(BROWSER_TOOLS.len(), 2);
    }

    #[test]
    fn test_session_tools_are_active() {
        for &name in SESSION_TOOLS {
            let tool = CORE_TOOLS
                .iter()
                .find(|t| t.name == name)
                .unwrap_or_else(|| panic!("session tool {name} missing from CORE_TOOLS"));
            let spec = builtin_to_spec(tool);
            assert!(spec.is_active, "{name} should be active");
            assert!(
                spec.inactive_reason.is_empty(),
                "{name} should have empty inactive_reason"
            );
        }
    }

    #[test]
    fn test_security_policy_tools_are_inactive() {
        for &name in SECURITY_POLICY_TOOLS {
            let tool = CORE_TOOLS
                .iter()
                .find(|t| t.name == name)
                .unwrap_or_else(|| panic!("security tool {name} missing from CORE_TOOLS"));
            let spec = builtin_to_spec(tool);
            assert!(!spec.is_active, "{name} should be inactive");
            assert_eq!(
                spec.inactive_reason, REASON_DAEMON_ONLY,
                "{name} should have daemon-only reason"
            );
        }
    }

    #[test]
    fn test_session_and_security_cover_all_core_tools() {
        for tool in CORE_TOOLS {
            assert!(
                SESSION_TOOLS.contains(&tool.name) || SECURITY_POLICY_TOOLS.contains(&tool.name),
                "core tool {:?} is in neither SESSION_TOOLS nor SECURITY_POLICY_TOOLS",
                tool.name
            );
        }
    }

    #[test]
    fn test_excluded_tools_not_in_core_filtered() {
        let filtered: Vec<&BuiltInTool> = CORE_TOOLS
            .iter()
            .filter(|t| !ANDROID_EXCLUDED_TOOLS.contains(&t.name))
            .collect();
        assert!(!filtered.iter().any(|t| t.name == "screenshot"));
        assert!(filtered.iter().any(|t| t.name == "shell"));
    }

    #[test]
    fn test_excluded_tools_not_in_browser_filtered() {
        let filtered: Vec<&BuiltInTool> = BROWSER_TOOLS
            .iter()
            .filter(|t| !ANDROID_EXCLUDED_TOOLS.contains(&t.name))
            .collect();
        assert!(!filtered.iter().any(|t| t.name == "browser"));
        assert!(filtered.iter().any(|t| t.name == "browser_open"));
    }

    #[test]
    fn test_conditional_tools_default_inactive() {
        let web_search = builtin_to_spec(&WEB_SEARCH_TOOL);
        assert!(
            !web_search.is_active,
            "web_search should default to inactive"
        );

        let http = builtin_to_spec(&HTTP_TOOL);
        assert!(!http.is_active, "http_request should default to inactive");
        assert_eq!(http.inactive_reason, REASON_DAEMON_ONLY);

        let composio = builtin_to_spec(&COMPOSIO_TOOL);
        assert!(!composio.is_active, "composio should default to inactive");

        let delegate = builtin_to_spec(&DELEGATE_TOOL);
        assert!(!delegate.is_active, "delegate should default to inactive");

        for browser_tool in BROWSER_TOOLS {
            let spec = builtin_to_spec(browser_tool);
            assert!(
                !spec.is_active,
                "{} should default to inactive",
                browser_tool.name
            );
        }
    }
}
