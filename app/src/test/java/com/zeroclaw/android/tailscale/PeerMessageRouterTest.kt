/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.tailscale

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PeerMessageRouterTest {
    private val peers =
        listOf(
            PeerRouteEntry("homeserver", "100.10.0.5", 42617, "zeroclaw"),
            PeerRouteEntry("workpc", "100.10.0.12", 18789, "openclaw"),
        )

    @Test
    fun `matches prefix with space`() {
        val result = PeerMessageRouter.matchAlias("@homeserver what is disk usage?", peers)
        assertNotNull(result)
        assertEquals("homeserver", result!!.alias)
        assertEquals("what is disk usage?", result.strippedMessage)
    }

    @Test
    fun `matches prefix at end of string`() {
        val result = PeerMessageRouter.matchAlias("@homeserver", peers)
        assertNotNull(result)
        assertEquals("homeserver", result!!.alias)
        assertEquals("", result.strippedMessage)
    }

    @Test
    fun `matching is case insensitive`() {
        val result = PeerMessageRouter.matchAlias("@HomeServer hello", peers)
        assertNotNull(result)
        assertEquals("homeserver", result!!.alias)
    }

    @Test
    fun `no match returns null`() {
        val result = PeerMessageRouter.matchAlias("hello world", peers)
        assertNull(result)
    }

    @Test
    fun `mid-message mention is ignored`() {
        val result = PeerMessageRouter.matchAlias("ask @homeserver about weather", peers)
        assertNull(result)
    }

    @Test
    fun `partial alias does not match`() {
        val result = PeerMessageRouter.matchAlias("@home something", peers)
        assertNull(result)
    }

    @Test
    fun `alias validation rejects special characters`() {
        assertFalse(PeerMessageRouter.isValidAlias("home server"))
        assertFalse(PeerMessageRouter.isValidAlias("home@server"))
        assertFalse(PeerMessageRouter.isValidAlias("/homeserver"))
        assertFalse(PeerMessageRouter.isValidAlias(""))
    }

    @Test
    fun `alias validation accepts valid names`() {
        assertTrue(PeerMessageRouter.isValidAlias("homeserver"))
        assertTrue(PeerMessageRouter.isValidAlias("work-pc"))
        assertTrue(PeerMessageRouter.isValidAlias("server_2"))
        assertTrue(PeerMessageRouter.isValidAlias("a"))
    }

    @Test
    fun `alias validation enforces max length`() {
        assertFalse(PeerMessageRouter.isValidAlias("a".repeat(33)))
        assertTrue(PeerMessageRouter.isValidAlias("a".repeat(32)))
    }

    @Test
    fun `conflict resolution appends suffix`() {
        val input = listOf("server", "server", "server")
        val resolved = PeerMessageRouter.resolveAliasConflicts(input)
        assertEquals(listOf("server", "server_2", "server_3"), resolved)
    }
}
