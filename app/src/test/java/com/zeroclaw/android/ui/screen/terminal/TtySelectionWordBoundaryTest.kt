/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.terminal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TtySelectionWordBoundaryTest {
    private fun offsets(n: Int): List<UInt> = (0 until n).map { it.toUInt() }

    @Test
    fun `finds word in middle of row`() {
        val text = "hello world test"
        val result = findWordBoundaries(text, offsets(16), 6)
        assertEquals(6 to 10, result)
    }

    @Test
    fun `returns null for whitespace`() {
        val text = "hello world"
        assertNull(findWordBoundaries(text, offsets(11), 5))
    }

    @Test
    fun `returns null for out of bounds`() {
        val text = "hello"
        assertNull(findWordBoundaries(text, offsets(5), 10))
        assertNull(findWordBoundaries(text, offsets(5), -1))
    }

    @Test
    fun `handles start of row`() {
        val text = "hello world"
        assertEquals(0 to 4, findWordBoundaries(text, offsets(11), 0))
    }

    @Test
    fun `handles end of row`() {
        val text = "hello world"
        assertEquals(6 to 10, findWordBoundaries(text, offsets(11), 10))
    }

    @Test
    fun `returns null for empty row`() {
        assertNull(findWordBoundaries("", emptyList(), 0))
    }

    @Test
    fun `stops at punctuation`() {
        val text = "ls|grep foo"
        assertEquals(0 to 1, findWordBoundaries(text, offsets(11), 0))
        assertNull(findWordBoundaries(text, offsets(11), 2))
        assertEquals(3 to 6, findWordBoundaries(text, offsets(11), 3))
    }

    @Test
    fun `handles single character word`() {
        val text = "a b c"
        assertEquals(0 to 0, findWordBoundaries(text, offsets(5), 0))
        assertEquals(2 to 2, findWordBoundaries(text, offsets(5), 2))
        assertEquals(4 to 4, findWordBoundaries(text, offsets(5), 4))
    }
}
