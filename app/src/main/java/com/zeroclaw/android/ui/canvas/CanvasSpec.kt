/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.canvas

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Enumerates the text styles available for [CanvasElement.Text] elements.
 *
 * Each value maps to a specific [MaterialTheme.typography][androidx.compose.material3.Typography]
 * text style during rendering.
 */
@Serializable
enum class CanvasTextStyle {
    /** Standard body text. */
    @SerialName("body")
    BODY,

    /** Smaller caption text for supplementary information. */
    @SerialName("caption")
    CAPTION,

    /** Compact label text for UI chrome. */
    @SerialName("label")
    LABEL,

    /** Monospace code text rendered in a code block aesthetic. */
    @SerialName("code")
    CODE,
}

/**
 * Enumerates the visual styles available for [CanvasElement.Button] elements.
 *
 * Each value maps to a different Material 3 button composable during rendering.
 */
@Serializable
enum class CanvasButtonStyle {
    /** Filled button with prominent background colour. */
    @SerialName("filled")
    FILLED,

    /** Outlined button with a border and no fill. */
    @SerialName("outlined")
    OUTLINED,

    /** Text-only button with no border or fill. */
    @SerialName("text")
    TEXT,
}

/**
 * Motion variants available for [CanvasElement.Mascot].
 *
 * These map to the reusable mini Zero mascot poses used elsewhere in the UI.
 */
@Serializable
enum class CanvasMascotState {
    /** Quiet ambient state for passive presence. */
    @SerialName("idle")
    IDLE,

    /** In-progress state for thinking, searching, or waiting. */
    @SerialName("thinking")
    THINKING,

    /** Active state for typing, tool output, or rapid terminal updates. */
    @SerialName("typing")
    TYPING,

    /** Positive confirmation state for successful outcomes. */
    @SerialName("success")
    SUCCESS,

    /** Recoverable failure state for errors, cancellations, or blocked actions. */
    @SerialName("error")
    ERROR,

    /** Energetic celebratory state for bigger wins and onboarding moments. */
    @SerialName("celebrate")
    CELEBRATE,

    /** Slightly playful state for peeking or compact UI presence. */
    @SerialName("peek")
    PEEK,

    /** Warm, content state with happy eyes for positive interactions. */
    @SerialName("smiling")
    SMILING,

    /** Affectionate state with heart eyes for enthusiastic reactions. */
    @SerialName("love")
    LOVE,

    /** Frustrated state with slanted eyes for displeased reactions. */
    @SerialName("angry")
    ANGRY,

    /** Deep idle state with closed eyes for long-running or dormant tasks. */
    @SerialName("sleeping")
    SLEEPING,
}

/**
 * A single element within an agent-generated canvas UI specification.
 *
 * The AI agent returns a JSON description of UI elements, and the client
 * renders each element as a native Compose composable. Elements can be
 * nested via [Row] and [Column] for layout composition.
 *
 * This sealed hierarchy is designed for exhaustive `when` matching so
 * every element type has a corresponding renderer.
 */
@Serializable
sealed interface CanvasElement {
    /**
     * Plain text paragraph rendered with a configurable typography style.
     *
     * @property content The text content to display.
     * @property style The typography style to apply.
     */
    @Serializable
    @SerialName("text")
    data class Text(
        val content: String,
        val style: CanvasTextStyle = CanvasTextStyle.BODY,
    ) : CanvasElement

    /**
     * Section heading rendered with headline typography.
     *
     * The [level] controls the visual weight: 1 maps to `headlineLarge`,
     * 2 maps to `headlineMedium`, and 3 or higher maps to `headlineSmall`.
     *
     * @property content The heading text.
     * @property level Heading level (1-3), where 1 is the largest.
     */
    @Serializable
    @SerialName("heading")
    data class Heading(
        val content: String,
        val level: Int = 1,
    ) : CanvasElement

    /**
     * Remote image loaded asynchronously via Coil.
     *
     * @property url The image URL to load.
     * @property alt Accessibility description for the image.
     * @property width Optional width constraint in dp. Height scales proportionally.
     */
    @Serializable
    @SerialName("image")
    data class Image(
        val url: String,
        val alt: String,
        val width: Int? = null,
    ) : CanvasElement

    /**
     * Inline mini Zero mascot rendered as native Compose UI.
     *
     * This gives terminal canvas payloads a lightweight mascot surface without requiring remote
     * images. It is intended for friendly status, success, and thinking affordances.
     *
     * @property state Motion variant for the mascot.
     * @property label Optional text shown below the mascot.
     * @property size Optional size in dp. Defaults to 64dp.
     */
    @Serializable
    @SerialName("mascot")
    data class Mascot(
        val state: CanvasMascotState = CanvasMascotState.IDLE,
        val label: String? = null,
        val size: Int = 40,
    ) : CanvasElement

    /**
     * Interactive button that triggers an action callback when pressed.
     *
     * The [action] string is passed to the `onAction` handler, which
     * typically sends it back to the agent as a follow-up message.
     *
     * @property label The button label text.
     * @property action The action identifier dispatched on click.
     * @property style The visual button style variant.
     */
    @Serializable
    @SerialName("button")
    data class Button(
        val label: String,
        val action: String,
        val style: CanvasButtonStyle = CanvasButtonStyle.FILLED,
    ) : CanvasElement

    /**
     * Material 3 elevated card with a title, body content, and optional action buttons.
     *
     * @property title The card header text.
     * @property content The card body text.
     * @property actions Optional list of buttons rendered at the bottom of the card.
     */
    @Serializable
    @SerialName("card")
    data class Card(
        val title: String,
        val content: String,
        val actions: kotlin.collections.List<Button> = emptyList(),
    ) : CanvasElement

    /**
     * Ordered or unordered list of text items.
     *
     * When [ordered] is true, items are prefixed with sequential numbers.
     * Otherwise, items are prefixed with bullet points.
     *
     * @property items The list of text entries.
     * @property ordered Whether to render numbered (true) or bulleted (false) items.
     */
    @Serializable
    @SerialName("list")
    data class ListElement(
        val items: kotlin.collections.List<String>,
        val ordered: Boolean = false,
    ) : CanvasElement

    /**
     * Horizontal divider line separating content sections.
     *
     * @property thickness The divider thickness in dp.
     */
    @Serializable
    @SerialName("divider")
    data class Divider(
        val thickness: Int = 1,
    ) : CanvasElement

    /**
     * Vertical spacing element for layout control.
     *
     * @property height The spacer height in dp. Defaults to 16dp (2x baseline grid).
     */
    @Serializable
    @SerialName("spacer")
    data class Spacer(
        val height: Int = 16,
    ) : CanvasElement

    /**
     * Determinate progress indicator with an optional label.
     *
     * The [value] is clamped to the 0.0-1.0 range during rendering.
     *
     * @property value Progress fraction between 0.0 and 1.0.
     * @property label Optional text label displayed above the progress bar.
     */
    @Serializable
    @SerialName("progress")
    data class Progress(
        val value: Float,
        val label: String? = null,
    ) : CanvasElement

    /**
     * Single selectable chip, typically used inside a [ChipGroup].
     *
     * @property label The chip label text.
     * @property selected Whether the chip appears in the selected visual state.
     * @property action Optional action identifier dispatched when the chip is tapped.
     */
    @Serializable
    @SerialName("chip")
    data class Chip(
        val label: String,
        val selected: Boolean = false,
        val action: String? = null,
    ) : CanvasElement

    /**
     * Horizontal flow layout containing multiple [Chip] elements.
     *
     * Chips wrap to new lines when they exceed the available width.
     *
     * @property chips The list of chips to render in the group.
     */
    @Serializable
    @SerialName("chip_group")
    data class ChipGroup(
        val chips: kotlin.collections.List<Chip>,
    ) : CanvasElement

    /**
     * Horizontal row layout containing nested canvas elements.
     *
     * Child elements are arranged left-to-right with even weight distribution.
     *
     * @property elements The child elements to render in a row.
     */
    @Serializable
    @SerialName("row")
    data class Row(
        val elements: kotlin.collections.List<CanvasElement>,
    ) : CanvasElement

    /**
     * Vertical column layout containing nested canvas elements.
     *
     * Child elements are arranged top-to-bottom in sequence.
     *
     * @property elements The child elements to render in a column.
     */
    @Serializable
    @SerialName("column")
    data class Column(
        val elements: kotlin.collections.List<CanvasElement>,
    ) : CanvasElement
}

/**
 * A complete agent-generated canvas UI frame.
 *
 * Represents the top-level container returned by the AI agent within a
 * `canvas` fenced code block. Contains an ordered list of [CanvasElement]
 * instances that are rendered sequentially in a vertical layout, and an
 * optional title displayed as a header above the elements.
 *
 * @property elements The ordered list of canvas elements to render.
 * @property title Optional header text displayed above the canvas content.
 */
@Serializable
data class CanvasFrame(
    val elements: List<CanvasElement>,
    val title: String? = null,
)
