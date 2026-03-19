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
    #[serde(default = "default_md_version")]
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

/// Returns the default version string for markdown skills.
fn default_md_version() -> String {
    "0.1.0".to_string()
}

/// Parses YAML frontmatter from a `SKILL.md` file.
///
/// Expects the content to start with a `---` fence line followed by
/// YAML content and a closing `---` fence. The YAML is deserialized
/// into [`MdSkillMeta`] using `serde_yml`. Unrecognised keys under
/// `metadata.zeroclaw` are captured in the `extra` map and logged
/// at debug level.
///
/// Returns `None` if the content does not start with `---`.
/// Otherwise returns `Some((meta, body))` where `body` is the
/// content after the closing fence.
fn parse_md_frontmatter(content: &str) -> Option<(MdSkillMeta, String)> {
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
fn resolve_manifest_path(skill_dir: &std::path::Path) -> Option<std::path::PathBuf> {
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
fn resolve_disabled_md_path(skill_dir: &std::path::Path) -> Option<std::path::PathBuf> {
    let disabled = skill_dir.join("SKILL.md.disabled");
    if disabled.is_file() {
        Some(disabled)
    } else {
        None
    }
}

/// Parses a TOML manifest string into a `(SkillManifest, Vec<ToolManifest>)`.
///
/// Tries the upstream nested `[skill]` section format first, then
/// falls back to the flat format for backward compatibility.
fn parse_manifest(content: &str) -> Option<(SkillManifest, Vec<ToolManifest>)> {
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
/// expansion (`~`), environment variable expansion (`$`), and
/// command substitution (backticks or `$()`).
///
/// This is a **defense-in-depth** check. The daemon's `SecurityPolicy`
/// is the real enforcement boundary -- this function provides an early
/// rejection layer at the FFI edge so that obviously dangerous commands
/// never reach the daemon in the first place.
fn has_path_traversal(command: &str) -> bool {
    command.contains("..")
        || command.starts_with('/')
        || command.starts_with('~')
        || command.contains('$')
        || command.contains('`')
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

/// Scans the workspace skills directory for skill manifests.
///
/// Reads `SKILL.toml` (or `skill.toml` as fallback) from each
/// subdirectory of `{workspace}/skills/`. Community `.md` skills
/// and disabled `.md.disabled` skills are also loaded. Tools whose
/// command contains dangerous patterns (path traversal, absolute
/// paths, shell expansion) are silently dropped (see
/// [`has_path_traversal`]). Returns an empty vec if the directory
/// doesn't exist or has no skills.
#[allow(clippy::too_many_lines)]
pub(crate) fn load_skills_from_workspace(workspace_dir: &std::path::Path) -> Vec<LoadedSkill> {
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
        let dir_name = entry.file_name().to_string_lossy().into_owned();

        if let Some(manifest_path) = resolve_manifest_path(&path) {
            let Ok(content) = std::fs::read_to_string(&manifest_path) else {
                continue;
            };

            let ext = manifest_path
                .extension()
                .and_then(|e| e.to_str())
                .unwrap_or("");

            if ext == "md" {
                // Community markdown skill (enabled).
                let (mut skill, source_url, emoji, category, api_base) =
                    if let Some((meta, _body)) = parse_md_frontmatter(&content) {
                        let zc = meta.metadata.zeroclaw.as_ref();
                        (
                            SkillManifest {
                                name: meta.name,
                                description: meta.description,
                                version: meta.version,
                                author: meta.author,
                                tags: meta.tags,
                                permissions: Vec::new(),
                                tools: Vec::new(),
                                scripts: Vec::new(),
                                triggers: Vec::new(),
                            },
                            meta.homepage,
                            zc.and_then(|z| z.emoji.clone()),
                            zc.and_then(|z| z.category.clone()),
                            zc.and_then(|z| z.api_base.clone()),
                        )
                    } else {
                        // No frontmatter: use directory name and first
                        // non-heading line as description.
                        let desc = content
                            .lines()
                            .find(|l| {
                                let t = l.trim();
                                !t.is_empty() && !t.starts_with('#')
                            })
                            .unwrap_or("")
                            .to_string();
                        (
                            SkillManifest {
                                name: String::new(),
                                description: desc,
                                version: "0.1.0".to_string(),
                                author: None,
                                tags: Vec::new(),
                                permissions: Vec::new(),
                                tools: Vec::new(),
                                scripts: Vec::new(),
                                triggers: Vec::new(),
                            },
                            None,
                            None,
                            None,
                            None,
                        )
                    };
                if skill.name.is_empty() {
                    skill.name = dir_name;
                }
                result.push(LoadedSkill {
                    manifest: skill,
                    tools: Vec::new(),
                    is_community: true,
                    is_enabled: true,
                    source_url,
                    emoji,
                    category,
                    api_base,
                });
            } else {
                // TOML skill.
                let Some((mut skill, tools)) = parse_manifest(&content) else {
                    continue;
                };
                if skill.name.is_empty() {
                    skill.name = dir_name;
                }
                let safe_tools: Vec<ToolManifest> = tools
                    .into_iter()
                    .filter(|t| !has_path_traversal(&t.command))
                    .collect();
                result.push(LoadedSkill {
                    manifest: skill,
                    tools: safe_tools,
                    is_community: false,
                    is_enabled: true,
                    source_url: None,
                    emoji: None,
                    category: None,
                    api_base: None,
                });
            }
        } else if let Some(disabled_path) = resolve_disabled_md_path(&path) {
            // Disabled community markdown skill.
            let Ok(content) = std::fs::read_to_string(&disabled_path) else {
                continue;
            };
            let (mut skill, source_url, emoji, category, api_base) =
                if let Some((meta, _body)) = parse_md_frontmatter(&content) {
                    let zc = meta.metadata.zeroclaw.as_ref();
                    (
                        SkillManifest {
                            name: meta.name,
                            description: meta.description,
                            version: meta.version,
                            author: meta.author,
                            tags: meta.tags,
                            permissions: Vec::new(),
                            tools: Vec::new(),
                            scripts: Vec::new(),
                            triggers: Vec::new(),
                        },
                        meta.homepage,
                        zc.and_then(|z| z.emoji.clone()),
                        zc.and_then(|z| z.category.clone()),
                        zc.and_then(|z| z.api_base.clone()),
                    )
                } else {
                    let desc = content
                        .lines()
                        .find(|l| {
                            let t = l.trim();
                            !t.is_empty() && !t.starts_with('#')
                        })
                        .unwrap_or("")
                        .to_string();
                    (
                        SkillManifest {
                            name: String::new(),
                            description: desc,
                            version: "0.1.0".to_string(),
                            author: None,
                            tags: Vec::new(),
                            permissions: Vec::new(),
                            tools: Vec::new(),
                            scripts: Vec::new(),
                            triggers: Vec::new(),
                        },
                        None,
                        None,
                        None,
                        None,
                    )
                };
            if skill.name.is_empty() {
                skill.name = dir_name;
            }
            result.push(LoadedSkill {
                manifest: skill,
                tools: Vec::new(),
                is_community: true,
                is_enabled: false,
                source_url,
                emoji,
                category,
                api_base,
            });
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
    let workspace_dir = crate::runtime::with_daemon_config(|config| config.workspace_dir.clone())?;
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
    let workspace_dir = crate::runtime::with_daemon_config(|config| config.workspace_dir.clone())?;
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
    let workspace_dir = crate::runtime::with_daemon_config(|config| config.workspace_dir.clone())?;
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

/// Clones a skill from a git URL into the skills directory.
///
/// Only HTTPS URLs are accepted. Plain HTTP is rejected to prevent
/// man-in-the-middle attacks during skill installation.
fn install_skill_from_url(url: &str, skills_dir: &std::path::Path) -> Result<(), FfiError> {
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
fn install_skill_from_path(source: &str, skills_dir: &std::path::Path) -> Result<(), FfiError> {
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
fn copy_dir_recursive(src: &std::path::Path, dest: &std::path::Path) -> std::io::Result<()> {
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

    let workspace_dir = crate::runtime::with_daemon_config(|config| config.workspace_dir.clone())?;
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

/// Windows reserved filenames that cannot be used as skill names.
const WINDOWS_RESERVED: &[&str] = &[
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
fn validate_skill_name(name: &str) -> Result<(), FfiError> {
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
/// [`FfiError::SpawnError`] if directory creation or file writing fails.
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
/// `SKILL.md`. When `false`, renames `SKILL.md` to
/// `SKILL.md.disabled`. If the skill is already in the requested
/// state, this is a no-op.
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
/// Returns the full file content including YAML frontmatter. Checks
/// for `SKILL.md` first, then falls back to `SKILL.md.disabled`.
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

/// Saves a community skill's content via the running daemon's workspace.
///
/// Delegates to [`save_community_skill_to_workspace`] using the
/// workspace directory from the daemon configuration.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running,
/// [`FfiError::ConfigError`] if the name is unsafe, or
/// [`FfiError::SpawnError`] if writing fails.
pub(crate) fn save_community_skill_inner(name: String, content: String) -> Result<(), FfiError> {
    let workspace_dir = crate::runtime::with_daemon_config(|config| config.workspace_dir.clone())?;
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
pub(crate) fn toggle_community_skill_inner(name: String, enabled: bool) -> Result<(), FfiError> {
    let workspace_dir = crate::runtime::with_daemon_config(|config| config.workspace_dir.clone())?;
    toggle_community_skill_in_workspace(&workspace_dir, name, enabled)
}

/// Reads a community skill's content via the running daemon's workspace.
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
    let workspace_dir = crate::runtime::with_daemon_config(|config| config.workspace_dir.clone())?;
    get_skill_content_from_workspace(&workspace_dir, name)
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
    fn test_install_skill_http_url_rejected() {
        let skills_dir = std::env::temp_dir().join("zeroclaw_test_http_reject");
        let _ = std::fs::remove_dir_all(&skills_dir);
        std::fs::create_dir_all(&skills_dir).unwrap();

        let result = install_skill_from_url("http://example.com/skill.git", &skills_dir);
        assert!(result.is_err());
        match result.unwrap_err() {
            FfiError::InvalidArgument { detail } => {
                assert!(
                    detail.contains("HTTPS"),
                    "expected HTTPS message, got: {detail}"
                );
                assert!(
                    detail.contains("http"),
                    "expected scheme in message, got: {detail}"
                );
            }
            other => panic!("expected InvalidArgument, got {other:?}"),
        }

        let _ = std::fs::remove_dir_all(&skills_dir);
    }

    #[test]
    fn test_install_skill_https_url_accepted_format() {
        let skills_dir = std::env::temp_dir().join("zeroclaw_test_https_accept");
        let _ = std::fs::remove_dir_all(&skills_dir);
        std::fs::create_dir_all(&skills_dir).unwrap();

        // HTTPS URL passes the scheme check but will fail at git clone
        // (no network in unit tests). We just verify it gets past the
        // HTTPS validation and fails at a later stage.
        let result = install_skill_from_url("https://example.com/skill.git", &skills_dir);
        assert!(result.is_err());
        if let FfiError::InvalidArgument { .. } = result.unwrap_err() {
            panic!("HTTPS URL should not be rejected as InvalidArgument");
        }

        let _ = std::fs::remove_dir_all(&skills_dir);
    }

    #[test]
    fn test_remove_skill_not_running() {
        let result = remove_skill_inner("test-skill".into());
        assert!(result.is_err());
    }

    #[test]
    fn test_remove_skill_path_traversal_rejected() {
        let result = remove_skill_inner("../etc".into());
        assert!(result.is_err());
        match result.unwrap_err() {
            FfiError::ConfigError { detail } => {
                assert!(detail.contains("path traversal"));
            }
            other => panic!("expected ConfigError, got {other:?}"),
        }
    }

    #[test]
    fn test_install_skill_from_local_path() {
        let temp = std::env::temp_dir().join("zeroclaw_test_install_skill");
        let source_dir = temp.join("source-skill");
        let _ = std::fs::remove_dir_all(&temp);
        std::fs::create_dir_all(&source_dir).unwrap();
        std::fs::write(
            source_dir.join("skill.toml"),
            "name = \"installed-skill\"\ndescription = \"test\"\nversion = \"1.0.0\"\n",
        )
        .unwrap();

        let skills_dir = temp.join("skills");
        std::fs::create_dir_all(&skills_dir).unwrap();

        let result = install_skill_from_path(&source_dir.to_string_lossy(), &skills_dir);
        assert!(result.is_ok());
        assert!(skills_dir.join("source-skill").join("skill.toml").exists());

        let _ = std::fs::remove_dir_all(&temp);
    }

    #[test]
    fn test_install_skill_from_path_no_manifest() {
        let temp = std::env::temp_dir().join("zeroclaw_test_install_no_manifest");
        let source_dir = temp.join("bad-skill");
        let _ = std::fs::remove_dir_all(&temp);
        std::fs::create_dir_all(&source_dir).unwrap();

        let skills_dir = temp.join("skills");
        std::fs::create_dir_all(&skills_dir).unwrap();

        let result = install_skill_from_path(&source_dir.to_string_lossy(), &skills_dir);
        assert!(result.is_err());
        match result.unwrap_err() {
            FfiError::ConfigError { detail } => {
                assert!(detail.contains("no SKILL.toml or skill.toml"));
            }
            other => panic!("expected ConfigError, got {other:?}"),
        }

        let _ = std::fs::remove_dir_all(&temp);
    }

    #[test]
    fn test_install_skill_already_exists() {
        let temp = std::env::temp_dir().join("zeroclaw_test_install_exists");
        let source_dir = temp.join("dup-skill");
        let _ = std::fs::remove_dir_all(&temp);
        std::fs::create_dir_all(&source_dir).unwrap();
        std::fs::write(
            source_dir.join("skill.toml"),
            "name = \"dup\"\nversion = \"1.0.0\"\n",
        )
        .unwrap();

        let skills_dir = temp.join("skills");
        std::fs::create_dir_all(skills_dir.join("dup-skill")).unwrap();

        let result = install_skill_from_path(&source_dir.to_string_lossy(), &skills_dir);
        assert!(result.is_err());
        match result.unwrap_err() {
            FfiError::SpawnError { detail } => {
                assert!(detail.contains("already installed"));
            }
            other => panic!("expected SpawnError, got {other:?}"),
        }

        let _ = std::fs::remove_dir_all(&temp);
    }

    #[test]
    fn test_copy_dir_recursive() {
        let temp = std::env::temp_dir().join("zeroclaw_test_copy_dir");
        let _ = std::fs::remove_dir_all(&temp);
        let src = temp.join("src");
        let sub = src.join("sub");
        std::fs::create_dir_all(&sub).unwrap();
        std::fs::write(src.join("a.txt"), "hello").unwrap();
        std::fs::write(sub.join("b.txt"), "world").unwrap();

        let dest = temp.join("dest");
        copy_dir_recursive(&src, &dest).unwrap();

        assert!(dest.join("a.txt").exists());
        assert!(dest.join("sub").join("b.txt").exists());
        assert_eq!(
            std::fs::read_to_string(dest.join("a.txt")).unwrap(),
            "hello"
        );

        let _ = std::fs::remove_dir_all(&temp);
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
    fn test_load_skills_with_flat_manifest() {
        let temp = std::env::temp_dir().join("zeroclaw_test_skills_flat");
        let _ = std::fs::remove_dir_all(&temp);
        let skill_dir = temp.join("skills").join("test-skill");
        std::fs::create_dir_all(&skill_dir).unwrap();
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
        assert_eq!(result[0].manifest.name, "test-skill");
        assert_eq!(result[0].tools.len(), 1);
        assert_eq!(result[0].tools[0].name, "tool-a");
        assert!(!result[0].is_community);
        assert!(result[0].is_enabled);
        assert!(result[0].source_url.is_none());

        let _ = std::fs::remove_dir_all(&temp);
    }

    #[test]
    fn test_load_skills_uppercase_filename() {
        let temp = std::env::temp_dir().join("zeroclaw_test_skills_upper");
        let _ = std::fs::remove_dir_all(&temp);
        let skill_dir = temp.join("skills").join("upper-skill");
        std::fs::create_dir_all(&skill_dir).unwrap();
        std::fs::write(
            skill_dir.join("SKILL.toml"),
            r#"
name = "upper-skill"
description = "Skill with uppercase filename"
version = "2.0.0"

[[tools]]
name = "tool-upper"
description = "Upper tool"
kind = "shell"
command = "echo upper"
"#,
        )
        .unwrap();

        let result = load_skills_from_workspace(&temp);
        assert_eq!(result.len(), 1);
        assert_eq!(result[0].manifest.name, "upper-skill");
        assert_eq!(result[0].manifest.version, "2.0.0");
        assert_eq!(result[0].tools.len(), 1);
        assert_eq!(result[0].tools[0].name, "tool-upper");

        let _ = std::fs::remove_dir_all(&temp);
    }

    /// On case-sensitive filesystems (Linux, Android) `SKILL.toml` is
    /// preferred over `skill.toml` when both exist. On case-insensitive
    /// filesystems (Windows/NTFS) the two names alias to the same file,
    /// so we simply verify that at least one is found.
    #[test]
    fn test_load_skills_uppercase_preferred_over_lowercase() {
        let temp = std::env::temp_dir().join("zeroclaw_test_skills_priority");
        let _ = std::fs::remove_dir_all(&temp);
        let skill_dir = temp.join("skills").join("prio-skill");
        std::fs::create_dir_all(&skill_dir).unwrap();

        let upper = skill_dir.join("SKILL.toml");
        let lower = skill_dir.join("skill.toml");

        std::fs::write(&upper, "name = \"from-upper\"\nversion = \"1.0.0\"\n").unwrap();
        std::fs::write(&lower, "name = \"from-lower\"\nversion = \"1.0.0\"\n").unwrap();

        let case_sensitive = upper.exists() && lower.exists() && {
            let u = std::fs::read_to_string(&upper).unwrap();
            let l = std::fs::read_to_string(&lower).unwrap();
            u != l
        };

        let result = load_skills_from_workspace(&temp);
        assert_eq!(result.len(), 1);

        if case_sensitive {
            assert_eq!(result[0].manifest.name, "from-upper");
        } else {
            assert!(
                result[0].manifest.name == "from-upper" || result[0].manifest.name == "from-lower"
            );
        }

        let _ = std::fs::remove_dir_all(&temp);
    }

    #[test]
    fn test_load_skills_nested_skill_section() {
        let temp = std::env::temp_dir().join("zeroclaw_test_skills_nested");
        let _ = std::fs::remove_dir_all(&temp);
        let skill_dir = temp.join("skills").join("nested-skill");
        std::fs::create_dir_all(&skill_dir).unwrap();
        std::fs::write(
            skill_dir.join("SKILL.toml"),
            r#"
[skill]
name = "nested-skill"
description = "A nested-format skill"
version = "3.0.0"
author = "upstream"
tags = ["nested", "test"]

[[tools]]
name = "tool-nested"
description = "Nested tool"
kind = "http"
command = "https://example.com/api"
"#,
        )
        .unwrap();

        let result = load_skills_from_workspace(&temp);
        assert_eq!(result.len(), 1);
        let loaded = &result[0];
        assert_eq!(loaded.manifest.name, "nested-skill");
        assert_eq!(loaded.manifest.description, "A nested-format skill");
        assert_eq!(loaded.manifest.version, "3.0.0");
        assert_eq!(loaded.manifest.author.as_deref(), Some("upstream"));
        assert_eq!(loaded.manifest.tags, vec!["nested", "test"]);
        assert_eq!(loaded.tools.len(), 1);
        assert_eq!(loaded.tools[0].name, "tool-nested");
        assert_eq!(loaded.tools[0].kind, "http");
        assert!(!loaded.is_community);
        assert!(loaded.is_enabled);

        let _ = std::fs::remove_dir_all(&temp);
    }

    #[test]
    fn test_tool_args_parsed() {
        let temp = std::env::temp_dir().join("zeroclaw_test_skills_args");
        let _ = std::fs::remove_dir_all(&temp);
        let skill_dir = temp.join("skills").join("args-skill");
        std::fs::create_dir_all(&skill_dir).unwrap();
        std::fs::write(
            skill_dir.join("SKILL.toml"),
            r#"
name = "args-skill"
version = "1.0.0"

[[tools]]
name = "tool-with-args"
description = "Tool with args"
kind = "shell"
command = "curl"

[tools.args]
url = "https://example.com"
method = "GET"
"#,
        )
        .unwrap();

        let result = load_skills_from_workspace(&temp);
        assert_eq!(result.len(), 1);
        let tool = &result[0].tools[0];
        assert_eq!(tool.name, "tool-with-args");
        assert_eq!(tool.args.len(), 2);
        assert_eq!(tool.args.get("url").unwrap(), "https://example.com");
        assert_eq!(tool.args.get("method").unwrap(), "GET");

        let _ = std::fs::remove_dir_all(&temp);
    }

    #[test]
    fn test_tool_args_default_empty() {
        let content = r#"
name = "no-args"
version = "1.0.0"

[[tools]]
name = "simple-tool"
description = "No args"
kind = "shell"
command = "echo hello"
"#;
        let (_, tools) = parse_manifest(content).unwrap();
        assert_eq!(tools.len(), 1);
        assert!(tools[0].args.is_empty());
    }

    #[test]
    fn test_path_traversal_in_tool_command_rejected() {
        let temp = std::env::temp_dir().join("zeroclaw_test_skills_traversal");
        let _ = std::fs::remove_dir_all(&temp);
        let skill_dir = temp.join("skills").join("traverse-skill");
        std::fs::create_dir_all(&skill_dir).unwrap();
        std::fs::write(
            skill_dir.join("SKILL.toml"),
            r#"
name = "traverse-skill"
version = "1.0.0"

[[tools]]
name = "safe-tool"
description = "Safe"
kind = "shell"
command = "echo safe"

[[tools]]
name = "evil-tool"
description = "Evil"
kind = "shell"
command = "../../etc/passwd"

[[tools]]
name = "also-evil"
description = "Also evil"
kind = "shell"
command = "cat ../secret.txt"
"#,
        )
        .unwrap();

        let result = load_skills_from_workspace(&temp);
        assert_eq!(result.len(), 1);
        let tools = &result[0].tools;
        assert_eq!(tools.len(), 1, "only the safe tool should remain");
        assert_eq!(tools[0].name, "safe-tool");

        let _ = std::fs::remove_dir_all(&temp);
    }

    #[test]
    fn test_has_path_traversal() {
        // Path traversal
        assert!(has_path_traversal("../../etc/passwd"));
        assert!(has_path_traversal("cat ../secret"));
        assert!(has_path_traversal("ls .."));

        // Absolute paths
        assert!(has_path_traversal("/usr/bin/ls"));
        assert!(has_path_traversal("/etc/passwd"));

        // Tilde expansion
        assert!(has_path_traversal("~/.ssh/id_rsa"));
        assert!(has_path_traversal("~root/.bashrc"));

        // Environment variable expansion
        assert!(has_path_traversal("echo $HOME"));
        assert!(has_path_traversal("cat ${SECRET}"));

        // Command substitution
        assert!(has_path_traversal("echo `whoami`"));
        assert!(has_path_traversal("echo $(id)"));

        // Safe commands
        assert!(!has_path_traversal("echo hello"));
        assert!(!has_path_traversal("curl https://example.com"));
        assert!(!has_path_traversal("run-tool --flag value"));
    }

    #[test]
    fn test_parse_manifest_nested_format() {
        let content = r#"
[skill]
name = "nested"
description = "Nested format"
version = "1.0.0"

[[tools]]
name = "t1"
description = "Tool 1"
kind = "shell"
command = "echo 1"
"#;
        let (skill, tools) = parse_manifest(content).unwrap();
        assert_eq!(skill.name, "nested");
        assert_eq!(skill.description, "Nested format");
        assert_eq!(tools.len(), 1);
        assert_eq!(tools[0].name, "t1");
    }

    #[test]
    fn test_parse_manifest_flat_format() {
        let content = r#"
name = "flat"
description = "Flat format"
version = "2.0.0"

[[tools]]
name = "t2"
description = "Tool 2"
kind = "script"
command = "run.sh"
"#;
        let (skill, tools) = parse_manifest(content).unwrap();
        assert_eq!(skill.name, "flat");
        assert_eq!(skill.description, "Flat format");
        assert_eq!(tools.len(), 1);
        assert_eq!(tools[0].name, "t2");
    }

    #[test]
    fn test_parse_manifest_invalid_toml() {
        let content = "this is {{ not valid toml";
        assert!(parse_manifest(content).is_none());
    }

    /// Verifies manifest resolution with only `skill.toml` present,
    /// then checks that `SKILL.toml` is found when added. On
    /// case-insensitive filesystems both names alias to the same
    /// file, so we just verify a path is returned.
    #[test]
    fn test_resolve_manifest_path_prefers_uppercase() {
        let temp = std::env::temp_dir().join("zeroclaw_test_resolve_manifest");
        let _ = std::fs::remove_dir_all(&temp);
        std::fs::create_dir_all(&temp).unwrap();

        assert!(resolve_manifest_path(&temp).is_none());

        std::fs::write(temp.join("skill.toml"), "name = \"low\"\n").unwrap();
        let path = resolve_manifest_path(&temp).unwrap();
        let name = path.file_name().unwrap().to_string_lossy();
        assert!(
            name.eq_ignore_ascii_case("skill.toml"),
            "expected skill.toml variant, got {name}"
        );

        let _ = std::fs::remove_dir_all(&temp);

        std::fs::create_dir_all(&temp).unwrap();
        std::fs::write(temp.join("SKILL.toml"), "name = \"up\"\n").unwrap();
        let path = resolve_manifest_path(&temp).unwrap();
        let name = path.file_name().unwrap().to_string_lossy();
        assert!(
            name.eq_ignore_ascii_case("skill.toml"),
            "expected SKILL.toml variant, got {name}"
        );

        let _ = std::fs::remove_dir_all(&temp);
    }

    #[test]
    fn test_install_skill_from_local_path_uppercase_manifest() {
        let temp = std::env::temp_dir().join("zeroclaw_test_install_upper");
        let source_dir = temp.join("upper-source");
        let _ = std::fs::remove_dir_all(&temp);
        std::fs::create_dir_all(&source_dir).unwrap();
        std::fs::write(
            source_dir.join("SKILL.toml"),
            "name = \"upper-install\"\nversion = \"1.0.0\"\n",
        )
        .unwrap();

        let skills_dir = temp.join("skills");
        std::fs::create_dir_all(&skills_dir).unwrap();

        let result = install_skill_from_path(&source_dir.to_string_lossy(), &skills_dir);
        assert!(result.is_ok());
        assert!(skills_dir.join("upper-source").join("SKILL.toml").exists());

        let _ = std::fs::remove_dir_all(&temp);
    }

    #[test]
    fn test_install_skill_from_nonexistent_source() {
        let temp = std::env::temp_dir().join("zeroclaw_test_install_nonexistent");
        let _ = std::fs::remove_dir_all(&temp);
        let skills_dir = temp.join("skills");
        std::fs::create_dir_all(&skills_dir).unwrap();

        let result = install_skill_from_path("/nonexistent/path/to/skill", &skills_dir);
        assert!(result.is_err());
        match result.unwrap_err() {
            FfiError::ConfigError { detail } => {
                assert!(
                    detail.contains("failed to resolve source path"),
                    "expected resolve error, got: {detail}"
                );
            }
            other => panic!("expected ConfigError, got {other:?}"),
        }

        let _ = std::fs::remove_dir_all(&temp);
    }

    #[test]
    fn test_parse_md_frontmatter_full() {
        let content = "---\nname: my-skill\ndescription: Does cool things\nversion: 2.0.0\nhomepage: https://example.com\nauthor: Test Author\ntags:\n  - devops\n  - automation\n---\n\n# My Skill\n\nBody content here.\n";
        let (meta, body) = parse_md_frontmatter(content).unwrap();
        assert_eq!(meta.name, "my-skill");
        assert_eq!(meta.description, "Does cool things");
        assert_eq!(meta.version, "2.0.0");
        assert_eq!(meta.homepage.as_deref(), Some("https://example.com"));
        assert_eq!(meta.author.as_deref(), Some("Test Author"));
        assert_eq!(meta.tags, vec!["devops", "automation"]);
        assert!(body.contains("# My Skill"));
    }

    #[test]
    fn test_parse_md_frontmatter_no_fences() {
        let content = "# Just Markdown\n\nNo frontmatter here.\n";
        assert!(parse_md_frontmatter(content).is_none());
    }

    #[test]
    fn test_parse_md_frontmatter_empty_fences() {
        let content = "---\n---\n\nBody only.\n";
        let (meta, body) = parse_md_frontmatter(content).unwrap();
        assert!(meta.name.is_empty());
        assert!(meta.description.is_empty());
        assert!(body.contains("Body only."));
    }

    #[test]
    fn test_parse_md_frontmatter_quoted_description() {
        let content =
            "---\nname: test\ndescription: \"A skill with: colons and 'quotes'\"\n---\n\nBody\n";
        let (meta, _) = parse_md_frontmatter(content).unwrap();
        assert_eq!(meta.description, "A skill with: colons and 'quotes'");
    }

    #[test]
    fn test_parse_md_frontmatter_nested_metadata_parsed() {
        let content = "---\nname: test\nmetadata:\n  zeroclaw:\n    emoji: x\n    category: social\n    api_base: https://example.com/api\n---\n\nBody\n";
        let (meta, _) = parse_md_frontmatter(content).unwrap();
        assert_eq!(meta.name, "test");
        let zc = meta.metadata.zeroclaw.as_ref().unwrap();
        assert_eq!(zc.emoji.as_deref(), Some("x"));
        assert_eq!(zc.category.as_deref(), Some("social"));
        assert_eq!(zc.api_base.as_deref(), Some("https://example.com/api"));
    }

    #[test]
    fn test_serde_yml_frontmatter_minimal() {
        let yaml = "name: bare\n";
        let meta: MdSkillMeta = serde_yml::from_str(yaml).unwrap();
        assert_eq!(meta.name, "bare");
        assert_eq!(meta.version, "0.1.0");
        assert!(meta.author.is_none());
        assert!(meta.tags.is_empty());
        assert!(meta.metadata.zeroclaw.is_none());
    }

    #[test]
    fn test_serde_yml_frontmatter_unknown_keys_in_extra() {
        let yaml = "name: test\nmetadata:\n  zeroclaw:\n    emoji: x\n    custom_field: hello\n";
        let meta: MdSkillMeta = serde_yml::from_str(yaml).unwrap();
        let zc = meta.metadata.zeroclaw.as_ref().unwrap();
        assert!(zc.extra.contains_key("custom_field"));
    }

    #[test]
    fn test_load_skills_skips_unreadable_manifest() {
        let temp = std::env::temp_dir().join("zeroclaw_test_skills_unreadable");
        let _ = std::fs::remove_dir_all(&temp);
        let skill_dir = temp.join("skills").join("bad-manifest");
        std::fs::create_dir_all(&skill_dir).unwrap();
        std::fs::write(skill_dir.join("SKILL.toml"), "{{invalid toml").unwrap();

        let result = load_skills_from_workspace(&temp);
        assert!(
            result.is_empty(),
            "invalid manifest should be silently skipped"
        );

        let _ = std::fs::remove_dir_all(&temp);
    }

    #[test]
    fn test_resolve_manifest_path_md_fallback() {
        let temp = std::env::temp_dir().join("zeroclaw_test_resolve_md");
        let _ = std::fs::remove_dir_all(&temp);
        std::fs::create_dir_all(&temp).unwrap();
        std::fs::write(temp.join("SKILL.md"), "---\nname: md-skill\n---\n# Hi\n").unwrap();

        let path = resolve_manifest_path(&temp).unwrap();
        let name = path.file_name().unwrap().to_string_lossy();
        assert!(
            name.eq_ignore_ascii_case("skill.md"),
            "expected SKILL.md, got {name}"
        );

        let _ = std::fs::remove_dir_all(&temp);
    }

    #[test]
    fn test_resolve_manifest_toml_preferred_over_md() {
        let temp = std::env::temp_dir().join("zeroclaw_test_resolve_toml_over_md");
        let _ = std::fs::remove_dir_all(&temp);
        std::fs::create_dir_all(&temp).unwrap();
        std::fs::write(
            temp.join("SKILL.toml"),
            "name = \"toml\"\nversion = \"1.0.0\"\n",
        )
        .unwrap();
        std::fs::write(temp.join("SKILL.md"), "---\nname: md\n---\n# Hi\n").unwrap();

        let path = resolve_manifest_path(&temp).unwrap();
        let name = path.file_name().unwrap().to_string_lossy();
        assert!(
            name.eq_ignore_ascii_case("skill.toml"),
            "expected SKILL.toml over SKILL.md, got {name}"
        );

        let _ = std::fs::remove_dir_all(&temp);
    }

    #[test]
    fn test_resolve_disabled_md_path() {
        let temp = std::env::temp_dir().join("zeroclaw_test_resolve_disabled");
        let _ = std::fs::remove_dir_all(&temp);
        std::fs::create_dir_all(&temp).unwrap();
        std::fs::write(
            temp.join("SKILL.md.disabled"),
            "---\nname: off\n---\n# Off\n",
        )
        .unwrap();

        let path = resolve_disabled_md_path(&temp);
        assert!(path.is_some());

        let _ = std::fs::remove_dir_all(&temp);
    }

    #[test]
    fn test_load_skills_md_community_skill() {
        let temp = std::env::temp_dir().join("zeroclaw_test_skills_md_community");
        let _ = std::fs::remove_dir_all(&temp);
        let skill_dir = temp.join("skills").join("md-skill");
        std::fs::create_dir_all(&skill_dir).unwrap();
        std::fs::write(
            skill_dir.join("SKILL.md"),
            "---\nname: md-skill\ndescription: \"A markdown skill\"\n---\n\n# Markdown Skill\n\nInstructions here.\n",
        )
        .unwrap();

        let result = load_skills_from_workspace(&temp);
        assert_eq!(result.len(), 1);
        assert_eq!(result[0].manifest.name, "md-skill");
        assert_eq!(result[0].manifest.description, "A markdown skill");
        assert!(result[0].tools.is_empty());
        assert!(result[0].is_community);
        assert!(result[0].is_enabled);

        let _ = std::fs::remove_dir_all(&temp);
    }

    #[test]
    fn test_load_skills_disabled_community_skill() {
        let temp = std::env::temp_dir().join("zeroclaw_test_skills_disabled");
        let _ = std::fs::remove_dir_all(&temp);
        let skill_dir = temp.join("skills").join("off-skill");
        std::fs::create_dir_all(&skill_dir).unwrap();
        std::fs::write(
            skill_dir.join("SKILL.md.disabled"),
            "---\nname: off-skill\ndescription: \"Disabled skill\"\n---\n\n# Off\n",
        )
        .unwrap();

        let result = load_skills_from_workspace(&temp);
        assert_eq!(result.len(), 1);
        assert_eq!(result[0].manifest.name, "off-skill");
        assert!(!result[0].is_enabled);
        assert!(result[0].is_community);

        let _ = std::fs::remove_dir_all(&temp);
    }

    #[test]
    fn test_save_community_skill_creates_directory() {
        let temp = std::env::temp_dir().join("zeroclaw_test_save_skill");
        let _ = std::fs::remove_dir_all(&temp);
        let skills_dir = temp.join("skills");
        std::fs::create_dir_all(&skills_dir).unwrap();

        let content = "---\nname: new-skill\ndescription: \"Fresh skill\"\n---\n\n# New\n";
        save_community_skill_to_workspace(&temp, "new-skill".into(), content.into()).unwrap();

        let saved = skills_dir.join("new-skill").join("SKILL.md");
        assert!(saved.exists());
        assert_eq!(std::fs::read_to_string(&saved).unwrap(), content);

        let _ = std::fs::remove_dir_all(&temp);
    }

    #[test]
    fn test_save_community_skill_overwrites() {
        let temp = std::env::temp_dir().join("zeroclaw_test_save_overwrite");
        let _ = std::fs::remove_dir_all(&temp);
        let skill_dir = temp.join("skills").join("existing");
        std::fs::create_dir_all(&skill_dir).unwrap();
        std::fs::write(skill_dir.join("SKILL.md"), "old content").unwrap();

        let new_content = "---\nname: existing\n---\n\n# Updated\n";
        save_community_skill_to_workspace(&temp, "existing".into(), new_content.into()).unwrap();

        assert_eq!(
            std::fs::read_to_string(skill_dir.join("SKILL.md")).unwrap(),
            new_content,
        );

        let _ = std::fs::remove_dir_all(&temp);
    }

    #[test]
    fn test_save_community_skill_path_traversal_rejected() {
        let temp = std::env::temp_dir().join("zeroclaw_test_save_traversal");
        let _ = std::fs::remove_dir_all(&temp);
        std::fs::create_dir_all(temp.join("skills")).unwrap();

        let result = save_community_skill_to_workspace(&temp, "../evil".into(), "x".into());
        assert!(result.is_err());

        let result2 = save_community_skill_to_workspace(&temp, "has/slash".into(), "x".into());
        assert!(result2.is_err());

        let result3 = save_community_skill_to_workspace(&temp, "CON".into(), "x".into());
        assert!(result3.is_err());
        match result3.unwrap_err() {
            FfiError::ConfigError { detail } => {
                assert!(detail.contains("reserved name"));
            }
            other => panic!("expected ConfigError, got {other:?}"),
        }

        let _ = std::fs::remove_dir_all(&temp);
    }

    #[test]
    fn test_toggle_community_skill_disable() {
        let temp = std::env::temp_dir().join("zeroclaw_test_toggle_disable");
        let _ = std::fs::remove_dir_all(&temp);
        let skill_dir = temp.join("skills").join("toggle-me");
        std::fs::create_dir_all(&skill_dir).unwrap();
        std::fs::write(skill_dir.join("SKILL.md"), "content").unwrap();

        toggle_community_skill_in_workspace(&temp, "toggle-me".into(), false).unwrap();

        assert!(!skill_dir.join("SKILL.md").exists());
        assert!(skill_dir.join("SKILL.md.disabled").exists());

        let _ = std::fs::remove_dir_all(&temp);
    }

    #[test]
    fn test_toggle_community_skill_enable() {
        let temp = std::env::temp_dir().join("zeroclaw_test_toggle_enable");
        let _ = std::fs::remove_dir_all(&temp);
        let skill_dir = temp.join("skills").join("toggle-on");
        std::fs::create_dir_all(&skill_dir).unwrap();
        std::fs::write(skill_dir.join("SKILL.md.disabled"), "content").unwrap();

        toggle_community_skill_in_workspace(&temp, "toggle-on".into(), true).unwrap();

        assert!(skill_dir.join("SKILL.md").exists());
        assert!(!skill_dir.join("SKILL.md.disabled").exists());

        let _ = std::fs::remove_dir_all(&temp);
    }

    #[test]
    fn test_get_skill_content_reads_file() {
        let temp = std::env::temp_dir().join("zeroclaw_test_get_content");
        let _ = std::fs::remove_dir_all(&temp);
        let skill_dir = temp.join("skills").join("read-me");
        std::fs::create_dir_all(&skill_dir).unwrap();
        let expected = "---\nname: read-me\n---\n\n# Read Me\n";
        std::fs::write(skill_dir.join("SKILL.md"), expected).unwrap();

        let content = get_skill_content_from_workspace(&temp, "read-me".into()).unwrap();
        assert_eq!(content, expected);

        let _ = std::fs::remove_dir_all(&temp);
    }

    #[test]
    fn test_get_skill_content_reads_disabled() {
        let temp = std::env::temp_dir().join("zeroclaw_test_get_disabled_content");
        let _ = std::fs::remove_dir_all(&temp);
        let skill_dir = temp.join("skills").join("disabled-read");
        std::fs::create_dir_all(&skill_dir).unwrap();
        let expected = "---\nname: disabled-read\n---\n\n# Disabled\n";
        std::fs::write(skill_dir.join("SKILL.md.disabled"), expected).unwrap();

        let content = get_skill_content_from_workspace(&temp, "disabled-read".into()).unwrap();
        assert_eq!(content, expected);

        let _ = std::fs::remove_dir_all(&temp);
    }

    #[test]
    fn test_validate_skill_name_empty() {
        let result = validate_skill_name("");
        assert!(result.is_err());
        match result.unwrap_err() {
            FfiError::ConfigError { detail } => {
                assert!(detail.contains("empty"));
            }
            other => panic!("expected ConfigError, got {other:?}"),
        }
    }

    #[test]
    fn test_validate_skill_name_null_byte() {
        let result = validate_skill_name("bad\0name");
        assert!(result.is_err());
    }

    #[test]
    fn test_validate_skill_name_windows_reserved() {
        for reserved in WINDOWS_RESERVED {
            let result = validate_skill_name(reserved);
            assert!(result.is_err(), "should reject {reserved}");
            let result_lower = validate_skill_name(&reserved.to_lowercase());
            assert!(
                result_lower.is_err(),
                "should reject {}",
                reserved.to_lowercase()
            );
        }
    }

    #[test]
    fn test_validate_skill_name_valid() {
        assert!(validate_skill_name("my-skill").is_ok());
        assert!(validate_skill_name("skill_123").is_ok());
        assert!(validate_skill_name("CoolSkill").is_ok());
    }

    #[test]
    fn test_load_md_skill_without_frontmatter() {
        let temp = std::env::temp_dir().join("zeroclaw_test_load_no_fm");
        let _ = std::fs::remove_dir_all(&temp);
        let skill_dir = temp.join("skills").join("plain-md");
        std::fs::create_dir_all(&skill_dir).unwrap();
        std::fs::write(
            skill_dir.join("SKILL.md"),
            "# My Skill\n\nDo the thing for me.\n",
        )
        .unwrap();

        let skills = load_skills_from_workspace(&temp);
        assert_eq!(skills.len(), 1);
        assert_eq!(skills[0].manifest.name, "plain-md");
        assert_eq!(skills[0].manifest.description, "Do the thing for me.");
        assert!(skills[0].is_community);
        assert!(skills[0].is_enabled);

        let _ = std::fs::remove_dir_all(&temp);
    }

    #[test]
    fn test_load_disabled_md_skill_without_frontmatter() {
        let temp = std::env::temp_dir().join("zeroclaw_test_load_disabled_no_fm");
        let _ = std::fs::remove_dir_all(&temp);
        let skill_dir = temp.join("skills").join("disabled-plain");
        std::fs::create_dir_all(&skill_dir).unwrap();
        std::fs::write(
            skill_dir.join("SKILL.md.disabled"),
            "# Disabled\n\nThis skill is off.\n",
        )
        .unwrap();

        let skills = load_skills_from_workspace(&temp);
        assert_eq!(skills.len(), 1);
        assert_eq!(skills[0].manifest.name, "disabled-plain");
        assert_eq!(skills[0].manifest.description, "This skill is off.");
        assert!(skills[0].is_community);
        assert!(!skills[0].is_enabled);

        let _ = std::fs::remove_dir_all(&temp);
    }
}
