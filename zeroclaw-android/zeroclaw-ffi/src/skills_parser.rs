// Copyright (c) 2026 @Natfii. All rights reserved.

//! Parsing helpers for skill manifests.
//!
//! Reads `SKILL.toml`, `skill.toml`, and community `SKILL.md` files from
//! a skill directory, validates tool commands against path-traversal and
//! shell-expansion patterns, and surfaces a small set of pure helpers
//! consumed by both `skills.rs` (workspace scanning) and
//! `skills_install.rs` (post-install manifest detection).

use crate::skills::{MdSkillMeta, SkillManifest, ToolManifest, WrappedSkillManifest};

/// Returns the default version string for markdown skills.
pub(crate) fn default_md_version() -> String {
    "0.1.0".to_string()
}

/// Parses YAML frontmatter from a `SKILL.md` file.
///
/// Expects the content to start with a `---` fence line followed by
/// YAML content and a closing `---` fence. The YAML is deserialized
/// into [`MdSkillMeta`] using `serde_yml`. Unrecognised keys under
/// `metadata.zeroclaw` are captured in the `extra` map and logged at
/// debug level.
///
/// Returns `None` if the content does not start with `---`.
/// Otherwise returns `Some((meta, body))` where `body` is the content
/// after the closing fence.
pub(crate) fn parse_md_frontmatter(content: &str) -> Option<(MdSkillMeta, String)> {
    let trimmed = content.trim_start();
    if !trimmed.starts_with("---") {
        return None;
    }

    let after_first_fence = &trimmed[3..];
    let after_first_fence = after_first_fence.trim_start_matches(['\r', '\n']);

    let (frontmatter, body) = if let Some(rest) = after_first_fence
        .strip_prefix("---\r\n")
        .or_else(|| after_first_fence.strip_prefix("---\n"))
    {
        ("", rest.to_string())
    } else if let Some(closing_pos) = after_first_fence.find("\n---") {
        let fm = &after_first_fence[..closing_pos];
        let rest = &after_first_fence[closing_pos + 4..];
        let rest = rest.strip_prefix('\n').unwrap_or(rest);
        (fm, rest.to_string())
    } else {
        return None;
    };

    let meta: MdSkillMeta = serde_yml::from_str(frontmatter).unwrap_or_default();

    if let Some(zc) = meta.metadata.zeroclaw.as_ref()
        && !zc.extra.is_empty()
    {
        tracing::debug!(
            skill = %meta.name,
            "unrecognised keys in metadata.zeroclaw: {:?}",
            zc.extra.keys().collect::<Vec<_>>()
        );
    }

    Some((meta, body))
}

/// Resolves the manifest file path for a skill directory.
///
/// Tries `SKILL.toml` first (upstream convention), then `skill.toml`
/// for backward compatibility, and finally `SKILL.md` for community
/// markdown skills. Returns `None` if no manifest file exists.
pub(crate) fn resolve_manifest_path(skill_dir: &std::path::Path) -> Option<std::path::PathBuf> {
    let upper = skill_dir.join("SKILL.toml");
    if upper.is_file() {
        return Some(upper);
    }
    let lower = skill_dir.join("skill.toml");
    if lower.is_file() {
        return Some(lower);
    }
    let md = skill_dir.join("SKILL.md");
    if md.is_file() {
        return Some(md);
    }
    None
}

/// Resolves a disabled community skill manifest path.
///
/// Checks for `SKILL.md.disabled` in the skill directory. Returns
/// `Some(path)` if the disabled manifest exists, `None` otherwise.
pub(crate) fn resolve_disabled_md_path(skill_dir: &std::path::Path) -> Option<std::path::PathBuf> {
    let disabled = skill_dir.join("SKILL.md.disabled");
    if disabled.is_file() {
        Some(disabled)
    } else {
        None
    }
}

/// Parses a TOML manifest string into a `(SkillManifest, Vec<ToolManifest>)`.
///
/// Tries the upstream nested `[skill]` section format first, then falls
/// back to the flat format for backward compatibility.
pub(crate) fn parse_manifest(content: &str) -> Option<(SkillManifest, Vec<ToolManifest>)> {
    if let Ok(wrapped) = toml::from_str::<WrappedSkillManifest>(content) {
        return Some((
            SkillManifest {
                tools: Vec::new(),
                scripts: wrapped.scripts,
                triggers: wrapped.triggers,
                ..wrapped.skill
            },
            wrapped.tools,
        ));
    }
    if let Ok(flat) = toml::from_str::<SkillManifest>(content) {
        let tools = flat.tools.clone();
        let skill = SkillManifest {
            tools: Vec::new(),
            ..flat
        };
        return Some((skill, tools));
    }
    None
}

/// Returns `true` if a tool command contains dangerous path or shell
/// expansion sequences.
///
/// Checks for path traversal (`..`), absolute paths (`/`), tilde
/// expansion (`~`), environment variable expansion (`$`), and command
/// substitution (backticks or `$()`).
///
/// This is a **defense-in-depth** check. The daemon's `SecurityPolicy`
/// is the real enforcement boundary; this function provides an early
/// rejection layer at the FFI edge so obviously dangerous commands
/// never reach the daemon.
pub(crate) fn has_path_traversal(command: &str) -> bool {
    command.contains("..")
        || command.starts_with('/')
        || command.starts_with('~')
        || command.contains('$')
        || command.contains('`')
}
