/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

//! Thin FFI bridge over the core Rust scripting runtime.

use std::collections::HashSet;
use std::path::Path;
use std::sync::{Arc, LazyLock, Mutex, OnceLock};

use once_cell::sync::OnceCell;

use crate::error::FfiError;
use crate::{
    auth_profiles, capability_grants, cost, cron, events, health, memory_browse, models, runtime,
    skills, tools_browse, vision,
};
use zeroclaw::scripting::{
    RhaiScriptRuntime, ScriptError, ScriptHost, ScriptManifest, ScriptOperation, ScriptRuntimeKind,
    ScriptValue,
};
use zeroclaw::scripting::storage::ScriptStorage;

static KNOWN_CAPABILITIES: LazyLock<HashSet<&'static str>> = LazyLock::new(|| {
    HashSet::from([
        "agent.read",
        "model.chat",
        "config.validate",
        "channel.write",
        "channel.read",
        "provider.write",
        "cost.read",
        "events.read",
        "cron.read",
        "cron.write",
        "skills.read",
        "skills.write",
        "tools.read",
        "memory.read",
        "memory.write",
        "agent.control",
        "trace.read",
        "auth.read",
        "auth.write",
        "model.read",
        "tools.call",
        "storage.read",
        "storage.write",
        "storage.delete",
    ])
});

const DANGEROUS_CAPABILITIES: &[&str] = &[
    "tools.call",
    "cron.write",
    "auth.write",
    "auth.read",
];

fn validate_capabilities(caps: &[String]) -> Result<(), ScriptError> {
    for cap in caps {
        if !KNOWN_CAPABILITIES.contains(cap.as_str()) {
            return Err(ScriptError::ValidationError {
                detail: format!("unknown capability: {cap:?}"),
            });
        }
        if DANGEROUS_CAPABILITIES.contains(&cap.as_str()) {
            tracing::warn!(capability = %cap, "skill requests dangerous capability");
        }
    }
    Ok(())
}

/// Maps a [`ScriptOperation`] to its required capability string.
///
/// Mirrors the upstream `ScriptOperation::capability()` method which is
/// module-private in the core crate and inaccessible from the FFI layer.
fn operation_capability(op: ScriptOperation) -> &'static str {
    match op {
        ScriptOperation::Status
        | ScriptOperation::Version
        | ScriptOperation::RunningConfig
        | ScriptOperation::HealthDetail
        | ScriptOperation::HealthComponent
        | ScriptOperation::DoctorChannels => "agent.read",
        ScriptOperation::SendMessage | ScriptOperation::SendVision => "model.chat",
        ScriptOperation::ValidateConfig => "config.validate",
        ScriptOperation::BindChannelIdentity => "channel.write",
        ScriptOperation::ChannelAllowlist => "channel.read",
        ScriptOperation::SwapProvider => "provider.write",
        ScriptOperation::CostSummary
        | ScriptOperation::DailyCost
        | ScriptOperation::MonthlyCost
        | ScriptOperation::CheckBudget => "cost.read",
        ScriptOperation::RecentEvents => "events.read",
        ScriptOperation::ListCronJobs | ScriptOperation::GetCronJob => "cron.read",
        ScriptOperation::AddCronJob
        | ScriptOperation::AddOneShotJob
        | ScriptOperation::AddCronJobAt
        | ScriptOperation::AddCronJobEvery
        | ScriptOperation::RemoveCronJob
        | ScriptOperation::PauseCronJob
        | ScriptOperation::ResumeCronJob => "cron.write",
        ScriptOperation::ListSkills | ScriptOperation::GetSkillTools => "skills.read",
        ScriptOperation::InstallSkill | ScriptOperation::RemoveSkill => "skills.write",
        ScriptOperation::ListTools => "tools.read",
        ScriptOperation::ListMemories
        | ScriptOperation::ListMemoriesByCategory
        | ScriptOperation::RecallMemory
        | ScriptOperation::MemoryCount => "memory.read",
        ScriptOperation::ForgetMemory => "memory.write",
        ScriptOperation::EngageEStop
        | ScriptOperation::GetEStopStatus
        | ScriptOperation::ResumeEStop => "agent.control",
        ScriptOperation::QueryTraces | ScriptOperation::QueryTracesByFilter => "trace.read",
        ScriptOperation::ListAuthProfiles => "auth.read",
        ScriptOperation::RemoveAuthProfile => "auth.write",
        ScriptOperation::DiscoverModels
        | ScriptOperation::DiscoverModelsWithKey
        | ScriptOperation::DiscoverModelsWithKeyAndBaseUrl => "model.read",
        ScriptOperation::InvokeTool => "tools.call",
        ScriptOperation::ReadStorage => "storage.read",
        ScriptOperation::WriteStorage | ScriptOperation::DeleteStorage => "storage.write",
    }
}

/// Returns a human-readable description of what a dangerous capability allows.
fn capability_description(capability: &str) -> &'static str {
    match capability {
        "tools.call" => "Execute arbitrary tools on the device (shell commands, file access, etc.)",
        "cron.write" => "Create, modify, or remove scheduled background tasks",
        "auth.write" => "Modify or remove stored authentication profiles",
        "auth.read" => "Read stored authentication profiles and credentials",
        _ => "Perform a privileged operation",
    }
}

/// Checks whether the given operation requires a dangerous capability that has
/// not yet been approved by the user. If approval is needed, emits an event to
/// Kotlin, blocks the current thread until the user responds, and either
/// persists the grant or returns [`ScriptError::CapabilityDenied`].
///
/// # Blocking
///
/// This function calls `blocking_recv()` on a tokio oneshot channel. It is safe
/// to call from JNI threads (REPL) and `spawn_blocking` threads (cron) but must
/// **never** be called from within a tokio async context.
fn require_dangerous_capability_approval(
    operation: ScriptOperation,
    manifest_name: &str,
) -> Result<(), ScriptError> {
    let capability = operation_capability(operation);
    if !DANGEROUS_CAPABILITIES.contains(&capability) {
        return Ok(());
    }

    // Check persisted grants first.
    let ws_dir = workspace_dir().map_err(|e| ScriptError::HostError {
        operation: "capability_check".to_string(),
        detail: e.to_string(),
    })?;
    if capability_grants::is_capability_granted(&ws_dir, manifest_name, capability) {
        return Ok(());
    }

    tracing::info!(
        skill = %manifest_name,
        capability = %capability,
        "dangerous capability requires user approval; blocking until resolved"
    );

    // Create a pending approval and emit an event for Kotlin.
    let triggered_via = "terminal"; // TODO: propagate from session/channel context
    let (request_id, rx) =
        capability_grants::request_capability_approval(manifest_name, capability, triggered_via);

    let description = capability_description(capability);
    let esc = events::escape_json_string;
    let event_data = format!(
        r#"{{"request_id":"{}","skill_name":"{}","capability":"{}","triggered_via":"{}","description":"{}"}}"#,
        esc(&request_id),
        esc(manifest_name),
        esc(capability),
        esc(triggered_via),
        esc(description),
    );
    events::emit_custom_event("capability_approval_required", &event_data);

    // Block the script's thread until the user approves or denies.
    // SAFETY (threading): scripts run on JNI threads (REPL) or
    // spawn_blocking threads (cron), so blocking_recv() will not
    // deadlock the tokio runtime.
    let approved = rx.blocking_recv().unwrap_or(false);

    if approved {
        tracing::info!(
            skill = %manifest_name,
            capability = %capability,
            "capability approved by user"
        );
        // Compute content hash from the script file for grant binding.
        let hash = {
            let script_path = ws_dir
                .join("workflows")
                .join(manifest_name)
                .with_extension("rhai");
            zeroclaw::scripting::content_hash::hash_file(&script_path)
                .unwrap_or_default()
        };
        if let Err(e) = capability_grants::save_grant(&ws_dir, manifest_name, capability, triggered_via, &hash) {
            tracing::warn!(
                skill = %manifest_name,
                capability = %capability,
                error = %e,
                "failed to persist capability grant; approval is session-only"
            );
        }
        Ok(())
    } else {
        tracing::warn!(
            skill = %manifest_name,
            capability = %capability,
            "capability denied by user"
        );
        Err(ScriptError::CapabilityDenied {
            operation: manifest_name.to_string(),
            capability: capability.to_string(),
        })
    }
}

/// Typed validation response surfaced to UniFFI callers.
#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiScriptValidation {
    /// Normalized manifest or fallback name.
    pub manifest_name: String,
    /// Runtime identifier for the next run.
    pub runtime: String,
    /// Capabilities granted for the next run.
    pub requested_capabilities: Vec<String>,
    /// Capabilities inferred from the source but missing from the manifest.
    pub missing_capabilities: Vec<String>,
    /// Non-fatal validation warnings.
    pub warnings: Vec<String>,
    /// Capability names the runtime knows how to enforce.
    pub available_capabilities: Vec<String>,
}

/// Guest/runtime availability exposed to Kotlin and CLI callers.
#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiScriptRuntime {
    /// Stable runtime identifier.
    pub kind: String,
    /// Whether the runtime is available in this build.
    pub available: bool,
    /// Whether the runtime isolates guest code more strongly than Rhai.
    pub isolates_guest: bool,
    /// Human-readable status note.
    pub notes: String,
}

/// Workspace or packaged script manifest surfaced to the frontend.
#[derive(Debug, Clone, uniffi::Record)]
pub struct FfiWorkspaceScript {
    /// Display name.
    pub name: String,
    /// Semantic version string.
    pub version: String,
    /// Optional description.
    pub description: Option<String>,
    /// Runtime identifier.
    pub runtime: String,
    /// Relative workspace path.
    pub relative_path: String,
    /// Optional entrypoint or export name.
    pub entrypoint: Option<String>,
    /// Capabilities requested by this script.
    pub requested_capabilities: Vec<String>,
    /// Trigger summaries for review in the UI.
    pub trigger_summaries: Vec<String>,
}

static SCRIPT_RUNTIME: OnceLock<RhaiScriptRuntime> = OnceLock::new();
static SCRIPT_STORAGE: OnceCell<Mutex<ScriptStorage>> = OnceCell::new();

fn with_script_storage<T>(
    f: impl FnOnce(&ScriptStorage) -> Result<T, anyhow::Error>,
) -> Result<T, ScriptError> {
    let storage = SCRIPT_STORAGE
        .get_or_try_init(|| -> Result<Mutex<ScriptStorage>, anyhow::Error> {
            let dir = workspace_dir().unwrap_or_else(|_| std::path::PathBuf::from("."));
            let store = ScriptStorage::open(&dir)?;
            Ok(Mutex::new(store))
        })
        .map_err(|e| ScriptError::HostError {
            operation: "storage_init".to_string(),
            detail: e.to_string(),
        })?;
    let guard = storage.lock().map_err(|e| ScriptError::HostError {
        operation: "storage_lock".to_string(),
        detail: e.to_string(),
    })?;
    f(&guard).map_err(|e| ScriptError::HostError {
        operation: "storage".to_string(),
        detail: e.to_string(),
    })
}

/// Dispatches a script operation that does not require `SendMessage`
/// interception or dangerous-capability approval.
///
/// Used by both [`FfiScriptHost`] and [`AgentScriptHost`](crate::agent_script_host::AgentScriptHost)
/// to share the common operation handling logic.
#[allow(clippy::too_many_lines)]
pub(crate) fn dispatch_common_operation(
    operation: ScriptOperation,
    args: serde_json::Value,
) -> Result<ScriptValue, ScriptError> {
    match operation {
        ScriptOperation::Status => Ok(ScriptValue::String(
            runtime::get_status_inner().map_err(script_host_error("status"))?,
        )),
        ScriptOperation::Version => Ok(ScriptValue::String(
            crate::get_version().map_err(script_host_error("version"))?,
        )),
        ScriptOperation::ValidateConfig => {
            let config_toml = string_arg(&args, "config_toml")?;
            Ok(ScriptValue::String(
                runtime::validate_config_inner(config_toml)
                    .map_err(script_host_error("validate_config"))?,
            ))
        }
        ScriptOperation::RunningConfig => Ok(ScriptValue::String(
            runtime::get_running_config_inner().map_err(script_host_error("config"))?,
        )),
        ScriptOperation::BindChannelIdentity => {
            let channel = string_arg(&args, "channel")?;
            let user_id = string_arg(&args, "user_id")?;
            let field = runtime::bind_channel_identity_inner(channel.clone(), user_id.clone())
                .map_err(script_host_error("bind"))?;
            let message = if field == "already_bound" {
                format!("{user_id} is already bound to {channel}")
            } else {
                format!("Bound {user_id} to {channel} ({field}). Restart daemon to apply.")
            };
            Ok(ScriptValue::String(message))
        }
        ScriptOperation::ChannelAllowlist => {
            let channel = string_arg(&args, "channel")?;
            let allowlist = runtime::get_channel_allowlist_inner(channel)
                .map_err(script_host_error("allowlist"))?;
            Ok(ScriptValue::String(to_json(&allowlist)?))
        }
        ScriptOperation::SwapProvider => {
            let provider = string_arg(&args, "provider")?;
            let model = string_arg(&args, "model")?;
            runtime::swap_provider_inner(provider, model, None)
                .map_err(script_host_error("swap_provider"))?;
            Ok(ScriptValue::Unit)
        }
        ScriptOperation::HealthDetail => Ok(ScriptValue::String(to_json(
            &health::get_health_detail_inner().map_err(script_host_error("health"))?,
        )?)),
        ScriptOperation::HealthComponent => {
            let name = string_arg(&args, "name")?;
            Ok(ScriptValue::String(to_json(
                &health::get_component_health_inner(name),
            )?))
        }
        ScriptOperation::DoctorChannels => {
            let config_toml = args
                .get("config_toml")
                .and_then(serde_json::Value::as_str)
                .unwrap_or_default()
                .to_string();
            let data_dir = args
                .get("data_dir")
                .and_then(serde_json::Value::as_str)
                .unwrap_or_default()
                .to_string();
            Ok(ScriptValue::String(
                runtime::doctor_channels_inner(config_toml, data_dir)
                    .map_err(script_host_error("doctor"))?,
            ))
        }
        ScriptOperation::CostSummary => Ok(ScriptValue::String(to_json(
            &cost::get_cost_summary_inner().map_err(script_host_error("cost"))?,
        )?)),
        ScriptOperation::DailyCost => Ok(ScriptValue::Float(
            cost::get_daily_cost_inner(
                i32_arg(&args, "year")?,
                u32_arg(&args, "month")?,
                u32_arg(&args, "day")?,
            )
            .map_err(script_host_error("cost_daily"))?,
        )),
        ScriptOperation::MonthlyCost => Ok(ScriptValue::Float(
            cost::get_monthly_cost_inner(i32_arg(&args, "year")?, u32_arg(&args, "month")?)
                .map_err(script_host_error("cost_monthly"))?,
        )),
        ScriptOperation::CheckBudget => Ok(ScriptValue::String(to_json(
            &cost::check_budget_inner(float_arg(&args, "estimated")?)
                .map_err(script_host_error("budget"))?,
        )?)),
        ScriptOperation::RecentEvents => Ok(ScriptValue::String(
            events::get_recent_events_inner(u32_arg(&args, "limit")?)
                .map_err(script_host_error("events"))?,
        )),
        ScriptOperation::ListCronJobs => Ok(ScriptValue::String(to_json(
            &cron::list_cron_jobs_inner().map_err(script_host_error("cron_list"))?,
        )?)),
        ScriptOperation::GetCronJob => Ok(ScriptValue::String(to_json(
            &cron::get_cron_job_inner(string_arg(&args, "id")?)
                .map_err(script_host_error("cron_get"))?,
        )?)),
        ScriptOperation::AddCronJob => Ok(ScriptValue::String(to_json(
            &cron::add_cron_job_inner(
                string_arg(&args, "expression")?,
                string_arg(&args, "command")?,
            )
            .map_err(script_host_error("cron_add"))?,
        )?)),
        ScriptOperation::AddOneShotJob => Ok(ScriptValue::String(to_json(
            &cron::add_one_shot_job_inner(
                string_arg(&args, "delay")?,
                string_arg(&args, "command")?,
            )
            .map_err(script_host_error("cron_oneshot"))?,
        )?)),
        ScriptOperation::AddCronJobAt => Ok(ScriptValue::String(to_json(
            &cron::add_cron_job_at_inner(
                string_arg(&args, "timestamp")?,
                string_arg(&args, "command")?,
            )
            .map_err(script_host_error("cron_add_at"))?,
        )?)),
        ScriptOperation::AddCronJobEvery => Ok(ScriptValue::String(to_json(
            &cron::add_cron_job_every_inner(
                u64_arg(&args, "every_ms")?,
                string_arg(&args, "command")?,
            )
            .map_err(script_host_error("cron_add_every"))?,
        )?)),
        ScriptOperation::RemoveCronJob => {
            cron::remove_cron_job_inner(string_arg(&args, "id")?)
                .map_err(script_host_error("cron_remove"))?;
            Ok(ScriptValue::Unit)
        }
        ScriptOperation::PauseCronJob => {
            cron::pause_cron_job_inner(string_arg(&args, "id")?)
                .map_err(script_host_error("cron_pause"))?;
            Ok(ScriptValue::Unit)
        }
        ScriptOperation::ResumeCronJob => {
            cron::resume_cron_job_inner(string_arg(&args, "id")?)
                .map_err(script_host_error("cron_resume"))?;
            Ok(ScriptValue::Unit)
        }
        ScriptOperation::ListSkills => Ok(ScriptValue::String(to_json(
            &skills::list_skills_inner().map_err(script_host_error("skills"))?,
        )?)),
        ScriptOperation::GetSkillTools => Ok(ScriptValue::String(to_json(
            &skills::get_skill_tools_inner(string_arg(&args, "name")?)
                .map_err(script_host_error("skill_tools"))?,
        )?)),
        ScriptOperation::InstallSkill => {
            skills::install_skill_inner(string_arg(&args, "source")?)
                .map_err(script_host_error("skill_install"))?;
            Ok(ScriptValue::Unit)
        }
        ScriptOperation::RemoveSkill => {
            skills::remove_skill_inner(string_arg(&args, "name")?)
                .map_err(script_host_error("skill_remove"))?;
            Ok(ScriptValue::Unit)
        }
        ScriptOperation::ListTools => Ok(ScriptValue::String(to_json(
            &tools_browse::list_tools_inner().map_err(script_host_error("tools"))?,
        )?)),
        ScriptOperation::ListMemories => Ok(ScriptValue::String(to_json(
            &memory_browse::list_memories_inner(None, u32_arg(&args, "limit")?, None)
                .map_err(script_host_error("memories"))?,
        )?)),
        ScriptOperation::ListMemoriesByCategory => Ok(ScriptValue::String(to_json(
            &memory_browse::list_memories_inner(
                Some(string_arg(&args, "category")?),
                u32_arg(&args, "limit")?,
                None,
            )
            .map_err(script_host_error("memories_by_category"))?,
        )?)),
        ScriptOperation::RecallMemory => Ok(ScriptValue::String(to_json(
            &memory_browse::recall_memory_inner(
                string_arg(&args, "query")?,
                u32_arg(&args, "limit")?,
                None,
            )
            .map_err(script_host_error("memory_recall"))?,
        )?)),
        ScriptOperation::ForgetMemory => Ok(ScriptValue::Bool(
            memory_browse::forget_memory_inner(string_arg(&args, "key")?)
                .map_err(script_host_error("memory_forget"))?,
        )),
        ScriptOperation::MemoryCount => Ok(ScriptValue::Int(i64::from(
            memory_browse::memory_count_inner().map_err(script_host_error("memory_count"))?,
        ))),
        ScriptOperation::EngageEStop => {
            crate::estop::engage_estop_inner().map_err(script_host_error("estop"))?;
            Ok(ScriptValue::Unit)
        }
        ScriptOperation::GetEStopStatus => {
            let status = crate::estop::get_estop_status_inner()
                .map_err(script_host_error("estop_status"))?;
            Ok(ScriptValue::String(
                serde_json::to_string(&serde_json::json!({
                    "engaged": status.engaged,
                    "engaged_at_ms": status.engaged_at_ms,
                }))
                .map_err(json_error("estop_status"))?,
            ))
        }
        ScriptOperation::ResumeEStop => {
            crate::estop::resume_estop_inner().map_err(script_host_error("estop_resume"))?;
            Ok(ScriptValue::Unit)
        }
        ScriptOperation::QueryTraces => Ok(ScriptValue::String(
            crate::traces::query_traces_inner(None, None, u32_arg(&args, "limit")?)
                .map_err(script_host_error("traces"))?,
        )),
        ScriptOperation::QueryTracesByFilter => Ok(ScriptValue::String(
            crate::traces::query_traces_inner(
                Some(string_arg(&args, "filter")?),
                None,
                u32_arg(&args, "limit")?,
            )
            .map_err(script_host_error("traces_filter"))?,
        )),
        ScriptOperation::ListAuthProfiles => Ok(ScriptValue::String(to_json(
            &auth_profiles::list_auth_profiles_inner()
                .map_err(script_host_error("auth_list"))?
                .iter()
                .map(|profile| {
                    serde_json::json!({
                        "id": profile.id,
                        "provider": profile.provider,
                        "kind": profile.kind,
                        "active": profile.is_active,
                    })
                })
                .collect::<Vec<_>>(),
        )?)),
        ScriptOperation::RemoveAuthProfile => {
            auth_profiles::remove_auth_profile_inner(
                string_arg(&args, "provider")?,
                string_arg(&args, "profile_name")?,
            )
            .map_err(script_host_error("auth_remove"))?;
            Ok(ScriptValue::Unit)
        }
        ScriptOperation::DiscoverModels => Ok(ScriptValue::String(
            models::discover_models_inner(string_arg(&args, "provider")?, String::new(), None)
                .map_err(script_host_error("models"))?,
        )),
        ScriptOperation::DiscoverModelsWithKey => Ok(ScriptValue::String(
            models::discover_models_inner(
                string_arg(&args, "provider")?,
                string_arg(&args, "api_key")?,
                None,
            )
            .map_err(script_host_error("models_with_key"))?,
        )),
        ScriptOperation::DiscoverModelsWithKeyAndBaseUrl => Ok(ScriptValue::String(
            models::discover_models_inner(
                string_arg(&args, "provider")?,
                string_arg(&args, "api_key")?,
                optional_string_arg(&args, "base_url"),
            )
            .map_err(script_host_error("models_full"))?,
        )),
        ScriptOperation::InvokeTool => {
            let name = string_arg(&args, "name")?;
            let tool_args =
                optional_string_arg(&args, "args").unwrap_or_else(|| "{}".to_string());
            Ok(ScriptValue::String(
                tools_browse::invoke_tool_inner(&name, &tool_args)
                    .map_err(script_host_error("tool_call"))?,
            ))
        }
        ScriptOperation::ReadStorage => {
            let key = string_arg(&args, "key")?;
            let script_name = optional_string_arg(&args, "script")
                .unwrap_or_else(|| "anonymous".to_string());
            match with_script_storage(|store| store.read(&script_name, &key))? {
                Some(v) => Ok(ScriptValue::String(v)),
                None => Ok(ScriptValue::Unit),
            }
        }
        ScriptOperation::WriteStorage => {
            let key = string_arg(&args, "key")?;
            let value = string_arg(&args, "value")?;
            if value.len() > 1_048_576 {
                return Err(ScriptError::InvalidArgument {
                    detail: "storage value exceeds 1 MiB limit".to_string(),
                });
            }
            let script_name = optional_string_arg(&args, "script")
                .unwrap_or_else(|| "anonymous".to_string());
            with_script_storage(|store| store.write(&script_name, &key, &value))?;
            Ok(ScriptValue::Unit)
        }
        ScriptOperation::DeleteStorage => {
            let key = string_arg(&args, "key")?;
            let script_name = optional_string_arg(&args, "script")
                .unwrap_or_else(|| "anonymous".to_string());
            let deleted = with_script_storage(|store| store.delete(&script_name, &key))?;
            Ok(ScriptValue::Bool(deleted))
        }
        // SendMessage and SendVision are handled by each ScriptHost
        // implementation directly, not through common dispatch.
        ScriptOperation::SendMessage | ScriptOperation::SendVision => {
            Err(ScriptError::HostError {
                operation: operation.display_name().to_string(),
                detail: "SendMessage/SendVision must be handled by the ScriptHost implementation"
                    .to_string(),
            })
        }
    }
}

struct FfiScriptHost;

impl ScriptHost for FfiScriptHost {
    fn call(
        &self,
        operation: ScriptOperation,
        args: serde_json::Value,
    ) -> Result<ScriptValue, ScriptError> {
        // Extract the manifest name injected by call_host() and strip it
        // from the args before dispatching to the operation handler.
        let manifest_name = args
            .get("__manifest_name")
            .and_then(serde_json::Value::as_str)
            .unwrap_or("anonymous")
            .to_string();
        let args = {
            if let serde_json::Value::Object(mut map) = args {
                map.remove("__manifest_name");
                serde_json::Value::Object(map)
            } else {
                args
            }
        };

        // Gate: dangerous capabilities require explicit user approval
        // persisted in capability_grants.json. If the grant doesn't exist
        // yet, block this thread while Kotlin presents the approval prompt.
        require_dangerous_capability_approval(operation, &manifest_name)?;

        match operation {
            ScriptOperation::SendMessage => {
                let message = string_arg(&args, "message")?;
                Ok(ScriptValue::String(
                    runtime::send_message_inner(message).map_err(script_host_error("send"))?,
                ))
            }
            ScriptOperation::SendVision => {
                let text = string_arg(&args, "text")?;
                let images = string_list_arg(&args, "images");
                let mime_types = string_list_arg(&args, "mime_types");
                Ok(ScriptValue::String(
                    vision::send_vision_message_inner(text, images, mime_types)
                        .map_err(script_host_error("send_vision"))?,
                ))
            }
            other => dispatch_common_operation(other, args),
        }
    }
}

fn script_runtime() -> &'static RhaiScriptRuntime {
    SCRIPT_RUNTIME.get_or_init(|| RhaiScriptRuntime::new(Arc::new(FfiScriptHost)))
}

fn runtime_label(kind: &ScriptRuntimeKind) -> String {
    kind.identifier().to_string()
}

fn validation_to_ffi(validation: zeroclaw::scripting::ScriptValidation) -> FfiScriptValidation {
    FfiScriptValidation {
        manifest_name: validation.manifest_name,
        runtime: runtime_label(&validation.runtime),
        requested_capabilities: validation.requested_capabilities,
        missing_capabilities: validation.missing_capabilities,
        warnings: validation.warnings,
        available_capabilities: validation.available_capabilities,
    }
}

fn manifest_from_capabilities(capabilities: Vec<String>) -> ScriptManifest {
    ScriptManifest {
        capabilities,
        explicit_capabilities: true,
        ..Default::default()
    }
}

fn trigger_summary(trigger: &zeroclaw::scripting::ScriptTrigger) -> String {
    let mut parts = vec![trigger.kind.clone()];
    if let Some(schedule) = &trigger.schedule {
        parts.push(format!("schedule={schedule}"));
    }
    if let Some(event) = &trigger.event {
        parts.push(format!("event={event}"));
    }
    if let Some(channel) = &trigger.channel {
        parts.push(format!("channel={channel}"));
    }
    if let Some(provider) = &trigger.provider {
        parts.push(format!("provider={provider}"));
    }
    if let Some(script) = &trigger.script {
        parts.push(format!("script={script}"));
    }
    parts.join(" | ")
}

fn workspace_script_to_ffi(manifest: zeroclaw::scripting::ScriptManifest) -> FfiWorkspaceScript {
    FfiWorkspaceScript {
        name: manifest.name,
        version: manifest.version,
        description: manifest.description,
        runtime: runtime_label(&manifest.runtime),
        relative_path: manifest
            .script_path
            .map(|path| path.to_string_lossy().replace('\\', "/"))
            .unwrap_or_default(),
        entrypoint: manifest.entrypoint,
        requested_capabilities: manifest.capabilities,
        trigger_summaries: manifest.triggers.iter().map(trigger_summary).collect(),
    }
}

/// Wraps an [`FfiError`] into a [`ScriptError::HostError`] with the given
/// operation label.
pub(crate) fn script_host_error(operation: &'static str) -> impl FnOnce(FfiError) -> ScriptError {
    move |error| ScriptError::HostError {
        operation: operation.to_string(),
        detail: error.to_string(),
    }
}

/// Wraps a [`serde_json::Error`] into a [`ScriptError::HostError`] with a
/// serialization-failure message.
pub(crate) fn json_error(
    operation: &'static str,
) -> impl FnOnce(serde_json::Error) -> ScriptError {
    move |error| ScriptError::HostError {
        operation: operation.to_string(),
        detail: format!("serialization failed: {error}"),
    }
}

/// Converts a [`ScriptError`] into the appropriate [`FfiError`] variant.
pub(crate) fn map_script_error(error: ScriptError) -> FfiError {
    let detail = error.to_string();
    match error {
        ScriptError::InvalidArgument { .. }
        | ScriptError::ValidationError { .. }
        | ScriptError::CapabilityDenied { .. } => FfiError::InvalidArgument { detail },
        ScriptError::HostError { .. } => FfiError::StateError { detail },
        ScriptError::InternalState { .. } => FfiError::StateCorrupted { detail },
    }
}

/// Serializes a value to JSON, mapping failures to [`ScriptError`].
pub(crate) fn to_json<T: serde::Serialize>(value: &T) -> Result<String, ScriptError> {
    serde_json::to_string(value).map_err(json_error("json"))
}

/// Extracts a required string argument from a JSON value map.
pub(crate) fn string_arg(args: &serde_json::Value, key: &str) -> Result<String, ScriptError> {
    args.get(key)
        .and_then(serde_json::Value::as_str)
        .map(ToString::to_string)
        .ok_or_else(|| ScriptError::InvalidArgument {
            detail: format!("missing string argument: {key}"),
        })
}

/// Extracts an optional string argument, returning `None` when absent or empty.
pub(crate) fn optional_string_arg(args: &serde_json::Value, key: &str) -> Option<String> {
    args.get(key)
        .and_then(serde_json::Value::as_str)
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .map(ToString::to_string)
}

/// Extracts a list of strings from a JSON array argument.
pub(crate) fn string_list_arg(args: &serde_json::Value, key: &str) -> Vec<String> {
    args.get(key)
        .and_then(serde_json::Value::as_array)
        .into_iter()
        .flatten()
        .filter_map(serde_json::Value::as_str)
        .map(ToString::to_string)
        .collect()
}

/// Extracts a required `i64` argument from a JSON value map.
pub(crate) fn int_arg(args: &serde_json::Value, key: &str) -> Result<i64, ScriptError> {
    args.get(key)
        .and_then(serde_json::Value::as_i64)
        .ok_or_else(|| ScriptError::InvalidArgument {
            detail: format!("missing integer argument: {key}"),
        })
}

/// Extracts a required `i32` argument from a JSON value map.
pub(crate) fn i32_arg(args: &serde_json::Value, key: &str) -> Result<i32, ScriptError> {
    i32::try_from(int_arg(args, key)?).map_err(|_| ScriptError::InvalidArgument {
        detail: format!("integer argument out of range for i32: {key}"),
    })
}

/// Extracts a required non-negative `u32` argument from a JSON value map.
pub(crate) fn u32_arg(args: &serde_json::Value, key: &str) -> Result<u32, ScriptError> {
    u32::try_from(int_arg(args, key)?).map_err(|_| ScriptError::InvalidArgument {
        detail: format!("integer argument must be a non-negative u32: {key}"),
    })
}

/// Extracts a required non-negative `u64` argument from a JSON value map.
pub(crate) fn u64_arg(args: &serde_json::Value, key: &str) -> Result<u64, ScriptError> {
    u64::try_from(int_arg(args, key)?).map_err(|_| ScriptError::InvalidArgument {
        detail: format!("integer argument must be a non-negative u64: {key}"),
    })
}

/// Extracts a required `f64` argument from a JSON value map.
pub(crate) fn float_arg(args: &serde_json::Value, key: &str) -> Result<f64, ScriptError> {
    args.get(key)
        .and_then(serde_json::Value::as_f64)
        .ok_or_else(|| ScriptError::InvalidArgument {
            detail: format!("missing float argument: {key}"),
        })
}

fn repl_default_manifest() -> ScriptManifest {
    ScriptManifest {
        name: "repl".to_string(),
        explicit_capabilities: true,
        capabilities: vec![
            "model.chat".to_string(),
            "model.read".to_string(),
            "status.read".to_string(),
            "storage.read".to_string(),
            "storage.write".to_string(),
            "memory.read".to_string(),
            "cost.read".to_string(),
            "events.read".to_string(),
        ],
        ..Default::default()
    }
}

pub(crate) fn eval_script_inner(source: String) -> Result<String, FfiError> {
    script_runtime()
        .eval_script(&source, Some(repl_default_manifest()))
        .map_err(map_script_error)
}

pub(crate) fn eval_script_with_capabilities_inner(
    source: String,
    granted_capabilities: Vec<String>,
) -> Result<String, FfiError> {
    script_runtime()
        .eval_script(
            &source,
            Some(manifest_from_capabilities(granted_capabilities)),
        )
        .map_err(map_script_error)
}

pub(crate) fn eval_repl_inner(source: String) -> Result<String, FfiError> {
    eval_script_inner(source)
}

pub(crate) fn validate_script_inner(source: String) -> Result<FfiScriptValidation, FfiError> {
    let validation = script_runtime()
        .validate_script(&source, None)
        .map_err(map_script_error)?;
    Ok(validation_to_ffi(validation))
}

pub(crate) fn validate_script_with_capabilities_inner(
    source: String,
    granted_capabilities: Vec<String>,
) -> Result<FfiScriptValidation, FfiError> {
    let validation = script_runtime()
        .validate_script(
            &source,
            Some(manifest_from_capabilities(granted_capabilities)),
        )
        .map_err(map_script_error)?;
    Ok(validation_to_ffi(validation))
}

pub(crate) fn list_script_capabilities_inner() -> Vec<String> {
    script_runtime()
        .list_capabilities()
        .into_iter()
        .map(|capability| capability.name)
        .collect()
}

fn workspace_dir() -> Result<std::path::PathBuf, FfiError> {
    crate::runtime::with_daemon_config(|config| config.workspace_dir.clone())
}

fn list_workspace_scripts_in_dir(workspace_dir: &Path) -> Vec<FfiWorkspaceScript> {
    zeroclaw::scripting::discover_workspace_scripts(workspace_dir)
        .into_iter()
        .map(workspace_script_to_ffi)
        .collect()
}

pub(crate) fn list_workspace_scripts_inner() -> Result<Vec<FfiWorkspaceScript>, FfiError> {
    let workspace_dir = workspace_dir()?;
    Ok(list_workspace_scripts_in_dir(&workspace_dir))
}

pub(crate) fn validate_workspace_script_inner(
    relative_path: String,
) -> Result<FfiScriptValidation, FfiError> {
    let workspace_dir = workspace_dir()?;
    let validation = script_runtime()
        .validate_workspace_script(&workspace_dir, Path::new(&relative_path), None)
        .map_err(map_script_error)?;
    Ok(validation_to_ffi(validation))
}

pub(crate) fn run_workspace_script_inner(
    relative_path: String,
    granted_capabilities: Vec<String>,
) -> Result<String, FfiError> {
    let workspace_dir = workspace_dir()?;
    let manifest_override = Some(granted_capabilities);
    script_runtime()
        .eval_workspace_script(&workspace_dir, Path::new(&relative_path), manifest_override)
        .map_err(map_script_error)
}

pub(crate) fn list_script_runtimes_inner() -> Vec<FfiScriptRuntime> {
    script_runtime()
        .plugin_runtimes()
        .into_iter()
        .map(|runtime| FfiScriptRuntime {
            kind: runtime_label(&runtime.kind),
            available: runtime.available,
            isolates_guest: runtime.isolates_guest,
            notes: runtime.notes,
        })
        .collect()
}

pub(crate) fn register_script_triggers_inner() -> Result<u32, FfiError> {
    let workspace_dir = workspace_dir()?;

    // Register the real FfiScriptHost so cron script jobs can call back
    // into the Android bridge instead of falling back to StubScriptHost.
    let host: Arc<dyn zeroclaw::scripting::ScriptHost> = Arc::new(FfiScriptHost);
    zeroclaw::scripting::set_cron_script_host(host);

    let scripts = zeroclaw::scripting::discover_workspace_scripts(&workspace_dir);
    let cron_triggers = zeroclaw::scripting::triggers::collect_cron_triggers(&scripts);

    let config = crate::runtime::clone_daemon_config()?;
    let existing = zeroclaw::cron::list_jobs(&config)
        .map_err(|e| FfiError::StateError { detail: e.to_string() })?;

    let mut registered = 0u32;
    for resolved in &cron_triggers {
        let Some(schedule_expr) = resolved.trigger.schedule.as_deref() else { continue };
        let script_path = match resolved.manifest.script_path.as_ref() {
            Some(p) => p.to_string_lossy().to_string(),
            None => continue,
        };
        let trigger_name = format!("trigger:{}", resolved.manifest.name);

        // Idempotent: skip if job with this name already exists
        if existing
            .iter()
            .any(|j| j.name.as_deref() == Some(trigger_name.as_str()))
        {
            continue;
        }

        validate_capabilities(&resolved.manifest.capabilities).map_err(map_script_error)?;

        let schedule = zeroclaw::cron::Schedule::Cron {
            expr: schedule_expr.to_string(),
            tz: None,
        };
        match zeroclaw::cron::add_script_job(
            &config,
            Some(trigger_name.clone()),
            schedule,
            &script_path,
            &resolved.manifest.capabilities,
        ) {
            Ok(_) => registered += 1,
            Err(e) => tracing::warn!("Failed to register trigger {trigger_name}: {e}"),
        }
    }

    Ok(registered)
}

pub(crate) fn script_plugin_host_wit_inner() -> String {
    script_runtime().plugin_host_wit().to_string()
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    #[test]
    fn eval_script_still_supports_basic_expressions() {
        let result = eval_script_inner("2 + 3".into()).unwrap();
        assert_eq!(result, "5");
    }

    #[test]
    fn validate_script_returns_requested_capabilities() {
        let validation = validate_script_inner(r"agent::status(); memory_count();".into()).unwrap();
        assert!(
            validation
                .requested_capabilities
                .contains(&"agent.read".to_string())
        );
        assert!(
            validation
                .requested_capabilities
                .contains(&"memory.read".to_string())
        );
    }

    #[test]
    fn capability_listing_includes_default_denies() {
        let capabilities = list_script_capabilities_inner();
        assert!(capabilities.contains(&"net.none".to_string()));
        assert!(capabilities.contains(&"model.chat".to_string()));
    }

    #[test]
    fn explicit_empty_capabilities_deny_host_calls() {
        let error =
            eval_script_with_capabilities_inner(r#"send("hello");"#.into(), vec![]).unwrap_err();
        assert!(matches!(error, FfiError::InvalidArgument { .. }));
        assert!(error.to_string().contains("capability denied"));
    }

    #[test]
    fn list_script_runtimes_reports_rhai() {
        let runtimes = list_script_runtimes_inner();
        assert!(
            runtimes
                .iter()
                .any(|runtime| runtime.kind == "rhai" && runtime.available)
        );
    }

    #[test]
    fn plugin_host_wit_is_exposed() {
        let wit = script_plugin_host_wit_inner();
        assert!(wit.contains("world zero-scripting-plugin"));
    }

    #[test]
    fn list_workspace_scripts_surfaces_manifests() {
        let dir = tempfile::tempdir().unwrap();
        let workflows_dir = dir.path().join("workflows");
        std::fs::create_dir_all(&workflows_dir).unwrap();
        std::fs::write(workflows_dir.join("cleanup.rhai"), "2 + 2").unwrap();

        let manifests = list_workspace_scripts_in_dir(dir.path());
        assert!(
            manifests
                .iter()
                .any(|manifest| manifest.relative_path == "workflows/cleanup.rhai")
        );
    }
}
