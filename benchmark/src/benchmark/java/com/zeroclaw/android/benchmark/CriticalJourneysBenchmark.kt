/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Number of benchmark iterations per journey. */
private const val ITERATIONS = 5

/** Timeout in milliseconds while waiting for benchmark UI state. */
private const val WAIT_TIMEOUT_MS = 10_000L

/** Package name for the benchmarked application. */
private const val TARGET_PACKAGE = "com.zeroclaw.android"

/** Fully qualified launcher activity for the benchmarked application. */
private const val TARGET_ACTIVITY = "com.zeroclaw.android.MainActivity"

/** Intent extra used by the app to select a benchmark-safe start destination. */
private const val EXTRA_BENCHMARK_START_DESTINATION =
    "com.zeroclaw.android.extra.BENCHMARK_START_DESTINATION"

/** Start destination token for the dashboard screen. */
private const val ROUTE_DASHBOARD = "dashboard"

/** Start destination token for the API keys screen. */
private const val ROUTE_API_KEYS = "api_keys"

/** Start destination token for the provider connections screen. */
private const val ROUTE_PROVIDER_CONNECTIONS = "provider_connections"

/** Start destination token for the terminal screen. */
private const val ROUTE_TERMINAL = "terminal"

/**
 * Macrobenchmarks covering the critical journeys called out in the UI/UX audit.
 *
 * These runs measure cold startup into dashboard, terminal compose/send/scroll,
 * provider connections open, and API key list open using the app's benchmark
 * build type and baseline-profile-aware compilation.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class CriticalJourneysBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStartupDashboard() =
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
            iterations = ITERATIONS,
            startupMode = StartupMode.COLD,
        ) {
            launchRoute(ROUTE_DASHBOARD)
            device.wait(Until.hasObject(By.text("At a Glance")), WAIT_TIMEOUT_MS)
        }

    @Test
    fun terminalSendAndScroll() =
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
            iterations = ITERATIONS,
            setupBlock = {
                launchRoute(ROUTE_TERMINAL)
                device.wait(
                    Until.hasObject(By.text("Type a command or message")),
                    WAIT_TIMEOUT_MS,
                )
            },
        ) {
            val input =
                device.findObject(By.text("Type a command or message"))
                    ?: error("Terminal input field did not appear")
            input.text = "hello from benchmark"
            device.findObject(By.desc("Send"))?.click() ?: error("Send button did not appear")
            device.wait(
                Until.hasObject(By.textContains("No chat provider configured")),
                WAIT_TIMEOUT_MS,
            )
            device.waitForIdle()
            scrollTerminalHistory()
        }

    @Test
    fun providerConnectionsOpen() =
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
            iterations = ITERATIONS,
            setupBlock = {
                pressHome()
            },
        ) {
            launchRoute(ROUTE_PROVIDER_CONNECTIONS)
            device.wait(
                Until.hasObject(
                    By.text("Manage Claude, ChatGPT, and Gemini logins separately from manual API keys."),
                ),
                WAIT_TIMEOUT_MS,
            )
        }

    @Test
    fun apiKeysOpen() =
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
            iterations = ITERATIONS,
            setupBlock = {
                pressHome()
            },
        ) {
            launchRoute(ROUTE_API_KEYS)
            device.wait(Until.hasObject(By.text("API Keys")), WAIT_TIMEOUT_MS)
        }

    /**
     * Starts the target app into a benchmark-specific route while force-stopping
     * any existing process state so each iteration is deterministic.
     */
    private fun MacrobenchmarkScope.launchRoute(route: String) {
        pressHome()
        val command =
            buildString {
                append("am start -W -S -n ")
                append(TARGET_PACKAGE)
                append("/")
                append(TARGET_ACTIVITY)
                append(" --es ")
                append(EXTRA_BENCHMARK_START_DESTINATION)
                append(" ")
                append(route)
            }
        device.executeShellCommand(command)
        device.wait(Until.hasObject(By.pkg(TARGET_PACKAGE).depth(0)), WAIT_TIMEOUT_MS)
        device.waitForIdle()
    }

    /**
     * Flings the terminal list in both directions to exercise rendering of
     * recent blocks after a send.
     */
    private fun MacrobenchmarkScope.scrollTerminalHistory() {
        val displayHeight = device.displayHeight
        val displayWidth = device.displayWidth
        val startX = displayWidth / 2
        val startY = (displayHeight * 0.75f).toInt()
        val endY = (displayHeight * 0.25f).toInt()
        repeat(2) {
            device.swipe(startX, startY, startX, endY, 24)
            device.waitForIdle()
            device.swipe(startX, endY, startX, startY, 24)
            device.waitForIdle()
        }
    }
}
