/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.service.ondevice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ReplyScrubber.scrubReplyText")
class ReplyScrubberTest {
    private val model = "gemma-4-e4b-it"

    @Test
    fun `strips a leading reply-sent status marker`() {
        val raw = "[reply sent: Chatter/non-actionable reply]\nHello there!"
        assertEquals("Hello there!", ReplyScrubber.scrubReplyText(model, raw))
    }

    @Test
    fun `strips a leading memory-context header`() {
        val raw = "[Memory context]\nThe capital of France is Paris."
        assertEquals(
            "The capital of France is Paris.",
            ReplyScrubber.scrubReplyText(model, raw),
        )
    }

    @Test
    fun `strips leading bracketed status markers`() {
        assertEquals("Sure!", ReplyScrubber.scrubReplyText(model, "[No reply sent: chatter]\nSure!"))
        assertEquals("Sure!", ReplyScrubber.scrubReplyText(model, "[Used tools: web_search]\nSure!"))
    }

    @Test
    fun `strips stacked leading bracketed markers`() {
        val raw = "[Memory context]\n[No reply sent: chatter]\nActual answer."
        assertEquals("Actual answer.", ReplyScrubber.scrubReplyText(model, raw))
    }

    @Test
    fun `preserves bare gate verdicts so the reply-gate is not corrupted`() {
        // The reply-gate's own classification flows through this scrub;
        // stripping REPLY / NO_REPLY would empty the verdict and break it.
        assertEquals("REPLY", ReplyScrubber.scrubReplyText(model, "REPLY"))
        assertEquals(
            "NO_REPLY[INFO]: chatter",
            ReplyScrubber.scrubReplyText(model, "NO_REPLY[INFO]: chatter"),
        )
    }

    @Test
    fun `collapses a degenerate repetition run down to the clean prefix`() {
        assertEquals(
            "Sure, I can help!",
            ReplyScrubber.scrubReplyText(model, "Sure, I can help! lololololololol"),
        )
    }

    @Test
    fun `truncates at the earliest point a degenerate run begins`() {
        // "'bah" cycles from index 1, so the only non-degenerate prefix is "I".
        assertEquals("I", ReplyScrubber.scrubReplyText(model, "I'bah'bah'bah'bah'bah'"))
    }

    @Test
    fun `leaves a clean reply untouched`() {
        val raw = "Hey there! 👋 I'm Zero. How can I help you out today? 😊"
        assertEquals(raw, ReplyScrubber.scrubReplyText(model, raw))
    }

    @Test
    fun `preserves legitimate short elongation`() {
        val raw = "soooo glad to help! Hmmmm, let me think."
        assertEquals(raw, ReplyScrubber.scrubReplyText(model, raw))
    }

    @Test
    fun `does not strip a bracket mid-sentence`() {
        val raw = "Your array [1, 2, 3] looks fine to me."
        assertEquals(raw, ReplyScrubber.scrubReplyText(model, raw))
    }

    @Test
    fun `caps pathologically long input so the repetition scan stays bounded`() {
        // A 60k-char degenerate reply must not drive the O(n^2) scan
        // quadratic: the input is capped before scanning. Returning at all
        // (fast) plus a bounded result proves the cap took effect.
        val huge = "ab".repeat(30_000)
        val result = ReplyScrubber.scrubReplyText(model, huge)
        assertTrue(result.length <= 8 * 1024, "scrub must cap input length")
    }

    @Test
    fun `is a no-op for non-gemma models`() {
        val raw = "[Memory context]\nstuff"
        assertEquals(raw, ReplyScrubber.scrubReplyText("phi-4-mini", raw))
    }
}
