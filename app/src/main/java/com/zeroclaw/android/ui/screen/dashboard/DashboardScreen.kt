// Copyright 2026 @Natfii, MIT License

@file:Suppress("MagicNumber", "MatchingDeclarationName")

package com.zeroclaw.android.ui.screen.dashboard

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zeroclaw.android.ZeroAIApplication
import com.zeroclaw.android.model.ActivityEvent
import com.zeroclaw.android.model.ComponentHealth
import com.zeroclaw.android.model.CostSummary
import com.zeroclaw.android.model.CronJob
import com.zeroclaw.android.model.DaemonStatus
import com.zeroclaw.android.model.HealthDetail
import com.zeroclaw.android.model.KeyRejectionEvent
import com.zeroclaw.android.model.MemoryConflict
import com.zeroclaw.android.model.ServiceState
import com.zeroclaw.android.ui.component.ContentPane
import com.zeroclaw.android.ui.component.LoadingIndicator
import com.zeroclaw.android.ui.component.MiniZeroMascot
import com.zeroclaw.android.ui.component.MiniZeroMascotState
import com.zeroclaw.android.ui.component.RestartRequiredBanner
import com.zeroclaw.android.ui.component.SectionHeader
import com.zeroclaw.android.util.BatteryOptimization
import com.zeroclaw.android.util.LocalPowerSaveMode
import com.zeroclaw.android.viewmodel.DaemonUiState
import com.zeroclaw.android.viewmodel.DaemonViewModel
import kotlinx.coroutines.launch

/**
 * Aggregated state for the dashboard content composable.
 *
 * @property serviceState Current daemon service lifecycle state.
 * @property statusState Daemon status with loading/error variants.
 * @property keyRejection Latest API key rejection event, if any.
 * @property healthDetail Component health breakdown, if available.
 * @property costSummary Accumulated cost summary, if available.
 * @property cronJobs Active cron job list.
 * @property enabledAgentCount Number of enabled agent connections.
 * @property installedPluginCount Number of installed plugins.
 * @property daemonStatus Latest daemon status snapshot, if available.
 * @property activityEvents Recent activity feed events.
 * @property restartRequired Whether the daemon must be restarted to apply pending changes.
 * @property memoryHealthWarning Warning from failed memory health check, if any.
 * @property estopEngaged Whether the emergency stop is currently active.
 */
data class DashboardState(
    val serviceState: ServiceState,
    val statusState: DaemonUiState<DaemonStatus>,
    val keyRejection: KeyRejectionEvent?,
    val healthDetail: HealthDetail?,
    val costSummary: CostSummary?,
    val cronJobs: List<CronJob>,
    val enabledAgentCount: Int,
    val installedPluginCount: Int,
    val daemonStatus: DaemonStatus?,
    val activityEvents: List<ActivityEvent>,
    val restartRequired: Boolean = false,
    val memoryHealthWarning: String? = null,
    val estopEngaged: Boolean = false,
)

/**
 * Dashboard home screen displaying daemon status, component health,
 * cost summary, cron summary, metrics, and an activity feed.
 *
 * Thin stateful wrapper that collects ViewModel flows and delegates
 * rendering to [DashboardContent].
 *
 * @param edgeMargin Horizontal padding based on window width size class.
 * @param onNavigateToCostDetail Callback to navigate to the cost detail screen.
 * @param onNavigateToCronJobs Callback to navigate to the cron jobs management screen.
 * @param restartRequired Whether the daemon needs a restart to apply pending changes.
 * @param onRestartDaemon Callback invoked when the user taps the restart button.
 * @param viewModel The [DaemonViewModel] for daemon state and actions.
 * @param modifier Modifier applied to the root layout.
 */
@Composable
fun DashboardScreen(
    edgeMargin: Dp,
    onNavigateToCostDetail: () -> Unit = {},
    onNavigateToCronJobs: () -> Unit = {},
    restartRequired: Boolean = false,
    onRestartDaemon: () -> Unit = {},
    viewModel: DaemonViewModel = viewModel(),
    modifier: Modifier = Modifier,
) {
    val serviceState by viewModel.serviceState.collectAsStateWithLifecycle()
    val statusState by viewModel.statusState.collectAsStateWithLifecycle()
    val keyRejection by viewModel.keyRejectionEvent.collectAsStateWithLifecycle()
    val healthDetail by viewModel.healthDetail.collectAsStateWithLifecycle()
    val costSummary by viewModel.costSummary.collectAsStateWithLifecycle()
    val cronJobs by viewModel.cronJobs.collectAsStateWithLifecycle()
    val enabledAgentCount by viewModel.enabledAgentCount.collectAsStateWithLifecycle()
    val installedPluginCount by viewModel.installedPluginCount.collectAsStateWithLifecycle()
    val daemonStatus by viewModel.daemonStatus.collectAsStateWithLifecycle()
    val activityEvents by viewModel.activityEvents.collectAsStateWithLifecycle()
    val memoryConflict by viewModel.memoryConflict.collectAsStateWithLifecycle()
    val memoryHealthWarning by viewModel.memoryHealthWarning.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val app = context.applicationContext as ZeroAIApplication
    val estopEngaged by app.estopRepository.engaged.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()

    val conflictData = memoryConflict
    if (conflictData is MemoryConflict.StaleData) {
        MemoryConflictDialog(
            conflict = conflictData,
            onDelete = { viewModel.resolveMemoryConflict(shouldDelete = true) },
            onKeep = { viewModel.resolveMemoryConflict(shouldDelete = false) },
        )
    }

    DashboardContent(
        state =
            DashboardState(
                serviceState = serviceState,
                statusState = statusState,
                keyRejection = keyRejection,
                healthDetail = healthDetail,
                costSummary = costSummary,
                cronJobs = cronJobs,
                enabledAgentCount = enabledAgentCount,
                installedPluginCount = installedPluginCount,
                daemonStatus = daemonStatus,
                activityEvents = activityEvents,
                restartRequired = restartRequired,
                memoryHealthWarning = memoryHealthWarning,
                estopEngaged = estopEngaged,
            ),
        edgeMargin = edgeMargin,
        onNavigateToCostDetail = onNavigateToCostDetail,
        onNavigateToCronJobs = onNavigateToCronJobs,
        onRestartDaemon = onRestartDaemon,
        onStartDaemon = viewModel::requestStart,
        onStopDaemon = viewModel::requestStop,
        onDismissKeyRejection = viewModel::dismissKeyRejection,
        onDismissMemoryHealthWarning = viewModel::dismissMemoryHealthWarning,
        onEngageEstop = { coroutineScope.launch { app.estopRepository.engage() } },
        onResumeEstop = { coroutineScope.launch { app.estopRepository.resume() } },
        modifier = modifier,
    )
}

/**
 * Stateless dashboard content composable for testing.
 *
 * Receives all state and callbacks as parameters, rendering the full
 * dashboard layout without any ViewModel dependency.
 *
 * @param state Aggregated dashboard state snapshot.
 * @param edgeMargin Horizontal padding based on window width size class.
 * @param onNavigateToCostDetail Callback to navigate to cost detail.
 * @param onNavigateToCronJobs Callback to navigate to cron jobs.
 * @param onRestartDaemon Callback to restart the daemon.
 * @param onStartDaemon Callback to start the daemon.
 * @param onStopDaemon Callback to stop the daemon.
 * @param onDismissKeyRejection Callback to dismiss the key rejection banner.
 * @param onDismissMemoryHealthWarning Callback to dismiss the memory health warning.
 * @param onEngageEstop Callback to engage the emergency stop.
 * @param onResumeEstop Callback to resume from the emergency stop.
 * @param modifier Modifier applied to the root layout.
 */
@Composable
internal fun DashboardContent(
    state: DashboardState,
    edgeMargin: Dp,
    onNavigateToCostDetail: () -> Unit,
    onNavigateToCronJobs: () -> Unit,
    onRestartDaemon: () -> Unit = {},
    onStartDaemon: () -> Unit,
    onStopDaemon: () -> Unit,
    onDismissKeyRejection: () -> Unit,
    onDismissMemoryHealthWarning: () -> Unit = {},
    onEngageEstop: () -> Unit = {},
    onResumeEstop: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val oemType = remember { BatteryOptimization.detectAggressiveOem() }
    val isExempt = remember { BatteryOptimization.isExempt(context) }
    var bannerDismissed by remember { mutableStateOf(false) }

    ContentPane(
        modifier =
            modifier
                .padding(horizontal = edgeMargin),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            if (state.restartRequired) {
                RestartRequiredBanner(onRestartDaemon = onRestartDaemon)
            }

            if (oemType != null && !isExempt && !bannerDismissed) {
                BatteryOptimizationBanner(
                    oemType = oemType,
                    onDismiss = { bannerDismissed = true },
                    onLearnMore = {
                        val url = BatteryOptimization.getOemInstructionsUrl(oemType)
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(url)),
                        )
                    },
                )
            }

            if (state.keyRejection != null) {
                KeyRejectionBanner(onDismiss = onDismissKeyRejection)
            }

            if (state.memoryHealthWarning != null) {
                MemoryHealthWarningBanner(
                    warning = state.memoryHealthWarning,
                    onDismiss = onDismissMemoryHealthWarning,
                )
            }

            EstopSection(
                engaged = state.estopEngaged,
                onEngage = onEngageEstop,
                onResume = onResumeEstop,
            )

            val toggleTimestamps = remember { mutableListOf<Long>() }
            val mascotSpeedMultiplier = remember { mutableStateOf(1) }

            StatusHeroCard(
                serviceState = state.serviceState,
                errorMessage = (state.statusState as? DaemonUiState.Error)?.detail,
                animationSpeedMultiplier = mascotSpeedMultiplier.value,
                onStart = {
                    val now = System.currentTimeMillis()
                    toggleTimestamps.add(now)
                    toggleTimestamps.removeAll { now - it > 3000 }
                    mascotSpeedMultiplier.value = toggleSpeedTier(toggleTimestamps.size)
                    onStartDaemon()
                },
                onStop = {
                    val now = System.currentTimeMillis()
                    toggleTimestamps.add(now)
                    toggleTimestamps.removeAll { now - it > 3000 }
                    mascotSpeedMultiplier.value = toggleSpeedTier(toggleTimestamps.size)
                    onStopDaemon()
                },
            )

            if (state.serviceState == ServiceState.RUNNING) {
                state.healthDetail?.let { detail ->
                    ComponentHealthRow(healthDetail = detail)
                }
            }

            SectionHeader(title = "At a Glance")
            MetricCardsRow(
                enabledAgentCount = state.enabledAgentCount,
                installedPluginCount = state.installedPluginCount,
                daemonStatus = state.daemonStatus,
                serviceState = state.serviceState,
            )

            if (state.serviceState == ServiceState.RUNNING) {
                state.costSummary?.let { cost ->
                    CostSummaryCard(
                        costSummary = cost,
                        onClick = onNavigateToCostDetail,
                    )
                }
            }

            if (state.serviceState == ServiceState.RUNNING && state.cronJobs.isNotEmpty()) {
                CronSummaryCard(
                    cronJobs = state.cronJobs,
                    onClick = onNavigateToCronJobs,
                )
            }

            SectionHeader(title = "Recent Activity")
            ActivityFeedSection(events = state.activityEvents)

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * Hero card showing daemon status with start/stop toggle.
 *
 * The mascot entrance animation defaults to 1200ms. Rapidly toggling
 * on/off within a 3-second window speeds it up, maxing at 8x (150ms).
 *
 * @param serviceState Current lifecycle state of the daemon.
 * @param errorMessage Optional error detail to display when in [ServiceState.ERROR].
 * @param animationSpeedMultiplier Speed multiplier for mascot enter/exit transitions.
 * @param onStart Callback invoked when the user taps Start.
 * @param onStop Callback invoked when the user taps Stop.
 */
@Composable
private fun StatusHeroCard(
    serviceState: ServiceState,
    errorMessage: String?,
    animationSpeedMultiplier: Int = 1,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val isTransitioning =
        serviceState == ServiceState.STARTING ||
            serviceState == ServiceState.STOPPING
    val isRunning = serviceState == ServiceState.RUNNING

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics { liveRegion = LiveRegionMode.Polite },
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text(
                text = "Daemon Status",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = serviceStateDescription(serviceState),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(modifier = Modifier.height(16.dp))
            val isPowerSave = LocalPowerSaveMode.current
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                FilledTonalButton(
                    onClick = if (isRunning) onStop else onStart,
                    enabled = !isTransitioning,
                    modifier =
                        Modifier
                            .defaultMinSize(minHeight = 48.dp)
                            .semantics {
                                contentDescription =
                                    if (isRunning) "Stop daemon" else "Start daemon"
                            },
                ) {
                    Text(text = if (isRunning) "Stop Daemon" else "Start Daemon")
                }
                if (isTransitioning) {
                    Spacer(modifier = Modifier.width(12.dp))
                    LoadingIndicator()
                }
                Spacer(modifier = Modifier.weight(1f))
                val peekDurationMs = 1200 / animationSpeedMultiplier
                AnimatedVisibility(
                    visible = isRunning,
                    enter =
                        if (isPowerSave) {
                            EnterTransition.None
                        } else {
                            slideInHorizontally(tween(peekDurationMs)) { it } +
                                fadeIn(tween(peekDurationMs))
                        },
                    exit =
                        if (isPowerSave) {
                            ExitTransition.None
                        } else {
                            slideOutHorizontally(tween(peekDurationMs)) { it } +
                                fadeOut(tween(peekDurationMs))
                        },
                ) {
                    MiniZeroMascot(
                        state = MiniZeroMascotState.Idle,
                        size = 48.dp,
                        contentDescription = "Zero is awake",
                    )
                }
            }
            if (serviceState == ServiceState.ERROR) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = errorMessage ?: "Daemon encountered an error",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * Dismissible banner warning about aggressive OEM battery management.
 *
 * @param oemType Detected OEM battery management type.
 * @param onDismiss Callback when the user dismisses the banner.
 * @param onLearnMore Callback when the user taps "Learn More".
 */
@Composable
private fun BatteryOptimizationBanner(
    oemType: BatteryOptimization.OemBatteryType,
    onDismiss: () -> Unit,
    onLearnMore: () -> Unit,
) {
    val oemName =
        when (oemType) {
            BatteryOptimization.OemBatteryType.XIAOMI -> "Xiaomi"
            BatteryOptimization.OemBatteryType.SAMSUNG -> "Samsung"
            BatteryOptimization.OemBatteryType.HUAWEI -> "Huawei"
            BatteryOptimization.OemBatteryType.ONEPLUS -> "OnePlus"
            BatteryOptimization.OemBatteryType.OPPO -> "Oppo"
            BatteryOptimization.OemBatteryType.VIVO -> "Vivo"
        }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Battery optimization",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text =
                    "$oemName devices may stop the daemon in the background. " +
                        "Disable battery optimization for reliable operation.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onLearnMore) {
                    Text("Learn More")
                }
                TextButton(onClick = onDismiss) {
                    Text("Dismiss")
                }
            }
        }
    }
}

/**
 * Dismissible banner shown when an API key rejection has been detected.
 *
 * @param onDismiss Callback when the user dismisses the banner.
 */
@Composable
private fun KeyRejectionBanner(onDismiss: () -> Unit) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics { liveRegion = LiveRegionMode.Polite },
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text =
                    "An API key may be invalid or expired. " +
                        "Check Settings \u203A API Keys.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onDismiss) {
                Text("Dismiss")
            }
        }
    }
}

/** Number of seconds in one minute, used for uptime formatting. */
private const val SECONDS_PER_MINUTE = 60

/** Number of seconds in one hour, used for uptime formatting. */
private const val SECONDS_PER_HOUR = 3600L

/**
 * Row of three compact metric cards summarising agent count, plugin count,
 * and daemon uptime. Each card occupies equal width via [Modifier.weight].
 *
 * @param enabledAgentCount Number of enabled agent connections.
 * @param installedPluginCount Number of installed plugins.
 * @param daemonStatus Latest daemon status snapshot, or null if unavailable.
 * @param serviceState Current service lifecycle state; used to determine whether
 *   to show uptime or "Offline".
 */
@Composable
private fun MetricCardsRow(
    enabledAgentCount: Int,
    installedPluginCount: Int,
    daemonStatus: DaemonStatus?,
    serviceState: ServiceState,
) {
    val uptimeText = formatUptime(daemonStatus, serviceState)
    val fontScale = LocalDensity.current.fontScale

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val useStackedLayout = maxWidth < 300.dp || fontScale >= 1.3f

        if (useStackedLayout) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MetricCard(
                    label = "Agents",
                    value = enabledAgentCount.toString(),
                    description = "enabled",
                    modifier = Modifier.fillMaxWidth(),
                )
                MetricCard(
                    label = "Plugins",
                    value = installedPluginCount.toString(),
                    description = "installed",
                    modifier = Modifier.fillMaxWidth(),
                )
                MetricCard(
                    label = "Uptime",
                    value = uptimeText,
                    description = if (serviceState == ServiceState.RUNNING) "running" else "",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MetricCard(
                    label = "Agents",
                    value = enabledAgentCount.toString(),
                    description = "enabled",
                    modifier = Modifier.weight(1f),
                )
                MetricCard(
                    label = "Plugins",
                    value = installedPluginCount.toString(),
                    description = "installed",
                    modifier = Modifier.weight(1f),
                )
                MetricCard(
                    label = "Uptime",
                    value = uptimeText,
                    description = if (serviceState == ServiceState.RUNNING) "running" else "",
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * Compact metric card displaying a label, a prominent value, and an optional
 * description line beneath.
 *
 * Styled with [MaterialTheme.colorScheme.surfaceContainerLow] background and
 * centered text alignment for visual consistency in a multi-card row.
 *
 * @param label Short heading displayed above the value (e.g. "Agents").
 * @param value The primary metric value displayed prominently (e.g. "3").
 * @param description Optional secondary text below the value (e.g. "enabled").
 * @param modifier Modifier applied to the root [Card] layout.
 */
@Composable
private fun MetricCard(
    label: String,
    value: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            if (description.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * Formats daemon uptime into a human-readable string.
 *
 * When the daemon is running and a status snapshot is available, formats the
 * [DaemonStatus.uptimeSeconds] as "Xh Ym" (e.g. "2h 15m") or "Xm" for
 * durations under one hour. Returns "Offline" when the daemon is not running
 * or no status has been received.
 *
 * @param status Latest daemon health snapshot, or null if unavailable.
 * @param serviceState Current service lifecycle state.
 * @return Formatted uptime string.
 */
private fun formatUptime(
    status: DaemonStatus?,
    serviceState: ServiceState,
): String {
    if (serviceState != ServiceState.RUNNING || status == null) {
        return "Offline"
    }
    val totalSeconds = status.uptimeSeconds
    val hours = totalSeconds / SECONDS_PER_HOUR
    val minutes = (totalSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    return if (hours > 0) {
        "${hours}h ${minutes}m"
    } else {
        "${minutes}m"
    }
}

private fun serviceStateDescription(state: ServiceState): String =
    when (state) {
        ServiceState.STOPPED -> "The daemon is not running."
        ServiceState.STARTING -> "The daemon is starting up\u2026"
        ServiceState.RUNNING -> "The daemon is running and healthy."
        ServiceState.STOPPING -> "The daemon is shutting down\u2026"
        ServiceState.ERROR -> "The daemon encountered an error."
    }

/**
 * Maps the number of rapid toggles within the spam window to a speed multiplier.
 *
 * @param recentToggleCount Number of toggles in the last 3 seconds.
 * @return Speed multiplier: 1, 2, 4, or 8.
 */
private fun toggleSpeedTier(recentToggleCount: Int): Int =
    when {
        recentToggleCount >= 6 -> 8
        recentToggleCount >= 4 -> 4
        recentToggleCount >= 2 -> 2
        else -> 1
    }

/** Component status value indicating healthy operation. */
private const val COMPONENT_STATUS_OK = "ok"

/** Component status value indicating the component is initializing. */
private const val COMPONENT_STATUS_STARTING = "starting"

/** Status dot indicator size in dp. */
private const val STATUS_DOT_SIZE = 8

/**
 * Flow row of chips showing each daemon component's health status.
 *
 * Each chip displays the component name, a colored status dot
 * (green for ok, amber for starting, red for error), and a restart
 * count badge when the count is greater than zero.
 *
 * Visible only when the daemon is running and a [HealthDetail] is available.
 *
 * @param healthDetail Structured health snapshot from the health bridge.
 * @param modifier Modifier applied to the root layout.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ComponentHealthRow(
    healthDetail: HealthDetail,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Component Health",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                healthDetail.components.forEach { component ->
                    ComponentHealthChip(component = component)
                }
            }
        }
    }
}

/**
 * Individual chip for a single daemon component showing its status.
 *
 * Displays a colored dot, the component name, and an optional badge
 * indicating the restart count. The dot color follows the same scheme
 * as the top-bar status indicator: green for ok, amber for starting,
 * red for error.
 *
 * @param component Health data for this component.
 * @param modifier Modifier applied to the chip card.
 */
@Composable
private fun ComponentHealthChip(
    component: ComponentHealth,
    modifier: Modifier = Modifier,
) {
    val dotColor =
        when (component.status) {
            COMPONENT_STATUS_OK -> MaterialTheme.colorScheme.primary
            COMPONENT_STATUS_STARTING -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.error
        }
    val statusLabel =
        when (component.status) {
            COMPONENT_STATUS_OK -> "healthy"
            COMPONENT_STATUS_STARTING -> "starting"
            else -> "error"
        }

    Card(
        modifier =
            modifier.semantics(mergeDescendants = true) {
                contentDescription =
                    "${component.name}: $statusLabel" +
                    if (component.restartCount > 0) {
                        ", ${component.restartCount} restarts"
                    } else {
                        ""
                    }
            },
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(STATUS_DOT_SIZE.dp)
                        .clip(CircleShape)
                        .background(dotColor),
            )
            Text(
                text = component.name,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (component.restartCount > 0) {
                Badge(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ) {
                    Text(text = component.restartCount.toString())
                }
            }
        }
    }
}

/**
 * Blocking dialog shown when stale memory backend data is found at daemon startup.
 *
 * @param conflict The stale data descriptor.
 * @param onDelete Callback when user chooses to delete stale files.
 * @param onKeep Callback when user chooses to keep stale files.
 */
@Composable
private fun MemoryConflictDialog(
    conflict: MemoryConflict.StaleData,
    onDelete: () -> Unit,
    onKeep: () -> Unit,
) {
    val sizeText = formatFileSize(conflict.staleSizeBytes)
    val plural = if (conflict.staleFileCount != 1) "s" else ""
    AlertDialog(
        onDismissRequest = { /* non-dismissable — user must choose */ },
        title = { Text("Memory Backend Changed") },
        text = {
            Text(
                "You switched from ${conflict.staleBackend} to " +
                    "${conflict.currentBackend}. Found " +
                    "${conflict.staleFileCount} stale ${conflict.staleBackend} " +
                    "file$plural ($sizeText).\n\n" +
                    "Delete old data to prevent conflicts?",
            )
        },
        confirmButton = {
            FilledTonalButton(onClick = onDelete) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onKeep) {
                Text("Keep")
            }
        },
    )
}

/**
 * Persistent warning banner for a failed memory health check.
 *
 * @param warning Human-readable failure reason.
 * @param onDismiss Callback to dismiss the banner.
 */
@Composable
private fun MemoryHealthWarningBanner(
    warning: String,
    onDismiss: () -> Unit,
) {
    Card(
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Memory: $warning",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) {
                Text("Dismiss")
            }
        }
    }
}

/**
 * Emergency stop section displayed at the top of the dashboard.
 *
 * When not engaged, shows a red button to activate the kill-all flag.
 * When engaged, shows a full-width error banner with a resume button
 * that requires device credential confirmation if a lock screen is set.
 *
 * @param engaged Whether the emergency stop is currently active.
 * @param onEngage Callback to engage the emergency stop.
 * @param onResume Callback to resume from the emergency stop.
 */
@Composable
private fun EstopSection(
    engaged: Boolean,
    onEngage: () -> Unit,
    onResume: () -> Unit,
) {
    if (engaged) {
        EstopActiveBanner(onResume = onResume)
    } else {
        EstopButton(onEngage = onEngage)
    }
}

/**
 * Red tonal button for engaging the emergency stop.
 *
 * Uses [MaterialTheme.colorScheme.error] for visual urgency and meets
 * the 48dp minimum touch target requirement.
 *
 * @param onEngage Callback invoked when the user taps the button.
 */
@Composable
private fun EstopButton(onEngage: () -> Unit) {
    var showConfirmDialog by remember { mutableStateOf(false) }

    Button(
        onClick = { showConfirmDialog = true },
        colors =
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
        modifier =
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .semantics {
                    contentDescription = "Emergency stop: halt all agent execution"
                },
    ) {
        Text(text = "Emergency Stop")
    }

    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = { Text("Engage Emergency Stop?") },
            text = {
                Text(
                    "This will immediately halt all agent execution and " +
                        "cancel any active sessions. The daemon will remain " +
                        "running but no actions will be processed.",
                )
            },
            confirmButton = {
                FilledTonalButton(
                    onClick = {
                        showConfirmDialog = false
                        onEngage()
                    },
                ) {
                    Text("Engage")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

/**
 * Full-width error banner shown when the emergency stop is active.
 *
 * Displays a warning message and a resume button. If the device has
 * a lock screen configured, tapping resume launches the device credential
 * confirmation screen (PIN, pattern, or fingerprint). If no lock screen
 * is set, a simple confirmation dialog is shown instead.
 *
 * @param onResume Callback invoked after the user successfully confirms resume.
 */
@Composable
private fun EstopActiveBanner(onResume: () -> Unit) {
    val context = LocalContext.current
    val keyguardManager =
        remember { context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager }
    val isDeviceSecure = remember { keyguardManager.isDeviceSecure }
    var showResumeDialog by remember { mutableStateOf(false) }

    val credentialLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                onResume()
            }
        }

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics { liveRegion = LiveRegionMode.Polite },
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Emergency stop active",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text =
                    "All agent execution is halted. No actions will be " +
                        "processed until the emergency stop is released.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(modifier = Modifier.height(8.dp))
            FilledTonalButton(
                onClick = {
                    if (isDeviceSecure) {
                        @Suppress("DEPRECATION")
                        val intent =
                            keyguardManager.createConfirmDeviceCredentialIntent(
                                "Resume Emergency Stop",
                                "Confirm your identity to resume agent execution",
                            )
                        if (intent != null) {
                            credentialLauncher.launch(intent)
                        } else {
                            onResume()
                        }
                    } else {
                        showResumeDialog = true
                    }
                },
                modifier =
                    Modifier
                        .defaultMinSize(minHeight = 48.dp)
                        .semantics {
                            contentDescription = "Resume from emergency stop"
                        },
            ) {
                Text("Resume")
            }
        }
    }

    if (showResumeDialog) {
        AlertDialog(
            onDismissRequest = { showResumeDialog = false },
            title = { Text("Resume Agent Execution?") },
            text = {
                Text(
                    "This will lift the emergency stop and allow agents " +
                        "to process actions again.",
                )
            },
            confirmButton = {
                FilledTonalButton(
                    onClick = {
                        showResumeDialog = false
                        onResume()
                    },
                ) {
                    Text("Resume")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResumeDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

/**
 * Formats a byte count as a human-readable file size.
 *
 * @param bytes Size in bytes.
 * @return Formatted string (e.g. "2.4 MB", "128 KB").
 */
private fun formatFileSize(bytes: Long): String {
    val kb = BYTES_PER_KB
    val mb = kb * BYTES_PER_KB
    return when {
        bytes >= mb -> "%.1f MB".format(bytes.toFloat() / mb)
        bytes >= kb -> "%.0f KB".format(bytes.toFloat() / kb)
        else -> "$bytes B"
    }
}

/** Number of bytes per kilobyte. */
private const val BYTES_PER_KB = 1024L
