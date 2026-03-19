/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Maximum readable content width for settings, forms, and card-based screens. */
private val MaxContentWidth = 840.dp

/**
 * Centers dense content on larger layouts while keeping compact layouts full width.
 *
 * @param modifier Modifier applied to the outer full-size container.
 * @param content Content rendered inside the constrained inner pane.
 */
@Composable
fun ContentPane(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .widthIn(max = MaxContentWidth),
            content = content,
        )
    }
}
