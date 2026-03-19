/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.tailscale

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zeroclaw.android.ZeroAIApplication
import com.zeroclaw.android.model.CachedTailscalePeer
import com.zeroclaw.android.model.CachedTailscaleService
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
