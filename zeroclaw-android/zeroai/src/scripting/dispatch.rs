// Copyright (c) 2026 @Natfii. All rights reserved.

//! Rhai-backed runtime dispatch: engine construction, host bridge, and
//! script execution flow including workspace-script discovery.

use crate::scripting::audit::{
    array_to_strings, consume_audit_record, dynamic_to_string, record_script_audit_event,
    script_eval_error, validation_failure_audit,
};
use crate::scripting::capabilities::{
    infer_capabilities, is_safe_url, normalize_manifest, open_workspace_file,
    resolve_workspace_script_manifest, ScriptExecutionSession, CAPABILITY_DENIED_SENTINEL,
};
use crate::scripting::discovery::{
    runtime_is_available, runtime_runtime_notes, unavailable_runtime_execution_detail,
};
use crate::scripting::manifest::{
    default_script_capabilities, validation_available_capability_names, ScriptAuditRecord,
    ScriptCapability, ScriptError, ScriptHost, ScriptLimits, ScriptManifest, ScriptOperation,
    ScriptPluginRuntime, ScriptRuntimeKind, ScriptValidation, ScriptValue,
};
use chrono::Datelike;
use rhai::packages::{CorePackage, Package};
use rhai::{Array, Dynamic, Engine, EvalAltResult, Module};
use serde_json::json;
use std::collections::BTreeSet;
use std::path::Path;
use std::sync::{Arc, Mutex};
use std::time::Instant;

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
                self.validate_guest_manifest(manifest, source.len())
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
                return Err(ScriptError::ValidationError {
                    detail: "Wasm component guests cannot be evaluated from inline source. \
                             Use eval_workspace_script with a .wasm file path instead."
                        .to_string(),
                });
            }
            #[cfg(not(feature = "scripting-wasm-component"))]
            {
                let detail = "WasmComponent runtime not available in this build. \
                     Enable with: features.add(\"scripting-wasm-component\") in \
                     lib/build.gradle.kts"
                    .to_string();
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

    /// Validate a workspace script by relative path.
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
                let detail = "WasmComponent runtime not available in this build. \
                     Enable with: features.add(\"scripting-wasm-component\") in \
                     lib/build.gradle.kts"
                    .to_string();
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
        if source.len() > self.limits.max_script_bytes {
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
    Ok(call_host(host, session, operation, args)?.into_display_string())
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

// Precision loss above 2^52 is inherent to surfacing an integer host
// value through Rhai's f64 float type and acceptable for script display.
#[allow(clippy::cast_precision_loss)]
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
