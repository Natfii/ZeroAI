/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Vertical padding around the terminal mode toggle bar. */
private const val TOGGLE_BAR_VERTICAL_PAD_DP = 4

/** Number of positions in the REPL/Shell mode segmented control. */
private const val MODE_SEGMENT_COUNT = 2

/**
 * Persistent top bar that switches the terminal between the interactive
 * REPL and the raw Shell (local PTY / SSH) surface, with a shortcut to
 * the terminal theme picker.
 *
 * The segmented control reuses the view model's `switchToRepl`/`switchToTty`
 * transitions; selecting the already-active mode is a no-op so repeated taps
 * never recreate a session. The label text plus the segmented selected state
 * announce both the mode name and whether it is active, satisfying the toggle
 * accessibility requirement. An opaque surface background keeps the bar
 * visible above either themed surface.
 *
 * @param isShellMode Whether the Shell (TTY) surface is currently active.
 * @param onSelectRepl Switches to the REPL surface.
 * @param onSelectShell Switches to the Shell surface.
 * @param onOpenTheme Opens the terminal theme picker.
 * @param edgeMargin Horizontal inset matching the window width size class.
 * @param modifier Modifier applied to the bar row.
 */
@Composable
internal fun TerminalModeToggleBar(
    isShellMode: Boolean,
    onSelectRepl: () -> Unit,
    onSelectShell: () -> Unit,
    onOpenTheme: () -> Unit,
    edgeMargin: Dp,
    modifier: Modifier = Modifier,
) {
    val accent = themedRoleColor(BlockRole.INPUT_PROMPT)

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = edgeMargin, vertical = TOGGLE_BAR_VERTICAL_PAD_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TOGGLE_BAR_VERTICAL_PAD_DP.dp),
    ) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.weight(1f)) {
            SegmentedButton(
                selected = !isShellMode,
                onClick = { if (isShellMode) onSelectRepl() },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = MODE_SEGMENT_COUNT),
                label = { Text("REPL") },
            )
            SegmentedButton(
                selected = isShellMode,
                onClick = { if (!isShellMode) onSelectShell() },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = MODE_SEGMENT_COUNT),
                label = { Text("Shell") },
            )
        }
        IconButton(
            onClick = onOpenTheme,
            modifier =
                Modifier.semantics {
                    contentDescription = "Choose terminal theme"
                },
        ) {
            Icon(
                imageVector = Icons.Outlined.Palette,
                contentDescription = null,
                tint = accent,
            )
        }
    }
}
