/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Height of the TTY status bar in dp. */
private const val STATUS_BAR_HEIGHT_DP = 48

/** Size of the status indicator dot in dp. */
private const val STATUS_DOT_SIZE_DP = 8

/** Spacing between the status dot and the label text in dp. */
private const val DOT_LABEL_SPACING_DP = 8

/** Horizontal edge padding for the status bar in dp. */
private const val EDGE_PADDING_DP = 16

/** Size of the warning icon in dp. */
private const val WARNING_ICON_SIZE_DP = 16

/** Minimum touch target size for interactive elements in dp. */
private const val TOUCH_TARGET_SIZE_DP = 48

/**
 * Status bar displayed at the top of the TTY terminal surface.
 *
 * Shows the current session state on the left side using a colored
 * indicator and descriptive label, and a close button on the right.
 * The entire row is marked as a polite live region so screen readers
 * announce state transitions without interrupting the user.
 *
 * When a [terminalTitle] is provided (set by the shell via OSC 0/2),
 * it is appended to the status label after an em-dash separator.
 *
 * Color mapping:
 * - [TtySessionUiState.LocalShell] and [TtySessionUiState.SshConnected]:
 *   [MaterialTheme.colorScheme.tertiary] (green-like).
 * - [TtySessionUiState.SshConnecting] and [TtySessionUiState.SshAuthRequired]:
 *   [MaterialTheme.colorScheme.secondary] (amber-like).
 * - [TtySessionUiState.Error]: [MaterialTheme.colorScheme.error] (red).
 * - [TtySessionUiState.HostKeyVerification]:
 *   [MaterialTheme.colorScheme.secondary] (amber-like).
 *
 * @param session Current lifecycle state of the TTY session.
 * @param onClose Callback when the user taps the close button.
 * @param terminalTitle Terminal title set by OSC 0/2, or `null` if unset.
 * @param modifier Modifier applied to the outer [Row].
 */
@Composable
fun TtyStatusBar(
    session: TtySessionUiState,
    onClose: () -> Unit,
    terminalTitle: String? = null,
    modifier: Modifier = Modifier,
) {
    val (indicatorColor, baseLabel) = resolveStatusDisplay(session)
    val label = if (terminalTitle != null) "$baseLabel \u2014 $terminalTitle" else baseLabel

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .height(STATUS_BAR_HEIGHT_DP.dp)
                .padding(horizontal = EDGE_PADDING_DP.dp)
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = "Terminal status: $label"
                },
    ) {
        StatusIndicator(
            color = indicatorColor,
            isError = session is TtySessionUiState.Error,
        )
        Spacer(modifier = Modifier.width(DOT_LABEL_SPACING_DP.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = indicatorColor,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = onClose,
            modifier =
                Modifier
                    .size(TOUCH_TARGET_SIZE_DP.dp)
                    .semantics {
                        contentDescription = "Close terminal session"
                    },
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * Resolves the indicator color and label text for a given session state.
 *
 * @param session Current TTY session UI state.
 * @return A [Pair] of indicator [Color] and human-readable label.
 */
@Composable
private fun resolveStatusDisplay(session: TtySessionUiState): Pair<Color, String> =
    when (session) {
        is TtySessionUiState.LocalShell ->
            MaterialTheme.colorScheme.tertiary to "Local"
        is TtySessionUiState.SshConnecting ->
            MaterialTheme.colorScheme.secondary to "Connecting\u2026"
        is TtySessionUiState.HostKeyVerification ->
            MaterialTheme.colorScheme.secondary to "Verify host key"
        is TtySessionUiState.SshAuthRequired ->
            MaterialTheme.colorScheme.secondary to "Authentication required"
        is TtySessionUiState.SshConnected ->
            MaterialTheme.colorScheme.tertiary to "SSH ${session.hostLabel}"
        is TtySessionUiState.Error ->
            MaterialTheme.colorScheme.error to "Disconnected"
    }

/**
 * Small colored indicator showing the session health.
 *
 * Renders a filled circle dot for healthy or intermediate states,
 * and a warning triangle icon for error states.
 *
 * @param color Tint color for the indicator.
 * @param isError Whether to show a warning icon instead of a dot.
 */
@Composable
private fun StatusIndicator(
    color: Color,
    isError: Boolean,
) {
    if (isError) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(WARNING_ICON_SIZE_DP.dp),
        )
    } else {
        Box(
            modifier =
                Modifier
                    .size(STATUS_DOT_SIZE_DP.dp)
                    .clip(CircleShape)
                    .background(color),
        )
    }
}
