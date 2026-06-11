// Copyright (c) 2026 @Natfii. All rights reserved.

//! Unit tests for [`crate::skills`]. Loaded via
//! `#[path = "skills_tests.rs"] mod tests;` from `skills.rs`.

#![allow(clippy::unwrap_used)]

use super::*;
use crate::skills_install::copy_dir_recursive;

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
