/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.canvas

import android.util.Log
import kotlinx.serialization.json.Json

/**
 * Regex matching a fenced canvas code block in an agent response.
 *
 * Captures the JSON content between opening ` ```canvas ` and closing
 * ` ``` ` markers. The pattern uses `DOTALL` mode so the content can
 * span multiple lines.
 */
private val CANVAS_FENCE_REGEX =
    Regex(
        """```canvas\s*\n([\s\S]*?)\n\s*```""",
    )

/** Tag used for log messages from the canvas parser. */
private const val TAG = "CanvasParser"

/**
 * Lenient JSON parser configured for agent-generated canvas payloads.
 *
 * Ignores unknown keys so that forward-compatible fields added by newer
 * agent versions do not break parsing. Coerces nulls to defaults and
 * allows trailing commas for robustness against imperfect model output.
 */
private val canvasJson =
    Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

/**
 * Parses a JSON string into a [CanvasFrame].
 *
 * Accepts either a bare [CanvasFrame] JSON object or a wrapper object
 * with a `"canvas"` key containing the frame. Returns `null` if the
 * JSON is malformed or does not match the expected structure, ensuring
 * the caller can fall back to plain text rendering.
 *
 * @param json The raw JSON string to parse.
 * @return The parsed [CanvasFrame], or `null` on failure.
 */
fun parseCanvasJson(json: String): CanvasFrame? {
    val trimmed = json.trim()
    if (trimmed.isEmpty()) return null

    return tryParseWrapped(trimmed) ?: tryParseBare(trimmed)
}

/**
 * Attempts to parse the JSON as a wrapper object with a `"canvas"` key.
 *
 * Expected format:
 * ```json
 * { "canvas": { "title": "...", "elements": [...] } }
 * ```
 *
 * @param json The trimmed JSON string.
 * @return The parsed [CanvasFrame], or `null` if parsing fails.
 */
private fun tryParseWrapped(json: String): CanvasFrame? =
    runCatching {
        val wrapper = canvasJson.decodeFromString<CanvasWrapper>(json)
        wrapper.canvas
    }.onFailure { e ->
        Log.d(TAG, "Wrapped canvas parse failed: ${e.message}")
    }.getOrNull()

/**
 * Attempts to parse the JSON directly as a [CanvasFrame].
 *
 * Expected format:
 * ```json
 * { "title": "...", "elements": [...] }
 * ```
 *
 * @param json The trimmed JSON string.
 * @return The parsed [CanvasFrame], or `null` if parsing fails.
 */
private fun tryParseBare(json: String): CanvasFrame? =
    runCatching {
        canvasJson.decodeFromString<CanvasFrame>(json)
    }.onFailure { e ->
        Log.d(TAG, "Bare canvas parse failed: ${e.message}")
    }.getOrNull()

/**
 * Splits an agent response into plain text and an optional canvas JSON block.
 *
 * Scans the response for a fenced ` ```canvas ` code block. If found,
 * the text before the fence is returned as the first element, and the
 * JSON content of the fence is returned as the second. If no canvas
 * fence is found, the entire response is returned as plain text with
 * a `null` second element.
 *
 * @param response The full agent response string.
 * @return A pair of (plain text before canvas, canvas JSON or null).
 */
fun detectCanvasBlock(response: String): Pair<String, String?> {
    val match = CANVAS_FENCE_REGEX.find(response) ?: return Pair(response, null)

    val beforeCanvas = response.substring(0, match.range.first).trimEnd()
    val canvasJson = match.groupValues[1].trim()

    return Pair(beforeCanvas, canvasJson)
}

/**
 * Wrapper object for the `{ "canvas": { ... } }` JSON envelope format.
 *
 * @property canvas The inner canvas frame payload.
 */
@kotlinx.serialization.Serializable
private data class CanvasWrapper(
    val canvas: CanvasFrame,
)
