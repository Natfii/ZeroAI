/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.plugins

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.zeroclaw.android.model.SearchEngineHealth

/** Engine condition reported when searches are succeeding. */
private const val CONDITION_HEALTHY = "healthy"

/** Engine condition reported while the engine waits out a failure backoff. */
private const val CONDITION_BACKOFF = "backoff"

/** Engine condition reported while the self-repair pipeline investigates a layout change. */
private const val CONDITION_LAYOUT_SUSPECT = "layout_suspect"

/** Seconds per minute, for the backoff countdown display. */
private const val SECONDS_PER_MINUTE = 60L

/**
 * Per-engine status rows for the on-device meta search backend.
 *
 * Each row pairs an 8dp colored dot with a text label so color is never
 * the only status differentiator: "Active" (healthy, primary), "Backing
 * off (Nm)" (backoff with minutes remaining, tertiary), and "Repairing"
 * (layout_suspect, error). Rows whose engine has self-repaired append a
 * "self-repaired" note. Renders nothing when [engines] is empty, which
 * is how callers hide the section when health data is unavailable.
 *
 * @param engines Engine health rows from the health FFI.
 * @param modifier Modifier applied to the section column.
 */
@Composable
fun WebSearchEngineStatusSection(
    engines: List<SearchEngineHealth>,
    modifier: Modifier = Modifier,
) {
    if (engines.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Engine status",
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(modifier = Modifier.height(4.dp))
        for (engine in engines) {
            WebSearchEngineStatusRow(engine)
        }
    }
}

/**
 * Single engine row: colored status dot, engine name, and status label.
 *
 * @param engine Health row to render.
 */
@Composable
private fun WebSearchEngineStatusRow(engine: SearchEngineHealth) {
    val (color, label) = engineStatusPresentation(engine)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription = "${engine.displayName} engine status: $label"
                },
    ) {
        Box(
            modifier =
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = engine.displayName,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Maps an engine's condition to its status dot color and text label.
 *
 * @param engine Health row to present.
 * @return Dot color paired with the human-readable status label.
 */
@Composable
private fun engineStatusPresentation(engine: SearchEngineHealth): Pair<Color, String> {
    val base =
        when (engine.condition) {
            CONDITION_HEALTHY -> MaterialTheme.colorScheme.primary to "Active"
            CONDITION_BACKOFF ->
                MaterialTheme.colorScheme.tertiary to
                    "Backing off (${backoffMinutes(engine.backoffRemainingSecs)}m)"
            CONDITION_LAYOUT_SUSPECT -> MaterialTheme.colorScheme.error to "Repairing"
            else -> MaterialTheme.colorScheme.outline to engine.condition
        }
    return if (engine.repairedAtUnix != null) {
        base.first to "${base.second} · self-repaired"
    } else {
        base
    }
}

/**
 * Converts remaining backoff seconds to whole minutes for display,
 * rounding up and never reporting less than one minute.
 *
 * @param backoffRemainingSecs Remaining backoff seconds, or `null`.
 * @return Minutes remaining, at least 1.
 */
private fun backoffMinutes(backoffRemainingSecs: Long?): Long {
    val secs = backoffRemainingSecs ?: 0L
    return ((secs + SECONDS_PER_MINUTE - 1) / SECONDS_PER_MINUTE).coerceAtLeast(1L)
}
