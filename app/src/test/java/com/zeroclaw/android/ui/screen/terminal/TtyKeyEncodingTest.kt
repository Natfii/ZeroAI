/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.terminal

import com.zeroclaw.android.ui.screen.terminal.TerminalViewModel.Companion.encodeTtyKey
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Verifies the PTY byte sequences emitted by [encodeTtyKey] for the TTY key
 * row, including standard xterm CSI modifier encoding for Ctrl/Alt on the
 * cursor and navigation keys.
 */
class TtyKeyEncodingTest {
    private val esc: Byte = 0x1B
    private val bracket: Byte = 0x5B

    @Test
    fun `unmodified cursor keys emit plain CSI sequences`() {
        assertArrayEquals(byteArrayOf(esc, bracket, 0x41), encodeTtyKey(TtySpecialKey.UP))
        assertArrayEquals(byteArrayOf(esc, bracket, 0x42), encodeTtyKey(TtySpecialKey.DOWN))
        assertArrayEquals(byteArrayOf(esc, bracket, 0x43), encodeTtyKey(TtySpecialKey.RIGHT))
        assertArrayEquals(byteArrayOf(esc, bracket, 0x44), encodeTtyKey(TtySpecialKey.LEFT))
        assertArrayEquals(byteArrayOf(esc, bracket, 0x48), encodeTtyKey(TtySpecialKey.HOME))
        assertArrayEquals(byteArrayOf(esc, bracket, 0x46), encodeTtyKey(TtySpecialKey.END))
    }

    @Test
    fun `ctrl modifies cursor keys with xterm parameter 5`() {
        // ESC [ 1 ; 5 A
        assertArrayEquals(
            byteArrayOf(esc, bracket, 0x31, 0x3B, 0x35, 0x41),
            encodeTtyKey(TtySpecialKey.UP, ctrl = true),
        )
        assertArrayEquals(
            byteArrayOf(esc, bracket, 0x31, 0x3B, 0x35, 0x44),
            encodeTtyKey(TtySpecialKey.LEFT, ctrl = true),
        )
    }

    @Test
    fun `alt modifies cursor keys with xterm parameter 3 and ctrl plus alt is 7`() {
        // ESC [ 1 ; 3 A
        assertArrayEquals(
            byteArrayOf(esc, bracket, 0x31, 0x3B, 0x33, 0x41),
            encodeTtyKey(TtySpecialKey.UP, alt = true),
        )
        // ESC [ 1 ; 7 A
        assertArrayEquals(
            byteArrayOf(esc, bracket, 0x31, 0x3B, 0x37, 0x41),
            encodeTtyKey(TtySpecialKey.UP, ctrl = true, alt = true),
        )
    }

    @Test
    fun `page keys emit tilde sequences with optional modifier`() {
        assertArrayEquals(byteArrayOf(esc, bracket, 0x35, 0x7E), encodeTtyKey(TtySpecialKey.PAGE_UP))
        assertArrayEquals(byteArrayOf(esc, bracket, 0x36, 0x7E), encodeTtyKey(TtySpecialKey.PAGE_DOWN))
        // Ctrl+PgUp = ESC [ 5 ; 5 ~
        assertArrayEquals(
            byteArrayOf(esc, bracket, 0x35, 0x3B, 0x35, 0x7E),
            encodeTtyKey(TtySpecialKey.PAGE_UP, ctrl = true),
        )
    }

    @Test
    fun `control bytes are correct and alt prefixes them with esc`() {
        assertArrayEquals(byteArrayOf(0x09), encodeTtyKey(TtySpecialKey.TAB))
        assertArrayEquals(byteArrayOf(0x1B), encodeTtyKey(TtySpecialKey.ESC))
        assertArrayEquals(byteArrayOf(0x0D), encodeTtyKey(TtySpecialKey.ENTER))
        assertArrayEquals(byteArrayOf(esc, 0x09), encodeTtyKey(TtySpecialKey.TAB, alt = true))
        assertArrayEquals(byteArrayOf(esc, 0x0D), encodeTtyKey(TtySpecialKey.ENTER, alt = true))
    }

    @Test
    fun `punctuation keys emit their literal char and ignore ctrl`() {
        assertArrayEquals(byteArrayOf(0x7C), encodeTtyKey(TtySpecialKey.PIPE))
        assertArrayEquals(byteArrayOf(0x2F), encodeTtyKey(TtySpecialKey.SLASH))
        assertArrayEquals(byteArrayOf(0x7E), encodeTtyKey(TtySpecialKey.TILDE))
        assertArrayEquals(byteArrayOf(0x2D), encodeTtyKey(TtySpecialKey.DASH))
        // Ctrl no longer mangles punctuation into surprising control codes.
        assertArrayEquals(byteArrayOf(0x7C), encodeTtyKey(TtySpecialKey.PIPE, ctrl = true))
        assertArrayEquals(byteArrayOf(0x7E), encodeTtyKey(TtySpecialKey.TILDE, ctrl = true))
        // Alt still prefixes punctuation with ESC (meta).
        assertArrayEquals(byteArrayOf(esc, 0x7C), encodeTtyKey(TtySpecialKey.PIPE, alt = true))
    }

    @Test
    fun `modifier toggle keys emit nothing`() {
        assertEquals(0, encodeTtyKey(TtySpecialKey.CTRL).size)
        assertEquals(0, encodeTtyKey(TtySpecialKey.ALT).size)
    }
}
