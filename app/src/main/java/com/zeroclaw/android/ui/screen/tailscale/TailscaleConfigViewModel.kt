/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.tailscale

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.zeroclaw.android.ZeroAIApplication
import com.zeroclaw.android.model.CachedTailscalePeer
import com.zeroclaw.android.model.CachedTailscaleService
import com.zeroclaw.android.tailscale.PeerMessageRouter
import com.zeroclaw.android.tailscale.isAgentKind
import com.zeroclaw.android.tailscale.normalizeKind
import com.zeroclaw.android.viewmodel.DaemonUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Configuration for a single tailscale peer agent, used by the UI layer.
 *
 * @property ip Tailscale IP address.
 * @property hostname Peer hostname.
 * @property kind Agent type: `"zeroclaw"` or `"openclaw"`.
 * @property port Agent gateway TCP port.
 * @property alias User-configurable @mention alias.
 * @property authRequired Whether the peer requires a bearer token.
 * @property enabled Whether this peer is enabled for routing.
 */
data class TailscalePeerConfig(
    val ip: String,
    val hostname: String,
    val kind: String,
    val port: Int,
    val alias: String,
    val authRequired: Boolean = true,
    val enabled: Boolean = true,
)

/**
 * UI state for the tailscale peer agents section.
 */
sealed interface TailscalePeersUiState {
    /** Loading peer configuration. */
    data object Loading : TailscalePeersUiState

    /**
     * Error loading peers.
     *
     * @property message Human-readable error description.
     * @property onRetry Callback to retry the failed operation.
     */
    data class Error(
        val message: String,
        val onRetry: () -> Unit,
    ) : TailscalePeersUiState

    /** No agent peers discovered. */
    data object Empty : TailscalePeersUiState

    /**
     * Peers loaded successfully.
     *
     * @property peers List of discovered peer agent configurations.
     */
    data class Content(
        val peers: List<TailscalePeerConfig>,
    ) : TailscalePeersUiState
}

/**
 * State for the Tailscale configuration screen content.
 *
 * @property isVpnActive Whether a VPN transport is currently active.
 * @property tailnetName Tailnet name from auto-discovery, or empty.
 * @property selfIp This device's Tailscale IP from auto-discovery, or empty.
 * @property awarenessEnabled Whether the agent awareness toggle is on.
 * @property peers Combined list of manual and auto-discovered peers with probe results.
 * @property lastScanTimestamp Unix timestamp (millis) of last scan, or 0 if never scanned.
 * @property manualPeerInput Current text in the "Add Peer" text field.
 */
data class TailscaleConfigState(
    val isVpnActive: Boolean = false,
    val tailnetName: String = "",
    val selfIp: String = "",
    val awarenessEnabled: Boolean = false,
    val peers: List<CachedTailscalePeer> = emptyList(),
    val lastScanTimestamp: Long = 0L,
    val manualPeerInput: String = "",
)

/**
 * ViewModel for the Tailscale configuration screen.
 *
 * Manages VPN detection, manual peer entry, auto-discovery via FFI,
 * service probing, and settings persistence.
 */
class TailscaleConfigViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val app = application as ZeroAIApplication
    private val settingsRepository = app.settingsRepository

    private val _scanState =
        MutableStateFlow<DaemonUiState<String>>(DaemonUiState.Idle)

    /** Scan operation state (Idle, Loading, Error, or Content with status message). */
    val scanState: StateFlow<DaemonUiState<String>> = _scanState.asStateFlow()

    /** Screen content state derived from persisted settings. */
    val configState: StateFlow<TailscaleConfigState> =
        settingsRepository.settings
            .map { settings ->
                val peers =
                    if (
                        settings.tailscaleCachedDiscovery.isNotBlank()
                    ) {
                        try {
                            Json.decodeFromString<List<CachedTailscalePeer>>(
                                settings.tailscaleCachedDiscovery,
                            )
                        } catch (_: Exception) {
                            emptyList()
                        }
                    } else {
                        emptyList()
                    }
                TailscaleConfigState(
                    isVpnActive = isVpnActive(),
                    awarenessEnabled = settings.tailscaleAwarenessEnabled,
                    peers = peers,
                    lastScanTimestamp =
                        settings.tailscaleLastScanTimestamp,
                )
            }.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000L),
                TailscaleConfigState(),
            )

    private val _peersState =
        MutableStateFlow<TailscalePeersUiState>(TailscalePeersUiState.Loading)

    /** Peer agents UI state. */
    val peersState: StateFlow<TailscalePeersUiState> = _peersState.asStateFlow()

    private val encryptedPrefs by lazy {
        val masterKey =
            MasterKey
                .Builder(getApplication<ZeroAIApplication>())
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
        EncryptedSharedPreferences.create(
            getApplication(),
            "tailscale_peer_tokens",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    init {
        loadPeerConfig()
    }

    private fun loadPeerConfig() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val settings = settingsRepository.settings.first()
                val peers =
                    if (settings.tailscaleCachedDiscovery.isNotBlank()) {
                        try {
                            Json.decodeFromString<List<CachedTailscalePeer>>(
                                settings.tailscaleCachedDiscovery,
                            )
                        } catch (_: Exception) {
                            emptyList()
                        }
                    } else {
                        emptyList()
                    }

                val rawPeers =
                    peers.flatMap { peer ->
                        peer.services
                            .filter { isAgentKind(it.kind) }
                            .map { svc ->
                                TailscalePeerConfig(
                                    ip = peer.ip,
                                    hostname = peer.hostname,
                                    kind = normalizeKind(svc.kind),
                                    port = svc.port,
                                    alias = normalizeKind(svc.kind),
                                    authRequired = svc.authRequired,
                                )
                            }
                    }
                val defaults =
                    PeerMessageRouter.resolveAliasConflicts(
                        rawPeers.map { it.alias },
                    )
                val agentPeers =
                    rawPeers.mapIndexed { i, peer ->
                        val saved = getSavedAlias(peer.ip, peer.port)
                        peer.copy(alias = saved ?: defaults[i])
                    }

                _peersState.value =
                    if (agentPeers.isEmpty()) {
                        TailscalePeersUiState.Empty
                    } else {
                        TailscalePeersUiState.Content(agentPeers)
                    }
            } catch (e: Exception) {
                _peersState.value =
                    TailscalePeersUiState.Error(
                        message = "Failed to load peer config: ${e.message}",
                        onRetry = { loadPeerConfig() },
                    )
            }
        }
    }

    /**
     * Saves a peer agent token to encrypted storage.
     *
     * @param ip Peer Tailscale IP.
     * @param port Peer gateway port.
     * @param token The bearer token to store.
     */
    fun savePeerToken(
        ip: String,
        port: Int,
        token: String,
    ) {
        val key = peerTokenKey(ip, port)
        encryptedPrefs.edit().putString(key, token).apply()
    }

    /**
     * Retrieves a peer agent token from encrypted storage.
     *
     * @param ip Peer Tailscale IP.
     * @param port Peer gateway port.
     * @return The token, or `null` if not stored.
     */
    fun getPeerToken(
        ip: String,
        port: Int,
    ): String? {
        val key = peerTokenKey(ip, port)
        return encryptedPrefs.getString(key, null)
    }

    /**
     * Generates the encrypted preferences key for a peer token.
     *
     * @param ip Peer IP address.
     * @param port Peer gateway port.
     * @return Formatted key string.
     */
    private fun peerTokenKey(
        ip: String,
        port: Int,
    ): String {
        val sanitizedIp = ip.replace(Regex("[^a-fA-F0-9.:]"), "")
        return "tailscale_peer_${sanitizedIp}_$port"
    }

    /**
     * Retrieves a persisted alias from encrypted storage.
     *
     * @param ip Peer IP address.
     * @param port Peer gateway port.
     * @return The saved alias, or `null` if none set.
     */
    private fun getSavedAlias(
        ip: String,
        port: Int,
    ): String? {
        val key = peerAliasKey(ip, port)
        return encryptedPrefs.getString(key, null)
    }

    /**
     * Persists an alias to encrypted storage.
     *
     * @param ip Peer IP address.
     * @param port Peer gateway port.
     * @param alias Alias to store.
     */
    private fun saveAlias(
        ip: String,
        port: Int,
        alias: String,
    ) {
        val key = peerAliasKey(ip, port)
        encryptedPrefs.edit().putString(key, alias).apply()
    }

    /**
     * Generates the encrypted preferences key for a peer alias.
     *
     * @param ip Peer IP address.
     * @param port Peer gateway port.
     * @return Formatted key string.
     */
    private fun peerAliasKey(
        ip: String,
        port: Int,
    ): String {
        val sanitizedIp = ip.replace(Regex("[^a-fA-F0-9.:]"), "")
        return "tailscale_alias_${sanitizedIp}_$port"
    }

    /**
     * Updates the alias for a peer agent.
     *
     * @param ip Peer IP address.
     * @param port Peer gateway port.
     * @param alias New alias value.
     */
    fun updatePeerAlias(
        ip: String,
        port: Int,
        alias: String,
    ) {
        val current = _peersState.value
        if (current !is TailscalePeersUiState.Content) return
        _peersState.value =
            TailscalePeersUiState.Content(
                current.peers.map { peer ->
                    if (peer.ip == ip && peer.port == port) peer.copy(alias = alias) else peer
                },
            )
        saveAlias(ip, port, alias)
    }

    /**
     * Toggles a peer agent's enabled state.
     *
     * @param ip Peer IP address.
     * @param port Peer gateway port.
     * @param enabled New enabled state.
     */
    fun togglePeer(
        ip: String,
        port: Int,
        enabled: Boolean,
    ) {
        val current = _peersState.value
        if (current !is TailscalePeersUiState.Content) return
        _peersState.value =
            TailscalePeersUiState.Content(
                current.peers.map { peer ->
                    if (peer.ip == ip && peer.port == port) peer.copy(enabled = enabled) else peer
                },
            )
    }

    /**
     * Toggles the agent awareness setting.
     *
     * @param enabled New enabled state.
     */
    fun setAwarenessEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setTailscaleAwarenessEnabled(enabled)
        }
    }

    /**
     * Adds a manually entered peer IP or hostname.
     *
     * @param peerAddress IP address or hostname to add.
     */
    fun addManualPeer(peerAddress: String) {
        if (peerAddress.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val current =
                settingsRepository.settings.first().tailscaleManualPeers
            val existing =
                if (current.isNotBlank()) {
                    try {
                        Json.decodeFromString<List<String>>(current)
                    } catch (_: Exception) {
                        emptyList()
                    }
                } else {
                    emptyList()
                }
            if (existing.contains(peerAddress)) return@launch
            val updated = existing + peerAddress
            settingsRepository.setTailscaleManualPeers(
                Json.encodeToString(updated),
            )

            _scanState.value = DaemonUiState.Loading
            try {
                val probeResults =
                    com.zeroclaw.ffi.tailnetProbeServices(listOf(peerAddress))
                val settings = settingsRepository.settings.first()
                val existingPeers =
                    if (
                        settings.tailscaleCachedDiscovery.isNotBlank()
                    ) {
                        try {
                            Json.decodeFromString<List<CachedTailscalePeer>>(
                                settings.tailscaleCachedDiscovery,
                            )
                        } catch (_: Exception) {
                            emptyList()
                        }
                    } else {
                        emptyList()
                    }
                val newPeers =
                    probeResults.map { probe ->
                        CachedTailscalePeer(
                            hostname = "",
                            ip = probe.ip,
                            isManual = true,
                            services =
                                probe.services.map { svc ->
                                    CachedTailscaleService(
                                        kind = svc.kind.name.lowercase(),
                                        port = svc.port.toInt(),
                                        version = svc.version,
                                        healthy = svc.healthy,
                                        authRequired = svc.authRequired,
                                    )
                                },
                        )
                    }
                val merged =
                    (
                        existingPeers.filter { it.ip != peerAddress } + newPeers
                    ).distinctBy { it.ip }
                settingsRepository.setTailscaleCachedDiscovery(
                    Json.encodeToString(merged),
                )
                settingsRepository.setTailscaleLastScanTimestamp(
                    System.currentTimeMillis(),
                )
                val svcCount = newPeers.sumOf { it.services.size }
                _scanState.value =
                    DaemonUiState.Content(
                        "Found $svcCount service(s) on $peerAddress",
                    )
                loadPeerConfig()
            } catch (e: Exception) {
                _scanState.value =
                    DaemonUiState.Error(
                        detail = "Scan failed: ${e.message}",
                        retry = { scanServices() },
                    )
            }
        }
    }

    /**
     * Removes a manually added peer.
     *
     * @param peerAddress IP address or hostname to remove.
     */
    fun removeManualPeer(peerAddress: String) {
        viewModelScope.launch {
            val settings = settingsRepository.settings.first()
            val manualList =
                if (settings.tailscaleManualPeers.isNotBlank()) {
                    try {
                        Json.decodeFromString<List<String>>(settings.tailscaleManualPeers)
                    } catch (_: Exception) {
                        emptyList()
                    }
                } else {
                    emptyList()
                }
            settingsRepository.setTailscaleManualPeers(
                Json.encodeToString(manualList.filter { it != peerAddress }),
            )

            val cachedList =
                if (settings.tailscaleCachedDiscovery.isNotBlank()) {
                    try {
                        Json.decodeFromString<List<CachedTailscalePeer>>(
                            settings.tailscaleCachedDiscovery,
                        )
                    } catch (_: Exception) {
                        emptyList()
                    }
                } else {
                    emptyList()
                }
            settingsRepository.setTailscaleCachedDiscovery(
                Json.encodeToString(cachedList.filter { it.ip != peerAddress }),
            )
        }
    }

    /**
     * Attempts Tailscale local API auto-discovery.
     *
     * On success, merges discovered peers with manual entries.
     * On failure, posts a toast-level message (not a blocking error).
     */
    @Suppress("TooGenericExceptionCaught")
    fun autoDiscover() {
        viewModelScope.launch(Dispatchers.IO) {
            _scanState.value = DaemonUiState.Loading
            try {
                val result = com.zeroclaw.ffi.tailnetAutoDiscover()
                val discoveredPeers =
                    result.peers.map { peer ->
                        CachedTailscalePeer(
                            hostname = peer.hostname,
                            ip = peer.ip,
                            isManual = false,
                        )
                    }
                val currentSettings =
                    settingsRepository.settings.first()
                val manualIps =
                    if (
                        currentSettings.tailscaleManualPeers.isNotBlank()
                    ) {
                        try {
                            Json.decodeFromString<List<String>>(
                                currentSettings.tailscaleManualPeers,
                            )
                        } catch (_: Exception) {
                            emptyList()
                        }
                    } else {
                        emptyList()
                    }
                val manualPeers =
                    manualIps.map { ip ->
                        CachedTailscalePeer(
                            hostname = "",
                            ip = ip,
                            isManual = true,
                        )
                    }
                val merged =
                    (discoveredPeers + manualPeers).distinctBy { it.ip }
                settingsRepository.setTailscaleCachedDiscovery(
                    Json.encodeToString(merged),
                )
                _scanState.value =
                    DaemonUiState.Content(
                        "Found ${result.peers.size} peer(s)" +
                            " on ${result.tailnetName}",
                    )
                loadPeerConfig()
            } catch (_: Exception) {
                _scanState.value =
                    DaemonUiState.Content(
                        "Auto-discovery unavailable" +
                            " \u2014 add peers manually",
                    )
            }
        }
    }

    /**
     * Probes all known peers (manual + cached) for Ollama and zeroclaw services.
     */
    @Suppress("TooGenericExceptionCaught")
    fun scanServices() {
        viewModelScope.launch(Dispatchers.IO) {
            _scanState.value = DaemonUiState.Loading
            try {
                val settings = settingsRepository.settings.first()
                val cachedPeers =
                    if (
                        settings.tailscaleCachedDiscovery.isNotBlank()
                    ) {
                        try {
                            Json.decodeFromString<List<CachedTailscalePeer>>(
                                settings.tailscaleCachedDiscovery,
                            )
                        } catch (_: Exception) {
                            emptyList()
                        }
                    } else {
                        emptyList()
                    }
                val manualIps =
                    if (
                        settings.tailscaleManualPeers.isNotBlank()
                    ) {
                        try {
                            Json.decodeFromString<List<String>>(
                                settings.tailscaleManualPeers,
                            )
                        } catch (_: Exception) {
                            emptyList()
                        }
                    } else {
                        emptyList()
                    }
                val allIps =
                    (cachedPeers.map { it.ip } + manualIps).distinct()
                if (allIps.isEmpty()) {
                    _scanState.value =
                        DaemonUiState.Content("No peers to scan")
                    return@launch
                }

                val probeResults =
                    com.zeroclaw.ffi.tailnetProbeServices(allIps)
                val updatedPeers =
                    probeResults.map { probe ->
                        val existing =
                            cachedPeers.firstOrNull { it.ip == probe.ip }
                        CachedTailscalePeer(
                            hostname = existing?.hostname ?: "",
                            ip = probe.ip,
                            isManual =
                                existing?.isManual
                                    ?: manualIps.contains(probe.ip),
                            services =
                                probe.services.map { svc ->
                                    CachedTailscaleService(
                                        kind = svc.kind.name.lowercase(),
                                        port = svc.port.toInt(),
                                        version = svc.version,
                                        healthy = svc.healthy,
                                        authRequired = svc.authRequired,
                                    )
                                },
                        )
                    }

                settingsRepository.setTailscaleCachedDiscovery(
                    Json.encodeToString(updatedPeers),
                )
                settingsRepository.setTailscaleLastScanTimestamp(
                    System.currentTimeMillis(),
                )

                val totalServices =
                    updatedPeers.sumOf { it.services.size }
                _scanState.value =
                    DaemonUiState.Content(
                        "Found $totalServices service(s)" +
                            " across ${updatedPeers.size} peer(s)",
                    )
                loadPeerConfig()
            } catch (e: Exception) {
                _scanState.value =
                    DaemonUiState.Error(
                        detail = "Scan failed: ${e.message}",
                        retry = { scanServices() },
                    )
            }
        }
    }

    private fun isVpnActive(): Boolean {
        val context = getApplication<ZeroAIApplication>()
        val cm =
            context.getSystemService(
                android.content.Context.CONNECTIVITY_SERVICE,
            ) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }
}
