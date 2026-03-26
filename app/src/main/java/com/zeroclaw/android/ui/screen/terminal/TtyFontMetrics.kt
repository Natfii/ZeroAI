/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.terminal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/** Default monospace font size for the TTY renderer. */
val TTY_DEFAULT_FONT_SIZE: TextUnit = 14.sp

/** Minimum font size the user may select for the TTY renderer. */
val TTY_MIN_FONT_SIZE: TextUnit = 8.sp

/** Maximum font size the user may select for the TTY renderer. */
val TTY_MAX_FONT_SIZE: TextUnit = 32.sp

/**
 * Measured dimensions of a single monospace cell in the TTY renderer.
 *
 * All pixel values are resolved at the density active when
 * [rememberCellMetrics] is called and are valid only for the
 * [textStyle] and [measurer] they were derived from. Callers must
 * re-derive metrics whenever [textStyle] or the display density changes.
 *
 * @property cellWidthPx Width of one character cell in pixels.
 * @property cellHeightPx Height of one character cell in pixels.
 * @property baselinePx Offset from the top of the cell to the text baseline, in pixels.
 * @property textStyle The [TextStyle] that was used to produce these measurements.
 * @property measurer The [TextMeasurer] instance used to produce these measurements.
 */
@Stable
data class TtyCellMetrics(
    val cellWidthPx: Float,
    val cellHeightPx: Float,
    val baselinePx: Float,
    val textStyle: TextStyle,
    val measurer: TextMeasurer,
)

/**
 * Creates and remembers [TtyCellMetrics] for a monospace cell at [fontSize].
 *
 * The character "W" is measured with [FontFamily.Monospace] at [fontSize]
 * to derive the canonical cell dimensions. Results are recomputed whenever
 * [fontSize] or the current display density changes; otherwise the cached
 * value is returned without re-measurement.
 *
 * Safe to call from the main thread inside a Composable.
 *
 * @param fontSize Desired font size for the TTY renderer.
 * @return [TtyCellMetrics] representing one character cell at [fontSize].
 */
@Composable
fun rememberCellMetrics(fontSize: TextUnit = TTY_DEFAULT_FONT_SIZE): TtyCellMetrics {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current

    return remember(fontSize, density) {
        val style =
            TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = fontSize,
            )
        val result =
            measurer.measure(
                text = "W",
                style = style,
            )
        TtyCellMetrics(
            cellWidthPx = result.size.width.toFloat(),
            cellHeightPx = result.size.height.toFloat(),
            baselinePx = result.firstBaseline,
            textStyle = style,
            measurer = measurer,
        )
    }
}

/**
 * Calculates how many columns and rows of monospace cells fit within the given pixel dimensions.
 *
 * Each axis is floored to whole cells and then clamped to a minimum of 1
 * so the returned grid is never degenerate.
 *
 * @param widthPx Available width in pixels.
 * @param heightPx Available height in pixels.
 * @param metrics Measured cell dimensions to use for the calculation.
 * @return A [Pair] of `(columns, rows)`, each at least 1.
 */
fun calculateGridSize(
    widthPx: Float,
    heightPx: Float,
    metrics: TtyCellMetrics,
): Pair<Int, Int> {
    val cols = (widthPx / metrics.cellWidthPx).toInt().coerceAtLeast(1)
    val rows = (heightPx / metrics.cellHeightPx).toInt().coerceAtLeast(1)
    return cols to rows
}
