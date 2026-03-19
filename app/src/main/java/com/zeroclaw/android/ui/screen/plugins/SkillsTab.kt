/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

@file:Suppress("MagicNumber")
@file:OptIn(ExperimentalMaterial3Api::class)

package com.zeroclaw.android.ui.screen.plugins

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zeroclaw.android.model.Skill
import com.zeroclaw.android.ui.component.CategoryBadge
import com.zeroclaw.android.ui.component.EmptyState
import com.zeroclaw.android.ui.component.ErrorCard
import com.zeroclaw.android.ui.component.LoadingIndicator

/** Maximum number of tool name badges to show before truncating. */
private const val MAX_TOOL_BADGES = 3

/**
 * Content for the Skills tab inside the combined Plugins and Skills screen.
 *
 * Shows a search-filtered list of community skills loaded from the workspace.
 * Each skill card displays metadata, tool count, a toggle switch, and a
 * remove button. A FAB navigates to the skill builder for creating new skills.
 *
 * The first time the FAB is tapped, a one-time warning dialog informs the user
 * that community skills can contact external services. After the user confirms,
 * the acknowledgement is persisted and the dialog is never shown again.
 *
 * @param skillsViewModel ViewModel providing skills state and actions.
 * @param onNavigateToBuilder Callback to navigate to the skill builder,
 *     passing the skill name for editing or null for creating a new skill.
 * @param modifier Modifier applied to the root layout.
 */
@Composable
fun SkillsTab(
    skillsViewModel: SkillsViewModel,
    onNavigateToBuilder: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val filteredState by skillsViewModel.filteredUiState.collectAsStateWithLifecycle()
    val searchQuery by skillsViewModel.searchQuery.collectAsStateWithLifecycle()
    val warningSeen by skillsViewModel.skillInstallWarningSeen.collectAsStateWithLifecycle()
    var pendingRemoval by remember { mutableStateOf<String?>(null) }
    var showInstallWarning by remember { mutableStateOf(false) }

    pendingRemoval?.let { skillName ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text("Remove skill?") },
            text = {
                Text(
                    "\"$skillName\" will be permanently deleted" +
                        " from this device.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    skillsViewModel.removeSkill(skillName)
                    pendingRemoval = null
                }) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoval = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (showInstallWarning) {
        AlertDialog(
            onDismissRequest = { showInstallWarning = false },
            title = { Text("Skills can access external services") },
            text = {
                Text(
                    "Community skills may define API endpoints that the agent will " +
                        "contact on your behalf. Only install skills from sources you trust.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    skillsViewModel.markSkillInstallWarningSeen()
                    showInstallWarning = false
                    onNavigateToBuilder(null)
                }) {
                    Text("I understand")
                }
            },
            dismissButton = {
                TextButton(onClick = { showInstallWarning = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { skillsViewModel.updateSearch(it) },
                label = { Text("Search skills") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text =
                    "Skills extend the agent with external API tools. " +
                        "HTTP Request must be enabled in Hub for skills to make API calls.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(modifier = Modifier.height(8.dp))

            when (val state = filteredState) {
                is SkillsUiState.Loading -> {
                    LoadingIndicator(
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                }
                is SkillsUiState.Error -> {
                    ErrorCard(
                        message = state.detail,
                        onRetry = { skillsViewModel.loadSkills() },
                    )
                }
                is SkillsUiState.Content -> {
                    if (state.data.isEmpty()) {
                        EmptyState(
                            icon = Icons.Outlined.AutoFixHigh,
                            message =
                                if (searchQuery.isBlank()) {
                                    "No community skills yet. Tap + to create" +
                                        " one or fetch from ClawHub."
                                } else {
                                    "No skills match your search"
                                },
                        )
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(
                                items = state.data,
                                key = { it.name },
                                contentType = { "skill" },
                            ) { skill ->
                                SkillCard(
                                    skill = skill,
                                    onRemove = {
                                        pendingRemoval = skill.name
                                    },
                                    onToggle = { enabled ->
                                        skillsViewModel.toggleSkill(
                                            skill.name,
                                            enabled,
                                        )
                                    },
                                    onClick = {
                                        onNavigateToBuilder(skill.name)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
        FloatingActionButton(
            onClick = {
                if (warningSeen) {
                    onNavigateToBuilder(null)
                } else {
                    showInstallWarning = true
                }
            },
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                    .semantics {
                        contentDescription = "Create new skill"
                    },
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Icon(
                imageVector = Icons.Outlined.Add,
                contentDescription = null,
            )
        }
    }
}

/**
 * Card displaying a single skill with metadata and action controls.
 *
 * Shows the skill name, description, version, author, tags, tool count,
 * a toggle switch, and a delete button. Disabled skills are rendered with
 * reduced opacity. Tool names are shown as badges up to [MAX_TOOL_BADGES].
 * Community skills show a tinted badge and a contextual tool count label.
 *
 * @param skill The skill to display.
 * @param onRemove Callback to remove this skill.
 * @param onToggle Callback when the enabled toggle is changed.
 * @param onClick Callback when the card is tapped.
 * @param modifier Modifier applied to the card.
 */
@Composable
private fun SkillCard(
    skill: Skill,
    onRemove: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val toolCountText =
        if (skill.isCommunity && skill.toolCount == 0) {
            "Tools in skill prompt"
        } else {
            "${skill.toolCount} tools"
        }
    Card(
        onClick = onClick,
        modifier =
            modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .alpha(if (skill.isEnabled) 1f else 0.5f)
                .semantics(mergeDescendants = true) {
                    contentDescription =
                        "${skill.name}: ${skill.description}, " +
                        "$toolCountText, version ${skill.version}" +
                        if (skill.isCommunity) ", community skill" else ""
                },
        colors =
            CardDefaults.cardColors(
                containerColor =
                    MaterialTheme.colorScheme.surfaceContainerLow,
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
                        text = skill.name,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = skill.description,
                        style = MaterialTheme.typography.bodySmall,
                        color =
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Switch(
                    checked = skill.isEnabled,
                    onCheckedChange = onToggle,
                    modifier =
                        Modifier.semantics {
                            contentDescription =
                                "Toggle skill ${skill.name}"
                        },
                )
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(
                    onClick = onRemove,
                    modifier =
                        Modifier
                            .defaultMinSize(
                                minWidth = 48.dp,
                                minHeight = 48.dp,
                            ).semantics {
                                contentDescription =
                                    "Remove skill ${skill.name}"
                            },
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "v${skill.version}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                skill.author?.let { author ->
                    Text(
                        text = " \u2022 $author",
                        style = MaterialTheme.typography.labelSmall,
                        color =
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = toolCountText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (skill.isCommunity) {
                    Text(
                        text = "Community",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier =
                            Modifier
                                .background(
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    shape = MaterialTheme.shapes.extraSmall,
                                ).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }

            if (skill.tags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    skill.tags.forEach { tag ->
                        CategoryBadge(category = tag)
                    }
                }
            }

            if (skill.toolNames.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                val displayNames = skill.toolNames.take(MAX_TOOL_BADGES)
                val remaining = skill.toolNames.size - displayNames.size
                Text(
                    text =
                        displayNames.joinToString(", ") +
                            if (remaining > 0) {
                                " +$remaining more"
                            } else {
                                ""
                            },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
