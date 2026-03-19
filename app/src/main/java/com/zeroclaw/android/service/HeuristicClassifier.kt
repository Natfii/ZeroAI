/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.service

import com.zeroclaw.android.model.RouteHint

/**
 * Zero-latency message classifier using regex patterns and keyword matching.
 *
 * Used as the fallback classifier when Gemini Nano is unavailable (app
 * backgrounded, model not downloaded, battery quota exceeded, or device
 * unsupported). Runs in microseconds with no model weights or network calls.
 *
 * Classification priority (first match wins):
 * 1. Code/structured data detection → [RouteHint.COMPLEX]
 * 2. Tool-use verb detection → [RouteHint.TOOL_USE]
 * 3. Creative verb detection → [RouteHint.CREATIVE]
 * 4. Simple pattern detection → [RouteHint.SIMPLE]
 * 5. Complexity verb / multi-part / length checks → [RouteHint.COMPLEX]
 * 6. Default for ambiguous input → [RouteHint.COMPLEX]
 *
 * The asymmetric default-to-complex strategy is intentional: sending a
 * simple query to a strong model costs a few extra cents, but sending a
 * complex query to a weak model produces a bad answer.
 */
object HeuristicClassifier {
    /** Matches fenced code blocks. */
    private val CODE_FENCE = Regex("```[\\s\\S]*?```")

    /** Matches inline code spans. */
    private val INLINE_CODE = Regex("`[^`]+`")

    /** Matches JSON-like structures. */
    private val JSON_PATTERN = Regex("""\{[\s\S]*?"[^"]+"\s*:""")

    /** Matches XML or HTML tags. */
    private val XML_PATTERN = Regex("""<[a-zA-Z][a-zA-Z0-9]*[\s>]""")

    /** Matches SQL keywords at word boundaries. */
    private val SQL_PATTERN =
        Regex(
            """\b(SELECT|INSERT|UPDATE|DELETE|CREATE|ALTER|DROP)\b""",
            RegexOption.IGNORE_CASE,
        )

    /** Matches LaTeX math notation. */
    private val MATH_PATTERN = Regex("""\$[^$]+\$|\\(frac|sqrt|sum|int|begin)\{""")

    /** Matches tool-use action verbs. */
    private val TOOL_USE_PATTERN =
        Regex(
            """\b(search for|look\s+up|calculate|fetch|find all|run|execute|check the)\b""",
            RegexOption.IGNORE_CASE,
        )

    /** Matches creative task verbs. */
    private val CREATIVE_PATTERN =
        Regex(
            """\b(write a|write me|compose|brainstorm|imagine|invent|draft a|come up with|create a story|make up)\b""",
            RegexOption.IGNORE_CASE,
        )

    /** Matches simple factual question patterns. */
    private val SIMPLE_PATTERN =
        Regex(
            """\b(what is|what's|define|who is|who's|when did|when was|how many|how much|translate|name the|list the|list all)\b""",
            RegexOption.IGNORE_CASE,
        )

    /** Matches common greetings. */
    private val GREETING_PATTERN =
        Regex(
            """^(hi|hello|hey|howdy|good morning|good afternoon|good evening|sup|yo|what's up|thanks|thank you|ok|okay)\s*[!?.]*$""",
            RegexOption.IGNORE_CASE,
        )

    /** Matches complex reasoning verbs. */
    private val COMPLEX_VERB_PATTERN =
        Regex(
            """\b(explain|compare|contrast|analyze|evaluate|synthesize|design|implement|debug|refactor|optimize|prove|derive|why does|how does)\b""",
            RegexOption.IGNORE_CASE,
        )

    /** Matches multi-part question indicators. */
    private val MULTI_PART_PATTERN =
        Regex(
            """(^\s*\d+[.)]\s)|(\b(additionally|furthermore|also|moreover|first[\s,].*second|and also)\b)""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
        )

    /** Matches constraint-heavy language. */
    private val CONSTRAINT_PATTERN =
        Regex(
            """\b(do not|don't|without|except|only if|make sure|must not|avoid|never)\b""",
            RegexOption.IGNORE_CASE,
        )

    /** Word count threshold: messages shorter than this are likely simple. */
    private const val SHORT_MESSAGE_WORDS = 12

    /** Word count threshold: messages at this count gain a complexity point. */
    private const val MEDIUM_MESSAGE_WORDS = 50

    /** Word count threshold: messages longer than this are likely complex. */
    private const val LONG_MESSAGE_WORDS = 200

    /** Score at or above which ambiguous messages are classified as complex. */
    private const val AMBIGUOUS_COMPLEXITY_THRESHOLD = 2

    /**
     * Classifies a user message into a [RouteHint] complexity tier.
     *
     * Evaluation runs in priority order. The first matching tier wins.
     * Ambiguous messages default to [RouteHint.COMPLEX] to protect quality.
     *
     * @param message The raw user message text.
     * @return The classified [RouteHint].
     */
    fun classify(message: String): RouteHint {
        val trimmed = message.trim()
        if (trimmed.isEmpty()) return RouteHint.SIMPLE
        if (hasCodeOrStructuredData(trimmed)) return RouteHint.COMPLEX
        if (TOOL_USE_PATTERN.containsMatchIn(trimmed)) return RouteHint.TOOL_USE
        if (CREATIVE_PATTERN.containsMatchIn(trimmed)) return RouteHint.CREATIVE
        return classifyByComplexity(trimmed)
    }

    /**
     * Classifies messages that didn't match high-signal verb patterns.
     *
     * Checks greetings, simple factual questions, explicit complexity
     * signals, and falls back to weighted scoring for ambiguous input.
     *
     * @param trimmed The trimmed, non-empty message text.
     * @return The classified [RouteHint].
     */
    private fun classifyByComplexity(trimmed: String): RouteHint {
        if (GREETING_PATTERN.matches(trimmed)) return RouteHint.SIMPLE

        val wordCount = trimmed.split("\\s+".toRegex()).size
        if (wordCount <= SHORT_MESSAGE_WORDS && SIMPLE_PATTERN.containsMatchIn(trimmed)) {
            return RouteHint.SIMPLE
        }
        val hasExplicitComplexity =
            COMPLEX_VERB_PATTERN.containsMatchIn(trimmed) ||
                MULTI_PART_PATTERN.containsMatchIn(trimmed) ||
                wordCount > LONG_MESSAGE_WORDS
        if (hasExplicitComplexity) return RouteHint.COMPLEX
        return scoreAmbiguous(trimmed, wordCount)
    }

    /**
     * Scores ambiguous messages using weighted heuristics.
     *
     * @param trimmed The trimmed message text.
     * @param wordCount Pre-computed word count.
     * @return [RouteHint.COMPLEX] if score meets threshold, otherwise
     *   [RouteHint.SIMPLE] for short messages or [RouteHint.COMPLEX] as default.
     */
    private fun scoreAmbiguous(
        trimmed: String,
        wordCount: Int,
    ): RouteHint {
        var score = 0
        if (CONSTRAINT_PATTERN.containsMatchIn(trimmed)) score += 2
        if (wordCount > MEDIUM_MESSAGE_WORDS) score += 1
        if (trimmed.contains("?") && trimmed.indexOf("?") != trimmed.lastIndexOf("?")) score += 2
        if (score >= AMBIGUOUS_COMPLEXITY_THRESHOLD) return RouteHint.COMPLEX
        return if (wordCount <= SHORT_MESSAGE_WORDS) RouteHint.SIMPLE else RouteHint.COMPLEX
    }

    /**
     * Checks for code blocks, structured data, or formal notation.
     *
     * @param text The trimmed message text.
     * @return `true` if any structural pattern is detected.
     */
    private fun hasCodeOrStructuredData(text: String): Boolean =
        CODE_FENCE.containsMatchIn(text) ||
            INLINE_CODE.containsMatchIn(text) ||
            JSON_PATTERN.containsMatchIn(text) ||
            XML_PATTERN.containsMatchIn(text) ||
            SQL_PATTERN.containsMatchIn(text) ||
            MATH_PATTERN.containsMatchIn(text)
}
