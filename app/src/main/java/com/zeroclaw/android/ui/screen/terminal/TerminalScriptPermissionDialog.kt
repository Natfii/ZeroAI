/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.terminal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Capability review dialog shown before running a packaged workspace script.
 *
 * The dialog mirrors the Rust capability model by listing every requested
 * capability and letting the user grant or deny each one explicitly before
 * execution starts.
 *
 * @param request Pending script permission request.
 * @param onToggleCapability Callback toggling a single capability.
 * @param onGrantAll Callback granting all requested capabilities.
 * @param onDenyAll Callback denying all requested capabilities.
 * @param onConfirm Callback confirming execution with the selected grant set.
 * @param onDismiss Callback dismissing the dialog without running the script.
 */
@Composable
internal fun TerminalScriptPermissionDialog(
    request: TerminalScriptPermissionRequest,
    onToggleCapability: (String) -> Unit,
    onGrantAll: () -> Unit,
    onDenyAll: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        title = { Text("Review script permissions") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = request.manifestName,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Path: ${request.relativePath}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Runtime: ${request.runtime}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (request.warnings.isNotEmpty()) {
                    DialogSection(
                        title = "Warnings",
                        items = request.warnings,
                    )
                }

                if (request.missingCapabilities.isNotEmpty()) {
                    DialogSection(
                        title = "Missing from manifest",
                        items = request.missingCapabilities,
                    )
                }

                if (request.requestedCapabilities.isEmpty()) {
                    Text(
                        text = "This script does not request any host capabilities.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                } else {
                    Text(
                        text = "Grant capabilities for this run",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onGrantAll) {
                            Text("Grant all")
                        }
                        TextButton(onClick = onDenyAll) {
                            Text("Deny all")
                        }
                    }
                    request.requestedCapabilities.forEach { capability ->
                        CapabilityToggleRow(
                            capability = capability,
                            granted = capability in request.grantedCapabilities,
                            onToggle = { onToggleCapability(capability) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Run script")
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
 * Section inside the script permission dialog.
 *
 * @param title Section heading.
 * @param items Section body items rendered as bullet lines.
 */
@Composable
private fun DialogSection(
    title: String,
    items: List<String>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        items.forEach { item ->
            Text(
                text = "\u2022 $item",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Row allowing the user to grant or deny one capability for the pending run.
 *
 * @param capability Capability name.
 * @param granted Whether the capability is currently granted.
 * @param onToggle Callback toggling the capability state.
 */
@Composable
private fun CapabilityToggleRow(
    capability: String,
    granted: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .clickable(onClick = onToggle)
                .semantics {
                    contentDescription =
                        "$capability permission ${if (granted) "granted" else "denied"}"
                },
    ) {
        Checkbox(
            checked = granted,
            onCheckedChange = { onToggle() },
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = capability,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
