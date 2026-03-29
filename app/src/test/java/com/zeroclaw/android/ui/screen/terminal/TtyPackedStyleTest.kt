/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.terminal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TtyPackedStyleTest {
    @Test
    fun `default style extracts zero colors and no flags`() {
        val style = 0L
        assertEquals(0, style.packedFgRgb())
        assertEquals(0, style.packedBgRgb())
        assertFalse(style.packedIsBold())
        assertFalse(style.packedIsItalic())
        assertFalse(style.packedIsUnderline())
    }

    @Test
    fun `fg color extraction with high bits set`() {
        val style = (0xFFL shl 48) or (0x80L shl 40) or (0x40L shl 32)
        assertEquals(0xFF8040, style.packedFgRgb())
        assertEquals(0xFF_FF8040.toInt(), style.packedFgArgb())
    }

    @Test
    fun `bg color extraction`() {
        val style = (0x10L shl 24) or (0x20L shl 16) or (0x30L shl 8)
        assertEquals(0x102030, style.packedBgRgb())
        assertEquals(0xFF_102030.toInt(), style.packedBgArgb())
    }

    @Test
    fun `bold italic underline flags`() {
        val style = 0b0000_0111L
        assertTrue(style.packedIsBold())
        assertTrue(style.packedIsItalic())
        assertTrue(style.packedIsUnderline())
        assertFalse(style.packedIsStrikethrough())
        assertFalse(style.packedIsInverse())
    }

    @Test
    fun `negative Long from high fg bits still extracts correctly`() {
        val fg = (0xFFL shl 48) or (0xFFL shl 40) or (0xFFL shl 32)
        // Simulate a Rust i64 with bit 63 set (e.g. reserved bits non-zero), as can
        // happen when the high byte of fg (bits 48-55) causes the underlying u64 to be
        // reinterpreted as a negative i64 after casting. ushr must zero-fill, not sign-extend.
        val style = fg or Long.MIN_VALUE
        assertTrue(style < 0, "style should be negative due to bit 63 sign bit")
        assertEquals(0xFFFFFF, style.packedFgRgb())
        assertEquals(0xFF_FFFFFF.toInt(), style.packedFgArgb())
    }

    @Test
    fun `full style with all fields`() {
        val flags = 1L or 4L or 32L
        val fg = (0xAAL shl 48) or (0xBBL shl 40) or (0xCCL shl 32)
        val bg = (0x11L shl 24) or (0x22L shl 16) or (0x33L shl 8)
        val style = fg or bg or flags

        assertTrue(style.packedIsBold())
        assertFalse(style.packedIsItalic())
        assertTrue(style.packedIsUnderline())
        assertTrue(style.packedIsInverse())
        assertEquals(0xAABBCC, style.packedFgRgb())
        assertEquals(0x112233, style.packedBgRgb())
    }

    @Test
    fun `packedIsBlink extracts bit 7`() {
        val blink = 1L shl 7
        assertTrue(blink.packedIsBlink())
        assertFalse(0L.packedIsBlink())
    }

    @Test
    fun `packedUnderlineStyle extracts bits 56-58`() {
        for (style in 0..5) {
            val packed = style.toLong() shl 56
            assertEquals(style, packed.packedUnderlineStyle())
        }
    }

    @Test
    fun `packedIsOverline extracts bit 59`() {
        val overline = 1L shl 59
        assertTrue(overline.packedIsOverline())
        assertFalse(0L.packedIsOverline())
    }

    @Test
    fun `all flags combined do not interfere`() {
        val all =
            (1L shl 0) or // bold
                (1L shl 1) or // italic
                (1L shl 2) or // underline
                (1L shl 3) or // strikethrough
                (1L shl 4) or // dim
                (1L shl 5) or // inverse
                (1L shl 6) or // invisible
                (1L shl 7) or // blink
                (3L shl 56) or // underline style = curly
                (1L shl 59) // overline
        assertTrue(all.packedIsBold())
        assertTrue(all.packedIsItalic())
        assertTrue(all.packedIsUnderline())
        assertTrue(all.packedIsStrikethrough())
        assertTrue(all.packedIsDim())
        assertTrue(all.packedIsInverse())
        assertTrue(all.packedIsInvisible())
        assertTrue(all.packedIsBlink())
        assertEquals(3, all.packedUnderlineStyle())
        assertTrue(all.packedIsOverline())
    }
}
