/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.service.features

import com.zeroclaw.android.data.email.EmailConfigState
import com.zeroclaw.android.model.AppSettings

/**
 * A single feature's contribution to the daemon TOML + agent awareness.
 *
 * Each feature owns one [FeatureContributor] implementation that decides,
 * based on the current settings, whether the feature is active and—if so—
 * emits both:
 *   1. The TOML section the daemon needs to enable the feature, and
 *   2. The natural-language fragment the agent receives in
 *      `[system_prompt] hub_app_context` so it knows the feature is on.
 *
 * Co-locating these two concerns in a single class is the whole point:
 * adding a feature becomes a one-file change instead of touching
 * `ConfigTomlBuilder`, `ZeroAIDaemonService.startDaemon`, and the
 * `hubAppContext` join in three separate places. Disabled features
 * simply return early in [contribute] and contribute nothing.
 */
interface FeatureContributor {
    /** Short identifier used in logs (`"twitter"`, `"email"`, ...). */
    val key: String

    /**
     * Inspect [ctx] and—if the feature is configured and enabled—emit
     * the feature's TOML section via [FeatureContext.emitToml] and its
     * awareness fragment via [FeatureContext.appendAwareness].
     *
     * Contributors must be safe to call when their feature is disabled;
     * the expected pattern is to bail out early with no side effects.
     */
    suspend fun contribute(ctx: FeatureContext)
}

/**
 * Carries the inputs a [FeatureContributor] needs (settings, email
 * config, etc.) and the sinks it writes to (TOML buffer, awareness
 * fragments).
 *
 * The daemon constructs one of these per startup, runs every
 * contributor against it, and then concatenates [assembledToml] onto
 * the base TOML and threads [assembledAwareness] into the global
 * config's `hubAppContext` field.
 *
 * @property settings Effective app settings snapshot at daemon start.
 * @property emailConfig Email configuration state, or null if the
 *   repository read failed.
 */
@Suppress("OutdatedDocumentation")
class FeatureContext(
    val settings: AppSettings,
    val emailConfig: EmailConfigState?,
    /**
     * Looks up the user-customised alias for a Tailscale peer service.
     * Returns null when no override is stored, in which case the caller
     * should fall back to the auto-generated default alias.
     *
     * Injected by the daemon (which owns the EncryptedSharedPreferences
     * backing store) so [FeatureContributor] implementations stay free
     * of Android `Context` plumbing.
     */
    val peerAliasLookup: (ip: String, port: Int) -> String? = { _, _ -> null },
) {
    private val tomlBuilder = StringBuilder()
    private val awarenessFragments = mutableListOf<String>()

    /**
     * Emits a TOML section by writing into the shared buffer. The
     * caller is responsible for adding a leading blank line and the
     * `[section_header]` line itself; this helper just provides the
     * receiver-style builder so contributors read like the existing
     * `appendXxxSection` methods.
     *
     * @param block TOML-emitting block.
     */
    fun emitToml(block: StringBuilder.() -> Unit) {
        if (tomlBuilder.isNotEmpty() && !tomlBuilder.endsWith("\n\n")) {
            tomlBuilder.appendLine()
        }
        tomlBuilder.block()
    }

    /**
     * Records one bullet-style awareness line. Lines are joined with
     * newlines in [assembledAwareness] and surfaced to the agent
     * exactly as written, so write user-facing prose.
     *
     * @param line Awareness fragment (e.g. `"- Email: Connected as ..."`).
     */
    fun appendAwareness(line: String) {
        awarenessFragments += line
    }

    /** TOML text contributed by every active feature, concatenated. */
    fun assembledToml(): String = tomlBuilder.toString()

    /**
     * Combined awareness block as a single string with one fragment
     * per line, or null when no feature contributed anything.
     */
    fun assembledAwareness(): String? = awarenessFragments.takeIf { it.isNotEmpty() }?.joinToString("\n")
}
