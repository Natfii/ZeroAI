# xAI (Grok) Provider Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add xAI (Grok) as a dedicated provider with tool-calling quirk handling, integrated into both Rust engine and Kotlin UI.

**Architecture:** Dedicated `xai.rs` implementing the `Provider` trait using xAI's OpenAI-compatible Chat Completions endpoint (`https://api.x.ai/v1`). Three xAI-specific preprocessors: outbound tool schema cleaning, inbound HTML entity decoding, and `strict` flag removal. Kotlin side gets registry + slot entries.

**Tech Stack:** Rust (async_trait, reqwest, serde_json), Kotlin (Jetpack Compose, JUnit 5)

**Spec:** `docs/superpowers/specs/2026-03-19-xai-grok-provider-design.md`

---

## File Map

| Action | File | Responsibility |
|--------|------|---------------|
| Create | `zeroclaw/src/providers/xai.rs` | xAI provider: struct, helpers, trait impl, unit tests |
| Modify | `zeroclaw/src/providers/mod.rs:12-17` | Add `pub mod xai;` |
| Modify | `zeroclaw/src/providers/mod.rs:95-103` | Add `"xai-"` to scrub prefixes |
| Modify | `zeroclaw/src/providers/mod.rs:182-188` | Add `"xai" \| "grok"` credential resolution |
| Modify | `zeroclaw/src/providers/mod.rs:272-321` | Add factory match arm |
| Modify | `zeroclaw/src/providers/mod.rs:347-351` | Update error message |
| Modify | `zeroclaw/src/providers/mod.rs:424-457` | Add to `list_providers()` |
| Modify | `zeroclaw/src/providers/mod.rs:476-484` | Update test name + assertion |
| Modify | `zeroclaw/src/config/schema.rs:15-29` | Add `"provider.xai"` to proxy keys |
| Modify | `zeroclaw/src/config/schema.rs:3488-3493` | Add `effective_model()` arm |
| Modify | `zeroclaw/src/integrations/registry.rs:99-111` | Add xAI integration entry |
| Modify | `zeroclaw/tests/provider_resolution.rs` | Add xAI + grok factory tests |
| Modify | `app/.../data/ProviderRegistry.kt:166-186` | Add xAI ProviderInfo entry |
| Modify | `app/.../data/ProviderSlotRegistry.kt:106-124` | Add xAI slot, bump Ollama order |
| Modify | `app/.../ui/component/ProviderIcon.kt:50-65` | Add xAI brand color |
| Modify | `app/.../data/ProviderRegistryTest.kt` | Add `"xai"` to hardcoded lists |
| Modify | `app/.../data/ProviderSlotRegistryTest.kt:17` | Update count 6→7 |
| Modify | `app/.../service/ConfigTomlBuilderTest.kt:905-908` | Add `"xai"` passthrough test |

---

### Task 1: xAI Tool Quirk Helpers + Tests

**Files:**
- Create: `zeroclaw/src/providers/xai.rs`

This task creates the xAI-specific helper functions and their tests, before the full provider struct.

- [ ] **Step 1: Write the failing tests for `decode_html_entities`**

In `zeroclaw/src/providers/xai.rs`:

```rust
// Copyright (c) 2026 @Natfii. All rights reserved.

//! xAI (Grok) provider — dedicated Chat Completions implementation.
//!
//! Uses the OpenAI-compatible wire format at `https://api.x.ai/v1` with
//! three xAI-specific quirks handled in pre/post-processing:
//! 1. Strip unsupported JSON Schema keywords from outgoing tool schemas
//! 2. Decode HTML entities in incoming tool call arguments
//! 3. Remove `strict` flag from function tool definitions

/// Maximum tool call arguments size (1 MiB) before HTML entity decoding.
const MAX_TOOL_ARGS_BYTES: usize = 1_048_576;

/// Default model for xAI when none is specified.
#[allow(dead_code)]
const XAI_DEFAULT_MODEL: &str = "grok-4";

/// Decodes the five HTML entities xAI may inject into tool call argument strings.
///
/// Returns an error if `input` exceeds [`MAX_TOOL_ARGS_BYTES`] or if the
/// decoded result is not valid JSON.
fn decode_html_entities(input: &str) -> anyhow::Result<String> {
    anyhow::ensure!(
        input.len() <= MAX_TOOL_ARGS_BYTES,
        "tool call arguments exceed {MAX_TOOL_ARGS_BYTES} byte limit"
    );
    let decoded = input
        .replace("&quot;", "\"")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&#39;", "'");
    // Validate the decoded string is valid JSON.
    let _: serde_json::Value = serde_json::from_str(&decoded)
        .map_err(|e| anyhow::anyhow!("decoded tool arguments are not valid JSON: {e}"))?;
    Ok(decoded)
}

/// Strips JSON Schema keywords unsupported by xAI and the `strict` flag
/// from function tool definitions.
///
/// Modifies tools in-place. Keywords removed from `parameters` objects:
/// `minLength`, `maxLength`, `minItems`, `maxItems`, `minContains`, `maxContains`.
/// The `strict` boolean is removed from `function` objects.
fn clean_tool_schemas(tools: &mut Vec<serde_json::Value>) {
    const UNSUPPORTED_KEYWORDS: &[&str] = &[
        "minLength",
        "maxLength",
        "minItems",
        "maxItems",
        "minContains",
        "maxContains",
    ];

    for tool in tools.iter_mut() {
        // Strip `strict` from function definition
        if let Some(func) = tool.get_mut("function") {
            if let Some(obj) = func.as_object_mut() {
                obj.remove("strict");
            }
            // Strip unsupported keywords from parameters (recursive)
            if let Some(params) = func.get_mut("parameters") {
                strip_keywords_recursive(params, UNSUPPORTED_KEYWORDS);
            }
        }
    }
}

/// Recursively strip named keys from a JSON value and its nested objects.
fn strip_keywords_recursive(value: &mut serde_json::Value, keywords: &[&str]) {
    if let Some(obj) = value.as_object_mut() {
        for kw in keywords {
            obj.remove(*kw);
        }
        // Recurse into `properties` (object schemas)
        if let Some(props) = obj.get_mut("properties") {
            if let Some(props_obj) = props.as_object_mut() {
                for (_, v) in props_obj.iter_mut() {
                    strip_keywords_recursive(v, keywords);
                }
            }
        }
        // Recurse into `items` (array schemas)
        if let Some(items) = obj.get_mut("items") {
            strip_keywords_recursive(items, keywords);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn decode_html_entities_decodes_all_five() {
        let input = r#"{&quot;name&quot;: &quot;O&amp;M&quot;, &quot;ok&quot;: &quot;&lt;3&gt;&quot;, &quot;q&quot;: &quot;it&#39;s&quot;}"#;
        let result = decode_html_entities(input).unwrap();
        let parsed: serde_json::Value = serde_json::from_str(&result).unwrap();
        assert_eq!(parsed["name"], "O&M");
        assert_eq!(parsed["ok"], "<3>");
        assert_eq!(parsed["q"], "it's");
    }

    #[test]
    fn decode_html_entities_rejects_oversized_input() {
        let huge = "x".repeat(MAX_TOOL_ARGS_BYTES + 1);
        assert!(decode_html_entities(&huge).is_err());
    }

    #[test]
    fn decode_html_entities_rejects_invalid_json() {
        let input = "not json at all";
        assert!(decode_html_entities(input).is_err());
    }

    #[test]
    fn decode_html_entities_passthrough_valid_json() {
        let input = r#"{"key": "value"}"#;
        let result = decode_html_entities(input).unwrap();
        assert_eq!(result, input);
    }

    #[test]
    fn clean_tool_schemas_strips_keywords_and_strict() {
        let mut tools: Vec<serde_json::Value> = vec![serde_json::json!({
            "type": "function",
            "function": {
                "name": "test_fn",
                "description": "A test function",
                "strict": true,
                "parameters": {
                    "type": "object",
                    "properties": {
                        "name": {
                            "type": "string",
                            "minLength": 1,
                            "maxLength": 100
                        },
                        "items": {
                            "type": "array",
                            "minItems": 1,
                            "maxItems": 10,
                            "items": {
                                "type": "string",
                                "minLength": 1
                            }
                        }
                    }
                }
            }
        })];

        clean_tool_schemas(&mut tools);

        let func = &tools[0]["function"];
        // strict should be removed
        assert!(func.get("strict").is_none());
        // top-level property keywords removed
        let name_prop = &func["parameters"]["properties"]["name"];
        assert!(name_prop.get("minLength").is_none());
        assert!(name_prop.get("maxLength").is_none());
        assert_eq!(name_prop["type"], "string"); // type preserved
        // array property keywords removed
        let items_prop = &func["parameters"]["properties"]["items"];
        assert!(items_prop.get("minItems").is_none());
        assert!(items_prop.get("maxItems").is_none());
        // nested items keywords removed
        let nested = &items_prop["items"];
        assert!(nested.get("minLength").is_none());
    }

    #[test]
    fn clean_tool_schemas_preserves_valid_keywords() {
        let mut tools: Vec<serde_json::Value> = vec![serde_json::json!({
            "type": "function",
            "function": {
                "name": "test_fn",
                "description": "desc",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "count": {
                            "type": "integer",
                            "minimum": 0,
                            "maximum": 100,
                            "description": "a count"
                        }
                    },
                    "required": ["count"]
                }
            }
        })];

        clean_tool_schemas(&mut tools);

        let count = &tools[0]["function"]["parameters"]["properties"]["count"];
        assert_eq!(count["minimum"], 0);
        assert_eq!(count["maximum"], 100);
        assert_eq!(count["description"], "a count");
        // required preserved at parameters level
        assert!(tools[0]["function"]["parameters"]["required"].is_array());
    }
}
```

Note: Constructor and credential tests (`creates_with_key`, `creates_without_key`, `require_credential_fails_without_key`) will be added in Task 2 after the struct is defined.

- [ ] **Step 2: Run tests to verify they compile and pass**

Run: `cd /c/Users/Natal/Github/Zero/zeroclaw && cargo test --lib providers::xai::tests -- --nocapture`

Note: Tests should pass immediately since the helpers and tests are in the same file. This is TDD for the helpers that will be called by the provider implementation in Task 2.

- [ ] **Step 3: Commit**

```bash
git add zeroclaw/src/providers/xai.rs
git commit -m "feat(providers): add xAI tool quirk helpers with tests

Implements decode_html_entities (with 1 MiB guard + JSON validation)
and clean_tool_schemas (strips 6 unsupported keywords + strict flag).
These helpers handle xAI's three documented tool-calling quirks."
```

---

### Task 2: xAI Provider Struct + Trait Implementation

**Files:**
- Modify: `zeroclaw/src/providers/xai.rs` (add struct + trait impl above `#[cfg(test)]`)

- [ ] **Step 1: Add the provider struct and constructors**

Add above `#[cfg(test)]` in `xai.rs`:

```rust
use crate::providers::traits::{
    ChatMessage, ChatResponse as ProviderChatResponse, Provider, ProviderCapabilities,
    ToolCall as ProviderToolCall,
};
use crate::tools::ToolSpec;
use async_trait::async_trait;
use reqwest::Client;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;

const XAI_BASE_URL: &str = "https://api.x.ai/v1";
const XAI_DEFAULT_TIMEOUT_SECS: u64 = 300;

/// xAI (Grok) provider using the Chat Completions API.
///
/// Wraps the OpenAI-compatible wire format with xAI-specific
/// tool schema cleaning and HTML entity decoding.
pub struct XaiProvider {
    credential: Option<String>,
    custom_headers: Option<HashMap<String, String>>,
}

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

#[derive(Debug, Serialize)]
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

impl XaiProvider {
    /// Creates a new xAI provider with the given API key.
    pub fn new(credential: Option<&str>) -> Self {
        Self {
            credential: credential.map(ToString::to_string),
            custom_headers: None,
        }
    }

    /// Sets custom HTTP headers for all API requests.
    pub fn with_custom_headers(mut self, headers: Option<HashMap<String, String>>) -> Self {
        self.custom_headers = headers;
        self
    }

    /// Strips the `provider/` prefix from model names (e.g., `"xai/grok-4"` → `"grok-4"`).
    fn normalized_model_name(model: &str) -> &str {
        model.rsplit('/').next().unwrap_or(model)
    }

    /// Converts trait-level ChatMessages to xAI wire-format messages.
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

    /// Converts trait-level ToolSpecs to xAI tool JSON, cleaning schemas.
    fn convert_and_clean_tools(tools: Option<&[ToolSpec]>) -> Option<Vec<serde_json::Value>> {
        tools.map(|items| {
            let mut tool_values: Vec<serde_json::Value> = items
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
            clean_tool_schemas(&mut tool_values);
            tool_values
        })
    }

    /// Parses a native xAI response message into the trait-level response,
    /// applying HTML entity decoding to tool call arguments.
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

    fn http_client(&self) -> Client {
        crate::config::build_runtime_proxy_client_with_timeouts(
            "provider.xai",
            XAI_DEFAULT_TIMEOUT_SECS,
            10,
        )
    }

    fn require_credential(&self) -> anyhow::Result<&str> {
        self.credential.as_deref().ok_or_else(|| {
            anyhow::anyhow!(
                "xAI API key not set. Set XAI_API_KEY or configure in settings."
            )
        })
    }
}
```

- [ ] **Step 2: Add the Provider trait implementation**

Append below the `impl XaiProvider` block, still above `#[cfg(test)]`:

```rust
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
        let model_name = Self::normalized_model_name(model);

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
            model: model_name.to_string(),
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
            .ok_or_else(|| anyhow::anyhow!("xAI returned empty response"))
    }

    async fn chat_with_history(
        &self,
        messages: &[ChatMessage],
        model: &str,
        temperature: f64,
    ) -> anyhow::Result<String> {
        let credential = self.require_credential()?;
        let model_name = Self::normalized_model_name(model);
        let native_messages = Self::convert_messages(messages);

        let request = ChatRequest {
            model: model_name.to_string(),
            messages: native_messages,
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
            .ok_or_else(|| anyhow::anyhow!("xAI returned empty response"))
    }

    async fn chat_with_tools(
        &self,
        messages: &[ChatMessage],
        tools: &[serde_json::Value],
        model: &str,
        temperature: f64,
    ) -> anyhow::Result<ProviderChatResponse> {
        let credential = self.require_credential()?;
        let model_name = Self::normalized_model_name(model);
        let native_messages = Self::convert_messages(messages);

        // Parse raw tool JSON values into ToolSpecs, then convert and clean
        let tool_specs: Vec<crate::tools::ToolSpec> = tools
            .iter()
            .filter_map(|v| serde_json::from_value(v.clone()).ok())
            .collect();
        let cleaned_tools = Self::convert_and_clean_tools(Some(&tool_specs));

        let request = ChatRequest {
            model: model_name.to_string(),
            messages: native_messages,
            temperature,
            tools: cleaned_tools,
            tool_choice: Some("auto".to_string()),
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
        let message = chat_response
            .choices
            .into_iter()
            .next()
            .map(|c| c.message)
            .ok_or_else(|| anyhow::anyhow!("xAI returned empty response"))?;

        Self::parse_response(message)
    }

    // Streaming: uses the default trait implementation (returns empty stream).
    // xAI streaming uses the same SSE format as OpenAI, but the existing
    // OpenAI provider doesn't export a reusable SSE helper. Streaming support
    // can be added in a follow-up by extracting the SSE parser into a shared
    // utility. The default trait impl returns an empty stream, which the
    // caller handles gracefully by falling back to non-streaming.
}
```

Note: `stream_chat_with_system` and `stream_chat_with_history` use the default trait implementations (empty stream / error message). Streaming can be added later when an SSE parser is extracted from the OpenAI provider.

Also remove the unused imports (`StreamChunk`, `StreamOptions`, `StreamResult`, `futures::{stream, StreamExt}`) from the import block if not used elsewhere in the file.

- [ ] **Step 3: Add constructor and credential unit tests**

Append to the existing `mod tests` block in `xai.rs`:

```rust
    #[test]
    fn creates_with_key() {
        let provider = XaiProvider::new(Some("xai-test-key"));
        assert!(provider.credential.is_some());
    }

    #[test]
    fn creates_without_key() {
        let provider = XaiProvider::new(None);
        assert!(provider.credential.is_none());
    }

    #[test]
    fn require_credential_fails_without_key() {
        let provider = XaiProvider::new(None);
        let result = provider.require_credential();
        assert!(result.is_err());
        let msg = result.unwrap_err().to_string();
        assert!(msg.contains("xAI API key not set"));
    }

    #[test]
    fn normalized_model_name_strips_prefix() {
        assert_eq!(XaiProvider::normalized_model_name("xai/grok-4"), "grok-4");
        assert_eq!(XaiProvider::normalized_model_name("grok-4"), "grok-4");
    }
```

- [ ] **Step 4: Verify compilation**

Run: `cd /c/Users/Natal/Github/Zero/zeroclaw && cargo check --lib`
Expected: Compiles (the module isn't registered in mod.rs yet, but file-level syntax is checked).

- [ ] **Step 5: Commit**

```bash
git add zeroclaw/src/providers/xai.rs
git commit -m "feat(providers): add xAI provider struct and trait implementation

Implements Provider trait with chat_with_system, chat_with_history,
and chat_with_tools (with schema cleaning + HTML decode).
Streaming deferred until SSE parser is extracted from OpenAI provider."
```

---

### Task 3: Wire xAI into Rust Provider Factory

**Files:**
- Modify: `zeroclaw/src/providers/mod.rs`

- [ ] **Step 1: Add module declaration**

At `mod.rs:17`, after `pub mod openai;`:

```rust
pub mod xai;
```

- [ ] **Step 2: Add `"xai-"` to scrub_secret_patterns**

At `mod.rs:95`, change array size from 7 to 8 and add the prefix:

```rust
const PREFIXES: [&str; 8] = [
    "sk-",
    "xoxb-",
    "xoxp-",
    "ghp_",
    "gho_",
    "ghu_",
    "github_pat_",
    "xai-",
];
```

- [ ] **Step 3: Add credential resolution**

At `mod.rs:187`, add before `_ => vec![],`:

```rust
"xai" | "grok" => vec!["XAI_API_KEY"],
```

- [ ] **Step 4: Add factory match arm**

At `mod.rs:305`, after the `"openrouter"` arm and before `name if name.starts_with("custom:")`:

```rust
"xai" | "grok" => Ok(Box::new(
    xai::XaiProvider::new(key).with_custom_headers(headers),
)),
```

- [ ] **Step 5: Update error message**

At `mod.rs:348`, update the `Supported:` list:

```rust
"Unknown provider: {name}. Supported: openai, anthropic, gemini, ollama, openrouter, xai.\n\
```

- [ ] **Step 6: Add to `list_providers()`**

At `mod.rs:449`, before the Ollama entry, add:

```rust
ProviderInfo {
    name: "xai",
    display_name: "xAI (Grok)",
    aliases: &["grok"],
    local: false,
},
```

- [ ] **Step 7: Update test**

At `mod.rs:476`, rename test and update assertion:

```rust
#[test]
fn list_providers_returns_six_entries() {
    let providers = list_providers();
    assert_eq!(providers.len(), 6);
    assert!(providers.iter().any(|p| p.name == "anthropic"));
    assert!(providers.iter().any(|p| p.name == "openai"));
    assert!(providers.iter().any(|p| p.name == "gemini"));
    assert!(providers.iter().any(|p| p.name == "openrouter"));
    assert!(providers.iter().any(|p| p.name == "xai"));
    assert!(providers.iter().any(|p| p.name == "ollama"));
}
```

Also add a scrub test:

```rust
#[test]
fn scrub_secret_patterns_redacts_xai_prefix() {
    let input = "Error: invalid key xai-abc123xyz456";
    let result = scrub_secret_patterns(input);
    assert!(!result.contains("xai-abc123xyz456"));
    assert!(result.contains("[REDACTED]"));
}
```

- [ ] **Step 8: Verify compilation**

Run: `cd /c/Users/Natal/Github/Zero/zeroclaw && cargo check --lib`
Expected: Compiles successfully.

Run: `cd /c/Users/Natal/Github/Zero/zeroclaw && cargo test --lib providers::tests -- --nocapture`
Expected: All tests pass.

- [ ] **Step 9: Commit**

```bash
git add zeroclaw/src/providers/mod.rs zeroclaw/src/providers/xai.rs
git commit -m "feat(providers): wire xAI into provider factory

Adds module declaration, factory match arm (xai|grok), credential
resolution for XAI_API_KEY, secret scrubbing for xai- prefix,
list_providers entry, and updated error message."
```

---

### Task 4: Config Schema + Integration Registry

**Files:**
- Modify: `zeroclaw/src/config/schema.rs:15-29` and `:3488-3493`
- Modify: `zeroclaw/src/integrations/registry.rs:99-111`

- [ ] **Step 1: Add proxy service key**

At `schema.rs:19`, after `"provider.openai",`:

```rust
"provider.xai",
```

- [ ] **Step 2: Add effective_model arm**

At `schema.rs:3491`, before `_ =>`:

```rust
"xai" | "grok" => "xai/grok-4",
```

- [ ] **Step 3: Add integration registry entry**

At `registry.rs:111`, after the OpenRouter entry:

```rust
IntegrationEntry {
    name: "xAI",
    description: "Grok 4 models",
    category: IntegrationCategory::AiModel,
    status_fn: |c| {
        if c.default_provider.as_deref() == Some("xai")
            || c.default_provider.as_deref() == Some("grok")
        {
            IntegrationStatus::Active
        } else {
            IntegrationStatus::Available
        }
    },
},
```

- [ ] **Step 4: Verify compilation**

Run: `cd /c/Users/Natal/Github/Zero/zeroclaw && cargo check --lib`
Expected: Compiles.

- [ ] **Step 5: Commit**

```bash
git add zeroclaw/src/config/schema.rs zeroclaw/src/integrations/registry.rs
git commit -m "feat(config): add xAI to proxy keys, effective_model, and integration registry"
```

---

### Task 5: Rust Integration Tests

**Files:**
- Modify: `zeroclaw/tests/provider_resolution.rs`

- [ ] **Step 1: Add factory resolution tests**

Append after the alias resolution section (after line 59):

```rust
#[test]
fn factory_resolves_xai_provider() {
    assert_provider_ok("xai", Some("test-key"), None);
}

#[test]
fn factory_grok_alias_resolves_to_xai() {
    assert_provider_ok("grok", Some("test-key"), None);
}
```

- [ ] **Step 2: Run all Rust tests**

Run: `cd /c/Users/Natal/Github/Zero/zeroclaw && cargo test -- --nocapture`
Expected: All tests pass, including new xAI tests and updated list_providers test.

- [ ] **Step 3: Run clippy**

Run: `cd /c/Users/Natal/Github/Zero/zeroclaw && cargo clippy -- -D warnings`
Expected: No warnings.

- [ ] **Step 4: Commit**

```bash
git add zeroclaw/tests/provider_resolution.rs
git commit -m "test(providers): add xAI and grok alias factory resolution tests"
```

---

### Task 6: Kotlin ProviderRegistry + ProviderIcon

**Files:**
- Modify: `app/src/main/java/com/zeroclaw/android/data/ProviderRegistry.kt:166-186`
- Modify: `app/src/main/java/com/zeroclaw/android/ui/component/ProviderIcon.kt:50-65`

- [ ] **Step 1: Add xAI to ProviderRegistry**

In `ProviderRegistry.kt`, in `primaryProviders()`, add after the OpenRouter entry (line 166) and before the Ollama entry:

```kotlin
ProviderInfo(
    id = "xai",
    displayName = "xAI (Grok)",
    authType = ProviderAuthType.API_KEY_ONLY,
    suggestedModels =
        listOf(
            "grok-4",
            "grok-4-1-fast-reasoning",
            "grok-4-1-fast-non-reasoning",
        ),
    aliases = listOf("grok"),
    category = ProviderCategory.PRIMARY,
    iconUrl = faviconUrl("x.ai"),
    modelListUrl = "https://api.x.ai/v1/models",
    modelListFormat = ModelListFormat.OPENAI_COMPATIBLE,
    keyCreationUrl = "https://console.x.ai",
    keyPrefix = "xai-",
    keyPrefixHint = "xAI keys typically start with xai-",
    helpText = "Get your API key from the xAI Console",
),
```

- [ ] **Step 2: Add xAI brand color to ProviderIcon**

In `ProviderIcon.kt`, after `COLOR_OPENROUTER` (line 50):

```kotlin
/** Brand color for xAI. */
private const val COLOR_XAI = 0xFF1D1D1F.toLong()
```

And in the `PROVIDER_BRAND_COLORS` map (line 64), add before the closing paren:

```kotlin
"xai" to Color(COLOR_XAI),
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/zeroclaw/android/data/ProviderRegistry.kt
git add app/src/main/java/com/zeroclaw/android/ui/component/ProviderIcon.kt
git commit -m "feat(ui): add xAI to ProviderRegistry and ProviderIcon brand colors"
```

---

### Task 7: Kotlin ProviderSlotRegistry

**Files:**
- Modify: `app/src/main/java/com/zeroclaw/android/data/ProviderSlotRegistry.kt:106-124`

**IMPORTANT:** This must be done AFTER Task 6 — the `init` block validates that `providerRegistryId` resolves in `ProviderRegistry`.

- [ ] **Step 1: Add xAI slot and bump Ollama order**

In `ProviderSlotRegistry.kt`, after the OpenRouter slot (line 114) and before the Ollama slot:

```kotlin
ProviderSlot(
    slotId = "xai-api",
    displayName = "xAI API",
    credentialType = SlotCredentialType.API_KEY,
    baseOrder = 7,
    rustProvider = "xai",
    authProfileProvider = null,
    providerRegistryId = "xai",
),
```

Update Ollama's `baseOrder` from `6` to `8` (line 119).

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/zeroclaw/android/data/ProviderSlotRegistry.kt
git commit -m "feat(ui): add xAI provider slot, bump Ollama to order 8"
```

---

### Task 8: Kotlin Test Updates

**Files:**
- Modify: `app/src/test/java/com/zeroclaw/android/data/ProviderRegistryTest.kt`
- Modify: `app/src/test/java/com/zeroclaw/android/data/ProviderSlotRegistryTest.kt`
- Modify: `app/src/test/java/com/zeroclaw/android/service/ConfigTomlBuilderTest.kt`

- [ ] **Step 1: Update ProviderRegistryTest**

In `ProviderRegistryTest.kt`:

Add to the `expectedWithIcons` list (line 113-119):
```kotlin
"xai",
```

Add to the `expectedWithModelList` map (line 131-137):
```kotlin
"xai" to ModelListFormat.OPENAI_COMPATIBLE,
```

Add to the `expectedWithUrl` list (line 154-158):
```kotlin
"xai",
```

Add to the `priorityIds` list (line 191-196):
```kotlin
"xai",
```

Add a new test for xAI specifics:
```kotlin
@Test
@DisplayName("xai has API_KEY_ONLY auth type and correct prefix")
fun `xai has API_KEY_ONLY auth type and correct prefix`() {
    val provider = ProviderRegistry.findById("xai")
    assertNotNull(provider)
    assertEquals(ProviderAuthType.API_KEY_ONLY, provider!!.authType)
    assertEquals("xai-", provider.keyPrefix)
    assertEquals(ModelListFormat.OPENAI_COMPATIBLE, provider.modelListFormat)
    assertTrue(provider.oauthClientId.isEmpty())
}

@Test
@DisplayName("grok alias resolves to xai")
fun `grok alias resolves to xai`() {
    val grok = ProviderRegistry.findById("grok")
    assertNotNull(grok)
    assertEquals("xai", grok!!.id)
}
```

- [ ] **Step 2: Update ProviderSlotRegistryTest**

In `ProviderSlotRegistryTest.kt:17`, change `6` to `7`:
```kotlin
assertEquals(7, slots.size)
```

Add a new test:
```kotlin
@Test
fun resolvesXaiSlot() {
    assertEquals("xai-api", ProviderSlotRegistry.resolveSlotId("xai", false))
    assertNull(ProviderSlotRegistry.resolveSlotId("xai", true))
}
```

- [ ] **Step 3: Update ConfigTomlBuilderTest**

In `ConfigTomlBuilderTest.kt:908`, add after the openrouter assertion:
```kotlin
assertEquals("xai", ConfigTomlBuilder.resolveProvider("xai", ""))
```

- [ ] **Step 4: Run Kotlin tests**

Run: `cd /c/Users/Natal/Github/Zero && ./gradlew app:testDebugUnitTest --tests "com.zeroclaw.android.data.ProviderRegistryTest" --tests "com.zeroclaw.android.data.ProviderSlotRegistryTest" --tests "com.zeroclaw.android.service.ConfigTomlBuilderTest"`
Expected: All tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/test/java/com/zeroclaw/android/data/ProviderRegistryTest.kt
git add app/src/test/java/com/zeroclaw/android/data/ProviderSlotRegistryTest.kt
git add app/src/test/java/com/zeroclaw/android/service/ConfigTomlBuilderTest.kt
git commit -m "test(ui): add xAI to all Kotlin provider test assertions"
```

---

### Task 9: Full Build Verification

- [ ] **Step 1: Run full Rust test suite**

Run: `cd /c/Users/Natal/Github/Zero/zeroclaw && cargo test -- --nocapture`
Expected: All tests pass.

- [ ] **Step 2: Run Rust lints**

Run: `cd /c/Users/Natal/Github/Zero/zeroclaw && cargo clippy -- -D warnings && cargo fmt --check`
Expected: No warnings, no formatting issues.

- [ ] **Step 3: Run full Kotlin test suite**

Run: `cd /c/Users/Natal/Github/Zero && ./gradlew app:testDebugUnitTest`
Expected: All tests pass.

- [ ] **Step 4: Run Kotlin lints**

Run: `cd /c/Users/Natal/Github/Zero && ./gradlew spotlessCheck detekt`
Expected: No issues.

- [ ] **Step 5: Verify Gradle build**

Run: `cd /c/Users/Natal/Github/Zero && ./gradlew assembleDebug`
Expected: Build succeeds.
