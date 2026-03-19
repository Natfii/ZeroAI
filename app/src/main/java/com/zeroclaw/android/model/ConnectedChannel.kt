/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.model

/**
 * Input type for a channel configuration field, determining the
 * keyboard type and visual treatment in the form UI.
 */
enum class FieldInputType {
    /** Plain text input. */
    TEXT,

    /** Numeric input. */
    NUMBER,

    /** URL input with URL keyboard hints. */
    URL,

    /** Boolean toggle (switch). */
    BOOLEAN,

    /** Comma-separated list input. */
    LIST,

    /** Secret input with masked display and reveal toggle. */
    SECRET,
}

/**
 * Specification for a single configuration field within a [ChannelType].
 *
 * Each channel type declares its fields statically so the UI can render
 * a dynamic form without hard-coding per-channel layouts.
 *
 * @property key TOML key name matching the upstream Rust struct field.
 * @property label Human-readable label for the form field.
 * @property isRequired Whether the field must have a non-blank value to save.
 * @property isSecret Whether the value should be stored in encrypted preferences.
 * @property defaultValue Default value pre-filled in the form, empty if none.
 * @property inputType Determines keyboard type and visual treatment.
 */
data class ChannelFieldSpec(
    val key: String,
    val label: String,
    val isRequired: Boolean = false,
    val isSecret: Boolean = false,
    val defaultValue: String = "",
    val inputType: FieldInputType = FieldInputType.TEXT,
)

/**
 * Supported chat channel types matching upstream ZeroAI channel configurations.
 *
 * Each entry declares its display name, TOML section key, and the list of
 * configuration fields required by the upstream Rust struct.
 *
 * @property displayName Human-readable name shown in the UI.
 * @property tomlKey Key used in the `[channels_config.<key>]` TOML section.
 * @property fields Ordered list of configuration field specifications.
 */
enum class ChannelType(
    val displayName: String,
    val tomlKey: String,
    val fields: List<ChannelFieldSpec>,
) {
    /** Telegram Bot API channel. */
    TELEGRAM(
        displayName = "Telegram",
        tomlKey = "telegram",
        fields =
            listOf(
                ChannelFieldSpec(
                    "bot_token",
                    "Bot Token",
                    isRequired = true,
                    isSecret = true,
                    inputType = FieldInputType.SECRET,
                ),
                ChannelFieldSpec(
                    "allowed_users",
                    "Allowed Users",
                    inputType = FieldInputType.LIST,
                ),
            ),
    ),

    /** Discord Bot channel. */
    DISCORD(
        displayName = "Discord",
        tomlKey = "discord",
        fields =
            listOf(
                ChannelFieldSpec(
                    "bot_token",
                    "Bot Token",
                    isRequired = true,
                    isSecret = true,
                    inputType = FieldInputType.SECRET,
                ),
            ),
    ),
}

/**
 * A configured chat channel instance.
 *
 * Non-secret configuration values are stored in Room via [configValues].
 * Secret values (bot tokens, passwords) are stored separately in
 * EncryptedSharedPreferences and retrieved on demand.
 *
 * @property id Unique identifier (UUID string).
 * @property type The channel platform type.
 * @property isEnabled Whether the channel is active for daemon communication.
 * @property configValues Non-secret configuration key-value pairs.
 * @property createdAt Epoch milliseconds when the channel was configured.
 */
data class ConnectedChannel(
    val id: String,
    val type: ChannelType,
    val isEnabled: Boolean = true,
    val configValues: Map<String, String> = emptyMap(),
    val createdAt: Long = System.currentTimeMillis(),
)
