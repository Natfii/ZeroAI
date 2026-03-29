/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.terminal

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/** Default monospace font size for the TTY renderer. */
val TTY_DEFAULT_FONT_SIZE: TextUnit = 14.sp

/** Minimum font size the user may select for the TTY renderer. */
val TTY_MIN_FONT_SIZE: TextUnit = 8.sp

/** Maximum font size the user may select for the TTY renderer. */
val TTY_MAX_FONT_SIZE: TextUnit = 32.sp

/** Minimum char buffer size for drawTextRun. */
private const val MIN_CHAR_BUFFER = 512

/** Multiplier for grid columns to char buffer size (UTF-8 worst case). */
private const val CHAR_BUFFER_MULTIPLIER = 4

/**
 * Pre-computed font state for the native Canvas terminal renderer.
 *
 * Contains a single [android.graphics.Paint] instance that is mutated
 * in-place during rendering (color, bold, italic, underline), plus
 * cached metrics and reusable buffers that avoid per-frame allocation.
 *
 * @property cellWidthPx Width of one monospace character cell in pixels.
 * @property cellHeightPx Height of one character cell in pixels (line spacing).
 * @property baselinePx Offset from the top of the cell to the text baseline.
 * @property paint Shared Paint instance — mutated in-place during rendering.
 * @property charBuffer Reusable buffer for passing row text to drawTextRun.
 */
@Stable
class TtyFontState(
    val cellWidthPx: Float,
    val cellHeightPx: Float,
    val baselinePx: Float,
    val paint: Paint,
    val charBuffer: CharArray,
)

/**
 * Creates and remembers [TtyFontState] for a monospace cell at [fontSize].
 *
 * Recomputed whenever [fontSize] or the current display density changes.
 *
 * @param fontSize Desired font size for the TTY renderer.
 * @param gridCols Current grid column count, used to size the char buffer.
 * @return [TtyFontState] with cached metrics and pre-allocated buffers.
 */
@Composable
fun rememberFontState(
    fontSize: TextUnit = TTY_DEFAULT_FONT_SIZE,
    gridCols: Int = 80,
): TtyFontState {
    val density = LocalDensity.current

    return remember(fontSize, density, gridCols) {
        val textSizePx = with(density) { fontSize.toPx() }
        val paint =
            Paint().apply {
                isAntiAlias = true
                typeface = Typeface.MONOSPACE
                textSize = textSizePx
            }

        val cellWidth = paint.measureText("X")
        val cellHeight = paint.fontSpacing
        val baseline = -paint.ascent()

        val bufferSize = maxOf(gridCols * CHAR_BUFFER_MULTIPLIER, MIN_CHAR_BUFFER)

        TtyFontState(
            cellWidthPx = cellWidth,
            cellHeightPx = cellHeight,
            baselinePx = baseline,
            paint = paint,
            charBuffer = CharArray(bufferSize),
        )
    }
}

/**
 * Calculates how many columns and rows of monospace cells fit within
 * the given pixel dimensions.
 *
 * @param widthPx Available width in pixels.
 * @param heightPx Available height in pixels.
 * @param state Font state with measured cell dimensions.
 * @return A [Pair] of `(columns, rows)`, each at least 1.
 */
fun calculateGridSize(
    widthPx: Float,
    heightPx: Float,
    state: TtyFontState,
): Pair<Int, Int> {
    val cols = (widthPx / state.cellWidthPx).toInt().coerceAtLeast(1)
    val rows = (heightPx / state.cellHeightPx).toInt().coerceAtLeast(1)
    return cols to rows
}
