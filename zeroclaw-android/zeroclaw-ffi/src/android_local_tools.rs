// Copyright (c) 2026 @Natfii. All rights reserved.

//! Single source of truth for **Android-embedder-local tools** — the tools
//! that exist only in this FFI crate (not upstream) and need to reach
//! every per-agent tool registry the daemon builds.
//!
//! Two consumers call this helper:
//!
//!   1. [`crate::session_registry::build_tools_registry`] — the Terminal
//!      `session_send` path and the Hub > Tools inventory display
//!      (`tools_browse.rs`).
//!   2. The `EXTRA_TOOLS_FACTORY` closure installed at FFI start
//!      (`runtime.rs::start_daemon_inner`) — fan-out path through
//!      [`zeroclaw_runtime::tools::all_tools_with_runtime`] into the
//!      channel orchestrator, gateway, and every agent loop registry.
//!
//! Without this consolidation the two lists drifted: a tool added in
//! one place silently failed to reach the other, surfacing as "works in
//! Terminal but not on Discord" (or vice versa). One helper, both paths
//! call it, no drift possible.
//!
//! Returns fresh `Box<dyn Tool>` instances on every call — never share
//! Arcs across registries since the upstream tools wrap them in
//! per-agent rate-limiters / path-guards / sandboxes that would
//! otherwise leak state across agents.

use zeroclaw::tools::Tool;

/// Constructs the Android-local tool list for one tool registry.
///
/// Currently:
///   - `twitter_read_profile` — public X syndication endpoint reader
///   - `shared_folder_*` — SAF-backed file shim (3 tools)
///   - `read_messages` (lazy) — Google Messages bridge, gated on a
///     successfully-paired session store
pub(crate) fn android_local_tools() -> Vec<Box<dyn Tool>> {
    let mut tools: Vec<Box<dyn Tool>> = Vec::new();
    tools.extend(crate::twitter_browse_tool::create_twitter_browse_tools());
    tools.extend(crate::shared_folder::create_shared_folder_tools());
    if zeroai::messages_bridge::session::get_store().is_some() {
        tools.push(Box::new(
            zeroai::messages_bridge::tool::ReadMessagesTool::new_lazy(),
        ));
    }
    tools
}
