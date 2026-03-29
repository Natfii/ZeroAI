/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.terminal

// SAFETY: All shift operations use `ushr` (unsigned right shift), never `shr`.
// UniFFI maps Rust i64 to Kotlin Long (signed). Packed values with bit 55+
// set arrive as negative Longs. Arithmetic `shr` would sign-extend and
// corrupt color extraction. `ushr` zero-fills from the left, which is correct.

/**
 * Extracts the 24-bit foreground RGB value from a packed cell style.
 *
 * @return RGB value in range `0x000000..0xFFFFFF`, or `0` for terminal default.
 */
@Suppress("MagicNumber")
internal inline fun Long.packedFgRgb(): Int = ((this ushr 32) and 0xFFFFFFL).toInt()

/**
 * Extracts the foreground color as an opaque ARGB int for Canvas drawing.
 *
 * @return ARGB value with alpha `0xFF`, or `0` if foreground is terminal default.
 */
@Suppress("MagicNumber")
internal inline fun Long.packedFgArgb(): Int {
    val rgb = packedFgRgb()
    return if (rgb == 0) 0 else rgb or 0xFF000000.toInt()
}

/**
 * Extracts the 24-bit background RGB value from a packed cell style.
 *
 * @return RGB value in range `0x000000..0xFFFFFF`, or `0` for terminal default.
 */
@Suppress("MagicNumber")
internal inline fun Long.packedBgRgb(): Int = ((this ushr 8) and 0xFFFFFFL).toInt()

/**
 * Extracts the background color as an opaque ARGB int for Canvas drawing.
 *
 * @return ARGB value with alpha `0xFF`, or `0` if background is terminal default.
 */
@Suppress("MagicNumber")
internal inline fun Long.packedBgArgb(): Int {
    val rgb = packedBgRgb()
    return if (rgb == 0) 0 else rgb or 0xFF000000.toInt()
}

/** Whether the cell text is bold (bit 0). */
@Suppress("MagicNumber")
internal inline fun Long.packedIsBold(): Boolean = (this and 0x01L) != 0L

/** Whether the cell text is italic (bit 1). */
@Suppress("MagicNumber")
internal inline fun Long.packedIsItalic(): Boolean = (this and 0x02L) != 0L

/** Whether the cell text is underlined (bit 2). */
@Suppress("MagicNumber")
internal inline fun Long.packedIsUnderline(): Boolean = (this and 0x04L) != 0L

/** Whether the cell text has strikethrough (bit 3). */
@Suppress("MagicNumber")
internal inline fun Long.packedIsStrikethrough(): Boolean = (this and 0x08L) != 0L

/** Whether the cell text is dim (bit 4). */
@Suppress("MagicNumber")
internal inline fun Long.packedIsDim(): Boolean = (this and 0x10L) != 0L

/** Whether the cell colors are inverted (bit 5). */
@Suppress("MagicNumber")
internal inline fun Long.packedIsInverse(): Boolean = (this and 0x20L) != 0L

/** Whether the cell text is invisible (bit 6). */
@Suppress("MagicNumber")
internal inline fun Long.packedIsInvisible(): Boolean = (this and 0x40L) != 0L

/** Bit 7 — blink. */
@Suppress("MagicNumber")
internal inline fun Long.packedIsBlink(): Boolean = (this ushr 7 and 1L) != 0L

/** Bits 56-58 — underline style (0=none, 1=single, 2=double, 3=curly, 4=dotted, 5=dashed). */
@Suppress("MagicNumber")
internal inline fun Long.packedUnderlineStyle(): Int = ((this ushr 56) and 0x7L).toInt()

/** Bit 59 — overline. */
@Suppress("MagicNumber")
internal inline fun Long.packedIsOverline(): Boolean = (this ushr 59 and 1L) != 0L
