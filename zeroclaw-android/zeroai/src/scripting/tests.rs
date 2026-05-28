// Copyright (c) 2026 @Natfii. All rights reserved.

//! Integration tests for the scripting module.

use super::*;
use crate::scripting::capabilities::{is_safe_url, open_workspace_file};
use std::collections::HashMap;
use std::path::Path;
use std::sync::Arc;

struct TestHost {
    responses: HashMap<ScriptOperation, ScriptValue>,
}

impl Default for TestHost {
    fn default() -> Self {
        let mut responses = HashMap::new();
        responses.insert(
            ScriptOperation::Status,
            ScriptValue::String(r#"{"daemon_running":false}"#.to_string()),
        );
        responses.insert(
            ScriptOperation::Version,
            ScriptValue::String("0.0.37".to_string()),
        );
        responses.insert(
            ScriptOperation::SendMessage,
            ScriptValue::String("sent".to_string()),
        );
        responses.insert(
            ScriptOperation::HealthDetail,
            ScriptValue::String(r#"{"healthy":true}"#.to_string()),
        );
        responses.insert(
            ScriptOperation::ListTools,
            ScriptValue::String(r#"["shell","memory_recall"]"#.to_string()),
        );
        responses.insert(
            ScriptOperation::ListMemories,
            ScriptValue::String(r#"["memory-a"]"#.to_string()),
        );
        responses.insert(
            ScriptOperation::RecallMemory,
            ScriptValue::String(r#"["memory-a"]"#.to_string()),
        );
        responses.insert(ScriptOperation::MemoryCount, ScriptValue::Int(2));
        responses.insert(ScriptOperation::ForgetMemory, ScriptValue::Bool(true));
        responses.insert(
            ScriptOperation::RecentEvents,
            ScriptValue::String(r#"["event-a"]"#.to_string()),
        );
        responses.insert(
            ScriptOperation::QueryTraces,
            ScriptValue::String(r#"["trace-a"]"#.to_string()),
        );
        responses.insert(
            ScriptOperation::ListSkills,
            ScriptValue::String(r#"["skill-a"]"#.to_string()),
        );
        Self { responses }
    }
}

impl ScriptHost for TestHost {
    fn call(
        &self,
        operation: ScriptOperation,
        _args: serde_json::Value,
    ) -> Result<ScriptValue, ScriptError> {
        self.responses
            .get(&operation)
            .cloned()
            .ok_or_else(|| ScriptError::HostError {
                operation: operation.display_name().to_string(),
                detail: "missing response".to_string(),
            })
    }
}

fn runtime() -> RhaiScriptRuntime {
    RhaiScriptRuntime::new(Arc::new(TestHost::default()))
}

#[test]
fn arithmetic_eval_still_works() {
    let result = runtime().eval_script("2 + 3", None).unwrap();
    assert_eq!(result, "5");
}

#[test]
fn validation_infers_flat_and_namespaced_capabilities() {
    let validation = runtime()
        .validate_script(
            r#"
            send("hello");
            memory::recall("topic", 5);
            tools::list();
        "#,
            None,
        )
        .unwrap();

    assert_eq!(
        validation.requested_capabilities,
        vec![
            "memory.read".to_string(),
            "model.chat".to_string(),
            "tools.read".to_string(),
        ]
    );
    assert!(validation
        .warnings
        .iter()
        .any(|warning| warning.contains("source-inferred")));
}

#[test]
fn explicit_manifest_missing_capability_is_reported() {
    let validation = runtime()
        .validate_script(
            r#"send("hello");"#,
            Some(ScriptManifest {
                name: "limited".to_string(),
                capabilities: vec!["memory.read".to_string()],
                ..Default::default()
            }),
        )
        .unwrap();

    assert_eq!(validation.missing_capabilities, vec!["model.chat".to_string()]);
}

#[test]
fn denied_capability_fails_execution() {
    let error = runtime()
        .eval_script(
            r#"send("hello");"#,
            Some(ScriptManifest {
                name: "limited".to_string(),
                capabilities: vec!["memory.read".to_string()],
                ..Default::default()
            }),
        )
        .unwrap_err();

    assert!(matches!(error, ScriptError::CapabilityDenied { .. }));
    assert!(error.to_string().contains("capability denied"));
}

#[test]
fn workspace_discovery_finds_workflows_and_skill_scripts() {
    let dir = tempfile::tempdir().unwrap();
    let workflow_dir = dir.path().join("workflows");
    std::fs::create_dir_all(&workflow_dir).unwrap();
    std::fs::write(workflow_dir.join("cleanup.rhai"), "status()").unwrap();

    let skill_dir = dir.path().join("skills").join("demo");
    std::fs::create_dir_all(skill_dir.join("scripts")).unwrap();
    std::fs::write(skill_dir.join("scripts").join("triage.rhai"), "memory_count()").unwrap();
    std::fs::write(
        skill_dir.join("SKILL.toml"),
        r#"
[skill]
name = "demo"
description = "demo skill"
version = "0.1.0"

permissions = ["memory.read"]

[[scripts]]
name = "triage"
path = "scripts/triage.rhai"

[[triggers]]
kind = "manual"
"#,
    )
    .unwrap();

    let manifests = discover_workspace_scripts(dir.path());
    assert_eq!(manifests.len(), 2);
    assert!(manifests.iter().any(|manifest| manifest.name.ends_with("cleanup.rhai")));
    assert!(manifests
        .iter()
        .any(|manifest| manifest.name == "demo::triage"
            && manifest.capabilities == vec!["memory.read".to_string()]));
}

#[test]
fn wit_definition_is_available() {
    let wit = runtime().plugin_host_wit();
    assert!(wit.contains("interface host"));
    assert!(wit.contains("invoke-tool"));
}

#[test]
fn guest_runtime_validation_is_deterministic() {
    let validation = runtime()
        .validate_script(
            "print('hello')",
            Some(ScriptManifest {
                name: "guest".to_string(),
                runtime: ScriptRuntimeKind::Python,
                capabilities: vec!["memory.read".to_string()],
                explicit_capabilities: true,
                ..Default::default()
            }),
        )
        .unwrap();

    assert_eq!(validation.runtime, ScriptRuntimeKind::Python);
    assert_eq!(validation.requested_capabilities, vec!["memory.read".to_string()]);
    assert!(validation
        .warnings
        .iter()
        .any(|warning| warning.contains("stable plugin ABI")));
}

#[test]
fn workspace_validation_uses_skill_manifest_capabilities() {
    let dir = tempfile::tempdir().unwrap();
    let skill_dir = dir.path().join("skills").join("demo");
    std::fs::create_dir_all(skill_dir.join("scripts")).unwrap();
    std::fs::write(
        skill_dir.join("SKILL.toml"),
        r#"
[skill]
name = "demo"
description = "demo skill"
version = "0.1.0"

permissions = ["memory.read"]

[[scripts]]
name = "triage"
path = "scripts/triage.rhai"
"#,
    )
    .unwrap();
    std::fs::write(skill_dir.join("scripts").join("triage.rhai"), "memory_count()").unwrap();

    let validation = runtime()
        .validate_workspace_script(
            dir.path(),
            Path::new("skills/demo/scripts/triage.rhai"),
            None,
        )
        .unwrap();

    assert_eq!(validation.requested_capabilities, vec!["memory.read".to_string()]);
}

#[test]
fn workspace_guest_runtime_is_blocked_until_enabled() {
    let dir = tempfile::tempdir().unwrap();
    let workflow_dir = dir.path().join("workflows");
    std::fs::create_dir_all(&workflow_dir).unwrap();
    std::fs::write(workflow_dir.join("guest.py"), "print('hello')").unwrap();

    let error = runtime()
        .eval_workspace_script(dir.path(), Path::new("workflows/guest.py"), None)
        .unwrap_err();

    assert!(matches!(error, ScriptError::ValidationError { .. }));
    assert!(error.to_string().contains("runtime 'python'"));
}

#[test]
fn explicit_empty_capabilities_stay_denied() {
    let validation = runtime()
        .validate_script(
            r#"send("hello");"#,
            Some(ScriptManifest {
                name: "deny-all".to_string(),
                explicit_capabilities: true,
                ..Default::default()
            }),
        )
        .unwrap();

    assert!(validation.requested_capabilities.is_empty());
    assert_eq!(validation.missing_capabilities, vec!["model.chat".to_string()]);
}

#[test]
fn new_operations_have_correct_capabilities() {
    assert_eq!(ScriptOperation::InvokeTool.capability(), "tools.call");
    assert_eq!(ScriptOperation::InvokeTool.display_name(), "tool_call");
    assert_eq!(ScriptOperation::ReadStorage.capability(), "storage.read");
    assert_eq!(ScriptOperation::WriteStorage.capability(), "storage.write");
    assert_eq!(ScriptOperation::DeleteStorage.capability(), "storage.write");
}

#[test]
fn wit_v0_2_0_has_required_functions() {
    let host = std::sync::Arc::new(StubScriptHost);
    let rt = RhaiScriptRuntime::new(host);
    let wit = rt.plugin_host_wit();
    assert!(wit.contains("@0.2.0"), "version");
    assert!(wit.contains("invoke-tool"), "invoke-tool");
    assert!(wit.contains("list-tools"), "list-tools");
    assert!(wit.contains("agent-status"), "agent-status");
    assert!(wit.contains("cron-list"), "cron-list");
    assert!(wit.contains("cost-summary"), "cost-summary");
    assert!(wit.contains("export run"), "guest run export");
}

#[test]
fn infinite_loop_is_terminated() {
    let host = Arc::new(StubScriptHost);
    let runtime = RhaiScriptRuntime::new(host);
    let result = runtime.eval_script("loop { }", None);
    assert!(result.is_err());
    let err = result.unwrap_err().to_string();
    assert!(
        err.contains("operations") || err.contains("timed out") || err.contains("progress"),
        "expected resource limit error, got: {err}"
    );
}

#[cfg(unix)]
#[test]
fn symlink_escape_is_blocked() {
    let workspace = tempfile::tempdir().unwrap();
    let escape_target = tempfile::tempdir().unwrap();
    let secret = escape_target.path().join("secret.txt");
    std::fs::write(&secret, "stolen data").unwrap();

    let link = workspace.path().join("escape");
    std::os::unix::fs::symlink(escape_target.path(), &link).unwrap();

    let result = open_workspace_file(
        workspace.path(),
        std::path::Path::new("escape/secret.txt"),
    );
    assert!(result.is_err(), "symlink escape should be blocked");
}

#[test]
fn is_safe_url_rejects_ipv4_mapped_ipv6() {
    assert!(is_safe_url("http://[::ffff:127.0.0.1]/api").is_err());
}

#[test]
fn is_safe_url_rejects_decimal_ip() {
    assert!(is_safe_url("http://2130706433/api").is_err());
}

#[test]
fn is_safe_url_rejects_rfc1918() {
    assert!(is_safe_url("http://10.0.0.1/api").is_err());
    assert!(is_safe_url("http://172.16.0.1/api").is_err());
    assert!(is_safe_url("http://192.168.1.1/api").is_err());
}

#[test]
fn is_safe_url_allows_public() {
    assert!(is_safe_url("https://api.openai.com/v1/models").is_ok());
}

#[test]
fn agent_eval_limits_have_higher_operations() {
    let agent = ScriptLimits::for_agent_eval();
    let default = ScriptLimits::default();
    assert_eq!(agent.max_operations, 10_000_000);
    assert_eq!(agent.max_call_levels, default.max_call_levels);
    assert_eq!(agent.max_expr_depth, default.max_expr_depth);
    assert_eq!(agent.max_string_size, default.max_string_size);
    assert_eq!(agent.max_array_size, default.max_array_size);
    assert_eq!(agent.max_map_size, default.max_map_size);
    assert_eq!(agent.max_script_bytes, default.max_script_bytes);
}

#[test]
fn agent_capabilities_without_nano() {
    let caps = build_agent_capabilities(false);
    assert!(caps.contains(&"storage.read".to_string()));
    assert!(caps.contains(&"storage.write".to_string()));
    assert!(caps.contains(&"memory.read".to_string()));
    assert!(caps.contains(&"memory.write".to_string()));
    assert!(caps.contains(&"tools.read".to_string()));
    assert!(caps.contains(&"tools.call".to_string()));
    assert!(caps.contains(&"cost.read".to_string()));
    assert!(caps.contains(&"events.read".to_string()));
    assert!(caps.contains(&"config.validate".to_string()));
    assert!(!caps.contains(&"model.chat".to_string()));
    assert!(!caps.contains(&"model.read".to_string()));
    assert!(!caps.contains(&"auth.read".to_string()));
    assert!(!caps.contains(&"trace.read".to_string()));
}

#[test]
fn agent_capabilities_with_nano() {
    let caps = build_agent_capabilities(true);
    assert!(caps.contains(&"model.chat".to_string()));
    assert!(!caps.contains(&"model.read".to_string()));
}
