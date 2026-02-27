# Onboarding Redesign: Full CLI Parity with Interactive Channel Sub-Flows

**Date:** 2026-02-26
**Status:** Approved
**Motivation:** Open issues #2 and #5 show users cannot navigate onboarding. The current 5-step wizard lacks inline guidance, live validation, and deep links. ZeroClaw's CLI has a comprehensive 9-step interactive wizard with per-channel walkthroughs that we need to match.

---

## Goals

1. Achieve full parity with ZeroClaw CLI's 9-step `onboard --interactive` wizard
2. Add Android-specific steps (permissions, battery, OEM kill protection)
3. Interactive channel sub-flows with deep links and live token validation
4. All setup flows reusable from both onboarding AND Settings screens
5. Live validation for every provider API key and channel token

## Non-Goals

- Hardware/GPIO step (feature flag disabled on Android)
- Changes to the FFI layer (pure Kotlin/UI work)
- Changes to the upstream ZeroClaw submodule

---

## Step Mapping: CLI to Android

| # | CLI Step | Android Step | Status |
|---|----------|-------------|--------|
| 0 | *(none)* | **Permissions & Security** | Existing (keep) |
| 1 | Workspace Setup | **Welcome & Workspace** | New |
| 2 | AI Provider & API Key | **Provider Setup** | Enhanced |
| 3 | Channels | **Channel Selection + Sub-Flows** | Enhanced + New |
| 4 | Tunnel | **Tunnel (Optional)** | New |
| 5 | Tool Mode & Security | **Security & Autonomy** | New |
| 6 | *(Hardware -- skipped)* | *(skipped)* | N/A |
| 7 | Memory Configuration | **Memory Setup** | New |
| 8 | Project Context | **Agent Identity** | Enhanced |
| 9 | Workspace Files | **Activation & Summary** | Enhanced |

**Total: 9 top-level steps (0-8) + nested channel sub-flows within Step 3.**

---

## Architecture: Modular Wizard with Reusable Sub-Flows

### Design Principle

Every setup screen in onboarding MUST also work standalone in Settings. The onboarding wizard is a **coordinator** that sequences reusable setup flows.

### Reusable Components

| Component | Onboarding Step | Settings Screen |
|-----------|----------------|-----------------|
| `ProviderSetupFlow` | Step 2 | API Keys, Service Config |
| `ChannelSetupFlow` (per type) | Step 3 sub-flow | Connected Channels > Add/Edit |
| `ChannelSelectionGrid` | Step 3 | Connected Channels > Add New |
| `TunnelConfigFlow` | Step 4 | Gateway & Pairing |
| `AutonomyPicker` | Step 5 | Security Overview |
| `MemoryConfigFlow` | Step 7 | Memory Advanced |
| `IdentityConfigFlow` | Step 8 | Agent Identity |
| `ConfigSummaryCard` | Step 9 | Dashboard status card |

### Shared Validation Layer

```
validation/
  ProviderValidator.kt    -- API key probe, returns ValidationResult
  ChannelValidator.kt     -- Per-channel token validation
  ModelFetcher.kt         -- Existing, reuse as-is
  ValidationResult.kt     -- sealed: Success(details), Failure(message, retryable), Loading
```

### Shared Utilities

```
util/
  ExternalAppLauncher.kt  -- Deep links to Telegram, Discord, provider consoles
```

---

## Step Details

### Step 0: Permissions & Security (existing, keep as-is)

- Notification permission (Android 13+)
- Battery optimization exemption
- Optional PIN setup (PBKDF2)
- No changes needed

### Step 1: Welcome & Workspace (new)

- Explain what ZeroClaw is (1-2 sentences)
- Show what the workspace will contain (file tree preview)
- "This wizard will help you set up your AI agent" messaging
- Single "Get Started" button

### Step 2: Provider Setup (enhanced)

Current behavior: dropdown + key field + base URL + model dropdown.

Enhancements:
- **Live API key validation** after paste/entry (async, 10s timeout)
  - Success: green checkmark, "Connected to {provider}" message
  - Failure: red X, specific error ("Invalid key", "Key revoked", "Endpoint unreachable")
  - Offline: yellow warning, "Could not verify -- you can continue and validate later"
- **Deep link to provider console** for key creation (e.g., "Don't have a key? [Get one here]")
- **Live model list** fetched on successful validation (existing ModelFetcher)
- **Key format hints** (existing keyPrefix/keyPrefixHint)
- Network scanner for local providers stays as-is

Provider validation endpoints:

| Provider | Endpoint | Auth |
|----------|----------|------|
| OpenAI | `GET /v1/models` | Bearer token |
| Anthropic | `GET /v1/models` | x-api-key header |
| OpenRouter | `GET /api/v1/models` | Bearer token |
| Google Gemini | `GET /v1beta/models?key={key}` | Query param |
| Ollama | `GET /api/tags` | None |
| LM Studio | `GET /v1/models` | Optional Bearer |
| vLLM | `GET /v1/models` | Optional Bearer |
| Custom | `GET {baseUrl}/v1/models` | Optional Bearer |

### Step 3: Channel Selection + Sub-Flows (major enhancement)

**3a: Channel Selection Grid**

Multi-select card grid showing all available channel types with:
- Channel icon + name
- Status indicator: not configured / configured / validated
- Tap to select/deselect
- "Skip" option prominent -- "You can add channels later in Settings"

**3b: Per-Channel Interactive Sub-Flow**

After selection, each chosen channel gets an interactive step-by-step wizard. The main progress bar shows "Step 3 of 9" while the channel sub-flow shows its own sub-step progress.

#### Telegram Sub-Flow (3 sub-steps)

**Sub-step 1: Create a Telegram Bot**
1. Numbered instructions explaining @BotFather flow
2. [Open @BotFather] button -- deep link: `tg://resolve?domain=BotFather`
3. Paste bot token field
4. [Validate] button -- calls `GET https://api.telegram.org/bot{token}/getMe`
   - Success: "Connected as @YourBotName" with green checkmark
   - Failure: "Invalid token" with retry, field highlighted red

**Sub-step 2: Allow Your Telegram Account**
1. Explanation: "Your bot needs to know who can talk to it"
2. [Get My User ID] button -- deep link: `tg://resolve?domain=userinfobot`
3. Explanation: "Message this bot, it will reply with your numeric ID"
4. Paste user ID / username field
5. [Add Another] for multiple users
6. Warning if allowlist empty or contains "*"

**Sub-step 3: Advanced Settings (Optional, collapsible)**
- Stream mode: polling (default) vs streaming
- Draft update interval: 1000ms default
- Interrupt on new message: off by default
- All pre-filled with sensible defaults

#### Discord Sub-Flow (3 sub-steps)

**Sub-step 1: Create a Discord Bot**
1. Instructions for Discord Developer Portal
2. [Open Developer Portal] -- deep link: `https://discord.com/developers/applications`
3. Paste bot token
4. [Validate] -- calls `GET https://discord.com/api/v10/users/@me` with Bearer token
   - Success: "Connected as BotName#1234"
   - Failure: "Invalid token"

**Sub-step 2: Add Bot to Server**
1. Instructions for OAuth2 invite URL + required permissions
2. Guild ID field with "How to find this" expandable hint
3. Allowed users list

**Sub-step 3: Advanced Settings (Optional)**
- listen_to_bots toggle (default: off)

#### Slack Sub-Flow (3 sub-steps)

**Sub-step 1: Create a Slack App**
1. Instructions for Slack App creation
2. [Open Slack App Console] -- `https://api.slack.com/apps`
3. Bot token (xoxb-...) and App token (xapp-...) fields
4. [Validate] -- calls `POST https://slack.com/api/auth.test` with Bearer
   - Success: "Connected to {team} as {bot_name}"

**Sub-step 2: Configure Channel**
1. Channel ID field with instructions
2. Allowed users list

**Sub-step 3: Advanced Settings (Optional)**
- Thread replies toggle, etc.

#### Other Channels

Each channel type follows the same pattern:
1. **Create/configure the external service** (with deep link + instructions)
2. **Paste credentials** (with live validation where possible)
3. **Configure access control** (allowed users/numbers/senders)
4. **Advanced settings** (optional, collapsible, sensible defaults)

Channels without live validation APIs (IRC, Email, Signal, etc.) skip the validate button and show a "Will be verified when daemon starts" message.

### Step 4: Tunnel (new, optional)

- Default: "Local Only" (no tunnel)
- Options: None, Ngrok, Cloudflare Tunnel, Custom endpoint
- If Ngrok selected: auth token field
- If Cloudflare: tunnel token field
- Skippable with prominent "Skip" action

### Step 5: Security & Autonomy (new)

- Autonomy level picker with explanations:
  - **Supervised** (default): "Agent asks before taking actions"
  - **Constrained**: "Agent acts within defined boundaries"
  - **Unconstrained**: "Agent acts freely" (with warning)
- Visual cards with icon + description for each level

### Step 6: Memory Setup (new)

- Backend picker: SQLite (default, recommended), Markdown, None
  - Lucid excluded unless upstream adds Android support
- Auto-save toggle (default: on)
- Embedding provider: None (default), OpenAI, Anthropic
  - If selected, reuses the API key from Step 2 if provider matches
- Conversation retention: slider or preset (7/30/90 days, Forever)

### Step 7: Agent Identity (enhanced)

Current: just agent name.

Enhanced to match CLI's Step 8:
- Agent name (required) -- existing
- Your name (optional) -- for personalization in workspace files
- Timezone picker (auto-detected from device, editable)
- Communication style (optional): Professional, Casual, Concise, Detailed
- Identity format: OpenClaw (default) or AIEOS

### Step 8: Activation & Summary (enhanced)

Matches CLI's `print_summary` output:

```
Configuration Summary
  Provider: openrouter
  Model: anthropic/claude-sonnet-4-6
  Autonomy: Supervised
  Memory: sqlite (auto-save: on)
  Channels: Telegram (@YourBot), Discord (BotName)
  Tunnel: none (local only)
  Identity: OpenClaw
  Agent: "ZeroClaw"
```

- Each line has a checkmark (validated) or warning icon (not validated)
- [Start Daemon] button
- Progress spinner during workspace scaffold + config write + daemon start
- On success: navigate to Dashboard

---

## State Management

### OnboardingCoordinator (ViewModel)

Single ViewModel that coordinates the flow:

```
OnboardingCoordinator
  currentStep: StateFlow<Int>              (0-8)
  canProceed: StateFlow<Boolean>           (derived from current step validity)
  isCompleting: StateFlow<Boolean>         (final step guard)
  permissionsState: PermissionsStepState
  welcomeState: WelcomeStepState
  providerState: ProviderStepState
  channelSelectionState: ChannelSelectionState
  channelSubFlowStates: Map<ChannelType, ChannelSubFlowState>
  tunnelState: TunnelStepState
  securityState: SecurityStepState
  memoryState: MemoryStepState
  identityState: IdentityStepState
  activationState: ActivationStepState
```

Each `*StepState` is a plain data class (not a ViewModel):
- Lightweight, testable, serializable
- Saved to `SavedStateHandle` for process death recovery
- Validation logic is pure functions on the state

### Process Death Recovery

All step states serialize to `SavedStateHandle`. On restoration, the wizard returns to the last completed step. Partially-filled fields are restored.

---

## Validation Design

### ValidationResult (sealed interface)

```kotlin
sealed interface ValidationResult {
    data object Idle : ValidationResult
    data object Loading : ValidationResult
    data class Success(val details: String) : ValidationResult
    data class Failure(val message: String, val retryable: Boolean) : ValidationResult
    data class Offline(val message: String) : ValidationResult
}
```

### Validation Behavior

- **Async**: runs on IO dispatcher via coroutine
- **Timeout**: 10 seconds per validation call
- **Offline-tolerant**: validation failure shows warning but does NOT block progression
- **Debounced**: provider key validation debounces 500ms after typing stops
- **Cancellable**: navigating away cancels in-flight validation

### ProviderValidator

Injected via Hilt. Used by:
- Onboarding Step 2
- API Keys screen (existing)
- Service Config screen (existing)

### ChannelValidator

Injected via Hilt. Per-channel validation logic:
- Telegram: `GET https://api.telegram.org/bot{token}/getMe`
- Discord: `GET https://discord.com/api/v10/users/@me` (Bearer)
- Slack: `POST https://slack.com/api/auth.test` (Bearer)
- Matrix: `GET {homeserver}/_matrix/client/v3/account/whoami` (Bearer)
- Others: connection test or "verified on daemon start" message

Used by:
- Onboarding Step 3 channel sub-flows
- Connected Channels > Add/Edit (existing Settings screen)

---

## Deep Link Map

| Target | URI | Used In |
|--------|-----|---------|
| Telegram @BotFather | `tg://resolve?domain=BotFather` | Telegram sub-step 1 |
| Telegram @userinfobot | `tg://resolve?domain=userinfobot` | Telegram sub-step 2 |
| Discord Developer Portal | `https://discord.com/developers/applications` | Discord sub-step 1 |
| Slack App Console | `https://api.slack.com/apps` | Slack sub-step 1 |
| OpenAI API Keys | `https://platform.openai.com/api-keys` | Provider step |
| Anthropic Console | `https://console.anthropic.com/settings/keys` | Provider step |
| OpenRouter Keys | `https://openrouter.ai/keys` | Provider step |
| Google AI Studio | `https://aistudio.google.com/apikey` | Provider step |

All deep links go through `ExternalAppLauncher.kt` which handles:
- `tg://` scheme fallback to `https://t.me/` if Telegram not installed
- `intent://` fallback for apps not installed
- Browser fallback for all HTTPS links

---

## Channel Setup Spec Model

```kotlin
data class ChannelSetupSpec(
    val channelType: ChannelType,
    val steps: List<ChannelSetupStepSpec>,
)

data class ChannelSetupStepSpec(
    val title: String,
    val instructions: List<InstructionItem>,
    val deepLink: DeepLink?,
    val fields: List<ChannelFieldSpec>,
    val validator: ValidatorType?,
    val optional: Boolean = false,
)

sealed interface InstructionItem {
    data class Text(val content: String) : InstructionItem
    data class NumberedStep(val number: Int, val content: String) : InstructionItem
    data class Warning(val content: String) : InstructionItem
    data class Hint(val content: String, val expandable: Boolean = false) : InstructionItem
}

data class DeepLink(
    val label: String,
    val uri: String,
    val fallbackUri: String? = null,
    val icon: ImageVector? = null,
)

enum class ValidatorType {
    TELEGRAM_BOT_TOKEN,
    DISCORD_BOT_TOKEN,
    SLACK_BOT_TOKEN,
    MATRIX_ACCESS_TOKEN,
    GENERIC_HTTP_PROBE,
}
```

Adding a new channel's guided flow = adding a new `ChannelSetupSpec` instance. No new composables needed.

---

## File Structure (new/modified)

### New Files (~25-30)

```
app/src/main/java/com/zeroclaw/android/
  ui/
    component/
      setup/
        ProviderSetupFlow.kt          -- reusable provider config + validation
        ChannelSelectionGrid.kt        -- reusable channel multi-select
        ChannelSetupFlow.kt            -- reusable per-channel sub-flow renderer
        TunnelConfigFlow.kt            -- reusable tunnel config
        AutonomyPicker.kt              -- reusable autonomy level selector
        MemoryConfigFlow.kt            -- reusable memory backend config
        IdentityConfigFlow.kt          -- reusable identity config
        ConfigSummaryCard.kt           -- reusable config summary display
        ValidationIndicator.kt         -- reusable validation state indicator
        InstructionsList.kt            -- reusable numbered instructions renderer
        DeepLinkButton.kt              -- reusable external app link button
    screen/
      onboarding/
        OnboardingScreen.kt            -- MODIFIED: expanded step navigation
        OnboardingCoordinator.kt       -- NEW: replaces OnboardingViewModel
        WelcomeStep.kt                 -- NEW
        SecurityStep.kt                -- NEW (wraps AutonomyPicker)
        ActivationStep.kt              -- MODIFIED: add config summary
        PermissionsStep.kt             -- EXISTING (no changes)
        state/
          OnboardingStepStates.kt      -- NEW: all step state data classes
  data/
    validation/
      ProviderValidator.kt             -- NEW (extracted from ModelFetcher logic)
      ChannelValidator.kt              -- NEW
      ValidationResult.kt              -- NEW
    channel/
      ChannelSetupSpecs.kt             -- NEW: all channel setup spec definitions
  util/
    ExternalAppLauncher.kt             -- NEW
```

### Modified Files

```
  ui/screen/onboarding/
    OnboardingScreen.kt                -- Expand from 5 to 9 steps
    ProviderStep.kt                    -- Extract to ProviderSetupFlow, keep as thin wrapper
    AgentConfigStep.kt                 -- Extract to IdentityConfigFlow, keep as thin wrapper
    ChannelSetupStep.kt                -- Extract to ChannelSelectionGrid + ChannelSetupFlow
    ActivationStep.kt                  -- Add config summary display
  ui/screen/settings/
    ConnectedChannelsScreen.kt         -- Use ChannelSetupFlow for add/edit
    ApiKeysScreen.kt                   -- Use ProviderValidator for inline validation
    ServiceConfigScreen.kt             -- Use shared components where applicable
```

---

## Migration Strategy

1. Extract existing logic into reusable components first (no behavior change)
2. Wire reusable components into existing Settings screens (verify nothing breaks)
3. Expand onboarding wizard with new steps
4. Add channel sub-flows
5. Add live validation
6. Add deep links
7. Test full flow end-to-end

This ordering ensures we never break existing functionality while building toward the new flow.

---

## Testing Strategy

- Unit tests for all validators (mock HTTP responses)
- Unit tests for ChannelSetupSpec completeness (every ChannelType has a spec)
- Unit tests for state serialization/deserialization (process death)
- Instrumented tests for deep link resolution
- Manual E2E test: fresh install through full onboarding with real Telegram bot

---

## Open Questions (resolved during design)

1. **Hardware step?** -- Skipped (feature flag disabled on Android)
2. **Tunnel step?** -- Included as optional, default "Local Only"
3. **Identity format?** -- OpenClaw default, AIEOS available in identity step
4. **Validation blocking?** -- No, validation failures warn but don't block progression
5. **Process death?** -- SavedStateHandle preserves all step state
