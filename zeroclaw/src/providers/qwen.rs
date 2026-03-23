// Copyright (c) 2026 @Natfii. All rights reserved.

//! Qwen (Alibaba DashScope) provider implementation.
//!
//! Handles Qwen-specific features:
//! - Three regional endpoints (International, China, US)
//! - `<think>...</think>` tag stripping in the batch path
//! - `reasoning_content` fallback when stripped content is empty
//! - Region-mismatch 400 errors classified as Auth for cascade

use std::collections::HashMap;

use anyhow::Result;
use async_trait::async_trait;

use crate::providers::openai_compat::{
    self, CompatChatRequest, CompatChatResponse, CompatToolCall,
};
use crate::providers::traits::{
    ChatMessage, ChatResponse, Provider, ProviderCapabilities, ToolCall,
};
use crate::providers::scrub_secret_patterns;

/// Regional endpoint selection for Alibaba DashScope API.
///
/// Qwen models are served from three independent regional endpoints.
/// Choose the region closest to your deployment for lowest latency.
/// NOT exported via FFI — region selection flows through the provider ID string.
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum QwenRegion {
    /// International endpoint (default for non-China deployments).
    International,
    /// Mainland China endpoint.
    China,
    /// United States endpoint.
    Us,
}

impl QwenRegion {
    /// Return the base URL for this region.
    pub fn base_url(&self) -> &'static str {
        match self {
            Self::International => "https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
            Self::China => "https://dashscope.aliyuncs.com/compatible-mode/v1",
            Self::Us => "https://dashscope-us.aliyuncs.com/compatible-mode/v1",
        }
    }
}

const QWEN_DEFAULT_MODEL: &str = "qwen3.5-plus";
const QWEN_DEFAULT_TIMEOUT_SECS: u64 = 300;

/// Qwen (Alibaba DashScope) provider.
///
/// Implements the [`Provider`] trait for the DashScope API, supporting regional
/// endpoints, think-tag stripping in the batch path, `reasoning_content` fallback,
/// and region-mismatch error classification as Auth for cascade.
pub struct QwenProvider {
    region: QwenRegion,
    /// API credential for authenticating requests.
    pub(crate) credential: Option<String>,
    custom_headers: Option<HashMap<String, String>>,
}

impl QwenProvider {
    /// Create a new provider targeting the given region with the given API credential.
    pub fn new(region: QwenRegion, credential: Option<&str>) -> Self {
        Self {
            region,
            credential: credential.map(ToString::to_string),
            custom_headers: None,
        }
    }

    /// Set custom HTTP headers to include in all API requests.
    pub fn with_custom_headers(mut self, headers: Option<HashMap<String, String>>) -> Self {
        self.custom_headers = headers;
        self
    }

    /// Strip the `"qwen/"` provider prefix from a model name.
    ///
    /// Returns the bare model name (e.g. `"qwen3.5-plus"` from `"qwen/qwen3.5-plus"`).
    fn normalized_model_name(model: &str) -> &str {
        model.rsplit('/').next().unwrap_or(model)
    }

    /// Return the stored credential or an error with actionable instructions.
    fn require_credential(&self) -> Result<&str> {
        self.credential.as_deref().ok_or_else(|| {
            anyhow::anyhow!(
                "Qwen API key not set. Set DASHSCOPE_API_KEY or configure in settings."
            )
        })
    }

    /// Build an HTTP client configured for Qwen requests.
    fn http_client(&self) -> reqwest::Client {
        crate::config::build_runtime_proxy_client_with_timeouts(
            "provider.qwen",
            QWEN_DEFAULT_TIMEOUT_SECS,
            10,
        )
    }
}

// ---------------------------------------------------------------------------
// Provider trait implementation
// ---------------------------------------------------------------------------

#[async_trait]
impl Provider for QwenProvider {
    /// Return Qwen provider capabilities.
    fn capabilities(&self) -> ProviderCapabilities {
        ProviderCapabilities {
            native_tool_calling: true,
            vision: true,
        }
    }

    /// One-shot chat with optional system prompt.
    ///
    /// Strips `<think>...</think>` tags from the response content. If the content
    /// is empty after stripping but `reasoning_content` is present, the reasoning
    /// content is used as the effective response. The effective content is scrubbed
    /// through [`scrub_secret_patterns`] before being returned.
    async fn chat_with_system(
        &self,
        system_prompt: Option<&str>,
        message: &str,
        model: &str,
        temperature: f64,
    ) -> anyhow::Result<String> {
        let credential = self.require_credential()?;
        let model = Self::normalized_model_name(model);
        let base_url = self.region.base_url();

        let mut messages = Vec::new();
        if let Some(sys) = system_prompt {
            messages.push(openai_compat::CompatMessage {
                role: "system".to_string(),
                content: Some(serde_json::Value::String(sys.to_string())),
                tool_call_id: None,
                tool_calls: None,
            });
        }
        messages.push(openai_compat::CompatMessage {
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
            .post(format!("{base_url}/chat/completions"))
            .header("Authorization", format!("Bearer {credential}"))
            .json(&request);
        let response = super::apply_custom_headers(req, &self.custom_headers)
            .send()
            .await?;

        if !response.status().is_success() {
            return Err(super::api_error("Qwen", response).await);
        }

        let chat_response: CompatChatResponse = response.json().await?;
        chat_response
            .choices
            .into_iter()
            .next()
            .and_then(|c| {
                let msg = c.message;
                let raw_content = msg.content.as_deref().unwrap_or("");
                let (stripped, _thinking) = openai_compat::strip_think_tags(raw_content);
                if stripped.is_empty() {
                    msg.reasoning_content
                        .map(|r| scrub_secret_patterns(&r))
                } else {
                    Some(scrub_secret_patterns(&stripped))
                }
            })
            .ok_or_else(|| anyhow::anyhow!("No response from Qwen"))
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
    /// Strips `<think>...</think>` tags from content and applies secret scrubbing
    /// to `reasoning_content`. If content is empty after stripping but
    /// `reasoning_content` is present, it is used as the effective text.
    async fn chat_with_tools(
        &self,
        messages: &[ChatMessage],
        tools: &[serde_json::Value],
        model: &str,
        temperature: f64,
    ) -> anyhow::Result<ChatResponse> {
        let credential = self.require_credential()?;
        let model = Self::normalized_model_name(model);
        let base_url = self.region.base_url();

        let converted = openai_compat::convert_messages(messages);

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
            .post(format!("{base_url}/chat/completions"))
            .header("Authorization", format!("Bearer {credential}"))
            .json(&body);
        let response = super::apply_custom_headers(req, &self.custom_headers)
            .send()
            .await?;

        if !response.status().is_success() {
            return Err(super::api_error("Qwen", response).await);
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
            .ok_or_else(|| anyhow::anyhow!("No response from Qwen"))?;

        let reasoning_content = message
            .reasoning_content
            .as_deref()
            .map(scrub_secret_patterns);

        let raw_content = message.content.as_deref().unwrap_or("");
        let (stripped, _thinking) = openai_compat::strip_think_tags(raw_content);
        let text = if stripped.is_empty() {
            reasoning_content.clone()
        } else {
            Some(scrub_secret_patterns(&stripped))
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

    // --- region_urls_are_correct ---

    #[test]
    fn region_urls_are_correct() {
        assert_eq!(
            QwenRegion::International.base_url(),
            "https://dashscope-intl.aliyuncs.com/compatible-mode/v1"
        );
        assert_eq!(
            QwenRegion::China.base_url(),
            "https://dashscope.aliyuncs.com/compatible-mode/v1"
        );
        assert_eq!(
            QwenRegion::Us.base_url(),
            "https://dashscope-us.aliyuncs.com/compatible-mode/v1"
        );
    }

    #[test]
    fn all_region_urls_are_https() {
        for region in [QwenRegion::International, QwenRegion::China, QwenRegion::Us] {
            assert!(
                region.base_url().starts_with("https://"),
                "Region {:?} URL is not HTTPS: {}",
                region,
                region.base_url()
            );
        }
    }

    // --- normalized_model_name ---

    #[test]
    fn normalized_model_name_strips_prefix() {
        assert_eq!(
            QwenProvider::normalized_model_name("qwen/qwen3.5-plus"),
            "qwen3.5-plus"
        );
    }

    #[test]
    fn normalized_model_name_no_prefix() {
        assert_eq!(
            QwenProvider::normalized_model_name("qwen3.5-plus"),
            "qwen3.5-plus"
        );
    }

    // --- capabilities ---

    #[test]
    fn capabilities_has_vision() {
        let provider = QwenProvider::new(QwenRegion::International, Some("sk-test"));
        let caps = provider.capabilities();
        assert!(caps.native_tool_calling);
        assert!(caps.vision);
    }

    // --- unused constant suppression ---

    #[test]
    fn default_model_is_set() {
        assert!(!QWEN_DEFAULT_MODEL.is_empty());
    }
}
