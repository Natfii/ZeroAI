// Copyright (c) 2026 @Natfii. All rights reserved.

//! Data types and capability catalogue for the scripting subsystem.

use serde::{Deserialize, Serialize};
use std::path::PathBuf;
use std::sync::{Arc, OnceLock};

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
    /// Stable capability name such as `model.chat` or `memory.read`.
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

impl ScriptLimits {
    /// Limits for agent eval_script: 10M operations (100x default) to
    /// support batch data processing. All other limits match default.
    /// The 30s wall-clock timeout is enforced separately via
    /// `engine.on_progress()`, not by this struct.
    pub fn for_agent_eval() -> Self {
        Self {
            max_operations: 10_000_000,
            ..Self::default()
        }
    }
}

/// Trigger metadata for packaged scripts.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
pub struct ScriptTrigger {
    /// Trigger type such as `manual`, `cron`, `channel_event`, or `provider_event`.
    pub kind: String,
    /// Optional cron schedule when `kind == "cron"`.
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
    pub fn display_name(self) -> &'static str {
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

    pub(crate) fn capability(self) -> &'static str {
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
    pub(crate) fn to_display_string(self) -> String {
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

/// Stub host that rejects all operations.
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
pub fn set_cron_script_host(host: Arc<dyn ScriptHost>) {
    let _ = CRON_SCRIPT_HOST.set(host);
}

/// Returns the registered cron [`ScriptHost`], if any.
pub fn get_cron_script_host() -> Option<Arc<dyn ScriptHost>> {
    CRON_SCRIPT_HOST.get().cloned()
}

pub(crate) fn default_script_version() -> String {
    "0.1.0".to_string()
}

/// Returns the canonical capability catalogue enforced by the runtime.
pub fn default_script_capabilities() -> Vec<ScriptCapability> {
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

/// Build the fixed capability set for agent eval_script invocations.
///
/// See spec: `docs/superpowers/specs/2026-03-21-eval-script-agent-tool-design.md`
pub fn build_agent_capabilities(nano_available: bool) -> Vec<String> {
    let mut caps = vec![
        "storage.read".to_string(),
        "storage.write".to_string(),
        "memory.read".to_string(),
        "memory.write".to_string(),
        "tools.read".to_string(),
        "tools.call".to_string(),
        "cost.read".to_string(),
        "events.read".to_string(),
        "config.validate".to_string(),
    ];
    if nano_available {
        caps.push("model.chat".to_string());
    }
    caps
}

pub(crate) fn validation_available_capability_names() -> Vec<String> {
    default_script_capabilities()
        .into_iter()
        .map(|capability| capability.name)
        .collect()
}
