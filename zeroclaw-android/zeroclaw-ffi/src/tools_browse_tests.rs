// Copyright (c) 2026 @Natfii. All rights reserved.

//! Unit tests for `crate::tools_browse`.

#![allow(clippy::unwrap_used)]

use super::*;

#[test]
fn eval_script_in_script_tool_denylist() {
    assert!(
        SCRIPT_TOOL_DENYLIST.contains(&"eval_script"),
        "eval_script must be in SCRIPT_TOOL_DENYLIST to prevent recursive script execution"
    );
}

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
    assert_eq!(CORE_TOOLS.len(), 14);
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
