/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.terminal.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [TerminalThemeParser] covering Ghostty-format theme
 * parsing, edge cases, and error handling.
 */
class TerminalThemeParserTest {
    @Test
    fun `parse minimal theme with bg and fg`() {
        val content =
            """
            background = #1e1e2e
            foreground = #cdd6f4
            """.trimIndent()

        val theme = TerminalThemeParser.parse("Test", content)

        assertNotNull(theme)
        assertEquals("Test", theme!!.name)
        assertEquals(0xFF1E1E2Eu, theme.bgArgb)
        assertEquals(0xFFCDD6F4u, theme.fgArgb)
        assertTrue(theme.isDark)
    }

    @Test
    fun `parse theme with cursor color`() {
        val content =
            """
            background = #1e1e2e
            foreground = #cdd6f4
            cursor-color = #f5e0dc
            """.trimIndent()

        val theme = TerminalThemeParser.parse("Test", content)

        assertNotNull(theme)
        assertEquals(0xFFF5E0DCu, theme!!.cursorArgb)
    }

    @Test
    fun `parse theme with palette entries`() {
        val content =
            """
            background = #1e1e2e
            foreground = #cdd6f4
            palette = 0=#45475a
            palette = 1=#f38ba8
            palette = 15=#a6adc8
            """.trimIndent()

        val theme = TerminalThemeParser.parse("Test", content)

        assertNotNull(theme)
        assertEquals(0xFF45475Au, theme!!.palette[0])
        assertEquals(0xFFF38BA8u, theme.palette[1])
        assertEquals(0xFFA6ADC8u, theme.palette[15])
    }

    @Test
    fun `parse light theme detected by luminance`() {
        val content =
            """
            background = #faf4ed
            foreground = #575279
            """.trimIndent()

        val theme = TerminalThemeParser.parse("Rosé Pine Dawn", content)

        assertNotNull(theme)
        assertEquals(false, theme!!.isDark)
    }

    @Test
    fun `parse ignores comment lines and blank lines`() {
        val content =
            """
            # This is a comment
            background = #1e1e2e

            foreground = #cdd6f4
            """.trimIndent()

        val theme = TerminalThemeParser.parse("Test", content)

        assertNotNull(theme)
        assertEquals(0xFF1E1E2Eu, theme!!.bgArgb)
    }

    @Test
    fun `parse returns null when background missing`() {
        val content = "foreground = #cdd6f4"
        val theme = TerminalThemeParser.parse("Test", content)
        assertNull(theme)
    }

    @Test
    fun `parse returns null when foreground missing`() {
        val content = "background = #1e1e2e"
        val theme = TerminalThemeParser.parse("Test", content)
        assertNull(theme)
    }

    @Test
    fun `parse handles uppercase hex`() {
        val content =
            """
            background = #1E1E2E
            foreground = #CDD6F4
            """.trimIndent()

        val theme = TerminalThemeParser.parse("Test", content)

        assertNotNull(theme)
        assertEquals(0xFF1E1E2Eu, theme!!.bgArgb)
    }
}
