/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConfigTomlBuilderPeersTest {
    @Test
    fun `empty list produces empty string`() {
        val result = ConfigTomlBuilder.buildTailscalePeersToml(emptyList())
        assertEquals("", result)
    }

    @Test
    fun `single peer emits correct TOML`() {
        val peers =
            listOf(
                PeerTomlEntry(
                    "100.10.0.5",
                    "homeserver",
                    "zeroclaw",
                    42617,
                    "homeserver",
                    false,
                    true,
                ),
            )
        val result = ConfigTomlBuilder.buildTailscalePeersToml(peers)
        assertTrue(result.contains("[[tailscale_peers.entries]]"))
        assertTrue(result.contains("ip = \"100.10.0.5\""))
        assertTrue(result.contains("kind = \"zeroclaw\""))
        assertTrue(result.contains("port = 42617"))
        assertTrue(result.contains("auth_required = false"))
        assertTrue(result.contains("enabled = true"))
        assertFalse(result.contains("[tailscale_peers]\n"))
    }

    @Test
    fun `two peers emit two table headers`() {
        val peers =
            listOf(
                PeerTomlEntry(
                    "100.10.0.5",
                    "home",
                    "zeroclaw",
                    42617,
                    "home",
                    false,
                    true,
                ),
                PeerTomlEntry(
                    "100.10.0.12",
                    "work",
                    "openclaw",
                    18789,
                    "work",
                    true,
                    true,
                ),
            )
        val result = ConfigTomlBuilder.buildTailscalePeersToml(peers)
        val count = Regex("\\[\\[tailscale_peers\\.entries]]").findAll(result).count()
        assertEquals(2, count)
    }

    @Test
    fun `invalid port rejected by isValid`() {
        val peer =
            PeerTomlEntry(
                "100.10.0.5",
                "home",
                "zeroclaw",
                -1,
                "home",
                false,
                true,
            )
        assertFalse(peer.isValid())
    }

    @Test
    fun `serializer throws when given invalid peer`() {
        val peer =
            PeerTomlEntry(
                "",
                "home",
                "zeroclaw",
                42617,
                "home",
                false,
                true,
            )
        assertThrows(IllegalArgumentException::class.java) {
            ConfigTomlBuilder.buildTailscalePeersToml(listOf(peer))
        }
    }

    @Test
    fun `isValid catches blank ip hostname and kind`() {
        assertFalse(PeerTomlEntry("", "h", "k", 80, "a", false, true).isValid())
        assertFalse(PeerTomlEntry("ip", "", "k", 80, "a", false, true).isValid())
        assertFalse(PeerTomlEntry("ip", "h", "", 80, "a", false, true).isValid())
        assertFalse(PeerTomlEntry("ip", "h", "k", 0, "a", false, true).isValid())
        assertFalse(PeerTomlEntry("ip", "h", "k", 65536, "a", false, true).isValid())
        assertTrue(PeerTomlEntry("ip", "h", "k", 1, "a", false, true).isValid())
    }
}
