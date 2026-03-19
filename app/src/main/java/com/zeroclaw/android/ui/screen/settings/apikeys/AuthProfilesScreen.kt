/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.settings.apikeys

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
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zeroclaw.android.ui.component.EmptyState
import com.zeroclaw.android.ui.component.ErrorCard
import com.zeroclaw.android.ui.component.LoadingIndicator
import com.zeroclaw.android.ui.component.MiniZeroMascot
import com.zeroclaw.android.ui.component.MiniZeroMascotState

/**
 * Screen for managing stored auth profiles (OAuth tokens and API key profiles).
 *
 * Displays a list of all auth profiles read from the daemon's workspace,
 * with controls to view details and delete individual profiles. Each card
 * shows the provider name, profile name, kind (OAuth or Token), active
 * status, and relevant timestamps.
 *
 * @param edgeMargin Horizontal padding based on window width size class.
 * @param authProfilesViewModel ViewModel providing profile state and actions.
 * @param modifier Modifier applied to the root layout.
 */
@Composable
fun AuthProfilesScreen(
    edgeMargin: Dp,
    authProfilesViewModel: AuthProfilesViewModel = viewModel(),
    modifier: Modifier = Modifier,
) {
    val uiState by authProfilesViewModel.uiState.collectAsStateWithLifecycle()
    val snackbarMessage by authProfilesViewModel.snackbarMessage.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var deleteTarget by remember { mutableStateOf<AuthProfileItem?>(null) }

    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            authProfilesViewModel.clearSnackbar()
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = edgeMargin),
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            when (val state = uiState) {
                is AuthProfilesUiState.Loading -> {
                    LoadingIndicator(
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                }
                is AuthProfilesUiState.Error -> {
                    ErrorCard(
                        message = state.detail,
                        onRetry = { authProfilesViewModel.loadProfiles() },
                    )
                }
                is AuthProfilesUiState.Content -> {
                    if (state.data.isEmpty()) {
                        EmptyState(
                            message = "No auth profiles stored yet.",
                            headerContent = {
                                MiniZeroMascot(
                                    state = MiniZeroMascotState.Peek,
                                    size = 72.dp,
                                    contentDescription = null,
                                )
                            },
                        )
                    } else {
                        AuthProfilesList(
                            profiles = state.data,
                            onDelete = { profile -> deleteTarget = profile },
                        )
                    }
                }
            }
        }
    }

    deleteTarget?.let { profile ->
        DeleteProfileDialog(
            profile = profile,
            onConfirm = {
                authProfilesViewModel.removeProfile(
                    profile.provider,
                    profile.profileName,
                )
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }
}

/**
 * Lazy column of auth profile cards.
 *
 * @param profiles List of auth profile items to display.
 * @param onDelete Callback invoked when the user requests deletion of a profile.
 */
@Composable
private fun AuthProfilesList(
    profiles: List<AuthProfileItem>,
    onDelete: (AuthProfileItem) -> Unit,
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(
            items = profiles,
            key = { it.id },
            contentType = { "auth_profile" },
        ) { profile ->
            val onDeleteProfile =
                remember(profile.id) {
                    { onDelete(profile) }
                }
            AuthProfileCard(
                profile = profile,
                onDelete = onDeleteProfile,
            )
        }
        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

/**
 * Card displaying a single auth profile with provider, kind, and timestamps.
 *
 * Shows the provider name and profile name as the headline, an active
 * badge when applicable, the profile kind (OAuth or Token), expiry
 * information if available, and creation/update timestamps. A delete
 * icon button allows removing the profile.
 *
 * @param profile The auth profile item to display.
 * @param onDelete Callback invoked when the delete button is tapped.
 * @param modifier Modifier applied to the card.
 */
@Composable
private fun AuthProfileCard(
    profile: AuthProfileItem,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val kindColor =
        when (profile.kind) {
            "OAuth" -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.tertiary
        }

    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {
                    contentDescription =
                        "Profile: ${profile.provider} ${profile.profileName}, " +
                        "kind: ${profile.kind}" +
                        if (profile.isActive) ", active" else ""
                },
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = profile.provider,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = profile.profileName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (profile.isActive) {
                        Text(
                            text = "Active",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = profile.kind,
                        style = MaterialTheme.typography.labelSmall,
                        color = kindColor,
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    profile.expiryLabel?.let { expiry ->
                        Text(
                            text = "Expires: $expiry",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = "Created: ${profile.createdLabel}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Updated: ${profile.updatedLabel}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                IconButton(
                    onClick = onDelete,
                    modifier =
                        Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .semantics { contentDescription = "Delete profile" },
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/**
 * Confirmation dialog shown before deleting an auth profile.
 *
 * Displays the profile's provider and name so the user can verify
 * which profile will be removed. This action is irreversible.
 *
 * @param profile The profile targeted for deletion.
 * @param onConfirm Called when the user confirms deletion.
 * @param onDismiss Called when the user cancels.
 */
@Composable
private fun DeleteProfileDialog(
    profile: AuthProfileItem,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete Profile?") },
        text = {
            Text(
                "Remove the \"${profile.profileName}\" profile for " +
                    "${profile.provider}? This action cannot be undone.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
