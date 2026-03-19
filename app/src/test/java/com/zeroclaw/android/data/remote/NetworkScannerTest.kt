/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data.remote

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
 * Unit tests for [NetworkScanner] raw HTTP helper methods.
 *
 * Tests cover [NetworkScanner.readCapped], [NetworkScanner.rawHttpGet],
 * and [NetworkScanner.decodeChunked]. The [rawHttpGet] tests use a
 * local [ServerSocket] on a random port to serve canned HTTP responses.
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
