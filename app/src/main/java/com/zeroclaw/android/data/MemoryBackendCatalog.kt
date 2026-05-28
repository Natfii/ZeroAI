/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.data

/**
 * Single source of truth for memory backend identifiers and labels.
 *
 * Both the TOML validation whitelist used by
 * [ConfigTomlBuilder][com.zeroclaw.android.service.ConfigTomlBuilder] and the
 * user-facing backend selector in
 * [MemoryConfigFlow][com.zeroclaw.android.ui.component.setup.MemoryConfigFlow]
 * read from this catalog so that adding or removing a backend touches one file.
 */
object MemoryBackendCatalog {
    /**
     * Describes a user-selectable memory backend.
     *
     * @property id Machine-readable identifier matching upstream TOML `memory.backend`.
     * @property label Human-readable name shown in summaries and selectors.
     * @property description Short blurb shown in the onboarding picker.
     */
    data class Entry(
        val id: String,
        val label: String,
        val description: String,
    )

    /** Fallback used by [validate] when the configured backend is unknown. */
    const val DEFAULT_ID: String = "sqlite"

    /**
     * Backends presented to the user in the Android backend selector.
     * Server-bound backends (Postgres, Qdrant) are intentionally absent
     * because no Android UI path can produce a working configuration for
     * them — they remain valid TOML values via [validIds].
     */
    val userSelectable: List<Entry> =
        listOf(
            Entry(
                id = "sqlite",
                label = "SQLite",
                description = "Fast local database. Best for most users.",
            ),
            Entry(
                id = "lucid",
                label = "Lucid (on-device)",
                description = "Upstream on-device memory backend with richer semantic recall. Heavier than SQLite.",
            ),
            Entry(
                id = "none",
                label = "None",
                description = "No persistent memory. Agent starts fresh each session.",
            ),
        )

    /**
     * Identifiers accepted by the upstream TOML schema. Includes
     * [userSelectable] entries plus server-bound backends that the
     * Android UI does not surface but the daemon still understands.
     */
    val validIds: Set<String> =
        userSelectable.map { it.id }.toSet() + setOf("postgres", "qdrant")

    /**
     * Returns [id] when it matches a known backend, otherwise [DEFAULT_ID].
     *
     * Callers that write the TOML config should use this to coerce arbitrary
     * stored strings into a value the daemon will accept.
     */
    fun validate(id: String): String = if (id in validIds) id else DEFAULT_ID

    /**
     * Returns the human-readable label for [id], or a title-cased fallback when
     * the id is unknown (e.g. server-bound backends without a UI entry).
     */
    fun labelFor(id: String): String =
        userSelectable.firstOrNull { it.id == id }?.label
            ?: id.replaceFirstChar { it.uppercase() }
}
