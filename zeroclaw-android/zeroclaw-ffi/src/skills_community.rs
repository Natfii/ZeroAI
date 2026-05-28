// Copyright (c) 2026 @Natfii. All rights reserved.

//! Community skill workspace operations: save, toggle (enable/disable),
//! and read content for `.md`-based community skills.
//!
//! Each FFI-facing inner resolves the workspace directory from the
//! running daemon's config, then delegates to a workspace-pure helper
//! that performs the actual filesystem work. The split keeps the
//! filesystem logic unit-testable without a running daemon.

use crate::error::FfiError;
use crate::skills_install::validate_skill_name;

/// Saves a community skill's `SKILL.md` content to the workspace.
///
/// Creates the skill directory under `{workspace}/skills/{name}/` if
/// needed, then writes `content` to `SKILL.md`. Any existing
/// `SKILL.md.disabled` file in the same directory is removed so the
/// skill is treated as enabled after saving.
///
/// # Errors
///
/// Returns [`FfiError::ConfigError`] if the name is unsafe, or
/// [`FfiError::SpawnError`] if directory creation or file writing
/// fails.
pub(crate) fn save_community_skill_to_workspace(
    workspace_dir: &std::path::Path,
    name: String,
    content: String,
) -> Result<(), FfiError> {
    validate_skill_name(&name)?;
    let skill_dir = workspace_dir.join("skills").join(&name);
    std::fs::create_dir_all(&skill_dir).map_err(|e| FfiError::SpawnError {
        detail: format!("failed to create skill directory: {e}"),
    })?;
    std::fs::write(skill_dir.join("SKILL.md"), &content).map_err(|e| FfiError::SpawnError {
        detail: format!("failed to write SKILL.md: {e}"),
    })?;
    let disabled = skill_dir.join("SKILL.md.disabled");
    if disabled.exists() {
        std::fs::remove_file(&disabled).map_err(|e| FfiError::SpawnError {
            detail: format!("failed to remove stale disabled marker: {e}"),
        })?;
    }
    Ok(())
}

/// Toggles a community skill between enabled and disabled.
///
/// When `enabled` is `true`, renames `SKILL.md.disabled` back to
/// `SKILL.md`. When `false`, renames `SKILL.md` to `SKILL.md.disabled`.
/// If the skill is already in the requested state, this is a no-op.
///
/// # Errors
///
/// Returns [`FfiError::ConfigError`] if the name is unsafe,
/// [`FfiError::InvalidArgument`] if the skill is not found, or
/// [`FfiError::SpawnError`] if the rename fails.
pub(crate) fn toggle_community_skill_in_workspace(
    workspace_dir: &std::path::Path,
    name: String,
    enabled: bool,
) -> Result<(), FfiError> {
    validate_skill_name(&name)?;
    let skill_dir = workspace_dir.join("skills").join(&name);
    let active = skill_dir.join("SKILL.md");
    let disabled = skill_dir.join("SKILL.md.disabled");

    if enabled {
        if disabled.exists() {
            std::fs::rename(&disabled, &active).map_err(|e| FfiError::SpawnError {
                detail: format!("failed to enable skill: {e}"),
            })?;
        } else if !active.exists() {
            return Err(FfiError::InvalidArgument {
                detail: format!("skill not found: {name}"),
            });
        }
    } else if active.exists() {
        std::fs::rename(&active, &disabled).map_err(|e| FfiError::SpawnError {
            detail: format!("failed to disable skill: {e}"),
        })?;
    } else if !disabled.exists() {
        return Err(FfiError::InvalidArgument {
            detail: format!("skill not found: {name}"),
        });
    }
    Ok(())
}

/// Reads the raw `SKILL.md` content of a community skill.
///
/// Returns the full file content including YAML frontmatter. Checks for
/// `SKILL.md` first, then falls back to `SKILL.md.disabled`.
///
/// # Errors
///
/// Returns [`FfiError::ConfigError`] if the name is unsafe,
/// [`FfiError::InvalidArgument`] if the skill is not found, or
/// [`FfiError::SpawnError`] if reading fails.
pub(crate) fn get_skill_content_from_workspace(
    workspace_dir: &std::path::Path,
    name: String,
) -> Result<String, FfiError> {
    validate_skill_name(&name)?;
    let skill_dir = workspace_dir.join("skills").join(&name);
    let active = skill_dir.join("SKILL.md");
    let disabled = skill_dir.join("SKILL.md.disabled");

    let path = if active.is_file() {
        active
    } else if disabled.is_file() {
        disabled
    } else {
        return Err(FfiError::InvalidArgument {
            detail: format!("skill not found: {name}"),
        });
    };

    std::fs::read_to_string(&path).map_err(|e| FfiError::SpawnError {
        detail: format!("failed to read skill content: {e}"),
    })
}

/// Saves a community skill's content via the running daemon's
/// workspace.
///
/// Delegates to [`save_community_skill_to_workspace`] using the
/// workspace directory from the daemon configuration.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running,
/// [`FfiError::ConfigError`] if the name is unsafe, or
/// [`FfiError::SpawnError`] if writing fails.
pub(crate) fn save_community_skill_inner(
    name: String,
    content: String,
) -> Result<(), FfiError> {
    let workspace_dir = crate::runtime::with_daemon_config(|config| config.data_dir.clone())?;
    save_community_skill_to_workspace(&workspace_dir, name, content)
}

/// Toggles a community skill via the running daemon's workspace.
///
/// Delegates to [`toggle_community_skill_in_workspace`] using the
/// workspace directory from the daemon configuration.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running,
/// [`FfiError::ConfigError`] if the name is unsafe, or
/// [`FfiError::SpawnError`] if the rename fails.
pub(crate) fn toggle_community_skill_inner(
    name: String,
    enabled: bool,
) -> Result<(), FfiError> {
    let workspace_dir = crate::runtime::with_daemon_config(|config| config.data_dir.clone())?;
    toggle_community_skill_in_workspace(&workspace_dir, name, enabled)
}

/// Reads a community skill's content via the running daemon's
/// workspace.
///
/// Delegates to [`get_skill_content_from_workspace`] using the
/// workspace directory from the daemon configuration.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running,
/// [`FfiError::ConfigError`] if the name is unsafe, or
/// [`FfiError::SpawnError`] if reading fails.
pub(crate) fn get_skill_content_inner(name: String) -> Result<String, FfiError> {
    let workspace_dir = crate::runtime::with_daemon_config(|config| config.data_dir.clone())?;
    get_skill_content_from_workspace(&workspace_dir, name)
}
