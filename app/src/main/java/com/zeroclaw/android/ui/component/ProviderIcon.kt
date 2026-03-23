/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import com.zeroclaw.android.data.ProviderRegistry

/** Minimum touch target size for accessibility. */
private const val ICON_SIZE_DP = 40

/** Pre-scaled pixel size for Coil image requests (40dp at 4x density). */
private const val ICON_SIZE_PX = 160

/** Brand color for Anthropic provider. */
private const val COLOR_ANTHROPIC = 0xFFD97706

/** Brand color for OpenAI provider. */
private const val COLOR_OPENAI = 0xFF10A37F

/** Brand color for Google Gemini provider. */
private const val COLOR_GOOGLE = 0xFF4285F4

/** Brand color for Ollama provider. */
private const val COLOR_OLLAMA = 0xFF000000

/** Brand color for OpenRouter provider. */
private const val COLOR_OPENROUTER = 0xFF6467F2

/** Brand color for xAI. */
private const val COLOR_XAI = 0xFF1D1D1F

/** Brand color for DeepSeek. */
private const val COLOR_DEEPSEEK = 0xFF4D6BFE

/** Brand color for Qwen (Alibaba). */
private const val COLOR_QWEN = 0xFF615EF0

/**
 * Pre-computed brand background colors keyed by provider ID.
 *
 * Foreground color is determined dynamically via [contrastingForeground]
 * to guarantee WCAG AA contrast (4.5:1) regardless of background luminance.
 */
private val PROVIDER_BRAND_COLORS: Map<String, Color> =
    mapOf(
        "anthropic" to Color(COLOR_ANTHROPIC),
        "openai" to Color(COLOR_OPENAI),
        "google-gemini" to Color(COLOR_GOOGLE),
        "ollama" to Color(COLOR_OLLAMA),
        "openrouter" to Color(COLOR_OPENROUTER),
        "xai" to Color(COLOR_XAI),
        "deepseek" to Color(COLOR_DEEPSEEK),
        "qwen" to Color(COLOR_QWEN),
    )

/** Luminance threshold below which white text is used, ensuring 4.5:1 contrast. */
private const val LUMINANCE_THRESHOLD = 0.179f

/**
 * Returns [Color.White] or [Color.Black] to ensure WCAG AA contrast against [background].
 *
 * @param background The background color to contrast against.
 * @return White for dark backgrounds, black for light backgrounds.
 */
private fun contrastingForeground(background: Color): Color = if (background.luminance() <= LUMINANCE_THRESHOLD) Color.White else Color.Black

/**
 * Circular icon showing the provider's logo fetched via Coil, with a
 * colored-circle-with-initial fallback when no icon URL is available or
 * loading fails.
 *
 * The image request includes an explicit size so Coil can decode at
 * the correct resolution without needing to measure the composable.
 *
 * Uses [ProviderRegistry] to resolve aliases so that e.g. "google" and
 * "google-gemini" both produce the same icon.
 *
 * @param provider Provider ID or name (e.g. "anthropic", "OpenAI").
 * @param modifier Modifier applied to the root layout.
 */
@Composable
fun ProviderIcon(
    provider: String,
    modifier: Modifier = Modifier,
) {
    val resolved = ProviderRegistry.findById(provider)
    val displayName = resolved?.displayName ?: provider
    val resolvedId = resolved?.id ?: provider.lowercase()
    val iconUrl = resolved?.iconUrl.orEmpty()

    if (iconUrl.isNotEmpty()) {
        val context = LocalContext.current
        val imageRequest =
            remember(iconUrl) {
                ImageRequest
                    .Builder(context)
                    .data(iconUrl)
                    .size(ICON_SIZE_PX, ICON_SIZE_PX)
                    .build()
            }
        SubcomposeAsyncImage(
            model = imageRequest,
            contentDescription = "$displayName provider",
            contentScale = ContentScale.Crop,
            modifier =
                modifier
                    .size(ICON_SIZE_DP.dp)
                    .clip(CircleShape),
            loading = { InitialCircle(resolvedId, displayName, Modifier) },
            error = { InitialCircle(resolvedId, displayName, Modifier) },
        )
    } else {
        InitialCircle(resolvedId, displayName, modifier)
    }
}

/**
 * Colored circle fallback showing the first letter of the provider name.
 *
 * @param providerId Resolved canonical provider ID for color lookup.
 * @param displayName Human-readable provider name.
 * @param modifier Modifier applied to the root layout.
 */
@Composable
private fun InitialCircle(
    providerId: String,
    displayName: String,
    modifier: Modifier,
) {
    val fallbackBg = MaterialTheme.colorScheme.secondaryContainer
    val fallbackFg = MaterialTheme.colorScheme.onSecondaryContainer
    val brandBg = PROVIDER_BRAND_COLORS[providerId]
    val bgColor = brandBg ?: fallbackBg
    val fgColor = if (brandBg != null) contrastingForeground(brandBg) else fallbackFg
    val initial = displayName.firstOrNull()?.uppercase() ?: "?"

    Box(
        modifier =
            modifier
                .size(ICON_SIZE_DP.dp)
                .clip(CircleShape)
                .background(bgColor)
                .semantics { contentDescription = "$displayName provider" },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial,
            color = fgColor,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}
