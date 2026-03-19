/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import com.zeroclaw.ffi.FfiCostSummary
import com.zeroclaw.ffi.FfiException
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Unit tests for [CostBridge].
 *
 * Uses MockK to mock the static UniFFI-generated functions so that tests
 * run without loading the native library.
 */
@DisplayName("CostBridge")
class CostBridgeTest {
    private lateinit var bridge: CostBridge

    /** Sets up mocks and creates a [CostBridge] with an unconfined dispatcher. */
    @BeforeEach
    fun setUp() {
        mockkStatic("com.zeroclaw.ffi.Zeroclaw_androidKt")
        bridge = CostBridge(ioDispatcher = UnconfinedTestDispatcher())
    }

    /** Tears down all mocks after each test. */
    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    @DisplayName("getCostSummary converts FFI record to CostSummary model")
    fun `getCostSummary converts FFI record to CostSummary model`() =
        runTest {
            val ffiSummary =
                FfiCostSummary(
                    sessionCostUsd = 1.25,
                    dailyCostUsd = 3.50,
                    monthlyCostUsd = 42.0,
                    totalTokens = 5000UL,
                    requestCount = 10U,
                    modelBreakdownJson = """{"gpt-4": 1.0}""",
                )
            every { com.zeroclaw.ffi.getCostSummary() } returns ffiSummary

            val result = bridge.getCostSummary()

            assertEquals(1.25, result.sessionCostUsd)
            assertEquals(3.50, result.dailyCostUsd)
            assertEquals(42.0, result.monthlyCostUsd)
            assertEquals(5000L, result.totalTokens)
            assertEquals(10, result.requestCount)
            assertEquals("""{"gpt-4": 1.0}""", result.modelBreakdownJson)
        }

    @Test
    @DisplayName("getCostSummary propagates FfiException")
    fun `getCostSummary propagates FfiException`() =
        runTest {
            every {
                com.zeroclaw.ffi.getCostSummary()
            } throws FfiException.StateException("daemon not running")

            assertThrows<FfiException> {
                bridge.getCostSummary()
            }
        }
}
