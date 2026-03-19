/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.plugins

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zeroclaw.android.data.email.EmailConfigState

/** Maximum number of scheduled check times allowed. */
private const val MAX_CHECK_TIMES = 5

/** Default time added when the user taps "Add Check Time". */
private const val DEFAULT_CHECK_TIME = "09:00"

/**
 * Configuration screen for the agent email integration.
 *
 * Displays IMAP/SMTP connection fields, password input, scheduled check
 * times, and a connection test button. Does not include an inbox viewer.
 *
 * @param onNavigateBack Called when the user presses the back button.
 * @param viewModel The [EmailConfigViewModel] managing screen state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmailConfigScreen(
    onNavigateBack: () -> Unit,
    viewModel: EmailConfigViewModel = viewModel(),
) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val testResult by viewModel.testResult.collectAsStateWithLifecycle()
    val isSaving by viewModel.isSaving.collectAsStateWithLifecycle()

    var draft by remember(config) { mutableStateOf(config) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Email") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Navigate back",
                        )
                    }
                },
                actions = {
                    val enabledDesc =
                        if (draft.isEnabled) "enabled" else "disabled"
                    Switch(
                        checked = draft.isEnabled,
                        onCheckedChange = { draft = draft.copy(isEnabled = it) },
                        modifier =
                            Modifier
                                .padding(end = 8.dp)
                                .semantics {
                                    contentDescription = "Email integration"
                                    stateDescription = enabledDesc
                                },
                    )
                },
            )
        },
    ) { innerPadding ->
        EmailConfigContent(
            draft = draft,
            onDraftChange = { draft = it },
            testResult = testResult,
            isSaving = isSaving,
            onTest = { viewModel.testConnection(draft) },
            onSave = { viewModel.save(draft) },
            modifier = Modifier.padding(innerPadding),
        )
    }
}

/**
 * Stateless content for the email configuration screen.
 *
 * @param draft Current local editing state.
 * @param onDraftChange Callback when the user edits a field.
 * @param testResult Result message from the last connection test.
 * @param isSaving Whether a save operation is in progress.
 * @param onTest Callback to trigger a connection test.
 * @param onSave Callback to persist the current draft.
 * @param modifier Modifier applied to the root layout.
 */
@Composable
private fun EmailConfigContent(
    draft: EmailConfigState,
    onDraftChange: (EmailConfigState) -> Unit,
    testResult: String?,
    isSaving: Boolean,
    onTest: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Connection",
            style = MaterialTheme.typography.titleMedium,
        )

        OutlinedTextField(
            value = draft.imapHost,
            onValueChange = { onDraftChange(draft.copy(imapHost = it)) },
            label = { Text("IMAP Host") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = draft.imapPort.toString(),
            onValueChange = { text ->
                text.toIntOrNull()?.let { port ->
                    onDraftChange(draft.copy(imapPort = port))
                }
            },
            label = { Text("IMAP Port") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = draft.smtpHost,
            onValueChange = { onDraftChange(draft.copy(smtpHost = it)) },
            label = { Text("SMTP Host") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = draft.smtpPort.toString(),
            onValueChange = { text ->
                text.toIntOrNull()?.let { port ->
                    onDraftChange(draft.copy(smtpPort = port))
                }
            },
            label = { Text("SMTP Port") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = draft.address,
            onValueChange = { onDraftChange(draft.copy(address = it)) },
            label = { Text("Email Address") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = draft.password,
            onValueChange = { onDraftChange(draft.copy(password = it)) },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            text =
                "For Gmail or Outlook, use an app-specific password " +
                    "rather than your account password.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(4.dp))

        OutlinedButton(
            onClick = onTest,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Test Connection")
        }

        if (testResult != null) {
            Text(
                text = testResult,
                style = MaterialTheme.typography.bodyMedium,
                color =
                    if (testResult.startsWith("Connection failed") ||
                        testResult.startsWith("Save failed")
                    ) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
            )
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Schedule",
            style = MaterialTheme.typography.titleMedium,
        )

        Text(
            text = "Check email at these times (24-hour format, device timezone).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        draft.checkTimes.forEachIndexed { index, time ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = time,
                    onValueChange = { newTime ->
                        val updated = draft.checkTimes.toMutableList()
                        updated[index] = newTime
                        onDraftChange(draft.copy(checkTimes = updated))
                    },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    label = { Text("Time ${index + 1}") },
                )
                IconButton(
                    onClick = {
                        val updated = draft.checkTimes.toMutableList()
                        updated.removeAt(index)
                        onDraftChange(draft.copy(checkTimes = updated))
                    },
                    modifier =
                        Modifier.semantics {
                            contentDescription = "Remove check time ${index + 1}"
                        },
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = null,
                    )
                }
            }
        }

        if (draft.checkTimes.size < MAX_CHECK_TIMES) {
            OutlinedButton(
                onClick = {
                    val updated = draft.checkTimes + DEFAULT_CHECK_TIME
                    onDraftChange(draft.copy(checkTimes = updated))
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text("Add Check Time")
            }
        }

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = onSave,
            enabled = !isSaving,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(48.dp),
        ) {
            Text(if (isSaving) "Saving..." else "Save")
        }
    }
}
