/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.service

import com.zeroclaw.android.model.RouteHint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HeuristicClassifierTest {
    // --- Code detection ---

    @Test
    fun `fenced code block routes to Complex`() {
        val msg = "Can you fix this?\n```kotlin\nfun main() {}\n```"
        assertEquals(RouteHint.COMPLEX, HeuristicClassifier.classify(msg))
    }

    @Test
    fun `inline code routes to Complex`() {
        val msg = "What does `listOf()` return?"
        assertEquals(RouteHint.COMPLEX, HeuristicClassifier.classify(msg))
    }

    // --- Structured data ---

    @Test
    fun `JSON object routes to Complex`() {
        val msg = """Parse this: {"name": "test", "value": 42}"""
        assertEquals(RouteHint.COMPLEX, HeuristicClassifier.classify(msg))
    }

    @Test
    fun `SQL query routes to Complex`() {
        val msg = "SELECT * FROM users WHERE active = true"
        assertEquals(RouteHint.COMPLEX, HeuristicClassifier.classify(msg))
    }

    // --- Tool use indicators ---

    @Test
    fun `search command routes to ToolUse`() {
        val msg = "Search for restaurants near me"
        assertEquals(RouteHint.TOOL_USE, HeuristicClassifier.classify(msg))
    }

    @Test
    fun `calculate request routes to ToolUse`() {
        val msg = "Calculate 15% tip on $47.50"
        assertEquals(RouteHint.TOOL_USE, HeuristicClassifier.classify(msg))
    }

    @Test
    fun `look up request routes to ToolUse`() {
        val msg = "Look up the weather in Tokyo"
        assertEquals(RouteHint.TOOL_USE, HeuristicClassifier.classify(msg))
    }

    // --- Simple patterns ---

    @Test
    fun `greeting routes to Simple`() {
        assertEquals(RouteHint.SIMPLE, HeuristicClassifier.classify("Hello"))
    }

    @Test
    fun `short factual question routes to Simple`() {
        assertEquals(RouteHint.SIMPLE, HeuristicClassifier.classify("What is the capital of France?"))
    }

    @Test
    fun `define request routes to Simple`() {
        assertEquals(RouteHint.SIMPLE, HeuristicClassifier.classify("Define photosynthesis"))
    }

    @Test
    fun `who is question routes to Simple`() {
        assertEquals(RouteHint.SIMPLE, HeuristicClassifier.classify("Who is Alan Turing?"))
    }

    // --- Complex patterns ---

    @Test
    fun `explain request routes to Complex`() {
        assertEquals(
            RouteHint.COMPLEX,
            HeuristicClassifier.classify("Explain how transformers work in machine learning"),
        )
    }

    @Test
    fun `compare request routes to Complex`() {
        assertEquals(
            RouteHint.COMPLEX,
            HeuristicClassifier.classify("Compare React and Vue for a large enterprise app"),
        )
    }

    @Test
    fun `multi-part numbered list routes to Complex`() {
        val msg = "1) What is Rust? 2) How does it compare to C++? 3) Should I use it?"
        assertEquals(RouteHint.COMPLEX, HeuristicClassifier.classify(msg))
    }

    @Test
    fun `long message over 200 words defaults to Complex`() {
        val msg = "word ".repeat(201)
        assertEquals(RouteHint.COMPLEX, HeuristicClassifier.classify(msg))
    }

    // --- Creative patterns ---

    @Test
    fun `write a story routes to Creative`() {
        assertEquals(RouteHint.CREATIVE, HeuristicClassifier.classify("Write a story about a robot dog"))
    }

    @Test
    fun `brainstorm request routes to Creative`() {
        assertEquals(RouteHint.CREATIVE, HeuristicClassifier.classify("Brainstorm names for my startup"))
    }

    // --- Ambiguous defaults to Complex ---

    @Test
    fun `ambiguous medium-length message defaults to Complex`() {
        val msg = "I need help with my project, there are several issues and I'm not sure where to start"
        assertEquals(RouteHint.COMPLEX, HeuristicClassifier.classify(msg))
    }

    // --- Edge cases ---

    @Test
    fun `empty string routes to Simple`() {
        assertEquals(RouteHint.SIMPLE, HeuristicClassifier.classify(""))
    }

    @Test
    fun `whitespace-only routes to Simple`() {
        assertEquals(RouteHint.SIMPLE, HeuristicClassifier.classify("   "))
    }
}
