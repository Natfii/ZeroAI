// Copyright (c) 2026 @Natfii. All rights reserved.

//! Workspace and skill-package script discovery.

use crate::scripting::manifest::ScriptManifest;
use crate::scripting::manifest::ScriptRuntimeKind;
use std::path::Path;

/// Discover workspace and skill-packaged scripts.
pub fn discover_workspace_scripts(workspace_dir: &Path) -> Vec<ScriptManifest> {
    let mut manifests = Vec::new();
    collect_workflow_manifests(&workspace_dir.join("workflows"), workspace_dir, &mut manifests);
    collect_skill_scripts(&workspace_dir.join("skills"), workspace_dir, &mut manifests);

    manifests.sort_by(|left, right| left.name.cmp(&right.name));
    manifests
}

/// Walks the `skills/` directory tree and collects every script declared
/// in a `skills/<name>/SKILL.toml` bundle. Upstream's `zeroclaw::skills`
/// iterator went `pub(crate)` during the workspace split, so we parse
/// the bundle manifest directly here.
///
/// Each entry is namespaced as `<skill_name>::<script_name>` and
/// inherits the skill's top-level `permissions` list as its
/// capabilities.
pub(crate) fn collect_skill_scripts(
    skills_root: &Path,
    _workspace_dir: &Path,
    output: &mut Vec<ScriptManifest>,
) {
    if !skills_root.exists() {
        return;
    }
    let Ok(skill_entries) = std::fs::read_dir(skills_root) else {
        return;
    };
    for skill_entry in skill_entries.flatten() {
        let skill_dir = skill_entry.path();
        if !skill_dir.is_dir() {
            continue;
        }
        let manifest_path = skill_dir.join("SKILL.toml");
        let Ok(raw) = std::fs::read_to_string(&manifest_path) else {
            continue;
        };
        let Ok(parsed) = toml::from_str::<SkillBundleManifest>(&raw) else {
            continue;
        };
        let permissions = parsed.skill.permissions.clone().unwrap_or_default();
        for script_def in parsed.scripts.unwrap_or_default() {
            let script_rel = std::path::PathBuf::from(&script_def.path);
            let abs_path = skill_dir.join(&script_rel);
            let runtime = runtime_kind_from_path(&abs_path).unwrap_or(ScriptRuntimeKind::Rhai);
            output.push(ScriptManifest {
                name: format!("{}::{}", parsed.skill.name, script_def.name),
                script_path: Some(abs_path),
                runtime,
                capabilities: permissions.clone(),
                ..Default::default()
            });
        }
    }
}

/// Minimal SKILL.toml shape — enough to lift script entries + the
/// skill's name and permissions list. Note: `permissions` lives
/// INSIDE the `[skill]` table per the SKILL.toml convention, not at
/// the top level.
#[derive(serde::Deserialize)]
struct SkillBundleManifest {
    skill: SkillBundleMeta,
    #[serde(default)]
    scripts: Option<Vec<SkillBundleScript>>,
}

/// Skill-identity block from SKILL.toml.
#[derive(serde::Deserialize)]
struct SkillBundleMeta {
    name: String,
    #[serde(default)]
    permissions: Option<Vec<String>>,
}

/// One `[[scripts]]` table entry.
#[derive(serde::Deserialize)]
struct SkillBundleScript {
    name: String,
    path: String,
}

pub(crate) fn collect_workflow_manifests(
    root: &Path,
    workspace_dir: &Path,
    output: &mut Vec<ScriptManifest>,
) {
    if !root.exists() {
        return;
    }

    let Ok(entries) = std::fs::read_dir(root) else {
        return;
    };

    for entry in entries.flatten() {
        let path = entry.path();
        if path.is_dir() {
            collect_workflow_manifests(&path, workspace_dir, output);
            continue;
        }

        let Some(runtime) = runtime_kind_from_path(&path) else {
            continue;
        };

        let relative = path
            .strip_prefix(workspace_dir)
            .unwrap_or(&path)
            .to_path_buf();
        output.push(ScriptManifest {
            name: relative.to_string_lossy().to_string(),
            script_path: Some(relative),
            runtime,
            ..Default::default()
        });
    }
}

pub(crate) fn runtime_kind_from_path(path: &Path) -> Option<ScriptRuntimeKind> {
    let extension = path.extension()?.to_string_lossy().to_ascii_lowercase();
    match extension.as_str() {
        "rhai" => Some(ScriptRuntimeKind::Rhai),
        "wasm" => Some(ScriptRuntimeKind::WasmComponent),
        "py" => Some(ScriptRuntimeKind::Python),
        _ => None,
    }
}

#[allow(dead_code)]
pub(crate) fn parse_runtime_kind(raw: &str) -> Option<ScriptRuntimeKind> {
    match raw.trim().to_ascii_lowercase().as_str() {
        "rhai" => Some(ScriptRuntimeKind::Rhai),
        "wasm" | "wasm_component" | "wasm-component" => Some(ScriptRuntimeKind::WasmComponent),
        "python" => Some(ScriptRuntimeKind::Python),
        _ => None,
    }
}

pub(crate) fn runtime_is_available(kind: &ScriptRuntimeKind) -> bool {
    match kind {
        ScriptRuntimeKind::Rhai => true,
        ScriptRuntimeKind::WasmComponent => cfg!(feature = "scripting-wasm-component"),
        ScriptRuntimeKind::Python => cfg!(feature = "scripting-python"),
    }
}

pub(crate) fn runtime_runtime_notes(kind: &ScriptRuntimeKind) -> &'static str {
    match kind {
        ScriptRuntimeKind::Rhai => {
            "Default embedded workflow runtime backed by the core capability host."
        }
        ScriptRuntimeKind::WasmComponent => {
            if runtime_is_available(kind) {
                "Feature-gated Wasm guest runtime is compiled in; host ABI plumbing is ready for component guest execution."
            } else {
                "Host ABI and manifest plumbing are ready; enable the `scripting-wasm-component` feature to continue wiring the guest runtime."
            }
        }
        ScriptRuntimeKind::Python => {
            if runtime_is_available(kind) {
                "Optional Python guest runtime feature is compiled in behind the stable plugin ABI."
            } else {
                "Reserved optional guest runtime behind the stable plugin ABI only."
            }
        }
    }
}

pub(crate) fn unavailable_runtime_execution_detail(kind: &ScriptRuntimeKind) -> String {
    match kind {
        ScriptRuntimeKind::Rhai => "rhai execution is always available".to_string(),
        ScriptRuntimeKind::WasmComponent => {
            if runtime_is_available(kind) {
                "runtime 'wasm-component' is compiled in but the guest execution path is not yet wired through the stable host ABI.".to_string()
            } else {
                "runtime 'wasm-component' is not enabled in this build; enable the `scripting-wasm-component` feature before executing guest plugins.".to_string()
            }
        }
        ScriptRuntimeKind::Python => {
            if runtime_is_available(kind) {
                "runtime 'python' is compiled in but remains reserved behind the stable plugin ABI until the guest host bridge is completed.".to_string()
            } else {
                "runtime 'python' is not enabled in this build; optional polyglot guests remain feature-gated behind the stable plugin ABI.".to_string()
            }
        }
    }
}
