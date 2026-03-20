// Copyright (c) 2026 @Natfii. All rights reserved.

//! xAI (Grok) provider helpers.
//!
//! Three API quirks handled here:
//!
//! 1. **HTML-entity-encoded tool arguments** — xAI returns tool call `arguments`
//!    with `&quot;`, `&amp;`, `&lt;`, `&gt;`, and `&#39;` HTML-encoded instead of
//!    bare JSON characters.  [`decode_html_entities`] decodes them back to plain
//!    text and validates the result is still valid JSON.
//!
//! 2. **Unsupported JSON Schema keywords** — xAI rejects requests that include
//!    `minLength`, `maxLength`, `minItems`, `maxItems`, `minContains`, or
//!    `maxContains` inside tool parameter schemas.  [`clean_tool_schemas`] strips
//!    these keywords (and the `strict` boolean used by other providers) before
//!    the request is sent.
//!
//! 3. **Default model** — The correct default model identifier is captured in
//!    [`XAI_DEFAULT_MODEL`] so every call site stays consistent.

use crate::providers::traits::{
    ChatMessage, ChatResponse as ProviderChatResponse, Provider, ProviderCapabilities,
    ToolCall as ProviderToolCall,
};
use crate::tools::ToolSpec;
use async_trait::async_trait;
use reqwest::Client;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;

/// Maximum number of bytes accepted by [`decode_html_entities`].
///
/// Matches xAI's documented 1 MiB limit for tool call argument payloads.
pub const MAX_TOOL_ARGS_BYTES: usize = 1_048_576; // 1 MiB

/// Default xAI model identifier used when no explicit model is configured.
pub const XAI_DEFAULT_MODEL: &str = "grok-4";

/// JSON Schema keywords that xAI rejects inside tool parameter schemas.
const STRIPPED_KEYWORDS: &[&str] = &[
    "minLength",
    "maxLength",
    "minItems",
    "maxItems",
    "minContains",
    "maxContains",
];

/// Decodes the five HTML entities that xAI encodes inside tool-call argument
/// strings and validates that the decoded result is valid JSON.
///
/// xAI encodes `"`, `&`, `<`, `>`, and `'` as HTML entities inside the
/// `arguments` field of tool calls.  Standard JSON parsers do not understand
/// HTML entities, so the raw string must be decoded before being parsed.
///
/// # Errors
///
/// Returns an error if:
/// - `input` exceeds [`MAX_TOOL_ARGS_BYTES`] bytes.
/// - The decoded string is not valid JSON.
pub fn decode_html_entities(input: &str) -> anyhow::Result<String> {
    if input.len() > MAX_TOOL_ARGS_BYTES {
        anyhow::bail!(
            "xAI tool arguments payload is too large: {} bytes (limit {} bytes)",
            input.len(),
            MAX_TOOL_ARGS_BYTES,
        );
    }

    // Replace in a single pass over the string, preferring a pre-allocated
    // buffer sized to the input to avoid repeated reallocations.
    let mut decoded = String::with_capacity(input.len());
    let mut rest = input;

    while !rest.is_empty() {
        // Find the next '&' which starts any entity we care about.
        match rest.find('&') {
            None => {
                decoded.push_str(rest);
                break;
            }
            Some(pos) => {
                // Copy everything before the '&'.
                decoded.push_str(&rest[..pos]);
                rest = &rest[pos..];

                if let Some(tail) = rest.strip_prefix("&quot;") {
                    decoded.push('"');
                    rest = tail;
                } else if let Some(tail) = rest.strip_prefix("&amp;") {
                    decoded.push('&');
                    rest = tail;
                } else if let Some(tail) = rest.strip_prefix("&lt;") {
                    decoded.push('<');
                    rest = tail;
                } else if let Some(tail) = rest.strip_prefix("&gt;") {
                    decoded.push('>');
                    rest = tail;
                } else if let Some(tail) = rest.strip_prefix("&#39;") {
                    decoded.push('\'');
                    rest = tail;
                } else {
                    // Not a recognised entity — copy the bare '&' and continue.
                    decoded.push('&');
                    rest = &rest[1..];
                }
            }
        }
    }

    // Validate that the decoded string is valid JSON before returning it.
    let _: serde_json::Value = serde_json::from_str(&decoded).map_err(|e| {
        anyhow::anyhow!("xAI tool arguments are not valid JSON after decoding: {e}")
    })?;

    Ok(decoded)
}

/// Strips JSON Schema keywords and the `strict` boolean that xAI does not
/// support from every tool definition in `tools`.
///
/// Mutates `tools` in-place.  Any tool entry that does not match the expected
/// shape (`{type: "function", function: {parameters: {...}}}`) is left
/// unchanged rather than causing an error.
pub fn clean_tool_schemas(tools: &mut Vec<serde_json::Value>) {
    for tool in tools.iter_mut() {
        // Remove `strict` at the top-level function definition.
        if let Some(function) = tool
            .as_object_mut()
            .and_then(|obj| obj.get_mut("function"))
            .and_then(|f| f.as_object_mut())
        {
            function.remove("strict");

            // Recursively strip unsupported keywords from the parameter schema.
            if let Some(parameters) = function.get_mut("parameters") {
                strip_keywords_recursive(parameters, STRIPPED_KEYWORDS);
            }
        }
    }
}

/// Recursively removes every key listed in `keywords` from `value` and all
/// nested JSON objects and arrays.
pub fn strip_keywords_recursive(value: &mut serde_json::Value, keywords: &[&str]) {
    match value {
        serde_json::Value::Object(map) => {
            for kw in keywords {
                map.remove(*kw);
            }
            for child in map.values_mut() {
                strip_keywords_recursive(child, keywords);
            }
        }
        serde_json::Value::Array(arr) => {
            for item in arr.iter_mut() {
                strip_keywords_recursive(item, keywords);
            }
        }
        // Scalar values have no keywords to strip.
        _ => {}
    }
}

const XAI_BASE_URL: &str = "https://api.x.ai/v1";
const XAI_DEFAULT_TIMEOUT_SECS: u64 = 300;

// ---------------------------------------------------------------------------
// Wire types
// ---------------------------------------------------------------------------

#[derive(Debug, Serialize)]
struct ChatRequest {
    model: String,
    messages: Vec<NativeMessage>,
    temperature: f64,
    #[serde(skip_serializing_if = "Option::is_none")]
    tools: Option<Vec<serde_json::Value>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    tool_choice: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    stream: Option<bool>,
}

#[derive(Debug, Serialize, Deserialize)]
struct NativeMessage {
    role: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    content: Option<serde_json::Value>,
    #[serde(skip_serializing_if = "Option::is_none")]
    tool_call_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    tool_calls: Option<Vec<NativeToolCall>>,
}

#[derive(Debug, Serialize, Deserialize)]
struct NativeToolCall {
    #[serde(skip_serializing_if = "Option::is_none")]
    id: Option<String>,
    #[serde(rename = "type", skip_serializing_if = "Option::is_none")]
    kind: Option<String>,
    function: NativeFunctionCall,
}

#[derive(Debug, Serialize, Deserialize)]
struct NativeFunctionCall {
    name: String,
    arguments: String,
}

#[derive(Debug, Deserialize)]
struct ChatResponse {
    choices: Vec<Choice>,
    #[serde(default)]
    usage: Option<UsageInfo>,
}

#[derive(Debug, Deserialize)]
struct Choice {
    message: ResponseMessage,
}

#[derive(Debug, Deserialize)]
struct ResponseMessage {
    #[serde(default)]
    content: Option<String>,
    #[serde(default)]
    reasoning_content: Option<String>,
    #[serde(default)]
    tool_calls: Option<Vec<NativeToolCall>>,
}

impl ResponseMessage {
    fn effective_content(&self) -> Option<String> {
        match &self.content {
            Some(c) if !c.is_empty() => Some(c.clone()),
            _ => self.reasoning_content.clone(),
        }
    }
}

#[derive(Debug, Deserialize)]
struct UsageInfo {
    #[serde(default)]
    prompt_tokens: Option<u64>,
    #[serde(default)]
    completion_tokens: Option<u64>,
}

// ---------------------------------------------------------------------------
// XaiProvider struct
// ---------------------------------------------------------------------------

/// xAI (Grok) provider implementation.
///
/// Implements the [`Provider`] trait for the xAI API, handling the three
/// API quirks documented in the module-level doc comment: HTML-entity-encoded
/// tool arguments, unsupported JSON Schema keywords, and model name normalization.
pub struct XaiProvider {
    pub(crate) credential: Option<String>,
    custom_headers: Option<HashMap<String, String>>,
}

impl XaiProvider {
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

    /// Strip the `"xai/"` provider prefix from a model name.
    ///
    /// Returns the bare model name (e.g. `"grok-4"` from `"xai/grok-4"`).
    fn normalized_model_name(model: &str) -> &str {
        model.rsplit('/').next().unwrap_or(model)
    }

    /// Convert trait [`ChatMessage`] slice to xAI wire messages.
    ///
    /// Handles assistant tool-call messages and tool result messages following
    /// the OpenAI Chat Completions wire format that xAI uses.
    fn convert_messages(messages: &[ChatMessage]) -> Vec<NativeMessage> {
        messages
            .iter()
            .map(|m| {
                if m.role == "assistant" {
                    if let Ok(value) = serde_json::from_str::<serde_json::Value>(&m.content) {
                        if let Some(tool_calls_value) = value.get("tool_calls") {
                            if let Ok(parsed_calls) =
                                serde_json::from_value::<Vec<ProviderToolCall>>(
                                    tool_calls_value.clone(),
                                )
                            {
                                let tool_calls = parsed_calls
                                    .into_iter()
                                    .map(|tc| NativeToolCall {
                                        id: Some(tc.id),
                                        kind: Some("function".to_string()),
                                        function: NativeFunctionCall {
                                            name: tc.name,
                                            arguments: tc.arguments,
                                        },
                                    })
                                    .collect::<Vec<_>>();
                                let content = value
                                    .get("content")
                                    .and_then(serde_json::Value::as_str)
                                    .map(|s| serde_json::Value::String(s.to_string()));
                                return NativeMessage {
                                    role: "assistant".to_string(),
                                    content,
                                    tool_call_id: None,
                                    tool_calls: Some(tool_calls),
                                };
                            }
                        }
                    }
                }

                if m.role == "tool" {
                    if let Ok(value) = serde_json::from_str::<serde_json::Value>(&m.content) {
                        let tool_call_id = value
                            .get("tool_call_id")
                            .and_then(serde_json::Value::as_str)
                            .map(ToString::to_string);
                        let content = value
                            .get("content")
                            .and_then(serde_json::Value::as_str)
                            .map(|s| serde_json::Value::String(s.to_string()));
                        return NativeMessage {
                            role: "tool".to_string(),
                            content,
                            tool_call_id,
                            tool_calls: None,
                        };
                    }
                }

                NativeMessage {
                    role: m.role.clone(),
                    content: Some(serde_json::Value::String(m.content.clone())),
                    tool_call_id: None,
                    tool_calls: None,
                }
            })
            .collect()
    }

    /// Convert [`ToolSpec`] slice to xAI-compatible JSON tool definitions.
    ///
    /// Applies [`clean_tool_schemas`] to strip unsupported JSON Schema keywords
    /// before the tools are sent in the request.
    fn convert_and_clean_tools(tools: Option<&[ToolSpec]>) -> Option<Vec<serde_json::Value>> {
        let items = tools?;
        if items.is_empty() {
            return None;
        }
        let mut converted: Vec<serde_json::Value> = items
            .iter()
            .map(|tool| {
                serde_json::json!({
                    "type": "function",
                    "function": {
                        "name": tool.name,
                        "description": tool.description,
                        "parameters": tool.parameters,
                    }
                })
            })
            .collect();
        clean_tool_schemas(&mut converted);
        Some(converted)
    }

    /// Parse a [`ResponseMessage`] into a [`ProviderChatResponse`].
    ///
    /// Applies [`decode_html_entities`] to each tool call's `arguments` string
    /// to undo xAI's HTML encoding of JSON content.
    fn parse_response(message: ResponseMessage) -> anyhow::Result<ProviderChatResponse> {
        let text = message.effective_content();
        let reasoning_content = message.reasoning_content.clone();
        let mut tool_calls = Vec::new();
        for tc in message.tool_calls.unwrap_or_default() {
            let decoded_args = decode_html_entities(&tc.function.arguments)?;
            tool_calls.push(ProviderToolCall {
                id: tc.id.unwrap_or_else(|| uuid::Uuid::new_v4().to_string()),
                name: tc.function.name,
                arguments: decoded_args,
            });
        }
        Ok(ProviderChatResponse {
            text,
            tool_calls,
            usage: None,
            reasoning_content,
        })
    }

    /// Build an HTTP client configured for xAI requests.
    fn http_client(&self) -> Client {
        crate::config::build_runtime_proxy_client_with_timeouts(
            "provider.xai",
            XAI_DEFAULT_TIMEOUT_SECS,
            10,
        )
    }

    /// Return the stored credential or an error with actionable instructions.
    fn require_credential(&self) -> anyhow::Result<&str> {
        self.credential.as_deref().ok_or_else(|| {
            anyhow::anyhow!("xAI API key not set. Set XAI_API_KEY or configure in settings.")
        })
    }
}

// ---------------------------------------------------------------------------
// Provider trait implementation
// ---------------------------------------------------------------------------

#[async_trait]
impl Provider for XaiProvider {
    fn capabilities(&self) -> ProviderCapabilities {
        ProviderCapabilities {
            native_tool_calling: true,
            vision: true,
        }
    }

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
            messages.push(NativeMessage {
                role: "system".to_string(),
                content: Some(serde_json::Value::String(sys.to_string())),
                tool_call_id: None,
                tool_calls: None,
            });
        }
        messages.push(NativeMessage {
            role: "user".to_string(),
            content: Some(serde_json::Value::String(message.to_string())),
            tool_call_id: None,
            tool_calls: None,
        });

        let request = ChatRequest {
            model: model.to_string(),
            messages,
            temperature,
            tools: None,
            tool_choice: None,
            stream: None,
        };

        let req = self
            .http_client()
            .post(format!("{XAI_BASE_URL}/chat/completions"))
            .header("Authorization", format!("Bearer {credential}"))
            .json(&request);
        let response = super::apply_custom_headers(req, &self.custom_headers)
            .send()
            .await?;

        if !response.status().is_success() {
            return Err(super::api_error("xAI", response).await);
        }

        let chat_response: ChatResponse = response.json().await?;
        chat_response
            .choices
            .into_iter()
            .next()
            .and_then(|c| c.message.effective_content())
            .ok_or_else(|| anyhow::anyhow!("No response from xAI"))
    }

    async fn chat(
        &self,
        request: crate::providers::traits::ChatRequest<'_>,
        model: &str,
        temperature: f64,
    ) -> anyhow::Result<ProviderChatResponse> {
        let credential = self.require_credential()?;
        let model = Self::normalized_model_name(model);
        let tools_payload = Self::convert_and_clean_tools(request.tools);
        let tool_choice = tools_payload.as_ref().map(|_| "auto".to_string());

        let request = ChatRequest {
            model: model.to_string(),
            messages: Self::convert_messages(request.messages),
            temperature,
            tools: tools_payload,
            tool_choice,
            stream: None,
        };

        let req = self
            .http_client()
            .post(format!("{XAI_BASE_URL}/chat/completions"))
            .header("Authorization", format!("Bearer {credential}"))
            .json(&request);
        let response = super::apply_custom_headers(req, &self.custom_headers)
            .send()
            .await?;

        if !response.status().is_success() {
            return Err(super::api_error("xAI", response).await);
        }

        let chat_response: ChatResponse = response.json().await?;
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
            .ok_or_else(|| anyhow::anyhow!("No response from xAI"))?;

        let mut result = Self::parse_response(message)?;
        result.usage = usage;
        Ok(result)
    }

    async fn chat_with_history(
        &self,
        messages: &[ChatMessage],
        model: &str,
        temperature: f64,
    ) -> anyhow::Result<String> {
        let credential = self.require_credential()?;
        let model = Self::normalized_model_name(model);

        let request = ChatRequest {
            model: model.to_string(),
            messages: Self::convert_messages(messages),
            temperature,
            tools: None,
            tool_choice: None,
            stream: None,
        };

        let req = self
            .http_client()
            .post(format!("{XAI_BASE_URL}/chat/completions"))
            .header("Authorization", format!("Bearer {credential}"))
            .json(&request);
        let response = super::apply_custom_headers(req, &self.custom_headers)
            .send()
            .await?;

        if !response.status().is_success() {
            return Err(super::api_error("xAI", response).await);
        }

        let chat_response: ChatResponse = response.json().await?;
        chat_response
            .choices
            .into_iter()
            .next()
            .and_then(|c| c.message.effective_content())
            .ok_or_else(|| anyhow::anyhow!("No response from xAI"))
    }

    async fn chat_with_tools(
        &self,
        messages: &[ChatMessage],
        tools: &[serde_json::Value],
        model: &str,
        temperature: f64,
    ) -> anyhow::Result<ProviderChatResponse> {
        let credential = self.require_credential()?;
        let model = Self::normalized_model_name(model);

        let mut cleaned_tools: Vec<serde_json::Value> = tools.to_vec();
        clean_tool_schemas(&mut cleaned_tools);
        let tools_payload = if cleaned_tools.is_empty() {
            None
        } else {
            Some(cleaned_tools)
        };
        let tool_choice = tools_payload.as_ref().map(|_| "auto".to_string());

        let request = ChatRequest {
            model: model.to_string(),
            messages: Self::convert_messages(messages),
            temperature,
            tools: tools_payload,
            tool_choice,
            stream: None,
        };

        let req = self
            .http_client()
            .post(format!("{XAI_BASE_URL}/chat/completions"))
            .header("Authorization", format!("Bearer {credential}"))
            .json(&request);
        let response = super::apply_custom_headers(req, &self.custom_headers)
            .send()
            .await?;

        if !response.status().is_success() {
            return Err(super::api_error("xAI", response).await);
        }

        let chat_response: ChatResponse = response.json().await?;
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
            .ok_or_else(|| anyhow::anyhow!("No response from xAI"))?;

        let mut result = Self::parse_response(message)?;
        result.usage = usage;
        Ok(result)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn decode_html_entities_decodes_all_five() {
        // xAI HTML-encodes the entire `arguments` string before embedding it
        // in the response JSON.  This means the structural `"` and `:` of the
        // arguments object are themselves replaced with entities, so after
        // decoding the full string is a valid JSON object.
        //
        // Wire representation of {"op":"<add>","tag":"it's","expr":"a&b"}:
        //   {&quot;op&quot;:&quot;&lt;add&gt;&quot;,&quot;tag&quot;:&quot;it&#39;s&quot;,&quot;expr&quot;:&quot;a&amp;b&quot;}
        //
        // After decoding all five entities the result must be parseable JSON.
        let encoded = concat!(
            "{&quot;op&quot;:&quot;&lt;add&gt;&quot;",
            ",&quot;tag&quot;:&quot;it&#39;s&quot;",
            ",&quot;expr&quot;:&quot;a&amp;b&quot;}",
        );
        let decoded = decode_html_entities(encoded).expect("should decode successfully");

        // Every entity must have been replaced.
        assert!(decoded.contains('"'), "should contain decoded &quot;");
        assert!(decoded.contains('<'), "should contain decoded &lt;");
        assert!(decoded.contains('>'), "should contain decoded &gt;");
        assert!(decoded.contains('\''), "should contain decoded &#39;");
        assert!(decoded.contains('&'), "should contain decoded &amp;");

        // The decoded string must be valid JSON.
        let parsed: serde_json::Value =
            serde_json::from_str(&decoded).expect("decoded result must be valid JSON");
        assert!(parsed.is_object());
        assert_eq!(parsed["op"], "<add>");
        assert_eq!(parsed["tag"], "it's");
        assert_eq!(parsed["expr"], "a&b");
    }

    #[test]
    fn decode_html_entities_rejects_oversized_input() {
        // Build a string that is exactly one byte over the limit.
        let oversized = "a".repeat(MAX_TOOL_ARGS_BYTES + 1);
        let result = decode_html_entities(&oversized);
        assert!(result.is_err(), "oversized input must be rejected");
        let msg = result.unwrap_err().to_string();
        assert!(
            msg.contains("too large"),
            "error message should mention 'too large', got: {msg}"
        );
    }

    #[test]
    fn decode_html_entities_rejects_invalid_json() {
        // After decoding this input is plain text, not JSON.
        let not_json = "hello &amp; world";
        let result = decode_html_entities(not_json);
        assert!(result.is_err(), "non-JSON input must be rejected");
        let msg = result.unwrap_err().to_string();
        assert!(
            msg.contains("not valid JSON"),
            "error message should mention 'not valid JSON', got: {msg}"
        );
    }

    #[test]
    fn decode_html_entities_passthrough_valid_json() {
        // A clean JSON string with no entities should pass through unchanged.
        let clean = r#"{"action":"jump","height":42}"#;
        let result = decode_html_entities(clean).expect("clean JSON should pass through");
        assert_eq!(result, clean);
    }

    #[test]
    fn clean_tool_schemas_strips_keywords_and_strict() {
        let mut tools = vec![json!({
            "type": "function",
            "function": {
                "name": "search",
                "description": "Searches for things",
                "strict": true,
                "parameters": {
                    "type": "object",
                    "properties": {
                        "query": {
                            "type": "string",
                            "minLength": 1,
                            "maxLength": 256,
                            "description": "The search query"
                        },
                        "tags": {
                            "type": "array",
                            "minItems": 0,
                            "maxItems": 10,
                            "minContains": 0,
                            "maxContains": 5,
                            "items": {
                                "type": "string",
                                "minLength": 1
                            }
                        }
                    },
                    "required": ["query"]
                }
            }
        })];

        clean_tool_schemas(&mut tools);

        let function = &tools[0]["function"];

        // `strict` must be removed from the function definition.
        assert!(
            function.get("strict").is_none(),
            "strict should have been removed"
        );

        let params = &function["parameters"];
        let query_props = &params["properties"]["query"];
        let tags_props = &params["properties"]["tags"];
        let tag_item_props = &tags_props["items"];

        // All six restricted keywords must be gone at every nesting level.
        for kw in STRIPPED_KEYWORDS {
            assert!(
                query_props.get(kw).is_none(),
                "query: keyword '{kw}' should have been stripped"
            );
            assert!(
                tags_props.get(kw).is_none(),
                "tags: keyword '{kw}' should have been stripped"
            );
            assert!(
                tag_item_props.get(kw).is_none(),
                "tags.items: keyword '{kw}' should have been stripped"
            );
        }
    }

    #[test]
    fn clean_tool_schemas_preserves_valid_keywords() {
        let mut tools = vec![json!({
            "type": "function",
            "function": {
                "name": "resize",
                "description": "Resizes an image",
                "parameters": {
                    "type": "object",
                    "description": "Parameters for resize",
                    "required": ["width", "height"],
                    "properties": {
                        "width": {
                            "type": "integer",
                            "minimum": 1,
                            "maximum": 8192,
                            "description": "Width in pixels"
                        },
                        "height": {
                            "type": "integer",
                            "minimum": 1,
                            "maximum": 8192,
                            "description": "Height in pixels"
                        }
                    }
                }
            }
        })];

        clean_tool_schemas(&mut tools);

        let params = &tools[0]["function"]["parameters"];
        let width = &params["properties"]["width"];
        let height = &params["properties"]["height"];

        // `minimum`, `maximum`, `description`, and `required` must survive.
        assert_eq!(width["minimum"], 1, "minimum should be preserved");
        assert_eq!(width["maximum"], 8192, "maximum should be preserved");
        assert!(
            width["description"].is_string(),
            "description should be preserved"
        );
        assert_eq!(height["minimum"], 1, "minimum should be preserved");
        assert_eq!(height["maximum"], 8192, "maximum should be preserved");
        assert!(
            params["required"].is_array(),
            "required array should be preserved"
        );
        assert!(
            params["description"].is_string(),
            "top-level description should be preserved"
        );
    }

    #[test]
    fn creates_with_key() {
        let p = XaiProvider::new(Some("xai-test-credential"));
        assert_eq!(p.credential.as_deref(), Some("xai-test-credential"));
    }

    #[test]
    fn creates_without_key() {
        let p = XaiProvider::new(None);
        assert!(p.credential.is_none());
    }

    #[test]
    fn require_credential_fails_without_key() {
        let p = XaiProvider::new(None);
        let result = p.require_credential();
        assert!(result.is_err());
        let msg = result.unwrap_err().to_string();
        assert!(
            msg.contains("xAI API key not set"),
            "error message should mention 'xAI API key not set', got: {msg}"
        );
    }

    #[test]
    fn normalized_model_name_strips_prefix() {
        assert_eq!(XaiProvider::normalized_model_name("xai/grok-4"), "grok-4");
        assert_eq!(XaiProvider::normalized_model_name("grok-4"), "grok-4");
    }
}
