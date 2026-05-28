// Copyright (c) 2026 @Natfii. All rights reserved.

//! Core scripting runtime, manifest model, and capability surface.
//!
//! This module centralises ZeroAI's scripting ownership inside `zeroclaw`
//! instead of the Android FFI crate. Rhai remains the first embedded
//! runtime, but the capability model and plugin ABI are defined here so
//! future runtimes can share the same host contract.
//!
//! The module is decomposed into focused submodules:
//!
//! - [`manifest`] — data types, capability catalogue, and host trait.
//! - [`discovery`] — workspace and skill-package script discovery.
//! - [`capabilities`] — capability inference, execution session, URL/path safety.
//! - [`audit`] — audit-record helpers and error normalisation.
//! - [`dispatch`] — Rhai engine construction and host-call dispatch.
//! - [`triggers`] — trigger matching and registration for packaged scripts.

pub mod audit;
pub mod capabilities;
pub mod content_hash;
pub mod discovery;
pub mod dispatch;
pub mod manifest;
pub mod plugin_abi;
pub mod storage;
pub mod triggers;

pub use discovery::discover_workspace_scripts;
pub use dispatch::RhaiScriptRuntime;
pub use manifest::{
    build_agent_capabilities, default_script_capabilities, get_cron_script_host,
    set_cron_script_host, ScriptAuditRecord, ScriptCapability, ScriptError, ScriptHost,
    ScriptLimits, ScriptManifest, ScriptOperation, ScriptPluginRuntime, ScriptRuntimeKind,
    ScriptTrigger, ScriptValidation, ScriptValue, StubScriptHost,
};

#[cfg(test)]
mod tests;
