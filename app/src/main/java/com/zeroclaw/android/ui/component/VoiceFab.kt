/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.component

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.zeroclaw.android.model.VoiceState
import com.zeroclaw.android.util.LocalPowerSaveMode

/** Pulsing animation duration in milliseconds. */
private const val PULSE_DURATION_MS = 600

/** Minimum scale factor for the pulsing animation. */
private const val PULSE_SCALE_MIN = 0.9f

/** Maximum scale factor for the pulsing animation. */
private const val PULSE_SCALE_MAX = 1.1f

/** Size of the circular progress indicator inside the FAB. */
private const val PROGRESS_SIZE_DP = 24

/** Stroke width of the circular progress indicator. */
private const val PROGRESS_STROKE_DP = 3

/**
 * Floating action button for voice input/output on the terminal screen.
 *
 * The FAB appearance adapts to the current [VoiceState]:
 * - [VoiceState.Idle]: microphone icon on primary container background
 * - [VoiceState.Listening]: animated pulsing mic icon on error (red) background
 * - [VoiceState.Processing]: circular progress indicator inside the FAB
 * - [VoiceState.Speaking]: volume/speaker icon on tertiary background
 * - [VoiceState.Error]: mic-off icon on error container background
 *
 * The pulsing animation during [VoiceState.Listening] is disabled when
 * the device is in power-save mode, falling back to a static icon.
 *
 * Accessibility: the FAB uses [LiveRegionMode.Polite] so screen readers
 * announce state changes. The [contentDescription] updates per state.
 * Long-press while [VoiceState.Speaking] stops TTS playback.
 *
 * @param voiceState Current voice pipeline state.
 * @param onClick Callback when the FAB is tapped.
 * @param onLongClick Callback when the FAB is long-pressed. Used to
 *   stop TTS while [VoiceState.Speaking].
 * @param modifier Modifier applied to the FAB.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VoiceFab(
    voiceState: VoiceState,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isPowerSave = LocalPowerSaveMode.current

    val description =
        when (voiceState) {
            is VoiceState.Idle -> "Start voice input"
            is VoiceState.Listening -> "Listening\u2026"
            is VoiceState.Processing -> "Processing\u2026"
            is VoiceState.Speaking -> "Stop speaking"
            is VoiceState.Error -> "Voice error"
        }

    val containerColor =
        when (voiceState) {
            is VoiceState.Idle -> MaterialTheme.colorScheme.primaryContainer
            is VoiceState.Listening -> MaterialTheme.colorScheme.error
            is VoiceState.Processing -> MaterialTheme.colorScheme.primaryContainer
            is VoiceState.Speaking -> MaterialTheme.colorScheme.tertiary
            is VoiceState.Error -> MaterialTheme.colorScheme.errorContainer
        }

    val contentColor =
        when (voiceState) {
            is VoiceState.Idle -> MaterialTheme.colorScheme.onPrimaryContainer
            is VoiceState.Listening -> MaterialTheme.colorScheme.onError
            is VoiceState.Processing -> MaterialTheme.colorScheme.onPrimaryContainer
            is VoiceState.Speaking -> MaterialTheme.colorScheme.onTertiary
            is VoiceState.Error -> MaterialTheme.colorScheme.onErrorContainer
        }

    val pulseScale =
        if (voiceState is VoiceState.Listening && !isPowerSave) {
            val transition = rememberInfiniteTransition(label = "voicePulse")
            val scale by transition.animateFloat(
                initialValue = PULSE_SCALE_MIN,
                targetValue = PULSE_SCALE_MAX,
                animationSpec =
                    infiniteRepeatable(
                        animation = tween(PULSE_DURATION_MS),
                        repeatMode = RepeatMode.Reverse,
                    ),
                label = "pulseScale",
            )
            scale
        } else {
            1f
        }

    FloatingActionButton(
        onClick = onClick,
        containerColor = containerColor,
        contentColor = contentColor,
        modifier =
            modifier
                .scale(pulseScale)
                .semantics {
                    this.contentDescription = description
                    liveRegion = LiveRegionMode.Polite
                }.combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                ),
    ) {
        when (voiceState) {
            is VoiceState.Idle -> {
                Icon(
                    imageVector = Icons.Filled.Mic,
                    contentDescription = null,
                )
            }
            is VoiceState.Listening -> {
                Icon(
                    imageVector = Icons.Filled.Mic,
                    contentDescription = null,
                )
            }
            is VoiceState.Processing -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(PROGRESS_SIZE_DP.dp),
                    color = contentColor,
                    strokeWidth = PROGRESS_STROKE_DP.dp,
                )
            }
            is VoiceState.Speaking -> {
                Icon(
                    imageVector = Icons.Filled.VolumeUp,
                    contentDescription = null,
                )
            }
            is VoiceState.Error -> {
                Icon(
                    imageVector = Icons.Filled.MicOff,
                    contentDescription = null,
                )
            }
        }
    }
}
