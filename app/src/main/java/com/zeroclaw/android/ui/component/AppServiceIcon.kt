/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

@file:Suppress("FunctionNaming")

package com.zeroclaw.android.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest

/** Default icon size for remote service icons. */
private val ServiceIconSize = 40.dp

/** Pre-scaled pixel size for Coil image requests. */
private const val SERVICE_ICON_SIZE_PX = 160

/**
 * Circular icon for an external service such as Telegram, Discord, or a Google app.
 *
 * Loads the service's favicon or product icon from [iconUrl] and falls back to a
 * simple initial badge when the image is unavailable.
 *
 * @param label Human-readable service name used for accessibility and fallback text.
 * @param iconUrl Remote image URL to load.
 * @param modifier Modifier applied to the root icon.
 */
@Composable
fun AppServiceIcon(
    label: String,
    iconUrl: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val imageRequest =
        remember(iconUrl) {
            ImageRequest
                .Builder(context)
                .data(iconUrl)
                .size(SERVICE_ICON_SIZE_PX, SERVICE_ICON_SIZE_PX)
                .build()
        }

    SubcomposeAsyncImage(
        model = imageRequest,
        contentDescription = "$label icon",
        contentScale = ContentScale.Crop,
        modifier =
            modifier
                .size(ServiceIconSize)
                .clip(CircleShape),
        loading = { AppServiceInitial(label = label, modifier = Modifier) },
        error = { AppServiceInitial(label = label, modifier = Modifier) },
    )
}

/**
 * Fallback circular initial used when a service icon is unavailable.
 *
 * @param label Human-readable service name.
 * @param modifier Modifier applied to the root icon.
 */
@Composable
private fun AppServiceInitial(
    label: String,
    modifier: Modifier,
) {
    Box(
        modifier =
            modifier
                .size(ServiceIconSize)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .semantics { contentDescription = "$label icon" },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label.firstOrNull()?.uppercase() ?: "?",
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}
