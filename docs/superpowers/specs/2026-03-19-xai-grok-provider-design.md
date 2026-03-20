# Add xAI (Grok) as a Provider

**Date:** 2026-03-19
**Status:** Approved

## Summary

Add xAI (Grok) as a dedicated provider with its own `xai.rs` implementation. xAI uses the OpenAI Chat Completions wire format (`https://api.x.ai/v1`) but has three tool-calling quirks that require xAI-specific pre/post-processing: HTML entity decoding on tool call arguments, JSON Schema keyword stripping, and `strict` flag removal. Auth is API key only. Default model: `grok-4`.

## Decisions

| Decision | Choice |
|----------|--------|
| Implementation approach | Dedicated `xai.rs` (not reusing `openai.rs`) |
| Wire protocol | OpenAI Chat Completions (`/v1/chat/completions`) |
| Auth type | API key only (Bearer token) |
| Env var | `XAI_API_KEY` |
| Default model | `grok-4` (matches OpenClaw) |
| Key prefix | `xai-` |
| Aliases | `"grok"` |
| Tool quirk handling | All three (HTML decode, schema strip, strict removal) |
| Vision | Supported (OpenAI-compatible multipart content format) |
| Image generation | Out of scope (separate endpoint, not part of chat) |
| Responses API | Out of scope (using Chat Completions only) |
| Native web/x search tools | Out of scope (Responses API only) |

## Research Sources

- **xAI API docs**: `https://docs.x.ai` — OpenAI-compatible Chat Completions, tool calling, vision, streaming
- **OpenClaw** (`extensions/xai/`): Full plugin with stream wrappers for HTML entity decoding, schema keyword stripping, `strict` flag removal. Default model `grok-4`.
- **ZeroClaw upstream** (`zeroclaw-labs/zeroclaw`): xAI handled via `OpenAiCompatibleProvider` with match arms `"xai" | "grok"`, env var `XAI_API_KEY`. No dedicated module. Was gutted from ZeroAI when `compatible.rs` was removed.

## Rust Layer

### 1. New file: `zeroclaw/src/providers/xai.rs`

Dedicated provider implementing the `Provider` trait. Structure:

```rust
pub struct XaiProvider {
    credential: Option<String>,
    custom_headers: Option<HashMap<String, String>>,
}
```

**Capabilities:**
```rust
ProviderCapabilities {
    native_tool_calling: true,
    vision: true,
}
```

**Constants:**
```rust
const XAI_BASE_URL: &str = "https://api.x.ai/v1";
const XAI_DEFAULT_MODEL: &str = "grok-4";
```

**Trait methods to implement:**
- `chat_with_system` — standard Chat Completions request
- `chat_with_history` — multi-turn with message array
- `chat` — simple single-turn
- `chat_with_tools` — with tool definitions (apply schema cleaning before send, HTML decode on response)
- `stream_chat_with_system` — SSE streaming

**xAI-specific processing (3 quirks):**

1. **Outgoing tool schema cleaning** — Before sending any request with tools, strip these JSON Schema keywords from all tool parameter schemas:
   - `minLength`, `maxLength`, `minItems`, `maxItems`, `minContains`, `maxContains`
   - Also strip the `strict` boolean from function tool definitions
   - Implement as a helper: `fn clean_tool_schemas(tools: &mut Vec<serde_json::Value>)`

2. **Incoming tool call argument decoding** — After receiving a response with tool calls, decode HTML entities in `function.arguments` strings:
   - `&quot;` → `"`
   - `&amp;` → `&`
   - `&lt;` → `<`
   - `&gt;` → `>`
   - `&#39;` → `'`
   - Implement as a helper: `fn decode_html_entities(s: &str) -> String`
   - **Security**: Apply a max-length guard (e.g., 1 MiB) before decoding to prevent unbounded allocation from malformed responses. After decoding, validate that the result parses as `serde_json::Value` and return an error if it does not, rather than letting malformed JSON propagate silently to the tool dispatcher.

3. **Extended timeouts for reasoning models** — Reasoning models (`grok-4`, `*-reasoning`) can take significantly longer. Use a generous HTTP client timeout (300s vs the typical 120s).

**Request format** — identical to OpenAI Chat Completions:
```json
{
  "model": "grok-4",
  "messages": [
    {"role": "system", "content": "..."},
    {"role": "user", "content": "..."}
  ],
  "temperature": 0.7,
  "stream": false,
  "tools": [...]
}
```

**Vision format** — identical to OpenAI:
```json
{
  "role": "user",
  "content": [
    {"type": "text", "text": "What's in this image?"},
    {"type": "image_url", "image_url": {"url": "data:image/jpeg;base64,..."}}
  ]
}
```

### 2. Factory match arm (`providers/mod.rs`)

Add to `create_provider_with_url_and_options()`, matching existing pattern:

```rust
"xai" | "grok" => Ok(Box::new(
    xai::XaiProvider::new(key).with_custom_headers(headers),
)),
```

Where `key` comes from `resolve_provider_credential(name, api_key)` and `headers` from `options.custom_headers.clone()`, matching the existing factory arms.

### 3. Module declaration (`providers/mod.rs`)

Add `pub mod xai;` alongside the existing provider modules.

### 4. Provider list (`providers/mod.rs`)

Add entry to `list_providers()`:
```rust
ProviderInfo {
    name: "xai",
    display_name: "xAI (Grok)",
    aliases: &["grok"],
    local: false,
}
```

Rename `list_providers_returns_five_entries` test to `list_providers_returns_six_entries` and update assertion count (5 → 6).

### 5. Credential resolution (`providers/mod.rs`)

Add to `resolve_provider_credential()`:
```rust
"xai" | "grok" => vec!["XAI_API_KEY"],
```

### 6. Default model fallback (`config/schema.rs`)

Add `"xai" | "grok"` arm to `effective_model()` returning `"xai/grok-4"` (using the `provider/model` prefix convention matching existing arms like `"openai/gpt-4.1"`, `"gemini/gemini-2.5-flash"`).

### 7. Error messages

- xAI-specific error message when credential is missing: `"xAI API key not set. Set XAI_API_KEY or configure in settings."` — avoids the misleading "OpenAI API key" message that would occur if reusing `openai.rs`.
- Update the unknown provider error string in the factory to include `xai` in the "Supported: ..." list.

### 8. Secret scrubbing (`providers/mod.rs`)

Add `"xai-"` to the `PREFIXES` array in `scrub_secret_patterns()` so that xAI API keys are redacted from error messages and logs.

### 9. Integration registry (`integrations/registry.rs`)

Add xAI entry to `all_integrations()` as `IntegrationCategory::AiModel` so it appears in the health/integrations list, matching the pattern of all other cloud providers.

### 10. Proxy service keys (`config/schema.rs`)

Add `"provider.xai"` to `SUPPORTED_PROXY_SERVICE_KEYS` alongside the other cloud providers (`provider.anthropic`, `provider.gemini`, `provider.ollama`, `provider.openai`). Without this, users who configure proxy routing per-provider will not be able to select xAI in the proxy services UI.

## Kotlin Layer

**Dependency note:** `ProviderRegistry` entry must be added before `ProviderSlotRegistry` entry — the slot registry's `init` block validates that every slot's `providerRegistryId` resolves in the registry. Adding the slot first will crash the app on startup.

### 11. ProviderRegistry

New `ProviderInfo` entry:

- `id = "xai"`
- `displayName = "xAI (Grok)"`
- `authType = API_KEY_ONLY`
- `category = PRIMARY`
- `aliases = listOf("grok")`
- `suggestedModels = listOf("grok-4", "grok-4-1-fast-reasoning", "grok-4-1-fast-non-reasoning")`
- `keyPrefix = "xai-"`
- `keyPrefixHint = "xAI keys typically start with xai-"`
- `keyCreationUrl = "https://console.x.ai"`
- `modelListFormat = OPENAI_COMPATIBLE` (xAI's `/v1/models` endpoint follows OpenAI format)
- `modelListUrl = "https://api.x.ai/v1/models"`
- `helpText = "Get your API key from the xAI Console"`
- `iconUrl` = Google Favicon API for `x.ai`

### 12. ProviderSlotRegistry

New slot:
- Display: "xAI API"
- `credentialType = SlotCredentialType.API_KEY`
- `rustProvider = "xai"`
- `providerRegistryId = "xai"`
- `baseOrder = 7` (OpenRouter is 5, Ollama is 6 — bump Ollama to 8, insert xAI at 7 so Ollama stays last as the only local provider)
- Update `ProviderSlotRegistryTest` count assertion (6 → 7)

### 13. ProviderIcon brand color

Add `"xai"` entry to `PROVIDER_BRAND_COLORS` in `ProviderIcon.kt`. xAI's brand uses black/white — use a neutral dark color that works on both light and dark themes.

### 14. ConfigTomlBuilder

`resolveProvider()` already passes through unknown cloud provider names — `"xai"` will fall through with no changes needed. Add a test case for coverage to confirm.

## Tests

### Rust

| File | Change |
|------|--------|
| `zeroclaw/src/providers/xai.rs` | New: `test_clean_tool_schemas` — verify 6 keywords + `strict` flag stripped |
| `zeroclaw/src/providers/xai.rs` | New: `test_decode_html_entities` — verify all 5 entity types decoded |
| `zeroclaw/tests/provider_resolution.rs` | New: `factory_xai` and `factory_grok` — verify both aliases resolve |
| `zeroclaw/src/providers/mod.rs` | Rename `list_providers_returns_five_entries` → `list_providers_returns_six_entries`, assert 6 |

### Kotlin

| File | Change |
|------|--------|
| `ProviderRegistryTest.kt` | Add `"xai"` to hardcoded provider ID lists in icon, model list, key creation URL, and help text tests |
| `ProviderSlotRegistryTest.kt` | Update count assertion (6 → 7) |
| `ConfigTomlBuilderTest.kt` | Add `"xai"` to existing `resolveProvider` passthrough test group |

## Not In Scope

- **Responses API** (`/v1/responses`) — newer xAI API surface with stateful conversations, server-side tools. Not needed; Chat Completions covers all ZeroAI use cases.
- **Native web_search / x_search** — server-side tools only available via Responses API.
- **Image generation** (`grok-imagine-image`) — separate endpoint and billing, not part of chat flow.
- **Video generation** (`grok-imagine-video`) — separate endpoint.
- **OAuth / profile auth** — xAI is API key only.
- **Deferred completions** — async batch feature, not relevant for interactive chat.
- **Prompt caching** — automatic on xAI's side, no client-side changes needed.
