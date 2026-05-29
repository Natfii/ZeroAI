/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.terminal

import com.zeroclaw.android.ui.screen.terminal.TerminalViewModel.Companion.encodeTypedText
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Verifies the PTY byte encoding of typed soft-keyboard text via
 * [encodeTypedText], covering plain text, sticky Ctrl/Alt modifiers, and the
 * paste-safety bail-out for control-laden input.
 */
class TtyInputRoutingTest {
    @Test
    fun `plain text encodes as utf-8`() {
        assertArrayEquals(byteArrayOf(0x6C, 0x73), encodeTypedText("ls", ctrl = false, alt = false))
    }

    @Test
    fun `multi-byte unicode encodes as utf-8`() {
        assertArrayEquals(
            "ä".toByteArray(Charsets.UTF_8),
            encodeTypedText("ä", ctrl = false, alt = false),
        )
    }

    @Test
    fun `ctrl maps a single letter to its c0 control code`() {
        assertArrayEquals(byteArrayOf(0x03), encodeTypedText("c", ctrl = true, alt = false))
        assertArrayEquals(byteArrayOf(0x01), encodeTypedText("a", ctrl = true, alt = false))
        // Case-insensitive: an uppercase letter yields the same control code.
        assertArrayEquals(byteArrayOf(0x03), encodeTypedText("C", ctrl = true, alt = false))
    }

    @Test
    fun `ctrl maps the bracket range and sends other chars literally`() {
        // Ctrl-[ = ESC (0x1B); '[' is in the @.._ control range.
        assertArrayEquals(byteArrayOf(0x1B), encodeTypedText("[", ctrl = true, alt = false))
        // Ctrl+digit/symbol is outside the range, so the char is sent literally.
        assertArrayEquals(byteArrayOf(0x31), encodeTypedText("1", ctrl = true, alt = false))
        assertArrayEquals(byteArrayOf(0x2E), encodeTypedText(".", ctrl = true, alt = false))
    }

    @Test
    fun `ctrl only applies to a single character`() {
        // A committed word (autocorrect/swipe) is sent literally, not mangled.
        assertArrayEquals(
            "abc".toByteArray(Charsets.UTF_8),
            encodeTypedText("abc", ctrl = true, alt = false),
        )
    }

    @Test
    fun `alt prefixes a single character with esc`() {
        assertArrayEquals(byteArrayOf(0x1B, 0x78), encodeTypedText("x", ctrl = false, alt = true))
    }

    @Test
    fun `control-laden text routes to paste safety`() {
        assertNull(encodeTypedText("a\rb", ctrl = false, alt = false))
        assertNull(encodeTypedText("a\nb", ctrl = false, alt = false))
        assertNull(encodeTypedText("a" + 27.toChar() + "[D", ctrl = false, alt = false))
    }

    @Test
    fun `empty text encodes to no bytes`() {
        assertEquals(0, encodeTypedText("", ctrl = false, alt = false)?.size)
    }
}
