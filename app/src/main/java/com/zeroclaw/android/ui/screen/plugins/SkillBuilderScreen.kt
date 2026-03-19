/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.plugins

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Skill builder screen for creating and editing community skills.
 *
 * Provides fields for skill name, optional ClawHub URL with fetch
 * functionality, and a monospace editor for SKILL.md content.
 *
 * @param skillName Name of the skill to edit, or null for a new skill.
 * @param edgeMargin Horizontal padding based on window width size class.
 * @param onNavigateBack Callback to navigate back after save.
 * @param skillBuilderViewModel ViewModel providing state and actions.
 * @param modifier Modifier applied to the root layout.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillBuilderScreen(
    skillName: String?,
    edgeMargin: Dp,
    onNavigateBack: () -> Unit,
    skillBuilderViewModel: SkillBuilderViewModel = viewModel(),
    modifier: Modifier = Modifier,
) {
    val name by skillBuilderViewModel.name
        .collectAsStateWithLifecycle()
    val url by skillBuilderViewModel.url
        .collectAsStateWithLifecycle()
    val content by skillBuilderViewModel.content
        .collectAsStateWithLifecycle()
    val isFetching by skillBuilderViewModel.isFetching
        .collectAsStateWithLifecycle()
    val result by skillBuilderViewModel.result
        .collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val canSave = name.isNotBlank() && content.isNotBlank()

    LaunchedEffect(result) {
        when (val r = result) {
            is SkillBuilderResult.Success -> {
                snackbarHostState.showSnackbar("Skill saved")
                skillBuilderViewModel.clearResult()
                onNavigateBack()
            }
            is SkillBuilderResult.Error -> {
                snackbarHostState.showSnackbar(r.message)
                skillBuilderViewModel.clearResult()
            }
            null -> {}
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (skillName != null) {
                            "Edit Skill"
                        } else {
                            "New Skill"
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector =
                                Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Navigate back",
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            skillBuilderViewModel.saveSkill()
                        },
                        enabled = canSave,
                        modifier =
                            Modifier.semantics {
                                contentDescription = "Save skill"
                            },
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Check,
                            contentDescription = null,
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier,
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = edgeMargin)
                    .imePadding(),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = {
                    skillBuilderViewModel.updateName(it)
                },
                label = { Text("Skill Name") },
                singleLine = true,
                readOnly = isFetching,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = url,
                onValueChange = {
                    skillBuilderViewModel.updateUrl(it)
                },
                label = { Text("ClawHub URL (optional)") },
                placeholder = {
                    Text("https://clawhub.ai/owner/skill-name")
                },
                singleLine = true,
                readOnly = isFetching,
                modifier = Modifier.fillMaxWidth(),
            )

            if (isFetching) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(top = 8.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                TextButton(
                    onClick = { skillBuilderViewModel.fetchSkill() },
                    enabled = url.isNotBlank(),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Text("Fetch")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = content,
                onValueChange = {
                    skillBuilderViewModel.updateContent(it)
                },
                label = { Text("Skill Content") },
                readOnly = isFetching,
                textStyle =
                    TextStyle(
                        fontFamily = FontFamily.Monospace,
                    ),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
            )
        }
    }
}
