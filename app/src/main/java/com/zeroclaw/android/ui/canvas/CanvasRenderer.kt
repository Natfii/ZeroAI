/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

@file:Suppress("TooManyFunctions")

package com.zeroclaw.android.ui.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.zeroclaw.android.ui.component.MiniZeroMascot
import com.zeroclaw.android.ui.component.MiniZeroMascotState

/** Standard vertical spacing between canvas elements (8dp grid). */
private const val ELEMENT_SPACING_DP = 8

/** Inner padding for card content (16dp = 2x grid). */
private const val CARD_CONTENT_PADDING_DP = 16

/** Corner radius for code block backgrounds. */
private const val CODE_CORNER_DP = 4

/** Padding inside code block backgrounds. */
private const val CODE_PADDING_DP = 8

/** Minimum touch target height for interactive elements. */
private const val MIN_TOUCH_TARGET_DP = 48

/** Spacing between chips in a chip group (8dp grid). */
private const val CHIP_SPACING_DP = 8

/** Maximum nesting depth to prevent stack overflow from recursive layouts. */
private const val MAX_NESTING_DEPTH = 5

/**
 * Renders a complete [CanvasFrame] as native Compose UI.
 *
 * Each [CanvasElement] in the frame is rendered as the corresponding
 * Material 3 composable. An optional title is rendered as a headline
 * above the elements. All interactive elements dispatch their action
 * strings through [onAction], which typically sends the action back
 * to the AI agent as a follow-up message.
 *
 * Layout follows the 8dp baseline grid. All touch targets meet the
 * 48dp minimum. Accessibility content descriptions are provided for
 * every interactive element.
 *
 * @param frame The canvas frame to render.
 * @param onAction Callback invoked with the action string when an
 *   interactive element (button, chip) is activated.
 * @param modifier Modifier applied to the root column.
 */
@Composable
fun CanvasFrame(
    frame: CanvasFrame,
    onAction: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val elementCount by remember(frame) {
        derivedStateOf { frame.elements.size }
    }

    androidx.compose.foundation.layout.Column(
        verticalArrangement = Arrangement.spacedBy(ELEMENT_SPACING_DP.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        if (frame.title != null) {
            Text(
                text = frame.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
        }

        for (i in 0 until elementCount) {
            RenderElement(
                element = frame.elements[i],
                onAction = onAction,
                depth = 0,
            )
        }
    }
}

/**
 * Dispatches rendering for a single [CanvasElement] to the appropriate
 * composable based on its concrete type.
 *
 * Recursion depth is tracked via [depth] and capped at [MAX_NESTING_DEPTH]
 * to prevent stack overflows from deeply nested agent output.
 *
 * @param element The canvas element to render.
 * @param onAction Callback for interactive element activations.
 * @param depth Current nesting depth for recursive layouts.
 */
@Composable
private fun RenderElement(
    element: CanvasElement,
    onAction: (String) -> Unit,
    depth: Int,
) {
    if (depth > MAX_NESTING_DEPTH) return

    when (element) {
        is CanvasElement.Text -> RenderText(element)
        is CanvasElement.Heading -> RenderHeading(element)
        is CanvasElement.Image -> RenderImage(element)
        is CanvasElement.Mascot -> RenderMascot(element)
        is CanvasElement.Button -> RenderButton(element, onAction)
        is CanvasElement.Card -> RenderCard(element, onAction, depth)
        is CanvasElement.ListElement -> RenderList(element)
        is CanvasElement.Divider -> RenderDivider(element)
        is CanvasElement.Spacer -> RenderSpacer(element)
        is CanvasElement.Progress -> RenderProgress(element)
        is CanvasElement.Chip -> RenderChip(element, onAction)
        is CanvasElement.ChipGroup -> RenderChipGroup(element, onAction)
        is CanvasElement.Row -> RenderRow(element, onAction, depth)
        is CanvasElement.Column -> RenderColumn(element, onAction, depth)
    }
}

/**
 * Renders a [CanvasElement.Text] with the appropriate Material 3 typography.
 *
 * Code-style text is rendered with a monospace font inside a tinted
 * background surface for visual distinction.
 *
 * @param element The text element to render.
 */
@Composable
private fun RenderText(element: CanvasElement.Text) {
    when (element.style) {
        CanvasTextStyle.BODY ->
            Text(
                text = element.content,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        CanvasTextStyle.CAPTION ->
            Text(
                text = element.content,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        CanvasTextStyle.LABEL ->
            Text(
                text = element.content,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        CanvasTextStyle.CODE ->
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(CODE_CORNER_DP.dp),
                        ).padding(CODE_PADDING_DP.dp),
            ) {
                Text(
                    text = element.content,
                    style =
                        MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
    }
}

/**
 * Renders a [CanvasElement.Heading] using headline typography scaled by level.
 *
 * Level 1 uses `headlineLarge`, level 2 uses `headlineMedium`, and
 * level 3 or higher uses `headlineSmall`. The heading is marked with
 * the `heading()` semantics role for accessibility.
 *
 * @param element The heading element to render.
 */
@Composable
private fun RenderHeading(element: CanvasElement.Heading) {
    val textStyle =
        when {
            element.level <= 1 -> MaterialTheme.typography.headlineLarge
            element.level == 2 -> MaterialTheme.typography.headlineMedium
            else -> MaterialTheme.typography.headlineSmall
        }

    Text(
        text = element.content,
        style = textStyle,
        color = MaterialTheme.colorScheme.onSurface,
        modifier =
            Modifier.semantics {
                heading()
                contentDescription = "Heading level ${element.level}: ${element.content}"
            },
    )
}

/**
 * Renders a [CanvasElement.Image] using Coil's [AsyncImage] composable.
 *
 * If a [width][CanvasElement.Image.width] is specified, the image is
 * constrained to that width; otherwise it fills the available width.
 * The [alt][CanvasElement.Image.alt] text provides the content description
 * for accessibility.
 *
 * @param element The image element to render.
 */
@Composable
private fun RenderImage(element: CanvasElement.Image) {
    val context = LocalContext.current
    val imageModifier =
        if (element.width != null) {
            Modifier.widthIn(max = element.width.dp)
        } else {
            Modifier.fillMaxWidth()
        }

    AsyncImage(
        model =
            ImageRequest
                .Builder(context)
                .data(element.url)
                .build(),
        contentDescription = element.alt,
        modifier =
            imageModifier
                .semantics {
                    contentDescription = element.alt
                },
    )
}

/**
 * Renders a [CanvasElement.Mascot] using the reusable mini Zero mascot composable.
 *
 * The mascot is centered within the available width and may include a short label beneath it.
 *
 * @param element The mascot element to render.
 */
@Composable
private fun RenderMascot(element: CanvasElement.Mascot) {
    androidx.compose.foundation.layout.Column(
        verticalArrangement = Arrangement.spacedBy(ELEMENT_SPACING_DP.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {
                    contentDescription = element.label ?: "Zero mascot"
                },
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            MiniZeroMascot(
                state = element.state.toMiniZeroState(),
                size = element.size.dp,
                modifier = Modifier.align(androidx.compose.ui.Alignment.Center),
                contentDescription = null,
            )
        }

        if (element.label != null) {
            Text(
                text = element.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Renders a [CanvasElement.Button] as the appropriate Material 3 button variant.
 *
 * The button meets the 48dp minimum touch target height. On click, the
 * [action][CanvasElement.Button.action] string is dispatched through [onAction].
 *
 * @param element The button element to render.
 * @param onAction Callback invoked with the button's action string.
 */
@Composable
private fun RenderButton(
    element: CanvasElement.Button,
    onAction: (String) -> Unit,
) {
    val buttonModifier =
        Modifier
            .heightIn(min = MIN_TOUCH_TARGET_DP.dp)
            .semantics {
                contentDescription = "Button: ${element.label}"
            }

    when (element.style) {
        CanvasButtonStyle.FILLED ->
            Button(
                onClick = { onAction(element.action) },
                modifier = buttonModifier,
            ) {
                Text(text = element.label)
            }
        CanvasButtonStyle.OUTLINED ->
            OutlinedButton(
                onClick = { onAction(element.action) },
                modifier = buttonModifier,
            ) {
                Text(text = element.label)
            }
        CanvasButtonStyle.TEXT ->
            TextButton(
                onClick = { onAction(element.action) },
                modifier = buttonModifier,
            ) {
                Text(text = element.label)
            }
    }
}

/**
 * Renders a [CanvasElement.Card] as a Material 3 [ElevatedCard].
 *
 * The card displays a title, body content, and optional action buttons
 * in a bottom-aligned row. Nested button rendering respects the current
 * [depth] for recursion safety.
 *
 * @param element The card element to render.
 * @param onAction Callback for button action dispatches within the card.
 * @param depth Current nesting depth.
 */
@Composable
private fun RenderCard(
    element: CanvasElement.Card,
    onAction: (String) -> Unit,
    depth: Int,
) {
    ElevatedCard(
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = "Card: ${element.title}"
                },
    ) {
        androidx.compose.foundation.layout.Column(
            verticalArrangement = Arrangement.spacedBy(ELEMENT_SPACING_DP.dp),
            modifier = Modifier.padding(CARD_CONTENT_PADDING_DP.dp),
        ) {
            Text(
                text = element.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = element.content,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (element.actions.isNotEmpty()) {
                androidx.compose.foundation.layout.Row(
                    horizontalArrangement =
                        Arrangement.spacedBy(ELEMENT_SPACING_DP.dp),
                ) {
                    for (action in element.actions) {
                        RenderElement(
                            element = action,
                            onAction = onAction,
                            depth = depth + 1,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Renders a [CanvasElement.ListElement] as a vertical column of items.
 *
 * Ordered lists use sequential numbers as prefixes; unordered lists use
 * bullet characters. Each item maintains the 8dp vertical spacing.
 *
 * @param element The list element to render.
 */
@Composable
private fun RenderList(element: CanvasElement.ListElement) {
    androidx.compose.foundation.layout.Column(
        verticalArrangement = Arrangement.spacedBy(ELEMENT_SPACING_DP.dp),
    ) {
        for ((index, item) in element.items.withIndex()) {
            val prefix =
                if (element.ordered) {
                    "${index + 1}. "
                } else {
                    "\u2022 "
                }
            Text(
                text = "$prefix$item",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * Renders a [CanvasElement.Divider] as a Material 3 [HorizontalDivider].
 *
 * @param element The divider element with thickness configuration.
 */
@Composable
private fun RenderDivider(element: CanvasElement.Divider) {
    HorizontalDivider(
        thickness = element.thickness.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/**
 * Renders a [CanvasElement.Spacer] as vertical whitespace.
 *
 * @param element The spacer element with height configuration.
 */
@Composable
private fun RenderSpacer(element: CanvasElement.Spacer) {
    androidx.compose.foundation.layout.Spacer(
        modifier = Modifier.height(element.height.dp),
    )
}

/**
 * Renders a [CanvasElement.Progress] as a Material 3 [LinearProgressIndicator].
 *
 * The progress value is clamped to the 0.0-1.0 range. An optional label
 * is displayed above the bar with the percentage value.
 *
 * @param element The progress element to render.
 */
@Composable
private fun RenderProgress(element: CanvasElement.Progress) {
    val clampedValue = element.value.coerceIn(0f, 1f)
    val percentText = "${(clampedValue * PERCENT_MULTIPLIER).toInt()}%"
    val description = element.label?.let { "$it: $percentText" } ?: percentText

    androidx.compose.foundation.layout.Column {
        if (element.label != null) {
            Text(
                text = "${element.label} ($percentText)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        LinearProgressIndicator(
            progress = { clampedValue },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = "Progress: $description"
                    },
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

/** Multiplier to convert a 0.0-1.0 fraction to a percentage integer. */
private const val PERCENT_MULTIPLIER = 100

/**
 * Renders a single [CanvasElement.Chip] as a Material 3 [FilterChip].
 *
 * Chips with an [action][CanvasElement.Chip.action] dispatch it through
 * [onAction] when tapped. Chips without an action are rendered as
 * non-interactive labels. All chips meet the 48dp minimum touch target.
 *
 * @param element The chip element to render.
 * @param onAction Callback for chip action dispatches.
 */
@Composable
private fun RenderChip(
    element: CanvasElement.Chip,
    onAction: (String) -> Unit,
) {
    FilterChip(
        selected = element.selected,
        onClick = {
            element.action?.let { onAction(it) }
        },
        label = { Text(text = element.label) },
        modifier =
            Modifier
                .heightIn(min = MIN_TOUCH_TARGET_DP.dp)
                .semantics {
                    contentDescription =
                        if (element.selected) {
                            "Selected: ${element.label}"
                        } else {
                            element.label
                        }
                },
    )
}

/**
 * Renders a [CanvasElement.ChipGroup] as a [FlowRow] of filter chips.
 *
 * Chips wrap to the next line when they exceed the available width.
 * Spacing follows the 8dp baseline grid.
 *
 * @param element The chip group element to render.
 * @param onAction Callback for chip action dispatches.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RenderChipGroup(
    element: CanvasElement.ChipGroup,
    onAction: (String) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(CHIP_SPACING_DP.dp),
        verticalArrangement = Arrangement.spacedBy(CHIP_SPACING_DP.dp),
    ) {
        for (chip in element.chips) {
            RenderChip(chip, onAction)
        }
    }
}

/**
 * Renders a [CanvasElement.Row] as a horizontal layout of nested elements.
 *
 * Child elements are spaced using the 8dp baseline grid. Recursion
 * depth is incremented to prevent stack overflow.
 *
 * @param element The row element to render.
 * @param onAction Callback for interactive element dispatches.
 * @param depth Current nesting depth.
 */
@Composable
private fun RenderRow(
    element: CanvasElement.Row,
    onAction: (String) -> Unit,
    depth: Int,
) {
    androidx.compose.foundation.layout.Row(
        horizontalArrangement = Arrangement.spacedBy(ELEMENT_SPACING_DP.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        for (child in element.elements) {
            RenderElement(
                element = child,
                onAction = onAction,
                depth = depth + 1,
            )
        }
    }
}

/**
 * Renders a [CanvasElement.Column] as a vertical layout of nested elements.
 *
 * Child elements are spaced using the 8dp baseline grid. Recursion
 * depth is incremented to prevent stack overflow.
 *
 * @param element The column element to render.
 * @param onAction Callback for interactive element dispatches.
 * @param depth Current nesting depth.
 */
@Composable
private fun RenderColumn(
    element: CanvasElement.Column,
    onAction: (String) -> Unit,
    depth: Int,
) {
    androidx.compose.foundation.layout.Column(
        verticalArrangement = Arrangement.spacedBy(ELEMENT_SPACING_DP.dp),
    ) {
        for (child in element.elements) {
            RenderElement(
                element = child,
                onAction = onAction,
                depth = depth + 1,
            )
        }
    }
}

/** Maps a canvas mascot state to the shared [MiniZeroMascotState] model. */
private fun CanvasMascotState.toMiniZeroState(): MiniZeroMascotState =
    when (this) {
        CanvasMascotState.IDLE -> MiniZeroMascotState.Idle
        CanvasMascotState.THINKING -> MiniZeroMascotState.Thinking
        CanvasMascotState.TYPING -> MiniZeroMascotState.Typing
        CanvasMascotState.SUCCESS -> MiniZeroMascotState.Success
        CanvasMascotState.ERROR -> MiniZeroMascotState.Error
        CanvasMascotState.CELEBRATE -> MiniZeroMascotState.Celebrate
        CanvasMascotState.PEEK -> MiniZeroMascotState.Peek
        CanvasMascotState.SMILING -> MiniZeroMascotState.Smiling
        CanvasMascotState.LOVE -> MiniZeroMascotState.Love
        CanvasMascotState.ANGRY -> MiniZeroMascotState.Angry
        CanvasMascotState.SLEEPING -> MiniZeroMascotState.Sleeping
    }
