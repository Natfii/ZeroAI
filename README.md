# Zero

![banner](https://github.com/user-attachments/assets/eca832d2-c90b-4aed-867b-06d69cc19a7f)



<p align="center">                                                                                                                                                                                                            <img alt="Platform" src="https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white"/>
    <img alt="Min SDK" src="https://img.shields.io/badge/min%20SDK-28-brightgreen"/>                                                                                                                                 
    <img alt="Target SDK" src="https://img.shields.io/badge/target%20SDK-35-brightgreen"/>
    <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin&logoColor=white"/>
    <img alt="Rust" src="https://img.shields.io/badge/Rust-FFI-DEA584?logo=rust&logoColor=black"/>
    <img alt="Jetpack Compose" src="https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white"/>
    <img alt="License" src="https://img.shields.io/badge/license-Custom-lightgrey"/>
</p>

<p align="center">
    <img alt="UniFFI" src="https://img.shields.io/badge/bridge-UniFFI-blueviolet"/>
    <img alt="Providers" src="https://img.shields.io/badge/providers-OpenAI%20%7C%20Anthropic%20%7C%20Gemini%20%7C%20xAI%20%7C%20DeepSeek%20%7C%20Qwen%20%7C%20Ollama%20%7C%20OpenRouter-blue"/>
    <img alt="Channels" src="https://img.shields.io/badge/channels-Telegram%20%7C%20Discord%20%7C%20Email%20%7C%20Messages%20%7C%20CLI-blue"/>
  </p>

**Zero** is an Android AI agent app built with Kotlin, Rust, and UniFFI. It runs a
long-lived on-device service, exposes tools through a native [Zeroclaw] Rust core, and provides a Compose UI for configuring and operating the agent.


<p align="Center"><img src="https://github.com/user-attachments/assets/429db2eb-602b-4696-a414-46dc8dd744e0" alt="Zero screenshots" width="30%" /> <img src="https://github.com/user-attachments/assets/f32adefc-98d3-4772-9824-27c602f04c80" alt="Zero screenshots" width="30%" /> <img src="https://github.com/user-attachments/assets/85b797be-0b92-45ff-9394-0ada640fa7b7" alt="Zero screenshots" width="30%" /> </p>

## Project status

- Experimental and actively evolving.
- Built for Android 9+ and validated most heavily on recent Pixel hardware. Other
  devices and OEM builds may need additional validation.
- Large portions of the project were created with AI-assisted tooling and are still being
  audited and hardened.
- Public collaboration and support are not available as this is a personal project. I just want you all
  along for the journey!

The Rust core under `zeroclaw/` is a stripped and modified descendant of the upstream
ZeroClaw project:

- [https://github.com/zeroclaw-labs/zeroclaw](https://github.com/zeroclaw-labs/zeroclaw)

They deserve credit for the core runtime work. If you like what powers this app, please
support the upstream project directly.

Please do **not** file issues or support requests with ZeroClaw Labs for behavior that
comes from this downstream Android fork.

If you run this project, validate it against your own device, accounts, files, data, and
connected services before trusting it with anything important.

<p align="center">
  <img src="assets/mini-zero-peek.svg" alt="Zero peeking into the repo" width="52" />
</p>

## What Zero is

Zero is built for people who want an agent that feels personal, local-first, and always available on Android. The goal is for the app to feel less like a generic assistant shell and more like a home for your **in-app Zero**:

- **Android app UI** in Kotlin + Jetpack Compose
- **Native agent core** in Rust
- **UniFFI bridge** connecting Kotlin and Rust safely
- **Foreground daemon/service model** for persistent agent execution
- **Tooling, channels, memory, scheduling, and plugins** under one roof

Zero is designed to be private by default, configurable, and capable of running as more than a chat window. Your in-app Zero should be able to search, remember, route, schedule, and act through a native mobile-first stack.

<p align="center">
  <img src="assets/mini-zero-typing.svg" alt="Zero typing" width="40" />
  <img src="assets/mini-zero-smiling.svg" alt="Zero smiling" width="40" />
</p>

## Current capabilities

### Providers

- OpenAI
- Anthropic
- Google Gemini
- xAI (Grok)
- DeepSeek
- Qwen (Alibaba DashScope) — International, China, and US regional endpoints
- Ollama
- OpenRouter

### Channels

- Telegram
- Discord
- Email
- Google Messages
- in-app Terminal / REPL

That means your in-app Zero can live inside the app, speak through connected channels, and keep working through the daemon/runtime model.

### Built-in tooling

- web search
- web fetch
- HTTP requests
- vision / multimodal support
- smart message routing + provider cascade
- Twitter/X browsing via authenticated cookies
- **eval_script** — sandboxed Rhai scripting (agent writes and runs scripts during its own reasoning)

### Core systems

- agent config + routing
- memory backends
- cron / scheduling
- Rhai scripting engine with 24-capability security model
- plugin management (Hub: Apps, Skills, Plugins)
- ClawBoy — AI-played Game Boy emulator
- Android-native settings and service controls

Together, these systems make the in-app Zero more than a front-end character - they give Zero an actual runtime, memory, tools, and operational surface.

<p align="center">
  <img src="assets/mini-zero-success.svg" alt="Zero success" width="40" />
  <img src="assets/mini-zero-love.svg" alt="Zero love" width="40" />
  <img src="assets/mini-zero-idle.svg" alt="Zero idle" width="40" />
</p>

## Usage

### Terminal & REPL

The **Terminal** tab is your in-app command center. Type messages to talk to your Zero, or use slash commands:

- `/help` — list available commands
- `/nano <prompt>` — on-device Gemini Nano inference
- `/cost` — current session cost summary
- `@tty` — switch to the full TTY terminal mode

### SSH from your phone

Once in TTY mode (`@tty`), connect to any SSH server:

```
/ssh user@hostname
/ssh user@hostname -p 2222
```

Zero handles host key verification (TOFU), password and keyboard-interactive auth, and renders the remote session with a GPU-accelerated VT terminal (powered by [libghostty-vt](https://github.com/ghostty-org/ghostty)). The extra key row provides Tab, Ctrl, Esc, Alt, arrow keys, and Enter for comfortable terminal use on a touchscreen.

Manage SSH keys in **Settings > SSH Keys** (generate Ed25519/RSA, import from file, copy public key).

### Channels

Connect your Zero to external channels so it can respond on your behalf:

- **Telegram** — link a bot token, Zero replies in your Telegram chats
- **Discord** — link a bot token, Zero joins your Discord servers
- **Email** — IMAP/SMTP, Zero reads and drafts email responses
- **Google Messages** — experimental Bugle protocol bridge

Configure channels in **Hub > Apps**.

### ClawBoy

A Game Boy emulator that your Zero plays autonomously. Start a game by chatting "play pokemon" in the Terminal, Telegram, or Discord. Watch the AI make decisions in real-time through the Hub viewer.

## Architecture

Zero is split into a few major parts:

- `app/` - Android app, Compose UI, service orchestration, settings, plugin screens
- `zeroclaw/` - Rust core: tools, memory, config, runtime, channels, gateway
- `zeroclaw-android/zeroclaw-ffi/` - UniFFI bridge layer exported to Kotlin
- `lib/` - Android library packaging for native bindings
- `scripts/` - hooks, test helpers, release utilities

The Android layer owns UX, secret storage, and lifecycle for the in-app Zero.
The Rust layer owns the agent runtime, tools, config parsing, and execution engine that power Zero underneath.

<p align="center">
  <img src="assets/mini-zero-peek.svg" alt="Zero peek" width="38" />
  <img src="assets/mini-zero-typing.svg" alt="Zero typing" width="38" />
  <img src="assets/mini-zero-sleeping.svg" alt="Zero sleeping" width="38" />
</p>

## Upstream ancestry

The `zeroclaw/` Rust core in this repository is a **stripped and modified** version of the upstream project at:

- [https://github.com/zeroclaw-labs/zeroclaw](https://github.com/zeroclaw-labs/zeroclaw)

For the `zeroclaw/` directory, this repo uses the upstream **MIT license option**.

## Why...?

Zero is not just "an Android chat app." It is an attempt to build a full agent platform around a native mobile runtime, with the in-app Zero at the center of the experience:

- **private-first** secret handling and local settings
- **Rust core** for safety and portability
- **Android-native UX** instead of a thin web wrapper
- **persistent service model** for always-on workflows
- **extensible tool/plugin surface** for adding real capabilities over time

<p align="center">
  <img src="assets/mini-zero-angry.svg" alt="Zero angry" width="38" />
  <img src="assets/mini-zero-error.svg" alt="Zero error" width="38" />
  <img src="assets/mini-zero-success.svg" alt="Zero success" width="38" />
</p>

## Getting started

1. Install **JDK 17**, the **Android SDK** for API 35, and a current **Rust** toolchain.
2. Keep signing files, `local.properties`, and other machine-local overrides **outside**
   the repository tree.
3. Build the Android app with `./gradlew :app:assembleDebug`.
4. Before sharing builds, run `./gradlew spotlessCheck detekt :app:testDebugUnitTest :lib:testDebugUnitTest`.

## Development notes

- **Min SDK:** Android 9 / API 28
- **Target SDK:** 35
- **Languages:** Kotlin + Rust
- **Bridge:** UniFFI
- **Targets:** `aarch64-linux-android` and `x86_64-linux-android`

## Local configuration

- Keep `release.jks`, `local.properties`, and scratch directories such as `.tmp/` out of
  the repo tree and out of screenshots or support bundles.
- The Gradle build can load machine-local properties from `ZEROAI_LOCAL_PROPERTIES_FILE`
  or from `$HOME/.zeroai/local.properties` outside the repository.

## Support status

- Issues and pull requests are temporarily closed  (try making a fork you and your zero can grow together!)
- Please do not use upstream ZeroClaw Labs issue trackers for this downstream fork.

## Research

Some of the reverse engineering and protocol work done for this project is documented
publicly in case it helps others building similar integrations:

- [**Google Messages Bugle Protocol**](docs/research/google-messages-bugle-protocol.md) — reverse engineering notes on Google's proprietary Messages-for-Web RPC protocol (pairing, encryption, contacts/message sync, media upload)

## License

- Top-level app, docs, and assets are covered by the root [`LICENSE`](LICENSE).
- The upstream-derived Rust engine under [`zeroclaw/`](zeroclaw/) retains its upstream
  licensing; see [`zeroclaw/LICENSE-MIT`](zeroclaw/LICENSE-MIT) and
  [`zeroclaw/LICENSE-APACHE`](zeroclaw/LICENSE-APACHE).
