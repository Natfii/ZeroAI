/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

//! Skills browsing and management for the Android dashboard.
//!
//! Upstream v0.1.6 made the `zeroclaw::skills` module `pub(crate)`,
//! so skill loading and management now use filesystem-based scanning
//! of the workspace skills directory. Install and remove operations
//! are not available until the upstream exposes a gateway API for them.

use std::collections::HashMap;

use crate::error::FfiError;
use crate::skills_community::{
    get_skill_content_inner, save_community_skill_inner, toggle_community_skill_inner,
};
#[cfg(test)]
use crate::skills_community::{
    get_skill_content_from_workspace, save_community_skill_to_workspace,
    toggle_community_skill_in_workspace,
};
#[cfg(test)]
use crate::skills_install::WINDOWS_RESERVED;
use crate::skills_install::{
    copy_dir_recursive, install_skill_from_path, install_skill_from_url, validate_skill_name,
};
use crate::skills_loader::load_skills_from_workspace;
#[cfg(test)]
use crate::skills_parser::{
    has_path_traversal, parse_manifest, parse_md_frontmatter, resolve_disabled_md_path,
    resolve_manifest_path,
};

/// A skill loaded from the workspace skills directory.
///
/// Fields are populated by scanning `SKILL.toml` (or `skill.toml`)
/// manifests from the workspace directory, since the upstream `Skill`
/// type is no longer accessible from outside the crate.
#[derive(Debug, Clone, serde::Serialize, uniffi::Record)]
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
    /// Capability names requested by the skill's packaged scripts.
    pub requested_permissions: Vec<String>,
    /// Number of tools provided by this skill.
    pub tool_count: u32,
    /// Names of the tools provided by this skill.
    pub tool_names: Vec<String>,
    /// Number of packaged scripts declared by this skill.
    pub script_count: u32,
    /// Number of triggers declared by this skill.
    pub trigger_count: u32,
    /// Whether this skill is a community markdown skill (true) or a
    /// core TOML skill (false).
    pub is_community: bool,
    /// Whether this skill is currently enabled. Always `true` for TOML
    /// skills. `false` for community skills whose `SKILL.md` has been
    /// renamed to `SKILL.md.disabled`.
    pub is_enabled: bool,
    /// Optional source URL from the frontmatter `homepage` field.
    pub source_url: Option<String>,
    /// Optional emoji icon from skill metadata.
    pub emoji: Option<String>,
    /// Skill category from metadata.
    pub category: Option<String>,
    /// API base URL from skill metadata.
    pub api_base: Option<String>,
}

/// A single tool defined by a skill.
#[derive(Debug, Clone, serde::Serialize, uniffi::Record)]
pub struct FfiSkillTool {
    /// Unique tool name within the skill.
    pub name: String,
    /// Human-readable tool description.
    pub description: String,
    /// Tool kind: `"shell"`, `"http"`, or `"script"`.
    pub kind: String,
    /// Command string, URL, or script path.
    pub command: String,
    /// Named arguments for the tool, keyed by argument name.
    pub args: HashMap<String, String>,
}

/// Internal representation of a skill parsed from a TOML manifest.
#[derive(Debug, Clone, serde::Deserialize)]
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
    pub(crate) permissions: Vec<String>,
    #[serde(default)]
    pub(crate) tools: Vec<ToolManifest>,
    #[serde(default)]
    pub(crate) scripts: Vec<ScriptManifest>,
    #[serde(default)]
    pub(crate) triggers: Vec<TriggerManifest>,
}

/// Internal representation of a tool within a skill manifest.
#[derive(Debug, Clone, serde::Deserialize)]
pub(crate) struct ToolManifest {
    #[serde(default)]
    pub(crate) name: String,
    #[serde(default)]
    pub(crate) description: String,
    #[serde(default)]
    pub(crate) kind: String,
    #[serde(default)]
    pub(crate) command: String,
    /// Optional named arguments for the tool (upstream `SkillTool.args`).
    #[serde(default)]
    pub(crate) args: HashMap<String, String>,
}

/// Internal representation of a packaged skill script.
#[derive(Debug, Clone, serde::Deserialize)]
#[allow(dead_code)]
pub(crate) struct ScriptManifest {
    #[serde(default)]
    pub(crate) name: String,
    #[serde(default)]
    pub(crate) path: String,
    #[serde(default)]
    pub(crate) runtime: Option<String>,
}

/// Internal representation of a packaged skill trigger.
#[derive(Debug, Clone, serde::Deserialize)]
#[allow(dead_code)]
pub(crate) struct TriggerManifest {
    #[serde(default)]
    pub(crate) kind: String,
}

/// Wrapper for the upstream nested `[skill]` section format.
///
/// Upstream `SKILL.toml` files wrap skill metadata under a `[skill]`
/// table key, with `[[tools]]` at the top level. This struct enables
/// serde to parse that format before falling back to the flat layout.
#[derive(Debug, serde::Deserialize)]
pub(crate) struct WrappedSkillManifest {
    /// The nested `[skill]` section containing skill metadata.
    pub(crate) skill: SkillManifest,
    /// Top-level `[[tools]]` array (outside the `[skill]` section).
    #[serde(default)]
    pub(crate) tools: Vec<ToolManifest>,
    /// Top-level `[[scripts]]` array.
    #[serde(default)]
    pub(crate) scripts: Vec<ScriptManifest>,
    /// Top-level `[[triggers]]` array.
    #[serde(default)]
    pub(crate) triggers: Vec<TriggerManifest>,
}

/// Provider-specific metadata from a `SKILL.md` frontmatter block.
#[derive(Debug, Clone, Default, serde::Deserialize)]
pub(crate) struct ProviderMetadata {
    /// Optional emoji icon for the skill.
    pub(crate) emoji: Option<String>,
    /// Skill category (e.g. `"social"`, `"devops"`).
    pub(crate) category: Option<String>,
    /// API base URL for the skill.
    pub(crate) api_base: Option<String>,
    /// Unrecognised keys preserved for forward compatibility.
    #[serde(flatten)]
    pub(crate) extra: HashMap<String, serde_json::Value>,
}

/// Top-level metadata container from a `SKILL.md` frontmatter block.
#[derive(Debug, Clone, Default, serde::Deserialize)]
pub(crate) struct SkillMetadata {
    /// ZeroClaw-specific provider metadata.
    #[serde(default)]
    pub(crate) zeroclaw: Option<ProviderMetadata>,
    /// OpenClaw-specific provider metadata (reserved for future use).
    #[serde(default)]
    #[allow(dead_code)]
    pub(crate) openclaw: Option<ProviderMetadata>,
}

/// Metadata extracted from a `SKILL.md` YAML frontmatter block.
#[derive(Debug, Clone, Default, serde::Deserialize)]
pub(crate) struct MdSkillMeta {
    /// Display name of the skill.
    #[serde(default)]
    pub(crate) name: String,
    /// Human-readable description.
    #[serde(default)]
    pub(crate) description: String,
    /// Semantic version string (defaults to `"0.1.0"`).
    #[serde(default = "crate::skills_parser::default_md_version")]
    pub(crate) version: String,
    /// Optional source URL from the frontmatter `homepage` field.
    #[serde(default)]
    pub(crate) homepage: Option<String>,
    /// Optional author name or identifier.
    #[serde(default)]
    pub(crate) author: Option<String>,
    /// Tags for categorisation.
    #[serde(default)]
    pub(crate) tags: Vec<String>,
    /// Nested provider-specific metadata.
    #[serde(default)]
    pub(crate) metadata: SkillMetadata,
}


/// Intermediate representation of a loaded skill with source metadata.
///
/// Wraps the parsed [`SkillManifest`] and its tools together with
/// provenance flags that the FFI layer uses to populate [`FfiSkill`]
/// fields (`is_community`, `is_enabled`, `source_url`).
#[derive(Debug)]
pub(crate) struct LoadedSkill {
    /// Parsed skill metadata.
    pub(crate) manifest: SkillManifest,
    /// Tools from the skill (empty for `.md` skills).
    pub(crate) tools: Vec<ToolManifest>,
    /// Whether this is a community (`.md`) skill.
    pub(crate) is_community: bool,
    /// Whether the skill is currently enabled.
    pub(crate) is_enabled: bool,
    /// Source URL from homepage frontmatter field.
    pub(crate) source_url: Option<String>,
    /// Optional emoji icon from skill metadata.
    pub(crate) emoji: Option<String>,
    /// Skill category from metadata.
    pub(crate) category: Option<String>,
    /// API base URL from skill metadata.
    pub(crate) api_base: Option<String>,
}


// ── FFI exports ────────────────────────────────────────────────────────────

crate::ffi_export!(
    /// Lists all skills loaded from the workspace's `skills/` directory.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::StateError`] if the daemon is not running, or
    /// [`crate::FfiError::InternalPanic`] if native code panics.
    fn list_skills() -> Vec<FfiSkill> = list_skills_inner
);

crate::ffi_export!(
    /// Lists the tools provided by a specific skill.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::StateError`] if the daemon is not running, or
    /// [`crate::FfiError::InternalPanic`] if native code panics.
    fn get_skill_tools(skill_name: String) -> Vec<FfiSkillTool> = get_skill_tools_inner
);

crate::ffi_export!(
    /// Installs a skill from a URL or local path.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::StateError`] if the daemon is not running,
    /// [`crate::FfiError::SpawnError`] on install failure, or
    /// [`crate::FfiError::InternalPanic`] if native code panics.
    fn install_skill(source: String) -> () = install_skill_inner
);

crate::ffi_export!(
    /// Removes an installed skill by name.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::StateError`] if the daemon is not running,
    /// [`crate::FfiError::SpawnError`] if removal fails, or
    /// [`crate::FfiError::InternalPanic`] if native code panics.
    fn remove_skill(name: String) -> () = remove_skill_inner
);

crate::ffi_export!(
    /// Saves a community skill's `SKILL.md` content to the workspace.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::StateError`] if the daemon is not running,
    /// [`crate::FfiError::ConfigError`] if the name is unsafe,
    /// [`crate::FfiError::SpawnError`] if writing fails, or
    /// [`crate::FfiError::InternalPanic`] if native code panics.
    fn save_community_skill(name: String, content: String) -> () = save_community_skill_inner
);

crate::ffi_export!(
    /// Toggles a community skill between enabled and disabled.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::StateError`] if the daemon is not running,
    /// [`crate::FfiError::ConfigError`] if the name is unsafe,
    /// [`crate::FfiError::SpawnError`] if the rename fails, or
    /// [`crate::FfiError::InternalPanic`] if native code panics.
    fn toggle_community_skill(name: String, enabled: bool) -> () = toggle_community_skill_inner
);

crate::ffi_export!(
    /// Reads the raw `SKILL.md` content of a community skill.
    ///
    /// # Errors
    ///
    /// Returns [`crate::FfiError::StateError`] if the daemon is not running,
    /// [`crate::FfiError::ConfigError`] if the name is unsafe,
    /// [`crate::FfiError::SpawnError`] if the file is not found, or
    /// [`crate::FfiError::InternalPanic`] if native code panics.
    fn get_skill_content(name: String) -> String = get_skill_content_inner
);

// ── Inner implementations ──────────────────────────────────────────────────

/// Reads skill manifests from `{workspace}/skills/` subdirectories.
/// Returns an empty vector if no skills are installed.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running.
pub(crate) fn list_skills_inner() -> Result<Vec<FfiSkill>, FfiError> {
    let workspace_dir = crate::runtime::with_daemon_config(|config| config.data_dir.clone())?;
    let skills = load_skills_from_workspace(&workspace_dir);
    Ok(skills
        .iter()
        .map(|loaded| FfiSkill {
            name: loaded.manifest.name.clone(),
            description: loaded.manifest.description.clone(),
            version: loaded.manifest.version.clone(),
            author: loaded.manifest.author.clone(),
            tags: loaded.manifest.tags.clone(),
            requested_permissions: loaded.manifest.permissions.clone(),
            tool_count: u32::try_from(loaded.tools.len()).unwrap_or(u32::MAX),
            tool_names: loaded.tools.iter().map(|t| t.name.clone()).collect(),
            script_count: u32::try_from(loaded.manifest.scripts.len()).unwrap_or(u32::MAX),
            trigger_count: u32::try_from(loaded.manifest.triggers.len()).unwrap_or(u32::MAX),
            is_community: loaded.is_community,
            is_enabled: loaded.is_enabled,
            source_url: loaded.source_url.clone(),
            emoji: loaded.emoji.clone(),
            category: loaded.category.clone(),
            api_base: loaded.api_base.clone(),
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
    let workspace_dir = crate::runtime::with_daemon_config(|config| config.data_dir.clone())?;
    let skills = load_skills_from_workspace(&workspace_dir);
    let tools = skills
        .iter()
        .find(|loaded| loaded.manifest.name == skill_name)
        .map_or_else(Vec::new, |loaded| {
            loaded
                .tools
                .iter()
                .map(|t| FfiSkillTool {
                    name: t.name.clone(),
                    description: t.description.clone(),
                    kind: t.kind.clone(),
                    command: t.command.clone(),
                    args: t.args.clone(),
                })
                .collect()
        });
    Ok(tools)
}

/// Installs a skill from a URL or local path.
///
/// For URLs (starting with `http://` or `https://`), runs `git clone
/// --depth 1` into the workspace `skills/` directory. For local paths,
/// copies the directory tree recursively.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running,
/// [`FfiError::SpawnError`] if the git clone or copy fails,
/// [`FfiError::ConfigError`] if the source skill has no manifest.
pub(crate) fn install_skill_inner(source: String) -> Result<(), FfiError> {
    let workspace_dir = crate::runtime::with_daemon_config(|config| config.data_dir.clone())?;
    let skills_dir = workspace_dir.join("skills");
    std::fs::create_dir_all(&skills_dir).map_err(|e| FfiError::SpawnError {
        detail: format!("failed to create skills directory: {e}"),
    })?;

    if source.starts_with("http://") || source.starts_with("https://") {
        install_skill_from_url(&source, &skills_dir)
    } else {
        install_skill_from_path(&source, &skills_dir)
    }
}


/// Removes an installed skill by name.
///
/// Deletes the skill directory from the workspace's `skills/` folder.
/// Path traversal attempts (e.g. `../`) are rejected.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running,
/// [`FfiError::ConfigError`] if the name is invalid,
/// [`FfiError::InvalidArgument`] if the skill is not found, or
/// [`FfiError::SpawnError`] if deletion fails.
pub(crate) fn remove_skill_inner(name: String) -> Result<(), FfiError> {
    validate_skill_name(&name)?;

    let workspace_dir = crate::runtime::with_daemon_config(|config| config.data_dir.clone())?;
    let skill_dir = workspace_dir.join("skills").join(&name);

    if !skill_dir.is_dir() {
        return Err(FfiError::InvalidArgument {
            detail: format!("skill not found: {name}"),
        });
    }

    std::fs::remove_dir_all(&skill_dir).map_err(|e| FfiError::SpawnError {
        detail: format!("failed to remove skill directory: {e}"),
    })
}


#[cfg(test)]
#[path = "skills_tests.rs"]
mod tests;
