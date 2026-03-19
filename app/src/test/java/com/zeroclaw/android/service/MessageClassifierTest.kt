/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.service

import com.zeroclaw.android.model.RouteHint
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MessageClassifierTest {
    @Test
    fun `when not foreground uses heuristic`() =
        runTest {
            val classifier =
                MessageClassifier(
                    nanoClassifier = null,
                    isForeground = { false },
                )
            val result = classifier.classify("Hello")
            assertEquals(RouteHint.SIMPLE, result)
        }

    @Test
    fun `when foreground but no nano uses heuristic`() =
        runTest {
            val classifier =
                MessageClassifier(
                    nanoClassifier = null,
                    isForeground = { true },
                )
            val result = classifier.classify("Explain quantum computing")
            assertEquals(RouteHint.COMPLEX, result)
        }

    @Test
    fun `heuristic correctly classifies code`() =
        runTest {
            val classifier =
                MessageClassifier(
                    nanoClassifier = null,
                    isForeground = { false },
                )
            val result = classifier.classify("Fix this:\n```\nval x = 1\n```")
            assertEquals(RouteHint.COMPLEX, result)
        }
}
