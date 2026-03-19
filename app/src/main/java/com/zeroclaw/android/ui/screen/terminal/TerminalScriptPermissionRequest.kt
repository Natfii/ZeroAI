/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.terminal

/**
 * Pending packaged-script run awaiting Android-side capability review.
 *
 * Created after the Rust core validates a workspace or skill-packaged script.
 * The user can then grant or deny individual requested capabilities before
 * execution proceeds through the explicit-grant FFI path.
 *
 * @property relativePath Path to the script relative to the workspace root.
 * @property manifestName Stable manifest or fallback script name.
 * @property runtime Runtime identifier that will execute the script.
 * @property requestedCapabilities Capabilities requested by the script manifest.
 * @property grantedCapabilities Currently approved subset selected in the UI.
 * @property missingCapabilities Capabilities inferred from source but missing
 *   from the manifest grant set.
 * @property warnings Non-fatal validation warnings surfaced by Rust.
 */
data class TerminalScriptPermissionRequest(
    val relativePath: String,
    val manifestName: String,
    val runtime: String,
    val requestedCapabilities: List<String>,
    val grantedCapabilities: List<String>,
    val missingCapabilities: List<String>,
    val warnings: List<String>,
)
