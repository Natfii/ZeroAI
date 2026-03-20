/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProviderSlotRegistryTest {
    @Test
    fun returnsAllSixSlotsInStableOrder() {
        val slots = ProviderSlotRegistry.all()
        assertEquals(7, slots.size)
        assertEquals("gemini-api", slots.first().slotId)
        assertEquals("ollama", slots.last().slotId)
    }

    @Test
    fun resolvesOauthAndApiVariants() {
        assertEquals("gemini-api", ProviderSlotRegistry.resolveSlotId("google-gemini", false))
        assertNull(ProviderSlotRegistry.resolveSlotId("google-gemini", true))
        assertEquals("openai-api", ProviderSlotRegistry.resolveSlotId("openai", false))
        assertEquals("chatgpt", ProviderSlotRegistry.resolveSlotId("openai", true))
        assertEquals("anthropic-api", ProviderSlotRegistry.resolveSlotId("anthropic", false))
        assertNull(ProviderSlotRegistry.resolveSlotId("anthropic", true))
        assertEquals("ollama", ProviderSlotRegistry.resolveSlotId("ollama", false))
    }

    @Test
    fun resolvesOpenrouterSlot() {
        assertEquals("openrouter-api", ProviderSlotRegistry.resolveSlotId("openrouter", false))
        assertNull(ProviderSlotRegistry.resolveSlotId("openrouter", true))
    }

    @Test
    fun findsKnownSlotAndRejectsUnknown() {
        val slot = ProviderSlotRegistry.findById("gemini-api")
        assertNotNull(slot)
        assertEquals("Gemini API", slot?.displayName)
        assertNull(ProviderSlotRegistry.findById("gemini-oauth"))
        assertNull(ProviderSlotRegistry.findById("missing-slot"))
    }

    @Test
    fun resolvesXaiSlot() {
        assertEquals("xai-api", ProviderSlotRegistry.resolveSlotId("xai", false))
        assertNull(ProviderSlotRegistry.resolveSlotId("xai", true))
    }

    @Test
    fun providerRegistryMappingsAreResolvable() {
        assertTrue(ProviderSlotRegistry.all().all { ProviderRegistry.findById(it.providerRegistryId) != null })
    }
}
