/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.terminal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for [StreamingPhase] and [StreamingState].
 */
@DisplayName("StreamingState")
class StreamingStateTest {
    @Nested
    @DisplayName("StreamingPhase.isActive")
    inner class IsActive {
        @Test
        @DisplayName("active phases return true")
        fun `active phases return true`() {
            val activePhases =
                listOf(
                    StreamingPhase.SEARCHING_MEMORY,
                    StreamingPhase.CALLING_PROVIDER,
                    StreamingPhase.THINKING,
                    StreamingPhase.TOOL_EXECUTING,
                    StreamingPhase.RESPONDING,
                    StreamingPhase.COMPACTING,
                )
            for (phase in activePhases) {
                assertTrue(phase.isActive, "$phase should be active")
            }
        }

        @Test
        @DisplayName("terminal phases return false")
        fun `terminal phases return false`() {
            val terminalPhases =
                listOf(
                    StreamingPhase.IDLE,
                    StreamingPhase.COMPLETE,
                    StreamingPhase.CANCELLED,
                    StreamingPhase.ERROR,
                )
            for (phase in terminalPhases) {
                assertFalse(phase.isActive, "$phase should not be active")
            }
        }

        @Test
        @DisplayName("all phases are covered by active or terminal")
        fun `all phases are covered by active or terminal`() {
            assertEquals(
                StreamingPhase.entries.size,
                StreamingPhase.entries.count { it.isActive } +
                    StreamingPhase.entries.count { !it.isActive },
            )
        }
    }

    @Nested
    @DisplayName("StreamingState.idle")
    inner class Idle {
        @Test
        @DisplayName("factory returns IDLE phase with empty fields")
        fun `factory returns IDLE phase with empty fields`() {
            val state = StreamingState.idle()
            assertEquals(StreamingPhase.IDLE, state.phase)
            assertEquals("", state.thinkingText)
            assertEquals("", state.responseText)
            assertEquals(null, state.errorMessage)
            assertTrue(state.activeTools.isEmpty())
            assertTrue(state.toolResults.isEmpty())
            assertEquals(0, state.providerRound)
            assertEquals(0, state.toolCallCount)
            assertEquals(0L, state.llmDurationSecs)
        }
    }

    @Nested
    @DisplayName("StreamingState copy")
    inner class Copy {
        @Test
        @DisplayName("phase transition preserves accumulated text")
        fun `phase transition preserves accumulated text`() {
            val thinking =
                StreamingState(
                    phase = StreamingPhase.THINKING,
                    thinkingText = "Let me reason...",
                    providerRound = 1,
                )
            val responding = thinking.copy(phase = StreamingPhase.RESPONDING)
            assertEquals(StreamingPhase.RESPONDING, responding.phase)
            assertEquals("Let me reason...", responding.thinkingText)
            assertEquals(1, responding.providerRound)
        }

        @Test
        @DisplayName("provider round tracks through tool loop iterations")
        fun `provider round tracks through tool loop iterations`() {
            val round1 =
                StreamingState(
                    phase = StreamingPhase.CALLING_PROVIDER,
                    providerRound = 1,
                )
            val toolExec =
                round1.copy(
                    phase = StreamingPhase.TOOL_EXECUTING,
                    toolCallCount = 3,
                    llmDurationSecs = 5,
                )
            assertEquals(3, toolExec.toolCallCount)
            assertEquals(5L, toolExec.llmDurationSecs)

            val round2 =
                toolExec.copy(
                    phase = StreamingPhase.CALLING_PROVIDER,
                    providerRound = 2,
                    toolCallCount = 0,
                    llmDurationSecs = 0,
                )
            assertEquals(2, round2.providerRound)
            assertEquals(0, round2.toolCallCount)
        }
    }
}
