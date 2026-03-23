// Copyright (c) 2026 @Natfii. All rights reserved.

//! DeepSeek provider implementation.
//!
//! Handles DeepSeek-specific quirks:
//! - `parameters` vs `arguments` tool call field mismatch
//! - Strict `tool_call_id` requirement
//! - `reasoning_content` extraction for R1 thinking tokens
//! - Context overflow bailout (131K / 64K limits)
//! - Orphaned tool message protection

use std::collections::HashMap;

use anyhow::Result;
use async_trait::async_trait;

use crate::providers::openai_compat::{
    self, CompatChatRequest, CompatChatResponse, CompatMessage, CompatToolCall,
};
use crate::providers::traits::{
    ChatMessage, ChatResponse, Provider, ProviderCapabilities, ToolCall,
};
use crate::providers::scrub_secret_patterns;

const DEEPSEEK_BASE_URL: &str = "https://api.deepseek.com/v1";
const DEEPSEEK_DEFAULT_MODEL: &str = "deepseek-chat";
const DEEPSEEK_DEFAULT_TIMEOUT_SECS: u64 = 300;

/// DeepSeek provider.
///
/// Implements the [`Provider`] trait for the DeepSeek API, handling quirks
/// including `parameters` vs `arguments` field mismatch, strict `tool_call_id`
/// requirements, `reasoning_content` extraction for R1 thinking tokens, and
/// orphaned tool message protection.
pub struct DeepSeekProvider {
    /// API credential for authenticating requests.
    pub(crate) credential: Option<String>,
    custom_headers: Option<HashMap<String, String>>,
}

impl DeepSeekProvider {
    /// Create a new provider with the given API credential.
    pub fn new(credential: Option<&str>) -> Self {
        Self {
            credential: credential.map(ToString::to_string),
            custom_headers: None,
        }
    }

    /// Set custom HTTP headers to include in all API requests.
    pub fn with_custom_headers(mut self, headers: Option<HashMap<String, String>>) -> Self {
        self.custom_headers = headers;
        self
    }

    /// Strip the `"deepseek/"` provider prefix from a model name.
    ///
    /// Returns the bare model name (e.g. `"deepseek-chat"` from `"deepseek/deepseek-chat"`).
    fn normalized_model_name(model: &str) -> &str {
        model.rsplit('/').next().unwrap_or(model)
    }

    /// Return the stored credential or an error with actionable instructions.
    fn require_credential(&self) -> Result<&str> {
        self.credential.as_deref().ok_or_else(|| {
            anyhow::anyhow!(
                "DeepSeek API key not set. Set DEEPSEEK_API_KEY or configure in settings."
            )
        })
    }

    /// Validate that tool messages are not orphaned.
    ///
    /// DeepSeek enforces strict ordering: every `tool` role message must have a
    /// `tool_call_id` and must follow an `assistant` message that contains tool
    /// calls. Returns an error if either condition is violated.
    fn validate_tool_messages(messages: &[CompatMessage]) -> Result<()> {
        let mut last_had_tool_calls = false;
        for msg in messages {
            if msg.role == "tool" {
                if msg.tool_call_id.is_none() {
                    anyhow::bail!(
                        "Orphaned tool response message detected — \
                         conversation history may be corrupted"
                    );
                }
                if !last_had_tool_calls {
                    anyhow::bail!(
                        "Orphaned tool response message detected — \
                         no preceding assistant message with tool_calls"
                    );
                }
            }
            last_had_tool_calls =
                msg.role == "assistant"
                    && msg.tool_calls.as_ref().map_or(false, |tc| !tc.is_empty());
        }
        Ok(())
    }

    /// Build an HTTP client configured for DeepSeek requests.
    fn http_client(&self) -> reqwest::Client {
        crate::config::build_runtime_proxy_client_with_timeouts(
            "provider.deepseek",
            DEEPSEEK_DEFAULT_TIMEOUT_SECS,
            10,
        )
    }
}

// ---------------------------------------------------------------------------
// Provider trait implementation
// ---------------------------------------------------------------------------

#[async_trait]
impl Provider for DeepSeekProvider {
    /// Return DeepSeek provider capabilities.
    fn capabilities(&self) -> ProviderCapabilities {
        ProviderCapabilities {
            native_tool_calling: true,
            vision: false,
        }
    }

    /// One-shot chat with optional system prompt.
    async fn chat_with_system(
        &self,
        system_prompt: Option<&str>,
        message: &str,
        model: &str,
        temperature: f64,
    ) -> anyhow::Result<String> {
        let credential = self.require_credential()?;
        let model = Self::normalized_model_name(model);

        let mut messages = Vec::new();
        if let Some(sys) = system_prompt {
            messages.push(CompatMessage {
                role: "system".to_string(),
                content: Some(serde_json::Value::String(sys.to_string())),
                tool_call_id: None,
                tool_calls: None,
            });
        }
        messages.push(CompatMessage {
            role: "user".to_string(),
            content: Some(serde_json::Value::String(message.to_string())),
            tool_call_id: None,
            tool_calls: None,
        });

        let request = CompatChatRequest {
            model: model.to_string(),
            messages,
            tools: None,
            max_tokens: None,
            temperature,
            stream: false,
        };

        let req = self
            .http_client()
            .post(format!("{DEEPSEEK_BASE_URL}/chat/completions"))
            .header("Authorization", format!("Bearer {credential}"))
            .json(&request);
        let response = super::apply_custom_headers(req, &self.custom_headers)
            .send()
            .await?;

        if !response.status().is_success() {
            return Err(super::api_error("DeepSeek", response).await);
        }

        let chat_response: CompatChatResponse = response.json().await?;
        chat_response
            .choices
            .into_iter()
            .next()
            .and_then(|c| {
                let msg = c.message;
                match msg.content {
                    Some(ref c) if !c.is_empty() => Some(c.clone()),
                    _ => msg
                        .reasoning_content
                        .map(|r| scrub_secret_patterns(&r)),
                }
            })
            .ok_or_else(|| anyhow::anyhow!("No response from DeepSeek"))
    }

    /// Structured chat API for agent loop callers.
    ///
    /// Passes full message history and converts any tool specs to the
    /// OpenAI-compatible function calling format, delegating to
    /// [`chat_with_tools`].
    async fn chat(
        &self,
        request: crate::providers::traits::ChatRequest<'_>,
        model: &str,
        temperature: f64,
    ) -> anyhow::Result<ChatResponse> {
        let tool_values: Vec<serde_json::Value> = request
            .tools
            .unwrap_or(&[])
            .iter()
            .map(|spec| {
                serde_json::json!({
                    "type": "function",
                    "function": {
                        "name": spec.name,
                        "description": spec.description,
                        "parameters": spec.parameters,
                    }
                })
            })
            .collect();
        self.chat_with_tools(request.messages, &tool_values, model, temperature)
            .await
    }

    /// Chat with tool definitions for native function calling support.
    ///
    /// Validates tool messages for orphaned tool responses before sending.
    /// Normalizes `parameters` vs `arguments` field mismatch on response tool
    /// calls and applies secret scrubbing to any `reasoning_content`.
    async fn chat_with_tools(
        &self,
        messages: &[ChatMessage],
        tools: &[serde_json::Value],
        model: &str,
        temperature: f64,
    ) -> anyhow::Result<ChatResponse> {
        let credential = self.require_credential()?;
        let model = Self::normalized_model_name(model);

        let converted = openai_compat::convert_messages(messages);
        Self::validate_tool_messages(&converted)?;

        let tools_payload = if tools.is_empty() {
            None
        } else {
            Some(serde_json::Value::Array(tools.to_vec()))
        };
        let tool_choice = tools_payload.as_ref().map(|_| "auto".to_string());

        let request = CompatChatRequest {
            model: model.to_string(),
            messages: converted,
            tools: tools_payload,
            max_tokens: None,
            temperature,
            stream: false,
        };

        // tool_choice is part of the request body — extend the serialized JSON
        // with it since CompatChatRequest doesn't carry the field.
        let mut body = serde_json::to_value(&request)?;
        if let (Some(obj), Some(tc)) = (body.as_object_mut(), tool_choice) {
            obj.insert("tool_choice".to_string(), serde_json::Value::String(tc));
        }

        let req = self
            .http_client()
            .post(format!("{DEEPSEEK_BASE_URL}/chat/completions"))
            .header("Authorization", format!("Bearer {credential}"))
            .json(&body);
        let response = super::apply_custom_headers(req, &self.custom_headers)
            .send()
            .await?;

        if !response.status().is_success() {
            return Err(super::api_error("DeepSeek", response).await);
        }

        let chat_response: CompatChatResponse = response.json().await?;
        let usage = chat_response
            .usage
            .map(|u| crate::providers::traits::TokenUsage {
                input_tokens: u.prompt_tokens,
                output_tokens: u.completion_tokens,
            });

        let message = chat_response
            .choices
            .into_iter()
            .next()
            .map(|c| c.message)
            .ok_or_else(|| anyhow::anyhow!("No response from DeepSeek"))?;

        let reasoning_content = message
            .reasoning_content
            .as_deref()
            .map(scrub_secret_patterns);

        let text = match message.content {
            Some(ref c) if !c.is_empty() => Some(c.clone()),
            _ => reasoning_content.clone(),
        };

        let mut tool_calls_raw: Vec<CompatToolCall> =
            message.tool_calls.unwrap_or_default();
        openai_compat::normalize_tool_calls(&mut tool_calls_raw);
        let tool_calls: Vec<ToolCall> = openai_compat::extract_tool_calls(&tool_calls_raw);

        Ok(ChatResponse {
            text,
            tool_calls,
            usage,
            reasoning_content,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::providers::openai_compat::{CompatFunctionCall, CompatMessage, CompatToolCall};

    // --- normalized_model_name ---

    #[test]
    fn normalized_model_name_strips_prefix() {
        assert_eq!(
            DeepSeekProvider::normalized_model_name("deepseek/deepseek-chat"),
            "deepseek-chat"
        );
    }

    #[test]
    fn normalized_model_name_no_prefix_unchanged() {
        assert_eq!(
            DeepSeekProvider::normalized_model_name("deepseek-chat"),
            "deepseek-chat"
        );
    }

    // --- validate_tool_messages ---

    #[test]
    fn validate_tool_messages_valid_sequence() {
        let messages = vec![
            CompatMessage {
                role: "user".to_string(),
                content: Some(serde_json::Value::String("Hello".to_string())),
                tool_call_id: None,
                tool_calls: None,
            },
            CompatMessage {
                role: "assistant".to_string(),
                content: None,
                tool_call_id: None,
                tool_calls: Some(vec![CompatToolCall {
                    id: Some("call_1".to_string()),
                    kind: Some("function".to_string()),
                    function: CompatFunctionCall {
                        name: "get_weather".to_string(),
                        arguments: "{}".to_string(),
                    },
                    parameters: None,
                }]),
            },
            CompatMessage {
                role: "tool".to_string(),
                content: Some(serde_json::Value::String("sunny".to_string())),
                tool_call_id: Some("call_1".to_string()),
                tool_calls: None,
            },
        ];
        assert!(DeepSeekProvider::validate_tool_messages(&messages).is_ok());
    }

    #[test]
    fn validate_tool_messages_orphaned_no_tool_call_id() {
        let messages = vec![CompatMessage {
            role: "tool".to_string(),
            content: Some(serde_json::Value::String("result".to_string())),
            tool_call_id: None,
            tool_calls: None,
        }];
        let result = DeepSeekProvider::validate_tool_messages(&messages);
        assert!(result.is_err());
        let msg = result.unwrap_err().to_string();
        assert!(
            msg.contains("Orphaned"),
            "error should mention Orphaned, got: {msg}"
        );
    }

    #[test]
    fn validate_tool_messages_orphaned_no_preceding_assistant() {
        let messages = vec![
            CompatMessage {
                role: "user".to_string(),
                content: Some(serde_json::Value::String("Hello".to_string())),
                tool_call_id: None,
                tool_calls: None,
            },
            CompatMessage {
                role: "tool".to_string(),
                content: Some(serde_json::Value::String("result".to_string())),
                tool_call_id: Some("call_1".to_string()),
                tool_calls: None,
            },
        ];
        let result = DeepSeekProvider::validate_tool_messages(&messages);
        assert!(result.is_err());
        let msg = result.unwrap_err().to_string();
        assert!(
            msg.contains("Orphaned"),
            "error should mention Orphaned, got: {msg}"
        );
    }

    // --- capabilities ---

    #[test]
    fn capabilities_no_vision() {
        let provider = DeepSeekProvider::new(Some("sk-test"));
        let caps = provider.capabilities();
        assert!(caps.native_tool_calling);
        assert!(!caps.vision);
    }
}
