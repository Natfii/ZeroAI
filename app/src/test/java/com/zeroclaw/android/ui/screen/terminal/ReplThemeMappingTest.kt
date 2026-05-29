/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.terminal

import androidx.compose.ui.graphics.Color
import com.zeroclaw.android.ui.screen.terminal.theme.TerminalTheme
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReplThemeMappingTest {
    private val theme =
        TerminalTheme(
            name = "Test",
            bgArgb = 0xFF101010u,
            fgArgb = 0xFFEEEEEEu,
            cursorArgb = 0xFFFFFFFFu,
            palette =
                List(TerminalTheme.PALETTE_SIZE) { index ->
                    // Encode the index in the low byte so each entry is distinguishable.
                    (0xFF000000u or index.toUInt())
                },
            isDark = true,
        )

    @Test
    fun `foreground roles use theme foreground`() {
        assertEquals(Color(0xFFEEEEEE), theme.colorForRole(BlockRole.RESPONSE))
        assertEquals(Color(0xFFEEEEEE), theme.colorForRole(BlockRole.INPUT_TEXT))
    }

    @Test
    fun `accent roles use fixed palette indices`() {
        assertEquals(Color(0xFF000004), theme.colorForRole(BlockRole.INPUT_PROMPT))
        assertEquals(Color(0xFF000001), theme.colorForRole(BlockRole.ERROR))
        assertEquals(Color(0xFF000008), theme.colorForRole(BlockRole.SYSTEM))
        assertEquals(Color(0xFF000008), theme.colorForRole(BlockRole.STRUCTURED))
    }

    @Test
    fun `background and border resolve from theme`() {
        assertEquals(Color(0xFF101010), theme.replBackground())
        assertEquals(Color(0xFF000008), theme.replBorder())
    }

    @Test
    fun `colors are forced opaque even when palette entry lacks alpha`() {
        val noAlpha =
            theme.copy(
                fgArgb = 0x00EEEEEEu,
                palette = List(TerminalTheme.PALETTE_SIZE) { 0x00112233u },
            )
        assertEquals(Color(0xFFEEEEEE), noAlpha.colorForRole(BlockRole.RESPONSE))
        assertEquals(Color(0xFF112233), noAlpha.colorForRole(BlockRole.ERROR))
    }
}
