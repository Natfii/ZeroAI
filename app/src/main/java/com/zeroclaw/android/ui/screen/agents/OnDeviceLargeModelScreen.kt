/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

@file:Suppress("FunctionNaming")

package com.zeroclaw.android.ui.screen.agents

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zeroclaw.android.model.LiteRtModelStatus
import com.zeroclaw.android.model.LiteRtRisk
import com.zeroclaw.android.service.ondevice.OnDeviceInferenceState
import com.zeroclaw.android.ui.component.ContentPane

/** Section spacing within the screen. */
private val SectionGap = 16.dp

/** Internal padding for primary cards. */
private val CardPadding = 16.dp

/** Corner radius for picker cards. */
private val CardCorner = 12.dp

/** Token-per-thousand divisor used for the "32K" / "128K" labels. */
private const val TOKENS_PER_K = 1000

/** Bytes per gigabyte (decimal — matches what users see in storage UIs). */
private const val BYTES_PER_GB = 1_000_000_000L

/** Bytes per megabyte (decimal). */
private const val BYTES_PER_MB = 1_000_000L

/**
 * On-device large model setup screen.
 *
 * Surfaces the single shipped LiteRT-LM variant (Gemma 4 E2B-it),
 * its per-device RAM/storage gating, and the on-device agent enable
 * toggle. Catalog deliberately scoped to one entry until additional
 * variants can be validated on hardware — see [LiteRtModelCatalog].
 *
 * Enable toggle is mutually exclusive with the cloud slot toggles via
 * `AgentDao.toggleExclusive`: enabling here disables every other
 * agent, and enabling a cloud slot disables this row.
 *
 * @param edgeMargin Horizontal padding based on window width class.
 * @param viewModel Backing ViewModel.
 * @param modifier Modifier applied to the root layout.
 */
@Composable
fun OnDeviceLargeModelScreen(
    edgeMargin: Dp,
    viewModel: OnDeviceLargeModelViewModel = viewModel(),
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isEnabled by viewModel.isEnabled.collectAsStateWithLifecycle()
    val inferenceState by viewModel.inferenceState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshState()
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(modifier = modifier) { innerPadding ->
        ContentPane(
            modifier =
                Modifier
                    .padding(innerPadding)
                    .padding(horizontal = edgeMargin),
        ) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(SectionGap),
            ) {
                Text(
                    text = "On-device large model",
                    style = MaterialTheme.typography.headlineSmall,
                )
                ResourceWarningCard()
                EnableToggleCard(enabled = isEnabled, onToggle = viewModel::toggleEnabled)
                InferenceStateCard(state = inferenceState)
                Text(
                    text = "Model",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                uiState.statuses.forEach { status ->
                    LiteRtModelCard(
                        status = status,
                        selected = status.model.id == uiState.selectedModelId,
                        onSelect = { viewModel.selectModel(status.model) },
                        onDownload = { viewModel.startDownload(status.model) },
                        onCancel = { viewModel.cancelDownload(status.model) },
                        onDelete = { viewModel.deleteModel(status.model) },
                    )
                }
            }
        }
    }
}

/**
 * Warning banner that frames the trade-offs of running an on-device
 * LLM before the user starts downloading multi-GB weights.
 */
@Composable
private fun ResourceWarningCard() {
    OutlinedCard(shape = RoundedCornerShape(CardCorner), modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(CardPadding),
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "On-device inference is power and RAM hungry",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text =
                        "Running a local LLM downloads ~2.6 GB of weights and " +
                            "uses ~700 MB of GPU memory during inference. " +
                            "Requires a Mali or Adreno GPU; Tensor G5 (Pixel 10) " +
                            "is not yet supported. Expect noticeable battery " +
                            "drain when chatting locally.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Compact card showing the live LiteRT-LM engine state.
 *
 * Surfaces whether the daemon currently has the model loaded, is
 * loading it, failed to load, or is idle. Lets the user see at a
 * glance whether a chat will land on the local engine or fall back
 * to whatever's the next route in the cascade.
 */
@Composable
private fun InferenceStateCard(state: OnDeviceInferenceState) {
    val (title, body, tint) =
        when (state) {
            OnDeviceInferenceState.Idle ->
                Triple(
                    "Engine idle",
                    "Enable the agent + start the daemon to load the model.",
                    MaterialTheme.colorScheme.onSurfaceVariant,
                )
            is OnDeviceInferenceState.Loading ->
                Triple(
                    "Loading ${state.model.displayName}…",
                    "The daemon is initialising the LiteRT-LM engine.",
                    MaterialTheme.colorScheme.tertiary,
                )
            is OnDeviceInferenceState.Loaded ->
                Triple(
                    "Loaded · ${state.model.displayName}",
                    "Daemon has the model in GPU memory. Terminal chat routes here.",
                    MaterialTheme.colorScheme.primary,
                )
            is OnDeviceInferenceState.Failed ->
                Triple(
                    "Load failed",
                    state.reason,
                    MaterialTheme.colorScheme.error,
                )
        }
    OutlinedCard(shape = RoundedCornerShape(CardCorner), modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(CardPadding),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = tint,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Mutual-exclusion enable switch matching the slot cards' style.
 */
@Composable
private fun EnableToggleCard(
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    OutlinedCard(shape = RoundedCornerShape(CardCorner), modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(CardPadding),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (enabled) "Enabled" else "Disabled",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text =
                        if (enabled) {
                            "Active agent. Other agents are disabled until daemon restart."
                        } else {
                            "Enable to make the on-device LLM the active agent."
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = { onToggle() })
        }
    }
}

/**
 * One LiteRT-LM variant in the picker. Tapping the card selects it;
 * the Download button gates on the per-variant RAM/storage check.
 */
@Composable
@Suppress("LongParameterList")
private fun LiteRtModelCard(
    status: LiteRtModelStatus,
    selected: Boolean,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val borderColor =
        if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.outlineVariant
        }
    OutlinedCard(
        shape = RoundedCornerShape(CardCorner),
        border = BorderStroke(width = 1.dp, color = borderColor),
        modifier =
            Modifier
                .fillMaxWidth()
                .selectable(
                    selected = selected,
                    role = Role.RadioButton,
                    onClick = onSelect,
                ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(CardPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ModelCardHeader(status = status, selected = selected)
            ModelCardStatusLine(
                status = status,
                onDownload = onDownload,
                onCancel = onCancel,
                onDelete = onDelete,
            )
        }
    }
}

/** Title row of a model card: selection radio + name + risk chip. */
@Composable
private fun ModelCardHeader(
    status: LiteRtModelStatus,
    selected: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector =
                if (selected) {
                    Icons.Filled.RadioButtonChecked
                } else {
                    Icons.Filled.RadioButtonUnchecked
                },
            contentDescription = null,
            tint =
                if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline
                },
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = status.model.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "${status.model.variantNote} · ${contextLabel(status.model.contextTokens)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        RiskChip(risk = effectiveRisk(status))
    }
}

/**
 * Resolves the chip tier to show, downgrading the static catalog
 * label when the current device can't actually host the variant.
 *
 * Without this, a low-end Pixel can see "Comfortable" on a card
 * whose Download button is greyed out for insufficient RAM — the
 * chip is the static aspirational tier, the gate is dynamic. We
 * promote the dynamic answer when the two disagree.
 */
private fun effectiveRisk(status: LiteRtModelStatus): LiteRtRisk {
    if (status is LiteRtModelStatus.NotDownloaded && (!status.ramOk || !status.storageOk)) {
        return LiteRtRisk.Heavy
    }
    return status.model.risk
}

/** Secondary line: status text + download/ready action affordance. */
@Composable
private fun ModelCardStatusLine(
    status: LiteRtModelStatus,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    when (status) {
        is LiteRtModelStatus.Ready -> ReadyRow(onDelete = onDelete)
        is LiteRtModelStatus.Downloading ->
            DownloadingProgress(status = status, onCancel = onCancel)
        is LiteRtModelStatus.Failed -> FailedRow(status = status, onRetry = onDownload)
        is LiteRtModelStatus.NotDownloaded ->
            NotDownloadedRow(status = status, onDownload = onDownload)
    }
}

/** Row shown when the model is downloaded and waiting for daemon load. */
@Composable
private fun ReadyRow(onDelete: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Downloaded · ready for next daemon start",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        OutlinedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
            Icon(imageVector = Icons.Filled.Delete, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Uninstall")
        }
    }
}

/** Row shown after a previous download attempt errored out. */
@Composable
private fun FailedRow(
    status: LiteRtModelStatus.Failed,
    onRetry: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Last download failed: ${status.reason}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
            Icon(imageVector = Icons.Filled.CloudDownload, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Retry download")
        }
    }
}

/** "Download <size>" button + RAM/storage warnings for [status]. */
@Composable
private fun NotDownloadedRow(
    status: LiteRtModelStatus.NotDownloaded,
    onDownload: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text =
                "${formatBytes(status.model.fileBytes)} download · " +
                    "${formatBytes(status.model.workingMemoryBytes)} working memory (GPU)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!status.ramOk) {
            Text(
                text = "Not enough free RAM to run this variant right now.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (!status.storageOk) {
            Text(
                text = "Not enough free storage for this download.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Button(
            onClick = onDownload,
            enabled = status.ramOk && status.storageOk,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(imageVector = Icons.Filled.CloudDownload, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Download ${formatBytes(status.model.fileBytes)}")
        }
    }
}

/** Progress UI shown while a download is in flight. */
@Composable
private fun DownloadingProgress(
    status: LiteRtModelStatus.Downloading,
    onCancel: () -> Unit,
) {
    val total = status.totalBytes.takeIf { it > 0L } ?: status.model.fileBytes
    val fraction =
        (status.bytesDownloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Downloading… ${formatBytes(status.bytesDownloaded)} / ${formatBytes(total)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text("Cancel download")
        }
    }
}

/** Small chip indicating the variant's RAM risk tier. */
@Composable
private fun RiskChip(risk: LiteRtRisk) {
    val (label, bg, fg) =
        when (risk) {
            LiteRtRisk.Comfortable ->
                Triple(
                    "Comfortable",
                    MaterialTheme.colorScheme.primaryContainer,
                    MaterialTheme.colorScheme.onPrimaryContainer,
                )
            LiteRtRisk.Moderate ->
                Triple(
                    "Moderate",
                    MaterialTheme.colorScheme.tertiaryContainer,
                    MaterialTheme.colorScheme.onTertiaryContainer,
                )
            LiteRtRisk.Heavy ->
                Triple(
                    "Heavy",
                    MaterialTheme.colorScheme.errorContainer,
                    MaterialTheme.colorScheme.onErrorContainer,
                )
        }
    Pill(label = label, background = bg, foreground = fg)
}

/** Tiny pill helper used by [RiskChip] and the NPU placeholder. */
@Composable
private fun Pill(
    label: String,
    background: Color,
    foreground: Color,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = foreground,
        modifier =
            Modifier
                .background(background, RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/** Formats a token count as a short label (e.g. `32K tokens`). */
private fun contextLabel(tokens: Int): String {
    val approxK = tokens / TOKENS_PER_K
    return if (approxK >= 1) "${approxK}K tokens" else "$tokens tokens"
}

/** Formats a byte count as a short string with decimal GB/MB. */
private fun formatBytes(bytes: Long): String =
    when {
        bytes >= BYTES_PER_GB -> "%.2f GB".format(bytes.toDouble() / BYTES_PER_GB)
        bytes >= BYTES_PER_MB -> "%.0f MB".format(bytes.toDouble() / BYTES_PER_MB)
        else -> "$bytes B"
    }
