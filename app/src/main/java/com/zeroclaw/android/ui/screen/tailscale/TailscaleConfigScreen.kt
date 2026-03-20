/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

@file:OptIn(ExperimentalMaterial3Api::class)

package com.zeroclaw.android.ui.screen.tailscale

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.VpnLock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zeroclaw.android.model.CachedTailscalePeer
import com.zeroclaw.android.viewmodel.DaemonUiState
import java.text.DateFormat
import java.util.Date

/**
 * Tailscale configuration screen for managing tailnet peers and service discovery.
 *
 * Shows VPN status, agent awareness toggle, manual peer entry,
 * auto-discovery and service scanning controls, and a list of
 * discovered peers with their services.
 *
 * @param onNavigateBack Called when the user navigates back.
 * @param viewModel The [TailscaleConfigViewModel] managing screen state.
 */
@Composable
fun TailscaleConfigScreen(
    onNavigateBack: () -> Unit,
    viewModel: TailscaleConfigViewModel = viewModel(),
) {
    val configState by viewModel.configState.collectAsStateWithLifecycle()
    val scanState by viewModel.scanState.collectAsStateWithLifecycle()
    val peersState by viewModel.peersState.collectAsStateWithLifecycle()
    var peerInput by remember { mutableStateOf("") }
    var tokenDialogPeerKey by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tailscale") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector =
                                Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Navigate back",
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier =
                Modifier
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item("vpn-status") {
                VpnStatusHeader(isVpnActive = configState.isVpnActive)
            }

            item("awareness-toggle") {
                AwarenessToggleRow(
                    enabled = configState.awarenessEnabled,
                    onToggle = viewModel::setAwarenessEnabled,
                )
            }

            item("add-peer") {
                AddPeerRow(
                    peerInput = peerInput,
                    onInputChange = { peerInput = it },
                    onAdd = {
                        viewModel.addManualPeer(peerInput.trim())
                        peerInput = ""
                    },
                )
            }

            item("action-buttons") {
                ScanButton(
                    scanState = scanState,
                    onScanServices = viewModel::scanServices,
                )
            }

            item("scan-result") {
                ScanResultBanner(scanState = scanState)
            }

            if (configState.peers.isEmpty()) {
                item("empty-state") {
                    Text(
                        text =
                            "No peers added yet. Enter a Tailscale" +
                                " IP or hostname above to get started.",
                        style = MaterialTheme.typography.bodyMedium,
                        color =
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }
            } else {
                items(
                    items = configState.peers,
                    key = { peer -> peer.ip },
                    contentType = { "peer_card" },
                ) { peer ->
                    PeerCard(
                        peer = peer,
                        onRemove =
                            if (peer.isManual) {
                                { viewModel.removeManualPeer(peer.ip) }
                            } else {
                                null
                            },
                    )
                }
            }

            item("peer-agents-header") {
                Text(
                    "Peer Agents",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }

            when (val peers = peersState) {
                is TailscalePeersUiState.Loading -> {
                    item("peer-agents-loading") {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                is TailscalePeersUiState.Empty -> {
                    item("peer-agents-empty") {
                        Text(
                            "No ZeroClaw or OpenClaw agents found on your peers.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                is TailscalePeersUiState.Error -> {
                    item("peer-agents-error") {
                        Text(
                            peers.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                is TailscalePeersUiState.Content -> {
                    items(
                        items = peers.peers,
                        key = { "${it.ip}:${it.port}" },
                        contentType = { "peer_agent" },
                    ) { peer ->
                        PeerRow(
                            peer = peer,
                            hasToken =
                                viewModel.getPeerToken(
                                    peer.ip,
                                    peer.port,
                                ) != null,
                            onAliasChange = {
                                viewModel.updatePeerAlias(
                                    peer.ip,
                                    peer.port,
                                    it,
                                )
                            },
                            onToggle = {
                                viewModel.togglePeer(peer.ip, peer.port, it)
                            },
                            onTokenTap = {
                                tokenDialogPeerKey = "${peer.ip}:${peer.port}"
                            },
                        )
                    }
                }
            }

            if (configState.lastScanTimestamp > 0L) {
                item("last-scan") {
                    val formatted =
                        remember(
                            configState.lastScanTimestamp,
                        ) {
                            DateFormat
                                .getDateTimeInstance(
                                    DateFormat.SHORT,
                                    DateFormat.SHORT,
                                ).format(Date(configState.lastScanTimestamp))
                        }
                    Text(
                        text = "Last scanned: $formatted",
                        style = MaterialTheme.typography.labelSmall,
                        color =
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item("bottom-spacer") {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        if (tokenDialogPeerKey != null) {
            TokenEntryDialog(
                onSave = { token ->
                    val (ip, port) = tokenDialogPeerKey!!.split(":")
                    viewModel.savePeerToken(ip, port.toInt(), token)
                    tokenDialogPeerKey = null
                },
                onDismiss = { tokenDialogPeerKey = null },
            )
        }
    }
}

/**
 * VPN active/inactive status header with icon and text.
 *
 * @param isVpnActive Whether a VPN transport is currently active.
 */
@Composable
private fun VpnStatusHeader(isVpnActive: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(
            imageVector =
                if (isVpnActive) {
                    Icons.Outlined.VpnLock
                } else {
                    Icons.Outlined.CloudOff
                },
            contentDescription = null,
            tint =
                if (isVpnActive) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
        )
        Text(
            text =
                if (isVpnActive) {
                    "VPN active"
                } else {
                    "VPN inactive"
                },
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

/**
 * Toggle row for enabling or disabling agent awareness.
 *
 * @param enabled Current toggle state.
 * @param onToggle Called with the new state when toggled.
 */
@Composable
private fun AwarenessToggleRow(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Enable Agent Awareness",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text =
                    "Inject discovered services into the agent" +
                        " system prompt",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
            modifier =
                Modifier.semantics {
                    contentDescription = "Enable Agent Awareness" +
                        if (enabled) " enabled" else " disabled"
                },
        )
    }
}

/**
 * Text field and button for adding a manual peer.
 *
 * @param peerInput Current text field content.
 * @param onInputChange Called when the text field value changes.
 * @param onAdd Called when the Add button is tapped.
 */
@Composable
private fun AddPeerRow(
    peerInput: String,
    onInputChange: (String) -> Unit,
    onAdd: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = peerInput,
            onValueChange = onInputChange,
            label = { Text("Peer IP or hostname") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = onAdd,
            enabled = peerInput.isNotBlank(),
            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
        ) {
            Text("Add")
        }
    }
}

/**
 * Scan services button.
 *
 * @param scanState Current scan operation state.
 * @param onScanServices Called when scan is tapped.
 */
@Composable
private fun ScanButton(
    scanState: DaemonUiState<String>,
    onScanServices: () -> Unit,
) {
    val isLoading = scanState is DaemonUiState.Loading
    Button(
        onClick = onScanServices,
        enabled = !isLoading,
        modifier =
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp),
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier =
                    Modifier
                        .height(20.dp)
                        .width(20.dp),
                strokeWidth = 2.dp,
            )
        } else {
            Text("Scan Services")
        }
    }
}

/**
 * Displays the result of the last scan or auto-discovery operation.
 *
 * Uses [LiveRegionMode.Polite] for accessibility announcements.
 *
 * @param scanState Current scan operation state.
 */
@Composable
private fun ScanResultBanner(scanState: DaemonUiState<String>) {
    when (scanState) {
        is DaemonUiState.Content -> {
            Text(
                text = scanState.data,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier =
                    Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                    },
            )
        }
        is DaemonUiState.Error -> {
            Text(
                text = scanState.detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier =
                    Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                    },
            )
        }
        else -> {}
    }
}

/**
 * Card displaying a tailnet peer with its discovered services.
 *
 * @param peer Cached peer data including hostname, IP, and services.
 * @param onRemove Optional callback to remove this peer (only for manual peers).
 */
@Composable
private fun PeerCard(
    peer: CachedTailscalePeer,
    onRemove: (() -> Unit)?,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = peer.hostname.ifEmpty { peer.ip },
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (peer.hostname.isNotEmpty()) {
                        Text(
                            text = peer.ip,
                            style = MaterialTheme.typography.bodySmall,
                            color =
                                MaterialTheme.colorScheme
                                    .onSurfaceVariant,
                        )
                    }
                    if (peer.isManual) {
                        Text(
                            text = "Manually added",
                            style = MaterialTheme.typography.labelSmall,
                            color =
                                MaterialTheme.colorScheme
                                    .onSurfaceVariant,
                        )
                    }
                }
                if (onRemove != null) {
                    IconButton(
                        onClick = onRemove,
                        modifier =
                            Modifier.semantics {
                                contentDescription = "Remove peer" +
                                    " ${peer.hostname.ifEmpty { peer.ip }}"
                            },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            if (peer.services.isNotEmpty()) {
                peer.services.forEach { svc ->
                    ServiceRow(
                        kind = svc.kind,
                        port = svc.port,
                        version = svc.version,
                        healthy = svc.healthy,
                    )
                }
            } else {
                Text(
                    text = "No services found",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Single service row within a peer card.
 *
 * Uses icon + text (not color-only) for health status per WCAG.
 *
 * @param kind Service type identifier.
 * @param port TCP port the service was found on.
 * @param version Version or info string, if available.
 * @param healthy Whether the service responded successfully.
 */
@Composable
private fun ServiceRow(
    kind: String,
    port: Int,
    version: String?,
    healthy: Boolean,
) {
    val label =
        when (kind) {
            "ollama" -> "Ollama"
            "lm_studio" -> "LM Studio"
            "vllm" -> "vLLM"
            "local_ai" -> "LocalAI"
            "zeroclaw" -> "zeroclaw daemon"
            else -> kind
        }
    val versionSuffix = if (version != null) " ($version)" else ""
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector =
                if (healthy) {
                    Icons.Filled.CheckCircle
                } else {
                    Icons.Filled.Warning
                },
            contentDescription =
                if (healthy) {
                    "$label healthy"
                } else {
                    "$label unhealthy"
                },
            tint =
                if (healthy) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
        )
        Text(
            text = "$label$versionSuffix on port $port",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = if (healthy) "Healthy" else "Unhealthy",
            style = MaterialTheme.typography.labelSmall,
            color =
                if (healthy) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
        )
    }
}

/**
 * Triple-encoded status indicator for a peer agent.
 *
 * Encodes status through color, icon shape, and text label so that
 * color is never the sole differentiator (WCAG 2.2 AA compliance).
 *
 * @param authRequired Whether the peer requires a bearer token.
 * @param hasToken Whether a token is stored for this peer.
 * @param modifier Optional modifier.
 */
@Composable
private fun PeerStatusIndicator(
    authRequired: Boolean,
    hasToken: Boolean,
    modifier: Modifier = Modifier,
) {
    val (icon, tint, label) =
        when {
            !authRequired || hasToken ->
                Triple(
                    Icons.Default.CheckCircle,
                    MaterialTheme.colorScheme.primary,
                    "Connected",
                )
            else ->
                Triple(
                    Icons.Default.Lock,
                    MaterialTheme.colorScheme.tertiary,
                    "Token required",
                )
        }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier.semantics(mergeDescendants = true) {
                contentDescription = label
            },
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * Row displaying a peer agent with alias field, status, token button, and toggle.
 *
 * @param peer Peer agent configuration.
 * @param hasToken Whether a token is stored for this peer.
 * @param onAliasChange Called when the alias text field changes.
 * @param onToggle Called when the enable/disable switch is toggled.
 * @param onTokenTap Called when the "Add token" chip is tapped.
 * @param modifier Optional modifier.
 */
@Composable
private fun PeerRow(
    peer: TailscalePeerConfig,
    hasToken: Boolean,
    onAliasChange: (String) -> Unit,
    onToggle: (Boolean) -> Unit,
    onTokenTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector =
                if (peer.kind == "zeroclaw") {
                    Icons.Default.Memory
                } else {
                    Icons.Default.Cloud
                },
            contentDescription = "${peer.kind} agent",
            modifier = Modifier.size(24.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(peer.hostname, style = MaterialTheme.typography.bodyMedium)
            PeerStatusIndicator(peer.authRequired, hasToken)
            OutlinedTextField(
                value = peer.alias,
                onValueChange = { newValue ->
                    val filtered =
                        newValue
                            .filter { it.isLetterOrDigit() || it == '-' || it == '_' }
                            .take(MAX_ALIAS_LENGTH)
                    onAliasChange(filtered)
                },
                label = { Text("Alias") },
                supportingText = {
                    Text(
                        "Chat prefix \u2014 type @${peer.alias} to reach this agent",
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (peer.authRequired && !hasToken) {
            AssistChip(
                onClick = onTokenTap,
                label = { Text("Add token") },
                modifier =
                    Modifier.semantics {
                        contentDescription = "Add token for ${peer.alias}"
                    },
            )
        }
        Switch(
            checked = peer.enabled,
            onCheckedChange = onToggle,
        )
    }
}

/**
 * Dialog for entering a peer agent bearer token.
 *
 * @param onSave Called with the entered token when Save is tapped.
 * @param onDismiss Called when the dialog is dismissed.
 */
@Composable
private fun TokenEntryDialog(
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var tokenText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enter Bearer Token") },
        text = {
            OutlinedTextField(
                value = tokenText,
                onValueChange = { tokenText = it },
                label = { Text("Token") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(tokenText) },
                enabled = tokenText.isNotBlank(),
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

/** Maximum alias length in characters, matching [PeerMessageRouter] validation. */
private const val MAX_ALIAS_LENGTH = 32
