/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.settings

import com.zeroclaw.android.ZeroAIApplication
import com.zeroclaw.android.model.AppSettings
import com.zeroclaw.android.service.DaemonServiceBridge
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Characterization tests for [SettingsViewModel].
 *
 * Locks in the current [DaemonServiceBridge.markRestartRequired] distribution
 * (which updaters mark a restart and which deliberately do not), the
 * scheduler/heartbeat clamps to [MIN_DAEMON_LIMIT], and the fallback-route
 * early-return path. These assert ACTUAL current behavior and must pass
 * against the code as-is; they are not change requests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("SettingsViewModel restart behavior")
class SettingsViewModelRestartBehaviorTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var repository: TestSettingsRepository
    private lateinit var bridge: DaemonServiceBridge
    private lateinit var onboardingRepository:
        com.zeroclaw.android.data.repository.OnboardingRepository
    private lateinit var mockApp: ZeroAIApplication
    private lateinit var viewModel: SettingsViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockkStatic("com.zeroclaw.ffi.Zeroclaw_androidKt")
        repository = TestSettingsRepository()
        bridge =
            mockk(relaxed = true) {
                every { restartRequired } returns MutableStateFlow(false)
            }
        onboardingRepository = mockk(relaxed = true)
        mockApp =
            mockk<ZeroAIApplication>(relaxed = true) {
                every { settingsRepository } returns repository
                every { daemonBridge } returns bridge
                every { onboardingRepository } returns this@SettingsViewModelRestartBehaviorTest.onboardingRepository
                every { agentRepository } returns
                    mockk { every { agents } returns MutableStateFlow(emptyList()) }
            }
        viewModel = SettingsViewModel(mockApp)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    @DisplayName("updateHost persists and marks restart required")
    fun `updateHost persists and marks restart required`() =
        runTest {
            viewModel.updateHost("x")

            assertEquals("x", repository.settings.first().host)
            verify(exactly = 1) { bridge.markRestartRequired() }
        }

    @Test
    @DisplayName("updateAutoStartOnBoot persists without marking restart")
    fun `updateAutoStartOnBoot persists without marking restart`() =
        runTest {
            viewModel.updateAutoStartOnBoot(true)

            assertEquals(true, repository.settings.first().autoStartOnBoot)
            verify(exactly = 0) { bridge.markRestartRequired() }
        }

    @Test
    @DisplayName("updateStripThinkingTags persists without marking restart")
    fun `updateStripThinkingTags persists without marking restart`() =
        runTest {
            viewModel.updateStripThinkingTags(true)

            assertEquals(true, repository.settings.first().stripThinkingTags)
            verify(exactly = 0) { bridge.markRestartRequired() }
        }

    @Test
    @DisplayName("updateSchedulerMaxTasks clamps 0 to MIN_DAEMON_LIMIT")
    fun `updateSchedulerMaxTasks clamps zero to one`() =
        runTest {
            viewModel.updateSchedulerMaxTasks(0L)

            assertEquals(1L, repository.settings.first().schedulerMaxTasks)
            verify(exactly = 1) { bridge.markRestartRequired() }
        }

    @Test
    @DisplayName("updateSchedulerMaxTasks keeps positive value unclamped")
    fun `updateSchedulerMaxTasks keeps positive value`() =
        runTest {
            viewModel.updateSchedulerMaxTasks(7L)

            assertEquals(7L, repository.settings.first().schedulerMaxTasks)
        }

    @Test
    @DisplayName("updateSchedulerMaxConcurrent clamps 0 to MIN_DAEMON_LIMIT")
    fun `updateSchedulerMaxConcurrent clamps zero to one`() =
        runTest {
            viewModel.updateSchedulerMaxConcurrent(0L)

            assertEquals(1L, repository.settings.first().schedulerMaxConcurrent)
            verify(exactly = 1) { bridge.markRestartRequired() }
        }

    @Test
    @DisplayName("updateHeartbeatIntervalMinutes clamps 0 to MIN_DAEMON_LIMIT")
    fun `updateHeartbeatIntervalMinutes clamps zero to one`() =
        runTest {
            viewModel.updateHeartbeatIntervalMinutes(0L)

            assertEquals(1L, repository.settings.first().heartbeatIntervalMinutes)
            verify(exactly = 1) { bridge.markRestartRequired() }
        }

    @Test
    @DisplayName("updateWebSearchRequestsPerMinute clamps 0 to 1 and marks restart required")
    fun `updateWebSearchRequestsPerMinute clamps zero to one and marks restart`() =
        runTest {
            viewModel.updateWebSearchRequestsPerMinute(0L)

            assertEquals(1L, repository.settings.first().webSearchRequestsPerMinute)
            verify(exactly = 1) { bridge.markRestartRequired() }
        }

    @Test
    @DisplayName("updateWebSearchRequestsPerMinute clamps 100 to 60")
    fun `updateWebSearchRequestsPerMinute clamps one hundred to sixty`() =
        runTest {
            viewModel.updateWebSearchRequestsPerMinute(100L)

            assertEquals(60L, repository.settings.first().webSearchRequestsPerMinute)
        }

    @Test
    @DisplayName("updateWebSearchRequestsPerMinute keeps an in-range value unclamped")
    fun `updateWebSearchRequestsPerMinute keeps in-range value`() =
        runTest {
            viewModel.updateWebSearchRequestsPerMinute(25L)

            assertEquals(25L, repository.settings.first().webSearchRequestsPerMinute)
        }

    @Test
    @DisplayName("updateWebSearchTavilyApiKey persists and marks restart required")
    fun `updateWebSearchTavilyApiKey persists and marks restart`() =
        runTest {
            viewModel.updateWebSearchTavilyApiKey("tvly-key")

            assertEquals("tvly-key", repository.settings.first().webSearchTavilyApiKey)
            verify(exactly = 1) { bridge.markRestartRequired() }
        }

    @Test
    @DisplayName("updateWebSearchSearxngUrl persists and marks restart required")
    fun `updateWebSearchSearxngUrl persists and marks restart`() =
        runTest {
            viewModel.updateWebSearchSearxngUrl("https://searx.example.com")

            assertEquals(
                "https://searx.example.com",
                repository.settings.first().webSearchSearxngUrl,
            )
            verify(exactly = 1) { bridge.markRestartRequired() }
        }

    @Test
    @DisplayName("selectFallbackRouteOption with unknown id is a no-op")
    fun `selectFallbackRouteOption unknown id is a no-op`() =
        runTest {
            viewModel.selectFallbackRouteOption("does-not-exist")

            verify(exactly = 0) { bridge.markRestartRequired() }
            assertEquals(AppSettings().defaultProvider, repository.settings.first().defaultProvider)
            assertEquals(AppSettings().defaultModel, repository.settings.first().defaultModel)
        }

    @Test
    @DisplayName("resetOnboarding clears identity, marks restart, resets onboarding")
    fun `resetOnboarding clears identity and resets`() =
        runTest {
            viewModel.resetOnboarding()

            assertEquals("", repository.settings.first().identityJson)
            verify { bridge.markRestartRequired() }
            coVerify { onboardingRepository.reset() }
        }
}
