/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.memory

/**
 * Gates messages for Phase 2 LLM extraction.
 *
 * A message is "interesting" when it's long enough to contain
 * personal disclosure, contains self-referential pronouns, and
 * wasn't already captured by heuristic extraction.
 */
object InterestingnessFilter {
    private val PERSONAL_PRONOUNS = Regex("""(?i)\b(I|my|me|mine|I'm|I've|I'll|I'd)\b""")
    private val COMMAND_PREFIX = Regex("""^/\w+""")
    private const val MIN_LENGTH = 100

    /**
     * Returns true if the message warrants LLM extraction.
     *
     * @param message User message text.
     * @param heuristicCaptured Whether heuristic extraction found anything.
     * @return True if the message should be flagged for LLM extraction.
     */
    fun isInteresting(
        message: String,
        heuristicCaptured: Boolean,
    ): Boolean {
        if (heuristicCaptured) return false
        if (message.length < MIN_LENGTH) return false
        if (COMMAND_PREFIX.containsMatchIn(message)) return false
        return PERSONAL_PRONOUNS.containsMatchIn(message)
    }
}
