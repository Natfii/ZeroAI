/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.terminal

/**
 * Parses `/nano` command text into a [NanoIntent] variant using keyword matching.
 *
 * The parser applies a deterministic, longest-match-first strategy against
 * known trigger phrases. Triggers must match at word boundaries to avoid
 * false positives (e.g. "summary" does not match the "summarize" trigger).
 *
 * Resolution order:
 * 1. [NanoIntent.Summarize] -- summarization trigger phrases
 * 2. [NanoIntent.Proofread] -- proofreading trigger phrases
 * 3. [NanoIntent.Rewrite] -- rewriting trigger phrases with style detection
 * 4. [NanoIntent.Describe] -- image description trigger phrases
 * 5. [NanoIntent.General] -- fallback for unrecognized input
 */
object NanoIntentParser {
    /**
     * Trigger phrases for the [NanoIntent.Summarize] intent.
     *
     * Sorted by descending length so the longest match wins.
     */
    private val SUMMARIZE_TRIGGERS: List<String> =
        listOf(
            "give me the gist",
            "key points",
            "summarize",
            "brief me",
            "overview",
            "sum up",
            "recap",
            "tldr",
        )

    /**
     * Trigger phrases for the [NanoIntent.Proofread] intent.
     *
     * Sorted by descending length so the longest match wins.
     */
    private val PROOFREAD_TRIGGERS: List<String> =
        listOf(
            "fix the grammar",
            "grammar check",
            "check spelling",
            "correct this",
            "spell check",
            "fix grammar",
            "proofread",
        )

    /**
     * Trigger phrases for the [NanoIntent.Rewrite] intent.
     *
     * Sorted by descending length so the longest match wins.
     */
    private val REWRITE_TRIGGERS: List<String> =
        listOf(
            "change tone",
            "sound more",
            "make this",
            "rephrase",
            "rewrite",
            "make it",
            "tone",
        )

    /**
     * Trigger phrases for the [NanoIntent.Describe] intent.
     *
     * Sorted by descending length so the longest match wins.
     */
    private val DESCRIBE_TRIGGERS: List<String> =
        listOf(
            "what is this image",
            "what's in this",
            "what do you see",
            "describe",
        )

    /**
     * Keyword-to-style mapping for [NanoIntent.Rewrite].
     *
     * Each entry maps one or more keywords to a [RewriteStyle]. Keywords
     * are matched at word boundaries within the remaining text after the
     * rewrite trigger. The longest keyword match wins.
     */
    private val STYLE_KEYWORDS: List<Pair<String, RewriteStyle>> =
        listOf(
            "different words" to RewriteStyle.REPHRASE,
            "conversational" to RewriteStyle.FRIENDLY,
            "professional" to RewriteStyle.PROFESSIONAL,
            "more detail" to RewriteStyle.ELABORATE,
            "alternative" to RewriteStyle.REPHRASE,
            "friendlier" to RewriteStyle.FRIENDLY,
            "elaborate" to RewriteStyle.ELABORATE,
            "friendly" to RewriteStyle.FRIENDLY,
            "business" to RewriteStyle.PROFESSIONAL,
            "rephrase" to RewriteStyle.REPHRASE,
            "emojify" to RewriteStyle.EMOJIFY,
            "concise" to RewriteStyle.SHORTEN,
            "shorter" to RewriteStyle.SHORTEN,
            "shorten" to RewriteStyle.SHORTEN,
            "casual" to RewriteStyle.FRIENDLY,
            "detail" to RewriteStyle.ELABORATE,
            "expand" to RewriteStyle.ELABORATE,
            "formal" to RewriteStyle.PROFESSIONAL,
            "longer" to RewriteStyle.ELABORATE,
            "brief" to RewriteStyle.SHORTEN,
            "emoji" to RewriteStyle.EMOJIFY,
        )

    /**
     * Parses the user's input text (after the `/nano` prefix) into a
     * [NanoIntent].
     *
     * Matching is case-insensitive and respects word boundaries. The
     * longest matching trigger phrase wins when multiple triggers could
     * match.
     *
     * @param input The raw text after `/nano `. May be empty.
     * @return The classified [NanoIntent].
     */
    fun parse(input: String): NanoIntent {
        val trimmed = input.trim()
        val lower = trimmed.lowercase()

        findTrigger(lower, SUMMARIZE_TRIGGERS)?.let { match ->
            val text = trimmed.substring(match.endIndex).trim()
            return NanoIntent.Summarize(text = text)
        }

        findTrigger(lower, PROOFREAD_TRIGGERS)?.let { match ->
            val text = trimmed.substring(match.endIndex).trim()
            return NanoIntent.Proofread(text = text)
        }

        findTrigger(lower, REWRITE_TRIGGERS)?.let { match ->
            val text = trimmed.substring(match.endIndex).trim()
            val style = resolveStyle(lower) ?: RewriteStyle.REPHRASE
            return NanoIntent.Rewrite(text = text, style = style)
        }

        findTrigger(lower, DESCRIBE_TRIGGERS)?.let {
            return NanoIntent.Describe
        }

        return NanoIntent.General(prompt = trimmed)
    }

    /**
     * Finds the first (longest) trigger that matches at a word boundary
     * in the input.
     *
     * Triggers are pre-sorted by descending length, so the first match
     * is always the longest.
     *
     * @param lower The lowercased input text.
     * @param triggers Trigger phrases sorted by descending length.
     * @return A [TriggerMatch] with the position and length of the match,
     *   or `null` if no trigger matched.
     */
    private fun findTrigger(
        lower: String,
        triggers: List<String>,
    ): TriggerMatch? {
        for (trigger in triggers) {
            val index = lower.indexOf(trigger)
            if (index < 0) continue
            if (isWordBoundary(lower, index, trigger.length)) {
                return TriggerMatch(
                    startIndex = index,
                    endIndex = index + trigger.length,
                )
            }
        }
        return null
    }

    /**
     * Checks that a substring match sits on word boundaries.
     *
     * A word boundary exists when the character immediately before the
     * match start (if any) is not a letter or digit, and the character
     * immediately after the match end (if any) is not a letter or digit.
     *
     * @param text The full lowercased input.
     * @param index Start index of the candidate match.
     * @param length Length of the candidate match.
     * @return `true` if both boundaries are satisfied.
     */
    private fun isWordBoundary(
        text: String,
        index: Int,
        length: Int,
    ): Boolean {
        val end = index + length
        val beforeOk = index == 0 || !text[index - 1].isLetterOrDigit()
        val afterOk = end >= text.length || !text[end].isLetterOrDigit()
        return beforeOk && afterOk
    }

    /**
     * Resolves a [RewriteStyle] from style keywords in the input text.
     *
     * Scans [STYLE_KEYWORDS] (longest first) for a word-boundary match.
     *
     * @param lower The lowercased input text.
     * @return The matched [RewriteStyle], or `null` if no style keyword
     *   was found.
     */
    private fun resolveStyle(lower: String): RewriteStyle? {
        for ((keyword, style) in STYLE_KEYWORDS) {
            val index = lower.indexOf(keyword)
            if (index < 0) continue
            if (isWordBoundary(lower, index, keyword.length)) {
                return style
            }
        }
        return null
    }

    /**
     * Represents a successful trigger match within the input string.
     *
     * @property startIndex The start index of the trigger in the input.
     * @property endIndex The end index (exclusive) of the trigger in the input.
     */
    private data class TriggerMatch(
        val startIndex: Int,
        val endIndex: Int,
    )
}
