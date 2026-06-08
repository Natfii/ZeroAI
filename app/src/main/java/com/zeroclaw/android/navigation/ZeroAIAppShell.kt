/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.zeroclaw.android.ZeroAIApplication
import com.zeroclaw.android.model.ServiceState
import com.zeroclaw.android.ui.component.LoadingIndicator
import com.zeroclaw.android.ui.component.StatusDot
import com.zeroclaw.android.ui.lock.rememberDeviceCredentialAuthenticator
import com.zeroclaw.android.viewmodel.DaemonViewModel
import kotlinx.coroutines.flow.map

/** Set of top-level routes where the bottom navigation bar should be visible. */
private val topLevelRoutes = TopLevelDestination.entries.map { it.route::class }

/**
 * Root composable providing the application shell with adaptive navigation
 * and a top app bar.
 *
 * Uses [NavigationSuiteScaffold] to automatically switch between a bottom
 * navigation bar (< 600dp), navigation rail (600-840dp), and navigation
 * drawer (840dp+) based on the current window width.
 *
 * The [StatusDot] is visible in the top bar on all screens to provide
 * persistent daemon status feedback.
 *
 * @param windowWidthSizeClass Current [WindowWidthSizeClass] for responsive layout.
 * @param benchmarkStartDestination Optional benchmark-only route override used by
 *   macrobenchmark tests.
 * @param viewModel The [DaemonViewModel] for daemon state.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ZeroAIAppShell(
    windowWidthSizeClass: WindowWidthSizeClass,
    benchmarkStartDestination: Any? = null,
    viewModel: DaemonViewModel = viewModel(),
) {
    val context = LocalContext.current
    val app = context.applicationContext as ZeroAIApplication
    val onboardingRepo = app.onboardingRepository
    val onboardingCompleted by onboardingRepo.isCompleted
        .map<Boolean, Boolean?> { it }
        .collectAsStateWithLifecycle(initialValue = null)

    val startDestination: Any? =
        benchmarkStartDestination
            ?: onboardingCompleted?.let { completed ->
                if (completed) {
                    DashboardRoute
                } else {
                    OnboardingRoute
                }
            }

    if (startDestination == null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            LoadingIndicator()
        }
        return
    }

    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val serviceState by viewModel.serviceState.collectAsStateWithLifecycle()

    val isLocked by app.sessionLockManager.isLocked.collectAsStateWithLifecycle()
    val settings by app.settingsRepository.settings.collectAsStateWithLifecycle(
        initialValue =
            com.zeroclaw.android.model
                .AppSettings(),
    )
    val isOnboarding = currentDestination?.hasRoute(OnboardingRoute::class) == true
    // Exempt onboarding from the lock ONLY on first run (no lock configured
    // yet). On a re-run of a completed setup the lock must still fire, or a
    // background-timeout mid-wizard would expose the steps without the
    // credential challenge.
    val shouldShowLock =
        isLocked &&
            settings.useDeviceCredential &&
            (!isOnboarding || onboardingCompleted == true)

    val isTopLevel =
        !isOnboarding &&
            currentDestination?.hierarchy?.any { dest ->
                topLevelRoutes.any { routeClass -> dest.hasRoute(routeClass) }
            } == true

    val edgeMargin =
        if (windowWidthSizeClass == WindowWidthSizeClass.Compact) 16.dp else 24.dp

    // Hide the navigation bar/rail while the soft keyboard is up so screens
    // with a bottom input (the terminal, chat) sit flush above the keyboard
    // instead of floating a nav-bar's height above it, and to reclaim space.
    val imeVisible = WindowInsets.isImeVisible
    val navLayoutType =
        when {
            imeVisible -> NavigationSuiteType.None
            windowWidthSizeClass == WindowWidthSizeClass.Compact -> NavigationSuiteType.NavigationBar
            else -> NavigationSuiteType.NavigationRail
        }

    Box(modifier = Modifier.fillMaxSize()) {
        if (isOnboarding) {
            Scaffold { innerPadding ->
                ZeroAINavHost(
                    navController = navController,
                    startDestination = startDestination,
                    edgeMargin = edgeMargin,
                    modifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding),
                    daemonViewModel = viewModel,
                )
            }
        } else if (isTopLevel) {
            NavigationSuiteScaffold(
                layoutType = navLayoutType,
                navigationSuiteItems = {
                    TopLevelDestination.entries.forEach { destination ->
                        val selected =
                            currentDestination?.hierarchy?.any { dest ->
                                dest.hasRoute(destination.route::class)
                            } == true
                        item(
                            selected = selected,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.startDestinationId) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector =
                                        if (selected) {
                                            destination.selectedIcon
                                        } else {
                                            destination.unselectedIcon
                                        },
                                    contentDescription = destination.label,
                                )
                            },
                            label = { Text(destination.label) },
                        )
                    }
                },
            ) {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = {
                                TopBarTitle(serviceState = serviceState)
                            },
                        )
                    },
                ) { innerPadding ->
                    ZeroAINavHost(
                        navController = navController,
                        startDestination = startDestination,
                        edgeMargin = edgeMargin,
                        modifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding),
                        daemonViewModel = viewModel,
                    )
                }
            }
        } else {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            TopBarTitle(
                                serviceState = serviceState,
                                title = screenTitleFor(currentDestination) ?: "ZeroAI",
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = { navController.popBackStack() }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Navigate back",
                                )
                            }
                        },
                    )
                },
            ) { innerPadding ->
                ZeroAINavHost(
                    navController = navController,
                    startDestination = startDestination,
                    edgeMargin = edgeMargin,
                    modifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding),
                    daemonViewModel = viewModel,
                )
            }
        }

        if (shouldShowLock) {
            DeviceCredentialLockShell(
                onUnlocked = { app.sessionLockManager.unlock() },
            )
        }
    }
}

/**
 * Full-screen locked overlay whose only action unlocks via the device credential.
 *
 * Renders an opaque surface over the app and prompts for the screen-lock
 * credential through [rememberDeviceCredentialAuthenticator]. The prompt is
 * launched automatically on first display and re-launchable from the unlock
 * button, calling [onUnlocked] only after a confirmed match. This replaces the
 * retired app-invented PIN gate; there is one device-credential path.
 *
 * @param onUnlocked Invoked after a successful device-credential confirmation.
 */
@Composable
private fun DeviceCredentialLockShell(onUnlocked: () -> Unit) {
    val authenticator = rememberDeviceCredentialAuthenticator()
    val prompt: () -> Unit = {
        authenticator.authenticate(
            title = "ZeroAI is locked",
            subtitle = "Confirm your device PIN, pattern, or password to continue",
            onSuccess = onUnlocked,
        )
    }

    LaunchedEffect(Unit) { prompt() }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(LOCK_SHELL_SPACING),
        ) {
            Icon(
                imageVector = Icons.Outlined.Lock,
                contentDescription = null,
                modifier = Modifier.size(LOCK_SHELL_ICON_SIZE),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "ZeroAI is locked",
                style = MaterialTheme.typography.headlineSmall,
                modifier =
                    Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                    },
            )
            FilledTonalButton(onClick = prompt) {
                Text("Unlock")
            }
        }
    }
}

/**
 * Returns a human-readable screen title for a sub-screen destination.
 *
 * @param destination The current [NavDestination], or null.
 * @return The screen title, or null if the destination is unknown.
 */
@Suppress("CyclomaticComplexMethod")
private fun screenTitleFor(destination: NavDestination?): String? {
    if (destination == null) return null
    return when {
        destination.hasRoute(AboutRoute::class) -> "About"
        destination.hasRoute(AppearanceRoute::class) -> "Appearance"
        destination.hasRoute(ProviderSlotDetailRoute::class) -> "Connection"
        destination.hasRoute(ApiKeyDetailRoute::class) -> "API Key"
        destination.hasRoute(ApiKeysRoute::class) -> "API Keys"
        destination.hasRoute(AutonomyRoute::class) -> "Autonomy"
        destination.hasRoute(BatterySettingsRoute::class) -> "Battery"
        destination.hasRoute(ChannelDetailRoute::class) -> "Channel"
        destination.hasRoute(ConnectedChannelsRoute::class) -> "Channels"
        destination.hasRoute(CostDetailRoute::class) -> "Cost Tracking"
        destination.hasRoute(CronJobsRoute::class) -> "Scheduled Tasks"
        destination.hasRoute(DoctorRoute::class) -> "Doctor"
        destination.hasRoute(LogViewerRoute::class) -> "Logs"
        destination.hasRoute(MemoryAdvancedRoute::class) -> "Memory Advanced"
        destination.hasRoute(MemoryBrowserRoute::class) -> "Memory Browser"
        destination.hasRoute(PluginDetailRoute::class) -> "Plugin"
        destination.hasRoute(ProviderConnectionsRoute::class) -> "Provider Logins"
        destination.hasRoute(QrScannerRoute::class) -> "QR Scanner"
        destination.hasRoute(ServiceConfigRoute::class) -> "Service Config"
        destination.hasRoute(SkillPermissionsRoute::class) -> "Skill Permissions"
        destination.hasRoute(SshKeysRoute::class) -> "SSH Keys"
        else -> null
    }
}

/**
 * Top app bar title row with app name and daemon [StatusDot].
 *
 * @param serviceState Current [ServiceState] shown in the status dot.
 * @param title Text displayed as the bar title.
 */
@Composable
private fun TopBarTitle(
    serviceState: ServiceState,
    title: String = "ZeroAI",
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title)
        Spacer(modifier = Modifier.width(8.dp))
        StatusDot(state = serviceState)
    }
}

private val LOCK_SHELL_SPACING = 16.dp
private val LOCK_SHELL_ICON_SIZE = 48.dp
