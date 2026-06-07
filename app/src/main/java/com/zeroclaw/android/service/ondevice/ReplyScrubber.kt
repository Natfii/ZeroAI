/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.service.ondevice

/**
 * Cleans a fully-assembled on-device reply before it reaches the agent
 * loop / channel.
 *
 * Weak Gemma-class models echo the channel prompt's structural
 * scaffolding (`[Memory context]`, `[No reply sent: …]`,
 * `[Used tools: …]`) as if it were their reply, then degenerate into
 * short-token repetition (`I'bah'bah'…`). Cloud models don't, so the
 * scrub is a no-op unless [GemmaResponseHarness.appliesTo] the model.
 *
 * Lives apart from [GemmaResponseHarness] (tool-call salvage): it shares
 * no state with it, keeps that object from sprawling, and gets its own
 * `ReplyScrubberTest` seam.
 */
internal object ReplyScrubber {
    /**
     * Leading bracketed scaffolding lines weak on-device models parrot
     * back from the channel prompt: the memory-context block header,
     * the reply-gate's `[No reply sent: …]` / `[reply sent: …]`
     * markers, and the `[Used tools: …]` summary. Anchored whole-line
     * (via [Regex.matches]) so brackets inside genuine prose are never
     * stripped.
     *
     * Deliberately does NOT match the bare reply-gate verdicts
     * (`REPLY` / `NO_REPLY[…]: …`): the gate's own classification call
     * also flows through this scrub, so stripping its verdict would
     * empty the response and break the gate. The observed reply garbage
     * is always the bracketed form anyway.
     */
    private val LEADING_SCAFFOLD_LINE =
        Regex(
            "^\\s*(?:" +
                "\\[\\s*(?:end )?memory context\\b[^\\]]*\\]|" +
                "\\[\\s*(?:no )?reply sent\\b[^\\]]*\\]|" +
                "\\[\\s*used tools\\b[^\\]]*\\]" +
                ")\\s*\$",
            RegexOption.IGNORE_CASE,
        )

    /** Shortest repeating unit considered for degenerate-run detection. */
    private const val MIN_REPEAT_UNIT = 1

    /** Longest repeating unit considered (covers `bah'`-style 4-char loops). */
    private const val MAX_REPEAT_UNIT = 12

    /**
     * Consecutive repeats of a single character needed to count as
     * degenerate — high so legitimate elongation ("soooo", "hmmmm")
     * survives.
     */
    private const val SINGLE_CHAR_REPEAT_MIN = 8

    /** Consecutive repeats of a multi-character unit needed to count as degenerate. */
    private const val MULTI_CHAR_REPEAT_MIN = 4

    /**
     * Cap on the input scanned, mirroring [GemmaResponseHarness]'s
     * salvage-path bound. Without it the O(n²)-worst-case repetition
     * scan below could be driven quadratic by a long near-cyclic
     * degenerate reply — exactly the input this code exists for. A few
     * KB is far above any legitimate on-device reply.
     */
    private const val SCRUB_MAX_CHARS = 8 * 1024

    /**
     * Scrubs a fully-assembled on-device reply.
     *
     * No-op unless [GemmaResponseHarness.appliesTo] [modelId]. After
     * capping the input ([SCRUB_MAX_CHARS]), runs two conservative
     * passes: drop leading scaffolding lines, then truncate the first
     * degenerate repetition run.
     *
     * @param modelId Active on-device model id; gates applicability.
     * @param raw Complete assembled response text.
     * @return Cleaned reply text (trimmed); may be blank if the reply
     *   was entirely artifact.
     */
    fun scrubReplyText(
        modelId: String,
        raw: String,
    ): String {
        if (!GemmaResponseHarness.appliesTo(modelId)) return raw
        val capped = raw.take(SCRUB_MAX_CHARS)
        val lines = capped.split("\n")
        var start = 0
        while (start < lines.size &&
            (lines[start].isBlank() || LEADING_SCAFFOLD_LINE.matches(lines[start]))
        ) {
            start++
        }
        val destripped = lines.subList(start, lines.size).joinToString("\n")
        return collapseDegenerateRepetition(destripped).trim()
    }

    /**
     * Truncates [text] at the first index where some unit of length
     * [MIN_REPEAT_UNIT]..[MAX_REPEAT_UNIT] repeats degenerate-many
     * times (see [SINGLE_CHAR_REPEAT_MIN] / [MULTI_CHAR_REPEAT_MIN]);
     * leaves non-repetitive text untouched.
     */
    private fun collapseDegenerateRepetition(text: String): String {
        val n = text.length
        for (i in text.indices) {
            for (unit in MIN_REPEAT_UNIT..MAX_REPEAT_UNIT) {
                val needed = if (unit == 1) SINGLE_CHAR_REPEAT_MIN else MULTI_CHAR_REPEAT_MIN
                if (i + unit * needed <= n && countConsecutiveRepeats(text, i, unit) >= needed) {
                    return text.substring(0, i).trimEnd()
                }
            }
        }
        return text
    }

    /**
     * Counts how many times the [unit]-length substring at [start]
     * repeats consecutively in [text]. Returns 0 for a blank unit so
     * whitespace runs aren't treated as degenerate. Callers must
     * ensure `start + unit <= text.length`.
     */
    private fun countConsecutiveRepeats(
        text: String,
        start: Int,
        unit: Int,
    ): Int {
        val candidate = text.substring(start, start + unit)
        if (candidate.isBlank()) return 0
        val n = text.length
        var repeats = 1
        var j = start + unit
        while (j + unit <= n && text.regionMatches(j, candidate, 0, unit)) {
            repeats++
            j += unit
        }
        return repeats
    }
}
