/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data.channel

import com.zeroclaw.android.model.ChannelFieldSpec
import com.zeroclaw.android.model.ChannelType
import com.zeroclaw.android.util.DeepLinkTarget
import com.zeroclaw.android.util.ExternalAppLauncher

/**
 * Specification for an interactive channel setup sub-flow.
 *
 * Each channel type defines a sequence of guided [steps] that walk the
 * user through credentials, configuration, and optional advanced settings.
 *
 * @property channelType The channel platform this spec describes.
 * @property steps Ordered list of sub-step specifications for the setup flow.
 */
data class ChannelSetupSpec(
    val channelType: ChannelType,
    val steps: List<ChannelSetupStepSpec>,
)

/**
 * One sub-step in a channel setup flow.
 *
 * Each step groups related fields together with contextual instructions,
 * an optional deep link to an external service, and an optional live
 * validator to verify credentials before proceeding.
 *
 * @property title Short heading displayed at the top of the step.
 * @property instructions Ordered content items rendered as guidance text.
 * @property deepLink Optional external link target for the step (e.g. BotFather).
 * @property fields Configuration fields collected in this step.
 * @property validatorType Optional live validation to run on this step's fields.
 * @property optional Whether the user may skip this step entirely.
 */
data class ChannelSetupStepSpec(
    val title: String,
    val instructions: List<InstructionItem>,
    val deepLink: DeepLinkTarget? = null,
    val fields: List<ChannelFieldSpec>,
    val validatorType: ValidatorType? = null,
    val optional: Boolean = false,
)

/**
 * Instruction items rendered in the setup flow.
 *
 * Each variant maps to a distinct visual treatment in the UI:
 * plain text, numbered steps, warnings, or expandable hints.
 */
sealed interface InstructionItem {
    /**
     * Plain descriptive text.
     *
     * @property content The text content to display.
     */
    data class Text(
        val content: String,
    ) : InstructionItem

    /**
     * A numbered step in a procedure.
     *
     * @property number The step number (1-based).
     * @property content The instruction text for this step.
     */
    data class NumberedStep(
        val number: Int,
        val content: String,
    ) : InstructionItem

    /**
     * A warning callout emphasising important information.
     *
     * @property content The warning message to display.
     */
    data class Warning(
        val content: String,
    ) : InstructionItem

    /**
     * A hint that may be shown in a collapsible section.
     *
     * @property content The hint text.
     * @property expandable Whether the hint is initially collapsed.
     */
    data class Hint(
        val content: String,
        val expandable: Boolean = false,
    ) : InstructionItem
}

/**
 * Types of live validation available for channel setup.
 *
 * Each value corresponds to a platform-specific API call that
 * verifies the user's credentials before proceeding to the next step.
 */
enum class ValidatorType {
    /** Validates a Telegram Bot API token via the `getMe` endpoint. */
    TELEGRAM_BOT_TOKEN,

    /** Validates a Discord bot token via the `users/@me` endpoint. */
    DISCORD_BOT_TOKEN,
}

/**
 * Registry of [ChannelSetupSpec] definitions for all supported channel types.
 *
 * Provides a data-driven lookup from [ChannelType] to its guided setup
 * specification. Channel types without a dedicated spec fall back to a
 * generic two-step flow via [forType].
 */
@Suppress("LongMethod", "MagicNumber")
object ChannelSetupSpecs {
    /**
     * Returns the [ChannelSetupSpec] for the given [type], or null if the
     * channel type does not support guided setup.
     *
     * @param type The channel type to look up.
     * @return The setup specification, or null for unsupported types.
     */
    fun forType(type: ChannelType): ChannelSetupSpec? = specs[type]

    /**
     * Retrieves fields from a [ChannelType] by their key names.
     *
     * Returns only those [ChannelFieldSpec] entries whose [ChannelFieldSpec.key]
     * appears in [keys], preserving the order of [keys].
     */
    private fun ChannelType.fieldsByKey(
        vararg keys: String,
    ): List<ChannelFieldSpec> {
        val indexed = fields.associateBy { it.key }
        return keys.mapNotNull { indexed[it] }
    }

    /**
     * Retrieves fields from a [ChannelType] excluding the given key names.
     *
     * Returns [ChannelFieldSpec] entries whose [ChannelFieldSpec.key] does
     * not appear in [keys], preserving the original field order.
     */
    private fun ChannelType.fieldsExcluding(
        vararg keys: String,
    ): List<ChannelFieldSpec> {
        val excluded = keys.toSet()
        return fields.filter { it.key !in excluded }
    }

    /**
     * Builds a generic two-step spec for channels without a dedicated flow.
     *
     * Step 1 ("Credentials") contains all required or secret fields.
     * Step 2 ("Configuration") contains the remaining fields and is
     * marked optional. If all fields are credentials, only one step
     * is produced.
     */
    private fun genericSpec(type: ChannelType): ChannelSetupSpec {
        val credentials = type.fields.filter { it.isRequired || it.isSecret }
        val configuration =
            type.fields.filter { !it.isRequired && !it.isSecret }

        val steps =
            buildList {
                add(
                    ChannelSetupStepSpec(
                        title = "Credentials",
                        instructions =
                            listOf(
                                InstructionItem.Text(
                                    "Enter the credentials for your " +
                                        "${type.displayName} integration.",
                                ),
                            ),
                        fields = credentials,
                    ),
                )
                if (configuration.isNotEmpty()) {
                    add(
                        ChannelSetupStepSpec(
                            title = "Configuration",
                            instructions =
                                listOf(
                                    InstructionItem.Text(
                                        "Configure additional settings for " +
                                            "${type.displayName}.",
                                    ),
                                ),
                            fields = configuration,
                            optional = true,
                        ),
                    )
                }
            }

        return ChannelSetupSpec(channelType = type, steps = steps)
    }

    /** Pre-built spec map keyed by [ChannelType]. */
    private val specs: Map<ChannelType, ChannelSetupSpec> =
        buildMap {
            put(ChannelType.TELEGRAM, telegramSpec())

            ChannelType.entries
                .filter { it !in this && it != ChannelType.DISCORD }
                .forEach { put(it, genericSpec(it)) }
        }

    /** Builds the Telegram setup spec with 3 sub-steps. */
    private fun telegramSpec(): ChannelSetupSpec =
        ChannelSetupSpec(
            channelType = ChannelType.TELEGRAM,
            steps =
                listOf(
                    ChannelSetupStepSpec(
                        title = "Create a Telegram Bot",
                        instructions =
                            listOf(
                                InstructionItem.Text(
                                    "Create a new bot using Telegram's BotFather.",
                                ),
                                InstructionItem.NumberedStep(
                                    1,
                                    "Open BotFather using the link below.",
                                ),
                                InstructionItem.NumberedStep(
                                    2,
                                    "Send /newbot and follow the prompts.",
                                ),
                                InstructionItem.NumberedStep(
                                    3,
                                    "Copy the API token BotFather gives you.",
                                ),
                                InstructionItem.Warning(
                                    "Keep your bot token secret. Anyone with the " +
                                        "token can control your bot.",
                                ),
                            ),
                        deepLink = ExternalAppLauncher.TELEGRAM_BOTFATHER,
                        fields = ChannelType.TELEGRAM.fieldsByKey("bot_token"),
                        validatorType = ValidatorType.TELEGRAM_BOT_TOKEN,
                    ),
                    ChannelSetupStepSpec(
                        title = "Allow Your Account",
                        instructions =
                            listOf(
                                InstructionItem.Text(
                                    "Restrict which Telegram users can interact " +
                                        "with your bot.",
                                ),
                                InstructionItem.NumberedStep(
                                    1,
                                    "Open the userinfobot link below.",
                                ),
                                InstructionItem.NumberedStep(
                                    2,
                                    "Send any message to get your numeric user ID.",
                                ),
                                InstructionItem.NumberedStep(
                                    3,
                                    "Paste your user ID into the field below.",
                                ),
                                InstructionItem.Hint(
                                    "You can add multiple user IDs separated " +
                                        "by commas.",
                                    expandable = true,
                                ),
                            ),
                        deepLink = ExternalAppLauncher.TELEGRAM_USERINFOBOT,
                        fields = ChannelType.TELEGRAM.fieldsByKey("allowed_users"),
                    ),
                    ChannelSetupStepSpec(
                        title = "Advanced Settings",
                        instructions =
                            listOf(
                                InstructionItem.Text(
                                    "Configure optional Telegram-specific behaviour.",
                                ),
                            ),
                        fields =
                            ChannelType.TELEGRAM.fieldsExcluding(
                                "bot_token",
                                "allowed_users",
                            ),
                        optional = true,
                    ),
                ),
        )
}
