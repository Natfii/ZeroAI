/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

@file:Suppress("TooManyFunctions")

package com.zeroclaw.android.ui.screen.settings.ssh

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zeroclaw.android.data.ssh.SshKeyEntry
import com.zeroclaw.android.ui.component.EmptyState
import com.zeroclaw.ffi.SshKeyAlgorithm
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/** Minimum touch target size in dp. */
private const val MIN_TOUCH_TARGET_DP = 48

/** Standard horizontal padding in dp. */
private const val EDGE_PADDING_DP = 16

/** Standard vertical spacing between items in dp. */
private const val ITEM_SPACING_DP = 8

/** Inner card padding in dp. */
private const val CARD_PADDING_DP = 16

/** Bottom sheet content padding in dp. */
private const val SHEET_PADDING_DP = 24

/** Section heading spacing in dp. */
private const val SECTION_SPACING_DP = 16

/** Maximum characters allowed for a key label. */
private const val MAX_LABEL_LENGTH = 64

/** Small spacing between inline elements in dp. */
private const val INLINE_SPACING_DP = 8

/** Spacing between FAB buttons in dp. */
private const val FAB_SPACING_DP = 12

/**
 * SSH key management screen.
 *
 * Displays a list of stored SSH keys with options to generate new
 * keys, import existing keys from files, view key details in a bottom
 * sheet, and delete keys. Includes empty state with guided actions
 * and accessibility support for all interactive elements.
 *
 * @param onBack Callback invoked when the back navigation button is pressed.
 * @param viewModel ViewModel providing SSH key state and actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SshKeyScreen(
    onBack: () -> Unit,
    viewModel: SshKeyViewModel = viewModel(),
) {
    val keys by viewModel.keys.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showGenerateDialog by remember { mutableStateOf(false) }
    var showImportLabelDialog by remember { mutableStateOf(false) }
    var pendingImportUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var showPassphraseDialog by remember { mutableStateOf(false) }
    var pendingImportLabel by remember { mutableStateOf("") }
    var detailKey by remember { mutableStateOf<SshKeyEntry?>(null) }

    val filePicker =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) {
                pendingImportUri = uri
                showImportLabelDialog = true
            }
        }

    LaunchedEffect(error) {
        error?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SSH Keys") },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier =
                            Modifier.semantics {
                                contentDescription = "Navigate back"
                            },
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        floatingActionButton = {
            if (keys.isNotEmpty()) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(FAB_SPACING_DP.dp),
                ) {
                    SmallFloatingActionButton(
                        onClick = { filePicker.launch(arrayOf("*/*")) },
                        modifier =
                            Modifier.semantics {
                                contentDescription = "Import SSH key from file"
                            },
                    ) {
                        Icon(Icons.Outlined.FileUpload, contentDescription = null)
                    }
                    SmallFloatingActionButton(
                        onClick = { showGenerateDialog = true },
                        modifier =
                            Modifier.semantics {
                                contentDescription = "Generate new SSH key"
                            },
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                    }
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                )
            } else if (keys.isEmpty()) {
                SshEmptyState(
                    onGenerate = { showGenerateDialog = true },
                    onImport = { filePicker.launch(arrayOf("*/*")) },
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(ITEM_SPACING_DP.dp),
                    contentPadding =
                        androidx.compose.foundation.layout.PaddingValues(
                            horizontal = EDGE_PADDING_DP.dp,
                            vertical = ITEM_SPACING_DP.dp,
                        ),
                ) {
                    items(
                        items = keys,
                        key = { it.keyId },
                        contentType = { "ssh_key" },
                    ) { entry ->
                        SshKeyCard(
                            entry = entry,
                            onClick = { detailKey = entry },
                        )
                    }
                }
            }
        }
    }

    if (showGenerateDialog) {
        GenerateKeyDialog(
            onDismiss = { showGenerateDialog = false },
            onConfirm = { algorithm, label ->
                showGenerateDialog = false
                viewModel.generateKey(algorithm, label)
            },
        )
    }

    if (showImportLabelDialog) {
        ImportLabelDialog(
            onDismiss = {
                showImportLabelDialog = false
                pendingImportUri = null
            },
            onConfirm = { label ->
                showImportLabelDialog = false
                pendingImportLabel = label
                val uri = pendingImportUri ?: return@ImportLabelDialog
                viewModel.importKey(uri, null, label)
                pendingImportUri = null
            },
            onPassphraseNeeded = { label ->
                showImportLabelDialog = false
                pendingImportLabel = label
                showPassphraseDialog = true
            },
        )
    }

    if (showPassphraseDialog) {
        PassphraseDialog(
            onDismiss = {
                showPassphraseDialog = false
                pendingImportUri = null
                pendingImportLabel = ""
            },
            onConfirm = { passphrase ->
                showPassphraseDialog = false
                val uri = pendingImportUri ?: return@PassphraseDialog
                viewModel.importKey(uri, passphrase, pendingImportLabel)
                pendingImportUri = null
                pendingImportLabel = ""
            },
        )
    }

    detailKey?.let { entry ->
        KeyDetailBottomSheet(
            entry = entry,
            viewModel = viewModel,
            onDismiss = { detailKey = null },
            onDeleted = {
                detailKey = null
                scope.launch { snackbarHostState.showSnackbar("Key deleted") }
            },
        )
    }
}

/**
 * Empty state shown when no SSH keys are stored.
 *
 * Displays a centered message with Generate and Import action buttons
 * to guide the user toward creating their first key.
 *
 * @param onGenerate Callback invoked when the Generate button is tapped.
 * @param onImport Callback invoked when the Import button is tapped.
 */
@Composable
private fun SshEmptyState(
    onGenerate: () -> Unit,
    onImport: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        EmptyState(
            icon = Icons.Outlined.Key,
            message = "No SSH keys yet",
            modifier = Modifier.weight(1f),
        )
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = EDGE_PADDING_DP.dp,
                        vertical = SECTION_SPACING_DP.dp,
                    ),
            horizontalArrangement =
                Arrangement.spacedBy(
                    ITEM_SPACING_DP.dp,
                    Alignment.CenterHorizontally,
                ),
        ) {
            OutlinedButton(
                onClick = onGenerate,
                modifier =
                    Modifier
                        .defaultMinSize(minHeight = MIN_TOUCH_TARGET_DP.dp)
                        .semantics { contentDescription = "Generate new SSH key" },
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(INLINE_SPACING_DP.dp))
                Text("Generate")
            }
            OutlinedButton(
                onClick = onImport,
                modifier =
                    Modifier
                        .defaultMinSize(minHeight = MIN_TOUCH_TARGET_DP.dp)
                        .semantics { contentDescription = "Import SSH key from file" },
            ) {
                Icon(Icons.Outlined.FileUpload, contentDescription = null)
                Spacer(Modifier.width(INLINE_SPACING_DP.dp))
                Text("Import")
            }
        }
    }
}

/**
 * Card displaying summary information for a single SSH key.
 *
 * Shows the algorithm badge, user-assigned label, SHA-256 fingerprint
 * in monospace, and creation date. Tapping the card opens the detail
 * bottom sheet.
 *
 * @param entry SSH key metadata to display.
 * @param onClick Callback invoked when the card is tapped.
 */
@Composable
private fun SshKeyCard(
    entry: SshKeyEntry,
    onClick: () -> Unit,
) {
    val algorithmBadge =
        when (entry.algorithm) {
            "ed25519" -> "ED"
            "rsa4096" -> "RSA"
            else -> entry.algorithm.uppercase(Locale.ROOT)
        }

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(
                    onClickLabel = "View key details for ${entry.label}",
                    onClick = onClick,
                ).semantics {
                    contentDescription =
                        "$algorithmBadge key, ${entry.label}"
                },
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(CARD_PADDING_DP.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ITEM_SPACING_DP.dp),
        ) {
            Card(
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
            ) {
                Text(
                    text = algorithmBadge,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier =
                        Modifier.padding(
                            horizontal = INLINE_SPACING_DP.dp,
                            vertical = 4.dp,
                        ),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.label,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                )
                Text(
                    text = entry.fingerprintSha256,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                )
                Text(
                    text = formatEpochMs(entry.createdAtEpochMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Dialog for generating a new SSH key.
 *
 * Presents algorithm selection via [FilterChip]s (Ed25519 default,
 * RSA-4096) and a required label field. The Generate button is
 * disabled until a non-empty label is entered.
 *
 * @param onDismiss Callback invoked when the dialog is dismissed.
 * @param onConfirm Callback invoked with the selected algorithm and label.
 */
@Composable
private fun GenerateKeyDialog(
    onDismiss: () -> Unit,
    onConfirm: (SshKeyAlgorithm, String) -> Unit,
) {
    var algorithm by remember { mutableStateOf(SshKeyAlgorithm.ED25519) }
    var label by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Generate SSH Key") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(ITEM_SPACING_DP.dp),
            ) {
                Text(
                    text = "Algorithm",
                    style = MaterialTheme.typography.labelLarge,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(INLINE_SPACING_DP.dp),
                ) {
                    FilterChip(
                        selected = algorithm == SshKeyAlgorithm.ED25519,
                        onClick = { algorithm = SshKeyAlgorithm.ED25519 },
                        label = { Text("Ed25519") },
                        modifier =
                            Modifier
                                .defaultMinSize(minHeight = MIN_TOUCH_TARGET_DP.dp)
                                .semantics {
                                    contentDescription = "Select Ed25519 algorithm"
                                },
                    )
                    FilterChip(
                        selected = algorithm == SshKeyAlgorithm.RSA4096,
                        onClick = { algorithm = SshKeyAlgorithm.RSA4096 },
                        label = { Text("RSA-4096") },
                        modifier =
                            Modifier
                                .defaultMinSize(minHeight = MIN_TOUCH_TARGET_DP.dp)
                                .semantics {
                                    contentDescription = "Select RSA-4096 algorithm"
                                },
                    )
                }
                OutlinedTextField(
                    value = label,
                    onValueChange = { if (it.length <= MAX_LABEL_LENGTH) label = it },
                    label = { Text("Label") },
                    placeholder = { Text("e.g. Work Laptop") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = {
                        Text("${label.length}/$MAX_LABEL_LENGTH")
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(algorithm, label.trim()) },
                enabled = label.isNotBlank(),
            ) {
                Text("Generate")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

/**
 * Dialog for assigning a label to an imported SSH key.
 *
 * Shown after the user selects a file via the SAF file picker.
 * Provides an option to enter a passphrase if the key is encrypted.
 *
 * @param onDismiss Callback invoked when the dialog is dismissed.
 * @param onConfirm Callback invoked with the entered label for a non-encrypted key.
 * @param onPassphraseNeeded Callback invoked with the label when the user
 *   indicates the key requires a passphrase.
 */
@Composable
private fun ImportLabelDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    onPassphraseNeeded: (String) -> Unit,
) {
    var label by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import SSH Key") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(ITEM_SPACING_DP.dp),
            ) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { if (it.length <= MAX_LABEL_LENGTH) label = it },
                    label = { Text("Label") },
                    placeholder = { Text("e.g. Server Key") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = {
                        Text("${label.length}/$MAX_LABEL_LENGTH")
                    },
                )
                TextButton(
                    onClick = { onPassphraseNeeded(label.trim()) },
                    enabled = label.isNotBlank(),
                    modifier =
                        Modifier.semantics {
                            contentDescription = "Key requires a passphrase"
                        },
                ) {
                    Text("Key has passphrase...")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(label.trim()) },
                enabled = label.isNotBlank(),
            ) {
                Text("Import")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

/**
 * Dialog for entering a passphrase to decrypt an imported SSH key.
 *
 * Uses [CharArray] for the passphrase value and zeroes it on dismiss
 * to minimize in-memory exposure of sensitive material.
 *
 * @param onDismiss Callback invoked when the dialog is dismissed.
 * @param onConfirm Callback invoked with the passphrase as a [CharArray].
 */
@Composable
private fun PassphraseDialog(
    onDismiss: () -> Unit,
    onConfirm: (CharArray) -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = {
            passphrase.toCharArray().fill('\u0000')
            onDismiss()
        },
        title = { Text("Enter Passphrase") },
        text = {
            OutlinedTextField(
                value = passphrase,
                onValueChange = { passphrase = it },
                label = { Text("Passphrase") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val chars = passphrase.toCharArray()
                    passphrase = ""
                    onConfirm(chars)
                },
                enabled = passphrase.isNotEmpty(),
            ) {
                Text("Unlock")
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    passphrase.toCharArray().fill('\u0000')
                    onDismiss()
                },
            ) {
                Text("Cancel")
            }
        },
    )
}

/**
 * Bottom sheet showing full detail for a selected SSH key.
 *
 * Displays the public key in a selectable monospace container with a
 * copy button, algorithm, full fingerprint, and creation date. Includes
 * a delete button with confirmation dialog.
 *
 * @param entry SSH key metadata to display.
 * @param viewModel ViewModel for key actions (getPublicKey, deleteKey).
 * @param onDismiss Callback invoked when the sheet is dismissed.
 * @param onDeleted Callback invoked after a key is successfully deleted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KeyDetailBottomSheet(
    entry: SshKeyEntry,
    viewModel: SshKeyViewModel,
    onDismiss: () -> Unit,
    onDeleted: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var publicKey by remember { mutableStateOf<String?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(entry.keyId) {
        publicKey = viewModel.getPublicKey(entry.keyId)
    }

    val algorithmLabel =
        when (entry.algorithm) {
            "ed25519" -> "Ed25519"
            "rsa4096" -> "RSA-4096"
            else -> entry.algorithm
        }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SHEET_PADDING_DP.dp)
                    .padding(bottom = SHEET_PADDING_DP.dp)
                    .semantics { paneTitle = "SSH key detail for ${entry.label}" },
            verticalArrangement = Arrangement.spacedBy(SECTION_SPACING_DP.dp),
        ) {
            Text(
                text = entry.label,
                style = MaterialTheme.typography.titleLarge,
            )

            DetailRow(label = "Algorithm", value = algorithmLabel)
            DetailRow(
                label = "Fingerprint",
                value = entry.fingerprintSha256,
                monospace = true,
            )
            DetailRow(
                label = "Created",
                value = formatEpochMs(entry.createdAtEpochMs),
            )

            Spacer(Modifier.height(INLINE_SPACING_DP.dp))

            Text(
                text = "Public Key",
                style = MaterialTheme.typography.labelLarge,
            )

            if (publicKey != null) {
                Card(
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                ) {
                    Column(
                        modifier = Modifier.padding(CARD_PADDING_DP.dp),
                    ) {
                        SelectionContainer {
                            Text(
                                text = publicKey.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                            )
                        }
                        Spacer(Modifier.height(INLINE_SPACING_DP.dp))
                        OutlinedButton(
                            onClick = {
                                copyPublicKeyToClipboard(
                                    context,
                                    publicKey.orEmpty(),
                                )
                                scope.launch {
                                    onDismiss()
                                }
                            },
                            modifier =
                                Modifier
                                    .defaultMinSize(minHeight = MIN_TOUCH_TARGET_DP.dp)
                                    .semantics {
                                        contentDescription =
                                            "Copy public key to clipboard"
                                    },
                        ) {
                            Icon(
                                Icons.Filled.ContentCopy,
                                contentDescription = null,
                            )
                            Spacer(Modifier.width(INLINE_SPACING_DP.dp))
                            Text("Copy")
                        }
                    }
                }
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }

            Spacer(Modifier.height(INLINE_SPACING_DP.dp))

            TextButton(
                onClick = { showDeleteConfirm = true },
                modifier =
                    Modifier
                        .defaultMinSize(minHeight = MIN_TOUCH_TARGET_DP.dp)
                        .semantics {
                            contentDescription = "Delete key ${entry.label}"
                        },
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.width(INLINE_SPACING_DP.dp))
                Text(
                    text = "Delete Key",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    if (showDeleteConfirm) {
        DeleteConfirmDialog(
            label = entry.label,
            onDismiss = { showDeleteConfirm = false },
            onConfirm = {
                showDeleteConfirm = false
                viewModel.deleteKey(entry.keyId)
                onDeleted()
            },
        )
    }
}

/**
 * Labeled detail row used in the key detail bottom sheet.
 *
 * @param label Row label text.
 * @param value Row value text.
 * @param monospace Whether to render the value in monospace font.
 */
@Composable
private fun DetailRow(
    label: String,
    value: String,
    monospace: Boolean = false,
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
            modifier =
                if (monospace) {
                    Modifier.horizontalScroll(rememberScrollState())
                } else {
                    Modifier
                },
        )
    }
}

/**
 * Confirmation dialog for deleting an SSH key.
 *
 * @param label Label of the key being deleted, shown in the message.
 * @param onDismiss Callback invoked when the dialog is dismissed.
 * @param onConfirm Callback invoked when deletion is confirmed.
 */
@Composable
private fun DeleteConfirmDialog(
    label: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete Key?") },
        text = {
            Text(
                "Permanently delete \"$label\"? " +
                    "Servers using this key will no longer accept connections.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = "Delete",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

/**
 * Copies the SSH public key to the clipboard with a sensitive-data flag.
 *
 * Sets `android.content.extra.IS_SENSITIVE` to prevent the clipboard
 * content from appearing in text prediction or keyboard learning.
 *
 * @param context Android context for accessing the clipboard service.
 * @param publicKey Public key string to copy.
 */
private fun copyPublicKeyToClipboard(
    context: Context,
    publicKey: String,
) {
    val clipboardManager =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clipData = ClipData.newPlainText("SSH Public Key", publicKey)
    clipData.description.extras =
        PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
    clipboardManager.setPrimaryClip(clipData)
}

/**
 * Formats an epoch-millisecond timestamp as a `yyyy-MM-dd` date string.
 *
 * Uses [SimpleDateFormat] for API 28 compatibility.
 *
 * @param epochMs Timestamp in milliseconds since Unix epoch.
 * @return Formatted date string.
 */
private fun formatEpochMs(epochMs: Long): String {
    val format = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    return format.format(Date(epochMs))
}
