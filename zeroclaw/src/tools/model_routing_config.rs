use super::traits::{Tool, ToolResult};
use crate::config::{Config, DelegateAgentConfig};
use crate::security::SecurityPolicy;
use crate::util::MaybeSet;
use async_trait::async_trait;
use serde_json::{json, Value};
use std::collections::BTreeMap;
use std::fs;
use std::sync::Arc;

const DEFAULT_AGENT_MAX_DEPTH: u32 = 3;
const DEFAULT_AGENT_MAX_ITERATIONS: usize = 10;

pub struct ModelRoutingConfigTool {
    config: Arc<Config>,
    security: Arc<SecurityPolicy>,
}

impl ModelRoutingConfigTool {
    pub fn new(config: Arc<Config>, security: Arc<SecurityPolicy>) -> Self {
        Self { config, security }
    }

    fn load_config_without_env(&self) -> anyhow::Result<Config> {
        let contents = fs::read_to_string(&self.config.config_path).map_err(|error| {
            anyhow::anyhow!(
                "Failed to read config file {}: {error}",
                self.config.config_path.display()
            )
        })?;

        let mut parsed: Config = toml::from_str(&contents).map_err(|error| {
            anyhow::anyhow!(
                "Failed to parse config file {}: {error}",
                self.config.config_path.display()
            )
        })?;
        parsed.config_path = self.config.config_path.clone();
        parsed.workspace_dir = self.config.workspace_dir.clone();
        Ok(parsed)
    }

    fn require_write_access(&self) -> Option<ToolResult> {
        if !self.security.can_act() {
            return Some(ToolResult {
                success: false,
                output: String::new(),
                error: Some("Action blocked: autonomy is read-only".into()),
            });
        }

        if !self.security.record_action() {
            return Some(ToolResult {
                success: false,
                output: String::new(),
                error: Some("Action blocked: rate limit exceeded".into()),
            });
        }

        None
    }

    fn parse_string_list(raw: &Value, field: &str) -> anyhow::Result<Vec<String>> {
        if let Some(raw_string) = raw.as_str() {
            return Ok(raw_string
                .split(',')
                .map(str::trim)
                .filter(|entry| !entry.is_empty())
                .map(ToOwned::to_owned)
                .collect());
        }

        if let Some(array) = raw.as_array() {
            let mut out = Vec::new();
            for item in array {
                let value = item
                    .as_str()
                    .ok_or_else(|| anyhow::anyhow!("'{field}' array must only contain strings"))?;
                let trimmed = value.trim();
                if !trimmed.is_empty() {
                    out.push(trimmed.to_string());
                }
            }
            return Ok(out);
        }

        anyhow::bail!("'{field}' must be a string or string[]")
    }

    fn parse_non_empty_string(args: &Value, field: &str) -> anyhow::Result<String> {
        let value = args
            .get(field)
            .and_then(Value::as_str)
            .ok_or_else(|| anyhow::anyhow!("Missing '{field}'"))?
            .trim();

        if value.is_empty() {
            anyhow::bail!("'{field}' must not be empty");
        }

        Ok(value.to_string())
    }

    fn parse_optional_string_update(args: &Value, field: &str) -> anyhow::Result<MaybeSet<String>> {
        let Some(raw) = args.get(field) else {
            return Ok(MaybeSet::Unset);
        };

        if raw.is_null() {
            return Ok(MaybeSet::Null);
        }

        let value = raw
            .as_str()
            .ok_or_else(|| anyhow::anyhow!("'{field}' must be a string or null"))?
            .trim()
            .to_string();

        let output = if value.is_empty() {
            MaybeSet::Null
        } else {
            MaybeSet::Set(value)
        };
        Ok(output)
    }

    fn parse_optional_f64_update(args: &Value, field: &str) -> anyhow::Result<MaybeSet<f64>> {
        let Some(raw) = args.get(field) else {
            return Ok(MaybeSet::Unset);
        };

        if raw.is_null() {
            return Ok(MaybeSet::Null);
        }

        let value = raw
            .as_f64()
            .ok_or_else(|| anyhow::anyhow!("'{field}' must be a number or null"))?;
        Ok(MaybeSet::Set(value))
    }

    fn parse_optional_usize_update(args: &Value, field: &str) -> anyhow::Result<MaybeSet<usize>> {
        let Some(raw) = args.get(field) else {
            return Ok(MaybeSet::Unset);
        };

        if raw.is_null() {
            return Ok(MaybeSet::Null);
        }

        let raw_value = raw
            .as_u64()
            .ok_or_else(|| anyhow::anyhow!("'{field}' must be a non-negative integer or null"))?;
        let value = usize::try_from(raw_value)
            .map_err(|_| anyhow::anyhow!("'{field}' is too large for this platform"))?;
        Ok(MaybeSet::Set(value))
    }

    fn parse_optional_u32_update(args: &Value, field: &str) -> anyhow::Result<MaybeSet<u32>> {
        let Some(raw) = args.get(field) else {
            return Ok(MaybeSet::Unset);
        };

        if raw.is_null() {
            return Ok(MaybeSet::Null);
        }

        let raw_value = raw
            .as_u64()
            .ok_or_else(|| anyhow::anyhow!("'{field}' must be a non-negative integer or null"))?;
        let value =
            u32::try_from(raw_value).map_err(|_| anyhow::anyhow!("'{field}' must fit in u32"))?;
        Ok(MaybeSet::Set(value))
    }

    fn parse_optional_bool(args: &Value, field: &str) -> anyhow::Result<Option<bool>> {
        let Some(raw) = args.get(field) else {
            return Ok(None);
        };

        let value = raw
            .as_bool()
            .ok_or_else(|| anyhow::anyhow!("'{field}' must be a boolean"))?;
        Ok(Some(value))
    }

    fn snapshot(cfg: &Config) -> Value {
        let mut agents: BTreeMap<String, Value> = BTreeMap::new();
        for (name, agent) in &cfg.agents {
            agents.insert(
                name.clone(),
                json!({
                    "provider": agent.provider,
                    "model": agent.model,
                    "system_prompt": agent.system_prompt,
                    "api_key_configured": agent
                        .api_key
                        .as_ref()
                        .is_some_and(|value| !value.trim().is_empty()),
                    "temperature": agent.temperature,
                    "max_depth": agent.max_depth,
                    "agentic": agent.agentic,
                    "allowed_tools": agent.allowed_tools,
                    "max_iterations": agent.max_iterations,
                }),
            );
        }

        json!({
            "default": {
                "provider": cfg.default_provider,
                "model": cfg.default_model,
                "temperature": cfg.default_temperature,
            },
            "agents": agents,
        })
    }

    fn handle_get(&self) -> anyhow::Result<ToolResult> {
        let cfg = self.load_config_without_env()?;
        Ok(ToolResult {
            success: true,
            output: serde_json::to_string_pretty(&Self::snapshot(&cfg))?,
            error: None,
        })
    }

    async fn handle_set_default(&self, args: &Value) -> anyhow::Result<ToolResult> {
        let provider_update = Self::parse_optional_string_update(args, "provider")?;
        let model_update = Self::parse_optional_string_update(args, "model")?;
        let temperature_update = Self::parse_optional_f64_update(args, "temperature")?;

        let any_update = !matches!(provider_update, MaybeSet::Unset)
            || !matches!(model_update, MaybeSet::Unset)
            || !matches!(temperature_update, MaybeSet::Unset);

        if !any_update {
            anyhow::bail!("set_default requires at least one of: provider, model, temperature");
        }

        let mut cfg = self.load_config_without_env()?;

        match provider_update {
            MaybeSet::Set(provider) => cfg.default_provider = Some(provider),
            MaybeSet::Null => cfg.default_provider = None,
            MaybeSet::Unset => {}
        }

        match model_update {
            MaybeSet::Set(model) => cfg.default_model = Some(model),
            MaybeSet::Null => cfg.default_model = None,
            MaybeSet::Unset => {}
        }

        match temperature_update {
            MaybeSet::Set(temperature) => {
                if !(0.0..=2.0).contains(&temperature) {
                    anyhow::bail!("'temperature' must be between 0.0 and 2.0");
                }
                cfg.default_temperature = temperature;
            }
            MaybeSet::Null => {
                cfg.default_temperature = Config::default().default_temperature;
            }
            MaybeSet::Unset => {}
        }

        cfg.save().await?;

        Ok(ToolResult {
            success: true,
            output: serde_json::to_string_pretty(&json!({
                "message": "Default provider/model settings updated",
                "config": Self::snapshot(&cfg),
            }))?,
            error: None,
        })
    }

    async fn handle_upsert_agent(&self, args: &Value) -> anyhow::Result<ToolResult> {
        let name = Self::parse_non_empty_string(args, "name")?;
        let provider = Self::parse_non_empty_string(args, "provider")?;
        let model = Self::parse_non_empty_string(args, "model")?;

        let system_prompt_update = Self::parse_optional_string_update(args, "system_prompt")?;
        let api_key_update = Self::parse_optional_string_update(args, "api_key")?;
        let temperature_update = Self::parse_optional_f64_update(args, "temperature")?;
        let max_depth_update = Self::parse_optional_u32_update(args, "max_depth")?;
        let max_iterations_update = Self::parse_optional_usize_update(args, "max_iterations")?;
        let agentic_update = Self::parse_optional_bool(args, "agentic")?;

        let allowed_tools_update = if let Some(raw) = args.get("allowed_tools") {
            Some(Self::parse_string_list(raw, "allowed_tools")?)
        } else {
            None
        };

        let mut cfg = self.load_config_without_env()?;

        let mut next_agent = cfg
            .agents
            .get(&name)
            .cloned()
            .unwrap_or(DelegateAgentConfig {
                provider: provider.clone(),
                model: model.clone(),
                system_prompt: None,
                api_key: None,
                temperature: None,
                max_depth: DEFAULT_AGENT_MAX_DEPTH,
                agentic: false,
                allowed_tools: Vec::new(),
                max_iterations: DEFAULT_AGENT_MAX_ITERATIONS,
            });

        next_agent.provider = provider;
        next_agent.model = model;

        match system_prompt_update {
            MaybeSet::Set(value) => next_agent.system_prompt = Some(value),
            MaybeSet::Null => next_agent.system_prompt = None,
            MaybeSet::Unset => {}
        }

        match api_key_update {
            MaybeSet::Set(value) => next_agent.api_key = Some(value),
            MaybeSet::Null => next_agent.api_key = None,
            MaybeSet::Unset => {}
        }

        match temperature_update {
            MaybeSet::Set(value) => {
                if !(0.0..=2.0).contains(&value) {
                    anyhow::bail!("'temperature' must be between 0.0 and 2.0");
                }
                next_agent.temperature = Some(value);
            }
            MaybeSet::Null => next_agent.temperature = None,
            MaybeSet::Unset => {}
        }

        match max_depth_update {
            MaybeSet::Set(value) => next_agent.max_depth = value,
            MaybeSet::Null => next_agent.max_depth = DEFAULT_AGENT_MAX_DEPTH,
            MaybeSet::Unset => {}
        }

        match max_iterations_update {
            MaybeSet::Set(value) => next_agent.max_iterations = value,
            MaybeSet::Null => next_agent.max_iterations = DEFAULT_AGENT_MAX_ITERATIONS,
            MaybeSet::Unset => {}
        }

        if let Some(agentic) = agentic_update {
            next_agent.agentic = agentic;
        }

        if let Some(allowed_tools) = allowed_tools_update {
            next_agent.allowed_tools = allowed_tools;
        }

        if next_agent.max_depth == 0 {
            anyhow::bail!("'max_depth' must be greater than 0");
        }

        if next_agent.max_iterations == 0 {
            anyhow::bail!("'max_iterations' must be greater than 0");
        }

        if next_agent.agentic && next_agent.allowed_tools.is_empty() {
            anyhow::bail!(
                "Agent '{name}' has agentic=true but allowed_tools is empty. Set allowed_tools or disable agentic mode."
            );
        }

        cfg.agents.insert(name.clone(), next_agent);
        cfg.save().await?;

        Ok(ToolResult {
            success: true,
            output: serde_json::to_string_pretty(&json!({
                "message": "Delegate agent upserted",
                "name": name,
                "config": Self::snapshot(&cfg),
            }))?,
            error: None,
        })
    }

    async fn handle_remove_agent(&self, args: &Value) -> anyhow::Result<ToolResult> {
        let name = Self::parse_non_empty_string(args, "name")?;

        let mut cfg = self.load_config_without_env()?;
        if cfg.agents.remove(&name).is_none() {
            anyhow::bail!("No delegate agent found with name '{name}'");
        }

        cfg.save().await?;

        Ok(ToolResult {
            success: true,
            output: serde_json::to_string_pretty(&json!({
                "message": "Delegate agent removed",
                "name": name,
                "config": Self::snapshot(&cfg),
            }))?,
            error: None,
        })
    }
}

#[async_trait]
impl Tool for ModelRoutingConfigTool {
    fn name(&self) -> &str {
        "model_routing_config"
    }

    fn description(&self) -> &str {
        "Manage default model settings and delegate sub-agent profiles"
    }

    fn parameters_schema(&self) -> Value {
        json!({
            "type": "object",
            "properties": {
                "action": {
                    "type": "string",
                    "enum": [
                        "get",
                        "set_default",
                        "upsert_agent",
                        "remove_agent"
                    ],
                    "default": "get"
                },
                "provider": {
                    "type": "string",
                    "description": "Provider for set_default/upsert_agent"
                },
                "model": {
                    "type": "string",
                    "description": "Model for set_default/upsert_agent"
                },
                "temperature": {
                    "type": ["number", "null"],
                    "description": "Optional temperature override (0.0-2.0)"
                },
                "api_key": {
                    "type": ["string", "null"],
                    "description": "Optional API key override for delegate agent"
                },
                "name": {
                    "type": "string",
                    "description": "Delegate sub-agent name for upsert_agent/remove_agent"
                },
                "system_prompt": {
                    "type": ["string", "null"],
                    "description": "Optional system prompt override for delegate agent"
                },
                "max_depth": {
                    "type": ["integer", "null"],
                    "minimum": 1,
                    "description": "Delegate max recursion depth"
                },
                "agentic": {
                    "type": "boolean",
                    "description": "Enable tool-call loop mode for delegate agent"
                },
                "allowed_tools": {
                    "description": "Allowed tools for agentic delegate mode (string or string array)",
                    "oneOf": [
                        {"type": "string"},
                        {"type": "array", "items": {"type": "string"}}
                    ]
                },
                "max_iterations": {
                    "type": ["integer", "null"],
                    "minimum": 1,
                    "description": "Maximum tool-call iterations for agentic delegate mode"
                }
            },
            "additionalProperties": false
        })
    }

    async fn execute(&self, args: Value) -> anyhow::Result<ToolResult> {
        let action = args
            .get("action")
            .and_then(Value::as_str)
            .unwrap_or("get")
            .to_ascii_lowercase();

        let result = match action.as_str() {
            "get" => self.handle_get(),
            "set_default" | "upsert_agent" | "remove_agent" => {
                if let Some(blocked) = self.require_write_access() {
                    return Ok(blocked);
                }

                match action.as_str() {
                    "set_default" => self.handle_set_default(&args).await,
                    "upsert_agent" => self.handle_upsert_agent(&args).await,
                    "remove_agent" => self.handle_remove_agent(&args).await,
                    _ => unreachable!("validated above"),
                }
            }
            _ => anyhow::bail!(
                "Unknown action '{action}'. Valid: get, set_default, upsert_agent, remove_agent"
            ),
        };

        match result {
            Ok(outcome) => Ok(outcome),
            Err(error) => Ok(ToolResult {
                success: false,
                output: String::new(),
                error: Some(error.to_string()),
            }),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::security::{AutonomyLevel, SecurityPolicy};
    use tempfile::TempDir;

    fn test_security() -> Arc<SecurityPolicy> {
        Arc::new(SecurityPolicy {
            autonomy: AutonomyLevel::Supervised,
            workspace_dir: std::env::temp_dir(),
            ..SecurityPolicy::default()
        })
    }

    fn readonly_security() -> Arc<SecurityPolicy> {
        Arc::new(SecurityPolicy {
            autonomy: AutonomyLevel::ReadOnly,
            workspace_dir: std::env::temp_dir(),
            ..SecurityPolicy::default()
        })
    }

    async fn test_config(tmp: &TempDir) -> Arc<Config> {
        let config = Config {
            workspace_dir: tmp.path().join("workspace"),
            config_path: tmp.path().join("config.toml"),
            ..Config::default()
        };
        config.save().await.unwrap();
        Arc::new(config)
    }

    #[tokio::test]
    async fn set_default_updates_provider_model_and_temperature() {
        let tmp = TempDir::new().unwrap();
        let tool = ModelRoutingConfigTool::new(test_config(&tmp).await, test_security());

        let result = tool
            .execute(json!({
                "action": "set_default",
                "provider": "kimi",
                "model": "moonshot-v1-8k",
                "temperature": 0.2
            }))
            .await
            .unwrap();

        assert!(result.success, "{:?}", result.error);
        let output: Value = serde_json::from_str(&result.output).unwrap();
        assert_eq!(
            output["config"]["default"]["provider"].as_str(),
            Some("kimi")
        );
        assert_eq!(
            output["config"]["default"]["model"].as_str(),
            Some("moonshot-v1-8k")
        );
        assert_eq!(
            output["config"]["default"]["temperature"].as_f64(),
            Some(0.2)
        );
    }

    #[tokio::test]
    async fn upsert_and_remove_delegate_agent() {
        let tmp = TempDir::new().unwrap();
        let tool = ModelRoutingConfigTool::new(test_config(&tmp).await, test_security());

        let upsert = tool
            .execute(json!({
                "action": "upsert_agent",
                "name": "coder",
                "provider": "openai",
                "model": "gpt-5.3-codex",
                "agentic": true,
                "allowed_tools": ["file_read", "file_write", "shell"],
                "max_iterations": 6
            }))
            .await
            .unwrap();
        assert!(upsert.success, "{:?}", upsert.error);

        let get_result = tool.execute(json!({"action": "get"})).await.unwrap();
        let output: Value = serde_json::from_str(&get_result.output).unwrap();
        assert_eq!(output["agents"]["coder"]["provider"], json!("openai"));
        assert_eq!(output["agents"]["coder"]["model"], json!("gpt-5.3-codex"));
        assert_eq!(output["agents"]["coder"]["agentic"], json!(true));

        let remove = tool
            .execute(json!({
                "action": "remove_agent",
                "name": "coder"
            }))
            .await
            .unwrap();
        assert!(remove.success, "{:?}", remove.error);

        let get_result = tool.execute(json!({"action": "get"})).await.unwrap();
        let output: Value = serde_json::from_str(&get_result.output).unwrap();
        assert!(output["agents"]["coder"].is_null());
    }

    #[tokio::test]
    async fn read_only_mode_blocks_mutating_actions() {
        let tmp = TempDir::new().unwrap();
        let tool = ModelRoutingConfigTool::new(test_config(&tmp).await, readonly_security());

        let result = tool
            .execute(json!({
                "action": "set_default",
                "provider": "openai"
            }))
            .await
            .unwrap();

        assert!(!result.success);
        assert!(result.error.unwrap_or_default().contains("read-only"));
    }
}
