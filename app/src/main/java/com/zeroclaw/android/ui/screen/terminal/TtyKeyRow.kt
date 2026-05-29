/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Spacing between adjacent key buttons in dp. */
private const val KEY_SPACING_DP = 4

/** Horizontal padding at the edges of the key row in dp. */
private const val ROW_HORIZONTAL_PADDING_DP = 8

/** Minimum height of each key button in dp to meet touch target requirements. */
private const val KEY_MIN_HEIGHT_DP = 44

/** Corner radius of each key button in dp. */
private const val KEY_CORNER_DP = 8

/**
 * Special keys available in the TTY extra key row.
 *
 * Each entry defines a visible [label] rendered on the button face
 * and a human-readable [description] used as the button's
 * `contentDescription` for screen readers.
 *
 * @property label Short text displayed on the key button.
 * @property description Accessible description of the key's function.
 */
enum class TtySpecialKey(
    val label: String,
    val description: String,
) {
    /** Tab key for indentation and auto-completion. */
    TAB("Tab", "Tab key"),

    /** Control modifier key for terminal control sequences. */
    CTRL("Ctrl", "Control key"),

    /** Escape key for mode switching and cancellation. */
    ESC("Esc", "Escape key"),

    /** Alt modifier key for terminal escape sequences. */
    ALT("Alt", "Alt key"),

    /** Up arrow for command history and cursor movement. */
    UP("↑", "Up arrow"),

    /** Down arrow for command history and cursor movement. */
    DOWN("↓", "Down arrow"),

    /** Left arrow for cursor movement. */
    LEFT("←", "Left arrow"),

    /** Right arrow for cursor movement. */
    RIGHT("→", "Right arrow"),

    /** Home key to move the cursor to the beginning of the line. */
    HOME("Home", "Home key"),

    /** End key to move the cursor to the end of the line. */
    END("End", "End key"),

    /** Page up key to scroll the terminal output up one page. */
    PAGE_UP("PgUp", "Page up key"),

    /** Page down key to scroll the terminal output down one page. */
    PAGE_DOWN("PgDn", "Page down key"),

    /** Pipe character for shell command piping. */
    PIPE("|", "Pipe character"),

    /** Forward slash for file paths and search. */
    SLASH("/", "Forward slash"),

    /** Tilde character for home directory references. */
    TILDE("~", "Tilde character"),

    /** Dash character for command flags and options. */
    DASH("-", "Dash character"),

    /** Enter/Return key to execute commands. */
    ENTER("⏎", "Enter key"),
}

/**
 * Keys on the top row. The arrow keys are arranged as a spatial cross with
 * the rest of the keys: [UP] here sits directly above [DOWN] in the bottom
 * row, with [LEFT]/[RIGHT] flanking it, matching common terminal muscle
 * memory. A soft-keyboard toggle is appended after these as the row's last
 * cell (see [TtyKeyRow]).
 */
private val TTY_KEY_ROW_TOP =
    listOf(
        TtySpecialKey.ESC,
        TtySpecialKey.SLASH,
        TtySpecialKey.DASH,
        TtySpecialKey.TILDE,
        TtySpecialKey.HOME,
        TtySpecialKey.UP,
        TtySpecialKey.END,
        TtySpecialKey.PAGE_UP,
    )

/** Keys on the bottom row, aligned so [TtySpecialKey.DOWN] sits under [TtySpecialKey.UP]. */
private val TTY_KEY_ROW_BOTTOM =
    listOf(
        TtySpecialKey.TAB,
        TtySpecialKey.CTRL,
        TtySpecialKey.ALT,
        TtySpecialKey.PIPE,
        TtySpecialKey.LEFT,
        TtySpecialKey.DOWN,
        TtySpecialKey.RIGHT,
        TtySpecialKey.PAGE_DOWN,
        TtySpecialKey.ENTER,
    )

/**
 * Two fixed rows of special key buttons for TTY input.
 *
 * The keys divide the available width evenly across two non-scrolling rows
 * so every key is always visible and tappable (no horizontal sliding). The
 * arrows form a spatial cross (Up over Down, Left/Right flanking Down across
 * the two rows). Modifier keys (Ctrl, Alt) render filled when active and
 * tonal when inactive, giving a clear toggle indicator. The top row ends
 * with a soft-keyboard show/hide toggle. Each button is labelled with an
 * accessible content description and meets the minimum touch target height.
 *
 * @param onKeyPress Callback invoked with the pressed [TtySpecialKey].
 * @param onToggleKeyboard Callback invoked to show/hide the soft keyboard.
 * @param ctrlActive Whether the Ctrl modifier is currently toggled on.
 * @param altActive Whether the Alt modifier is currently toggled on.
 * @param modifier Modifier applied to the outer [Surface].
 */
@Composable
fun TtyKeyRow(
    onKeyPress: (TtySpecialKey) -> Unit,
    onToggleKeyboard: () -> Unit = {},
    ctrlActive: Boolean = false,
    altActive: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(KEY_SPACING_DP.dp),
            modifier =
                Modifier.padding(
                    horizontal = ROW_HORIZONTAL_PADDING_DP.dp,
                    vertical = KEY_SPACING_DP.dp,
                ),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(KEY_SPACING_DP.dp)) {
                TTY_KEY_ROW_TOP.forEach { key ->
                    TtyKeyButton(
                        key = key,
                        isActive = isModifierActive(key, ctrlActive, altActive),
                        onClick = { onKeyPress(key) },
                        modifier = Modifier.weight(1f),
                    )
                }
                TtyKeyboardToggleButton(
                    onClick = onToggleKeyboard,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(KEY_SPACING_DP.dp)) {
                TTY_KEY_ROW_BOTTOM.forEach { key ->
                    TtyKeyButton(
                        key = key,
                        isActive = isModifierActive(key, ctrlActive, altActive),
                        onClick = { onKeyPress(key) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** Whether [key] is the currently-active Ctrl or Alt modifier. */
private fun isModifierActive(
    key: TtySpecialKey,
    ctrlActive: Boolean,
    altActive: Boolean,
): Boolean =
    when (key) {
        TtySpecialKey.CTRL -> ctrlActive
        TtySpecialKey.ALT -> altActive
        else -> false
    }

/**
 * A single compact key button for the TTY key row.
 *
 * Rendered as a rounded filled cell (custom rather than a Material button so
 * it compresses to the evenly-weighted column width). Uses the primary
 * container color when [isActive] (an engaged modifier) and the surface
 * variant otherwise.
 *
 * @param key The special key this button represents.
 * @param isActive Whether this key is an engaged modifier toggle.
 * @param onClick Callback invoked when the button is tapped.
 * @param modifier Modifier applied to the button cell (carries the row weight).
 */
@Composable
private fun TtyKeyButton(
    key: TtySpecialKey,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background =
        if (isActive) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        }
    val foreground =
        if (isActive) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurface
        }
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .defaultMinSize(minHeight = KEY_MIN_HEIGHT_DP.dp)
                .clip(RoundedCornerShape(KEY_CORNER_DP.dp))
                .background(background)
                .clickable(onClick = onClick)
                .semantics {
                    contentDescription =
                        if (isActive) "${key.description}, active" else key.description
                },
    ) {
        Text(
            text = key.label,
            style = MaterialTheme.typography.labelLarge,
            color = foreground,
        )
    }
}

/**
 * The soft-keyboard show/hide toggle cell in the key row.
 *
 * @param onClick Callback invoked to toggle the on-screen keyboard.
 * @param modifier Modifier applied to the button cell (carries the row weight).
 */
@Composable
private fun TtyKeyboardToggleButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .defaultMinSize(minHeight = KEY_MIN_HEIGHT_DP.dp)
                .clip(RoundedCornerShape(KEY_CORNER_DP.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(onClick = onClick)
                .semantics {
                    contentDescription = "Show or hide the soft keyboard"
                },
    ) {
        Icon(
            imageVector = Icons.Outlined.Keyboard,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }
}
