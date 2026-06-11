// Copyright (c) 2026 @Natfii. All rights reserved.

//! Skill installation and filesystem-safety helpers.
//!
//! Cloning skills from URLs, copying them from local paths, and
//! validating skill names against path traversal and Windows reserved
//! names. Kept separate from `skills.rs` so the parser/loader and FFI
//! exports there can stay focused.


use crate::error::FfiError;
use crate::skills_parser::resolve_manifest_path;

/// Clones a skill from a git URL into the skills directory.
///
/// Only HTTPS URLs are accepted. Plain HTTP is rejected to prevent
/// man-in-the-middle attacks during skill installation.
pub(crate) fn install_skill_from_url(url: &str, skills_dir: &std::path::Path) -> Result<(), FfiError> {
    if !url.starts_with("https://") {
        return Err(FfiError::InvalidArgument {
            detail: format!(
                "skill install URLs must use HTTPS (got: {})",
                url.split("://").next().unwrap_or("unknown"),
            ),
        });
    }

    let repo_name = url
        .rsplit('/')
        .next()
        .unwrap_or("skill")
        .trim_end_matches(".git");
    if repo_name.is_empty() || repo_name.contains("..") {
        return Err(FfiError::ConfigError {
            detail: format!("invalid skill URL: {url}"),
        });
    }

    let dest = skills_dir.join(repo_name);
    if dest.exists() {
        return Err(FfiError::SpawnError {
            detail: format!("skill already installed: {repo_name}"),
        });
    }

    let output = std::process::Command::new("git")
        .args(["clone", "--depth", "1", url])
        .arg(&dest)
        .output()
        .map_err(|e| FfiError::SpawnError {
            detail: format!("failed to run git clone: {e}"),
        })?;

    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        return Err(FfiError::SpawnError {
            detail: format!("git clone failed: {stderr}"),
        });
    }

    if resolve_manifest_path(&dest).is_none() {
        let _ = std::fs::remove_dir_all(&dest);
        return Err(FfiError::ConfigError {
            detail: format!("cloned repository has no SKILL.toml or skill.toml manifest: {url}"),
        });
    }

    Ok(())
}

/// Copies a skill from a local path into the skills directory.
///
/// The source path is canonicalized before use to resolve symlinks
/// and prevent path traversal attacks.
pub(crate) fn install_skill_from_path(source: &str, skills_dir: &std::path::Path) -> Result<(), FfiError> {
    let src_path =
        std::path::Path::new(source)
            .canonicalize()
            .map_err(|e| FfiError::ConfigError {
                detail: format!("failed to resolve source path '{source}': {e}"),
            })?;
    if !src_path.is_dir() {
        return Err(FfiError::ConfigError {
            detail: format!("source is not a directory: {source}"),
        });
    }

    if resolve_manifest_path(&src_path).is_none() {
        return Err(FfiError::ConfigError {
            detail: format!("source directory has no SKILL.toml or skill.toml manifest: {source}"),
        });
    }

    let dir_name = src_path.file_name().ok_or_else(|| FfiError::ConfigError {
        detail: format!("cannot determine directory name from: {source}"),
    })?;

    let dest = skills_dir.join(dir_name);
    if dest.exists() {
        return Err(FfiError::SpawnError {
            detail: format!("skill already installed: {}", dir_name.to_string_lossy()),
        });
    }

    if let Err(e) = copy_dir_recursive(&src_path, &dest) {
        let _ = std::fs::remove_dir_all(&dest);
        return Err(FfiError::SpawnError {
            detail: format!("failed to copy skill directory: {e}"),
        });
    }

    Ok(())
}

/// Recursively copies a directory tree.
pub(crate) fn copy_dir_recursive(src: &std::path::Path, dest: &std::path::Path) -> std::io::Result<()> {
    std::fs::create_dir_all(dest)?;
    for entry in std::fs::read_dir(src)? {
        let entry = entry?;
        let entry_dest = dest.join(entry.file_name());
        if entry.file_type()?.is_dir() {
            copy_dir_recursive(&entry.path(), &entry_dest)?;
        } else {
            std::fs::copy(entry.path(), entry_dest)?;
        }
    }
    Ok(())
}
/// Windows reserved filenames that cannot be used as skill names.
pub(crate) const WINDOWS_RESERVED: &[&str] = &[
    "CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8",
    "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
];

/// Validates a skill name for filesystem safety.
///
/// Rejects empty names, names containing path traversal sequences
/// (`..`, `/`, `\`, null bytes), and Windows reserved filenames
/// (`CON`, `NUL`, `COM1`--`COM9`, `LPT1`--`LPT9`, etc.).
///
/// # Errors
///
/// Returns [`FfiError::ConfigError`] if the name is invalid.
pub(crate) fn validate_skill_name(name: &str) -> Result<(), FfiError> {
    if name.is_empty() {
        return Err(FfiError::ConfigError {
            detail: "skill name cannot be empty".to_string(),
        });
    }
    if name.contains("..") || name.contains('/') || name.contains('\\') || name.contains('\0') {
        return Err(FfiError::ConfigError {
            detail: format!("invalid skill name (path traversal rejected): {name}"),
        });
    }
    if WINDOWS_RESERVED
        .iter()
        .any(|r| r.eq_ignore_ascii_case(name))
    {
        return Err(FfiError::ConfigError {
            detail: format!("invalid skill name (reserved name): {name}"),
        });
    }
    Ok(())
}
