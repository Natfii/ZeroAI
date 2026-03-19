/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.model

/**
 * A skill loaded from the workspace or community repository.
 *
 * Maps to the Rust `FfiSkill` record transferred across the FFI boundary.
 *
 * @property name Display name of the skill.
 * @property description Human-readable description.
 * @property version Semantic version string.
 * @property author Optional author name or identifier.
 * @property tags Tags for categorisation (e.g. "automation", "devops").
 * @property toolCount Number of tools provided by this skill.
 * @property toolNames Names of the tools provided by this skill.
 * @property isCommunity Whether this skill was imported from the community hub.
 * @property isEnabled Whether the skill is currently enabled for use.
 * @property sourceUrl Original URL the skill was imported from, if any.
 * @property emoji Optional emoji icon from skill metadata.
 * @property category Skill category from metadata (e.g. "social").
 * @property apiBase API base URL from skill metadata.
 */
data class Skill(
    val name: String,
    val description: String,
    val version: String,
    val author: String?,
    val tags: List<String>,
    val toolCount: Int,
    val toolNames: List<String>,
    val isCommunity: Boolean = false,
    val isEnabled: Boolean = true,
    val sourceUrl: String? = null,
    val emoji: String? = null,
    val category: String? = null,
    val apiBase: String? = null,
)
