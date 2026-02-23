/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

//! Skills browsing and management for the Android dashboard.
//!
//! Upstream v0.1.6 made the `zeroclaw::skills` module `pub(crate)`,
//! so skill loading and management now use filesystem-based scanning
//! of the workspace skills directory. Install and remove operations
//! are not available until the upstream exposes a gateway API for them.

use crate::error::FfiError;

/// A skill loaded from the workspace skills directory.
///
/// Fields are populated by scanning `skill.toml` manifests from the
/// workspace directory, since the upstream `Skill` type is no longer
/// accessible from outside the crate.
#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiSkill {
    /// Display name of the skill.
    pub name: String,
    /// Human-readable description.
    pub description: String,
    /// Semantic version string.
    pub version: String,
    /// Optional author name or identifier.
    pub author: Option<String>,
    /// Tags for categorisation (e.g. `"automation"`, `"devops"`).
    pub tags: Vec<String>,
    /// Number of tools provided by this skill.
    pub tool_count: u32,
    /// Names of the tools provided by this skill.
    pub tool_names: Vec<String>,
}

/// A single tool defined by a skill.
#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiSkillTool {
    /// Unique tool name within the skill.
    pub name: String,
    /// Human-readable tool description.
    pub description: String,
    /// Tool kind: `"shell"`, `"http"`, or `"script"`.
    pub kind: String,
    /// Command string, URL, or script path.
    pub command: String,
}

/// Internal representation of a skill parsed from a TOML manifest.
#[derive(Debug, serde::Deserialize)]
pub(crate) struct SkillManifest {
    #[serde(default)]
    pub(crate) name: String,
    #[serde(default)]
    pub(crate) description: String,
    #[serde(default)]
    pub(crate) version: String,
    #[serde(default)]
    pub(crate) author: Option<String>,
    #[serde(default)]
    pub(crate) tags: Vec<String>,
    #[serde(default)]
    pub(crate) tools: Vec<ToolManifest>,
}

/// Internal representation of a tool within a skill manifest.
#[derive(Debug, serde::Deserialize)]
pub(crate) struct ToolManifest {
    #[serde(default)]
    pub(crate) name: String,
    #[serde(default)]
    pub(crate) description: String,
    #[serde(default)]
    pub(crate) kind: String,
    #[serde(default)]
    pub(crate) command: String,
}

/// Scans the workspace skills directory for skill manifests.
///
/// Reads `skill.toml` from each subdirectory of `{workspace}/skills/`.
/// Returns an empty vec if the directory doesn't exist or has no skills.
pub(crate) fn load_skills_from_workspace(
    workspace_dir: &std::path::Path,
) -> Vec<(SkillManifest, Vec<ToolManifest>)> {
    let skills_dir = workspace_dir.join("skills");
    let Ok(entries) = std::fs::read_dir(&skills_dir) else {
        return Vec::new();
    };

    let mut result = Vec::new();
    for entry in entries.flatten() {
        let path = entry.path();
        if !path.is_dir() {
            continue;
        }
        let manifest_path = path.join("skill.toml");
        if let Ok(content) = std::fs::read_to_string(&manifest_path)
            && let Ok(manifest) = toml::from_str::<SkillManifest>(&content)
        {
            let tools = manifest.tools;
            let skill = SkillManifest {
                name: if manifest.name.is_empty() {
                    entry
                        .file_name()
                        .to_string_lossy()
                        .into_owned()
                } else {
                    manifest.name
                },
                description: manifest.description,
                version: manifest.version,
                author: manifest.author,
                tags: manifest.tags,
                tools: Vec::new(),
            };
            result.push((skill, tools));
        }
    }
    result
}

/// Lists all skills loaded from the workspace directory.
///
/// Reads skill manifests from `{workspace}/skills/` subdirectories.
/// Returns an empty vector if no skills are installed.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running.
pub(crate) fn list_skills_inner() -> Result<Vec<FfiSkill>, FfiError> {
    let workspace_dir =
        crate::runtime::with_daemon_config(|config| config.workspace_dir.clone())?;
    let skills = load_skills_from_workspace(&workspace_dir);
    Ok(skills
        .iter()
        .map(|(skill, tools)| FfiSkill {
            name: skill.name.clone(),
            description: skill.description.clone(),
            version: skill.version.clone(),
            author: skill.author.clone(),
            tags: skill.tags.clone(),
            tool_count: u32::try_from(tools.len()).unwrap_or(u32::MAX),
            tool_names: tools.iter().map(|t| t.name.clone()).collect(),
        })
        .collect())
}

/// Lists the tools provided by a specific skill.
///
/// Returns an empty vector if the skill is not found or has no tools.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running.
pub(crate) fn get_skill_tools_inner(skill_name: String) -> Result<Vec<FfiSkillTool>, FfiError> {
    let workspace_dir =
        crate::runtime::with_daemon_config(|config| config.workspace_dir.clone())?;
    let skills = load_skills_from_workspace(&workspace_dir);
    let tools = skills
        .iter()
        .find(|(s, _)| s.name == skill_name)
        .map_or_else(Vec::new, |(_, tools)| {
            tools
                .iter()
                .map(|t| FfiSkillTool {
                    name: t.name.clone(),
                    description: t.description.clone(),
                    kind: t.kind.clone(),
                    command: t.command.clone(),
                })
                .collect()
        });
    Ok(tools)
}

/// Installs a skill from a URL or local path.
///
/// Not available in v0.1.6 — the upstream `skills::handle_command`
/// function is in a `pub(crate)` module. Returns an error with an
/// explanation.
///
/// # Errors
///
/// Always returns [`FfiError::SpawnError`] in v0.1.6.
pub(crate) fn install_skill_inner(source: String) -> Result<(), FfiError> {
    let _ = crate::runtime::is_daemon_running()?;
    Err(FfiError::SpawnError {
        detail: format!(
            "skill install not available: upstream skills module is pub(crate) \
             in v0.1.6 (source: {source})"
        ),
    })
}

/// Removes an installed skill by name.
///
/// Not available in v0.1.6 — the upstream `skills::handle_command`
/// function is in a `pub(crate)` module. Returns an error with an
/// explanation.
///
/// # Errors
///
/// Always returns [`FfiError::SpawnError`] in v0.1.6.
pub(crate) fn remove_skill_inner(name: String) -> Result<(), FfiError> {
    let _ = crate::runtime::is_daemon_running()?;
    Err(FfiError::SpawnError {
        detail: format!(
            "skill remove not available: upstream skills module is pub(crate) \
             in v0.1.6 (name: {name})"
        ),
    })
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    #[test]
    fn test_list_skills_not_running() {
        let result = list_skills_inner();
        assert!(result.is_err());
        match result.unwrap_err() {
            FfiError::StateError { detail } => {
                assert!(detail.contains("not running"));
            }
            other => panic!("expected StateError, got {other:?}"),
        }
    }

    #[test]
    fn test_get_skill_tools_not_running() {
        let result = get_skill_tools_inner("test".into());
        assert!(result.is_err());
        match result.unwrap_err() {
            FfiError::StateError { detail } => {
                assert!(detail.contains("not running"));
            }
            other => panic!("expected StateError, got {other:?}"),
        }
    }

    #[test]
    fn test_install_skill_not_running() {
        let result = install_skill_inner("https://example.com/skill".into());
        assert!(result.is_err());
    }

    #[test]
    fn test_remove_skill_not_running() {
        let result = remove_skill_inner("test-skill".into());
        assert!(result.is_err());
    }

    #[test]
    fn test_load_skills_empty_dir() {
        let temp = std::env::temp_dir().join("zeroclaw_test_skills_empty");
        let _ = std::fs::create_dir_all(&temp);
        let result = load_skills_from_workspace(&temp);
        assert!(result.is_empty());
        let _ = std::fs::remove_dir_all(&temp);
    }

    #[test]
    fn test_load_skills_with_manifest() {
        let temp = std::env::temp_dir().join("zeroclaw_test_skills_manifest");
        let skill_dir = temp.join("skills").join("test-skill");
        let _ = std::fs::create_dir_all(&skill_dir);
        std::fs::write(
            skill_dir.join("skill.toml"),
            r#"
name = "test-skill"
description = "A test skill"
version = "1.0.0"
author = "tester"
tags = ["test"]

[[tools]]
name = "tool-a"
description = "Tool A"
kind = "shell"
command = "echo a"
"#,
        )
        .unwrap();

        let result = load_skills_from_workspace(&temp);
        assert_eq!(result.len(), 1);
        assert_eq!(result[0].0.name, "test-skill");
        assert_eq!(result[0].1.len(), 1);
        assert_eq!(result[0].1[0].name, "tool-a");

        let _ = std::fs::remove_dir_all(&temp);
    }
}
