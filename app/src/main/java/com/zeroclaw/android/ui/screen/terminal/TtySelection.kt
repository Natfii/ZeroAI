/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

@file:Suppress("MatchingDeclarationName")

package com.zeroclaw.android.ui.screen.terminal

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.runtime.Stable
import com.zeroclaw.ffi.TtyRenderRow

/**
 * Represents a rectangular text selection anchored to terminal grid coordinates.
 *
 * Coordinates are expressed as zero-based column and row indices into the
 * current [TtyRenderFrame] grid. The selection is inclusive on both ends:
 * [startCol] and [endCol] refer to character positions within a row's text,
 * while [startRow] and [endRow] identify which rows in the frame are covered.
 *
 * The anchor point (where the gesture started) may be at either end —
 * [normalised] always returns a copy where the start precedes or equals the end.
 *
 * @property startCol Column index of the selection anchor.
 * @property startRow Row index of the selection anchor.
 * @property endCol Column index of the selection focus (drag end).
 * @property endRow Row index of the selection focus (drag end).
 */
@Stable
data class TtySelectionState(
    val startCol: Int,
    val startRow: Int,
    val endCol: Int,
    val endRow: Int,
) {
    /**
     * Returns a copy of this selection where the start always precedes or equals the end.
     *
     * Rows are compared first; if [startRow] is greater than [endRow] the endpoints are
     * swapped entirely. When both rows are equal and [startCol] exceeds [endCol] the
     * columns are swapped while rows remain unchanged.
     *
     * @return A [TtySelectionState] guaranteed to satisfy `startRow <= endRow` and,
     *   when `startRow == endRow`, `startCol <= endCol`.
     */
    fun normalised(): TtySelectionState =
        when {
            startRow > endRow ->
                TtySelectionState(
                    startCol = endCol,
                    startRow = endRow,
                    endCol = startCol,
                    endRow = startRow,
                )
            startRow == endRow && startCol > endCol ->
                copy(
                    startCol = endCol,
                    endCol = startCol,
                )
            else -> this
        }
}

/**
 * Extracts the plain text covered by [selection] from a list of rendered terminal rows.
 *
 * The selection is normalised before extraction so callers do not need to sort endpoints
 * beforehand. For each row in the covered range the function slices the row's text field:
 *
 * - First row: from [TtySelectionState.startCol] to the row end (or [TtySelectionState.endCol]
 *   when the selection spans a single row).
 * - Middle rows: the entire text of the row.
 * - Last row: from column 0 to [TtySelectionState.endCol].
 *
 * Column values are clamped with [coerceIn] so out-of-bounds coordinates are silently
 * saturated rather than throwing an exception.
 *
 * @param selection The selection range in grid coordinates.
 * @param rows Ordered list of [TtyRenderRow] values matching the current frame.
 * @return The selected text with rows joined by newline characters, or an empty string
 *   when the selection range contains no valid rows.
 */
fun extractSelectedText(
    selection: TtySelectionState,
    rows: List<TtyRenderRow>,
): String {
    val norm = selection.normalised()
    val lines = mutableListOf<String>()

    for (rowIndex in norm.startRow..norm.endRow) {
        if (rowIndex < 0 || rowIndex >= rows.size) continue
        val text = rows[rowIndex].text
        val colFrom: Int
        val colTo: Int
        when (rowIndex) {
            norm.startRow -> {
                colFrom = norm.startCol.coerceIn(0, text.length)
                colTo =
                    if (norm.startRow == norm.endRow) {
                        norm.endCol.coerceIn(colFrom, text.length)
                    } else {
                        text.length
                    }
            }
            norm.endRow -> {
                colFrom = 0
                colTo = norm.endCol.coerceIn(0, text.length)
            }
            else -> {
                colFrom = 0
                colTo = text.length
            }
        }
        lines.add(text.substring(colFrom, colTo))
    }

    return lines.joinToString("\n")
}

/**
 * Copies [text] to the system clipboard, marking it as sensitive so the OS suppresses
 * clipboard notifications and autofill suggestions.
 *
 * The clip is created with [ClipData.newPlainText] and tagged with
 * [ClipDescription.EXTRA_IS_SENSITIVE] via a [PersistableBundle] so that Android 12+
 * clipboard toast notifications are suppressed for terminal output that may contain
 * credentials or private data.
 *
 * @param context Android [Context] used to resolve the [ClipboardManager] system service.
 * @param text The plain-text content to place on the clipboard.
 * @param label Human-readable label describing the clip source, shown in clipboard UIs.
 */
fun copyToClipboard(
    context: Context,
    text: String,
    label: String = "Terminal",
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(label, text)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val extras = PersistableBundle()
        extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        clip.description.extras = extras
    }
    clipboard.setPrimaryClip(clip)
}

/** Patterns that match common secrets in terminal output. */
private val SECRET_PATTERNS: List<Regex> =
    listOf(
        Regex("""(?i)(authorization:\s*bearer\s+)([A-Za-z0-9._\-+/=]{12,})"""),
        Regex("""(?i)(refresh[_ -]?token["'=:\s]+)([A-Za-z0-9._\-+/=]{12,})"""),
        Regex("""(?i)(access[_ -]?token["'=:\s]+)([A-Za-z0-9._\-+/=]{12,})"""),
        Regex("""(?i)(api[_ -]?key["'=:\s]+)([A-Za-z0-9._\-+/=]{12,})"""),
        Regex("""\bsk-[A-Za-z0-9_-]{12,}\b"""),
        Regex("""\bAIza[0-9A-Za-z\-_]{20,}\b"""),
        Regex("""\bya29\.[0-9A-Za-z._\-]+\b"""),
        Regex("""(?i)\b(cookie|set-cookie):\s*([^\r\n]+)"""),
    )

private const val REDACTION_VISIBLE_CHARS = 4

/**
 * Minimum [MatchResult.groupValues] size to confirm that at least two capture groups
 * matched (index 0 = full match, 1 = group 1, 2 = group 2).
 */
private const val REDACT_MIN_GROUP_VALUES = 3

/**
 * Redacts recognized secret patterns from terminal text before placing it on the clipboard.
 *
 * For each matched secret value, preserves up to [REDACTION_VISIBLE_CHARS] characters at
 * the start and end of the token with a `[REDACTED]` placeholder in between, keeping enough
 * context to identify the key type without leaking the full secret.
 *
 * @param text Raw terminal text that may contain credentials or tokens.
 * @return A copy of [text] with secret values replaced by redacted placeholders.
 */
internal fun redactClipboardSecrets(text: String): String {
    var redacted = text
    SECRET_PATTERNS.forEach { pattern ->
        redacted =
            pattern.replace(redacted) { match ->
                if (match.groupValues.size >= REDACT_MIN_GROUP_VALUES) {
                    match.groupValues[1] + preserveTokenShape(match.groupValues[2])
                } else {
                    preserveTokenShape(match.value)
                }
            }
    }
    return redacted
}

private fun preserveTokenShape(token: String): String {
    val trimmed = token.trim()
    if (trimmed.length <= REDACTION_VISIBLE_CHARS * 2) {
        return "[REDACTED]"
    }
    return buildString {
        append(trimmed.take(REDACTION_VISIBLE_CHARS))
        append("…")
        append(trimmed.takeLast(REDACTION_VISIBLE_CHARS))
        append(" [REDACTED]")
    }
}

/**
 * Finds the word boundaries around [col] in a terminal row.
 *
 * Scans left and right from the character at [col] until a word
 * delimiter (whitespace or punctuation) is reached. Returns the
 * inclusive column range `(startCol, endCol)` of the word, or
 * `null` if [col] is on a delimiter, whitespace, or out of bounds.
 *
 * @param text The row's concatenated UTF-8 text.
 * @param charOffsets UTF-16 code-unit offset per column (same length as styles).
 * @param col The column index to test.
 * @return Inclusive `(startCol, endCol)` pair, or `null` if no word at [col].
 */
fun findWordBoundaries(
    text: String,
    charOffsets: List<UInt>,
    col: Int,
): Pair<Int, Int>? {
    if (col < 0 || col >= charOffsets.size) return null
    val totalCols = charOffsets.size

    val charIdx = charOffsets[col].toInt()
    if (charIdx >= text.length) return null
    if (text[charIdx].isWordDelimiter()) return null

    var startCol = col
    while (startCol > 0) {
        val prevCharIdx = charOffsets[startCol - 1].toInt()
        if (prevCharIdx >= text.length || text[prevCharIdx].isWordDelimiter()) break
        startCol--
    }

    var endCol = col
    while (endCol < totalCols - 1) {
        val nextCharIdx = charOffsets[endCol + 1].toInt()
        if (nextCharIdx >= text.length || text[nextCharIdx].isWordDelimiter()) break
        endCol++
    }

    return startCol to endCol
}

private val WORD_DELIMITERS: Set<Char> =
    setOf(
        '(',
        ')',
        '[',
        ']',
        '{',
        '}',
        '<',
        '>',
        '\'',
        '"',
        ';',
        ':',
        ',',
        '.',
        '|',
        '&',
        '!',
        '@',
        '#',
        '$',
        '%',
        '^',
        '*',
        '=',
        '+',
        '~',
        '`',
        '\\',
    )

private fun Char.isWordDelimiter(): Boolean = isWhitespace() || this in WORD_DELIMITERS
