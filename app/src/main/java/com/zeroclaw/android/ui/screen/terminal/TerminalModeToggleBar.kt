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
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Vertical padding around the terminal mode toggle bar. */
private const val TOGGLE_BAR_VERTICAL_PAD_DP = 4

/**
 * Persistent top bar holding a single icon that toggles the terminal between
 * the interactive REPL/chat surface and the raw Shell (local PTY / SSH) surface.
 *
 * The two surfaces are visually distinct, so the toggle carries no label or
 * mode chip: the icon simply flips the active surface via the view model's
 * `switchToRepl`/`switchToTty` transitions. A state-aware content description
 * announces the current mode and the switch action for accessibility. An
 * opaque surface background keeps the bar visible above either themed surface.
 *
 * @param isShellMode Whether the Shell (TTY) surface is currently active.
 * @param onSelectRepl Switches to the REPL surface.
 * @param onSelectShell Switches to the Shell surface.
 * @param edgeMargin Horizontal inset matching the window width size class.
 * @param modifier Modifier applied to the bar row.
 */
@Composable
internal fun TerminalModeToggleBar(
    isShellMode: Boolean,
    onSelectRepl: () -> Unit,
    onSelectShell: () -> Unit,
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
        horizontalArrangement = Arrangement.End,
    ) {
        IconButton(
            onClick = { if (isShellMode) onSelectRepl() else onSelectShell() },
            modifier =
                Modifier.semantics {
                    contentDescription =
                        if (isShellMode) {
                            "Shell mode active, switch to chat"
                        } else {
                            "Chat mode active, switch to shell"
                        }
                },
        ) {
            Icon(
                imageVector = Icons.Outlined.Terminal,
                contentDescription = null,
                tint = accent,
            )
        }
    }
}
