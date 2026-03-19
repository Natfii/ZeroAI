/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zeroclaw.android.BuildConfig
import com.zeroclaw.android.model.AppSettings
import com.zeroclaw.android.model.ThemeMode
import com.zeroclaw.android.navigation.ApiKeysRoute
import com.zeroclaw.android.navigation.DashboardRoute
import com.zeroclaw.android.navigation.ProviderConnectionsRoute
import com.zeroclaw.android.navigation.SettingsRoute
import com.zeroclaw.android.navigation.TerminalRoute
import com.zeroclaw.android.navigation.ZeroAIAppShell
import com.zeroclaw.android.ui.theme.ZeroAITheme

/**
 * Main entry point for the ZeroAI Android application.
 *
 * Sets up edge-to-edge display and delegates all UI to
 * [ZeroAIAppShell] which manages navigation, the adaptive
 * navigation bar, and all screens.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val app = application as ZeroAIApplication
            val settings by app.settingsRepository.settings
                .collectAsStateWithLifecycle(
                    initialValue = AppSettings(),
                )
            val darkTheme =
                when (settings.theme) {
                    ThemeMode.SYSTEM -> isSystemInDarkTheme()
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                }
            val benchmarkStartDestination =
                if (BuildConfig.DEBUG || BuildConfig.BUILD_TYPE == "benchmark") {
                    when (intent.getStringExtra(EXTRA_BENCHMARK_START_DESTINATION)) {
                        BENCHMARK_ROUTE_API_KEYS -> ApiKeysRoute
                        BENCHMARK_ROUTE_DASHBOARD -> DashboardRoute
                        BENCHMARK_ROUTE_PROVIDER_CONNECTIONS -> ProviderConnectionsRoute
                        BENCHMARK_ROUTE_SETTINGS -> SettingsRoute
                        BENCHMARK_ROUTE_TERMINAL -> TerminalRoute
                        else -> null
                    }
                } else {
                    null
                }
            ZeroAITheme(darkTheme = darkTheme) {
                @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
                val windowSizeClass = calculateWindowSizeClass(this@MainActivity)
                ZeroAIAppShell(
                    windowWidthSizeClass = windowSizeClass.widthSizeClass,
                    benchmarkStartDestination = benchmarkStartDestination,
                )
            }
        }
    }

    /** Benchmark route constants for macrobenchmark navigation. */
    companion object {
        /** Intent extra used by macrobenchmarks to bypass first-run onboarding. */
        const val EXTRA_BENCHMARK_START_DESTINATION =
            "com.zeroclaw.android.extra.BENCHMARK_START_DESTINATION"

        /** Benchmark route token for the dashboard overview. */
        const val BENCHMARK_ROUTE_DASHBOARD = "dashboard"

        /** Benchmark route token for the API keys screen. */
        const val BENCHMARK_ROUTE_API_KEYS = "api_keys"

        /** Benchmark route token for the provider connections screen. */
        const val BENCHMARK_ROUTE_PROVIDER_CONNECTIONS = "provider_connections"

        /** Benchmark route token for the top-level settings screen. */
        const val BENCHMARK_ROUTE_SETTINGS = "settings"

        /** Benchmark route token for the terminal screen. */
        const val BENCHMARK_ROUTE_TERMINAL = "terminal"
    }
}
