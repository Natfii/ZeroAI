/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data.remote

import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lightweight reachability probe for local AI server base URLs.
 *
 * Sends a HEAD request (falling back to GET) to the `/models` or root
 * endpoint to determine whether the server at [baseUrl] is responsive.
 * Used to detect stale connections to servers that have gone offline.
 *
 * HTTP URLs are only probed for loopback, link-local, or private-network
 * hosts. Those probes use a raw TCP socket to bypass Android's cleartext
 * traffic policy, which blocks [HttpURLConnection] for non-HTTPS schemes
 * when `cleartextTrafficPermitted` is false. HTTPS URLs continue to use
 * [HttpURLConnection] normally.
 */
object ConnectionProber {
    /** Connect timeout for probe requests. */
    private const val PROBE_CONNECT_TIMEOUT_MS = 2000

    /** Read timeout for probe requests. */
    private const val PROBE_READ_TIMEOUT_MS = 2000

    /** HTTP status codes considered successful for a probe. */
    private const val MAX_SUCCESS_CODE = 399

    /** Default HTTP port. */
    private const val DEFAULT_HTTP_PORT = 80

    /** Maximum characters read from the status line. */
    private const val MAX_STATUS_LINE_LENGTH = 256

    /**
     * Probes whether a base URL is reachable.
     *
     * Attempts a HEAD request to the base URL. Returns true if the server
     * responds with any 2xx or 3xx status code within the timeout window.
     *
     * HTTP URLs only use a raw TCP socket for loopback, link-local, or
     * private-network hosts. Remote non-HTTPS URLs are treated as unreachable
     * so the app does not silently bypass its own cleartext policy.
     * HTTPS URLs use [HttpURLConnection].
     *
     * Safe to call from the main thread; dispatches to [Dispatchers.IO].
     *
     * @param baseUrl The provider base URL to probe (e.g. "http://192.168.1.50:1234/v1").
     * @return True if the server responded within the timeout, false otherwise.
     */
    @Suppress("TooGenericExceptionCaught", "InjectDispatcher")
    suspend fun isReachable(baseUrl: String): Boolean =
        withContext(Dispatchers.IO) {
            if (baseUrl.isBlank()) return@withContext false
            try {
                val url = URL(baseUrl)
                if (url.protocol.equals("http", ignoreCase = true)) {
                    if (isPermittedCleartextProbeHost(url.host)) {
                        probeRawSocket(url)
                    } else {
                        false
                    }
                } else {
                    probeHttpUrlConnection(url)
                }
            } catch (_: Exception) {
                false
            }
        }

    /**
     * Returns whether an HTTP probe is allowed for [host].
     *
     * Only loopback, link-local, and RFC1918/private-network targets are
     * permitted. Public hosts must use HTTPS.
     */
    internal fun isPermittedCleartextProbeHost(host: String): Boolean =
        try {
            val address = InetAddress.getByName(host)
            address.isAnyLocalAddress ||
                address.isLoopbackAddress ||
                address.isSiteLocalAddress ||
                address.isLinkLocalAddress
        } catch (_: Exception) {
            false
        }

    /**
     * Probes an HTTP URL using a raw TCP socket.
     *
     * Bypasses Android's cleartext traffic policy by using [Socket]
     * directly, which is not subject to the policy's best-effort
     * enforcement.
     *
     * @param url The parsed HTTP URL to probe.
     * @return True if the server responds with a 2xx or 3xx status code.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun probeRawSocket(url: URL): Boolean =
        try {
            val host = url.host
            val port = if (url.port == -1) DEFAULT_HTTP_PORT else url.port
            val path = url.path.ifEmpty { "/" }

            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), PROBE_CONNECT_TIMEOUT_MS)
                socket.soTimeout = PROBE_READ_TIMEOUT_MS

                val writer = socket.getOutputStream().bufferedWriter(Charsets.US_ASCII)
                writer.write("HEAD $path HTTP/1.1\r\n")
                writer.write("Host: $host:$port\r\n")
                writer.write("Connection: close\r\n")
                writer.write("\r\n")
                writer.flush()

                val statusLine = readStatusLine(socket.getInputStream())
                parseStatusCode(statusLine) in 1..MAX_SUCCESS_CODE
            }
        } catch (e: Exception) {
            false
        }

    /**
     * Reads the first line of an HTTP response (the status line).
     *
     * Reads byte-by-byte until `\r\n` is found or [MAX_STATUS_LINE_LENGTH]
     * bytes are read. This avoids buffering the entire response when only
     * the status code is needed.
     *
     * @param input The socket input stream.
     * @return The status line as a string.
     */
    @Suppress("LoopWithTooManyJumpStatements")
    private fun readStatusLine(input: java.io.InputStream): String {
        val buf = StringBuilder()
        while (buf.length < MAX_STATUS_LINE_LENGTH) {
            val b = input.read()
            if (b == -1) break
            val c = b.toChar()
            if (c == '\n' && buf.isNotEmpty() && buf[buf.length - 1] == '\r') {
                buf.setLength(buf.length - 1)
                break
            }
            buf.append(c)
        }
        return buf.toString()
    }

    /**
     * Extracts the numeric status code from an HTTP status line.
     *
     * Parses the second space-delimited token from a standard
     * `HTTP/1.x <code> <reason>` status line.
     *
     * @param statusLine The raw HTTP status line.
     * @return The status code, or -1 if parsing fails.
     */
    private fun parseStatusCode(statusLine: String): Int {
        val parts = statusLine.split(" ", limit = STATUS_LINE_PARTS)
        return if (parts.size >= STATUS_LINE_PARTS) {
            parts[1].toIntOrNull() ?: -1
        } else {
            -1
        }
    }

    /**
     * Probes an HTTPS URL using [HttpURLConnection].
     *
     * HTTPS traffic is always permitted by Android's network security
     * config, so [HttpURLConnection] works without restriction.
     *
     * @param url The parsed HTTPS URL to probe.
     * @return True if the server responds with a 2xx or 3xx status code.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun probeHttpUrlConnection(url: URL): Boolean =
        try {
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "HEAD"
            connection.connectTimeout = PROBE_CONNECT_TIMEOUT_MS
            connection.readTimeout = PROBE_READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            try {
                connection.connect()
                connection.responseCode in 1..MAX_SUCCESS_CODE
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            false
        }

    /** Number of space-delimited parts expected in an HTTP status line. */
    private const val STATUS_LINE_PARTS = 3
}
