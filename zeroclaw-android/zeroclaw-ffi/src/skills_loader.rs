// Copyright (c) 2026 @Natfii. All rights reserved.

//! Workspace skill loader.
//!
//! Scans `{workspace}/skills/` for skill manifests (`SKILL.toml`,
//! `skill.toml`, `SKILL.md`, or `SKILL.md.disabled`) and constructs
//! [`LoadedSkill`] records ready for the FFI surface in `skills.rs`.

use crate::skills::{LoadedSkill, SkillManifest, ToolManifest};
use crate::skills_parser::{
    has_path_traversal, parse_manifest, parse_md_frontmatter, resolve_disabled_md_path,
    resolve_manifest_path,
};

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
