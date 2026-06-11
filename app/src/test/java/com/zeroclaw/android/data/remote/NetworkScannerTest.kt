/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data.remote

import com.zeroclaw.android.model.AppSettings
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for [NetworkScanner] pure helper methods.
 *
 * Tests cover [NetworkScanner.readCapped], [NetworkScanner.rawHttpGet],
 * [NetworkScanner.decodeChunked], [NetworkScanner.deriveScanSubnets],
 * and [NetworkScanner.tailscalePeerIps]. The [rawHttpGet] tests use a
 * local [ServerSocket] on a random port to serve canned HTTP responses.
 *
 * The subnet-derivation and peer-extraction groups are fresh-install
 * regression tests: they pin down what the scanner probes given default
 * settings and VPN-shaped network addresses, the exact circumstances
 * that once left a first-run user unable to discover an LM Studio
 * server reachable only through Tailscale.
 */
@DisplayName("NetworkScanner")
class NetworkScannerTest {
    @Nested
    @DisplayName("readCapped")
    inner class ReadCappedTests {
        @Test
        @DisplayName("reads data under the limit")
        fun `reads data under the limit`() {
            val data = "Hello, world!".toByteArray()
            val input = ByteArrayInputStream(data)

            val result = NetworkScanner.readCapped(input, 1024)
            assertArrayEquals(data, result)
        }

        @Test
        @DisplayName("reads data exactly at the limit")
        fun `reads data exactly at the limit`() {
            val data = ByteArray(256) { it.toByte() }
            val input = ByteArrayInputStream(data)

            val result = NetworkScanner.readCapped(input, 256)
            assertArrayEquals(data, result)
        }

        @Test
        @DisplayName("throws IOException when stream exceeds limit")
        fun `throws IOException when stream exceeds limit`() {
            val data = ByteArray(101) { it.toByte() }
            val input = ByteArrayInputStream(data)

            val exception =
                assertThrows(IOException::class.java) {
                    NetworkScanner.readCapped(input, 100)
                }
            assertTrue(exception.message!!.contains("exceeds"))
        }

        @Test
        @DisplayName("handles empty stream")
        fun `handles empty stream`() {
            val input = ByteArrayInputStream(ByteArray(0))

            val result = NetworkScanner.readCapped(input, 1024)
            assertEquals(0, result.size)
        }

        @Test
        @DisplayName("reads data larger than one chunk size")
        fun `reads data larger than one chunk size`() {
            val size = 16384
            val data = ByteArray(size) { (it % 256).toByte() }
            val input = ByteArrayInputStream(data)

            val result = NetworkScanner.readCapped(input, size)
            assertArrayEquals(data, result)
        }
    }

    @Nested
    @DisplayName("rawHttpGet")
    inner class RawHttpGetTests {
        /**
         * Starts a local [ServerSocket] on a random port, accepts one
         * connection, writes [response], and closes the socket.
         *
         * Drains the incoming HTTP request headers by reading raw bytes
         * until the `\r\n\r\n` terminator is found, then writes the
         * canned response. A [CountDownLatch] ensures the server is
         * listening before the test client connects.
         *
         * @param response Raw HTTP response to send.
         * @return The port the server is listening on.
         */
        @Suppress(
            "CognitiveComplexMethod",
            "ComplexCondition",
            "LoopWithTooManyJumpStatements",
        )
        private fun serveOnce(response: String): Int {
            val server = ServerSocket(0)
            val port = server.localPort
            val ready = CountDownLatch(1)
            Thread {
                try {
                    server.use { srv ->
                        ready.countDown()
                        srv.accept().use { client ->
                            val input = client.getInputStream()
                            val headerBuf = StringBuilder()
                            while (true) {
                                val b = input.read()
                                if (b == -1) break
                                headerBuf.append(b.toChar())
                                if (headerBuf.length >= 4) {
                                    val len = headerBuf.length
                                    if (headerBuf[len - 4] == '\r' &&
                                        headerBuf[len - 3] == '\n' &&
                                        headerBuf[len - 2] == '\r' &&
                                        headerBuf[len - 1] == '\n'
                                    ) {
                                        break
                                    }
                                }
                            }
                            client.getOutputStream().write(
                                response.toByteArray(Charsets.US_ASCII),
                            )
                            client.getOutputStream().flush()
                        }
                    }
                } catch (_: Exception) {
                    // test server shutdown
                }
            }.start()
            ready.await()
            return port
        }

        @Test
        @DisplayName("parses Content-Length response")
        fun `parses Content-Length response`() {
            val body = """{"status":"ok"}"""
            val httpResponse =
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: ${body.length}\r\n" +
                    "Connection: close\r\n" +
                    "\r\n" +
                    body
            val port = serveOnce(httpResponse)

            val result = NetworkScanner.rawHttpGet("127.0.0.1", port, "/test")
            assertEquals(body, result)
        }

        @Test
        @DisplayName("parses Connection-close response without Content-Length")
        fun `parses Connection-close response without Content-Length`() {
            val body = """{"models":[]}"""
            val httpResponse =
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Connection: close\r\n" +
                    "\r\n" +
                    body
            val port = serveOnce(httpResponse)

            val result = NetworkScanner.rawHttpGet("127.0.0.1", port, "/api/tags")
            assertEquals(body, result)
        }

        @Test
        @DisplayName("rejects non-200 status code")
        fun `rejects non-200 status code`() {
            val httpResponse =
                "HTTP/1.1 404 Not Found\r\n" +
                    "Content-Length: 0\r\n" +
                    "Connection: close\r\n" +
                    "\r\n"
            val port = serveOnce(httpResponse)

            val exception =
                assertThrows(IOException::class.java) {
                    NetworkScanner.rawHttpGet("127.0.0.1", port, "/missing")
                }
            assertTrue(exception.message!!.contains("404"))
        }

        @Test
        @DisplayName("rejects malformed response without header terminator")
        fun `rejects malformed response without header terminator`() {
            val httpResponse = "garbage data with no CRLFCRLF"
            val port = serveOnce(httpResponse)

            val exception =
                assertThrows(IOException::class.java) {
                    NetworkScanner.rawHttpGet("127.0.0.1", port, "/bad")
                }
            assertTrue(exception.message!!.contains("Malformed"))
        }

        @Test
        @DisplayName("rejects 500 Internal Server Error")
        fun `rejects 500 Internal Server Error`() {
            val httpResponse =
                "HTTP/1.1 500 Internal Server Error\r\n" +
                    "Connection: close\r\n" +
                    "\r\n" +
                    "error"
            val port = serveOnce(httpResponse)

            val exception =
                assertThrows(IOException::class.java) {
                    NetworkScanner.rawHttpGet("127.0.0.1", port, "/error")
                }
            assertTrue(exception.message!!.contains("500"))
        }

        @Test
        @DisplayName("decodes chunked transfer encoding via rawHttpGet")
        fun `decodes chunked transfer encoding via rawHttpGet`() {
            val chunk1 = "Hello"
            val chunk2 = ", world!"
            val httpResponse =
                "HTTP/1.1 200 OK\r\n" +
                    "Transfer-Encoding: chunked\r\n" +
                    "Connection: close\r\n" +
                    "\r\n" +
                    "${Integer.toHexString(chunk1.length)}\r\n" +
                    "$chunk1\r\n" +
                    "${Integer.toHexString(chunk2.length)}\r\n" +
                    "$chunk2\r\n" +
                    "0\r\n" +
                    "\r\n"
            val port = serveOnce(httpResponse)

            val result = NetworkScanner.rawHttpGet("127.0.0.1", port, "/chunked")
            assertEquals("Hello, world!", result)
        }
    }

    @Nested
    @DisplayName("deriveScanSubnets")
    inner class DeriveScanSubnetsTests {
        @Test
        @DisplayName("maps a LAN address to its 24-bit prefix")
        fun `maps a LAN address to its 24-bit prefix`() {
            val result = NetworkScanner.deriveScanSubnets(listOf("192.168.1.42"))
            assertEquals(listOf("192.168.1"), result)
        }

        @Test
        @DisplayName("deduplicates addresses in the same subnet")
        fun `deduplicates addresses in the same subnet`() {
            val result =
                NetworkScanner.deriveScanSubnets(listOf("192.168.1.5", "192.168.1.99"))
            assertEquals(listOf("192.168.1"), result)
        }

        @Test
        @DisplayName("keeps distinct subnets in input order")
        fun `keeps distinct subnets in input order`() {
            val result =
                NetworkScanner.deriveScanSubnets(listOf("192.168.1.5", "10.0.0.7"))
            assertEquals(listOf("192.168.1", "10.0.0"), result)
        }

        @Test
        @DisplayName("scans the WiFi LAN even when a Tailscale VPN address is present")
        fun `scans the WiFi LAN even when a Tailscale VPN address is present`() {
            val result =
                NetworkScanner.deriveScanSubnets(listOf("100.86.12.9", "192.168.1.42"))
            assertEquals(listOf("192.168.1"), result)
        }

        @Test
        @DisplayName("excludes CGNAT addresses across the whole 100.64.0.0-10 range")
        fun `excludes CGNAT addresses across the whole range`() {
            val result =
                NetworkScanner.deriveScanSubnets(
                    listOf("100.64.0.1", "100.106.201.33", "100.127.255.254"),
                )
            assertEquals(emptyList<String>(), result)
        }

        @Test
        @DisplayName("keeps public addresses adjacent to the CGNAT range")
        fun `keeps public addresses adjacent to the CGNAT range`() {
            val result =
                NetworkScanner.deriveScanSubnets(listOf("100.63.255.1", "100.128.0.1"))
            assertEquals(listOf("100.63.255", "100.128.0"), result)
        }

        @Test
        @DisplayName("excludes loopback addresses")
        fun `excludes loopback addresses`() {
            val result = NetworkScanner.deriveScanSubnets(listOf("127.0.0.1"))
            assertEquals(emptyList<String>(), result)
        }

        @Test
        @DisplayName("excludes link-local addresses")
        fun `excludes link-local addresses`() {
            val result = NetworkScanner.deriveScanSubnets(listOf("169.254.13.37"))
            assertEquals(emptyList<String>(), result)
        }

        @Test
        @DisplayName("drops malformed and IPv6 addresses")
        fun `drops malformed and IPv6 addresses`() {
            val result =
                NetworkScanner.deriveScanSubnets(
                    listOf("fe80::1", "not an ip", "1.2.3", "1.2.3.4.5", "256.1.1.1", ""),
                )
            assertEquals(emptyList<String>(), result)
        }

        @Test
        @DisplayName("returns empty for empty input")
        fun `returns empty for empty input`() {
            assertEquals(emptyList<String>(), NetworkScanner.deriveScanSubnets(emptyList()))
        }
    }

    @Nested
    @DisplayName("tailscalePeerIps")
    inner class TailscalePeerIpsTests {
        /** A valid serialized discovery cache holding a single PC peer. */
        private val cachedPcPeer =
            """[{"hostname":"natal-pc","ip":"100.67.109.56","isManual":false}]"""

        @Test
        @DisplayName("fresh-install defaults probe no tailnet peers")
        fun `fresh-install defaults probe no tailnet peers`() {
            assertEquals(emptyList<String>(), NetworkScanner.tailscalePeerIps(AppSettings()))
        }

        @Test
        @DisplayName("ignores cached peers while awareness is disabled")
        fun `ignores cached peers while awareness is disabled`() {
            val settings =
                AppSettings(
                    tailscaleAwarenessEnabled = false,
                    tailscaleCachedDiscovery = cachedPcPeer,
                )
            assertEquals(emptyList<String>(), NetworkScanner.tailscalePeerIps(settings))
        }

        @Test
        @DisplayName("returns cached discovery peers when awareness is enabled")
        fun `returns cached discovery peers when awareness is enabled`() {
            val settings =
                AppSettings(
                    tailscaleAwarenessEnabled = true,
                    tailscaleCachedDiscovery = cachedPcPeer,
                )
            assertEquals(listOf("100.67.109.56"), NetworkScanner.tailscalePeerIps(settings))
        }

        @Test
        @DisplayName("merges manual peers with cached discovery and deduplicates")
        fun `merges manual peers with cached discovery and deduplicates`() {
            val settings =
                AppSettings(
                    tailscaleAwarenessEnabled = true,
                    tailscaleCachedDiscovery = cachedPcPeer,
                    tailscaleManualPeers = """["100.67.109.56","100.70.1.2"]""",
                )
            assertEquals(
                listOf("100.67.109.56", "100.70.1.2"),
                NetworkScanner.tailscalePeerIps(settings),
            )
        }

        @Test
        @DisplayName("skips a corrupt discovery cache without failing")
        fun `skips a corrupt discovery cache without failing`() {
            val settings =
                AppSettings(
                    tailscaleAwarenessEnabled = true,
                    tailscaleCachedDiscovery = "{not json",
                    tailscaleManualPeers = """["100.70.1.2"]""",
                )
            assertEquals(listOf("100.70.1.2"), NetworkScanner.tailscalePeerIps(settings))
        }

        @Test
        @DisplayName("skips corrupt manual peers without failing")
        fun `skips corrupt manual peers without failing`() {
            val settings =
                AppSettings(
                    tailscaleAwarenessEnabled = true,
                    tailscaleCachedDiscovery = cachedPcPeer,
                    tailscaleManualPeers = "][",
                )
            assertEquals(listOf("100.67.109.56"), NetworkScanner.tailscalePeerIps(settings))
        }
    }

    @Nested
    @DisplayName("decodeChunked")
    inner class DecodeChunkedTests {
        @Test
        @DisplayName("decodes single chunk")
        fun `decodes single chunk`() {
            val body = "d\r\nHello, world!\r\n0\r\n\r\n"
            val result = NetworkScanner.decodeChunked(body)
            assertEquals("Hello, world!", result)
        }

        @Test
        @DisplayName("decodes multiple chunks")
        fun `decodes multiple chunks`() {
            val body = "5\r\nHello\r\n7\r\n, world\r\n1\r\n!\r\n0\r\n\r\n"
            val result = NetworkScanner.decodeChunked(body)
            assertEquals("Hello, world!", result)
        }

        @Test
        @DisplayName("decodes empty chunked body")
        fun `decodes empty chunked body`() {
            val body = "0\r\n\r\n"
            val result = NetworkScanner.decodeChunked(body)
            assertEquals("", result)
        }

        @Test
        @DisplayName("handles uppercase hex chunk sizes")
        fun `handles uppercase hex chunk sizes`() {
            val data = "A".repeat(255)
            val body = "FF\r\n$data\r\n0\r\n\r\n"
            val result = NetworkScanner.decodeChunked(body)
            assertEquals(data, result)
        }

        @Test
        @DisplayName("handles lowercase hex chunk sizes")
        fun `handles lowercase hex chunk sizes`() {
            val data = "B".repeat(10)
            val body = "a\r\n${data}\r\n0\r\n\r\n"
            val result = NetworkScanner.decodeChunked(body)
            assertEquals(data, result)
        }

        @Test
        @DisplayName("returns empty string for empty input")
        fun `returns empty string for empty input`() {
            val result = NetworkScanner.decodeChunked("")
            assertEquals("", result)
        }
    }
}
