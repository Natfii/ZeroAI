/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data.remote

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ConnectionProber")
class ConnectionProberTest {
    @Test
    @DisplayName("allows loopback and private-network cleartext probe hosts")
    fun `allows local cleartext probe hosts`() {
        assertTrue(ConnectionProber.isPermittedCleartextProbeHost("localhost"))
        assertTrue(ConnectionProber.isPermittedCleartextProbeHost("127.0.0.1"))
        assertTrue(ConnectionProber.isPermittedCleartextProbeHost("10.0.0.25"))
        assertTrue(ConnectionProber.isPermittedCleartextProbeHost("192.168.1.50"))
    }

    @Test
    @DisplayName("rejects public cleartext probe hosts")
    fun `rejects public cleartext probe hosts`() {
        assertFalse(ConnectionProber.isPermittedCleartextProbeHost("example.com"))
        assertFalse(ConnectionProber.isPermittedCleartextProbeHost("8.8.8.8"))
    }
}
