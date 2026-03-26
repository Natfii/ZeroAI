/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.terminal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import com.zeroclaw.ffi.TtyCursorStyle
import com.zeroclaw.ffi.TtyRenderFrame
import com.zeroclaw.ffi.TtyRenderRow

/**
 * Canvas-based terminal renderer that draws a fixed-size monospace cell grid.
 *
 * Each frame received via [frame] is painted into a [Canvas] composable: the full
 * background is cleared with [TtyRenderFrame.defaultBgArgb], then each row is
 * rendered by [drawRow], and finally the cursor is drawn by [drawCursor] when both
 * [cursorVisible] and [TtyRenderFrame] indicate the cursor should appear.
 *
 * Pinch-to-zoom adjusts [fontSize] via [onFontSizeChange], clamped to the range
 * [[TTY_MIN_FONT_SIZE], [TTY_MAX_FONT_SIZE]]. Tap gestures notify [onTap] so the
 * caller can request IME focus. Grid dimension changes are reported once through
 * [onSizeChanged] whenever the computed column or row count changes.
 *
 * @param frame Current render frame produced by the VT backend, or null when
 *   nothing has been rendered yet.
 * @param fontSize Font size in sp used to derive [TtyCellMetrics] for the grid.
 * @param onFontSizeChange Invoked with the new font size (in sp, as a raw [Float])
 *   after a pinch-to-zoom gesture is resolved.
 * @param onTap Invoked on a single tap so the caller can request soft-keyboard focus.
 * @param onSizeChanged Invoked with the new `(cols, rows)` grid dimensions whenever
 *   the available canvas size or cell metrics produce a different grid than before.
 * @param cursorVisible External blink-phase signal; when false the cursor is hidden
 *   regardless of what [TtyRenderFrame] reports.
 * @param modifier [Modifier] applied to the underlying [Canvas].
 */
@Composable
fun TtyCanvasView(
    frame: TtyRenderFrame?,
    fontSize: Float,
    onFontSizeChange: (Float) -> Unit,
    onTap: () -> Unit,
    onSizeChanged: (cols: Int, rows: Int) -> Unit,
    cursorVisible: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val metrics = rememberCellMetrics(fontSize.sp)
    var lastGridSize by remember { mutableStateOf(Pair(0, 0)) }

    Canvas(
        modifier =
            modifier
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { onTap() })
                }.pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ ->
                        val newSize =
                            (fontSize * zoom)
                                .coerceIn(TTY_MIN_FONT_SIZE.value, TTY_MAX_FONT_SIZE.value)
                        onFontSizeChange(newSize)
                    }
                },
    ) {
        val (gridCols, gridRows) = calculateGridSize(size.width, size.height, metrics)
        val newGrid = gridCols to gridRows
        if (newGrid != lastGridSize) {
            lastGridSize = newGrid
            onSizeChanged(gridCols, gridRows)
        }

        if (frame == null) return@Canvas

        val defaultBg = frame.defaultBgArgb.toComposeColor()
        val defaultFg = frame.defaultFgArgb.toComposeColor()

        drawRect(color = defaultBg, size = size)

        val totalRows = minOf(frame.numRows.toInt(), frame.rows.size)
        val visibleRows = minOf(totalRows, gridRows)
        val startRow = totalRows - visibleRows

        for (i in 0 until visibleRows) {
            drawRow(
                row = frame.rows[startRow + i],
                rowIdx = i,
                cols = gridCols,
                metrics = metrics,
                defaultBg = defaultBg,
                defaultFg = defaultFg,
            )
        }

        val cursor = frame.cursor
        val cursorScreenRow = cursor.row.toInt()
        if (cursorVisible && cursor.visible && cursorScreenRow in 0 until visibleRows) {
            drawCursor(
                col = cursor.col.toInt(),
                row = cursorScreenRow,
                style = cursor.style,
                metrics = metrics,
                color = defaultFg,
            )
        }
    }
}

@Suppress("LongParameterList", "CognitiveComplexMethod")
private fun DrawScope.drawRow(
    row: TtyRenderRow,
    rowIdx: Int,
    @Suppress("UnusedParameter") cols: Int,
    metrics: TtyCellMetrics,
    @Suppress("UnusedParameter") defaultBg: Color,
    defaultFg: Color,
) {
    val y = rowIdx * metrics.cellHeightPx

    for (span in row.spans) {
        if (span.bgArgb == 0u) continue
        val x = span.startCol.toInt() * metrics.cellWidthPx
        val spanWidth = (span.endCol.toInt() - span.startCol.toInt()) * metrics.cellWidthPx
        drawRect(
            color = span.bgArgb.toComposeColor(),
            topLeft = Offset(x, y),
            size = Size(spanWidth, metrics.cellHeightPx),
        )
    }

    val textLen = row.text.length
    val annotated =
        buildAnnotatedString {
            withStyle(SpanStyle(color = defaultFg)) {
                append(row.text)
            }
            for (span in row.spans) {
                val start = span.startCol.toInt().coerceAtMost(textLen)
                val end = span.endCol.toInt().coerceAtMost(textLen)
                if (start >= end) continue

                val fgColor = if (span.fgArgb != 0u) span.fgArgb.toComposeColor() else null
                val fontWeight = if (span.bold) FontWeight.Bold else null
                val fontStyle = if (span.italic) FontStyle.Italic else null
                val textDecoration = if (span.underline) TextDecoration.Underline else null

                addStyle(
                    style =
                        SpanStyle(
                            color = fgColor ?: Color.Unspecified,
                            fontWeight = fontWeight,
                            fontStyle = fontStyle,
                            textDecoration = textDecoration,
                        ),
                    start = start,
                    end = end,
                )
            }
        }

    val result = metrics.measurer.measure(annotated, metrics.textStyle)
    drawText(result, topLeft = Offset(0f, y))
}

private fun DrawScope.drawCursor(
    col: Int,
    row: Int,
    style: TtyCursorStyle,
    metrics: TtyCellMetrics,
    color: Color,
) {
    val x = col * metrics.cellWidthPx
    val y = row * metrics.cellHeightPx

    when (style) {
        TtyCursorStyle.BLOCK ->
            drawRect(
                color = color.copy(alpha = 0.7f),
                topLeft = Offset(x, y),
                size = Size(metrics.cellWidthPx, metrics.cellHeightPx),
            )
        TtyCursorStyle.BAR ->
            drawRect(
                color = color,
                topLeft = Offset(x, y),
                size = Size(2f, metrics.cellHeightPx),
            )
        TtyCursorStyle.UNDERLINE ->
            drawRect(
                color = color,
                topLeft = Offset(x, y + metrics.cellHeightPx - 2f),
                size = Size(metrics.cellWidthPx, 2f),
            )
        TtyCursorStyle.BLOCK_HOLLOW ->
            drawRect(
                color = color,
                topLeft = Offset(x, y),
                size = Size(metrics.cellWidthPx, metrics.cellHeightPx),
                style = Stroke(width = 1.5f),
            )
    }
}

private fun UInt.toComposeColor(): Color = Color(this.toInt())
