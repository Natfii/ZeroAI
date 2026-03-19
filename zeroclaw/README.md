# zeroclaw

Embedded Rust core for the Zero Android app.

## Status in this repository

This `zeroclaw/` directory is **not** a standalone product surface here anymore.
It exists as the native Rust runtime used by the Android app and UniFFI bridge in the root project.

## Upstream ancestry

This directory contains a **stripped and modified** version of the upstream ZeroClaw project:

- <https://github.com/zeroclaw-labs/zeroclaw>

The upstream project is offered under **MIT OR Apache-2.0**.
For this repository's `zeroclaw/` directory, the **MIT** license option is used.
See:

- `LICENSE-MIT`
- `NOTICE`

## What remains relevant here

For the Android-only Zero project, this directory mainly provides:

- config parsing and schema support
- agent runtime logic
- tools, channels, gateway, and memory systems
- Rust code exported through the app's FFI layer

## What was intentionally trimmed

Desktop/distribution-oriented upstream surfaces may be reduced or removed here when they are not needed for the Android app.
That includes packaging, container, and other standalone-project conveniences that are outside the app-focused runtime.

## Working note

If you are changing code in `zeroclaw/`, treat it as app-embedded runtime code, not as an independently shipped upstream clone.
