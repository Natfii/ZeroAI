// Copyright (c) 2026 @Natfii. All rights reserved.

//! Capability grant infrastructure for script/skill approval.
//!
//! Scripts and skills that request dangerous capabilities (`tools.call`,
//! `cron.write`, `auth.write`, `auth.read`) require explicit one-time user
//! approval. Approvals are per-skill, permanent until revoked, and persisted
//! to `capability_grants.json` in the workspace directory.

use std::collections::HashMap;
use std::path::Path;
use std::sync::Mutex;
use std::sync::atomic::{AtomicU64, Ordering};

use once_cell::sync::Lazy;
use serde::{Deserialize, Serialize};
use tokio::sync::oneshot;

use crate::error::FfiError;

/// Monotonically increasing counter for generating unique request IDs.
static REQUEST_COUNTER: AtomicU64 = AtomicU64::new(1);

/// In-memory map of pending capability approval requests, keyed by request ID.
/// Cleared on daemon restart.
static PENDING_APPROVALS: Lazy<Mutex<HashMap<String, PendingApprovalEntry>>> =
    Lazy::new(|| Mutex::new(HashMap::new()));

/// Capabilities that require explicit user approval before a skill may use them.
#[allow(dead_code)]
pub(crate) const DANGEROUS_CAPABILITIES: &[&str] =
    &["tools.call", "cron.write", "auth.write", "auth.read"];

/// Internal bookkeeping for a single pending approval request.
struct PendingApprovalEntry {
    /// Name of the skill requesting the capability.
    skill_name: String,
    /// The capability being requested (e.g. `"tools.call"`).
    capability: String,
    /// Channel or surface that triggered the request (e.g. `"repl"`, `"telegram"`).
    triggered_via: String,
    /// One-shot sender to deliver the user's decision back to the waiting task.
    reply_tx: Option<oneshot::Sender<bool>>,
    /// RFC 3339 timestamp when the request was created.
    requested_at: String,
}

/// Information about a pending capability approval request, exposed over FFI.
#[derive(uniffi::Record)]
pub struct PendingApprovalInfo {
    /// Unique identifier for this request (e.g. `"cap-1"`).
    pub request_id: String,
    /// Name of the skill requesting the capability.
    pub skill_name: String,
    /// The capability being requested.
    pub capability: String,
    /// Channel or surface that triggered the request.
    pub triggered_via: String,
    /// RFC 3339 timestamp when the request was created.
    pub requested_at: String,
}

/// Information about a persisted capability grant, exposed over FFI.
#[derive(uniffi::Record)]
pub struct CapabilityGrantInfo {
    /// Name of the skill that was granted the capability.
    pub skill_name: String,
    /// The granted capability.
    pub capability: String,
    /// RFC 3339 timestamp when the grant was saved.
    pub granted_at: String,
    /// Channel or surface through which the grant was approved.
    pub granted_via: String,
    /// SHA-256 hex digest of the script at grant time. Empty for legacy grants.
    pub content_hash: String,
}

/// On-disk representation of all persisted capability grants.
#[derive(Serialize, Deserialize, Default)]
struct GrantsFile {
    /// Outer key: skill name. Inner key: capability string.
    grants: HashMap<String, HashMap<String, GrantEntry>>,
}

/// A single persisted grant entry.
#[derive(Serialize, Deserialize)]
struct GrantEntry {
    /// RFC 3339 timestamp when the grant was saved.
    granted_at: String,
    /// Channel or surface through which the grant was approved.
    granted_via: String,
    /// SHA-256 hex digest of the script at grant time.
    #[serde(default)]
    content_hash: String,
}

// ---------------------------------------------------------------------------
// Grant persistence
// ---------------------------------------------------------------------------

/// Returns `true` if the given skill already has a persisted grant for `capability`.
pub(crate) fn is_capability_granted(
    workspace_dir: &Path,
    skill_name: &str,
    capability: &str,
) -> bool {
    let path = workspace_dir.join("capability_grants.json");
    let Ok(contents) = std::fs::read_to_string(&path) else {
        return false;
    };
    let Ok(grants) = serde_json::from_str::<GrantsFile>(&contents) else {
        return false;
    };
    grants
        .grants
        .get(skill_name)
        .and_then(|m| m.get(capability))
        .is_some()
}

/// Returns `true` if the grant exists AND the stored hash matches `current_hash`.
///
/// Legacy grants (empty hash) are treated as invalid -- they require re-approval.
#[allow(dead_code)]
pub(crate) fn is_grant_valid_for_hash(
    workspace_dir: &Path,
    skill_name: &str,
    capability: &str,
    current_hash: &str,
) -> bool {
    let path = workspace_dir.join("capability_grants.json");
    let Ok(contents) = std::fs::read_to_string(&path) else {
        return false;
    };
    let Ok(grants) = serde_json::from_str::<GrantsFile>(&contents) else {
        return false;
    };
    grants
        .grants
        .get(skill_name)
        .and_then(|m| m.get(capability))
        .is_some_and(|entry| !entry.content_hash.is_empty() && entry.content_hash == current_hash)
}

/// Atomically persists a capability grant for `skill_name`/`capability`.
///
/// Uses write-to-tmp + rename for crash safety.
pub(crate) fn save_grant(
    workspace_dir: &Path,
    skill_name: &str,
    capability: &str,
    granted_via: &str,
    content_hash: &str,
) -> Result<(), FfiError> {
    let path = workspace_dir.join("capability_grants.json");
    let mut grants = std::fs::read_to_string(&path)
        .ok()
        .and_then(|s| serde_json::from_str::<GrantsFile>(&s).ok())
        .unwrap_or_default();
    grants
        .grants
        .entry(skill_name.to_string())
        .or_default()
        .insert(
            capability.to_string(),
            GrantEntry {
                granted_at: chrono::Utc::now().to_rfc3339(),
                granted_via: granted_via.to_string(),
                content_hash: content_hash.to_string(),
            },
        );
    let json = serde_json::to_string_pretty(&grants).map_err(|e| FfiError::StateError {
        detail: e.to_string(),
    })?;
    let tmp = path.with_extension("json.tmp");
    std::fs::write(&tmp, &json).map_err(|e| FfiError::StateError {
        detail: e.to_string(),
    })?;
    std::fs::rename(&tmp, &path).map_err(|e| FfiError::StateError {
        detail: e.to_string(),
    })?;
    Ok(())
}

// ---------------------------------------------------------------------------
// Pending approvals (in-memory, cleared on daemon restart)
// ---------------------------------------------------------------------------

/// Creates a pending approval request and returns the request ID plus a
/// one-shot receiver that will yield the user's decision.
pub(crate) fn request_capability_approval(
    skill_name: &str,
    capability: &str,
    triggered_via: &str,
) -> (String, oneshot::Receiver<bool>) {
    let request_id = format!("cap-{}", REQUEST_COUNTER.fetch_add(1, Ordering::Relaxed));
    let (tx, rx) = oneshot::channel();
    if let Ok(mut map) = PENDING_APPROVALS.lock() {
        map.insert(
            request_id.clone(),
            PendingApprovalEntry {
                skill_name: skill_name.to_string(),
                capability: capability.to_string(),
                triggered_via: triggered_via.to_string(),
                reply_tx: Some(tx),
                requested_at: chrono::Utc::now().to_rfc3339(),
            },
        );
    }
    (request_id, rx)
}

// ---------------------------------------------------------------------------
// FFI-exported inner functions
// ---------------------------------------------------------------------------

/// Returns all currently pending capability approval requests.
pub(crate) fn get_pending_approvals_inner() -> Result<Vec<PendingApprovalInfo>, FfiError> {
    let map = PENDING_APPROVALS.lock().map_err(|e| FfiError::StateError {
        detail: e.to_string(),
    })?;
    Ok(map
        .iter()
        .map(|(id, entry)| PendingApprovalInfo {
            request_id: id.clone(),
            skill_name: entry.skill_name.clone(),
            capability: entry.capability.clone(),
            triggered_via: entry.triggered_via.clone(),
            requested_at: entry.requested_at.clone(),
        })
        .collect())
}

/// Resolves a pending approval request by sending the user's decision.
pub(crate) fn resolve_capability_request_inner(
    request_id: String,
    approved: bool,
) -> Result<(), FfiError> {
    let mut map = PENDING_APPROVALS.lock().map_err(|e| FfiError::StateError {
        detail: e.to_string(),
    })?;
    let mut entry = map
        .remove(&request_id)
        .ok_or_else(|| FfiError::InvalidArgument {
            detail: format!("no pending approval with id '{request_id}'"),
        })?;
    if let Some(tx) = entry.reply_tx.take() {
        let _ = tx.send(approved);
    }
    Ok(())
}

/// Lists all persisted capability grants from the workspace grants file.
#[allow(clippy::unnecessary_wraps)]
pub(crate) fn list_capability_grants_inner(
    workspace_dir: &Path,
) -> Result<Vec<CapabilityGrantInfo>, FfiError> {
    let path = workspace_dir.join("capability_grants.json");
    let contents = std::fs::read_to_string(&path).unwrap_or_else(|_| "{}".to_string());
    let grants: GrantsFile = serde_json::from_str(&contents).unwrap_or_default();
    let mut result = Vec::new();
    for (skill, caps) in &grants.grants {
        for (cap, entry) in caps {
            result.push(CapabilityGrantInfo {
                skill_name: skill.clone(),
                capability: cap.clone(),
                granted_at: entry.granted_at.clone(),
                granted_via: entry.granted_via.clone(),
                content_hash: entry.content_hash.clone(),
            });
        }
    }
    Ok(result)
}

/// Revokes a single persisted capability grant. Atomic write via tmp + rename.
pub(crate) fn revoke_capability_grant_inner(
    workspace_dir: &Path,
    skill_name: &str,
    capability: &str,
) -> Result<(), FfiError> {
    let path = workspace_dir.join("capability_grants.json");
    let contents = std::fs::read_to_string(&path).unwrap_or_else(|_| "{}".to_string());
    let mut grants: GrantsFile = serde_json::from_str(&contents).unwrap_or_default();
    if let Some(skill_grants) = grants.grants.get_mut(skill_name) {
        skill_grants.remove(capability);
        if skill_grants.is_empty() {
            grants.grants.remove(skill_name);
        }
    }
    let json = serde_json::to_string_pretty(&grants).map_err(|e| FfiError::StateError {
        detail: e.to_string(),
    })?;
    let tmp = path.with_extension("json.tmp");
    std::fs::write(&tmp, &json).map_err(|e| FfiError::StateError {
        detail: e.to_string(),
    })?;
    std::fs::rename(&tmp, &path).map_err(|e| FfiError::StateError {
        detail: e.to_string(),
    })?;
    Ok(())
}
