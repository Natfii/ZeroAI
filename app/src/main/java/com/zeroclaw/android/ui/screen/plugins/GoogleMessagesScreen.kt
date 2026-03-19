/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.plugins

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

/**
 * Redirect stub for backward compatibility.
 *
 * Delegates to the full implementation in
 * [com.zeroclaw.android.ui.screen.messages.GoogleMessagesScreen].
 *
 * @param onBack Callback invoked when the user navigates back.
 * @param edgeMargin Horizontal padding based on window width size class.
 * @param modifier Modifier applied to the root layout.
 */
@Composable
fun GoogleMessagesScreenStub(
    onBack: () -> Unit,
    edgeMargin: Dp,
    modifier: Modifier = Modifier,
) {
    com.zeroclaw.android.ui.screen.messages.GoogleMessagesScreen(
        onBack = onBack,
        edgeMargin = edgeMargin,
        modifier = modifier,
    )
}
