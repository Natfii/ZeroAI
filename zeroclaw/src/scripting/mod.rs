// Copyright (c) 2026 Zeroclaw Labs. All rights reserved.

//! Core scripting runtime, manifest model, and capability surface.
//!
//! This module centralises ZeroAI's scripting ownership inside \`zeroclaw\`
//! instead of the Android FFI crate. Rhai remains the first embedded
//! runtime, but the capability model and plugin ABI are defined here so
//! future runtimes can share the same host contract.

pub mod content_hash;
pub mod plugin_abi;
pub mod storage;
pub mod triggers;

use crate::observability::runtime_trace;
use chrono::Datelike;
use rhai::packages::{CorePackage, Package};
use rhai::{Array, Dynamic, Engine, EvalAltResult, Module};
use serde::{Deserialize, Serialize};
use serde_json::json;
use std::collections::BTreeSet;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Instant;

/// Stable identifier for the runtime used by a script or plugin guest.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "snake_case")]
pub enum ScriptRuntimeKind {
    /// Lightweight embedded Rhai workflow.
    #[default]
    Rhai,
    /// Stronger-isolation WebAssembly component guest.
    WasmComponent,
    /// Optional polyglot guest reserved for later compatibility needs.
    Python,
}

impl ScriptRuntimeKind {
    /// Returns the stable runtime identifier used across FFI and audit records.
    pub fn identifier(&self) -> &'static str {
        match self {
            Self::Rhai => "rhai",
            Self::WasmComponent => "wasm-component",
            Self::Python => "python",
        }
    }
}

/// Capability that a script can request and the runtime can enforce.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
pub struct ScriptCapability {
    /// Stable capability name such as \`model.chat\` or \`memory.read\`.
    pub name: String,
    /// Human-readable description of what the capability unlocks.
    pub description: String,
    /// Optional scope suffix (for example a tool name or path scope).
    #[serde(default)]
    pub scope: Option<String>,
}

/// Resource limits enforced for every script execution.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ScriptLimits {
    /// Maximum Rhai operations before termination.
    pub max_operations: u64,
    /// Maximum function call depth.
    pub max_call_levels: usize,
    /// Maximum expression nesting depth.
    pub max_expr_depth: usize,
    /// Maximum size of a single string in bytes.
    pub max_string_size: usize,
    /// Maximum array length.
    pub max_array_size: usize,
    /// Maximum map length.
    pub max_map_size: usize,
    /// Maximum raw source size accepted for a single script.
    pub max_script_bytes: usize,
}

impl Default for ScriptLimits {
    fn default() -> Self {
        Self {
            max_operations: 100_000,
            max_call_levels: 16,
            max_expr_depth: 32,
            max_string_size: 64 * 1024,
            max_array_size: 1_024,
            max_map_size: 256,
            max_script_bytes: 128 * 1024,
        }
    }
}

/// Trigger metadata for packaged scripts.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
pub struct ScriptTrigger {
    /// Trigger type such as \`manual\`, \`cron\`, \`channel_event\`, or \`provider_event\`.
    pub kind: String,
    /// Optional cron schedule when \`kind == "cron"\`.
    #[serde(default)]
    pub schedule: Option<String>,
    /// Optional event name for event-driven triggers.
    #[serde(default)]
    pub event: Option<String>,
    /// Optional channel selector.
    #[serde(default)]
    pub channel: Option<String>,
    /// Optional provider selector.
    #[serde(default)]
    pub provider: Option<String>,
    /// Optional script name when multiple scripts share one manifest.
    #[serde(default)]
    pub script: Option<String>,
}

/// Manifest metadata describing one script entrypoint.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
pub struct ScriptManifest {
    /// Display name or stable identifier.
    pub name: String,
    /// Semantic version string.
    #[serde(default = "default_script_version")]
    pub version: String,
    /// Optional short description.
    #[serde(default)]
    pub description: Option<String>,
    /// Runtime kind used for execution.
    #[serde(default)]
    pub runtime: ScriptRuntimeKind,
    /// Optional entrypoint or export name.
    #[serde(default)]
    pub entrypoint: Option<String>,
    /// Optional path relative to the workspace or skill root.
    #[serde(default)]
    pub script_path: Option<PathBuf>,
    /// Explicitly granted capabilities for this execution.
    #[serde(default)]
    pub capabilities: Vec<String>,
    /// Whether the caller explicitly supplied the capability grant set.
    ///
    /// When `true`, an empty capability list remains empty instead of falling
    /// back to source inference. This allows callers to deny all host access
    /// explicitly while still using validation warnings for review.
    #[serde(default)]
    pub explicit_capabilities: bool,
    /// Trigger metadata for packaged workflows.
    #[serde(default)]
    pub triggers: Vec<ScriptTrigger>,
    /// Per-script resource limits.
    #[serde(default)]
    pub limits: ScriptLimits,
}

/// Result of script preflight validation.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
pub struct ScriptValidation {
    /// Normalised manifest name.
    pub manifest_name: String,
    /// Runtime kind that would be used to execute the script.
    pub runtime: ScriptRuntimeKind,
    /// Capabilities the script requests for the next run.
    pub requested_capabilities: Vec<String>,
    /// Capabilities inferred from source but missing from the explicit manifest.
    pub missing_capabilities: Vec<String>,
    /// Non-fatal validation warnings.
    pub warnings: Vec<String>,
    /// All capabilities the runtime knows how to enforce.
    pub available_capabilities: Vec<String>,
}

/// Audit record emitted for validation and execution traces.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
pub struct ScriptAuditRecord {
    /// Script name or fallback identifier.
    pub script_name: String,
    /// Runtime kind that handled the script.
    pub runtime: ScriptRuntimeKind,
    /// Whether the operation succeeded.
    pub success: bool,
    /// Capabilities requested for the run.
    pub requested_capabilities: Vec<String>,
    /// Capabilities the script attempted to use.
    pub attempted_capabilities: Vec<String>,
    /// Capabilities that were actually granted and used.
    pub used_capabilities: Vec<String>,
    /// Missing capabilities compared with the explicit manifest.
    pub missing_capabilities: Vec<String>,
    /// Warning messages gathered during validation.
    pub warnings: Vec<String>,
    /// Optional error detail.
    #[serde(default)]
    pub error: Option<String>,
    /// Execution duration in milliseconds.
    pub duration_ms: u128,
}

/// Advertised availability for current and future guest runtimes.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
pub struct ScriptPluginRuntime {
    /// Runtime kind.
    pub kind: ScriptRuntimeKind,
    /// Whether the runtime is currently available in this build.
    pub available: bool,
    /// Whether the runtime isolates guest code more strongly than Rhai.
    pub isolates_guest: bool,
    /// Human-readable note describing the current state.
    pub notes: String,
}

/// Operation that the scripting runtime can invoke on the host.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub enum ScriptOperation {
    Status,
    Version,
    SendMessage,
    SendVision,
    ValidateConfig,
    RunningConfig,
    BindChannelIdentity,
    ChannelAllowlist,
    SwapProvider,
    HealthDetail,
    HealthComponent,
    DoctorChannels,
    CostSummary,
    DailyCost,
    MonthlyCost,
    CheckBudget,
    RecentEvents,
    ListCronJobs,
    GetCronJob,
    AddCronJob,
    AddOneShotJob,
    AddCronJobAt,
    AddCronJobEvery,
    RemoveCronJob,
    PauseCronJob,
    ResumeCronJob,
    ListSkills,
    GetSkillTools,
    InstallSkill,
    RemoveSkill,
    ListTools,
    ListMemories,
    ListMemoriesByCategory,
    RecallMemory,
    ForgetMemory,
    MemoryCount,
    EngageEStop,
    GetEStopStatus,
    ResumeEStop,
    QueryTraces,
    QueryTracesByFilter,
    ListAuthProfiles,
    RemoveAuthProfile,
    DiscoverModels,
    DiscoverModelsWithKey,
    DiscoverModelsWithKeyAndBaseUrl,
    InvokeTool,
    ReadStorage,
    WriteStorage,
    DeleteStorage,
}

impl ScriptOperation {
    fn display_name(self) -> &'static str {
        match self {
            Self::Status => "status",
            Self::Version => "version",
            Self::SendMessage => "send",
            Self::SendVision => "send_vision",
            Self::ValidateConfig => "validate_config",
            Self::RunningConfig => "config",
            Self::BindChannelIdentity => "bind",
            Self::ChannelAllowlist => "allowlist",
            Self::SwapProvider => "swap_provider",
            Self::HealthDetail => "health",
            Self::HealthComponent => "health_component",
            Self::DoctorChannels => "doctor",
            Self::CostSummary => "cost",
            Self::DailyCost => "cost_daily",
            Self::MonthlyCost => "cost_monthly",
            Self::CheckBudget => "budget",
            Self::RecentEvents => "events",
            Self::ListCronJobs => "cron_list",
            Self::GetCronJob => "cron_get",
            Self::AddCronJob => "cron_add",
            Self::AddOneShotJob => "cron_oneshot",
            Self::AddCronJobAt => "cron_add_at",
            Self::AddCronJobEvery => "cron_add_every",
            Self::RemoveCronJob => "cron_remove",
            Self::PauseCronJob => "cron_pause",
            Self::ResumeCronJob => "cron_resume",
            Self::ListSkills => "skills",
            Self::GetSkillTools => "skill_tools",
            Self::InstallSkill => "skill_install",
            Self::RemoveSkill => "skill_remove",
            Self::ListTools => "tools",
            Self::ListMemories => "memories",
            Self::ListMemoriesByCategory => "memories_by_category",
            Self::RecallMemory => "memory_recall",
            Self::ForgetMemory => "memory_forget",
            Self::MemoryCount => "memory_count",
            Self::EngageEStop => "estop",
            Self::GetEStopStatus => "estop_status",
            Self::ResumeEStop => "estop_resume",
            Self::QueryTraces => "traces",
            Self::QueryTracesByFilter => "traces_filter",
            Self::ListAuthProfiles => "auth_list",
            Self::RemoveAuthProfile => "auth_remove",
            Self::DiscoverModels => "models",
            Self::DiscoverModelsWithKey => "models_with_key",
            Self::DiscoverModelsWithKeyAndBaseUrl => "models_full",
            Self::InvokeTool => "tool_call",
            Self::ReadStorage => "storage_read",
            Self::WriteStorage => "storage_write",
            Self::DeleteStorage => "storage_delete",
        }
    }

    fn capability(self) -> &'static str {
        match self {
            Self::Status
            | Self::Version
            | Self::RunningConfig
            | Self::HealthDetail
            | Self::HealthComponent
            | Self::DoctorChannels => "agent.read",
            Self::SendMessage | Self::SendVision => "model.chat",
            Self::ValidateConfig => "config.validate",
            Self::BindChannelIdentity => "channel.write",
            Self::ChannelAllowlist => "channel.read",
            Self::SwapProvider => "provider.write",
            Self::CostSummary | Self::DailyCost | Self::MonthlyCost | Self::CheckBudget => {
                "cost.read"
            }
            Self::RecentEvents => "events.read",
            Self::ListCronJobs | Self::GetCronJob => "cron.read",
            Self::AddCronJob
            | Self::AddOneShotJob
            | Self::AddCronJobAt
            | Self::AddCronJobEvery
            | Self::RemoveCronJob
            | Self::PauseCronJob
            | Self::ResumeCronJob => "cron.write",
            Self::ListSkills | Self::GetSkillTools => "skills.read",
            Self::InstallSkill | Self::RemoveSkill => "skills.write",
            Self::ListTools => "tools.read",
            Self::ListMemories
            | Self::ListMemoriesByCategory
            | Self::RecallMemory
            | Self::MemoryCount => "memory.read",
            Self::ForgetMemory => "memory.write",
            Self::EngageEStop | Self::GetEStopStatus | Self::ResumeEStop => "agent.control",
            Self::QueryTraces | Self::QueryTracesByFilter => "trace.read",
            Self::ListAuthProfiles => "auth.read",
            Self::RemoveAuthProfile => "auth.write",
            Self::DiscoverModels
            | Self::DiscoverModelsWithKey
            | Self::DiscoverModelsWithKeyAndBaseUrl => "model.read",
            Self::InvokeTool => "tools.call",
            Self::ReadStorage => "storage.read",
            Self::WriteStorage | Self::DeleteStorage => "storage.write",
        }
    }
}

/// Return value emitted by the host bridge.
#[derive(Debug, Clone, PartialEq)]
pub enum ScriptValue {
    Unit,
    String(String),
    Bool(bool),
    Int(i64),
    Float(f64),
}

impl ScriptValue {
    fn to_display_string(self) -> String {
        match self {
            Self::Unit => "ok".to_string(),
            Self::String(value) => value,
            Self::Bool(value) => value.to_string(),
            Self::Int(value) => value.to_string(),
            Self::Float(value) => value.to_string(),
        }
    }
}

/// Script runtime error with deterministic categories.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum ScriptError {
    /// Caller-supplied input was invalid.
    #[error("invalid argument: {detail}")]
    InvalidArgument { detail: String },
    /// Script validation failed before execution.
    #[error("validation error: {detail}")]
    ValidationError { detail: String },
    /// The script attempted a capability it was not granted.
    #[error("capability denied for {operation}: {capability}")]
    CapabilityDenied {
        /// Operation name that was denied.
        operation: String,
        /// Missing capability.
        capability: String,
    },
    /// Host-side execution failed.
    #[error("host error in {operation}: {detail}")]
    HostError {
        /// Operation name that failed.
        operation: String,
        /// Failure detail.
        detail: String,
    },
    /// Internal state was corrupted.
    #[error("internal state corrupted: {detail}")]
    InternalState { detail: String },
}

/// Host interface implemented by the FFI bridge or other frontends.
pub trait ScriptHost: Send + Sync {
    /// Execute one host operation for the scripting runtime.
    fn call(
        &self,
        operation: ScriptOperation,
        args: serde_json::Value,
    ) -> Result<ScriptValue, ScriptError>;
}

/// Stub host that rejects all operations. Used by the cron scheduler
/// when a full FFI host is unavailable.
pub struct StubScriptHost;

impl ScriptHost for StubScriptHost {
    fn call(
        &self,
        operation: ScriptOperation,
        _args: serde_json::Value,
    ) -> Result<ScriptValue, ScriptError> {
        Err(ScriptError::HostError {
            operation: operation.display_name().to_string(),
            detail: "no host available in this context".to_string(),
        })
    }
}

static CRON_SCRIPT_HOST: OnceLock<Arc<dyn ScriptHost>> = OnceLock::new();

/// Registers the [`ScriptHost`] that cron script jobs should use.
///
/// This is typically called once during FFI startup so the cron scheduler
/// can delegate host operations to the real Android bridge instead of the
/// stub.
pub fn set_cron_script_host(host: Arc<dyn ScriptHost>) {
    let _ = CRON_SCRIPT_HOST.set(host);
}

/// Returns the registered cron [`ScriptHost`], if any.
pub fn get_cron_script_host() -> Option<Arc<dyn ScriptHost>> {
    CRON_SCRIPT_HOST.get().cloned()
}

struct ScriptExecutionSession {
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
    fn new(validation: &ScriptValidation) -> Self {
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

    fn require_capability(
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

    fn audit_record(self, success: bool, error: Option<String>) -> ScriptAuditRecord {
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

/// Rhai-backed runtime for safe workflow-style scripting.
pub struct RhaiScriptRuntime {
    host: Arc<dyn ScriptHost>,
    limits: ScriptLimits,
}

impl RhaiScriptRuntime {
    /// Construct a runtime with default resource limits.
    pub fn new(host: Arc<dyn ScriptHost>) -> Self {
        Self {
            host,
            limits: ScriptLimits::default(),
        }
    }

    /// Construct a runtime with explicit limits.
    pub fn with_limits(host: Arc<dyn ScriptHost>, limits: ScriptLimits) -> Self {
        Self { host, limits }
    }

    /// Return the current execution limits.
    pub fn limits(&self) -> &ScriptLimits {
        &self.limits
    }

    /// Return all capabilities enforced by the runtime.
    pub fn list_capabilities(&self) -> Vec<ScriptCapability> {
        default_script_capabilities()
    }

    /// Return the plugin ABI definition used by future guest runtimes.
    pub fn plugin_host_wit(&self) -> &'static str {
        include_str!("host.wit")
    }

    /// Return advertised runtime availability for current and future guests.
    pub fn plugin_runtimes(&self) -> Vec<ScriptPluginRuntime> {
        vec![
            ScriptPluginRuntime {
                kind: ScriptRuntimeKind::Rhai,
                available: true,
                isolates_guest: false,
                notes: "Default embedded workflow runtime backed by the core capability host."
                    .to_string(),
            },
            ScriptPluginRuntime {
                kind: ScriptRuntimeKind::WasmComponent,
                available: runtime_is_available(&ScriptRuntimeKind::WasmComponent),
                isolates_guest: true,
                notes: runtime_runtime_notes(&ScriptRuntimeKind::WasmComponent).to_string(),
            },
            ScriptPluginRuntime {
                kind: ScriptRuntimeKind::Python,
                available: runtime_is_available(&ScriptRuntimeKind::Python),
                isolates_guest: true,
                notes: runtime_runtime_notes(&ScriptRuntimeKind::Python).to_string(),
            },
        ]
    }

    /// Validate a script and return the capabilities it requests.
    pub fn validate_script(
        &self,
        source: &str,
        manifest: Option<ScriptManifest>,
    ) -> Result<ScriptValidation, ScriptError> {
        let runtime = manifest
            .as_ref()
            .map(|value| value.runtime.clone())
            .unwrap_or_default();
        match runtime {
            ScriptRuntimeKind::Rhai => self.validate_rhai_source(source, manifest),
            ScriptRuntimeKind::WasmComponent | ScriptRuntimeKind::Python => {
                self.validate_guest_manifest(manifest, source.as_bytes().len())
            }
        }
    }

    /// Evaluate a script source string and return the display value.
    pub fn eval_script(
        &self,
        source: &str,
        manifest: Option<ScriptManifest>,
    ) -> Result<String, ScriptError> {
        let validation = self.validate_script(source, manifest)?;

        if validation.runtime == ScriptRuntimeKind::WasmComponent {
            #[cfg(feature = "scripting-wasm-component")]
            {
                // Wasm Component dispatch — inline source eval is not supported for
                // .wasm guests; they must be loaded from workspace files via
                // eval_workspace_script. Return an informative error.
                return Err(ScriptError::ValidationError {
                    detail: "Wasm component guests cannot be evaluated from inline source. \
                             Use eval_workspace_script with a .wasm file path instead."
                        .to_string(),
                });
            }
            #[cfg(not(feature = "scripting-wasm-component"))]
            {
                let detail = format!(
                    "WasmComponent runtime not available in this build. \
                     Enable with: features.add(\"scripting-wasm-component\") in \
                     lib/build.gradle.kts"
                );
                record_script_audit_event(
                    "script_run",
                    &validation_failure_audit(&validation, detail.clone()),
                );
                return Err(ScriptError::ValidationError { detail });
            }
        }

        if validation.runtime != ScriptRuntimeKind::Rhai {
            let detail = unavailable_runtime_execution_detail(&validation.runtime);
            record_script_audit_event(
                "script_run",
                &validation_failure_audit(&validation, detail.clone()),
            );
            return Err(ScriptError::ValidationError { detail });
        }

        let session = Arc::new(Mutex::new(ScriptExecutionSession::new(&validation)));
        let engine = self.build_engine(session.clone());
        let result = engine
            .eval::<Dynamic>(source)
            .map(dynamic_to_string)
            .map_err(|error| script_eval_error(error.to_string()));
        drop(engine);

        let audit = consume_audit_record(
            session,
            result.as_ref().err().map(std::string::ToString::to_string),
        )?;
        record_script_audit_event("script_run", &audit);
        result
    }

    /// Evaluate a workspace script by relative path.
    pub fn validate_workspace_script(
        &self,
        workspace_dir: &Path,
        relative_path: &Path,
        granted_capabilities: Option<Vec<String>>,
    ) -> Result<ScriptValidation, ScriptError> {
        let manifest =
            resolve_workspace_script_manifest(workspace_dir, relative_path, granted_capabilities)?;
        match manifest.runtime {
            ScriptRuntimeKind::Rhai => {
                use std::io::Read;
                let mut file = open_workspace_file(workspace_dir, relative_path)?;
                let mut source = String::new();
                file.read_to_string(&mut source).map_err(|error| ScriptError::InvalidArgument {
                    detail: format!(
                        "failed to read workspace script {}: {error}",
                        relative_path.display()
                    ),
                })?;
                self.validate_script(&source, Some(manifest))
            }
            ScriptRuntimeKind::WasmComponent | ScriptRuntimeKind::Python => {
                use std::io::Read;
                let mut file = open_workspace_file(workspace_dir, relative_path)?;
                let mut buf = Vec::new();
                file.read_to_end(&mut buf).map_err(|error| ScriptError::InvalidArgument {
                    detail: format!(
                        "failed to inspect workspace script {}: {error}",
                        relative_path.display()
                    ),
                })?;
                self.validate_guest_manifest(Some(manifest), buf.len())
            }
        }
    }

    /// Evaluate a workspace script by relative path.
    pub fn eval_workspace_script(
        &self,
        workspace_dir: &Path,
        relative_path: &Path,
        granted_capabilities: Option<Vec<String>>,
    ) -> Result<String, ScriptError> {
        let granted_capabilities_for_manifest = granted_capabilities.clone();
        let validation =
            self.validate_workspace_script(workspace_dir, relative_path, granted_capabilities)?;

        if validation.runtime == ScriptRuntimeKind::WasmComponent {
            #[cfg(feature = "scripting-wasm-component")]
            {
                // Wasm Component dispatch path — stub for now.
                // Future: load .wasm bytes, instantiate via wasmi/wasmtime,
                // bind host functions via PluginHost trait.
                {
                    use std::io::Read;
                    let mut file = open_workspace_file(workspace_dir, relative_path)?;
                    let mut wasm_bytes = Vec::new();
                    file.read_to_end(&mut wasm_bytes).map_err(|e| ScriptError::HostError {
                        operation: "wasm_load".to_string(),
                        detail: format!("Failed to read .wasm file: {e}"),
                    })?;
                    let _wasm_bytes = wasm_bytes;
                }
                return Err(ScriptError::ValidationError {
                    detail: "Wasm component loading verified, but host function binding is not \
                             yet complete. The .wasm file was found and readable."
                        .to_string(),
                });
            }
            #[cfg(not(feature = "scripting-wasm-component"))]
            {
                let detail = format!(
                    "WasmComponent runtime not available in this build. \
                     Enable with: features.add(\"scripting-wasm-component\") in \
                     lib/build.gradle.kts"
                );
                record_script_audit_event(
                    "script_run",
                    &validation_failure_audit(&validation, detail.clone()),
                );
                return Err(ScriptError::ValidationError { detail });
            }
        }

        if validation.runtime != ScriptRuntimeKind::Rhai {
            let detail = unavailable_runtime_execution_detail(&validation.runtime);
            record_script_audit_event(
                "script_run",
                &validation_failure_audit(&validation, detail.clone()),
            );
            return Err(ScriptError::ValidationError { detail });
        }

        let source = {
            use std::io::Read;
            let mut file = open_workspace_file(workspace_dir, relative_path)?;
            let mut buf = String::new();
            file.read_to_string(&mut buf).map_err(|error| ScriptError::InvalidArgument {
                detail: format!(
                    "failed to read workspace script {}: {error}",
                    relative_path.display()
                ),
            })?;
            buf
        };
        let manifest =
            resolve_workspace_script_manifest(
                workspace_dir,
                relative_path,
                granted_capabilities_for_manifest,
            )?;
        self.eval_script(&source, Some(manifest))
    }
}

/// Discover workspace and skill-packaged scripts.
pub fn discover_workspace_scripts(workspace_dir: &Path) -> Vec<ScriptManifest> {
    let mut manifests = Vec::new();
    collect_workflow_manifests(&workspace_dir.join("workflows"), workspace_dir, &mut manifests);

    for skill in crate::skills::load_skills(workspace_dir) {
        let Some(location) = skill.location.as_ref() else {
            continue;
        };
        let Some(skill_root) = location.parent() else {
            continue;
        };

        for script in &skill.scripts {
            let path = skill_root.join(&script.path);
            let runtime = script
                .runtime
                .as_deref()
                .and_then(parse_runtime_kind)
                .or_else(|| runtime_kind_from_path(&path))
                .unwrap_or_default();
            manifests.push(ScriptManifest {
                name: format!("{}::{}", skill.name, script.name),
                version: skill.version.clone(),
                description: script
                    .description
                    .clone()
                    .or_else(|| Some(skill.description.clone())),
                runtime,
                entrypoint: script.entrypoint.clone(),
                script_path: Some(path.strip_prefix(workspace_dir).unwrap_or(&path).to_path_buf()),
                capabilities: if script.capabilities.is_empty() {
                    skill.permissions.clone()
                } else {
                    script.capabilities.clone()
                },
                explicit_capabilities: true,
                triggers: if script.triggers.is_empty() {
                    skill.triggers.clone()
                } else {
                    script.triggers.clone()
                },
                limits: ScriptLimits::default(),
            });
        }
    }

    manifests.sort_by(|left, right| left.name.cmp(&right.name));
    manifests
}

fn collect_workflow_manifests(root: &Path, workspace_dir: &Path, output: &mut Vec<ScriptManifest>) {
    if !root.exists() {
        return;
    }

    let Ok(entries) = std::fs::read_dir(root) else {
        return;
    };

    for entry in entries.flatten() {
        let path = entry.path();
        if path.is_dir() {
            collect_workflow_manifests(&path, workspace_dir, output);
            continue;
        }

        let Some(runtime) = runtime_kind_from_path(&path) else {
            continue;
        };

        let relative = path
            .strip_prefix(workspace_dir)
            .unwrap_or(&path)
            .to_path_buf();
        output.push(ScriptManifest {
            name: relative.to_string_lossy().to_string(),
            script_path: Some(relative),
            runtime,
            ..Default::default()
        });
    }
}

fn runtime_kind_from_path(path: &Path) -> Option<ScriptRuntimeKind> {
    let extension = path.extension()?.to_string_lossy().to_ascii_lowercase();
    match extension.as_str() {
        "rhai" => Some(ScriptRuntimeKind::Rhai),
        "wasm" => Some(ScriptRuntimeKind::WasmComponent),
        "py" => Some(ScriptRuntimeKind::Python),
        _ => None,
    }
}

fn parse_runtime_kind(raw: &str) -> Option<ScriptRuntimeKind> {
    match raw.trim().to_ascii_lowercase().as_str() {
        "rhai" => Some(ScriptRuntimeKind::Rhai),
        "wasm" | "wasm_component" | "wasm-component" => Some(ScriptRuntimeKind::WasmComponent),
        "python" => Some(ScriptRuntimeKind::Python),
        _ => None,
    }
}

fn runtime_is_available(kind: &ScriptRuntimeKind) -> bool {
    match kind {
        ScriptRuntimeKind::Rhai => true,
        ScriptRuntimeKind::WasmComponent => cfg!(feature = "scripting-wasm-component"),
        ScriptRuntimeKind::Python => cfg!(feature = "scripting-python"),
    }
}

fn runtime_runtime_notes(kind: &ScriptRuntimeKind) -> &'static str {
    match kind {
        ScriptRuntimeKind::Rhai => {
            "Default embedded workflow runtime backed by the core capability host."
        }
        ScriptRuntimeKind::WasmComponent => {
            if runtime_is_available(kind) {
                "Feature-gated Wasm guest runtime is compiled in; host ABI plumbing is ready for component guest execution."
            } else {
                "Host ABI and manifest plumbing are ready; enable the `scripting-wasm-component` feature to continue wiring the guest runtime."
            }
        }
        ScriptRuntimeKind::Python => {
            if runtime_is_available(kind) {
                "Optional Python guest runtime feature is compiled in behind the stable plugin ABI."
            } else {
                "Reserved optional guest runtime behind the stable plugin ABI only."
            }
        }
    }
}

fn unavailable_runtime_execution_detail(kind: &ScriptRuntimeKind) -> String {
    match kind {
        ScriptRuntimeKind::Rhai => "rhai execution is always available".to_string(),
        ScriptRuntimeKind::WasmComponent => {
            if runtime_is_available(kind) {
                "runtime 'wasm-component' is compiled in but the guest execution path is not yet wired through the stable host ABI.".to_string()
            } else {
                "runtime 'wasm-component' is not enabled in this build; enable the `scripting-wasm-component` feature before executing guest plugins.".to_string()
            }
        }
        ScriptRuntimeKind::Python => {
            if runtime_is_available(kind) {
                "runtime 'python' is compiled in but remains reserved behind the stable plugin ABI until the guest host bridge is completed.".to_string()
            } else {
                "runtime 'python' is not enabled in this build; optional polyglot guests remain feature-gated behind the stable plugin ABI.".to_string()
            }
        }
    }
}

const CAPABILITY_DENIED_SENTINEL: &str = "__zero_capability_denied__";

/// Validates a workspace-relative path by attempting to open it via cap-std.
///
/// The `open_workspace_file` call (which uses `cap_std::fs::Dir`) structurally
/// prevents symlink escapes. If the open succeeds, the path is valid and inside
/// the workspace. The returned `PathBuf` is the simple join (not canonicalized).
fn validate_workspace_relative_path(
    workspace_dir: &Path,
    relative_path: &Path,
) -> Result<PathBuf, ScriptError> {
    let _file = open_workspace_file(workspace_dir, relative_path)?;
    Ok(workspace_dir.join(relative_path))
}

fn open_workspace_file(
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

fn resolve_workspace_script_manifest(
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

fn default_script_version() -> String {
    "0.1.0".to_string()
}

fn validation_failure_audit(validation: &ScriptValidation, detail: String) -> ScriptAuditRecord {
    ScriptAuditRecord {
        script_name: validation.manifest_name.clone(),
        runtime: validation.runtime.clone(),
        success: false,
        requested_capabilities: validation.requested_capabilities.clone(),
        attempted_capabilities: Vec::new(),
        used_capabilities: Vec::new(),
        missing_capabilities: validation.missing_capabilities.clone(),
        warnings: validation.warnings.clone(),
        error: Some(detail),
        duration_ms: 0,
    }
}

fn consume_audit_record(
    session: Arc<Mutex<ScriptExecutionSession>>,
    error: Option<String>,
) -> Result<ScriptAuditRecord, ScriptError> {
    let mutex = Arc::into_inner(session).ok_or_else(|| ScriptError::InternalState {
        detail: "script execution session still had outstanding references".to_string(),
    })?;
    let session = mutex.into_inner().map_err(|_| ScriptError::InternalState {
        detail: "script execution session mutex poisoned".to_string(),
    })?;
    Ok(session.audit_record(error.is_none(), error))
}

fn record_script_audit_event(event_type: &str, record: &ScriptAuditRecord) {
    runtime_trace::record_event(
        event_type,
        None,
        None,
        Some(match record.runtime {
            ScriptRuntimeKind::Rhai => "rhai",
            ScriptRuntimeKind::WasmComponent => "wasm-component",
            ScriptRuntimeKind::Python => "python",
        }),
        Some(&record.script_name),
        Some(record.success),
        record.error.as_deref(),
        json!(record),
    );
}

fn push_unique(values: &mut Vec<String>, value: &str) {
    if !values.iter().any(|existing| existing == value) {
        values.push(value.to_string());
    }
}

fn script_eval_error(detail: String) -> ScriptError {
    if let Some(encoded) = detail
        .split(CAPABILITY_DENIED_SENTINEL)
        .nth(1)
        .map(str::trim)
    {
        let encoded = encoded.trim_start_matches([':', '|']);
        let mut parts = encoded.splitn(3, '|');
        if let (Some(operation), Some(capability)) = (parts.next(), parts.next()) {
            return ScriptError::CapabilityDenied {
                operation: operation.to_string(),
                capability: capability.to_string(),
            };
        }
    }
    if detail.contains("capability denied") {
        ScriptError::ValidationError { detail }
    } else {
        ScriptError::HostError {
            operation: "script.eval".to_string(),
            detail,
        }
    }
}

fn dynamic_to_string(value: Dynamic) -> String {
    if value.is_unit() {
        return "ok".to_string();
    }
    if let Ok(string) = value.clone().into_string() {
        return string;
    }
    value.to_string()
}

fn default_script_capabilities() -> Vec<ScriptCapability> {
    vec![
        ScriptCapability {
            name: "agent.read".to_string(),
            description: "Read runtime status, health, config, and diagnostic data.".to_string(),
            scope: None,
        },
        ScriptCapability {
            name: "agent.control".to_string(),
            description: "Control emergency-stop and other lifecycle-sensitive host actions."
                .to_string(),
            scope: None,
        },
        ScriptCapability {
            name: "auth.read".to_string(),
            description: "Inspect available provider auth-profile metadata.".to_string(),
            scope: None,
        },
        ScriptCapability {
            name: "auth.write".to_string(),
            description: "Remove provider auth-profile metadata.".to_string(),
            scope: None,
        },
        ScriptCapability {
            name: "channel.read".to_string(),
            description: "Inspect bound channel identities and allowlists.".to_string(),
            scope: None,
        },
        ScriptCapability {
            name: "channel.write".to_string(),
            description: "Mutate channel bindings and allowlists.".to_string(),
            scope: None,
        },
        ScriptCapability {
            name: "config.validate".to_string(),
            description: "Validate agent configuration text.".to_string(),
            scope: None,
        },
        ScriptCapability {
            name: "cost.read".to_string(),
            description: "Inspect budget and cost-tracking information.".to_string(),
            scope: None,
        },
        ScriptCapability {
            name: "cron.read".to_string(),
            description: "Inspect scheduled automation jobs.".to_string(),
            scope: None,
        },
        ScriptCapability {
            name: "cron.write".to_string(),
            description: "Create, pause, resume, or remove scheduled automation jobs.".to_string(),
            scope: None,
        },
        ScriptCapability {
            name: "events.read".to_string(),
            description: "Read recent runtime and channel events.".to_string(),
            scope: None,
        },
        ScriptCapability {
            name: "fs.none".to_string(),
            description: "Default deny: no direct filesystem access is exposed to scripts."
                .to_string(),
            scope: None,
        },
        ScriptCapability {
            name: "memory.read".to_string(),
            description: "Recall or list memory entries.".to_string(),
            scope: None,
        },
        ScriptCapability {
            name: "memory.write".to_string(),
            description: "Delete or mutate stored memory entries.".to_string(),
            scope: None,
        },
        ScriptCapability {
            name: "model.chat".to_string(),
            description: "Send model-backed messages through the agent.".to_string(),
            scope: None,
        },
        ScriptCapability {
            name: "model.read".to_string(),
            description: "Inspect model discovery metadata.".to_string(),
            scope: None,
        },
        ScriptCapability {
            name: "net.none".to_string(),
            description: "Default deny: scripts do not receive raw network access.".to_string(),
            scope: None,
        },
        ScriptCapability {
            name: "provider.write".to_string(),
            description: "Change active provider routing choices.".to_string(),
            scope: None,
        },
        ScriptCapability {
            name: "shell.none".to_string(),
            description: "Default deny: scripts do not receive raw shell access.".to_string(),
            scope: None,
        },
        ScriptCapability {
            name: "skills.read".to_string(),
            description: "Inspect installed skill metadata.".to_string(),
            scope: None,
        },
        ScriptCapability {
            name: "skills.write".to_string(),
            description: "Install or remove skills.".to_string(),
            scope: None,
        },
        ScriptCapability {
            name: "tools.read".to_string(),
            description: "Inspect the currently available tool registry.".to_string(),
            scope: None,
        },
        ScriptCapability {
            name: "tools.call".to_string(),
            description: "Invoke a registered tool by name".to_string(),
            scope: Some("tool-name".to_string()),
        },
        ScriptCapability {
            name: "storage.read".to_string(),
            description: "Read script-scoped persistent storage".to_string(),
            scope: None,
        },
        ScriptCapability {
            name: "storage.write".to_string(),
            description: "Write to script-scoped persistent storage".to_string(),
            scope: None,
        },
        ScriptCapability {
            name: "trace.read".to_string(),
            description: "Inspect runtime trace history.".to_string(),
            scope: None,
        },
        ScriptCapability {
            name: "tool.call:<tool-name>".to_string(),
            description: "Reserved capability shape for stable plugin host tool invocation."
                .to_string(),
            scope: Some("<tool-name>".to_string()),
        },
        ScriptCapability {
            name: "fs.read:<path-scope>".to_string(),
            description: "Reserved capability shape for future path-scoped file reads."
                .to_string(),
            scope: Some("<path-scope>".to_string()),
        },
        ScriptCapability {
            name: "fs.write:<path-scope>".to_string(),
            description: "Reserved capability shape for future path-scoped file writes."
                .to_string(),
            scope: Some("<path-scope>".to_string()),
        },
    ]
}

fn validation_available_capability_names() -> Vec<String> {
    default_script_capabilities()
        .into_iter()
        .map(|capability| capability.name)
        .collect()
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

impl RhaiScriptRuntime {
    fn validate_guest_manifest(
        &self,
        manifest: Option<ScriptManifest>,
        source_len: usize,
    ) -> Result<ScriptValidation, ScriptError> {
        if source_len > self.limits.max_script_bytes {
            return Err(ScriptError::ValidationError {
                detail: format!(
                    "script exceeds the {} byte safety limit",
                    self.limits.max_script_bytes
                ),
            });
        }

        let normalised_manifest = normalize_manifest(manifest, Vec::new());
        let mut warnings = Vec::new();
        if normalised_manifest.capabilities.is_empty() {
            warnings.push(
                "Guest script does not currently request any host capabilities.".to_string(),
            );
        }
        warnings.push(runtime_runtime_notes(&normalised_manifest.runtime).to_string());
        warnings.push(unavailable_runtime_execution_detail(&normalised_manifest.runtime));

        let validation = ScriptValidation {
            manifest_name: normalised_manifest.name,
            runtime: normalised_manifest.runtime,
            requested_capabilities: normalised_manifest.capabilities,
            missing_capabilities: Vec::new(),
            warnings,
            available_capabilities: validation_available_capability_names(),
        };
        let audit = ScriptAuditRecord {
            script_name: validation.manifest_name.clone(),
            runtime: validation.runtime.clone(),
            success: true,
            requested_capabilities: validation.requested_capabilities.clone(),
            attempted_capabilities: Vec::new(),
            used_capabilities: Vec::new(),
            missing_capabilities: Vec::new(),
            warnings: validation.warnings.clone(),
            error: None,
            duration_ms: 0,
        };
        record_script_audit_event("script_validation", &audit);
        Ok(validation)
    }

    fn validate_rhai_source(
        &self,
        source: &str,
        manifest: Option<ScriptManifest>,
    ) -> Result<ScriptValidation, ScriptError> {
        let had_explicit_capabilities = manifest
            .as_ref()
            .is_some_and(|value| value.explicit_capabilities || !value.capabilities.is_empty());
        if source.as_bytes().len() > self.limits.max_script_bytes {
            return Err(ScriptError::ValidationError {
                detail: format!(
                    "script exceeds the {} byte safety limit",
                    self.limits.max_script_bytes
                ),
            });
        }

        let mut engine = Engine::new_raw();
        engine.register_global_module(CorePackage::new().as_shared_module());
        engine
            .compile(source)
            .map_err(|error| ScriptError::ValidationError {
                detail: error.to_string(),
            })?;

        let inferred = infer_capabilities(source);
        let normalised_manifest = normalize_manifest(manifest, inferred.clone());
        let requested = normalised_manifest.capabilities.clone();

        let requested_set: BTreeSet<String> = requested.iter().cloned().collect();
        let missing: Vec<String> = inferred
            .iter()
            .filter(|capability| !requested_set.contains(*capability))
            .cloned()
            .collect();
        let mut warnings = Vec::new();
        if !had_explicit_capabilities && !requested.is_empty() {
            warnings.push(
                "No explicit manifest capabilities were supplied; using source-inferred permissions for this execution.".to_string(),
            );
        }
        if !had_explicit_capabilities && requested.is_empty() {
            warnings.push("Script does not currently request any host capabilities.".to_string());
        }
        if !missing.is_empty() {
            warnings.push(
                "The explicit manifest omits one or more capabilities inferred from the source; execution will deny those calls.".to_string(),
            );
        }

        let validation = ScriptValidation {
            manifest_name: normalised_manifest.name,
            runtime: normalised_manifest.runtime,
            requested_capabilities: requested,
            missing_capabilities: missing,
            warnings,
            available_capabilities: validation_available_capability_names(),
        };
        let audit = ScriptAuditRecord {
            script_name: validation.manifest_name.clone(),
            runtime: validation.runtime.clone(),
            success: true,
            requested_capabilities: validation.requested_capabilities.clone(),
            attempted_capabilities: Vec::new(),
            used_capabilities: Vec::new(),
            missing_capabilities: validation.missing_capabilities.clone(),
            warnings: validation.warnings.clone(),
            error: None,
            duration_ms: 0,
        };
        record_script_audit_event("script_validation", &audit);
        Ok(validation)
    }

    fn build_engine(&self, session: Arc<Mutex<ScriptExecutionSession>>) -> Engine {
        let mut engine = Engine::new_raw();
        engine.register_global_module(CorePackage::new().as_shared_module());
        engine.set_max_operations(self.limits.max_operations);
        engine.set_max_expr_depths(self.limits.max_expr_depth, self.limits.max_expr_depth / 2);
        engine.set_max_string_size(self.limits.max_string_size);
        engine.set_max_array_size(self.limits.max_array_size);
        engine.set_max_map_size(self.limits.max_map_size);
        engine.set_max_call_levels(self.limits.max_call_levels);

        // Wall-clock timeout: terminate scripts after 30 seconds regardless
        // of operation count. A new engine is built per eval call, so
        // Instant::now() correctly captures the per-invocation start time.
        let deadline = Instant::now() + std::time::Duration::from_secs(30);
        engine.on_progress(move |_ops: u64| {
            if Instant::now() >= deadline {
                Some(Dynamic::from("script execution timed out (30s limit)"))
            } else {
                None
            }
        });

        self.register_flat_aliases(&mut engine, session.clone());
        self.register_namespaced_modules(&mut engine, session);
        engine
    }

    fn register_flat_aliases(
        &self,
        engine: &mut Engine,
        session: Arc<Mutex<ScriptExecutionSession>>,
    ) {
        let host = self.host.clone();
        let session_clone = session.clone();
        engine.register_fn("status", move || -> Result<String, Box<EvalAltResult>> {
            call_string(&host, &session_clone, ScriptOperation::Status, serde_json::Value::Null)
        });

        let host = self.host.clone();
        let session_clone = session.clone();
        engine.register_fn("version", move || -> Result<String, Box<EvalAltResult>> {
            call_string(&host, &session_clone, ScriptOperation::Version, serde_json::Value::Null)
        });

        let host = self.host.clone();
        let session_clone = session.clone();
        engine.register_fn(
            "send",
            move |message: String| -> Result<String, Box<EvalAltResult>> {
                call_string(
                    &host,
                    &session_clone,
                    ScriptOperation::SendMessage,
                    json!({ "message": message }),
                )
            },
        );

        let host = self.host.clone();
        let session_clone = session.clone();
        engine.register_fn(
            "send_vision",
            move |text: String, images: Array, mime_types: Array| -> Result<String, Box<EvalAltResult>> {
                call_string(
                    &host,
                    &session_clone,
                    ScriptOperation::SendVision,
                    json!({
                        "text": text,
                        "images": array_to_strings(images),
                        "mime_types": array_to_strings(mime_types),
                    }),
                )
            },
        );

        let host = self.host.clone();
        let session_clone = session.clone();
        engine.register_fn(
            "validate_config",
            move |config_toml: String| -> Result<String, Box<EvalAltResult>> {
                call_string(
                    &host,
                    &session_clone,
                    ScriptOperation::ValidateConfig,
                    json!({ "config_toml": config_toml }),
                )
            },
        );

        let host = self.host.clone();
        let session_clone = session.clone();
        engine.register_fn("config", move || -> Result<String, Box<EvalAltResult>> {
            call_string(
                &host,
                &session_clone,
                ScriptOperation::RunningConfig,
                serde_json::Value::Null,
            )
        });

        let host = self.host.clone();
        let session_clone = session.clone();
        engine.register_fn(
            "bind",
            move |channel: String, user_id: String| -> Result<String, Box<EvalAltResult>> {
                call_string(
                    &host,
                    &session_clone,
                    ScriptOperation::BindChannelIdentity,
                    json!({ "channel": channel, "user_id": user_id }),
                )
            },
        );

        let host = self.host.clone();
        let session_clone = session.clone();
        engine.register_fn(
            "allowlist",
            move |channel: String| -> Result<String, Box<EvalAltResult>> {
                call_string(
                    &host,
                    &session_clone,
                    ScriptOperation::ChannelAllowlist,
                    json!({ "channel": channel }),
                )
            },
        );

        let host = self.host.clone();
        let session_clone = session.clone();
        engine.register_fn(
            "swap_provider",
            move |provider: String, model: String| -> Result<String, Box<EvalAltResult>> {
                call_string(
                    &host,
                    &session_clone,
                    ScriptOperation::SwapProvider,
                    json!({ "provider": provider, "model": model }),
                )
            },
        );

        let host = self.host.clone();
        let session_clone = session.clone();
        engine.register_fn("health", move || -> Result<String, Box<EvalAltResult>> {
            call_string(
                &host,
                &session_clone,
                ScriptOperation::HealthDetail,
                serde_json::Value::Null,
            )
        });

        let host = self.host.clone();
        let session_clone = session.clone();
        engine.register_fn(
            "health_component",
            move |name: String| -> Result<String, Box<EvalAltResult>> {
                call_string(
                    &host,
                    &session_clone,
                    ScriptOperation::HealthComponent,
                    json!({ "name": name }),
                )
            },
        );

        let host = self.host.clone();
        let session_clone = session.clone();
        engine.register_fn("doctor", move || -> Result<String, Box<EvalAltResult>> {
            call_string(
                &host,
                &session_clone,
                ScriptOperation::DoctorChannels,
                json!({ "config_toml": "", "data_dir": "" }),
            )
        });

        let host = self.host.clone();
        let session_clone = session.clone();
        engine.register_fn(
            "doctor",
            move |config_toml: String, data_dir: String| -> Result<String, Box<EvalAltResult>> {
                call_string(
                    &host,
                    &session_clone,
                    ScriptOperation::DoctorChannels,
                    json!({ "config_toml": config_toml, "data_dir": data_dir }),
                )
            },
        );

        let host = self.host.clone();
        let session_clone = session.clone();
        engine.register_fn("cost", move || -> Result<String, Box<EvalAltResult>> {
            call_string(
                &host,
                &session_clone,
                ScriptOperation::CostSummary,
                serde_json::Value::Null,
            )
        });

        let host = self.host.clone();
        let session_clone = session.clone();
        engine.register_fn("cost_daily", move || -> Result<Dynamic, Box<EvalAltResult>> {
            let today = chrono::Utc::now().date_naive();
            call_float(
                &host,
                &session_clone,
                ScriptOperation::DailyCost,
                json!({
                    "year": Datelike::year(&today),
                    "month": Datelike::month(&today),
                    "day": Datelike::day(&today),
                }),
            )
        });

        let host = self.host.clone();
        let session_clone = session.clone();
        engine.register_fn(
            "cost_daily",
            move |year: i64, month: i64, day: i64| -> Result<Dynamic, Box<EvalAltResult>> {
                call_float(
                    &host,
                    &session_clone,
                    ScriptOperation::DailyCost,
                    json!({ "year": year, "month": month, "day": day }),
                )
            },
        );

        let host = self.host.clone();
        let session_clone = session.clone();
        engine.register_fn("cost_monthly", move || -> Result<Dynamic, Box<EvalAltResult>> {
            let now = chrono::Utc::now();
            call_float(
                &host,
                &session_clone,
                ScriptOperation::MonthlyCost,
                json!({
                    "year": Datelike::year(&now),
                    "month": Datelike::month(&now),
                }),
            )
        });

        let host = self.host.clone();
        let session_clone = session.clone();
        engine.register_fn(
            "cost_monthly",
            move |year: i64, month: i64| -> Result<Dynamic, Box<EvalAltResult>> {
                call_float(
                    &host,
                    &session_clone,
                    ScriptOperation::MonthlyCost,
                    json!({ "year": year, "month": month }),
                )
            },
        );

        let host = self.host.clone();
        let session_clone = session.clone();
        engine.register_fn(
            "budget",
            move |estimated: f64| -> Result<String, Box<EvalAltResult>> {
                call_string(
                    &host,
                    &session_clone,
                    ScriptOperation::CheckBudget,
                    json!({ "estimated": estimated }),
                )
            },
        );

        let host = self.host.clone();
        let session_clone = session.clone();
        engine.register_fn(
            "events",
            move |limit: i64| -> Result<String, Box<EvalAltResult>> {
                call_string(
                    &host,
                    &session_clone,
                    ScriptOperation::RecentEvents,
                    json!({ "limit": limit }),
                )
            },
        );

        let host = self.host.clone();
        let session_clone = session.clone();
        engine.register_fn("cron_list", move || -> Result<String, Box<EvalAltResult>> {
            call_string(
                &host,
                &session_clone,
                ScriptOperation::ListCronJobs,
                serde_json::Value::Null,
            )
        });

        let host = self.host.clone();
        let session_clone = session.clone();
        engine.register_fn(
            "cron_get",
            move |id: String| -> Result<String, Box<EvalAltResult>> {
                call_string(
                    &host,
                    &session_clone,
                    ScriptOperation::GetCronJob,
                    json!({ "id": id }),
                )
            },
        );

        let host = self.host.clone();
        let session_clone = session.clone();
        engine.register_fn(
            "cron_add",
            move |expression: String, command: String| -> Result<String, Box<EvalAltResult>> {
                call_string(
                    &host,
                    &session_clone,
                    ScriptOperation::AddCronJob,
                    json!({ "expression": expression, "command": command }),
                )
            },
        );

        let host = self.host.clone();
        let session_clone = session.clone();
        engine.register_fn(
            "cron_oneshot",
            move |delay: String, command: String| -> Result<String, Box<EvalAltResult>> {
                call_string(
                    &host,
                    &session_clone,
                    ScriptOperation::AddOneShotJob,
                    json!({ "delay": delay, "command": command }),
                )
            },
        );

        let host = self.host.clone();
        let session_clone = session.clone();
        engine.register_fn(
            "cron_add_at",
            move |timestamp: String, command: String| -> Result<String, Box<EvalAltResult>> {
                call_string(
                    &host,
                    &session_clone,
                    ScriptOperation::AddCronJobAt,
                    json!({ "timestamp": timestamp, "command": command }),
                )
            },
        );

        let host = self.host.clone();
        let session_clone = session.clone();
        engine.register_fn(
            "cron_add_every",
            move |every_ms: i64, command: String| -> Result<String, Box<EvalAltResult>> {
                call_string(
                    &host,
                    &session_clone,
                    ScriptOperation::AddCronJobEvery,
                    json!({ "every_ms": every_ms, "command": command }),
                )
            },
        );

        let host = self.host.clone();
        let session_clone = session.clone();
        engine.register_fn(
            "cron_remove",
            move |id: String| -> Result<String, Box<EvalAltResult>> {
                call_string(
                    &host,
                    &session_clone,
                    ScriptOperation::RemoveCronJob,
                    json!({ "id": id }),
                )
            },
        );

        let host = self.host.clone();
        let session_clone = session.clone();
        engine.register_fn(
            "cron_pause",
            move |id: String| -> Result<String, Box<EvalAltResult>> {
                call_string(
                    &host,
                    &session_clone,
                    ScriptOperation::PauseCronJob,
                    json!({ "id": id }),
                )
            },
        );

        let host = self.host.clone();
        let session_clone = session.clone();
        engine.register_fn(
            "cron_resume",
            move |id: String| -> Result<String, Box<EvalAltResult>> {
                call_string(
                    &host,
                    &session_clone,
                    ScriptOperation::ResumeCronJob,
                    json!({ "id": id }),
                )
            },
        );

        let host = self.host.clone();
        let session_clone = session.clone();
        engine.register_fn("skills", move || -> Result<String, Box<EvalAltResult>> {
            call_string(
                &host,
                &session_clone,
                ScriptOperation::ListSkills,
                serde_json::Value::Null,
            )
        });

        let host = self.host.clone();
        let session_clone = session.clone();
        engine.register_fn(
            "skill_tools",
            move |name: String| -> Result<String, Box<EvalAltResult>> {
                call_string(
                    &host,
                    &session_clone,
                    ScriptOperation::GetSkillTools,
                    json!({ "name": name }),
                )
            },
        );

        let host = self.host.clone();
        let session_clone = session.clone();
        engine.register_fn(
            "skill_install",
            move |source: String| -> Result<String, Box<EvalAltResult>> {
                call_string(
                    &host,
                    &session_clone,
                    ScriptOperation::InstallSkill,
                    json!({ "source": source }),
                )
            },
        );

        let host = self.host.clone();
        let session_clone = session.clone();
        engine.register_fn(
            "skill_remove",
            move |name: String| -> Result<String, Box<EvalAltResult>> {
                call_string(
                    &host,
                    &session_clone,
                    ScriptOperation::RemoveSkill,
                    json!({ "name": name }),
                )
            },
        );

        let host = self.host.clone();
        let session_clone = session.clone();
        engine.register_fn("tools", move || -> Result<String, Box<EvalAltResult>> {
            call_string(
                &host,
                &session_clone,
                ScriptOperation::ListTools,
                serde_json::Value::Null,
            )
        });

        let host = self.host.clone();
        let session_clone = session.clone();
        engine.register_fn(
            "memories",
            move |limit: i64| -> Result<String, Box<EvalAltResult>> {
                call_string(
                    &host,
                    &session_clone,
                    ScriptOperation::ListMemories,
                    json!({ "limit": limit }),
                )
            },
        );

        let host = self.host.clone();
        let session_clone = session.clone();
        engine.register_fn(
            "memories_by_category",
            move |category: String, limit: i64| -> Result<String, Box<EvalAltResult>> {
                call_string(
                    &host,
                    &session_clone,
                    ScriptOperation::ListMemoriesByCategory,
                    json!({ "category": category, "limit": limit }),
                )
            },
        );

        let host = self.host.clone();
        let session_clone = session.clone();
        engine.register_fn(
            "memory_recall",
            move |query: String, limit: i64| -> Result<String, Box<EvalAltResult>> {
                call_string(
                    &host,
                    &session_clone,
                    ScriptOperation::RecallMemory,
                    json!({ "query": query, "limit": limit }),
                )
            },
        );

        let host = self.host.clone();
        let session_clone = session.clone();
        engine.register_fn(
            "memory_forget",
            move |key: String| -> Result<bool, Box<EvalAltResult>> {
                call_bool(
                    &host,
                    &session_clone,
                    ScriptOperation::ForgetMemory,
                    json!({ "key": key }),
                )
            },
        );

        let host = self.host.clone();
        let session_clone = session.clone();
        engine.register_fn("memory_count", move || -> Result<i64, Box<EvalAltResult>> {
            call_int(
                &host,
                &session_clone,
                ScriptOperation::MemoryCount,
                serde_json::Value::Null,
            )
        });

        let host = self.host.clone();
        let session_clone = session.clone();
        engine.register_fn("estop", move || -> Result<String, Box<EvalAltResult>> {
            call_string(
                &host,
                &session_clone,
                ScriptOperation::EngageEStop,
                serde_json::Value::Null,
            )
        });

        let host = self.host.clone();
        let session_clone = session.clone();
        engine.register_fn("estop_status", move || -> Result<String, Box<EvalAltResult>> {
            call_string(
                &host,
                &session_clone,
                ScriptOperation::GetEStopStatus,
                serde_json::Value::Null,
            )
        });

        let host = self.host.clone();
        let session_clone = session.clone();
        engine.register_fn("estop_resume", move || -> Result<String, Box<EvalAltResult>> {
            call_string(
                &host,
                &session_clone,
                ScriptOperation::ResumeEStop,
                serde_json::Value::Null,
            )
        });

        let host = self.host.clone();
        let session_clone = session.clone();
        engine.register_fn(
            "traces",
            move |limit: i64| -> Result<String, Box<EvalAltResult>> {
                call_string(
                    &host,
                    &session_clone,
                    ScriptOperation::QueryTraces,
                    json!({ "limit": limit }),
                )
            },
        );

        let host = self.host.clone();
        let session_clone = session.clone();
        engine.register_fn(
            "traces_filter",
            move |filter: String, limit: i64| -> Result<String, Box<EvalAltResult>> {
                call_string(
                    &host,
                    &session_clone,
                    ScriptOperation::QueryTracesByFilter,
                    json!({ "filter": filter, "limit": limit }),
                )
            },
        );

        let host = self.host.clone();
        let session_clone = session.clone();
        engine.register_fn("auth_list", move || -> Result<String, Box<EvalAltResult>> {
            call_string(
                &host,
                &session_clone,
                ScriptOperation::ListAuthProfiles,
                serde_json::Value::Null,
            )
        });

        let host = self.host.clone();
        let session_clone = session.clone();
        engine.register_fn(
            "auth_remove",
            move |provider: String, profile_name: String| -> Result<String, Box<EvalAltResult>> {
                call_string(
                    &host,
                    &session_clone,
                    ScriptOperation::RemoveAuthProfile,
                    json!({ "provider": provider, "profile_name": profile_name }),
                )
            },
        );

        let host = self.host.clone();
        let session_clone = session.clone();
        engine.register_fn(
            "models",
            move |provider: String| -> Result<String, Box<EvalAltResult>> {
                call_string(
                    &host,
                    &session_clone,
                    ScriptOperation::DiscoverModels,
                    json!({ "provider": provider }),
                )
            },
        );

        engine.register_fn(
            "models_with_key",
            move |_provider: String, _api_key: String| -> Result<String, Box<EvalAltResult>> {
                Err("models_with_key is restricted; use models(provider) instead".into())
            },
        );

        let host = self.host.clone();
        let session_clone = session.clone();
        engine.register_fn(
            "models_full",
            move |provider: String,
                  api_key: String,
                  base_url: String|
                  -> Result<String, Box<EvalAltResult>> {
                if !base_url.is_empty() {
                    is_safe_url(&base_url)
                        .map_err(|error| -> Box<EvalAltResult> { error.to_string().into() })?;
                }
                call_string(
                    &host,
                    &session_clone,
                    ScriptOperation::DiscoverModelsWithKeyAndBaseUrl,
                    json!({ "provider": provider, "api_key": api_key, "base_url": base_url }),
                )
            },
        );

        let host = self.host.clone();
        let session_clone = session.clone();
        engine.register_fn(
            "tool_call",
            move |name: String, args: String| -> Result<String, Box<EvalAltResult>> {
                call_string(
                    &host,
                    &session_clone,
                    ScriptOperation::InvokeTool,
                    json!({ "name": name, "args": args }),
                )
            },
        );

        let host = self.host.clone();
        let session_clone = session.clone();
        engine.register_fn(
            "storage_read",
            move |key: String| -> Result<String, Box<EvalAltResult>> {
                let script_name = session_clone
                    .lock()
                    .map(|s| s.manifest_name.clone())
                    .unwrap_or_else(|_| "anonymous".to_string());
                call_string(
                    &host,
                    &session_clone,
                    ScriptOperation::ReadStorage,
                    json!({ "key": key, "script": script_name }),
                )
            },
        );

        let host = self.host.clone();
        let session_clone = session.clone();
        engine.register_fn(
            "storage_write",
            move |key: String, value: String| -> Result<String, Box<EvalAltResult>> {
                let script_name = session_clone
                    .lock()
                    .map(|s| s.manifest_name.clone())
                    .unwrap_or_else(|_| "anonymous".to_string());
                call_string(
                    &host,
                    &session_clone,
                    ScriptOperation::WriteStorage,
                    json!({ "key": key, "value": value, "script": script_name }),
                )
            },
        );

        let host = self.host.clone();
        let session_clone = session.clone();
        engine.register_fn(
            "storage_delete",
            move |key: String| -> Result<bool, Box<EvalAltResult>> {
                let script_name = session_clone
                    .lock()
                    .map(|s| s.manifest_name.clone())
                    .unwrap_or_else(|_| "anonymous".to_string());
                call_bool(
                    &host,
                    &session_clone,
                    ScriptOperation::DeleteStorage,
                    json!({ "key": key, "script": script_name }),
                )
            },
        );
    }

    fn register_namespaced_modules(
        &self,
        engine: &mut Engine,
        session: Arc<Mutex<ScriptExecutionSession>>,
    ) {
        let mut agent_module = Module::new();
        let host = self.host.clone();
        let session_clone = session.clone();
        agent_module.set_native_fn("status", move || -> Result<String, Box<EvalAltResult>> {
            call_string(&host, &session_clone, ScriptOperation::Status, serde_json::Value::Null)
        });

        let host = self.host.clone();
        let session_clone = session.clone();
        agent_module.set_native_fn("version", move || -> Result<String, Box<EvalAltResult>> {
            call_string(
                &host,
                &session_clone,
                ScriptOperation::Version,
                serde_json::Value::Null,
            )
        });

        let host = self.host.clone();
        let session_clone = session.clone();
        agent_module.set_native_fn(
            "send",
            move |message: String| -> Result<String, Box<EvalAltResult>> {
                call_string(
                    &host,
                    &session_clone,
                    ScriptOperation::SendMessage,
                    json!({ "message": message }),
                )
            },
        );

        let host = self.host.clone();
        let session_clone = session.clone();
        agent_module.set_native_fn(
            "send_vision",
            move |text: String, images: Array, mime_types: Array| -> Result<String, Box<EvalAltResult>> {
                call_string(
                    &host,
                    &session_clone,
                    ScriptOperation::SendVision,
                    json!({
                        "text": text,
                        "images": array_to_strings(images),
                        "mime_types": array_to_strings(mime_types),
                    }),
                )
            },
        );

        let host = self.host.clone();
        let session_clone = session.clone();
        agent_module.set_native_fn(
            "validate_config",
            move |config_toml: String| -> Result<String, Box<EvalAltResult>> {
                call_string(
                    &host,
                    &session_clone,
                    ScriptOperation::ValidateConfig,
                    json!({ "config_toml": config_toml }),
                )
            },
        );

        let host = self.host.clone();
        let session_clone = session.clone();
        agent_module.set_native_fn("config", move || -> Result<String, Box<EvalAltResult>> {
            call_string(
                &host,
                &session_clone,
                ScriptOperation::RunningConfig,
                serde_json::Value::Null,
            )
        });

        let host = self.host.clone();
        let session_clone = session.clone();
        agent_module.set_native_fn("health", move || -> Result<String, Box<EvalAltResult>> {
            call_string(
                &host,
                &session_clone,
                ScriptOperation::HealthDetail,
                serde_json::Value::Null,
            )
        });

        let host = self.host.clone();
        let session_clone = session.clone();
        agent_module.set_native_fn(
            "health_component",
            move |name: String| -> Result<String, Box<EvalAltResult>> {
                call_string(
                    &host,
                    &session_clone,
                    ScriptOperation::HealthComponent,
                    json!({ "name": name }),
                )
            },
        );

        let host = self.host.clone();
        let session_clone = session.clone();
        agent_module.set_native_fn("doctor", move || -> Result<String, Box<EvalAltResult>> {
            call_string(
                &host,
                &session_clone,
                ScriptOperation::DoctorChannels,
                json!({ "config_toml": "", "data_dir": "" }),
            )
        });

        engine.register_static_module("agent", agent_module.into());

        let mut tools_module = Module::new();
        let host = self.host.clone();
        let session_clone = session.clone();
        tools_module.set_native_fn("list", move || -> Result<String, Box<EvalAltResult>> {
            call_string(
                &host,
                &session_clone,
                ScriptOperation::ListTools,
                serde_json::Value::Null,
            )
        });
        let host = self.host.clone();
        let session_clone = session.clone();
        tools_module.set_native_fn(
            "call",
            move |name: String, args: String| -> Result<String, Box<EvalAltResult>> {
                call_string(
                    &host,
                    &session_clone,
                    ScriptOperation::InvokeTool,
                    json!({ "name": name, "args": args }),
                )
            },
        );
        engine.register_static_module("tools", tools_module.into());

        let mut memory_module = Module::new();
        let host = self.host.clone();
        let session_clone = session.clone();
        memory_module.set_native_fn(
            "list",
            move |limit: i64| -> Result<String, Box<EvalAltResult>> {
                call_string(
                    &host,
                    &session_clone,
                    ScriptOperation::ListMemories,
                    json!({ "limit": limit }),
                )
            },
        );

        let host = self.host.clone();
        let session_clone = session.clone();
        memory_module.set_native_fn(
            "list_category",
            move |category: String, limit: i64| -> Result<String, Box<EvalAltResult>> {
                call_string(
                    &host,
                    &session_clone,
                    ScriptOperation::ListMemoriesByCategory,
                    json!({ "category": category, "limit": limit }),
                )
            },
        );

        let host = self.host.clone();
        let session_clone = session.clone();
        memory_module.set_native_fn(
            "recall",
            move |query: String, limit: i64| -> Result<String, Box<EvalAltResult>> {
                call_string(
                    &host,
                    &session_clone,
                    ScriptOperation::RecallMemory,
                    json!({ "query": query, "limit": limit }),
                )
            },
        );

        let host = self.host.clone();
        let session_clone = session.clone();
        memory_module.set_native_fn(
            "forget",
            move |key: String| -> Result<bool, Box<EvalAltResult>> {
                call_bool(
                    &host,
                    &session_clone,
                    ScriptOperation::ForgetMemory,
                    json!({ "key": key }),
                )
            },
        );

        let host = self.host.clone();
        let session_clone = session.clone();
        memory_module.set_native_fn("count", move || -> Result<i64, Box<EvalAltResult>> {
            call_int(
                &host,
                &session_clone,
                ScriptOperation::MemoryCount,
                serde_json::Value::Null,
            )
        });
        engine.register_static_module("memory", memory_module.into());

        let mut events_module = Module::new();
        let host = self.host.clone();
        let session_clone = session.clone();
        events_module.set_native_fn(
            "recent",
            move |limit: i64| -> Result<String, Box<EvalAltResult>> {
                call_string(
                    &host,
                    &session_clone,
                    ScriptOperation::RecentEvents,
                    json!({ "limit": limit }),
                )
            },
        );

        let host = self.host.clone();
        let session_clone = session.clone();
        events_module.set_native_fn(
            "traces",
            move |limit: i64| -> Result<String, Box<EvalAltResult>> {
                call_string(
                    &host,
                    &session_clone,
                    ScriptOperation::QueryTraces,
                    json!({ "limit": limit }),
                )
            },
        );

        let host = self.host.clone();
        let session_clone = session.clone();
        events_module.set_native_fn(
            "traces_filter",
            move |filter: String, limit: i64| -> Result<String, Box<EvalAltResult>> {
                call_string(
                    &host,
                    &session_clone,
                    ScriptOperation::QueryTracesByFilter,
                    json!({ "filter": filter, "limit": limit }),
                )
            },
        );
        engine.register_static_module("events", events_module.into());

        let mut skills_module = Module::new();
        let host = self.host.clone();
        let session_clone = session.clone();
        skills_module.set_native_fn("list", move || -> Result<String, Box<EvalAltResult>> {
            call_string(
                &host,
                &session_clone,
                ScriptOperation::ListSkills,
                serde_json::Value::Null,
            )
        });

        let host = self.host.clone();
        let session_clone = session.clone();
        skills_module.set_native_fn(
            "tools",
            move |name: String| -> Result<String, Box<EvalAltResult>> {
                call_string(
                    &host,
                    &session_clone,
                    ScriptOperation::GetSkillTools,
                    json!({ "name": name }),
                )
            },
        );

        let host = self.host.clone();
        let session_clone = session.clone();
        skills_module.set_native_fn(
            "install",
            move |source: String| -> Result<String, Box<EvalAltResult>> {
                call_string(
                    &host,
                    &session_clone,
                    ScriptOperation::InstallSkill,
                    json!({ "source": source }),
                )
            },
        );

        let host = self.host.clone();
        let session_clone = session.clone();
        skills_module.set_native_fn(
            "remove",
            move |name: String| -> Result<String, Box<EvalAltResult>> {
                call_string(
                    &host,
                    &session_clone,
                    ScriptOperation::RemoveSkill,
                    json!({ "name": name }),
                )
            },
        );
        engine.register_static_module("skills", skills_module.into());

        let mut storage_module = Module::new();

        let host = self.host.clone();
        let session_clone = session.clone();
        storage_module.set_native_fn(
            "read",
            move |key: String| -> Result<String, Box<EvalAltResult>> {
                let script_name = session_clone
                    .lock()
                    .map(|s| s.manifest_name.clone())
                    .unwrap_or_else(|_| "anonymous".to_string());
                call_string(
                    &host,
                    &session_clone,
                    ScriptOperation::ReadStorage,
                    json!({ "key": key, "script": script_name }),
                )
            },
        );

        let host = self.host.clone();
        let session_clone = session.clone();
        storage_module.set_native_fn(
            "write",
            move |key: String, value: String| -> Result<String, Box<EvalAltResult>> {
                let script_name = session_clone
                    .lock()
                    .map(|s| s.manifest_name.clone())
                    .unwrap_or_else(|_| "anonymous".to_string());
                call_string(
                    &host,
                    &session_clone,
                    ScriptOperation::WriteStorage,
                    json!({ "key": key, "value": value, "script": script_name }),
                )
            },
        );

        let host = self.host.clone();
        let session_clone = session.clone();
        storage_module.set_native_fn(
            "delete",
            move |key: String| -> Result<bool, Box<EvalAltResult>> {
                let script_name = session_clone
                    .lock()
                    .map(|s| s.manifest_name.clone())
                    .unwrap_or_else(|_| "anonymous".to_string());
                call_bool(
                    &host,
                    &session_clone,
                    ScriptOperation::DeleteStorage,
                    json!({ "key": key, "script": script_name }),
                )
            },
        );

        engine.register_static_module("storage", storage_module.into());
    }
}

fn normalize_manifest(
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

fn infer_capabilities(source: &str) -> Vec<String> {
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
            v4.is_loopback()           // 127.0.0.0/8
            || v4.is_private()         // 10/8, 172.16/12, 192.168/16
            || v4.is_link_local()      // 169.254/16
            || v4.is_broadcast()       // 255.255.255.255
            || v4.is_unspecified()     // 0.0.0.0
            || v4.octets()[0] == 100 && (v4.octets()[1] & 0xC0) == 64  // 100.64/10 (CGNAT)
        }
        IpAddr::V6(v6) => {
            v6.is_loopback()           // ::1
            || v6.is_unspecified()     // ::
            // IPv4-mapped IPv6 (::ffff:x.x.x.x) — check the inner v4
            || match v6.to_ipv4_mapped() {
                Some(v4) => is_private_ip(&IpAddr::V4(v4)),
                None => false,
            }
            // Link-local (fe80::/10)
            || (v6.segments()[0] & 0xffc0) == 0xfe80
            // ULA (fc00::/7)
            || (v6.segments()[0] & 0xfe00) == 0xfc00
        }
    }
}

/// Validates that a URL does not target private/reserved IP space.
///
/// Parses the hostname, resolves DNS, and checks every resolved address.
/// Rejects if ANY resolved IP is private (prevents DNS rebinding where
/// one A record is public and another is private).
fn is_safe_url(url: &str) -> Result<(), ScriptError> {
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

    // First try parsing as literal IP (catches decimal, hex, IPv6 literals)
    if let Ok(ip) = host_str.parse::<IpAddr>() {
        if is_private_ip(&ip) {
            return Err(ScriptError::InvalidArgument {
                detail: format!("URL resolves to private IP: {ip}"),
            });
        }
    }

    // Resolve DNS and check ALL addresses
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
        Err(_) => {
            // DNS resolution failed — allow it through (the HTTP client
            // will fail anyway). This prevents blocking on DNS timeouts.
            Ok(())
        }
    }
}

fn array_to_strings(values: Array) -> Vec<String> {
    values
        .into_iter()
        .map(|value| value.into_string().unwrap_or_default())
        .collect()
}

fn call_host(
    host: &Arc<dyn ScriptHost>,
    session: &Arc<Mutex<ScriptExecutionSession>>,
    operation: ScriptOperation,
    args: serde_json::Value,
) -> Result<ScriptValue, Box<EvalAltResult>> {
    {
        let mut guard = session.lock().map_err(|_| -> Box<EvalAltResult> {
            ScriptError::InternalState {
                detail: "script execution session mutex poisoned".to_string(),
            }
            .to_string()
            .into()
        })?;
        guard
            .require_capability(operation.capability(), operation.display_name())
            .map_err(|error| -> Box<EvalAltResult> {
                match error {
                    ScriptError::CapabilityDenied {
                        operation,
                        capability,
                    } => format!(
                        "{CAPABILITY_DENIED_SENTINEL}|{operation}|{capability}"
                    )
                    .into(),
                    other => other.to_string().into(),
                }
            })?;
    }

    // Inject the session's manifest name into args so the FFI host can
    // identify which script/skill is requesting the operation (needed for
    // capability approval gating at the FFI boundary).
    let enriched_args = {
        let guard = session.lock().map_err(|_| -> Box<EvalAltResult> {
            ScriptError::InternalState {
                detail: "script execution session mutex poisoned".to_string(),
            }
            .to_string()
            .into()
        })?;
        match args {
            serde_json::Value::Object(mut map) => {
                map.insert(
                    "__manifest_name".to_string(),
                    serde_json::Value::String(guard.manifest_name.clone()),
                );
                serde_json::Value::Object(map)
            }
            serde_json::Value::Null => {
                serde_json::json!({ "__manifest_name": guard.manifest_name })
            }
            other => {
                // Wrap non-object args — this shouldn't normally happen.
                serde_json::json!({
                    "__args": other,
                    "__manifest_name": guard.manifest_name,
                })
            }
        }
    };

    host.call(operation, enriched_args)
        .map_err(|error| -> Box<EvalAltResult> { error.to_string().into() })
}

fn call_string(
    host: &Arc<dyn ScriptHost>,
    session: &Arc<Mutex<ScriptExecutionSession>>,
    operation: ScriptOperation,
    args: serde_json::Value,
) -> Result<String, Box<EvalAltResult>> {
    Ok(call_host(host, session, operation, args)?.to_display_string())
}

fn call_bool(
    host: &Arc<dyn ScriptHost>,
    session: &Arc<Mutex<ScriptExecutionSession>>,
    operation: ScriptOperation,
    args: serde_json::Value,
) -> Result<bool, Box<EvalAltResult>> {
    match call_host(host, session, operation, args)? {
        ScriptValue::Bool(value) => Ok(value),
        other => Err(format!("expected bool return, got {other:?}").into()),
    }
}

fn call_int(
    host: &Arc<dyn ScriptHost>,
    session: &Arc<Mutex<ScriptExecutionSession>>,
    operation: ScriptOperation,
    args: serde_json::Value,
) -> Result<i64, Box<EvalAltResult>> {
    match call_host(host, session, operation, args)? {
        ScriptValue::Int(value) => Ok(value),
        other => Err(format!("expected integer return, got {other:?}").into()),
    }
}

fn call_float(
    host: &Arc<dyn ScriptHost>,
    session: &Arc<Mutex<ScriptExecutionSession>>,
    operation: ScriptOperation,
    args: serde_json::Value,
) -> Result<Dynamic, Box<EvalAltResult>> {
    match call_host(host, session, operation, args)? {
        ScriptValue::Float(value) => Ok(Dynamic::from_float(value)),
        ScriptValue::Int(value) => Ok(Dynamic::from_float(value as f64)),
        other => Err(format!("expected float return, got {other:?}").into()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;

    struct TestHost {
        responses: HashMap<ScriptOperation, ScriptValue>,
    }

    impl Default for TestHost {
        fn default() -> Self {
            let mut responses = HashMap::new();
            responses.insert(
                ScriptOperation::Status,
                ScriptValue::String(r#"{"daemon_running":false}"#.to_string()),
            );
            responses.insert(
                ScriptOperation::Version,
                ScriptValue::String("0.0.37".to_string()),
            );
            responses.insert(
                ScriptOperation::SendMessage,
                ScriptValue::String("sent".to_string()),
            );
            responses.insert(
                ScriptOperation::HealthDetail,
                ScriptValue::String(r#"{"healthy":true}"#.to_string()),
            );
            responses.insert(
                ScriptOperation::ListTools,
                ScriptValue::String(r#"["shell","memory_recall"]"#.to_string()),
            );
            responses.insert(
                ScriptOperation::ListMemories,
                ScriptValue::String(r#"["memory-a"]"#.to_string()),
            );
            responses.insert(
                ScriptOperation::RecallMemory,
                ScriptValue::String(r#"["memory-a"]"#.to_string()),
            );
            responses.insert(ScriptOperation::MemoryCount, ScriptValue::Int(2));
            responses.insert(ScriptOperation::ForgetMemory, ScriptValue::Bool(true));
            responses.insert(
                ScriptOperation::RecentEvents,
                ScriptValue::String(r#"["event-a"]"#.to_string()),
            );
            responses.insert(
                ScriptOperation::QueryTraces,
                ScriptValue::String(r#"["trace-a"]"#.to_string()),
            );
            responses.insert(
                ScriptOperation::ListSkills,
                ScriptValue::String(r#"["skill-a"]"#.to_string()),
            );
            Self { responses }
        }
    }

    impl ScriptHost for TestHost {
        fn call(
            &self,
            operation: ScriptOperation,
            _args: serde_json::Value,
        ) -> Result<ScriptValue, ScriptError> {
            self.responses
                .get(&operation)
                .cloned()
                .ok_or_else(|| ScriptError::HostError {
                    operation: operation.display_name().to_string(),
                    detail: "missing response".to_string(),
                })
        }
    }

    fn runtime() -> RhaiScriptRuntime {
        RhaiScriptRuntime::new(Arc::new(TestHost::default()))
    }

    #[test]
    fn arithmetic_eval_still_works() {
        let result = runtime().eval_script("2 + 3", None).unwrap();
        assert_eq!(result, "5");
    }

    #[test]
    fn validation_infers_flat_and_namespaced_capabilities() {
        let validation = runtime()
            .validate_script(
                r#"
                send("hello");
                memory::recall("topic", 5);
                tools::list();
            "#,
                None,
            )
            .unwrap();

        assert_eq!(
            validation.requested_capabilities,
            vec![
                "memory.read".to_string(),
                "model.chat".to_string(),
                "tools.read".to_string(),
            ]
        );
        assert!(validation
            .warnings
            .iter()
            .any(|warning| warning.contains("source-inferred")));
    }

    #[test]
    fn explicit_manifest_missing_capability_is_reported() {
        let validation = runtime()
            .validate_script(
                r#"send("hello");"#,
                Some(ScriptManifest {
                    name: "limited".to_string(),
                    capabilities: vec!["memory.read".to_string()],
                    ..Default::default()
                }),
            )
            .unwrap();

        assert_eq!(validation.missing_capabilities, vec!["model.chat".to_string()]);
    }

    #[test]
    fn denied_capability_fails_execution() {
        let error = runtime()
            .eval_script(
                r#"send("hello");"#,
                Some(ScriptManifest {
                    name: "limited".to_string(),
                    capabilities: vec!["memory.read".to_string()],
                    ..Default::default()
                }),
            )
            .unwrap_err();

        assert!(matches!(error, ScriptError::CapabilityDenied { .. }));
        assert!(error.to_string().contains("capability denied"));
    }

    #[test]
    fn workspace_discovery_finds_workflows_and_skill_scripts() {
        let dir = tempfile::tempdir().unwrap();
        let workflow_dir = dir.path().join("workflows");
        std::fs::create_dir_all(&workflow_dir).unwrap();
        std::fs::write(workflow_dir.join("cleanup.rhai"), "status()").unwrap();

        let skill_dir = dir.path().join("skills").join("demo");
        std::fs::create_dir_all(skill_dir.join("scripts")).unwrap();
        std::fs::write(skill_dir.join("scripts").join("triage.rhai"), "memory_count()").unwrap();
        std::fs::write(
            skill_dir.join("SKILL.toml"),
            r#"
[skill]
name = "demo"
description = "demo skill"
version = "0.1.0"

permissions = ["memory.read"]

[[scripts]]
name = "triage"
path = "scripts/triage.rhai"

[[triggers]]
kind = "manual"
"#,
        )
        .unwrap();

        let manifests = discover_workspace_scripts(dir.path());
        assert_eq!(manifests.len(), 2);
        assert!(manifests.iter().any(|manifest| manifest.name.ends_with("cleanup.rhai")));
        assert!(manifests
            .iter()
            .any(|manifest| manifest.name == "demo::triage"
                && manifest.capabilities == vec!["memory.read".to_string()]));
    }

    #[test]
    fn wit_definition_is_available() {
        let wit = runtime().plugin_host_wit();
        assert!(wit.contains("interface host"));
        assert!(wit.contains("invoke-tool"));
    }

    #[test]
    fn guest_runtime_validation_is_deterministic() {
        let validation = runtime()
            .validate_script(
                "print('hello')",
                Some(ScriptManifest {
                    name: "guest".to_string(),
                    runtime: ScriptRuntimeKind::Python,
                    capabilities: vec!["memory.read".to_string()],
                    explicit_capabilities: true,
                    ..Default::default()
                }),
            )
            .unwrap();

        assert_eq!(validation.runtime, ScriptRuntimeKind::Python);
        assert_eq!(validation.requested_capabilities, vec!["memory.read".to_string()]);
        assert!(validation
            .warnings
            .iter()
            .any(|warning| warning.contains("stable plugin ABI")));
    }

    #[test]
    fn workspace_validation_uses_skill_manifest_capabilities() {
        let dir = tempfile::tempdir().unwrap();
        let skill_dir = dir.path().join("skills").join("demo");
        std::fs::create_dir_all(skill_dir.join("scripts")).unwrap();
        std::fs::write(
            skill_dir.join("SKILL.toml"),
            r#"
[skill]
name = "demo"
description = "demo skill"
version = "0.1.0"

permissions = ["memory.read"]

[[scripts]]
name = "triage"
path = "scripts/triage.rhai"
"#,
        )
        .unwrap();
        std::fs::write(skill_dir.join("scripts").join("triage.rhai"), "memory_count()").unwrap();

        let validation = runtime()
            .validate_workspace_script(
                dir.path(),
                Path::new("skills/demo/scripts/triage.rhai"),
                None,
            )
            .unwrap();

        assert_eq!(validation.requested_capabilities, vec!["memory.read".to_string()]);
    }

    #[test]
    fn workspace_guest_runtime_is_blocked_until_enabled() {
        let dir = tempfile::tempdir().unwrap();
        let workflow_dir = dir.path().join("workflows");
        std::fs::create_dir_all(&workflow_dir).unwrap();
        std::fs::write(workflow_dir.join("guest.py"), "print('hello')").unwrap();

        let error = runtime()
            .eval_workspace_script(dir.path(), Path::new("workflows/guest.py"), None)
            .unwrap_err();

        assert!(matches!(error, ScriptError::ValidationError { .. }));
        assert!(error.to_string().contains("runtime 'python'"));
    }

    #[test]
    fn explicit_empty_capabilities_stay_denied() {
        let validation = runtime()
            .validate_script(
                r#"send("hello");"#,
                Some(ScriptManifest {
                    name: "deny-all".to_string(),
                    explicit_capabilities: true,
                    ..Default::default()
                }),
            )
            .unwrap();

        assert!(validation.requested_capabilities.is_empty());
        assert_eq!(validation.missing_capabilities, vec!["model.chat".to_string()]);
    }

    #[test]
    fn new_operations_have_correct_capabilities() {
        assert_eq!(ScriptOperation::InvokeTool.capability(), "tools.call");
        assert_eq!(ScriptOperation::InvokeTool.display_name(), "tool_call");
        assert_eq!(ScriptOperation::ReadStorage.capability(), "storage.read");
        assert_eq!(ScriptOperation::WriteStorage.capability(), "storage.write");
        assert_eq!(ScriptOperation::DeleteStorage.capability(), "storage.write");
    }

    #[test]
    fn wit_v0_2_0_has_required_functions() {
        let host = std::sync::Arc::new(StubScriptHost);
        let rt = RhaiScriptRuntime::new(host);
        let wit = rt.plugin_host_wit();
        assert!(wit.contains("@0.2.0"), "version");
        assert!(wit.contains("invoke-tool"), "invoke-tool");
        assert!(wit.contains("list-tools"), "list-tools");
        assert!(wit.contains("agent-status"), "agent-status");
        assert!(wit.contains("cron-list"), "cron-list");
        assert!(wit.contains("cost-summary"), "cost-summary");
        assert!(wit.contains("export run"), "guest run export");
    }

    #[test]
    fn infinite_loop_is_terminated() {
        use std::sync::Arc;
        let host = Arc::new(StubScriptHost);
        let runtime = RhaiScriptRuntime::new(host);
        let result = runtime.eval_script("loop { }", None);
        assert!(result.is_err());
        let err = result.unwrap_err().to_string();
        assert!(
            err.contains("operations") || err.contains("timed out") || err.contains("progress"),
            "expected resource limit error, got: {err}"
        );
    }

    #[cfg(unix)]
    #[test]
    fn symlink_escape_is_blocked() {
        let workspace = tempfile::tempdir().unwrap();
        let escape_target = tempfile::tempdir().unwrap();
        let secret = escape_target.path().join("secret.txt");
        std::fs::write(&secret, "stolen data").unwrap();

        let link = workspace.path().join("escape");
        std::os::unix::fs::symlink(escape_target.path(), &link).unwrap();

        let result = open_workspace_file(
            workspace.path(),
            std::path::Path::new("escape/secret.txt"),
        );
        // cap-std Dir::open blocks escapes structurally
        assert!(result.is_err(), "symlink escape should be blocked");
    }

    #[test]
    fn is_safe_url_rejects_ipv4_mapped_ipv6() {
        // ::ffff:127.0.0.1 is localhost via IPv4-mapped IPv6
        assert!(is_safe_url("http://[::ffff:127.0.0.1]/api").is_err());
    }

    #[test]
    fn is_safe_url_rejects_decimal_ip() {
        // 2130706433 == 127.0.0.1 in decimal notation
        assert!(is_safe_url("http://2130706433/api").is_err());
    }

    #[test]
    fn is_safe_url_rejects_rfc1918() {
        assert!(is_safe_url("http://10.0.0.1/api").is_err());
        assert!(is_safe_url("http://172.16.0.1/api").is_err());
        assert!(is_safe_url("http://192.168.1.1/api").is_err());
    }

    #[test]
    fn is_safe_url_allows_public() {
        assert!(is_safe_url("https://api.openai.com/v1/models").is_ok());
    }
}
