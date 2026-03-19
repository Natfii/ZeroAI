/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.zeroclaw.android.util.LocalPowerSaveMode
import com.zeroclaw.android.util.rememberPowerSaveMode

private val DarkColorScheme =
    darkColorScheme(
        primary = Color(0xFF8AE7DD),
        onPrimary = Color(0xFF003732),
        primaryContainer = Color(0xFF00504A),
        onPrimaryContainer = Color(0xFFA6F3E9),
        secondary = Color(0xFFB4CCC7),
        onSecondary = Color(0xFF1F352F),
        secondaryContainer = Color(0xFF354B45),
        onSecondaryContainer = Color(0xFFD0E8E2),
        tertiary = Color(0xFFFFC98D),
        onTertiary = Color(0xFF4B2800),
        tertiaryContainer = Color(0xFF6B3A00),
        onTertiaryContainer = Color(0xFFFFDDB8),
    )

private val LightColorScheme =
    lightColorScheme(
        primary = Color(0xFF006B63),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFF9EF2E7),
        onPrimaryContainer = Color(0xFF00201D),
        secondary = Color(0xFF4A635D),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFCDE8E1),
        onSecondaryContainer = Color(0xFF051F1A),
        tertiary = Color(0xFF8B4B00),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFFFDDB8),
        onTertiaryContainer = Color(0xFF2C1600),
    )

/**
 * Application theme for ZeroAI.
 *
 * Uses Material You dynamic colour on Android 12+ and falls back to
 * the default Material 3 colour scheme on older API levels. Provides
 * [LocalPowerSaveMode] for battery-conscious rendering and
 * [ZeroAITypography] for explicit sp-based line heights.
 *
 * @param darkTheme Whether to apply the dark variant of the theme.
 * @param dynamicColor Whether to use platform dynamic colour (Material You).
 * @param content The composable content to theme.
 */
@Composable
fun ZeroAITheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme =
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val context = LocalContext.current
                if (darkTheme) {
                    dynamicDarkColorScheme(context)
                } else {
                    dynamicLightColorScheme(context)
                }
            }
            darkTheme -> DarkColorScheme
            else -> LightColorScheme
        }

    val isPowerSave = rememberPowerSaveMode()

    CompositionLocalProvider(LocalPowerSaveMode provides isPowerSave) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = ZeroAITypography,
            content = content,
        )
    }
}
