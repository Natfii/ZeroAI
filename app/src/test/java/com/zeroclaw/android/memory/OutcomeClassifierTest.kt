/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.memory

import com.zeroclaw.android.memory.OutcomeClassifier.InteractionOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("OutcomeClassifier")
class OutcomeClassifierTest {

    @Test
    @DisplayName("thanks message classifies as SUCCESS")
    fun `thanks is success`() {
        assertEquals(
            InteractionOutcome.SUCCESS,
            OutcomeClassifier.classify("Thanks, that works!", toolCallsSucceeded = true, wasRetry = false),
        )
    }

    @Test
    @DisplayName("rejection message classifies as FAILURE")
    fun `rejection is failure`() {
        assertEquals(
            InteractionOutcome.FAILURE,
            OutcomeClassifier.classify("No, that's wrong", toolCallsSucceeded = true, wasRetry = false),
        )
    }

    @Test
    @DisplayName("retry detected classifies as RETRY")
    fun `retry detected`() {
        assertEquals(
            InteractionOutcome.RETRY,
            OutcomeClassifier.classify(null, toolCallsSucceeded = true, wasRetry = true),
        )
    }

    @Test
    @DisplayName("tool failure classifies as DEGRADED")
    fun `tool failure`() {
        assertEquals(
            InteractionOutcome.DEGRADED,
            OutcomeClassifier.classify(null, toolCallsSucceeded = false, wasRetry = false),
        )
    }

    @Test
    @DisplayName("neutral fallback")
    fun `neutral fallback`() {
        assertEquals(
            InteractionOutcome.NEUTRAL,
            OutcomeClassifier.classify("ok", toolCallsSucceeded = true, wasRetry = false),
        )
    }

    @Test
    @DisplayName("mixed signals (thanks + rejection) classifies as FAILURE")
    fun `mixed signals is failure`() {
        assertEquals(
            InteractionOutcome.FAILURE,
            OutcomeClassifier.classify("Thanks but that's not quite right", toolCallsSucceeded = true, wasRetry = false),
        )
    }
}
