/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.model

/**
 * Message complexity tier for provider routing.
 *
 * Produced by [com.zeroclaw.android.service.MessageClassifier] and passed
 * to the Rust engine via [com.zeroclaw.ffi.sendMessageRouted] to influence
 * provider selection.
 *
 * @property ffiValue The lowercase string sent across the FFI boundary.
 */
enum class RouteHint(
    val ffiValue: String,
) {
    /** Simple factual lookups, greetings, short answers. */
    SIMPLE("simple"),

    /** Multi-step reasoning, code generation, analysis. */
    COMPLEX("complex"),

    /** Creative writing, brainstorming, open-ended generation. */
    CREATIVE("creative"),

    /** Requires function/tool calling capability. */
    TOOL_USE("tool_use"),
}
