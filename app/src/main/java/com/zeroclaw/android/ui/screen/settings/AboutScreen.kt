/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zeroclaw.android.BuildConfig
import com.zeroclaw.android.ui.component.SectionHeader
import com.zeroclaw.ffi.getVersion

/**
 * About screen displaying app version, licenses, and project links.
 *
 * @param edgeMargin Horizontal padding based on window width size class.
 * @param modifier Modifier applied to the root layout.
 */
@Composable
fun AboutScreen(
    edgeMargin: Dp,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var crateVersion by remember { mutableStateOf(CRATE_VERSION_FALLBACK) }

    LaunchedEffect(Unit) {
        @Suppress("TooGenericExceptionCaught")
        try {
            crateVersion = getVersion()
        } catch (_: Exception) {
            crateVersion = CRATE_VERSION_FALLBACK
        }
    }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(horizontal = edgeMargin)
                .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        SectionHeader(title = "Version")
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                AboutRow(label = "App Version", value = BuildConfig.VERSION_NAME)
                AboutRow(label = "Build", value = BuildConfig.VERSION_CODE.toString())
                AboutRow(label = "Crate Version", value = crateVersion)
            }
        }

        SectionHeader(title = "Links")
        TextButton(
            onClick = {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(NATFII_GITHUB_URL)),
                )
            },
        ) {
            Text("@Natfii on GitHub")
        }
        TextButton(
            onClick = {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(ZEROCLAW_REPO_URL)),
                )
            },
        ) {
            Text("ZeroClaw Engine (upstream)")
        }

        SectionHeader(title = "Credits")
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Zeroclaw",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "OpenClaw",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "Coco",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        SectionHeader(title = "License")
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
        ) {
            Text(
                text = MIT_LICENSE_TEXT,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

/**
 * Accessible label-value row for the about screen.
 *
 * Uses [semantics] with [mergeDescendants] so screen readers announce
 * the label and value as a single phrase.
 *
 * @param label Description of the value.
 * @param value The displayed value string.
 */
@Composable
private fun AboutRow(
    label: String,
    value: String,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {},
    ) {
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private const val CRATE_VERSION_FALLBACK = "unknown"
private const val NATFII_GITHUB_URL = "https://github.com/Natfii"
private const val ZEROCLAW_REPO_URL = "https://github.com/zeroclaw-labs/zeroclaw"

private const val MIT_LICENSE_TEXT =
    "MIT (Funny Use) License\n\n" +
        "Copyright (c) 2026 @Natfii\n\n" +
        "Permission is hereby granted, free of charge, to any person obtaining a copy " +
        "of this software and associated documentation files (the \"Software\"), to deal " +
        "in the Software without restriction, including without limitation the rights " +
        "to use, copy, modify, merge, publish, distribute, sublicense, and/or sell " +
        "copies of the Software, and to permit persons to whom the Software is " +
        "furnished to do so, subject to the following conditions:\n\n" +
        "1. Whatever you are doing with this Software must be considered funny. " +
        "If it is not funny, you do not have permission. If you are unsure " +
        "whether your use is funny, it probably isn't. Silly use is ok.\n\n" +
        "2. The above copyright notice, this permission notice, and the funny " +
        "requirement shall be included in all copies or substantial portions " +
        "of the Software.\n\n" +
        "THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR " +
        "IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, " +
        "FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE " +
        "AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER " +
        "LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, " +
        "OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE " +
        "SOFTWARE.\n\n" +
        "Note: The Rust engine under zeroclaw/ is separately licensed under the " +
        "standard MIT License by ZeroClaw Labs. See zeroclaw/LICENSE-MIT for those " +
        "terms. The funny requirement does not apply to that code."
