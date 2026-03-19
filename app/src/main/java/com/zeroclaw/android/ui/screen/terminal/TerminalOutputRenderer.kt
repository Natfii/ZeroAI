/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

@file:Suppress("TooManyFunctions")

package com.zeroclaw.android.ui.screen.terminal

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.zeroclaw.android.ui.canvas.CanvasFrame
import com.zeroclaw.android.ui.canvas.detectCanvasBlock
import com.zeroclaw.android.ui.canvas.parseCanvasJson
import com.zeroclaw.android.ui.theme.TerminalTypography
import org.json.JSONArray
import org.json.JSONObject

/** Horizontal padding inside each terminal block. */
private const val BLOCK_HORIZONTAL_PADDING_DP = 12

/** Vertical padding inside each terminal block. */
private const val BLOCK_VERTICAL_PADDING_DP = 8

/** Corner radius for structured output containers. */
private const val STRUCTURED_CORNER_DP = 8

/** Border width for structured output containers. */
private const val STRUCTURED_BORDER_DP = 1

/** Indentation spaces for pretty-printed JSON. */
private const val JSON_INDENT_SPACES = 2

/** Corner radius for canvas fallback code blocks. */
private const val CANVAS_FALLBACK_CORNER_DP = 4

/** Padding inside canvas fallback code blocks. */
private const val CANVAS_FALLBACK_PADDING_DP = 8

/** Vertical spacing between canvas and surrounding text. */
private const val CANVAS_SPACING_DP = 8

/** Size of provider attribution icons. */
private const val PROVIDER_ICON_SIZE_DP = 16

/** Horizontal gap between provider icon and label. */
private const val PROVIDER_ICON_GAP_DP = 4

/** Icon size for the terminal copy affordance. */
private const val COPY_ICON_SIZE_DP = 18

/** JSON key for daemon running status detection. */
private const val KEY_DAEMON_RUNNING = "daemon_running"

/** JSON key for session cost detection. */
private const val KEY_SESSION_COST = "session_cost_usd"

/**
 * Renders a single [TerminalBlock] in the terminal scrollback.
 *
 * Each block variant has its own visual style: input lines show a
 * prompt prefix, responses use plain monospace text, structured output
 * renders formatted JSON, errors are highlighted in red, and system
 * messages appear dimmed. Each block exposes a visible copy affordance
 * plus an accessibility custom action.
 *
 * @param block The terminal block to render.
 * @param onCopy Callback invoked with the copyable text.
 * @param onCanvasAction Callback invoked when a canvas interactive element
 *   is activated. The action string is sent back to the agent.
 * @param modifier Modifier applied to the block container.
 */
@Composable
fun TerminalBlockItem(
    block: TerminalBlock,
    onCopy: (String) -> Unit,
    onCanvasAction: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    when (block) {
        is TerminalBlock.Input -> InputBlock(block, onCopy, modifier)
        is TerminalBlock.Response -> ResponseBlock(block, onCopy, onCanvasAction, modifier)
        is TerminalBlock.Structured -> StructuredBlock(block, onCopy, modifier)
        is TerminalBlock.Error -> ErrorBlock(block, onCopy, modifier)
        is TerminalBlock.System -> SystemBlock(block, onCopy, modifier)
    }
}

/**
 * Renders a user input block with a `> ` prompt prefix.
 *
 * @param block The input block to render.
 * @param onCopy Callback invoked with the input text on long-press.
 * @param modifier Modifier applied to the block container.
 */
@Composable
private fun InputBlock(
    block: TerminalBlock.Input,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isCommand = block.text.startsWith("/")
    val description = if (isCommand) "Command: ${block.text}" else "Message: ${block.text}"

    CopyableBlockLayout(
        onCopy = { onCopy(block.text) },
        copyLabel = "Copy message",
        modifier = modifier,
    ) {
        Column(
            modifier =
                Modifier.semantics {
                    contentDescription = description
                    customActions =
                        listOf(
                            CustomAccessibilityAction(label = "Copy message") {
                                onCopy(block.text)
                                true
                            },
                        )
                },
        ) {
            val annotatedPrompt =
                buildAnnotatedString {
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) {
                        append("> ")
                    }
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurface)) {
                        append(block.text)
                    }
                }
            Text(
                text = annotatedPrompt,
                style = TerminalTypography.bodyMedium,
            )
            for (imageName in block.imageNames) {
                Text(
                    text = "  [image: $imageName]",
                    style = TerminalTypography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Renders an agent response block as plain monospace text.
 *
 * If the response contains a fenced ` ```canvas ` code block, the text
 * before the fence is rendered normally, and the canvas JSON is parsed
 * into a [CanvasFrame] and rendered as native Compose UI via
 * [com.zeroclaw.android.ui.canvas.CanvasFrame]. If canvas parsing fails,
 * the raw JSON is shown as a monospace code block for graceful degradation.
 *
 * Canvas button and chip actions are dispatched through [onCanvasAction],
 * which sends the action string back to the agent as a follow-up message.
 *
 * @param block The response block to render.
 * @param onCopy Callback invoked with the response content.
 * @param onCanvasAction Callback invoked when a canvas interactive element
 *   is activated. Defaults to no-op for backward compatibility.
 * @param modifier Modifier applied to the block container.
 */
@Composable
private fun ResponseBlock(
    block: TerminalBlock.Response,
    onCopy: (String) -> Unit,
    onCanvasAction: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val parsed = remember(block.content) { detectCanvasBlock(block.content) }
    val plainText = parsed.first
    val canvasJson = parsed.second
    val attribution =
        remember(block.providerId) {
            ProviderIconRegistry.forProvider(block.providerId)
        }

    CopyableBlockLayout(
        onCopy = { onCopy(block.content) },
        copyLabel = "Copy response",
        modifier = modifier,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(CANVAS_SPACING_DP.dp),
            modifier =
                Modifier.semantics {
                    contentDescription = plainText.ifEmpty { "Canvas response" }
                    customActions =
                        listOf(
                            CustomAccessibilityAction(label = "Copy response") {
                                onCopy(block.content)
                                true
                            },
                        )
                },
        ) {
            if (block.providerId != null) {
                Image(
                    painter = painterResource(id = attribution.icon),
                    contentDescription = "${attribution.name} provider",
                    modifier =
                        Modifier
                            .size(PROVIDER_ICON_SIZE_DP.dp)
                            .padding(bottom = PROVIDER_ICON_GAP_DP.dp),
                )
            }

            if (plainText.isNotEmpty()) {
                Text(
                    text = plainText,
                    style = TerminalTypography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            if (canvasJson != null) {
                val canvasFrame = remember(canvasJson) { parseCanvasJson(canvasJson) }
                if (canvasFrame != null) {
                    CanvasFrame(
                        frame = canvasFrame,
                        onAction = onCanvasAction,
                    )
                } else {
                    CanvasFallbackCodeBlock(json = canvasJson)
                }
            }
        }
    }
}

/**
 * Renders raw canvas JSON as a monospace code block when parsing fails.
 *
 * Provides graceful degradation so the user can still see the agent's
 * structured output even if the canvas schema is unrecognised.
 *
 * @param json The raw JSON string that failed to parse.
 */
@Composable
private fun CanvasFallbackCodeBlock(json: String) {
    Text(
        text = json,
        style =
            TerminalTypography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
            ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(CANVAS_FALLBACK_CORNER_DP.dp),
                ).padding(CANVAS_FALLBACK_PADDING_DP.dp)
                .semantics {
                    contentDescription = "Canvas data (could not parse)"
                },
    )
}

/**
 * Renders a structured JSON output block with pattern-detected formatting.
 *
 * Detects common response patterns (status, cost summary, arrays) and
 * renders them in a human-readable format. Falls back to pretty-printed
 * JSON for unrecognised structures.
 *
 * @param block The structured block to render.
 * @param onCopy Callback invoked with the raw JSON.
 * @param modifier Modifier applied to the block container.
 */
@Composable
private fun StructuredBlock(
    block: TerminalBlock.Structured,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    val formattedContent = remember(block.json) { formatStructuredJson(block.json) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(STRUCTURED_CORNER_DP.dp),
        modifier =
            modifier
                .fillMaxWidth()
                .border(
                    width = STRUCTURED_BORDER_DP.dp,
                    color = borderColor,
                    shape = RoundedCornerShape(STRUCTURED_CORNER_DP.dp),
                ),
    ) {
        CopyableBlockLayout(
            onCopy = { onCopy(block.json) },
            copyLabel = "Copy structured output",
            modifier =
                Modifier.semantics {
                    contentDescription = formattedContent
                    customActions =
                        listOf(
                            CustomAccessibilityAction(label = "Copy structured output") {
                                onCopy(block.json)
                                true
                            },
                        )
                },
        ) {
            Text(
                text = formattedContent,
                style = TerminalTypography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Renders an error block with red text and an "Error: " prefix.
 *
 * @param block The error block to render.
 * @param onCopy Callback invoked with the error message.
 * @param modifier Modifier applied to the block container.
 */
@Composable
private fun ErrorBlock(
    block: TerminalBlock.Error,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    CopyableBlockLayout(
        onCopy = { onCopy(block.message) },
        copyLabel = "Copy error",
        modifier =
            modifier.semantics {
                contentDescription = "Error: ${block.message}"
                customActions =
                    listOf(
                        CustomAccessibilityAction(label = "Copy error") {
                            onCopy(block.message)
                            true
                        },
                    )
            },
    ) {
        Text(
            text = "Error: ${block.message}",
            style = TerminalTypography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/**
 * Renders a system message block in dimmed outline colour.
 *
 * @param block The system block to render.
 * @param onCopy Callback invoked with the system message text.
 * @param modifier Modifier applied to the block container.
 */
@Composable
private fun SystemBlock(
    block: TerminalBlock.System,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    CopyableBlockLayout(
        onCopy = { onCopy(block.text) },
        copyLabel = "Copy system message",
        modifier =
            modifier.semantics {
                contentDescription = block.text
                customActions =
                    listOf(
                        CustomAccessibilityAction(label = "Copy system message") {
                            onCopy(block.text)
                            true
                        },
                    )
            },
    ) {
        Text(
            text = block.text,
            style = TerminalTypography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

/**
 * Shared terminal block layout with a visible copy affordance.
 *
 * @param onCopy Callback when the copy action is activated.
 * @param copyLabel Accessibility label for the copy action.
 * @param modifier Modifier applied to the root row.
 * @param content Content for the block body.
 */
@Composable
private fun CopyableBlockLayout(
    onCopy: () -> Unit,
    copyLabel: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(
                    horizontal = BLOCK_HORIZONTAL_PADDING_DP.dp,
                    vertical = BLOCK_VERTICAL_PADDING_DP.dp,
                ),
        horizontalArrangement = Arrangement.spacedBy(BLOCK_HORIZONTAL_PADDING_DP.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(CANVAS_SPACING_DP.dp),
        ) {
            content()
        }
        IconButton(
            onClick = onCopy,
            modifier =
                Modifier.semantics {
                    contentDescription = copyLabel
                },
        ) {
            Icon(
                imageVector = Icons.Outlined.ContentCopy,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(COPY_ICON_SIZE_DP.dp),
            )
        }
    }
}

/**
 * Formats a JSON string into a human-readable representation.
 *
 * Detects common response patterns:
 * - Objects with `daemon_running` field: status table with indicators.
 * - Objects with `session_cost_usd` field: cost summary.
 * - JSON arrays of objects: numbered list of entries.
 * - Fallback: pretty-printed JSON.
 *
 * @param json The raw JSON string to format.
 * @return A human-readable text representation.
 */
private fun formatStructuredJson(json: String): String {
    val trimmed = json.trim()
    if (trimmed.startsWith("{")) {
        return formatJsonObject(trimmed)
    }
    if (trimmed.startsWith("[")) {
        return formatJsonArray(trimmed)
    }
    return trimmed
}

/**
 * Formats a JSON object string based on detected field patterns.
 *
 * @param json A JSON object string.
 * @return Formatted text representation.
 */
private fun formatJsonObject(json: String): String {
    val obj =
        runCatching { JSONObject(json) }.getOrNull()
            ?: return json

    if (obj.has(KEY_DAEMON_RUNNING)) {
        return formatStatusObject(obj)
    }
    if (obj.has(KEY_SESSION_COST)) {
        return formatCostObject(obj)
    }
    return formatGenericObject(obj)
}

/**
 * Formats a daemon status JSON object with running indicators.
 *
 * @param obj The parsed JSON object containing status fields.
 * @return A multi-line status summary.
 */
private fun formatStatusObject(obj: JSONObject): String =
    buildString {
        val keys = obj.keys().asSequence().toList()
        for (key in keys) {
            val value = obj.get(key)
            val label = key.replace("_", " ")
            val indicator = if (value == true) "\u25CF" else "\u25CB"
            if (value is Boolean) {
                appendLine("$indicator $label")
            } else {
                appendLine("  $label: $value")
            }
        }
    }.trimEnd()

/**
 * Formats a cost summary JSON object.
 *
 * @param obj The parsed JSON object containing cost fields.
 * @return A multi-line cost summary.
 */
private fun formatCostObject(obj: JSONObject): String =
    buildString {
        val keys = obj.keys().asSequence().toList()
        for (key in keys) {
            val value = obj.get(key)
            val label = key.replace("_", " ")
            appendLine("$label: $value")
        }
    }.trimEnd()

/**
 * Formats a JSON array, rendering each element as a numbered entry.
 *
 * @param json A JSON array string.
 * @return A numbered list of entries, or pretty-printed JSON on parse failure.
 */
private fun formatJsonArray(json: String): String {
    val arr =
        runCatching { JSONArray(json) }.getOrNull()
            ?: return json

    if (arr.length() == 0) {
        return "(empty)"
    }

    return buildString {
        for (i in 0 until arr.length()) {
            val element = arr.get(i)
            if (element is JSONObject) {
                appendLine("${i + 1}. ${summarizeObject(element)}")
            } else {
                appendLine("${i + 1}. $element")
            }
        }
    }.trimEnd()
}

/**
 * Summarises a JSON object as a single line of key-value pairs.
 *
 * @param obj The JSON object to summarise.
 * @return A compact "key=value, key=value" representation.
 */
private fun summarizeObject(obj: JSONObject): String {
    val keys = obj.keys().asSequence().toList()
    return keys.joinToString(", ") { key -> "$key=${obj.get(key)}" }
}

/**
 * Formats a generic JSON object as pretty-printed key-value lines.
 *
 * @param obj The parsed JSON object.
 * @return Multi-line "key: value" text.
 */
private fun formatGenericObject(obj: JSONObject): String = runCatching { obj.toString(JSON_INDENT_SPACES) }.getOrDefault(obj.toString())
