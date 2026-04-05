# Local Gemma Edge Models Integration

## Context

ZeroAI currently supports cloud-based AI providers (OpenAI, Anthropic, Gemini, etc.), Ollama for local server-based inference, and ML Kit GenAI Nano for limited on-device tasks. The goal is to allow users to download and run full Gemma edge models (2B-4B parameters) locally on their Android device using Google's LiteRT-LM inference engine — eliminating the need for Google Playground or the separate AI Edge Gallery app.

This gives users a capable local LLM with multi-turn chat, native tool calling, and multimodal support, running fully offline and private.

## Full Model Catalog

All models shipped in the catalog. We actively test against Gemma 4 (latest); older models are available for users to experiment with.

| Model | Size | Context | Peak RAM | Multimodal | License |
|-------|------|---------|----------|------------|---------|
| Gemma3-1B-IT q4 | ~555 MB | 1024 | ~2.1 GB | No | Gemma |
| Gemma-3n E2B-it-int4 | ~2.2 GB | 4096 | ~5.9 GB | Text+Vision+Audio | Gemma |
| Gemma-3n E4B-it-int4 | ~3.5 GB | 4096 | ~7 GB | Text+Vision+Audio | Gemma |
| **Gemma-4 E2B** | ~2.6 GB | 128K | TBD | Text+Vision+Audio | Apache 2.0 |
| **Gemma-4 E4B** | ~3.7 GB | 128K | TBD | Text+Vision+Audio | Apache 2.0 |

Models hosted on Hugging Face Hub (`google/` and `litert-community/` orgs) as `.task` bundles.

**Download auth:** All models are gated on HF (even Apache 2.0 Gemma 4 requires license acceptance). The Gallery app uses HF OAuth. We'll implement HF OAuth in-app so users can browse, accept, and download seamlessly without leaving ZeroAI — mirroring how the Gallery app does it.

## Architecture: Kotlin-Native with FFI Bridge

**Why Kotlin-native (not local HTTP server or Rust C FFI):**
- LiteRT-LM has first-class Kotlin/JNI bindings — fighting this adds complexity for no gain
- Avoids spinning up a local HTTP server (battery, port conflicts, lifecycle management)
- Follows the same pattern as existing ML Kit Nano integration (already Kotlin-side)
- The Rust provider system can call into Kotlin via UniFFI callbacks

### High-Level Flow

```
User sends message
    → Rust agent runtime (zeroclaw)
        → Detects "gemma-edge" provider
        → Calls Kotlin via UniFFI FFI bridge
            → GemmaEdgeInferenceService (Kotlin)
                → LiteRT-LM Engine (C++/JNI)
                    → On-device hardware (CPU/GPU/NPU)
                → Native function calling via @Tool annotations
            → Streams tokens back via callback
        → Rust receives streamed response
    → Response displayed to user
```

## Implementation Plan

### Phase 1: HuggingFace Auth & Model Download Manager (Kotlin)

**New package:** `app/src/main/java/com/zeroclaw/android/edge/`

**1a. HuggingFace OAuth integration**
- Create `HuggingFaceAuthManager.kt`
  - OAuth 2.0 flow with HF (same approach as Gallery's `ProjectConfig.kt`)
  - Store HF token in `EncryptedSharedPreferences` (reuse `SecurePrefsProvider` pattern)
  - Token refresh handling
  - License acceptance check per model

**1b. Model catalog definition**
- Create `EdgeModelCatalog.kt` — full catalog of all Gemma edge models
  - Model ID, display name, HuggingFace repo, filename, download size, RAM requirement, capabilities, license
  - Reuse existing `OnDeviceStatus` sealed interface for download state tracking
  - RAM gating: query `ActivityManager.getMemoryInfo()`, warn if device RAM < model peak requirement

**1c. Model download manager**
- Create `EdgeModelDownloadManager.kt`
  - Download `.task` files from HF Hub via authenticated HTTPS
  - Store in app-specific internal storage (`context.filesDir/edge_models/`)
  - Track download progress (reuse `OnDeviceStatus.Downloading` pattern)
  - Resume interrupted downloads (HTTP Range headers)
  - Verify file integrity (size check)
  - List/delete downloaded models

### Phase 2: LiteRT-LM Inference Service with Native Tool Calling (Kotlin)

**Files to create:**
- `app/src/main/java/com/zeroclaw/android/edge/GemmaEdgeEngine.kt`

**2a. Engine wrapper**
- Wrap LiteRT-LM `Engine` + `Conversation` lifecycle
- `loadModel(modelPath: String)` — initialize engine from downloaded `.task` file
- `chat(message: String): Flow<String>` — streaming inference via `sendMessageAsync()`
- `chatSync(message: String): String` — blocking single-response
- `resetConversation()` — clear KV-cache / start fresh
- `unloadModel()` — free resources
- Hardware acceleration: GPU preferred, CPU fallback
- Memory management: unload model when backgrounded or on low-memory

**2b. Native function calling**
- Create `EdgeToolBridge.kt`
  - Map ZeroAI's `ToolSpec` definitions to LiteRT-LM `@Tool` annotations at runtime
  - Handle `onFunctionCalled` callbacks from the model
  - Route tool call results back to the conversation
  - This gives the on-device model access to ZeroAI's full tool ecosystem (web search, file ops, etc.)

**2c. Gradle dependencies**
- Add to `app/build.gradle.kts`:
  - `com.google.ai.edge.litert:litert:2.1.0` (core runtime)
  - LiteRT-LM Kotlin bindings (verify exact Maven coordinates — may be `com.google.mediapipe:tasks-genai` or newer `com.google.ai.edge.litert-lm`)

### Phase 3: UniFFI Bridge (Rust ↔ Kotlin)

**Files to modify:**
- `zeroclaw-android/zeroclaw-ffi/` — add edge inference FFI interface

**3a. FFI interface definition**
- Callback-based interface in UniFFI:
  - `edge_load_model(model_id: String) -> Result<(), String>`
  - `edge_chat(messages: Vec<EdgeMessage>, tools: Vec<EdgeToolDef>, callback: EdgeStreamCallback) -> Result<EdgeChatResult, String>`
  - `edge_unload_model()`
  - `edge_list_models() -> Vec<EdgeModelInfo>`
  - `edge_model_status(model_id: String) -> String`
  - `edge_download_model(model_id: String, callback: EdgeDownloadCallback) -> Result<(), String>`
- `EdgeStreamCallback` trait: `on_token(delta: String)`, `on_tool_call(name: String, args: String)`, `on_complete(full_text: String)`
- `EdgeChatResult` struct: text, tool_calls vec, usage

**3b. Kotlin-side FFI implementation**
- Implement in `DaemonServiceBridge.kt` or new `EdgeServiceBridge.kt`
- Route calls to `GemmaEdgeEngine` and `EdgeModelDownloadManager`

### Phase 4: Rust Provider Integration

**Files to create/modify:**
- `zeroclaw/src/providers/gemma_edge.rs` — new provider
- `zeroclaw/src/providers/mod.rs` — register in factory

**4a. GemmaEdgeProvider**
- Implements `Provider` trait
- `chat_with_system()` / `chat_with_history()` / `chat_with_tools()` → calls FFI bridge → LiteRT-LM
- Capabilities: `native_tool_calling: true`, `vision: true` (for multimodal models)
- Model name format: `gemma-edge/gemma-4-e2b`, `gemma-edge/gemma-3n-e2b`, etc.
- Streaming support via `stream_chat_with_history()` using FFI callbacks
- Convert `ToolSpec` → `EdgeToolDef` for native function calling

**4b. Provider factory registration**
- Add `"gemma-edge"` to `create_provider()` in `mod.rs`
- No API key needed — model selection determines which `.task` file to load
- HF auth handled separately on Kotlin side

### Phase 5: Config & Management Commands

**Files to modify:**
- `zeroclaw/src/config/schema.rs` — add edge model config fields

**5a. Configuration**
- Add optional `[edge_models]` section to config schema
  - `preferred_model`: default model to load
  - `auto_unload_minutes`: timeout to free memory (default: 30)
  - `gpu_preferred`: bool (default: true)
- Allow setting `gemma-edge` as `default_provider`

**5b. Management commands**
- `/edge list` — show full catalog with download status and sizes
- `/edge download <model>` — trigger authenticated download
- `/edge delete <model>` — remove downloaded model
- `/edge status` — show loaded model, memory usage, hardware backend
- `/edge login` — trigger HF OAuth flow

## Key Existing Files to Reuse

| Pattern | Existing File | Reuse For |
|---------|---------------|-----------|
| On-device status | `app/.../model/OnDeviceStatus.kt` | Download state lifecycle |
| Provider trait | `zeroclaw/src/providers/traits.rs` | GemmaEdgeProvider implements this |
| Provider factory | `zeroclaw/src/providers/mod.rs` | Register new provider |
| Ollama provider | `zeroclaw/src/providers/ollama.rs` | Reference for chat/tool implementation |
| FFI bridge | `zeroclaw-android/zeroclaw-ffi/` | Add edge inference callbacks |
| Daemon service | `app/.../service/DaemonServiceBridge.kt` | Route FFI calls to engine |
| Config schema | `zeroclaw/src/config/schema.rs` | Add edge model config |
| Secure prefs | `app/.../data/SecurePrefsProvider.kt` | Store HF OAuth token |
| Auth profiles | `app/.../data/oauth/AuthProfileStore.kt` | Reference for OAuth flow |

## Dependencies to Add

```kotlin
// app/build.gradle.kts
implementation("com.google.ai.edge.litert:litert:2.1.0")
// + LiteRT-LM Kotlin bindings (verify exact artifact coordinates)
```

## Verification Plan

1. **Build check:** Project compiles with LiteRT-LM dependency
2. **Auth test:** HF OAuth flow completes, token stored securely
3. **Download test:** Can download Gemma-4 E2B from HuggingFace with auth
4. **Inference test:** Load Gemma-4 E2B, send "Hello, who are you?", get coherent streaming response
5. **Tool calling test:** Model invokes a tool (e.g., web_search), receives result, incorporates it
6. **Provider test:** Set `gemma-edge` as default provider, run full agent loop
7. **Memory test:** Model unloads cleanly, no leaks on repeated load/unload
8. **Lifecycle test:** Model survives app backgrounding, handles low-memory correctly

## Remaining Open Question

- **LiteRT-LM Maven coordinates:** Need to verify exact artifact name — could be `com.google.mediapipe:tasks-genai`, `com.google.ai.edge.litert-lm:litert-lm`, or something else. Will check Gallery's `build.gradle.kts` and Maven Central during implementation.
