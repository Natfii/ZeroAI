/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.onboarding.steps.personality

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Available roles displayed as filter chips. */
private val Roles =
    listOf(
        "Assistant" to "assistant",
        "Companion" to "companion",
        "Mentor" to "mentor",
        "Rival" to "rival",
    )

/** Vertical spacing between sections. */
private val SectionSpacing = 16.dp

/** Spacing between role chips. */
private val ChipSpacing = 8.dp

/**
 * Personality sub-step 1: agent name and role selection.
 *
 * Renders an [OutlinedTextField] for the agent name (required) and a
 * [FlowRow] of [FilterChip] items for role selection. Shows an error
 * hint when the name is blank after the user has edited the field.
 *
 * @param agentName Current agent name value.
 * @param role Current role value (lowercase).
 * @param onAgentNameChanged Callback when the agent name changes.
 * @param onRoleChanged Callback when the role selection changes.
 * @param modifier Modifier applied to the root layout.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NameRoleScreen(
    agentName: String,
    role: String,
    onAgentNameChanged: (String) -> Unit,
    onRoleChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var hasEdited by rememberSaveable { mutableStateOf(false) }
    val showError = hasEdited && agentName.isBlank()

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(SectionSpacing),
    ) {
        Text(
            text = "Name Your Agent",
            style = MaterialTheme.typography.headlineMedium,
        )

        Text(
            text = "Give your AI a name and choose its role",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = agentName,
            onValueChange = {
                hasEdited = true
                onAgentNameChanged(it)
            },
            label = { Text("Agent name") },
            isError = showError,
            supportingText =
                if (showError) {
                    { Text("Name is required") }
                } else {
                    null
                },
            singleLine = true,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = "Agent name input"
                    },
        )

        Spacer(modifier = Modifier.height(ChipSpacing))

        Text(
            text = "Role",
            style = MaterialTheme.typography.titleMedium,
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(ChipSpacing),
            verticalArrangement = Arrangement.spacedBy(ChipSpacing),
        ) {
            Roles.forEach { (displayName, value) ->
                FilterChip(
                    selected = role == value,
                    onClick = { onRoleChanged(value) },
                    label = { Text(displayName) },
                    modifier =
                        Modifier.semantics {
                            contentDescription = "$displayName role"
                        },
                )
            }
        }
    }
}
