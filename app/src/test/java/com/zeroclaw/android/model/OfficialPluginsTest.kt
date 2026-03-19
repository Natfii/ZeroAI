package com.zeroclaw.android.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("OfficialPlugins")
class OfficialPluginsTest {
    @Test
    fun `ALL contains exactly 8 official plugin IDs`() {
        assertEquals(8, OfficialPlugins.ALL.size)
    }

    @Test
    fun `isOfficial returns true for all official IDs`() {
        OfficialPlugins.ALL.forEach { id ->
            assertTrue(OfficialPlugins.isOfficial(id), "Expected $id to be official")
        }
    }

    @Test
    fun `isOfficial returns false for community plugin IDs`() {
        assertFalse(OfficialPlugins.isOfficial("plugin-http-channel"))
        assertFalse(OfficialPlugins.isOfficial("plugin-mqtt-channel"))
        assertFalse(OfficialPlugins.isOfficial("some-random-id"))
    }

    @Test
    fun `isOfficial returns false for empty and blank strings`() {
        assertFalse(OfficialPlugins.isOfficial(""))
        assertFalse(OfficialPlugins.isOfficial("   "))
    }

    @Test
    fun `each plugin ID constant is present in ALL`() {
        val expectedIds =
            listOf(
                OfficialPlugins.WEB_SEARCH,
                OfficialPlugins.WEB_FETCH,
                OfficialPlugins.HTTP_REQUEST,
                OfficialPlugins.SHARED_FOLDER,
                OfficialPlugins.COMPOSIO,
                OfficialPlugins.VISION,
                OfficialPlugins.TRANSCRIPTION,
                OfficialPlugins.QUERY_CLASSIFICATION,
            )
        for (id in expectedIds) {
            assertTrue(id in OfficialPlugins.ALL, "Expected $id in ALL set")
        }
        assertEquals(expectedIds.size, OfficialPlugins.ALL.size)
    }

    @Test
    fun `ALL set size matches sync mapping count`() {
        val syncMappingCount = 8
        assertEquals(syncMappingCount, OfficialPlugins.ALL.size)
    }
}
