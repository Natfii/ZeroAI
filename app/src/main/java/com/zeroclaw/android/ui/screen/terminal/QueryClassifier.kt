/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

@file:Suppress("MagicNumber")

package com.zeroclaw.android.ui.screen.terminal

/**
 * Routing decision produced by [QueryClassifier].
 *
 * Determines whether a plain-text chat message should be handled
 * on-device by Gemini Nano or forwarded to the cloud provider.
 */
sealed interface RoutingDecision {
    /**
     * Route the query to on-device Gemini Nano inference.
     *
     * @property reason Human-readable explanation of the scoring
     *   signals that led to this decision, suitable for debug logging.
     */
    data class Local(
        val reason: String,
    ) : RoutingDecision

    /**
     * Route the query to the configured cloud provider.
     *
     * @property reason Human-readable explanation of the scoring
     *   signals that led to this decision, suitable for debug logging.
     */
    data class Cloud(
        val reason: String,
    ) : RoutingDecision
}

/**
 * Heuristic classifier that scores plain-text chat messages to decide
 * between on-device Gemini Nano and cloud provider routing.
 *
 * The classifier applies a weighted scoring system across five signal
 * categories: token length, question complexity, image attachment,
 * multi-step indicators, and code keywords. Each signal contributes
 * additive points to a Nano score and/or a Cloud score. When Nano
 * score strictly exceeds Cloud score, the query is routed locally;
 * ties go to Cloud.
 *
 * This is pure Kotlin string operations with zero inference cost and
 * zero Android framework dependencies.
 *
 * Token estimation uses a ratio of approximately 3.5 characters per
 * token, which is a reasonable heuristic for English text.
 *
 * **Known limitation:** Current implementation only classifies
 * English-language queries. Non-English input will fall through to
 * the default routing bucket (Cloud for short queries, since no
 * signal patterns will match to boost the Nano score).
 */
object QueryClassifier {
    /**
     * Approximate number of characters per token for English text.
     */
    private const val CHARS_PER_TOKEN: Double = 3.5

    /**
     * Upper bound for the "short query" token bucket.
     */
    private const val SHORT_TOKEN_THRESHOLD: Int = 50

    /**
     * Upper bound for the "medium query" token bucket.
     */
    private const val MEDIUM_TOKEN_THRESHOLD: Int = 150
    private const val SIMPLE_PATTERN_BONUS = 2
    private const val COMPLEX_PATTERN_BONUS = 2
    private const val IMAGE_ATTACHMENT_BONUS = 3
    private const val MULTI_STEP_BONUS = 2
    private const val CODE_KEYWORD_BONUS = 2
    private const val SHORT_QUERY_NANO_SCORE = 3
    private const val MEDIUM_QUERY_NANO_SCORE = 1
    private const val MEDIUM_QUERY_CLOUD_SCORE = 1
    private const val LONG_QUERY_CLOUD_SCORE = 3

    /**
     * Regex patterns that indicate a simple, factual question suitable
     * for on-device inference.
     *
     * Matched case-insensitively against the lowercased query.
     */
    private val SIMPLE_QUESTION_PATTERNS: List<Regex> =
        listOf(
            Regex("""\bwhat is\b"""),
            Regex("""\bdefine\b"""),
            Regex("""\btranslate\b"""),
            Regex("""\bhow do you spell\b"""),
            Regex("""\bmeaning of\b"""),
            Regex("""\bwho is\b"""),
            Regex("""\bwhen was\b"""),
            Regex("""\bwhere is\b"""),
        )

    /**
     * Regex patterns that indicate a complex task requiring cloud-level
     * reasoning capability.
     *
     * Matched case-insensitively against the lowercased query.
     */
    private val COMPLEX_TASK_PATTERNS: List<Regex> =
        listOf(
            Regex("""\bexplain why\b"""),
            Regex("""\bcompare\b"""),
            Regex("""\banalyze\b"""),
            Regex("""\bwrite a\b"""),
            Regex("""\bcreate a\b"""),
            Regex("""\bbuild a\b"""),
            Regex("""\bdesign a\b"""),
            Regex("""\bimplement\b"""),
            Regex("""\bhow would you\b"""),
            Regex("""\bwhat are the pros and cons\b"""),
        )

    /**
     * Regex patterns that indicate multi-step instructions, which
     * exceed Nano's context and reasoning limits.
     *
     * Matched case-insensitively against the lowercased query.
     */
    private val MULTI_STEP_PATTERNS: List<Regex> =
        listOf(
            Regex("""\bstep by step\b"""),
            Regex("""\bfirst\b.*\bthen\b"""),
            Regex("""\b1\."""),
            Regex("""\b2\."""),
            Regex("""\bwalk me through\b"""),
            Regex("""\bbreak down\b"""),
        )

    /**
     * Keywords associated with programming tasks that benefit from
     * cloud-level code understanding.
     *
     * Matched at word boundaries against the lowercased query.
     */
    private val CODE_KEYWORDS: List<Regex> =
        listOf(
            Regex("""\bfunction\b"""),
            Regex("""\bclass\b"""),
            Regex("""\bdebug\b"""),
            Regex("""\brefactor\b"""),
            Regex("""\bcompile\b"""),
            Regex("""\bruntime\b"""),
            Regex("""\bexception\b"""),
            Regex("""\bapi\b"""),
            Regex("""\bendpoint\b"""),
            Regex("""\bdatabase\b"""),
            Regex("""\bquery\b"""),
            Regex("""\bmigration\b"""),
        )

    /**
     * Classifies a plain-text chat message to determine whether it
     * should be routed to on-device Gemini Nano or the cloud provider.
     *
     * The method applies five scoring signals in sequence:
     * 1. **Token length** -- short queries favor Nano, long ones favor Cloud
     * 2. **Simple question patterns** -- factual lookups favor Nano
     * 3. **Complex task patterns** -- reasoning tasks favor Cloud
     * 4. **Image attachment** -- always favors Cloud (Nano v3 vision
     *    is limited)
     * 5. **Multi-step indicators** -- procedural queries favor Cloud
     * 6. **Code keywords** -- programming tasks favor Cloud
     *
     * Empty or whitespace-only input short-circuits to
     * [RoutingDecision.Cloud] without scoring.
     *
     * When Nano score strictly exceeds Cloud score, the decision is
     * [RoutingDecision.Local]. Ties and Cloud-dominant scores produce
     * [RoutingDecision.Cloud].
     *
     * @param query The raw user message text. May be empty.
     * @param hasImageAttachment Whether the message includes an image
     *   attachment.
     * @return A [RoutingDecision] with the routing verdict and a
     *   reason string documenting the signal breakdown.
     */
    fun classify(
        query: String,
        hasImageAttachment: Boolean = false,
    ): RoutingDecision {
        val trimmed = query.trim()

        if (trimmed.isEmpty()) {
            return RoutingDecision.Cloud(reason = "nano=0 cloud=0 (empty input)")
        }

        var nanoScore = 0
        var cloudScore = 0
        val signals = mutableListOf<String>()

        val lower = trimmed.lowercase()
        val estimatedTokens = estimateTokenCount(trimmed)

        scoreTokenLength(estimatedTokens, signals).let { (nano, cloud) ->
            nanoScore += nano
            cloudScore += cloud
        }

        if (matchesAny(lower, SIMPLE_QUESTION_PATTERNS)) {
            nanoScore += SIMPLE_PATTERN_BONUS
            signals.add("simple question pattern")
        }

        if (matchesAny(lower, COMPLEX_TASK_PATTERNS)) {
            cloudScore += COMPLEX_PATTERN_BONUS
            signals.add("complex task pattern")
        }

        if (hasImageAttachment) {
            cloudScore += IMAGE_ATTACHMENT_BONUS
            signals.add("image attachment")
        }

        if (matchesAny(lower, MULTI_STEP_PATTERNS)) {
            cloudScore += MULTI_STEP_BONUS
            signals.add("multi-step indicators")
        }

        if (matchesAny(lower, CODE_KEYWORDS)) {
            cloudScore += CODE_KEYWORD_BONUS
            signals.add("code keywords")
        }

        val reason = "nano=$nanoScore cloud=$cloudScore (${signals.joinToString(", ")})"

        return if (nanoScore > cloudScore) {
            RoutingDecision.Local(reason = reason)
        } else {
            RoutingDecision.Cloud(reason = reason)
        }
    }

    /**
     * Estimates the token count of a string using a character-based
     * heuristic.
     *
     * @param text The input text.
     * @return The estimated number of tokens, rounded to the nearest
     *   integer. Returns zero for empty input.
     */
    private fun estimateTokenCount(text: String): Int {
        if (text.isEmpty()) return 0
        return (text.length / CHARS_PER_TOKEN).toInt()
    }

    /**
     * Scores the token length signal and records the bucket label.
     *
     * @param tokens The estimated token count.
     * @param signals Mutable list to append the signal description to.
     * @return A pair of (nanoPoints, cloudPoints).
     */
    private fun scoreTokenLength(
        tokens: Int,
        signals: MutableList<String>,
    ): Pair<Int, Int> =
        when {
            tokens < SHORT_TOKEN_THRESHOLD -> {
                signals.add("short query")
                SHORT_QUERY_NANO_SCORE to 0
            }
            tokens <= MEDIUM_TOKEN_THRESHOLD -> {
                signals.add("medium query")
                MEDIUM_QUERY_NANO_SCORE to MEDIUM_QUERY_CLOUD_SCORE
            }
            else -> {
                signals.add("long query")
                0 to LONG_QUERY_CLOUD_SCORE
            }
        }

    /**
     * Tests whether any of the given regex patterns match within the
     * input string.
     *
     * @param input The lowercased input to search.
     * @param patterns The regex patterns to test.
     * @return `true` if at least one pattern finds a match.
     */
    private fun matchesAny(
        input: String,
        patterns: List<Regex>,
    ): Boolean = patterns.any { it.containsMatchIn(input) }
}
