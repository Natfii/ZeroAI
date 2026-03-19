/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

//! Thin FFI bridge over the core Rust scripting runtime.

use std::path::Path;
use std::sync::{Arc, OnceLock};

use crate::error::FfiError;
use crate::{
    auth_profiles, cost, cron, events, health, memory_browse, models, runtime, skills,
    tools_browse, vision,
};
use zeroclaw::scripting::{
    RhaiScriptRuntime, ScriptError, ScriptHost, ScriptManifest, ScriptOperation, ScriptRuntimeKind,
    ScriptValue,
};

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

struct FfiScriptHost;

impl ScriptHost for FfiScriptHost {
    #[allow(clippy::too_many_lines)]
    fn call(
        &self,
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

fn script_host_error(operation: &'static str) -> impl FnOnce(FfiError) -> ScriptError {
    move |error| ScriptError::HostError {
        operation: operation.to_string(),
        detail: error.to_string(),
    }
}

fn json_error(operation: &'static str) -> impl FnOnce(serde_json::Error) -> ScriptError {
    move |error| ScriptError::HostError {
        operation: operation.to_string(),
        detail: format!("serialization failed: {error}"),
    }
}

fn map_script_error(error: ScriptError) -> FfiError {
    let detail = error.to_string();
    match error {
        ScriptError::InvalidArgument { .. }
        | ScriptError::ValidationError { .. }
        | ScriptError::CapabilityDenied { .. } => FfiError::InvalidArgument { detail },
        ScriptError::HostError { .. } => FfiError::SpawnError { detail },
        ScriptError::InternalState { .. } => FfiError::StateCorrupted { detail },
    }
}

fn to_json<T: serde::Serialize>(value: &T) -> Result<String, ScriptError> {
    serde_json::to_string(value).map_err(json_error("json"))
}

fn string_arg(args: &serde_json::Value, key: &str) -> Result<String, ScriptError> {
    args.get(key)
        .and_then(serde_json::Value::as_str)
        .map(ToString::to_string)
        .ok_or_else(|| ScriptError::InvalidArgument {
            detail: format!("missing string argument: {key}"),
        })
}

fn optional_string_arg(args: &serde_json::Value, key: &str) -> Option<String> {
    args.get(key)
        .and_then(serde_json::Value::as_str)
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .map(ToString::to_string)
}

fn string_list_arg(args: &serde_json::Value, key: &str) -> Vec<String> {
    args.get(key)
        .and_then(serde_json::Value::as_array)
        .into_iter()
        .flatten()
        .filter_map(serde_json::Value::as_str)
        .map(ToString::to_string)
        .collect()
}

fn int_arg(args: &serde_json::Value, key: &str) -> Result<i64, ScriptError> {
    args.get(key)
        .and_then(serde_json::Value::as_i64)
        .ok_or_else(|| ScriptError::InvalidArgument {
            detail: format!("missing integer argument: {key}"),
        })
}

fn i32_arg(args: &serde_json::Value, key: &str) -> Result<i32, ScriptError> {
    i32::try_from(int_arg(args, key)?).map_err(|_| ScriptError::InvalidArgument {
        detail: format!("integer argument out of range for i32: {key}"),
    })
}

fn u32_arg(args: &serde_json::Value, key: &str) -> Result<u32, ScriptError> {
    u32::try_from(int_arg(args, key)?).map_err(|_| ScriptError::InvalidArgument {
        detail: format!("integer argument must be a non-negative u32: {key}"),
    })
}

fn u64_arg(args: &serde_json::Value, key: &str) -> Result<u64, ScriptError> {
    u64::try_from(int_arg(args, key)?).map_err(|_| ScriptError::InvalidArgument {
        detail: format!("integer argument must be a non-negative u64: {key}"),
    })
}

fn float_arg(args: &serde_json::Value, key: &str) -> Result<f64, ScriptError> {
    args.get(key)
        .and_then(serde_json::Value::as_f64)
        .ok_or_else(|| ScriptError::InvalidArgument {
            detail: format!("missing float argument: {key}"),
        })
}

pub(crate) fn eval_script_inner(source: String) -> Result<String, FfiError> {
    script_runtime()
        .eval_script(&source, None)
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
