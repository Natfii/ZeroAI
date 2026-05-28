// Copyright (c) 2026 @Natfii. All rights reserved.

//! Unit tests for `crate::repl`.

#![allow(clippy::unwrap_used)]

use super::*;

#[test]
fn eval_script_still_supports_basic_expressions() {
    let result = eval_script_inner("2 + 3".into()).unwrap();
    assert_eq!(result, "5");
}

#[test]
fn validate_script_returns_requested_capabilities() {
    let validation = validate_script_inner(r"agent::status(); memory_count();".into()).unwrap();
    assert!(
        validation
            .requested_capabilities
            .contains(&"agent.read".to_string())
    );
    assert!(
        validation
            .requested_capabilities
            .contains(&"memory.read".to_string())
    );
}

#[test]
fn capability_listing_includes_default_denies() {
    let capabilities = list_script_capabilities_inner();
    assert!(capabilities.contains(&"net.none".to_string()));
    assert!(capabilities.contains(&"model.chat".to_string()));
}

#[test]
fn explicit_empty_capabilities_deny_host_calls() {
    let error =
        eval_script_with_capabilities_inner(r#"send("hello");"#.into(), vec![]).unwrap_err();
    assert!(matches!(error, FfiError::InvalidArgument { .. }));
    assert!(error.to_string().contains("capability denied"));
}

#[test]
fn list_script_runtimes_reports_rhai() {
    let runtimes = list_script_runtimes_inner();
    assert!(
        runtimes
            .iter()
            .any(|runtime| runtime.kind == "rhai" && runtime.available)
    );
}

#[test]
fn plugin_host_wit_is_exposed() {
    let wit = script_plugin_host_wit_inner();
    assert!(wit.contains("world zero-scripting-plugin"));
}

#[test]
fn list_workspace_scripts_surfaces_manifests() {
    let dir = tempfile::tempdir().unwrap();
    let workflows_dir = dir.path().join("workflows");
    std::fs::create_dir_all(&workflows_dir).unwrap();
    std::fs::write(workflows_dir.join("cleanup.rhai"), "2 + 2").unwrap();

    let manifests = list_workspace_scripts_in_dir(dir.path());
    assert!(
        manifests
            .iter()
            .any(|manifest| manifest.relative_path == "workflows/cleanup.rhai")
    );
}
