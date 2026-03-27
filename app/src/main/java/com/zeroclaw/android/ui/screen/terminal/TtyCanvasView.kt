/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.terminal

import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import com.zeroclaw.ffi.TtyCursorStyle
import com.zeroclaw.ffi.TtyDirtyState
import com.zeroclaw.ffi.TtyRenderFrame
import com.zeroclaw.ffi.TtyRenderRow

// ── CachedRow for damage tracking ──────────────────────────────────────

/**
 * Per-row snapshot used for damage tracking.
 *
 * When a row is drawn fresh, its text and styles are cached here so
 * that on subsequent CLEAN or PARTIAL frames the row can be redrawn
 * from cache without re-extracting style information.
 *
 * @property text Concatenated UTF-8 cell text for the row.
 * @property styles Packed style longs, one per column.
 */
private class CachedRow(
    var text: String = "",
    var styles: List<Long> = emptyList(),
)

// ── Constants ──────────────────────────────────────────────────────────

/** Alpha applied to a solid block cursor so the glyph underneath is visible. */
private const val CURSOR_BLOCK_ALPHA = 0xB3

/** Width (px) of the bar cursor. */
private const val CURSOR_BAR_WIDTH = 2f

/** Height (px) of the underline cursor. */
private const val CURSOR_UNDERLINE_HEIGHT = 2f

/** Stroke width (px) of the hollow block cursor outline. */
private const val CURSOR_HOLLOW_STROKE = 1.5f

/** Italic text skew factor. */
private const val ITALIC_SKEW = -0.25f

/** Dim text brightness multiplier numerator. */
private const val DIM_MULTIPLIER_NUM = 2

/** Dim text brightness multiplier denominator. */
private const val DIM_MULTIPLIER_DEN = 3

/** Opaque alpha mask for ARGB construction. */
private const val ALPHA_OPAQUE = 0xFF000000.toInt()

/**
 * Canvas-based terminal renderer using native [Canvas.drawTextRun].
 *
 * Draws a monospace cell grid from the [TtyRenderFrame] provided by
 * [frameProvider]. The frame lambda is evaluated during the draw phase
 * only, avoiding recomposition when the frame changes. Run batching
 * groups consecutive same-styled cells into a single drawTextRun call.
 * Per-row [CachedRow] damage tracking skips unchanged rows on PARTIAL
 * and CLEAN dirty states.
 *
 * Pinch-to-zoom adjusts font size via [onFontSizeChange], clamped to
 * [[TTY_MIN_FONT_SIZE], [TTY_MAX_FONT_SIZE]]. Tap gestures notify
 * [onTap] so the caller can request IME focus.
 *
 * @param frameProvider Lambda returning the current [TtyRenderFrame],
 *   evaluated only during the draw phase.
 * @param gridCols Current grid column count for char buffer sizing.
 * @param fontSize Font size in sp for the monospace cell grid.
 * @param onFontSizeChange Invoked with the new font size (sp as raw
 *   [Float]) after a pinch-to-zoom gesture.
 * @param onTap Invoked on a single tap for soft-keyboard focus.
 * @param cursorVisible External blink-phase signal controlling cursor
 *   visibility.
 * @param modifier [Modifier] applied to the drawing surface.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod", "LongParameterList")
@Composable
fun TtyCanvasView(
    frameProvider: () -> TtyRenderFrame?,
    gridCols: Int,
    fontSize: Float,
    onFontSizeChange: (Float) -> Unit,
    onTap: () -> Unit,
    cursorVisible: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val fontState = rememberFontState(fontSize.sp, gridCols)
    var currentFontSize by remember { mutableFloatStateOf(fontSize) }

    val rowCache = remember { mutableListOf<CachedRow>() }

    val cursorPaint = remember { Paint().apply { isAntiAlias = true } }

    androidx.compose.foundation.layout.Box(
        modifier =
            modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { onTap() })
                }.pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ ->
                        val newSize =
                            (currentFontSize * zoom)
                                .coerceIn(TTY_MIN_FONT_SIZE.value, TTY_MAX_FONT_SIZE.value)
                        currentFontSize = newSize
                        onFontSizeChange(newSize)
                    }
                }.drawBehind {
                    drawIntoCanvas { canvas ->
                        val nativeCanvas = canvas.nativeCanvas
                        val frame = frameProvider() ?: return@drawIntoCanvas

                        val defaultBgArgb = frame.defaultBgArgb.toArgbInt()
                        val defaultFgArgb = frame.defaultFgArgb.toArgbInt()

                        val viewWidth = size.width
                        val viewHeight = size.height
                        val (gridColsComputed, gridRows) =
                            calculateGridSize(
                                viewWidth,
                                viewHeight,
                                fontState,
                            )

                        val totalRows = minOf(frame.numRows.toInt(), frame.rows.size)
                        val visibleRows = minOf(totalRows, gridRows)
                        val startRow = totalRows - visibleRows

                        // Ensure row cache has enough slots.
                        while (rowCache.size < visibleRows) {
                            rowCache.add(CachedRow())
                        }

                        // Clear background.
                        nativeCanvas.drawColor(defaultBgArgb)

                        val dirty = frame.dirtyState

                        for (i in 0 until visibleRows) {
                            val row = frame.rows[startRow + i]
                            val cached = rowCache[i]

                            when (dirty) {
                                TtyDirtyState.CLEAN -> {
                                    drawCachedRow(
                                        nativeCanvas,
                                        cached,
                                        i,
                                        gridColsComputed,
                                        fontState,
                                        defaultBgArgb,
                                        defaultFgArgb,
                                    )
                                }
                                TtyDirtyState.FULL -> {
                                    drawFreshRow(
                                        nativeCanvas,
                                        row,
                                        i,
                                        gridColsComputed,
                                        fontState,
                                        defaultBgArgb,
                                        defaultFgArgb,
                                    )
                                    cached.text = row.text
                                    cached.styles = row.styles.toList()
                                }
                                TtyDirtyState.PARTIAL -> {
                                    if (row.dirty) {
                                        drawFreshRow(
                                            nativeCanvas,
                                            row,
                                            i,
                                            gridColsComputed,
                                            fontState,
                                            defaultBgArgb,
                                            defaultFgArgb,
                                        )
                                        cached.text = row.text
                                        cached.styles = row.styles.toList()
                                    } else {
                                        drawCachedRow(
                                            nativeCanvas,
                                            cached,
                                            i,
                                            gridColsComputed,
                                            fontState,
                                            defaultBgArgb,
                                            defaultFgArgb,
                                        )
                                    }
                                }
                            }
                        }

                        // Cursor.
                        val cursor = frame.cursor
                        val cursorScreenRow = cursor.row.toInt()
                        if (cursorVisible &&
                            cursor.visible &&
                            cursorScreenRow in 0 until visibleRows
                        ) {
                            drawCursor(
                                nativeCanvas,
                                cursorPaint,
                                cursor.col.toInt(),
                                cursorScreenRow,
                                cursor.style,
                                fontState,
                                defaultFgArgb,
                            )
                        }
                    }
                },
    ) {
        // No child composables -- everything is drawn on the native canvas.
    }
}

// ── Row drawing (fresh from TtyRenderRow) ──────────────────────────────

/**
 * Draws a row directly from [TtyRenderRow] data, using run batching
 * to group consecutive same-styled cells into single drawTextRun calls.
 */
@Suppress("LongParameterList")
private fun drawFreshRow(
    canvas: Canvas,
    row: TtyRenderRow,
    rowIdx: Int,
    cols: Int,
    fontState: TtyFontState,
    defaultBgArgb: Int,
    defaultFgArgb: Int,
) {
    drawRowImpl(
        canvas,
        row.text,
        row.styles,
        rowIdx,
        cols,
        fontState,
        defaultBgArgb,
        defaultFgArgb,
    )
}

// ── Row drawing (from CachedRow) ───────────────────────────────────────

/**
 * Draws a row from [CachedRow] cache data, used for CLEAN rows and
 * non-dirty rows during PARTIAL updates.
 */
@Suppress("LongParameterList")
private fun drawCachedRow(
    canvas: Canvas,
    cached: CachedRow,
    rowIdx: Int,
    cols: Int,
    fontState: TtyFontState,
    defaultBgArgb: Int,
    defaultFgArgb: Int,
) {
    drawRowImpl(
        canvas,
        cached.text,
        cached.styles,
        rowIdx,
        cols,
        fontState,
        defaultBgArgb,
        defaultFgArgb,
    )
}

// ── Shared row drawing implementation ──────────────────────────────────

/**
 * Core row rendering: iterates columns, compares packed style values,
 * and flushes a run of same-styled cells in a single drawTextRun call.
 */
@Suppress("LongParameterList", "CyclomaticComplexMethod", "LongMethod")
private fun drawRowImpl(
    canvas: Canvas,
    text: String,
    styles: List<Long>,
    rowIdx: Int,
    cols: Int,
    fontState: TtyFontState,
    defaultBgArgb: Int,
    defaultFgArgb: Int,
) {
    val paint = fontState.paint
    val cellW = fontState.cellWidthPx
    val cellH = fontState.cellHeightPx
    val baseline = fontState.baselinePx
    val yTop = rowIdx * cellH
    val yBaseline = yTop + baseline

    val textLen = text.length
    val numStyles = styles.size
    val visCols = minOf(cols, numStyles)

    // Ensure the char buffer is large enough.
    val charBuf =
        if (fontState.charBuffer.size >= textLen) {
            fontState.charBuffer
        } else {
            CharArray(textLen)
        }
    text.toCharArray(charBuf, 0, 0, textLen)

    var runStartCol = 0
    var runStyle = if (visCols > 0) styles[0] else 0L

    for (col in 0..visCols) {
        val currentStyle = if (col < visCols) styles[col] else -1L
        if (col < visCols && currentStyle == runStyle) continue

        // Flush the run from runStartCol..<col
        val runEndCol = col
        val charStart = runStartCol.coerceAtMost(textLen)
        val charEnd = runEndCol.coerceAtMost(textLen)
        val charCount = charEnd - charStart

        flushRun(
            canvas,
            paint,
            charBuf,
            charStart,
            charCount,
            runStartCol,
            runEndCol,
            yTop,
            yBaseline,
            cellW,
            cellH,
            runStyle,
            defaultBgArgb,
            defaultFgArgb,
        )

        if (col < visCols) {
            runStartCol = col
            runStyle = currentStyle
        }
    }
}

// ── Run flushing ───────────────────────────────────────────────────────

/**
 * Draws a single run of same-styled cells: background rect, then text
 * via [Canvas.drawTextRun], then resets paint properties.
 */
@Suppress("LongParameterList", "CyclomaticComplexMethod")
private fun flushRun(
    canvas: Canvas,
    paint: Paint,
    chars: CharArray,
    charStart: Int,
    charCount: Int,
    colStart: Int,
    colEnd: Int,
    yTop: Float,
    yBaseline: Float,
    cellW: Float,
    cellH: Float,
    style: Long,
    defaultBgArgb: Int,
    defaultFgArgb: Int,
) {
    val xStart = colStart * cellW
    val runWidth = (colEnd - colStart) * cellW

    // Background.
    val bgArgb = style.packedBgArgb()
    if (bgArgb != 0 && bgArgb != defaultBgArgb) {
        paint.color = bgArgb
        paint.style = Paint.Style.FILL
        canvas.drawRect(xStart, yTop, xStart + runWidth, yTop + cellH, paint)
    }

    // Skip text drawing if there are no characters in this run.
    if (charCount <= 0) return

    // Foreground color.
    var fgArgb = style.packedFgArgb()
    if (fgArgb == 0) fgArgb = defaultFgArgb

    // Dim: multiply RGB channels by 2/3.
    if (style.packedIsDim()) {
        fgArgb = dimColor(fgArgb)
    }

    paint.color = fgArgb
    paint.style = Paint.Style.FILL

    // Text attributes.
    paint.isFakeBoldText = style.packedIsBold()
    paint.textSkewX = if (style.packedIsItalic()) ITALIC_SKEW else 0f
    paint.isUnderlineText = style.packedIsUnderline()
    paint.isStrikeThruText = style.packedIsStrikethrough()

    // Draw the text run.
    canvas.drawTextRun(
        chars,
        charStart,
        charCount,
        charStart,
        charCount,
        xStart,
        yBaseline,
        false,
        paint,
    )

    // Reset mutable paint properties.
    paint.isFakeBoldText = false
    paint.textSkewX = 0f
    paint.isUnderlineText = false
    paint.isStrikeThruText = false
}

// ── Cursor drawing ─────────────────────────────────────────────────────

/**
 * Draws the cursor at the given cell position.
 */
@Suppress("LongParameterList")
private fun drawCursor(
    canvas: Canvas,
    paint: Paint,
    col: Int,
    row: Int,
    style: TtyCursorStyle,
    fontState: TtyFontState,
    fgArgb: Int,
) {
    val x = col * fontState.cellWidthPx
    val y = row * fontState.cellHeightPx
    val w = fontState.cellWidthPx
    val h = fontState.cellHeightPx

    when (style) {
        TtyCursorStyle.BLOCK -> {
            paint.color = withAlpha(fgArgb, CURSOR_BLOCK_ALPHA)
            paint.style = Paint.Style.FILL
            canvas.drawRect(x, y, x + w, y + h, paint)
        }
        TtyCursorStyle.BAR -> {
            paint.color = fgArgb
            paint.style = Paint.Style.FILL
            canvas.drawRect(x, y, x + CURSOR_BAR_WIDTH, y + h, paint)
        }
        TtyCursorStyle.UNDERLINE -> {
            paint.color = fgArgb
            paint.style = Paint.Style.FILL
            canvas.drawRect(
                x,
                y + h - CURSOR_UNDERLINE_HEIGHT,
                x + w,
                y + h,
                paint,
            )
        }
        TtyCursorStyle.BLOCK_HOLLOW -> {
            paint.color = fgArgb
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = CURSOR_HOLLOW_STROKE
            canvas.drawRect(x, y, x + w, y + h, paint)
            paint.style = Paint.Style.FILL
            paint.strokeWidth = 0f
        }
    }
}

// ── Utility ────────────────────────────────────────────────────────────

/**
 * Applies dim effect by multiplying each RGB channel by 2/3.
 */
@Suppress("MagicNumber")
private fun dimColor(argb: Int): Int {
    val a = argb and ALPHA_OPAQUE
    val r = ((argb shr 16) and 0xFF) * DIM_MULTIPLIER_NUM / DIM_MULTIPLIER_DEN
    val g = ((argb shr 8) and 0xFF) * DIM_MULTIPLIER_NUM / DIM_MULTIPLIER_DEN
    val b = (argb and 0xFF) * DIM_MULTIPLIER_NUM / DIM_MULTIPLIER_DEN
    return a or (r shl 16) or (g shl 8) or b
}

/**
 * Replaces the alpha byte of an ARGB color.
 */
@Suppress("MagicNumber")
private fun withAlpha(
    argb: Int,
    alpha: Int,
): Int = (argb and 0x00FFFFFF) or (alpha shl 24)

/**
 * Converts a [UInt] packed ARGB value to a signed [Int] for
 * [android.graphics.Paint.setColor].
 */
@Suppress("MagicNumber")
private fun UInt.toArgbInt(): Int {
    val raw = this.toInt()
    return if (raw == 0) ALPHA_OPAQUE else raw
}

/**
 * Converts a raw [Float] to a [TextUnit] in sp.
 *
 * Used for font-size interop between the pinch-to-zoom gesture
 * (which operates on raw floats) and Compose text APIs.
 */
private inline val Float.sp: TextUnit
    get() = TextUnit(this, TextUnitType.Sp)
