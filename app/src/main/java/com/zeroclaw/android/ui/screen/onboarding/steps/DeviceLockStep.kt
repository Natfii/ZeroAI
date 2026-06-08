/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.onboarding.steps

import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.zeroclaw.android.ui.lock.DeviceCredentialAuthenticator
import com.zeroclaw.android.ui.lock.rememberDeviceCredentialAuthenticator

/** Spacing after the headline icon. */
private val HeroSpacing = 24.dp

/** Spacing after the headline text. */
private val HeadlineSpacing = 16.dp

/** Spacing after the body copy, before the call to action. */
private val BodySpacing = 24.dp

/** Spacing between the primary CTA and the secondary escape hatch. */
private val CtaSpacing = 8.dp

/** Size of the lock status icon at the top of the step. */
private val LockIconSize = 64.dp

/** Internal padding of the success confirmation card. */
private val CardPadding = 16.dp

/** Size of the success checkmark inside the confirmation card. */
private val CheckIconSize = 24.dp

/** Spacing between the success checkmark and its label. */
private val CheckTextSpacing = 12.dp

/** Minimum touch target height for the CTA buttons (WCAG AA). */
private val MinButtonHeight = 48.dp

/**
 * Onboarding step that locks ZeroAI behind the device screen-lock credential.
 *
 * The device credential (PIN, pattern, or password) is the single lock mode and
 * is required to use SSH. The step renders one of three states:
 *  - **Lock available, not yet enabled**: a "Use my phone lock" CTA that prompts
 *    for the device credential, then persists the choice and advances.
 *  - **No screen lock set**: a "Set a screen lock" CTA that deep-links into system
 *    settings, plus an escape hatch to continue with SSH disabled. The secure
 *    state is re-probed whenever the step resumes, so returning from settings
 *    flips the UI to the success state automatically.
 *  - **Lock enabled**: a success confirmation with a Continue affordance.
 *
 * Lock state is owned by [OnboardingCoordinator][com.zeroclaw.android.ui.screen.onboarding.OnboardingCoordinator]
 * and threaded through [useDeviceCredential] / [onUseDeviceCredentialChange]; this
 * step only renders it and triggers the credential prompt.
 *
 * @param useDeviceCredential Whether the device-credential app lock is enabled.
 * @param onUseDeviceCredentialChange Callback persisting the lock toggle.
 * @param onAdvance Callback advancing the wizard to the next step.
 * @param modifier Modifier applied to the root scrollable [Column].
 */
@Composable
fun DeviceLockStep(
    useDeviceCredential: Boolean,
    onUseDeviceCredentialChange: (Boolean) -> Unit,
    onAdvance: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val authenticator = rememberDeviceCredentialAuthenticator()
    var deviceSecure by remember { mutableStateOf(authenticator.isDeviceSecure()) }

    LifecycleResumeEffect(Unit) {
        deviceSecure = authenticator.isDeviceSecure()
        onPauseOrDispose {}
    }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
    ) {
        when {
            useDeviceCredential ->
                LockEnabledContent(onAdvance = onAdvance)
            deviceSecure ->
                LockAvailableContent(
                    authenticator = authenticator,
                    onEnabled = {
                        onUseDeviceCredentialChange(true)
                        onAdvance()
                    },
                )
            else ->
                NoScreenLockContent(
                    onContinueWithoutLock = {
                        onUseDeviceCredentialChange(false)
                        onAdvance()
                    },
                )
        }
    }
}

/**
 * Content shown once the device-credential app lock is enabled.
 *
 * @param onAdvance Callback advancing the wizard to the next step.
 */
@Composable
private fun LockEnabledContent(onAdvance: () -> Unit) {
    LockHeader(
        secure = true,
        headline = "App lock is on",
        body =
            "ZeroAI will ask for your phone PIN, pattern, or password on " +
                "launch and after a period of inactivity. SSH is unlocked.",
    )

    Card(
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(CardPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CheckTextSpacing),
        ) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(CheckIconSize),
            )
            Text(
                text = "Locked with your device credential",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }

    Spacer(modifier = Modifier.height(BodySpacing))

    FilledTonalButton(
        onClick = onAdvance,
        modifier =
            Modifier
                .fillMaxWidth()
                .height(MinButtonHeight),
    ) {
        Text("Continue")
    }
}

/**
 * Content shown when a screen lock exists but the app lock is not yet enabled.
 *
 * @param authenticator Device-credential prompt used to confirm ownership.
 * @param onEnabled Callback invoked after the credential is confirmed.
 */
@Composable
private fun LockAvailableContent(
    authenticator: DeviceCredentialAuthenticator,
    onEnabled: () -> Unit,
) {
    LockHeader(
        secure = false,
        headline = "Lock ZeroAI with your phone screen lock",
        body =
            "Require your device PIN, pattern, or password to open ZeroAI on " +
                "launch and after inactivity. This is required to use SSH.",
    )

    FilledTonalButton(
        onClick = {
            authenticator.authenticate(
                title = "Lock ZeroAI",
                subtitle = "Confirm your device credential to require it on unlock",
                onSuccess = onEnabled,
            )
        },
        modifier =
            Modifier
                .fillMaxWidth()
                .height(MinButtonHeight),
    ) {
        Text("Use my phone lock")
    }
}

/**
 * Content shown when the device has no screen lock configured.
 *
 * @param onContinueWithoutLock Callback advancing with SSH disabled.
 */
@Composable
private fun NoScreenLockContent(onContinueWithoutLock: () -> Unit) {
    val context = LocalContext.current

    LockHeader(
        secure = false,
        headline = "Set a screen lock to continue",
        body =
            "ZeroAI uses your phone's screen lock to protect SSH. Set a PIN, " +
                "pattern, or password in your device settings, then come back " +
                "to finish enabling the app lock.",
    )

    FilledTonalButton(
        onClick = { launchScreenLockSettings(context) },
        modifier =
            Modifier
                .fillMaxWidth()
                .height(MinButtonHeight),
    ) {
        Text("Set a screen lock")
    }

    Spacer(modifier = Modifier.height(CtaSpacing))

    OutlinedButton(
        onClick = onContinueWithoutLock,
        modifier =
            Modifier
                .fillMaxWidth()
                .height(MinButtonHeight),
    ) {
        Text("Continue without lock (SSH disabled)")
    }
}

/**
 * Headline block shared by every device-lock state.
 *
 * @param secure Whether to render the locked (vs unlocked) status icon.
 * @param headline Title text for the current state.
 * @param body Supporting copy explaining the state.
 */
@Composable
private fun LockHeader(
    secure: Boolean,
    headline: String,
    body: String,
) {
    Icon(
        imageVector = if (secure) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(LockIconSize),
    )

    Spacer(modifier = Modifier.height(HeroSpacing))

    Text(
        text = headline,
        style = MaterialTheme.typography.headlineSmall,
    )

    Spacer(modifier = Modifier.height(HeadlineSpacing))

    Text(
        text = body,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(modifier = Modifier.height(BodySpacing))
}

/**
 * Opens the system screen-lock setup, falling back to security settings.
 *
 * On API 30+ this targets [DevicePolicyManager.ACTION_SET_NEW_PASSWORD], which
 * lands directly on the credential-setup flow; older devices and any
 * [ActivityNotFoundException] fall back to [Settings.ACTION_SECURITY_SETTINGS].
 *
 * @param context Android context used to launch the settings activity.
 */
private fun launchScreenLockSettings(context: android.content.Context) {
    val primary =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(DevicePolicyManager.ACTION_SET_NEW_PASSWORD)
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }
    try {
        context.startActivity(primary)
    } catch (_: ActivityNotFoundException) {
        try {
            context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            // No settings activity available to set a screen lock; the user can
            // still continue with SSH disabled via the escape hatch.
        }
    }
}
