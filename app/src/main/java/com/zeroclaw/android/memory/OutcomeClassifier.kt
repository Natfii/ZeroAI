/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.memory

/**
 * Classifies interaction outcomes from follow-up signals.
 *
 * Uses regex pattern matching on follow-up messages. Runs in
 * microseconds. Simplified version of Reflexion's evaluator
 * (Shinn et al., NeurIPS 2023, https://arxiv.org/abs/2303.11366).
 */
object OutcomeClassifier {
    /** Interaction outcome categories. */
    enum class InteractionOutcome {
        /** Interaction completed successfully; user expressed satisfaction. */
        SUCCESS,

        /** Interaction failed; user expressed rejection or correction. */
        FAILURE,

        /** Interaction was a retry of a previous request. */
        RETRY,

        /** Interaction completed but at least one tool call failed. */
        DEGRADED,

        /** Outcome could not be determined from available signals. */
        NEUTRAL,
    }

    private val THANKS_PATTERN =
        Regex("""(?i)\b(thanks|thank you|perfect|great|exactly|that works|nice|awesome|good job)\b""")

    private val REJECTION_PATTERN =
        Regex("""(?i)\b(no|wrong|incorrect|that's not|try again|not what I|doesn't work|broken|fail)\b""")

    /**
     * Classifies the outcome of an interaction.
     *
     * Priority: FAILURE > SUCCESS > RETRY > DEGRADED > NEUTRAL.
     *
     * @param followUpMessage The user's next message after the interaction, or null.
     * @param toolCallsSucceeded Whether all tool calls in the interaction succeeded.
     * @param wasRetry Whether this was a retry of a previous request.
     * @return The classified outcome.
     */
    fun classify(
        followUpMessage: String?,
        toolCallsSucceeded: Boolean,
        wasRetry: Boolean,
    ): InteractionOutcome {
        // Rejection takes priority over thanks (mixed signals = failure)
        if (followUpMessage != null && REJECTION_PATTERN.containsMatchIn(followUpMessage)) {
            return InteractionOutcome.FAILURE
        }
        if (followUpMessage != null && THANKS_PATTERN.containsMatchIn(followUpMessage)) {
            return InteractionOutcome.SUCCESS
        }
        if (wasRetry) return InteractionOutcome.RETRY
        if (!toolCallsSucceeded) return InteractionOutcome.DEGRADED
        return InteractionOutcome.NEUTRAL
    }
}
