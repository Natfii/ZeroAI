/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

@file:Suppress("FunctionNaming")

package com.zeroclaw.android.ui.screen.onboarding.steps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zeroclaw.android.model.OnDeviceAiMachine
import com.zeroclaw.android.model.OnDeviceAiUiState

/** Standard spacing between blocks within the step. */
private val SectionSpacing = 16.dp

/** Internal padding for status / preview cards. */
private val CardPadding = 16.dp

/** Corner radius for status cards. */
private val CardCorner = 12.dp

/** Size of the leading status icon on the main card. */
private val IconSize = 32.dp

/** Spacing between an icon and its adjacent text. */
private val IconTextGap = 12.dp

/** Bytes per kilobyte, kept readable for the size formatter. */
private const val BYTES_PER_KB = 1024L

/** Conversion factor from bytes to megabytes. */
private const val BYTES_PER_MB = 1024 * 1024L

/** Threshold above which the formatter switches to GB. */
private const val BYTES_PER_GB = 1024 * 1024 * 1024L

/** Approximate token-per-thousand divisor for short labels (e.g. "32K"). */
private const val TOKENS_PER_K = 1000

/**
 * Single-page onboarding step that drives the AI Core + on-device
 * model setup.
 *
 * Reads a [OnDeviceAiUiState] from the coordinator and switches the
 * card body based on its [OnDeviceAiUiState.machine] variant. The
 * user actions (download, install AI Core, toggle preview track,
 * open enrollment) come in as callbacks so the composable stays
 * state-free and screenshot-testable.
 *
 * @param state Current on-device AI state — both the preview-track
 *   preference and the resolved machine variant.
 * @param onDownload Triggers `model.download()` for the current track.
 * @param onInstallAiCore Opens the Play Store listing for AI Core.
 * @param onTogglePreview Switches between Stable and Preview tracks.
 *   Ignored when [showPreviewSection] is false.
 * @param onEnrollPreview Opens the Developer Preview enrollment page.
 *   Ignored when [showPreviewSection] is false.
 * @param showPreviewSection When `true`, renders the secondary
 *   Developer Preview track switch + enrollment link. Onboarding sets
 *   this to `false` so the page is purely about the 4K auxiliary
 *   Nano; the Agents-tab screen sets it to `true` to drive the larger
 *   preview model setup.
 * @param showHeader When `true`, renders the built-in onboarding-flavored
 *   header ("On-device fallback — Sets up Gemini Nano…"). Callers that
 *   already supply a title for the host screen (such as the Agents-tab
 *   large-model screen) pass `false` so the auxiliary-Nano copy doesn't
 *   bleed into a context it doesn't describe.
 * @param modifier Modifier applied to the root column.
 */
@Composable
@Suppress("LongParameterList")
fun OnDeviceAiStep(
    state: OnDeviceAiUiState,
    onDownload: () -> Unit,
    onInstallAiCore: () -> Unit,
    onTogglePreview: (Boolean) -> Unit,
    onEnrollPreview: () -> Unit,
    showPreviewSection: Boolean = true,
    showHeader: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(SectionSpacing),
    ) {
        if (showHeader) OnDeviceAiHeader()
        OnDeviceAiPrimaryCard(
            state = state,
            onDownload = onDownload,
            onInstallAiCore = onInstallAiCore,
        )
        if (showPreviewSection) {
            OnDeviceAiPreviewSection(
                state = state,
                onTogglePreview = onTogglePreview,
                onEnrollPreview = onEnrollPreview,
            )
        }
    }
}

/**
 * Static header explaining what this step does.
 */
@Composable
private fun OnDeviceAiHeader() {
    Text(
        text = "On-device fallback",
        style = MaterialTheme.typography.headlineSmall,
    )
    Text(
        text =
            "Sets up Gemini Nano as a free, offline auxiliary brain. " +
                "Your cloud provider stays the main chat — Nano kicks in " +
                "when the daemon is off, when no provider is configured, " +
                "or to describe images for non-vision models. " +
                "Larger on-device models live in the Agents tab.",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Card whose body changes based on the [OnDeviceAiMachine] variant.
 * Centralises the visual conventions (icon header + optional action)
 * so each state only declares its semantic content, not its layout.
 */
@Composable
private fun OnDeviceAiPrimaryCard(
    state: OnDeviceAiUiState,
    onDownload: () -> Unit,
    onInstallAiCore: () -> Unit,
) {
    OutlinedCard(shape = RoundedCornerShape(CardCorner), modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(CardPadding)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(IconTextGap),
        ) {
            when (val machine = state.machine) {
                OnDeviceAiMachine.Checking -> CheckingBody()
                is OnDeviceAiMachine.Ready -> ReadyBody(machine, state.previewTrackSelected)
                OnDeviceAiMachine.Downloadable -> DownloadableBody(onDownload)
                is OnDeviceAiMachine.Downloading -> DownloadingBody(machine)
                OnDeviceAiMachine.NeedsAiCore -> NeedsAiCoreBody(onInstallAiCore)
                is OnDeviceAiMachine.SetupPending ->
                    SetupPendingBody(machine, state.previewTrackSelected)
                OnDeviceAiMachine.NotSupported -> NotSupportedBody()
            }
        }
    }
}

/**
 * Canonical icon + title + subtitle row used by every machine body.
 * Centralising the layout means each variant only has to declare its
 * icon, tint, and copy — no repeated `Row+Icon+Spacer+Column` plumbing.
 *
 * @param icon Leading icon for the variant.
 * @param iconTint Tint colour for the icon.
 * @param title Card headline (e.g. "Ready", "Download model").
 * @param subtitle Secondary body line; rendered when non-blank.
 */
@Composable
private fun IconHeaderRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(IconSize),
        )
        Spacer(modifier = Modifier.width(IconTextGap))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Renders the "Checking…" placeholder while the first status query
 * is in flight. Uses a circular progress indicator in place of the
 * static icon since the state itself implies movement.
 */
@Composable
private fun CheckingBody() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(IconSize))
        Spacer(modifier = Modifier.width(IconTextGap))
        Text(
            text = "Checking AI Core availability…",
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

/**
 * Renders the success state, including the resolved variant name and
 * token budget that beta2's `getBaseModelName()` / `getTokenLimit()`
 * surface returns.
 */
@Composable
private fun ReadyBody(
    machine: OnDeviceAiMachine.Ready,
    previewTrackSelected: Boolean,
) {
    IconHeaderRow(
        icon = Icons.Filled.CheckCircle,
        iconTint = MaterialTheme.colorScheme.primary,
        title = "Ready",
        subtitle = readySubtitle(machine, previewTrackSelected),
    )
}

/**
 * "Downloadable" prompt the user must explicitly accept. AI Core has
 * the variant listed but hasn't fetched the weights yet.
 */
@Composable
private fun DownloadableBody(onDownload: () -> Unit) {
    IconHeaderRow(
        icon = Icons.Filled.CloudDownload,
        iconTint = MaterialTheme.colorScheme.primary,
        title = "Download model",
        subtitle =
            "AI Core has Gemini Nano available but hasn't fetched " +
                "it yet. Downloads happen once and are then offline.",
    )
    Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
        Text("Download model")
    }
}

/**
 * Live progress UI while the model is being fetched.
 */
@Composable
private fun DownloadingBody(machine: OnDeviceAiMachine.Downloading) {
    IconHeaderRow(
        icon = Icons.Filled.CloudDownload,
        iconTint = MaterialTheme.colorScheme.primary,
        title = "Downloading…",
        subtitle = downloadingSubtitle(machine),
    )
    val progress = downloadProgressFraction(machine)
    if (progress != null) {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

/**
 * Shown when AI Core is missing entirely (typically a non-Pixel
 * device). Deep-links to the Play Store listing.
 */
@Composable
private fun NeedsAiCoreBody(onInstallAiCore: () -> Unit) {
    IconHeaderRow(
        icon = Icons.Filled.Warning,
        iconTint = MaterialTheme.colorScheme.tertiary,
        title = "Install AI Core",
        subtitle =
            "AI Core is the Google system app that hosts Gemini " +
                "Nano on Pixel devices. Install it from Play Store " +
                "to enable on-device AI.",
    )
    OutlinedButton(onClick = onInstallAiCore, modifier = Modifier.fillMaxWidth()) {
        Text("Open Play Store")
    }
}

/**
 * AI Core is installed but reports `UNAVAILABLE` — usually a config
 * sync that hasn't finished yet, or a preview track where the user
 * isn't enrolled.
 */
@Composable
private fun SetupPendingBody(
    machine: OnDeviceAiMachine.SetupPending,
    previewTrackSelected: Boolean,
) {
    val title =
        if (previewTrackSelected) {
            "Preview not available yet"
        } else {
            "AI Core setup still syncing"
        }
    val guidance =
        if (previewTrackSelected) {
            "The Developer Preview model isn't reachable. Make " +
                "sure you've joined the AI Core experimental " +
                "Google Group and the Play Store tester program, " +
                "then come back here in a few minutes."
        } else {
            "AI Core is finishing first-run setup. Wait a few " +
                "minutes on Wi-Fi, or reboot to expedite."
        }
    IconHeaderRow(
        icon = Icons.Filled.Warning,
        iconTint = MaterialTheme.colorScheme.tertiary,
        title = title,
        subtitle = "$guidance\n\nAI Core reported: ${machine.reason}",
    )
}

/**
 * Terminal "this device can't" branch. No action.
 */
@Composable
private fun NotSupportedBody() {
    IconHeaderRow(
        icon = Icons.Filled.Memory,
        iconTint = MaterialTheme.colorScheme.outline,
        title = "Not supported",
        subtitle =
            "This device doesn't support on-device AI. " +
                "Zero will still work with cloud providers.",
    )
}

/**
 * Secondary section that lets enrolled Developer Preview users opt
 * into the higher-context Gemma 4 E4B variant. Hidden when the device
 * doesn't even meet the basic requirements, since the toggle would
 * have nothing to drive.
 */
@Composable
private fun OnDeviceAiPreviewSection(
    state: OnDeviceAiUiState,
    onTogglePreview: (Boolean) -> Unit,
    onEnrollPreview: () -> Unit,
) {
    val machine = state.machine
    if (machine is OnDeviceAiMachine.NotSupported || machine is OnDeviceAiMachine.NeedsAiCore) {
        return
    }
    OutlinedCard(shape = RoundedCornerShape(CardCorner), modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(CardPadding),
            verticalArrangement = Arrangement.spacedBy(IconTextGap),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Use Developer Preview model",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text =
                            "Asks AI Core for the preview variant " +
                                "(Gemma 4 E4B / 128K context). Requires " +
                                "enrolling your Google account in the " +
                                "AI Core Developer Preview.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.previewTrackSelected,
                    onCheckedChange = onTogglePreview,
                )
            }
            OutlinedButton(onClick = onEnrollPreview, modifier = Modifier.fillMaxWidth()) {
                Text("Open enrollment page")
            }
        }
    }
}

/**
 * Computes the "Ready" subtitle, surfacing the resolved variant and
 * token budget (when ML Kit reports one) and stressing the auxiliary
 * role so users don't mistake the 4K context for a main chat backend.
 */
private fun readySubtitle(
    machine: OnDeviceAiMachine.Ready,
    previewTrackSelected: Boolean,
): String {
    val limitLabel =
        if (machine.tokenLimit > 0) {
            val approxK = machine.tokenLimit / TOKENS_PER_K
            if (approxK >= 1) " · ${approxK}K tokens" else " · ${machine.tokenLimit} tokens"
        } else {
            ""
        }
    val trackLabel = if (previewTrackSelected) " (preview)" else ""
    val auxiliaryNote = if (previewTrackSelected) "" else " · auxiliary use only"
    return "Running ${machine.modelName}$trackLabel$limitLabel$auxiliaryNote"
}

/**
 * Computes the live "X MB / Y MB" subtitle while downloading.
 */
private fun downloadingSubtitle(machine: OnDeviceAiMachine.Downloading): String {
    if (machine.totalBytes <= 0L) return "Starting download…"
    return "${formatBytes(machine.bytesDownloaded)} / ${formatBytes(machine.totalBytes)}"
}

/**
 * Maps a [OnDeviceAiMachine.Downloading] to a `[0f, 1f]` progress
 * fraction, or `null` when the total size is still unknown so the
 * UI can fall back to an indeterminate indicator.
 */
private fun downloadProgressFraction(machine: OnDeviceAiMachine.Downloading): Float? {
    if (machine.totalBytes <= 0L) return null
    val ratio = machine.bytesDownloaded.toFloat() / machine.totalBytes.toFloat()
    return ratio.coerceIn(0f, 1f)
}

/**
 * Formats a byte count as a short, human-readable string.
 */
private fun formatBytes(bytes: Long): String =
    when {
        bytes >= BYTES_PER_GB -> "%.1f GB".format(bytes.toFloat() / BYTES_PER_GB)
        bytes >= BYTES_PER_MB -> "%.0f MB".format(bytes.toFloat() / BYTES_PER_MB)
        bytes >= BYTES_PER_KB -> "%.0f KB".format(bytes.toFloat() / BYTES_PER_KB)
        else -> "$bytes B"
    }
