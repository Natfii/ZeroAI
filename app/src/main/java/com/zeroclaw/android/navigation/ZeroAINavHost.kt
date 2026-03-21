/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.navigation

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.zeroclaw.android.ZeroAIApplication
import com.zeroclaw.android.model.ServiceState
import com.zeroclaw.android.service.ZeroAIDaemonService
import com.zeroclaw.android.ui.component.PinEntryMode
import com.zeroclaw.android.ui.component.PinEntrySheet
import com.zeroclaw.android.ui.screen.agents.AgentsScreen
import com.zeroclaw.android.ui.screen.agents.ProviderSlotDetailScreen
import com.zeroclaw.android.ui.screen.clawboy.ClawBoyConfigScreen
import com.zeroclaw.android.ui.screen.dashboard.DashboardScreen
import com.zeroclaw.android.ui.screen.messages.GoogleMessagesScreen
import com.zeroclaw.android.ui.screen.onboarding.OnboardingScreen
import com.zeroclaw.android.ui.screen.plugins.EmailConfigScreen
import com.zeroclaw.android.ui.screen.plugins.PluginDetailScreen
import com.zeroclaw.android.ui.screen.plugins.PluginsScreen
import com.zeroclaw.android.ui.screen.plugins.PluginsViewModel
import com.zeroclaw.android.ui.screen.plugins.SkillBuilderScreen
import com.zeroclaw.android.ui.screen.settings.AboutScreen
import com.zeroclaw.android.ui.screen.settings.AutonomyScreen
import com.zeroclaw.android.ui.screen.settings.BatterySettingsScreen
import com.zeroclaw.android.ui.screen.settings.CostDetailScreen
import com.zeroclaw.android.ui.screen.settings.MemoryAdvancedScreen
import com.zeroclaw.android.ui.screen.settings.SchedulerScreen
import com.zeroclaw.android.ui.screen.settings.ServiceConfigScreen
import com.zeroclaw.android.ui.screen.settings.SettingsScreen
import com.zeroclaw.android.ui.screen.settings.SettingsViewModel
import com.zeroclaw.android.ui.screen.settings.WebDashboardScreen
import com.zeroclaw.android.ui.screen.settings.apikeys.ApiKeyDetailScreen
import com.zeroclaw.android.ui.screen.settings.apikeys.ApiKeysScreen
import com.zeroclaw.android.ui.screen.settings.apikeys.ApiKeysViewModel
import com.zeroclaw.android.ui.screen.settings.apikeys.AuthProfilesScreen
import com.zeroclaw.android.ui.screen.settings.channels.ChannelDetailScreen
import com.zeroclaw.android.ui.screen.settings.channels.ConnectedChannelsScreen
import com.zeroclaw.android.ui.screen.settings.cron.CronJobsScreen
import com.zeroclaw.android.ui.screen.settings.discord.DiscordChannelDetailScreen
import com.zeroclaw.android.ui.screen.settings.discord.DiscordChannelsScreen
import com.zeroclaw.android.ui.screen.settings.doctor.DoctorScreen
import com.zeroclaw.android.ui.screen.settings.gateway.QrScannerScreen
import com.zeroclaw.android.ui.screen.settings.logs.LogViewerScreen
import com.zeroclaw.android.ui.screen.settings.memory.MemoryBrowserScreen
import com.zeroclaw.android.ui.screen.settings.providers.ProviderConnectionsScreen
import com.zeroclaw.android.ui.screen.settings.skillpermissions.SkillPermissionsScreen
import com.zeroclaw.android.ui.screen.setup.SetupScreen
import com.zeroclaw.android.ui.screen.tailscale.TailscaleConfigScreen
import com.zeroclaw.android.ui.screen.terminal.TerminalScreen
import com.zeroclaw.android.ui.screen.twitter.TwitterConfigScreen
import com.zeroclaw.android.viewmodel.DaemonViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Single [NavHost] mapping all route objects to their screen composables.
 *
 * @param navController Navigation controller managing the back stack.
 * @param startDestination Route object for the initial destination.
 * @param edgeMargin Horizontal padding based on window width size class.
 * @param daemonViewModel Shared [DaemonViewModel] used by dashboard and status surfaces.
 * @param modifier Modifier applied to the [NavHost].
 */
@Composable
fun ZeroAINavHost(
    navController: NavHostController,
    startDestination: Any,
    edgeMargin: Dp,
    daemonViewModel: DaemonViewModel,
    modifier: Modifier = Modifier,
) {
    val pluginsViewModel: PluginsViewModel = viewModel()
    val scannedTokenHolder: ScannedTokenHolder = viewModel()
    val context = LocalContext.current
    val app = remember(context) { context.applicationContext as ZeroAIApplication }
    val restartRequired by app.daemonBridge.restartRequired
        .collectAsStateWithLifecycle()
    val restartScope = rememberCoroutineScope()
    val onRestartDaemon: () -> Unit =
        remember(app, context, restartScope) {
            {
                val stopIntent =
                    Intent(context, ZeroAIDaemonService::class.java).apply {
                        action = ZeroAIDaemonService.ACTION_STOP
                    }
                context.startService(stopIntent)
                restartScope.launch {
                    app.daemonBridge.serviceState.first {
                        it == ServiceState.STOPPED || it == ServiceState.ERROR
                    }
                    val startIntent =
                        Intent(context, ZeroAIDaemonService::class.java).apply {
                            action = ZeroAIDaemonService.ACTION_START
                        }
                    context.startForegroundService(startIntent)
                }
            }
        }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
    ) {
        composable<DashboardRoute> {
            DashboardScreen(
                edgeMargin = edgeMargin,
                onNavigateToCostDetail = { navController.navigate(CostDetailRoute) },
                onNavigateToCronJobs = { navController.navigate(CronJobsRoute) },
                restartRequired = restartRequired,
                onRestartDaemon = onRestartDaemon,
                viewModel = daemonViewModel,
            )
        }

        composable<AgentsRoute> {
            AgentsScreen(
                onNavigateToDetail = { agentId ->
                    navController.navigate(ProviderSlotDetailRoute(slotId = agentId))
                },
                edgeMargin = edgeMargin,
            )
        }

        composable<ProviderSlotDetailRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<ProviderSlotDetailRoute>()
            ProviderSlotDetailScreen(
                slotId = route.slotId,
                edgeMargin = edgeMargin,
            )
        }

        composable<PluginsRoute> {
            PluginsScreen(
                onNavigateToDetail = { pluginId ->
                    navController.navigate(PluginDetailRoute(pluginId = pluginId))
                },
                onNavigateToChannelDetail = { channelId, channelType ->
                    navController.navigate(
                        ChannelDetailRoute(
                            channelId = channelId,
                            channelType = channelType,
                        ),
                    )
                },
                onNavigateToDiscordHub = {
                    navController.navigate(DiscordChannelsRoute)
                },
                onNavigateToProviderSlotDetail = { slotId ->
                    navController.navigate(ProviderSlotDetailRoute(slotId = slotId))
                },
                onNavigateToSkillBuilder = { skillName ->
                    navController.navigate(SkillBuilderRoute(skillName = skillName))
                },
                onNavigateToTwitterConfig = {
                    navController.navigate(TwitterConfigRoute)
                },
                onNavigateToClawBoyConfig = {
                    navController.navigate(ClawBoyConfigRoute)
                },
                onNavigateToEmailConfig = {
                    navController.navigate(EmailConfigRoute)
                },
                onNavigateToGoogleMessages = {
                    navController.navigate(GoogleMessagesRoute)
                },
                onNavigateToTailscaleConfig = {
                    navController.navigate(TailscaleConfigRoute)
                },
                edgeMargin = edgeMargin,
                pluginsViewModel = pluginsViewModel,
            )
        }

        composable<PluginDetailRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<PluginDetailRoute>()
            PluginDetailScreen(
                pluginId = route.pluginId,
                onBack = { navController.popBackStack() },
                edgeMargin = edgeMargin,
            )
        }

        composable<SkillBuilderRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<SkillBuilderRoute>()
            SkillBuilderScreen(
                skillName = route.skillName,
                edgeMargin = edgeMargin,
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable<TwitterConfigRoute> {
            TwitterConfigScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable<ClawBoyConfigRoute> {
            ClawBoyConfigScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable<EmailConfigRoute> {
            EmailConfigScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable<TerminalRoute> {
            TerminalScreen(edgeMargin = edgeMargin)
        }

        composable<SettingsRoute> {
            val settingsViewModel: SettingsViewModel = viewModel()

            SettingsScreen(
                onNavigate = { action ->
                    when (action) {
                        SettingsNavAction.ServiceConfig ->
                            navController.navigate(ServiceConfigRoute)
                        SettingsNavAction.Battery ->
                            navController.navigate(BatterySettingsRoute)
                        SettingsNavAction.ApiKeys ->
                            navController.navigate(ApiKeysRoute)
                        SettingsNavAction.Channels ->
                            navController.navigate(ConnectedChannelsRoute)
                        SettingsNavAction.LogViewer ->
                            navController.navigate(LogViewerRoute)
                        SettingsNavAction.Doctor ->
                            navController.navigate(DoctorRoute)
                        SettingsNavAction.About ->
                            navController.navigate(AboutRoute)
                        SettingsNavAction.Autonomy ->
                            navController.navigate(AutonomyRoute)
                        SettingsNavAction.MemoryAdvanced ->
                            navController.navigate(MemoryAdvancedRoute)
                        SettingsNavAction.Scheduler ->
                            navController.navigate(SchedulerRoute)
                        SettingsNavAction.CronJobs ->
                            navController.navigate(CronJobsRoute)
                        SettingsNavAction.MemoryBrowser ->
                            navController.navigate(MemoryBrowserRoute)
                        SettingsNavAction.AuthProfiles ->
                            navController.navigate(AuthProfilesRoute)
                        SettingsNavAction.DiscordChannels ->
                            navController.navigate(DiscordChannelsRoute)
                        SettingsNavAction.ProviderConnections ->
                            navController.navigate(ProviderConnectionsRoute)
                        SettingsNavAction.WebDashboard ->
                            navController.navigate(WebDashboardRoute)
                        SettingsNavAction.SkillPermissions ->
                            navController.navigate(SkillPermissionsRoute)
                    }
                },
                onRerunWizard = {
                    settingsViewModel.resetOnboarding()
                    navController.navigate(OnboardingRoute) {
                        popUpTo(navController.graph.startDestinationId) { inclusive = true }
                    }
                },
                edgeMargin = edgeMargin,
                settingsViewModel = settingsViewModel,
            )
        }

        composable<ServiceConfigRoute> {
            ServiceConfigScreen(edgeMargin = edgeMargin)
        }

        composable<BatterySettingsRoute> {
            BatterySettingsScreen(edgeMargin = edgeMargin)
        }

        composable<ApiKeysRoute> {
            val context = LocalContext.current
            val app = context.applicationContext as ZeroAIApplication
            val apiKeysViewModel: ApiKeysViewModel = viewModel()
            val settings by app.settingsRepository.settings.collectAsStateWithLifecycle(
                initialValue =
                    com.zeroclaw.android.model
                        .AppSettings(),
            )
            var pendingRevealKeyId by remember { mutableStateOf<String?>(null) }
            var showPinSetupForReveal by remember { mutableStateOf(false) }
            var awaitingDeviceCredential by remember { mutableStateOf(false) }
            val keyguardManager =
                remember(context) {
                    context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                }
            val deviceCredentialLauncher =
                rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult(),
                ) { result ->
                    if (result.resultCode == Activity.RESULT_OK) {
                        pendingRevealKeyId?.let { apiKeysViewModel.revealKey(it) }
                    }
                    pendingRevealKeyId = null
                    awaitingDeviceCredential = false
                }
            val credentialsLauncher =
                rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument(),
                ) { uri ->
                    uri?.let { apiKeysViewModel.importCredentialsFile(context, it) }
                }
            ApiKeysScreen(
                onNavigateToDetail = { keyId ->
                    navController.navigate(ApiKeyDetailRoute(keyId = keyId))
                },
                onRequestSecureReveal = { keyId ->
                    pendingRevealKeyId = keyId
                    val launchedDeviceCredential =
                        if (keyguardManager.isDeviceSecure) {
                            @Suppress("DEPRECATION")
                            val intent =
                                keyguardManager.createConfirmDeviceCredentialIntent(
                                    "Reveal API Key",
                                    "Confirm your identity to reveal the stored key",
                                )
                            if (intent != null) {
                                awaitingDeviceCredential = true
                                deviceCredentialLauncher.launch(intent)
                                true
                            } else {
                                false
                            }
                        } else {
                            false
                        }
                    if (!launchedDeviceCredential && settings.pinHash.isEmpty()) {
                        showPinSetupForReveal = true
                    }
                },
                onExportResult = { payload ->
                    val shareIntent =
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, payload)
                            putExtra(
                                Intent.EXTRA_SUBJECT,
                                "ZeroAI API Keys (encrypted)",
                            )
                        }
                    context.startActivity(
                        Intent.createChooser(
                            shareIntent,
                            "Share encrypted keys",
                        ),
                    )
                },
                onImportCredentials = {
                    credentialsLauncher.launch(arrayOf("application/json", "*/*"))
                },
                edgeMargin = edgeMargin,
                apiKeysViewModel = apiKeysViewModel,
            )

            if (showPinSetupForReveal && pendingRevealKeyId != null) {
                PinEntrySheet(
                    mode = PinEntryMode.SETUP,
                    currentPinHash = "",
                    onPinSet = { newHash ->
                        restartScope.launch {
                            app.settingsRepository.setPinHash(newHash)
                        }
                        pendingRevealKeyId?.let { apiKeysViewModel.revealKey(it) }
                        pendingRevealKeyId = null
                        showPinSetupForReveal = false
                    },
                    onDismiss = {
                        pendingRevealKeyId = null
                        showPinSetupForReveal = false
                    },
                )
            } else if (!awaitingDeviceCredential && pendingRevealKeyId != null && settings.pinHash.isNotEmpty()) {
                PinEntrySheet(
                    mode = PinEntryMode.VERIFY,
                    currentPinHash = settings.pinHash,
                    onPinSet = {
                        pendingRevealKeyId?.let { apiKeysViewModel.revealKey(it) }
                        pendingRevealKeyId = null
                    },
                    onDismiss = { pendingRevealKeyId = null },
                )
            }
        }

        composable<ApiKeyDetailRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<ApiKeyDetailRoute>()
            val scannedKey by scannedTokenHolder.token
                .collectAsStateWithLifecycle()

            ApiKeyDetailScreen(
                keyId = route.keyId,
                providerHint = route.providerId,
                onSaved = { navController.popBackStack() },
                onNavigateToQrScanner = { navController.navigate(QrScannerRoute) },
                edgeMargin = edgeMargin,
                scannedApiKey = scannedKey,
                onScannedApiKeyConsumed = { scannedTokenHolder.consume() },
            )
        }

        composable<ConnectedChannelsRoute> {
            ConnectedChannelsScreen(
                onNavigateToDetail = { channelId, channelType ->
                    navController.navigate(
                        ChannelDetailRoute(
                            channelId = channelId,
                            channelType = channelType,
                        ),
                    )
                },
                edgeMargin = edgeMargin,
            )
        }

        composable<ChannelDetailRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<ChannelDetailRoute>()
            ChannelDetailScreen(
                channelId = route.channelId,
                channelTypeName = route.channelType,
                onSaved = { navController.popBackStack() },
                onBack = { navController.popBackStack() },
                edgeMargin = edgeMargin,
            )
        }

        composable<LogViewerRoute> {
            LogViewerScreen(edgeMargin = edgeMargin)
        }

        composable<DoctorRoute> {
            DoctorScreen(
                edgeMargin = edgeMargin,
                onNavigateToRoute = { route ->
                    when {
                        route == "agents" -> navController.navigate(AgentsRoute)
                        route == "api-keys" -> navController.navigate(ApiKeysRoute)
                        route == "battery-settings" -> navController.navigate(BatterySettingsRoute)
                        route.startsWith("provider-slot/") -> {
                            val slotId = route.removePrefix("provider-slot/")
                            navController.navigate(ProviderSlotDetailRoute(slotId = slotId))
                        }
                        route.startsWith("agent-detail/") -> {
                            navController.navigate(AgentsRoute)
                        }
                        route.startsWith("api-key-detail/") -> {
                            val keyId = route.removePrefix("api-key-detail/")
                            navController.navigate(ApiKeyDetailRoute(keyId = keyId))
                        }
                    }
                },
            )
        }

        composable<AboutRoute> {
            AboutScreen(edgeMargin = edgeMargin)
        }

        composable<AutonomyRoute> {
            AutonomyScreen(edgeMargin = edgeMargin)
        }

        composable<MemoryAdvancedRoute> {
            MemoryAdvancedScreen(edgeMargin = edgeMargin)
        }

        composable<SchedulerRoute> {
            SchedulerScreen(edgeMargin = edgeMargin)
        }

        composable<QrScannerRoute> {
            QrScannerScreen(
                onTokenScanned = { token ->
                    scannedTokenHolder.set(token)
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable<MemoryBrowserRoute> {
            MemoryBrowserScreen(edgeMargin = edgeMargin)
        }

        composable<OnboardingRoute> {
            OnboardingScreen(
                onComplete = {
                    navController.navigate(SetupRoute) {
                        popUpTo(OnboardingRoute) { inclusive = true }
                    }
                },
            )
        }

        composable<SetupRoute> {
            SetupScreen(
                onComplete = {
                    navController.navigate(DashboardRoute) {
                        popUpTo(SetupRoute) { inclusive = true }
                    }
                },
            )
        }

        composable<CostDetailRoute> {
            CostDetailScreen(edgeMargin = edgeMargin)
        }

        composable<CronJobsRoute> {
            CronJobsScreen(edgeMargin = edgeMargin)
        }

        composable<AuthProfilesRoute> {
            AuthProfilesScreen(edgeMargin = edgeMargin)
        }

        composable<ProviderConnectionsRoute> {
            ProviderConnectionsScreen(edgeMargin = edgeMargin)
        }

        composable<WebDashboardRoute> {
            WebDashboardScreen()
        }

        composable<DiscordChannelsRoute> {
            DiscordChannelsScreen(
                onChannelClick = { channelId ->
                    navController.navigate(DiscordChannelDetailRoute(channelId))
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable<DiscordChannelDetailRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<DiscordChannelDetailRoute>()
            DiscordChannelDetailScreen(
                channelId = route.channelId,
                onBack = { navController.popBackStack() },
            )
        }

        composable<GoogleMessagesRoute> {
            GoogleMessagesScreen(
                onBack = { navController.popBackStack() },
                edgeMargin = edgeMargin,
            )
        }

        composable<TailscaleConfigRoute> {
            TailscaleConfigScreen(
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable<SkillPermissionsRoute> {
            SkillPermissionsScreen(edgeMargin = edgeMargin)
        }
    }
}
