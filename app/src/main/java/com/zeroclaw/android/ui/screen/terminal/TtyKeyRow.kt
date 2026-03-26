/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.terminal

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Spacing between adjacent key buttons in dp. */
private const val KEY_SPACING_DP = 4

/** Horizontal padding at the edges of the key row in dp. */
private const val ROW_HORIZONTAL_PADDING_DP = 8

/** Minimum height of each key button in dp to meet touch target requirements. */
private const val KEY_MIN_HEIGHT_DP = 48

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
    UP("\u2191", "Up arrow"),

    /** Down arrow for command history and cursor movement. */
    DOWN("\u2193", "Down arrow"),

    /** Left arrow for cursor movement. */
    LEFT("\u2190", "Left arrow"),

    /** Right arrow for cursor movement. */
    RIGHT("\u2192", "Right arrow"),

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
    ENTER("\u23CE", "Enter key"),
}

/**
 * Horizontally scrollable row of special key buttons for TTY input.
 *
 * Renders one button per [TtySpecialKey] entry in a single scrollable
 * [Row]. Modifier keys (Ctrl, Alt) use a filled [Button] when active
 * and a [FilledTonalButton] when inactive, providing a clear visual
 * toggle indicator. Each button meets the 48dp minimum touch target
 * height and is labelled with an accessible content description.
 *
 * @param onKeyPress Callback invoked with the pressed [TtySpecialKey].
 * @param ctrlActive Whether the Ctrl modifier is currently toggled on.
 * @param altActive Whether the Alt modifier is currently toggled on.
 * @param modifier Modifier applied to the outer [Surface].
 */
@Composable
fun TtyKeyRow(
    onKeyPress: (TtySpecialKey) -> Unit,
    ctrlActive: Boolean = false,
    altActive: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(KEY_SPACING_DP.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = ROW_HORIZONTAL_PADDING_DP.dp),
        ) {
            TtySpecialKey.entries.forEach { key ->
                val isActiveModifier =
                    when (key) {
                        TtySpecialKey.CTRL -> ctrlActive
                        TtySpecialKey.ALT -> altActive
                        else -> false
                    }
                val keyModifier =
                    Modifier
                        .defaultMinSize(minHeight = KEY_MIN_HEIGHT_DP.dp)
                        .semantics {
                            contentDescription =
                                if (isActiveModifier) {
                                    "${key.description}, active"
                                } else {
                                    key.description
                                }
                        }

                if (isActiveModifier) {
                    Button(
                        onClick = { onKeyPress(key) },
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        modifier = keyModifier,
                    ) {
                        Text(
                            text = key.label,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                } else {
                    FilledTonalButton(
                        onClick = { onKeyPress(key) },
                        modifier = keyModifier,
                    ) {
                        Text(
                            text = key.label,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
    }
}
