// Copyright 2026 @Natfii, MIT License

package com.zeroclaw.android.ui.screen.settings.skillpermissions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.zeroclaw.ffi.CapabilityGrantInfo

/** Top padding in dp applied above the grants list content area. */
private const val GRANTS_HEADER_PADDING_TOP = 8

/**
 * Screen for viewing and revoking dangerous capability grants given to skills.
 *
 * Displays all persisted capability grants grouped by skill name, with a
 * delete button on each row to revoke individual grants. Shows an empty state
 * when no grants exist, and an error card if the grants file cannot be read.
 *
 * @param edgeMargin Horizontal padding based on window width size class.
 * @param skillPermissionsViewModel ViewModel providing grant state and revoke actions.
 * @param modifier Modifier applied to the root layout.
 */
@Composable
fun SkillPermissionsScreen(
    edgeMargin: Dp,
    skillPermissionsViewModel: SkillPermissionsViewModel = viewModel(),
    modifier: Modifier = Modifier,
) {
    val uiState by skillPermissionsViewModel.uiState.collectAsStateWithLifecycle()
    val snackbarMessage by skillPermissionsViewModel.snackbarMessage.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            skillPermissionsViewModel.clearSnackbar()
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
            Spacer(modifier = Modifier.height(GRANTS_HEADER_PADDING_TOP.dp))

            when (val state = uiState) {
                is SkillPermissionsUiState.Loading -> {
                    LoadingIndicator(
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                }
                is SkillPermissionsUiState.Error -> {
                    ErrorCard(
                        message = state.detail,
                        onRetry = { skillPermissionsViewModel.loadGrants() },
                    )
                }
                is SkillPermissionsUiState.Content -> {
                    if (state.data.isEmpty()) {
                        EmptyState(
                            icon = Icons.Outlined.Security,
                            message =
                                "No capability grants. Skills that request dangerous " +
                                    "permissions will appear here.",
                        )
                    } else {
                        GrantsList(
                            grants = state.data,
                            onRevoke = { skillName, capability ->
                                skillPermissionsViewModel.revokeGrant(skillName, capability)
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Lazy column of capability grant sections grouped by skill name.
 *
 * Each skill name is rendered as a sticky-style section header followed by
 * one [GrantRow] per capability in that skill's grant set.
 *
 * @param grants Flat list of all [CapabilityGrantInfo] records to display.
 * @param onRevoke Callback invoked with `(skillName, capability)` when the user taps revoke.
 */
@Composable
private fun GrantsList(
    grants: List<CapabilityGrantInfo>,
    onRevoke: (skillName: String, capability: String) -> Unit,
) {
    val grouped =
        remember(grants) {
            grants.groupBy { it.skillName }.toSortedMap()
        }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        grouped.forEach { (skillName, skillGrants) ->
            item(
                key = "header_$skillName",
                contentType = "skill_header",
            ) {
                SkillSectionHeader(skillName = skillName)
            }

            itemsIndexed(
                items = skillGrants,
                key = { _, grant -> "${grant.skillName}::${grant.capability}" },
                contentType = { _, _ -> "grant_row" },
            ) { index, grant ->
                GrantRow(
                    grant = grant,
                    onRevoke = { onRevoke(grant.skillName, grant.capability) },
                )
                if (index < skillGrants.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 16.dp),
                    )
                }
            }

            item(
                key = "spacer_$skillName",
                contentType = "spacer",
            ) {
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

/**
 * Section header label displaying a skill name.
 *
 * @param skillName The name of the skill whose grants follow this header.
 */
@Composable
private fun SkillSectionHeader(skillName: String) {
    Text(
        text = skillName,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 4.dp),
    )
}

/**
 * Row displaying a single capability grant with capability name, grant metadata,
 * and a delete (revoke) button.
 *
 * Shows the capability string as the primary label, the surface through which
 * the grant was approved, and the RFC 3339 timestamp formatted as a secondary label.
 *
 * @param grant The [CapabilityGrantInfo] to display.
 * @param onRevoke Callback invoked when the user taps the revoke button.
 * @param modifier Modifier applied to the card.
 */
@Composable
private fun GrantRow(
    grant: CapabilityGrantInfo,
    onRevoke: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription =
                        "Capability ${grant.capability} granted to ${grant.skillName} " +
                        "via ${grant.grantedVia}"
                },
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = grant.capability,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "via ${grant.grantedVia}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = grant.grantedAt,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = onRevoke,
                modifier =
                    Modifier
                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        .semantics {
                            contentDescription =
                                "Revoke ${grant.capability} for ${grant.skillName}"
                        },
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
