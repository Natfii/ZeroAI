# ZeroAI

An Android AI agent app built with Kotlin, Rust, and UniFFI. Runs an AI router as an always-on Android foreground service.

> Copy this file to `CLAUDE.md` or `agents.md` and customize for your environment.

## Architecture Overview

- **Languages**: Kotlin (Android UI/service layer) + Rust (core logic via FFI)
- **FFI Bridge**: Mozilla UniFFI (v0.29-0.30) generates Kotlin bindings from Rust
- **Build**: cargo-ndk for cross-compilation, Gobley for Gradle integration
- **Targets**: `aarch64-linux-android` and `x86_64-linux-android` only
- **Output**: `libzeroclaw.so` bundled in an Android AAR

### Repository Structure

```
Zero/
  zeroclaw/              # Rust core engine (absorbed, stripped)
  zeroclaw-android/      # Cargo workspace root
    zeroclaw-ffi/        # thin UniFFI-annotated facade crate
    Cargo.toml           # workspace manifest
  app/                   # Android app module (Kotlin/Compose)
  lib/                   # Library module (Gobley/UniFFI integration, publishes AAR)
  build.gradle.kts       # root Gradle build
```

### Core FFI Surface

33 `#[uniffi::export]` free functions in `zeroclaw-ffi/src/lib.rs`, all wrapped in `catch_unwind` and returning `Result<T, FfiError>`.

### 8 Core Traits

Provider, Channel, Memory, Tool, Observer, RuntimeAdapter, Sandbox, Peripheral -- each swappable via TOML configuration.

---

## Kotlin Conventions

### Naming

- Classes/interfaces: `PascalCase` -- functions: `camelCase` -- constants: `SCREAMING_SNAKE_CASE`
- Packages: all lowercase, no underscores
- Acronyms: treated as words (`HttpClient`, not `HTTPClient`)
- No Hungarian notation (`count`, not `mCount`)
- Test functions may use backticks with spaces for JVM tests

### Formatting

- **4-space indentation**, 100-character line limit
- K&R braces, `CamelCase` for naming
- Trailing commas at declaration sites, no semicolons
- Function signatures exceeding the line limit: each parameter on its own line with +4 indent, closing parenthesis and return type on their own line at base indent
- Enforce via **ktlint** (Spotless Gradle plugin) with `.editorconfig`

### File Structure

1. Copyright header (block comment, not KDoc)
2. File-level annotations
3. Package statement, imports (ASCII-sorted, no wildcards, single group)
4. Top-level declarations -- one top-level class per file, named after class
5. Blank line separates each section

### Static Analysis

- **detekt** rules enforced:
  - `UndocumentedPublicClass`, `UndocumentedPublicFunction`, `UndocumentedPublicProperty`
  - `ForbiddenComment` with patterns: TODO, FIXME, STOPSHIP (and optionally inline `//` comments)
  - `CyclomaticComplexMethod` threshold: 15
  - `LongMethod`: 60 lines
  - `LongParameterList`: 6 params
  - `CognitiveComplexMethod`: 15
- **Android Lint** for API compatibility, security vulnerabilities, accessibility gaps

### Documentation

- **KDoc is the sole documentation mechanism** -- no inline comments anywhere
- Every public declaration requires KDoc
- Protected members in open/abstract classes require KDoc
- Internal declarations: KDoc when the purpose isn't obvious from the name
- KDoc structure: summary sentence first, blank line, then detail description, then block tags (`@param`, `@return`, `@throws`, `@sample`)
- Use `[ClassName]` bracket links to enhance readability
- Data classes: class-level KDoc for each constructor parameter individually (`@property`)
- Sealed classes: document the class itself and each subclass separately
- Extension functions: `@receiver` to document what the receiver represents
- Suspend functions: document thread safety ("safe to call from main thread"), dispatcher switching, `CancellationException` behavior
- Companion object factories: document with `@return` describing the constructed instance

---

## Rust Conventions

### Naming

- Types and traits: `UpperCamelCase` -- functions/methods: `snake_case` -- constants/statics: `SCREAMING_SNAKE_CASE`
- Modules: `snake_case` -- macros: `snake_case!`
- Getters use `get_` prefix, `is_`/`has_` for boolean predicates
- Conversions follow `as_`/`to_`/`into_` convention based on ownership semantics

### FFI Boundary -- Critical Rules

- **Strict separation** between internal Rust API and the FFI module
- The FFI module is thin -- it translates between Rust and JNI types, delegating all logic to core modules
- Use `pub(crate)` liberally to prevent accidental public exposure
- Error enums use `#[derive(Debug, thiserror::Error, uniffi::Error)]` with `#[uniffi(flat_error)]` for Kotlin exception mapping
- Every `unsafe` block must be preceded by a `// SAFETY:` comment explaining the invariants
- Every `unsafe fn` must document a `# Safety` section in its doc comment
- **Error handling across FFI must never allow Rust panics to cross the boundary**. Wrap all FFI entry points with `catch_unwind`, return `Result<T, FfiError>`, and convert caught panics to `FfiError::InternalPanic`
- Map Rust error variants to appropriate Kotlin exception classes (`IllegalArgumentException`, `IllegalStateException`, `RuntimeException`, `IOException`)
- UniFFI generates all Kotlin bindings automatically via `#[uniffi::export]` on free functions. No hand-written JNI.

### Linting & Formatting

- **Clippy** with workspace lint patterns (see clippy.toml)
- **rustfmt** with stable options (edition = "2024", max_width = 100)
- **cargo-deny** for license compliance (`deny.toml`): allow only MIT, Apache-2.0, BSD-2/3-Clause, ISC, MPL-2.0, deny copyleft and `license-not-found`.
- TLS: **rustls is strongly preferred over OpenSSL** -- pure Rust, cross-compiles without C library configuration. Enable the `rustls-tls` feature flag to eliminate the OpenSSL dependency entirely.

---

## Android Service & UI Conventions

### Foreground Service

- Service type: `specialUse` (or `connectedDevice` as fallback) -- never `dataSync` (6-hour limit per 24h on Android 15)
- Notification channel: `IMPORTANCE_LOW` (shade + status bar, no sound)
- Notification content: minimal -- one line of status, one action button, no rapidly updating metrics
- Update notification no more frequently than every 30 seconds to avoid CPU spikes
- Service must use `START_STICKY` for auto-restart after process death
- `BOOT_COMPLETED` receiver for auto-start after reboot
- `ConnectivityManager.NetworkCallback` for WiFi-to-cellular transitions
- `onTaskRemoved()` override to handle user swiping the app from recents
- Battery optimization exemption: `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
- OEM battery kill behavior: guide users to dontkillmyapp.com for manufacturer-specific instructions. Show banner only on affected devices (Xiaomi, Samsung, Huawei, OnePlus).

### UI Framework

- **Jetpack Compose** with Material Design 3 (`MaterialTheme`)
- Dynamic Color (`Material You`) on Android 12+, with static fallback scheme
- M3 type scale: 15 styles, line heights in **sp not dp** (critical for Android 14+ nonlinear font scaling)
- **8dp baseline grid**: spacing values 4, 8, 12, 16, 24, 32, 48dp
- Edge margins: 16dp on compact, 24dp on medium/expanded
- Touch targets: minimum **48x48dp**
- `NavigationSuiteScaffold` for adaptive navigation (bottom bar < 600dp, rail 600-840dp, drawer 840dp+)
- Edge-to-edge display mandatory (SDK 35): `enableEdgeToEdge()`, `WindowInsets` handling, `Scaffold` with `innerPadding`
- Predictive back gesture: `android:enableOnBackInvokedCallback="true"` in manifest

### Navigation

- Single `NavHost` with typed routes (`@Serializable` route classes, Navigation Compose 2.8+)
- Service status: colored dot indicator in top bar visible on all screens (green/amber/red)
- 5 primary destinations in bottom navigation

### State Management

- Every screen: sealed interface `UiState<T>` with `Loading`, `Error(message, retry)`, `Empty`, `Content(data)` variants
- `StateFlow` from ViewModels, collected with `collectAsStateWithLifecycle`
- `derivedStateOf` for throttling high-frequency state into low-frequency UI updates
- For `LazyColumn`/`LazyRow`: always provide `key` and `contentType` parameters

### Battery-Conscious Rendering

- Defer state reads to layout/draw phase (not composition) -- this is the single most impactful optimization
- Composition -> Layout -> Drawing pipeline: use `Modifier.drawBehind` for cheapest possible updates
- Strong Skipping Mode enabled (Kotlin 2.0.20+)
- Baseline Profiles for ~30% startup time improvement
- **Coil** for image loading (~95KB, Kotlin-first, coroutine-native)
- Disable/simplify animations when battery saver is active: check `PowerManager.isPowerSaveMode`
- Use `EnterTransition.None`/`ExitTransition.None` as replacements under power save
- Prefer `graphicsLayer.alpha/scale/translationX` transforms (GPU-executed, no view invalidation)
- Infinite animations: always provide static fallbacks (Samsung Battery Guardian sets `ANIMATOR_DURATION_SCALE` to 0)
- Batch network requests, use `WorkManager` with network constraints for non-urgent work
- FCM for server-initiated updates instead of polling
- Wake lock discipline: foreground service manages its own wake lock, avoid manual wake locks from UI. Threshold is 2+ hours of partial wake locks in 24 hours.

### Accessibility

- Target **WCAG 2.2 Level AA**
- Every interactive element needs `contentDescription` and proper role assignment via `Modifier.semantics`
- API key fields: use `invisibleToUser()` on masked text, provide separate content description
- Status indicators: color is never the only differentiator -- also use text labels and distinct icon shapes
- Toggle switches: announce both item name and state
- Minimum contrast ratio: 4.5:1 for normal text, 3:1 for large text (AA)
- Support font scaling to 200%: use `sp` for both text size and line height
- `LiveRegionMode.Polite` for dynamic status updates (never `LiveRegionMode.Assertive` except for critical errors)

### Security

- API keys: stored in `EncryptedSharedPreferences` backed by `MasterKey` with `AES256_GCM`
- On devices with hardware security modules (StrongBox), master key is hardware-backed
- Keys are masked by default (last 4 chars visible), biometric auth required to reveal
- Rust layer accesses decrypted keys via FFI only when the service needs them -- never stored in Rust-side persistent storage
- Export/import: encrypted JSON file with Argon2 key derivation

---

## Git & Versioning

- **Conventional Commits**: `type(scope): description` -- types: feat, fix, refactor, test, docs, ci, build, chore
- **SemVer**: `MAJOR.MINOR.PATCH` with breaking changes spelled out: `feat!:` or `BREAKING CHANGE:` footer
- Two independent version tracks: App SemVer (Kotlin/Compose) and Crate SemVer (Rust library)
- Pre-commit hooks: ktlint format (staged .kt files), detekt (staged), clippy (+D warnings), cargo-deny
