/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.service.ondevice

import com.zeroclaw.android.model.LiteRtModelCatalog
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("GemmaResponseHarness.appliesTo")
class GemmaResponseHarnessAppliesToTest {
    @Test
    fun `applies to every gemma-4 catalog variant`() {
        LiteRtModelCatalog.all.forEach { model ->
            assertTrue(
                GemmaResponseHarness.appliesTo(model.id),
                "Harness must apply to ${model.id}",
            )
        }
    }

    @Test
    fun `applies to both E2B and E4B ids explicitly`() {
        assertTrue(GemmaResponseHarness.appliesTo("gemma-4-e2b-it"))
        assertTrue(GemmaResponseHarness.appliesTo("gemma-4-e4b-it"))
    }

    @Test
    fun `does not apply to non-gemma models`() {
        assertFalse(GemmaResponseHarness.appliesTo("phi-4-mini"))
        assertFalse(GemmaResponseHarness.appliesTo("qwen-2.5"))
        assertFalse(GemmaResponseHarness.appliesTo(""))
    }
}
