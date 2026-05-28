/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

@file:Suppress("MatchingDeclarationName")

package com.zeroclaw.android.ui.screen.agents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zeroclaw.android.ui.component.ContentPane
import com.zeroclaw.android.ui.component.EmptyState
import com.zeroclaw.android.ui.component.ProviderIcon

/**
 * Aggregated state for the fixed-slot Agents catalog.
 *
 * @property slots Filtered slot cards to display.
 * @property searchQuery Current search query text.
 */
data class AgentsState(
    val slots: List<AgentSlotItem>,
    val searchQuery: String,
)

/**
 * Fixed-slot Agents catalog screen.
 *
 * @param onNavigateToDetail Callback to navigate to a slot detail screen.
 * @param onNavigateToOnDeviceLarge Callback to navigate to the on-device
 *   large model setup screen.
 * @param edgeMargin Horizontal padding based on window width size class.
 * @param agentsViewModel The [AgentsViewModel] for slot state.
 * @param modifier Modifier applied to the root layout.
 */
@Composable
fun AgentsScreen(
    onNavigateToDetail: (String) -> Unit,
    onNavigateToOnDeviceLarge: () -> Unit,
    edgeMargin: Dp,
    agentsViewModel: AgentsViewModel = viewModel(),
    modifier: Modifier = Modifier,
) {
    val slots by agentsViewModel.slots.collectAsStateWithLifecycle()
    val searchQuery by agentsViewModel.searchQuery.collectAsStateWithLifecycle()
    val onDeviceLargeEnabled by agentsViewModel.onDeviceLargeEnabled.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        agentsViewModel.refreshConnections()
    }

    AgentsContent(
        state = AgentsState(slots = slots, searchQuery = searchQuery),
        edgeMargin = edgeMargin,
        onNavigateToDetail = onNavigateToDetail,
        onNavigateToOnDeviceLarge = onNavigateToOnDeviceLarge,
        onSearchChange = agentsViewModel::updateSearch,
        onToggleSlot = agentsViewModel::toggleSlot,
        onToggleOnDeviceLarge = agentsViewModel::toggleOnDeviceLarge,
        onDeviceLargeEnabled = onDeviceLargeEnabled,
        modifier = modifier,
    )
}

/**
 * Stateless fixed-slot Agents content.
 *
 * @param state Current screen state snapshot.
 * @param edgeMargin Horizontal padding based on window width size class.
 * @param onNavigateToDetail Callback to navigate to slot detail.
 * @param onNavigateToOnDeviceLarge Callback to open the on-device large
 *   model setup screen.
 * @param onSearchChange Callback when the search query changes.
 * @param onToggleSlot Callback to toggle a slot by its stable ID.
 * @param onToggleOnDeviceLarge Callback to flip the on-device agent's
 *   enabled state.
 * @param onDeviceLargeEnabled Whether the on-device large agent is
 *   currently the active agent.
 * @param modifier Modifier applied to the root layout.
 */
@Composable
@Suppress("LongParameterList")
internal fun AgentsContent(
    state: AgentsState,
    edgeMargin: Dp,
    onNavigateToDetail: (String) -> Unit,
    onNavigateToOnDeviceLarge: () -> Unit,
    onSearchChange: (String) -> Unit,
    onToggleSlot: (String) -> Unit,
    onToggleOnDeviceLarge: () -> Unit,
    onDeviceLargeEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier) { innerPadding ->
        ContentPane(
            modifier =
                Modifier
                    .padding(innerPadding)
                    .padding(horizontal = edgeMargin),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Text(
                    text = "Agents",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Manage provider routes and connected logins.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = onSearchChange,
                    label = { Text("Search providers") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(16.dp))
                OnDeviceLargeModelCard(
                    enabled = onDeviceLargeEnabled,
                    onClick = onNavigateToOnDeviceLarge,
                    onToggle = onToggleOnDeviceLarge,
                )
                Spacer(modifier = Modifier.height(16.dp))

                if (state.slots.isEmpty()) {
                    EmptyState(
                        icon = Icons.Outlined.SmartToy,
                        message = "No slots match your search",
                    )
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(
                            items = state.slots,
                            key = { it.slotId },
                            contentType = { "slot" },
                        ) { slot ->
                            val onToggle = remember(slot.slotId) { { onToggleSlot(slot.slotId) } }
                            val onClick =
                                remember(slot.slotId) {
                                    { onNavigateToDetail(slot.slotId) }
                                }
                            AgentSlotCard(
                                slot = slot,
                                onToggle = onToggle,
                                onClick = onClick,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Card for a single fixed provider slot.
 *
 * @param slot Slot UI model to render.
 * @param onToggle Callback when the enabled switch is toggled.
 * @param onClick Callback when the card is tapped.
 */
@Composable
private fun AgentSlotCard(
    slot: AgentSlotItem,
    onToggle: () -> Unit,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier =
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProviderIcon(provider = agentSlotProviderIconId(slot))
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = slot.displayName,
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = slot.providerName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = slot.connectionSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (slot.requiresConnection) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                )
                slot.modelName?.let { modelName ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = modelName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            if (slot.routesModelRequests) {
                Switch(
                    checked = slot.isEnabled,
                    onCheckedChange = { onToggle() },
                    modifier =
                        Modifier.semantics {
                            contentDescription =
                                "${slot.displayName} ${if (slot.isEnabled) "enabled" else "disabled"}"
                        },
                )
            } else {
                Text(
                    text = "Apps",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun agentSlotProviderIconId(slot: AgentSlotItem): String =
    when (slot.slotId) {
        "gemini-api" -> "google-gemini"
        "openai-api", "chatgpt" -> "openai"
        "anthropic-api", "claude-code" -> "anthropic"
        "ollama" -> "ollama"
        "openrouter-api" -> "openrouter"
        else -> slot.providerName
    }

/**
 * Card linking to the on-device large model setup screen.
 *
 * Visual parity with the cloud slot cards: tapping the card opens the
 * detail screen, the trailing [Switch] flips the on-device agent's
 * enabled state with the same mutual-exclusion semantics as the slot
 * toggles (`AgentDao.toggleExclusive`).
 *
 * @param enabled Whether the on-device large agent is currently active.
 * @param onClick Callback when the card is tapped.
 * @param onToggle Callback when the enable switch flips.
 */
@Composable
private fun OnDeviceLargeModelCard(
    enabled: Boolean,
    onClick: () -> Unit,
    onToggle: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier =
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Memory,
                contentDescription = null,
                tint =
                    if (enabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "On-device large model",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "LiteRT-LM · Gemma 4 / Phi-4",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (enabled) "Active agent" else "Tap to set up",
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (enabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Switch(checked = enabled, onCheckedChange = { onToggle() })
        }
    }
}
