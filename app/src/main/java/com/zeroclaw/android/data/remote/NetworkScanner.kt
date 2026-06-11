/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import com.zeroclaw.android.model.AppSettings
import com.zeroclaw.android.model.DiscoveredServer
import com.zeroclaw.android.model.LocalServerType
import com.zeroclaw.android.model.ScanState
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject

/**
 * Scans the local network for AI inference servers.
 *
 * Probes common AI server ports across the local /24 subnet using TCP
 * connection attempts, then identifies server types via HTTP probes and
 * optionally polls loaded models. Results are emitted as [ScanState]
 * updates via a [Flow].
 *
 * The scan is battery-conscious: it limits concurrent connections with
 * a [Semaphore], uses short timeouts, and runs entirely on [Dispatchers.IO].
 */
@Suppress("TooManyFunctions")
object NetworkScanner {
    /** Common AI server ports to probe, ordered by popularity. */
    private val TARGET_PORTS =
        intArrayOf(
            PORT_OLLAMA,
            PORT_LM_STUDIO,
            PORT_VLLM,
            PORT_LOCALAI,
            PORT_ZEROCLAW,
            PORT_OPENCLAW,
            PORT_HERMES,
        )

    /** Maximum concurrent TCP connection attempts. */
    private const val MAX_CONCURRENT = 64

    /** TCP connection timeout per host:port in milliseconds. */
    private const val CONNECT_TIMEOUT_MS = 400

    /** HTTP read timeout for identification probes in milliseconds. */
    private const val HTTP_TIMEOUT_MS = 3000

    /** Maximum HTTP response body size in bytes (1 MB). */
    private const val MAX_RESPONSE_BYTES = 1_048_576

    /** Total number of hosts in a /24 subnet (excluding network and broadcast). */
    private const val SUBNET_HOST_COUNT = 254

    /**
     * Scans the local network and emits [ScanState] updates.
     *
     * The flow emits [ScanState.Scanning] with progress updates, then
     * [ScanState.Completed] with the list of all discovered servers, or
     * [ScanState.Error] if the scan cannot start (e.g. no local network).
     *
     * Every connected WiFi or Ethernet network is scanned, not just the
     * active one: when a VPN such as Tailscale is up it becomes the
     * active network, and scanning only its tunnel address would miss
     * the real LAN entirely (see [deriveScanSubnets]).
     *
     * When Tailscale awareness is enabled and cached peers exist, those
     * peers are also probed for AI servers alongside the local subnets.
     * This allows discovering Ollama instances running on remote
     * machines connected via the tailnet. Results are deduplicated by
     * host and port in case a peer also sits inside a scanned subnet.
     *
     * @param context Application context for accessing [ConnectivityManager].
     * @return A cold [Flow] of scan state updates.
     */
    @Suppress("InjectDispatcher")
    fun scan(context: Context): Flow<ScanState> =
        channelFlow {
            val subnets = getScanSubnets(context)
            val tailscalePeers = getTailscalePeerIps(context)

            if (subnets.isEmpty() && tailscalePeers.isEmpty()) {
                send(ScanState.Error("Not connected to a local network"))
                return@channelFlow
            }

            send(ScanState.Scanning(0f))

            val subnetProbeCount = subnets.size * SUBNET_HOST_COUNT * TARGET_PORTS.size
            val tailscaleProbeCount = tailscalePeers.size * TARGET_PORTS.size
            val totalProbes = subnetProbeCount + tailscaleProbeCount
            val completed = AtomicInteger(0)

            val servers =
                coroutineScope {
                    val semaphore = Semaphore(MAX_CONCURRENT)
                    val jobs =
                        buildList {
                            for (subnet in subnets) {
                                addAll(launchProbeJobs(subnet, semaphore, completed))
                            }
                            addAll(
                                launchTailscaleProbeJobs(tailscalePeers, semaphore, completed),
                            )
                        }

                    val progressJob =
                        async {
                            emitProgress(completed, totalProbes)
                        }

                    val results = jobs.awaitAll().filterNotNull()
                    progressJob.cancel()
                    results
                }

            send(ScanState.Completed(servers.distinctBy { "${it.host}:${it.port}" }))
        }.flowOn(Dispatchers.IO)

    /**
     * Reads cached Tailscale peer IPs from settings when awareness is enabled.
     *
     * @param context Application context for accessing settings.
     * @return List of peer IP addresses to probe, empty if Tailscale is not set up.
     */
    private suspend fun getTailscalePeerIps(context: Context): List<String> {
        val app =
            context.applicationContext as? com.zeroclaw.android.ZeroAIApplication
                ?: return emptyList()
        return tailscalePeerIps(app.settingsRepository.settings.first())
    }

    /**
     * Extracts the Tailscale peer IPs to probe from persisted settings.
     *
     * Returns an empty list unless [AppSettings.tailscaleAwarenessEnabled]
     * is on, which means a fresh install never probes tailnet peers until
     * Tailscale awareness has been set up in Settings. Cached discovery
     * results and manually added peers are merged and deduplicated; a
     * corrupt JSON cache is skipped rather than failing the scan.
     *
     * @param settings Current persisted app settings.
     * @return Distinct peer IPs, empty when awareness is off or nothing is cached.
     */
    @JvmStatic
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    internal fun tailscalePeerIps(settings: AppSettings): List<String> {
        if (!settings.tailscaleAwarenessEnabled) return emptyList()

        val ips = mutableListOf<String>()

        if (settings.tailscaleCachedDiscovery.isNotBlank()) {
            try {
                val peers =
                    kotlinx.serialization.json.Json.decodeFromString<
                        List<com.zeroclaw.android.model.CachedTailscalePeer>,
                    >(settings.tailscaleCachedDiscovery)
                ips.addAll(peers.map { it.ip })
            } catch (_: Exception) {
                // corrupt cache — skip
            }
        }
        if (settings.tailscaleManualPeers.isNotBlank()) {
            try {
                val manual =
                    kotlinx.serialization.json.Json.decodeFromString<
                        List<String>,
                    >(settings.tailscaleManualPeers)
                ips.addAll(manual)
            } catch (_: Exception) {
                // corrupt cache — skip
            }
        }
        return ips.distinct()
    }

    /**
     * Launches parallel probe jobs for Tailscale peer IPs on all target ports.
     *
     * @param peerIps Tailscale peer IP addresses to probe.
     * @param semaphore Shared limiter capping concurrent connection attempts.
     * @param completed Atomic counter incremented after each probe completes.
     * @return List of deferred probe results.
     */
    private suspend fun kotlinx.coroutines.CoroutineScope.launchTailscaleProbeJobs(
        peerIps: List<String>,
        semaphore: Semaphore,
        completed: AtomicInteger,
    ): List<kotlinx.coroutines.Deferred<DiscoveredServer?>> {
        if (peerIps.isEmpty()) return emptyList()
        return buildList {
            for (ip in peerIps) {
                for (port in TARGET_PORTS) {
                    add(
                        async {
                            try {
                                semaphore.withPermit { probeHost(ip, port) }
                            } finally {
                                completed.incrementAndGet()
                            }
                        },
                    )
                }
            }
        }
    }

    /**
     * Launches parallel probe jobs for every host:port combination in the subnet.
     *
     * @param subnet The /24 subnet prefix (e.g. "192.168.1").
     * @param semaphore Shared limiter capping concurrent connection attempts.
     * @param completed Atomic counter incremented after each probe completes.
     * @return List of deferred probe results.
     */
    private suspend fun kotlinx.coroutines.CoroutineScope.launchProbeJobs(
        subnet: String,
        semaphore: Semaphore,
        completed: AtomicInteger,
    ): List<kotlinx.coroutines.Deferred<DiscoveredServer?>> =
        buildList {
            for (host in 1..SUBNET_HOST_COUNT) {
                val ip = "$subnet.$host"
                for (port in TARGET_PORTS) {
                    add(
                        async {
                            try {
                                semaphore.withPermit { probeHost(ip, port) }
                            } finally {
                                completed.incrementAndGet()
                            }
                        },
                    )
                }
            }
        }

    /**
     * Emits scanning progress at regular intervals until all probes complete.
     *
     * @param completed Atomic counter of completed probes.
     * @param total Total number of probes to run.
     */
    private suspend fun ProducerScope<ScanState>.emitProgress(
        completed: AtomicInteger,
        total: Int,
    ) {
        while (completed.get() < total) {
            send(ScanState.Scanning(completed.get().toFloat() / total))
            kotlinx.coroutines.delay(PROGRESS_UPDATE_INTERVAL_MS)
        }
    }

    /**
     * Collects the /24 subnet prefixes worth scanning across all connected networks.
     *
     * The active network alone is not enough: when a VPN such as
     * Tailscale is up, it becomes the active network and its tunnel
     * address would shadow the real WiFi LAN entirely. Every network
     * with a WiFi or Ethernet transport is therefore considered
     * alongside the active network, and [deriveScanSubnets] drops
     * address space that cannot be a scannable LAN.
     *
     * [ConnectivityManager.getAllNetworks] is deprecated in favor of the
     * asynchronous callback API, but it remains the only synchronous way
     * to enumerate currently connected networks for a one-shot scan.
     *
     * @param context Application context for [ConnectivityManager] access.
     * @return Distinct subnet prefixes (e.g. "192.168.1"), empty if none qualify.
     */
    private fun getScanSubnets(context: Context): List<String> {
        val cm =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return emptyList()
        val active = cm.activeNetwork

        @Suppress("DEPRECATION")
        val networks = cm.allNetworks
        val addresses =
            networks
                .filter { it == active || hasLanTransport(cm, it) }
                .mapNotNull { ipv4AddressOf(cm.getLinkProperties(it)) }
        return deriveScanSubnets(addresses)
    }

    /**
     * Reports whether [network] is a LAN-class (WiFi or Ethernet) network.
     *
     * @param cm Connectivity manager used to query capabilities.
     * @param network Network to inspect.
     * @return True for WiFi or Ethernet transports, false otherwise or
     *     when capabilities are unavailable.
     */
    private fun hasLanTransport(
        cm: ConnectivityManager,
        network: Network,
    ): Boolean {
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /**
     * Extracts the first non-loopback IPv4 address from link properties.
     *
     * @param linkProps Link properties of a network, may be null.
     * @return Dotted-quad address string, or null if none present.
     */
    private fun ipv4AddressOf(linkProps: LinkProperties?): String? =
        linkProps
            ?.linkAddresses
            ?.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
            ?.address
            ?.hostAddress

    /**
     * Derives distinct, scannable /24 subnet prefixes from device addresses.
     *
     * Addresses that cannot belong to a scannable LAN are dropped:
     * loopback (127.0.0.0/8), link-local (169.254.0.0/16), and the
     * CGNAT shared address space (100.64.0.0/10, RFC 6598). The CGNAT
     * rule matters in practice: Tailscale assigns tailnet addresses
     * from that range with peers scattered across the whole /10, so
     * scanning the device's own /24 slice finds nothing — tailnet peers
     * are reached via the Tailscale awareness peer list instead.
     * Cellular carriers also assign CGNAT addresses, where a subnet
     * scan is equally meaningless.
     *
     * @param ipv4Addresses Dotted-quad addresses of the device's networks.
     * @return Distinct /24 prefixes (e.g. "192.168.1") in input order.
     */
    @JvmStatic
    internal fun deriveScanSubnets(ipv4Addresses: List<String>): List<String> = ipv4Addresses.mapNotNull(::scannableSubnetPrefix).distinct()

    /**
     * Maps an IPv4 address to its /24 prefix when it can belong to a
     * scannable LAN, per the rules documented on [deriveScanSubnets].
     *
     * @param ip Candidate dotted-quad address.
     * @return Subnet prefix, or null for malformed or non-LAN addresses.
     */
    private fun scannableSubnetPrefix(ip: String): String? {
        val octets = ip.split(".").mapNotNull { it.toIntOrNull() }
        if (octets.size != OCTET_COUNT || octets.any { it !in 0..OCTET_MAX }) return null
        val isLoopback = octets[0] == LOOPBACK_FIRST_OCTET
        val isLinkLocal =
            octets[0] == LINK_LOCAL_FIRST_OCTET && octets[1] == LINK_LOCAL_SECOND_OCTET
        val isCgnat = octets[0] == CGNAT_FIRST_OCTET && octets[1] in CGNAT_SECOND_OCTET_RANGE
        return if (isLoopback || isLinkLocal || isCgnat) {
            null
        } else {
            "${octets[0]}.${octets[1]}.${octets[2]}"
        }
    }

    /**
     * Attempts a TCP connection followed by server identification on success.
     *
     * @param ip Target IP address.
     * @param port Target port.
     * @return A [DiscoveredServer] if an AI server is detected, null otherwise.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun probeHost(
        ip: String,
        port: Int,
    ): DiscoveredServer? {
        if (!isPortOpen(ip, port)) return null
        return identifyServer(ip, port)
    }

    /**
     * Tests whether a TCP port is reachable at the given address.
     *
     * @param ip Target IP address.
     * @param port Target port number.
     * @return True if the connection succeeds within the timeout.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun isPortOpen(
        ip: String,
        port: Int,
    ): Boolean =
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS)
                true
            }
        } catch (e: Exception) {
            false
        }

    /**
     * Identifies the type of AI server and polls loaded models.
     *
     * Tries Ollama's `/api/tags` first (if on the default Ollama port),
     * then falls back to the OpenAI-compatible `/v1/models` endpoint.
     *
     * @param ip Server IP address.
     * @param port Server port number.
     * @return A [DiscoveredServer] with type and models, or null if unidentified.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException", "ReturnCount")
    private fun identifyServer(
        ip: String,
        port: Int,
    ): DiscoveredServer? {
        // Agent-gateway probes first (they fingerprint specific JSON
        // shapes on /health and short-circuit out — no point falling
        // through to OpenAI-compat probes that would 404).
        when (port) {
            PORT_ZEROCLAW -> tryZeroclawGateway(ip, port)?.let { return it }
            PORT_OPENCLAW -> tryOpenclawGateway(ip, port)?.let { return it }
            PORT_HERMES -> tryHermesGateway(ip, port)?.let { return it }
        }

        val ollamaResult = tryOllama(ip, port)
        if (ollamaResult != null) return ollamaResult

        val openaiResult = tryOpenAiCompatible(ip, port)
        if (openaiResult != null) return openaiResult

        return null
    }

    /**
     * Probes for an upstream zeroclaw gateway via `GET /health`. Upstream
     * returns `{status, paired, require_pairing, runtime:{...}}`; the
     * `runtime` key is the structural signal we key off.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun tryZeroclawGateway(
        ip: String,
        port: Int,
    ): DiscoveredServer? =
        try {
            val json = rawHttpGet(ip, port, "/health")
            val root = JSONObject(json)
            if (!root.has("runtime") && !root.has("require_pairing")) {
                null
            } else {
                DiscoveredServer(ip, port, LocalServerType.ZEROCLAW)
            }
        } catch (e: Exception) {
            null
        }

    /**
     * Probes for an OpenClaw gateway via `GET /`. Detection key is a
     * top-level JSON `name` or `title` containing "openclaw" (matches
     * the Rust-side `probe_openclaw`).
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun tryOpenclawGateway(
        ip: String,
        port: Int,
    ): DiscoveredServer? =
        try {
            val json = rawHttpGet(ip, port, "/")
            val root = JSONObject(json)
            val nameHit = root.optString("name").lowercase().contains("openclaw")
            val titleHit = root.optString("title").lowercase().contains("openclaw")
            if (!nameHit && !titleHit) {
                null
            } else {
                DiscoveredServer(ip, port, LocalServerType.OPENCLAW)
            }
        } catch (e: Exception) {
            null
        }

    /**
     * Probes for a Hermes Agent gateway (Nous Research) via `GET /health`.
     * Definitive signal: `platform == "hermes-agent"` in response JSON.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun tryHermesGateway(
        ip: String,
        port: Int,
    ): DiscoveredServer? =
        try {
            val json = rawHttpGet(ip, port, "/health")
            val root = JSONObject(json)
            val platform = root.optString("platform")
            if (!platform.equals("hermes-agent", ignoreCase = true)) {
                null
            } else {
                DiscoveredServer(ip, port, LocalServerType.HERMES)
            }
        } catch (e: Exception) {
            null
        }

    /**
     * Probes for an Ollama server and extracts loaded models.
     *
     * @param ip Server IP address.
     * @param port Server port number.
     * @return A [DiscoveredServer] if Ollama is detected, null otherwise.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun tryOllama(
        ip: String,
        port: Int,
    ): DiscoveredServer? =
        try {
            val json = rawHttpGet(ip, port, "/api/tags")
            val root = JSONObject(json)
            val modelsArray = root.optJSONArray("models") ?: return null
            val models =
                buildList {
                    for (i in 0 until modelsArray.length()) {
                        val name = modelsArray.getJSONObject(i).optString("name", "")
                        if (name.isNotEmpty()) add(name)
                    }
                }
            DiscoveredServer(ip, port, LocalServerType.OLLAMA, models)
        } catch (e: Exception) {
            null
        }

    /**
     * Probes for an OpenAI-compatible server and extracts model IDs.
     *
     * @param ip Server IP address.
     * @param port Server port number.
     * @return A [DiscoveredServer] if an OpenAI-compatible API is detected, null otherwise.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun tryOpenAiCompatible(
        ip: String,
        port: Int,
    ): DiscoveredServer? =
        try {
            val json = rawHttpGet(ip, port, "/v1/models")
            val root = JSONObject(json)
            val dataArray = root.optJSONArray("data") ?: return null
            val models =
                buildList {
                    for (i in 0 until dataArray.length()) {
                        val id = dataArray.getJSONObject(i).optString("id", "")
                        if (id.isNotEmpty()) add(id)
                    }
                }
            DiscoveredServer(ip, port, LocalServerType.OPENAI_COMPATIBLE, models)
        } catch (e: Exception) {
            null
        }

    /**
     * Reads up to [maxBytes] from [input], throwing [java.io.IOException]
     * if the stream contains more data than allowed.
     *
     * Uses a manual read loop instead of [java.io.InputStream.readNBytes]
     * which requires API 33+. This method works on API 1+.
     *
     * @param input Stream to read from.
     * @param maxBytes Maximum allowed bytes.
     * @return The read bytes.
     * @throws java.io.IOException if the stream exceeds [maxBytes].
     */
    @JvmStatic
    @Suppress("LoopWithTooManyJumpStatements")
    internal fun readCapped(
        input: java.io.InputStream,
        maxBytes: Int,
    ): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(READ_CHUNK_SIZE)
        var totalRead = 0
        while (true) {
            val remaining = maxBytes + 1 - totalRead
            if (remaining <= 0) break
            val n = input.read(chunk, 0, minOf(chunk.size, remaining))
            if (n == -1) break
            buffer.write(chunk, 0, n)
            totalRead += n
        }
        if (totalRead > maxBytes) {
            throw java.io.IOException("Response exceeds $maxBytes bytes")
        }
        return buffer.toByteArray()
    }

    /**
     * Performs an HTTP GET using a raw TCP socket, bypassing Android's
     * network security cleartext traffic policy.
     *
     * Sends an HTTP/1.1 request with `Connection: close` and reads the
     * full response. Supports both `Content-Length` and read-until-close
     * response modes. Chunked transfer encoding is decoded if present.
     *
     * @param ip Target IP address.
     * @param port Target port number.
     * @param path HTTP request path (e.g. "/api/tags").
     * @return Response body as a string.
     * @throws java.io.IOException on network errors, non-200 status, or
     *     responses exceeding [MAX_RESPONSE_BYTES].
     */
    @JvmStatic
    internal fun rawHttpGet(
        ip: String,
        port: Int,
        path: String,
    ): String {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = HTTP_TIMEOUT_MS

            val writer = socket.getOutputStream().bufferedWriter(Charsets.US_ASCII)
            writer.write("GET $path HTTP/1.1\r\n")
            writer.write("Host: $ip:$port\r\n")
            writer.write("Accept: application/json\r\n")
            writer.write("Connection: close\r\n")
            writer.write("\r\n")
            writer.flush()

            val raw = readCapped(socket.getInputStream(), MAX_RESPONSE_BYTES)
            val response = String(raw, Charsets.UTF_8)

            val headerEnd = response.indexOf("\r\n\r\n")
            if (headerEnd == -1) {
                throw java.io.IOException("Malformed HTTP response: no header terminator")
            }

            val statusLine = response.substringBefore("\r\n")
            val statusCode =
                statusLine
                    .split(" ", limit = STATUS_LINE_PARTS)
                    .getOrNull(1)
            if (statusCode != "200") {
                val safeStatus =
                    statusLine
                        .take(MAX_STATUS_LINE_LENGTH)
                        .filter { it.isLetterOrDigit() || it in " /.:-" }
                throw java.io.IOException("HTTP error: $safeStatus")
            }

            val headers = response.substring(0, headerEnd).lowercase()
            val body = response.substring(headerEnd + HEADER_TERMINATOR_LENGTH)

            return if (headers.contains("transfer-encoding: chunked")) {
                decodeChunked(body)
            } else {
                body
            }
        }
    }

    /**
     * Decodes an HTTP chunked transfer-encoded body.
     *
     * Parses `<hex-size>\r\n<data>\r\n` chunks per RFC 7230 section 4.1
     * until the terminating `0\r\n` chunk.
     *
     * @param body The raw chunked body (after headers have been stripped).
     * @return Decoded body content.
     */
    @JvmStatic
    @Suppress("LoopWithTooManyJumpStatements")
    internal fun decodeChunked(body: String): String {
        val result = StringBuilder()
        var pos = 0
        while (pos < body.length) {
            val sizeEnd = body.indexOf("\r\n", pos)
            if (sizeEnd == -1) break
            val chunkSize =
                body.substring(pos, sizeEnd).trim().toIntOrNull(radix = 16) ?: break
            if (chunkSize == 0) break
            val dataStart = sizeEnd + 2
            val dataEnd = dataStart + chunkSize
            if (dataEnd > body.length) break
            result.append(body, dataStart, dataEnd)
            pos = dataEnd + 2
        }
        return result.toString()
    }

    /** Length of the HTTP header terminator sequence (`\r\n\r\n`). */
    private const val HEADER_TERMINATOR_LENGTH = 4

    /** Maximum characters kept from a server status line for error messages. */
    private const val MAX_STATUS_LINE_LENGTH = 64

    /** Expected minimum number of space-delimited parts in an HTTP status line. */
    private const val STATUS_LINE_PARTS = 3

    /** Read buffer size for [readCapped]. */
    private const val READ_CHUNK_SIZE = 8192

    /** Progress update emission interval during scanning. */
    private const val PROGRESS_UPDATE_INTERVAL_MS = 250L

    /** Number of octets in an IPv4 address. */
    private const val OCTET_COUNT = 4

    /** Highest valid value of an IPv4 octet. */
    private const val OCTET_MAX = 255

    /** First octet of the loopback range (127.0.0.0/8). */
    private const val LOOPBACK_FIRST_OCTET = 127

    /** First octet of the IPv4 link-local range (169.254.0.0/16). */
    private const val LINK_LOCAL_FIRST_OCTET = 169

    /** Second octet of the IPv4 link-local range (169.254.0.0/16). */
    private const val LINK_LOCAL_SECOND_OCTET = 254

    /** First octet of the CGNAT shared address space (100.64.0.0/10). */
    private const val CGNAT_FIRST_OCTET = 100

    /** Second-octet range of the CGNAT shared address space (100.64.0.0/10). */
    private val CGNAT_SECOND_OCTET_RANGE = 64..127

    /** Default Ollama port. */
    private const val PORT_OLLAMA = 11434

    /** Default LM Studio port. */
    private const val PORT_LM_STUDIO = 1234

    /** Default vLLM port. */
    private const val PORT_VLLM = 8000

    /** Default LocalAI port. */
    private const val PORT_LOCALAI = 8080

    /** Default upstream zeroclaw gateway port. */
    private const val PORT_ZEROCLAW = 42617

    /** Default OpenClaw gateway port. */
    private const val PORT_OPENCLAW = 18789

    /** Default Hermes Agent gateway port (Nous Research). */
    private const val PORT_HERMES = 8642
}
