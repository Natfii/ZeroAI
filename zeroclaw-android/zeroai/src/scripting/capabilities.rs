// Copyright (c) 2026 @Natfii. All rights reserved.

//! Capability inference, execution session, and request validation.

use crate::scripting::manifest::{
    default_script_version, ScriptAuditRecord, ScriptError, ScriptManifest, ScriptRuntimeKind,
    ScriptValidation,
};
use crate::scripting::discovery::{discover_workspace_scripts, runtime_kind_from_path};
use std::collections::BTreeSet;
use std::path::{Path, PathBuf};
use std::time::Instant;

pub(crate) const CAPABILITY_DENIED_SENTINEL: &str = "__zero_capability_denied__";

pub(crate) struct ScriptExecutionSession {
    pub(crate) manifest_name: String,
    runtime: ScriptRuntimeKind,
    granted_capabilities: BTreeSet<String>,
    requested_capabilities: Vec<String>,
    missing_capabilities: Vec<String>,
    warnings: Vec<String>,
    attempted_capabilities: Vec<String>,
    used_capabilities: Vec<String>,
    started_at: Instant,
}

impl ScriptExecutionSession {
    pub(crate) fn new(validation: &ScriptValidation) -> Self {
        Self {
            manifest_name: validation.manifest_name.clone(),
            runtime: validation.runtime.clone(),
            granted_capabilities: validation.requested_capabilities.iter().cloned().collect(),
            requested_capabilities: validation.requested_capabilities.clone(),
            missing_capabilities: validation.missing_capabilities.clone(),
            warnings: validation.warnings.clone(),
            attempted_capabilities: Vec::new(),
            used_capabilities: Vec::new(),
            started_at: Instant::now(),
        }
    }

    pub(crate) fn require_capability(
        &mut self,
        capability: &str,
        operation: &str,
    ) -> Result<(), ScriptError> {
        push_unique(&mut self.attempted_capabilities, capability);
        if self.granted_capabilities.contains(capability) {
            push_unique(&mut self.used_capabilities, capability);
            return Ok(());
        }

        Err(ScriptError::CapabilityDenied {
            operation: operation.to_string(),
            capability: capability.to_string(),
        })
    }

    pub(crate) fn audit_record(self, success: bool, error: Option<String>) -> ScriptAuditRecord {
        ScriptAuditRecord {
            script_name: self.manifest_name,
            runtime: self.runtime,
            success,
            requested_capabilities: self.requested_capabilities,
            attempted_capabilities: self.attempted_capabilities,
            used_capabilities: self.used_capabilities,
            missing_capabilities: self.missing_capabilities,
            warnings: self.warnings,
            error,
            duration_ms: self.started_at.elapsed().as_millis(),
        }
    }
}

pub(crate) fn push_unique(values: &mut Vec<String>, value: &str) {
    if !values.iter().any(|existing| existing == value) {
        values.push(value.to_string());
    }
}

pub(crate) fn normalize_manifest(
    manifest: Option<ScriptManifest>,
    inferred_capabilities: Vec<String>,
) -> ScriptManifest {
    let mut manifest = manifest.unwrap_or_default();
    if manifest.name.trim().is_empty() {
        manifest.name = "inline-script".to_string();
    }
    if manifest.version.trim().is_empty() {
        manifest.version = default_script_version();
    }
    if manifest.capabilities.is_empty() && !manifest.explicit_capabilities {
        manifest.capabilities = inferred_capabilities;
    } else {
        manifest.capabilities.sort();
        manifest.capabilities.dedup();
    }
    manifest
}

const INFERRED_BINDINGS: &[(&str, &str)] = &[
    ("status", "agent.read"),
    ("version", "agent.read"),
    ("send", "model.chat"),
    ("send_vision", "model.chat"),
    ("validate_config", "config.validate"),
    ("config", "agent.read"),
    ("bind", "channel.write"),
    ("allowlist", "channel.read"),
    ("swap_provider", "provider.write"),
    ("health", "agent.read"),
    ("health_component", "agent.read"),
    ("doctor", "agent.read"),
    ("cost", "cost.read"),
    ("cost_daily", "cost.read"),
    ("cost_monthly", "cost.read"),
    ("budget", "cost.read"),
    ("events", "events.read"),
    ("cron_list", "cron.read"),
    ("cron_get", "cron.read"),
    ("cron_add", "cron.write"),
    ("cron_oneshot", "cron.write"),
    ("cron_add_at", "cron.write"),
    ("cron_add_every", "cron.write"),
    ("cron_remove", "cron.write"),
    ("cron_pause", "cron.write"),
    ("cron_resume", "cron.write"),
    ("skills", "skills.read"),
    ("skill_tools", "skills.read"),
    ("skill_install", "skills.write"),
    ("skill_remove", "skills.write"),
    ("tools", "tools.read"),
    ("memories", "memory.read"),
    ("memories_by_category", "memory.read"),
    ("memory_recall", "memory.read"),
    ("memory_forget", "memory.write"),
    ("memory_count", "memory.read"),
    ("estop", "agent.control"),
    ("estop_status", "agent.control"),
    ("estop_resume", "agent.control"),
    ("traces", "trace.read"),
    ("traces_filter", "trace.read"),
    ("auth_list", "auth.read"),
    ("auth_remove", "auth.write"),
    ("models", "model.read"),
    ("models_with_key", "model.read"),
    ("models_full", "model.read"),
    ("agent::status", "agent.read"),
    ("agent::version", "agent.read"),
    ("agent::send", "model.chat"),
    ("agent::send_vision", "model.chat"),
    ("agent::validate_config", "config.validate"),
    ("agent::config", "agent.read"),
    ("agent::health", "agent.read"),
    ("agent::health_component", "agent.read"),
    ("agent::doctor", "agent.read"),
    ("tools::list", "tools.read"),
    ("memory::list", "memory.read"),
    ("memory::list_category", "memory.read"),
    ("memory::recall", "memory.read"),
    ("memory::forget", "memory.write"),
    ("memory::count", "memory.read"),
    ("events::recent", "events.read"),
    ("events::traces", "trace.read"),
    ("events::traces_filter", "trace.read"),
    ("skills::list", "skills.read"),
    ("skills::tools", "skills.read"),
    ("skills::install", "skills.write"),
    ("skills::remove", "skills.write"),
    ("tool_call", "tools.call"),
    ("tools::call", "tools.call"),
    ("storage_read", "storage.read"),
    ("storage_write", "storage.write"),
    ("storage_delete", "storage.write"),
    ("storage::read", "storage.read"),
    ("storage::write", "storage.write"),
    ("storage::delete", "storage.write"),
];

pub(crate) fn infer_capabilities(source: &str) -> Vec<String> {
    let mut capabilities = BTreeSet::new();
    let bytes = source.as_bytes();
    let mut index = 0usize;

    while index < bytes.len() {
        if is_identifier_start(bytes[index]) {
            let start = index;
            index += 1;
            while index < bytes.len() && is_identifier_continue(bytes[index]) {
                index += 1;
            }

            while index + 1 < bytes.len() && bytes[index] == b':' && bytes[index + 1] == b':' {
                index += 2;
                if index >= bytes.len() || !is_identifier_start(bytes[index]) {
                    break;
                }
                index += 1;
                while index < bytes.len() && is_identifier_continue(bytes[index]) {
                    index += 1;
                }
            }

            let token = &source[start..index];
            let mut lookahead = index;
            while lookahead < bytes.len() && bytes[lookahead].is_ascii_whitespace() {
                lookahead += 1;
            }
            if lookahead < bytes.len() && bytes[lookahead] == b'(' {
                for (binding, capability) in INFERRED_BINDINGS {
                    if token == *binding {
                        capabilities.insert((*capability).to_string());
                    }
                }
            }
            continue;
        }

        index += 1;
    }

    capabilities.into_iter().collect()
}

fn is_identifier_start(byte: u8) -> bool {
    byte.is_ascii_alphabetic() || byte == b'_'
}

fn is_identifier_continue(byte: u8) -> bool {
    byte.is_ascii_alphanumeric() || byte == b'_'
}

/// Returns true if an IP address is in a private/reserved range.
fn is_private_ip(ip: &std::net::IpAddr) -> bool {
    use std::net::IpAddr;

    match ip {
        IpAddr::V4(v4) => {
            v4.is_loopback()
            || v4.is_private()
            || v4.is_link_local()
            || v4.is_broadcast()
            || v4.is_unspecified()
            || v4.octets()[0] == 100 && (v4.octets()[1] & 0xC0) == 64
        }
        IpAddr::V6(v6) => {
            v6.is_loopback()
            || v6.is_unspecified()
            || match v6.to_ipv4_mapped() {
                Some(v4) => is_private_ip(&IpAddr::V4(v4)),
                None => false,
            }
            || (v6.segments()[0] & 0xffc0) == 0xfe80
            || (v6.segments()[0] & 0xfe00) == 0xfc00
        }
    }
}

/// Validates that a URL does not target private/reserved IP space.
pub(crate) fn is_safe_url(url: &str) -> Result<(), ScriptError> {
    use std::net::{IpAddr, ToSocketAddrs};

    let parsed = url::Url::parse(url).map_err(|e| ScriptError::InvalidArgument {
        detail: format!("invalid URL: {e}"),
    })?;

    let scheme = parsed.scheme();
    if scheme != "http" && scheme != "https" {
        return Err(ScriptError::InvalidArgument {
            detail: format!("only http/https schemes allowed, got '{scheme}'"),
        });
    }

    let host_str = parsed.host_str().ok_or_else(|| ScriptError::InvalidArgument {
        detail: "URL has no host".to_string(),
    })?;

    let port = parsed.port_or_known_default().unwrap_or(443);
    let socket_addr = format!("{host_str}:{port}");

    if let Ok(ip) = host_str.parse::<IpAddr>() {
        if is_private_ip(&ip) {
            return Err(ScriptError::InvalidArgument {
                detail: format!("URL resolves to private IP: {ip}"),
            });
        }
    }

    match socket_addr.to_socket_addrs() {
        Ok(addrs) => {
            for addr in addrs {
                if is_private_ip(&addr.ip()) {
                    return Err(ScriptError::InvalidArgument {
                        detail: format!(
                            "URL host '{host_str}' resolves to private IP: {}",
                            addr.ip()
                        ),
                    });
                }
            }
            Ok(())
        }
        Err(_) => Ok(()),
    }
}

/// Validates a workspace-relative path by attempting to open it via cap-std.
pub(crate) fn validate_workspace_relative_path(
    workspace_dir: &Path,
    relative_path: &Path,
) -> Result<PathBuf, ScriptError> {
    let _file = open_workspace_file(workspace_dir, relative_path)?;
    Ok(workspace_dir.join(relative_path))
}

pub(crate) fn open_workspace_file(
    workspace_dir: &Path,
    relative_path: &Path,
) -> Result<cap_std::fs::File, ScriptError> {
    use cap_std::ambient_authority;
    use cap_std::fs::Dir;

    if relative_path.is_absolute()
        || relative_path
            .components()
            .any(|c| matches!(c, std::path::Component::ParentDir))
    {
        return Err(ScriptError::InvalidArgument {
            detail: format!(
                "workspace path must be relative without '..': {}",
                relative_path.display()
            ),
        });
    }

    let dir = Dir::open_ambient_dir(workspace_dir, ambient_authority()).map_err(|e| {
        ScriptError::HostError {
            operation: "workspace_open".to_string(),
            detail: format!("failed to open workspace dir: {e}"),
        }
    })?;

    dir.open(relative_path).map_err(|e| ScriptError::InvalidArgument {
        detail: format!(
            "cannot open workspace file '{}': {e}",
            relative_path.display()
        ),
    })
}

pub(crate) fn resolve_workspace_script_manifest(
    workspace_dir: &Path,
    relative_path: &Path,
    granted_capabilities: Option<Vec<String>>,
) -> Result<ScriptManifest, ScriptError> {
    let path = validate_workspace_relative_path(workspace_dir, relative_path)?;
    let mut manifest = discover_workspace_scripts(workspace_dir)
        .into_iter()
        .find(|candidate| candidate.script_path.as_deref() == Some(relative_path))
        .unwrap_or_else(|| ScriptManifest {
            name: relative_path.to_string_lossy().to_string(),
            script_path: Some(relative_path.to_path_buf()),
            runtime: runtime_kind_from_path(&path).unwrap_or_default(),
            ..Default::default()
        });

    if let Some(mut capabilities) = granted_capabilities {
        capabilities.sort();
        capabilities.dedup();
        manifest.capabilities = capabilities;
    }

    Ok(manifest)
}
