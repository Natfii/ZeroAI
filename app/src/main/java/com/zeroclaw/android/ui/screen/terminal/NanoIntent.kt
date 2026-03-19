/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.terminal

/**
 * Classified intent for a `/nano` command.
 *
 * Produced by [NanoIntentParser] from the user's natural-language input
 * after the `/nano` prefix. The [TerminalViewModel] dispatches each
 * variant to the appropriate on-device bridge.
 */
sealed interface NanoIntent {
    /**
     * Summarize text using the ML Kit Summarization API.
     *
     * @property text The text to summarize. If blank, the ViewModel
     *   substitutes the last agent response.
     * @property isConversation Whether the input is a conversation
     *   transcript (affects ML Kit input type).
     */
    data class Summarize(
        val text: String,
        val isConversation: Boolean = false,
    ) : NanoIntent

    /**
     * Proofread text using the ML Kit Proofreading API.
     *
     * @property text The text to proofread. If blank, the ViewModel
     *   substitutes the last agent response.
     * @property isVoiceInput Whether the text came from voice input
     *   (affects ML Kit input type).
     */
    data class Proofread(
        val text: String,
        val isVoiceInput: Boolean = false,
    ) : NanoIntent

    /**
     * Rewrite text using the ML Kit Rewriting API.
     *
     * @property text The text to rewrite. If blank, the ViewModel
     *   substitutes the last agent response.
     * @property style The rewriting style to apply.
     */
    data class Rewrite(
        val text: String,
        val style: RewriteStyle,
    ) : NanoIntent

    /**
     * Describe an image using the ML Kit Image Description API.
     *
     * The ViewModel resolves the bitmap from the last captured image.
     */
    data object Describe : NanoIntent

    /**
     * General-purpose prompt sent to the Prompt API.
     *
     * Fallback when no specialized keyword matches. Uses multi-turn
     * chat history when available.
     *
     * @property prompt The user's raw prompt text.
     */
    data class General(
        val prompt: String,
    ) : NanoIntent
}

/**
 * Rewriting styles mapped to ML Kit
 * [RewriterOptions.OutputType][com.google.mlkit.genai.rewriting.RewriterOptions.OutputType].
 *
 * @property displayName Human-readable name shown in terminal output.
 */
enum class RewriteStyle(
    val displayName: String,
) {
    /** Expand with more detail. */
    ELABORATE("elaborate"),

    /** Insert relevant emoji. */
    EMOJIFY("emojify"),

    /** Condense while preserving meaning. */
    SHORTEN("shorten"),

    /** Casual, conversational tone. */
    FRIENDLY("friendly"),

    /** Formal, business tone. */
    PROFESSIONAL("professional"),

    /** Alternative vocabulary and syntax. */
    REPHRASE("rephrase"),
}
