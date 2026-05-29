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
    fun returnsAllSlotsInLocalFirstOrder() {
        val slots = ProviderSlotRegistry.all()
        // 6 slots after 2026-05-25 drop of deepseek/qwen/xai.
        assertEquals(6, slots.size)
        // Ollama leads (local-first ordering).
        assertEquals("ollama", slots.first().slotId)
        assertEquals("openrouter-api", slots.last().slotId)
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
        val slot = ProviderSlotRegistry.findById("ollama")
        assertNotNull(slot)
        assertEquals("Local", slot?.displayName)
        assertNull(ProviderSlotRegistry.findById("gemini-oauth"))
        assertNull(ProviderSlotRegistry.findById("missing-slot"))
        // Dropped provider IDs no longer resolve.
        assertNull(ProviderSlotRegistry.findById("xai-api"))
        assertNull(ProviderSlotRegistry.findById("deepseek-api"))
        assertNull(ProviderSlotRegistry.findById("qwen-api"))
    }

    /**
     * Sentinel test for UI hole #7: every `providerRegistryId` referenced
     * by a slot must resolve in [ProviderRegistry]. The init-time `check`
     * inside [ProviderSlotRegistry] also enforces this, but a failing
     * unit test surfaces the break at PR-review time instead of at app
     * launch — preventing the class of bug that crashed the app when
     * deepseek/qwen/xai tiles were dropped without updating the slots.
     */
    @Test
    fun everySlotResolvesInProviderRegistry() {
        val unresolved =
            ProviderSlotRegistry
                .all()
                .filter { ProviderRegistry.findById(it.providerRegistryId) == null }
                .map { it.slotId }
        assertTrue(
            unresolved.isEmpty(),
            "ProviderSlotRegistry references unknown ProviderRegistry IDs: $unresolved",
        )
    }
}
