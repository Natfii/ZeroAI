/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.terminal

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.util.Arrays

/** Vertical spacing between sections in the host key dialog body. */
private const val SECTION_SPACING_DP = 8

/** Padding inside the warning card in the host key dialog. */
private const val WARNING_CARD_PADDING_DP = 12

/**
 * Dialog prompting the user for an SSH password.
 *
 * The password is stored as a [CharArray] rather than a [String] to
 * allow explicit clearing from memory after use. On submit the array
 * is passed to [onSubmit] and immediately zeroed. On dismiss the
 * array is also zeroed to prevent leaks.
 *
 * @param onSubmit Callback receiving the password as a [CharArray].
 *   The caller is responsible for zeroing the array after use.
 * @param onDismiss Callback when the dialog is dismissed without submitting.
 */
@Composable
fun TtyPasswordDialog(
    onSubmit: (CharArray) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf(charArrayOf()) }

    DisposableEffect(Unit) {
        onDispose {
            Arrays.fill(password, '\u0000')
        }
    }

    AlertDialog(
        onDismissRequest = {
            Arrays.fill(password, '\u0000')
            onDismiss()
        },
        title = { Text("SSH Password") },
        text = {
            OutlinedTextField(
                value = String(password),
                onValueChange = { newValue ->
                    Arrays.fill(password, '\u0000')
                    password = newValue.toCharArray()
                },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .semantics {
                            contentDescription = "SSH password input"
                        },
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val submitted = password.copyOf()
                    Arrays.fill(password, '\u0000')
                    password = charArrayOf()
                    onSubmit(submitted)
                },
                enabled = password.isNotEmpty(),
            ) {
                Text("Connect")
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    Arrays.fill(password, '\u0000')
                    onDismiss()
                },
            ) {
                Text("Cancel")
            }
        },
    )
}

/**
 * Dialog displaying an SSH host key fingerprint for user verification.
 *
 * When [isChanged] is true the dialog shows a prominent red warning
 * card alerting the user that the host key has changed since the last
 * connection, which may indicate a man-in-the-middle attack. The
 * accept button is styled with the error color scheme and labelled
 * "Replace Trusted Key" to emphasise the destructive action.
 *
 * When [isChanged] is false the dialog shows a neutral verification
 * prompt for a first-time connection with a "Trust" accept button.
 *
 * The fingerprint and algorithm are rendered in a monospace font for
 * easy visual comparison.
 *
 * @param host Remote hostname or IP address.
 * @param port Remote SSH port number.
 * @param algorithm Key algorithm name (e.g. "ssh-ed25519").
 * @param fingerprint SHA-256 fingerprint of the host key.
 * @param isChanged Whether the host key differs from the previously
 *   trusted key for this host.
 * @param onAccept Callback when the user accepts or replaces the key.
 * @param onReject Callback when the user rejects the key.
 */
@Composable
fun TtyHostKeyDialog(
    host: String,
    port: Int,
    algorithm: String,
    fingerprint: String,
    isChanged: Boolean,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onReject,
        modifier =
            Modifier.semantics {
                liveRegion = LiveRegionMode.Polite
            },
        title = {
            Text(
                text =
                    if (isChanged) {
                        "HOST KEY HAS CHANGED"
                    } else {
                        "Verify Host Key"
                    },
                color =
                    if (isChanged) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
            )
        },
        text = {
            Column {
                if (isChanged) {
                    HostKeyWarningCard()
                    Spacer(modifier = Modifier.height(SECTION_SPACING_DP.dp))
                }

                HostKeyDetailRow(label = "Host", value = "$host:$port")
                Spacer(modifier = Modifier.height(SECTION_SPACING_DP.dp))
                HostKeyDetailRow(label = "Algorithm", value = algorithm)
                Spacer(modifier = Modifier.height(SECTION_SPACING_DP.dp))
                Text(
                    text = "Fingerprint",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = fingerprint,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier =
                        Modifier.semantics {
                            contentDescription =
                                "Host key fingerprint: $fingerprint"
                        },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onAccept,
                modifier =
                    Modifier.semantics {
                        contentDescription =
                            if (isChanged) {
                                "Replace trusted key for $host"
                            } else {
                                "Trust host key for $host"
                            }
                    },
            ) {
                Text(
                    text =
                        if (isChanged) {
                            "Replace Trusted Key"
                        } else {
                            "Trust"
                        },
                    color =
                        if (isChanged) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onReject) {
                Text("Cancel")
            }
        },
    )
}

/**
 * Warning card shown when an SSH host key has changed unexpectedly.
 *
 * Uses [MaterialTheme.colorScheme.errorContainer] as the background
 * to draw attention to the potentially dangerous situation.
 */
@Composable
private fun HostKeyWarningCard() {
    Card(
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription =
                        "Warning: the host key has changed. " +
                        "This could indicate a security threat."
                },
    ) {
        Text(
            text =
                "The host key for this server has changed since your " +
                    "last connection. This could indicate a man-in-the-middle " +
                    "attack or a legitimate server reconfiguration.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(WARNING_CARD_PADDING_DP.dp),
        )
    }
}

/**
 * Labelled detail row for host key information.
 *
 * @param label Field name displayed in a muted style.
 * @param value Field value displayed in the primary body style.
 */
@Composable
private fun HostKeyDetailRow(
    label: String,
    value: String,
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
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
