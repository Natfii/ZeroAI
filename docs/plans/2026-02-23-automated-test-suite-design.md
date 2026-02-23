# Automated Test Suite Design

**Date**: 2026-02-23
**Status**: Approved

## Overview

A layered test pyramid for ZeroClaw-Android combining Compose screen tests, Maestro E2E journey flows, and real-daemon integration tests. CI gates block PRs on failure; real-daemon tests run locally against LM Studio on an RTX 5090.

## Architecture

```
                ┌───────────┐
                │  E2E Real │  ← Local/nightly only (LM Studio + 5090)
                │  Daemon   │
                └─────┬─────┘
            ┌─────────┴─────────┐
            │  Maestro Journeys │  ← PR gate (Gradle Managed Device)
            │  (YAML flows)     │
            └─────────┬─────────┘
       ┌──────────────┴──────────────┐
       │  Compose Screen Tests       │  ← PR gate (Gradle Managed Device)
       │  (androidTest + ComposeRule) │
       └──────────────┬──────────────┘
  ┌───────────────────┴───────────────────────┐
  │  Unit Tests (JVM + Rust)                  │  ← PR gate
  │  JUnit5 + MockK + Turbine (42 tests)      │
  │  cargo test -p zeroclaw-ffi (65 tests)     │
  └───────────────────────────────────────────┘
```

### Layer Summary

| Layer | Tool | Runs on | Blocks PRs | Speed |
|-------|------|---------|------------|-------|
| Unit (JVM + Rust) | JUnit5, MockK, cargo test | GitHub Actions runner | Yes | ~2 min |
| Screen | Compose Testing + ComposeTestRule | Gradle Managed Device (API 35) | Yes | ~5-8 min |
| Journey | Maestro YAML flows | Gradle Managed Device (API 35) | Yes | ~5-10 min |
| Real Daemon E2E | Maestro + real FFI + LM Studio | Local machine only | No | ~3-5 min |

## Compose Screen Tests

### Refactoring Requirement

All 6 main screens currently call `viewModel()` internally. Each must be split into:

- **Stateful wrapper**: `FooScreen(viewModel)` — collects flows, delegates to content
- **Stateless content**: `FooContent(uiState, onAction, ...)` — receives data, renders UI

Tests target the `Content` function directly with synthetic state. Onboarding already uses this collector pattern and serves as the reference.

Estimated effort: ~2-3 hours of extract-method refactoring, no logic changes.

### Screen Test Coverage

| Screen | Test File | Validates |
|--------|-----------|-----------|
| Dashboard | `DashboardScreenTest.kt` | All 4 UiState variants, status indicator colors, glance metrics, button states |
| Connections | `ConnectionsScreenTest.kt` | Agent list, search filter, FAB, empty state, swipe-to-delete |
| Plugins | `PluginsScreenTest.kt` | Tab switching, plugin cards, sync button, search |
| Console | `ConsoleScreenTest.kt` | Message input + send, response area, scroll, clear |
| Settings | `SettingsScreenTest.kt` | Setting rows render, toggles, sub-screen navigation |
| Onboarding | `OnboardingScreenTest.kt` | All 5 steps, forward/back, validation errors, completion |
| API Keys | `ApiKeysScreenTest.kt` | Masked key list, add/delete/edit dialogs |
| Lock Screen | `LockScreenTest.kt` | PIN input, biometric trigger, wrong PIN error |
| Doctor | `DoctorScreenTest.kt` | Validation checks, pass/fail indicators, retry |

## Maestro E2E Journey Flows

### PR-Gated Journeys

| Flow | File | Path |
|------|------|------|
| Onboarding | `onboarding.yaml` | Launch fresh -> terms -> provider -> API key -> model -> complete |
| Daemon lifecycle | `daemon-lifecycle.yaml` | Dashboard -> start -> verify green -> stop -> verify red |
| Agent management | `agent-management.yaml` | Connections -> add agent -> configure -> verify -> delete -> verify |
| Plugin browsing | `plugin-browsing.yaml` | Plugins -> tab switching -> search -> verify results |
| Settings round-trip | `settings-roundtrip.yaml` | Settings -> change value -> navigate away -> return -> verify |
| Console interaction | `console-interaction.yaml` | Console -> type message -> verify response area -> clear |
| Error recovery | `error-recovery.yaml` | Start with bad config -> verify error -> fix -> retry -> verify |

### Reusable Subflows

Located in `maestro/subflows/`:
- `navigate-to-tab.yaml` — navigate to any bottom nav tab
- `complete-onboarding.yaml` — full onboarding sequence for use as a prerequisite
- `start-daemon.yaml` — start daemon and verify green status

### Real-Daemon Flows (Local Only)

Require LM Studio running at `http://192.168.1.197:1234` with a Qwen model.

| Flow | File | Validates |
|------|------|-----------|
| Daemon boot + respond | `real-daemon/daemon-boot.yaml` | Onboard with test config -> start -> green status -> send message -> non-empty response |
| Sustained conversation | `real-daemon/conversation.yaml` | 3 sequential messages -> response to each -> no crashes |
| Daemon restart cycle | `real-daemon/restart-cycle.yaml` | Start -> stop -> start -> send message -> verify works |
| Error handling | `real-daemon/bad-endpoint.yaml` | Unreachable endpoint -> start -> verify error surfaces gracefully |

### Lifecycle Flows (Local Only)

| Flow | Script | Validates |
|------|--------|-----------|
| Fresh install | `test-fresh-install.sh` | Install -> launch -> onboarding appears -> no stale state |
| Upgrade from previous | `test-upgrade.sh` | Install v(N-1) -> setup -> install v(N) over top -> data migrated -> daemon works |
| Uninstall/reinstall | `test-uninstall-reinstall.sh` | Setup -> uninstall -> reinstall -> clean slate |
| Downgrade guard | `test-downgrade-guard.sh` | Install v(N) -> attempt v(N-1) -> blocked or handled gracefully |

## Test Configuration

### Gradle Managed Device

```kotlin
android {
    testOptions {
        managedDevices {
            devices {
                create<ManagedVirtualDevice>("pixel7Api35") {
                    device = "Pixel 7"
                    apiLevel = 35
                    systemImageSource = "google"
                }
            }
            groups {
                create("ci") {
                    targetDevices.add(devices.getByName("pixel7Api35"))
                }
            }
        }
    }
}
```

### Real Daemon Test Config

File: `maestro/config/test-config.toml`

```toml
[provider]
type = "openai_compatible"
endpoint = "http://192.168.1.197:1234"
model = "qwen2.5"
api_key = "lm-studio"

[router]
default_temperature = 0.7
max_tokens = 1024

[memory]
type = "in_memory"

[channel]
type = "local"
```

## CI Workflow

### New Jobs in ci.yml

```
lint-rust ──┐
lint-kotlin ├─► test (unit) ──► screen-test ──► build
cargo-deny ─┘                   maestro-test ─┘
```

| Job | Command | Timeout |
|-----|---------|---------|
| `screen-test` | `./gradlew pixel7Api35DebugAndroidTest` | 20 min |
| `maestro-test` | Install Maestro -> boot GMD -> `maestro test maestro/flows/` | 20 min |

Both depend on unit tests passing first. Both are required status checks for PR merge.

GMD system images cached via Gradle build cache. GitHub Actions ubuntu runners support KVM for hardware-accelerated emulation.

## Claude Code Hooks

### Pre-Commit Test Gate

```
PreToolUse/Bash hook:
  trigger: git commit
  script: scripts/hooks/pre-commit-test.sh
```

| Files changed | Tests run | Time |
|---------------|-----------|------|
| `zeroclaw-ffi/src/**` only | Rust unit tests | ~1 min |
| `app/src/**`, `lib/src/**` (non-UI) | JVM unit tests | ~2 min |
| `app/src/**/ui/**`, `maestro/**` | JVM unit + Compose screen tests | ~8 min |

### Pre-Release Gate

```
PreToolUse/Bash hook:
  trigger: git commit with version bump (versionName/versionCode/Cargo.toml)
  script: scripts/hooks/pre-release-test.sh
```

Runs full test pyramid: unit + screen + Maestro journeys + lifecycle tests (~15 min). Prints reminder to run `./scripts/test-real-daemon.sh` locally before pushing.

## Directory Structure

```
ZeroClaw-Android/
├── app/src/androidTest/.../screen/        # NEW - 9 Compose screen tests
│   ├── DashboardScreenTest.kt
│   ├── ConnectionsScreenTest.kt
│   ├── PluginsScreenTest.kt
│   ├── ConsoleScreenTest.kt
│   ├── SettingsScreenTest.kt
│   ├── OnboardingScreenTest.kt
│   ├── ApiKeysScreenTest.kt
│   ├── LockScreenTest.kt
│   ├── DoctorScreenTest.kt
│   └── helpers/FakeData.kt
├── maestro/                               # NEW - Maestro test root
│   ├── flows/                             # 7 PR-gated journey flows
│   │   └── real-daemon/                   # 4 local-only flows
│   ├── subflows/                          # 3 reusable fragments
│   └── config/test-config.toml
├── scripts/                               # NEW - Test runner scripts
│   ├── hooks/                             # 2 Claude Code hook scripts
│   ├── test-real-daemon.sh
│   ├── test-fresh-install.sh
│   ├── test-upgrade.sh
│   ├── test-uninstall-reinstall.sh
│   └── test-local-all.sh
├── .github/workflows/ci.yml              # Updated - 2 new jobs
└── .claude/settings.json                  # Updated - test hooks
```

## Implementation Order

1. Install Maestro on Windows (native, no WSL needed)
2. Add Gradle Managed Device config + Compose testing dependencies
3. Refactor screen composables (stateful/stateless split)
4. Write Compose screen tests (9 files)
5. Write Maestro journey flows (7 PR-gated + 3 subflows)
6. Write Maestro real-daemon flows (4 local-only)
7. Write lifecycle test scripts (4 scripts)
8. Update CI workflow (2 new jobs)
9. Add Claude Code hooks (2 hooks)
10. Update ZeroClaw submodule to latest release
