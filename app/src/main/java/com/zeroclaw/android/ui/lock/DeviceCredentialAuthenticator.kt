/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.lock

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Prompts for the device screen-lock credential (PIN, pattern, or password).
 *
 * This is the single device-credential entry point for the app. It confirms
 * the user owns the device using the OS keyguard, with no app-invented PIN and
 * no biometric (fingerprint/face) factor — fingerprint has proven unreliable in
 * this app, so only [BiometricManager.Authenticators.DEVICE_CREDENTIAL] is ever
 * requested.
 *
 * Two OS paths are bridged behind one contract:
 *  - **API 30+**: [BiometricPrompt] with
 *    `setAllowedAuthenticators(DEVICE_CREDENTIAL)`. `setNegativeButtonText` is
 *    never called — pairing it with `DEVICE_CREDENTIAL` throws.
 *  - **API 28-29**: `DEVICE_CREDENTIAL` via `setAllowedAuthenticators` is
 *    unsupported, so [KeyguardManager.createConfirmDeviceCredentialIntent] is
 *    launched through an [ActivityResultContracts.StartActivityForResult]
 *    launcher.
 *
 * Construct via [rememberDeviceCredentialAuthenticator] so the API 28-29
 * launcher is wired into composition. This is a pure auth gate — no
 * `CryptoObject` is bound.
 */
@Stable
class DeviceCredentialAuthenticator internal constructor(
    private val context: Context,
    private val launchLegacyConfirm: (title: String, subtitle: String) -> Boolean,
) {
    /**
     * Whether a device screen-lock credential is set up.
     *
     * When this returns `false` there is nothing to authenticate against, so
     * callers must guide the user to set up a screen lock before enabling any
     * credential-gated feature.
     *
     * @return `true` if the device has a secure keyguard (PIN/pattern/password).
     */
    fun isDeviceSecure(): Boolean {
        val keyguardManager =
            context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return keyguardManager?.isDeviceSecure == true
    }

    /**
     * Prompts for the device credential, invoking [onSuccess] only on a
     * confirmed match.
     *
     * No-ops (without invoking [onSuccess]) when the device is not secured, so
     * callers should gate on [isDeviceSecure] first when the distinction
     * matters. A cancelled or failed prompt simply never calls [onSuccess].
     *
     * Safe to call from the main thread; the OS renders the prompt.
     *
     * @param title Prompt title shown to the user.
     * @param subtitle Prompt subtitle explaining why confirmation is needed.
     * @param onSuccess Invoked on the main thread after a successful confirmation.
     */
    fun authenticate(
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
    ) {
        if (!isDeviceSecure()) return
        pendingOnSuccess = onSuccess
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            authenticateWithBiometricPrompt(title, subtitle, onSuccess)
        } else if (!launchLegacyConfirm(title, subtitle)) {
            pendingOnSuccess = null
        }
    }

    /**
     * Routes a legacy keyguard confirmation result to the pending callback.
     *
     * Invoked by the API 28-29 [ActivityResultContracts.StartActivityForResult]
     * launcher wired in [rememberDeviceCredentialAuthenticator].
     *
     * @param confirmed Whether the keyguard returned [Activity.RESULT_OK].
     */
    internal fun onLegacyResult(confirmed: Boolean) {
        val callback = pendingOnSuccess
        pendingOnSuccess = null
        if (confirmed) callback?.invoke()
    }

    private fun authenticateWithBiometricPrompt(
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
    ) {
        val activity = context as? FragmentActivity ?: return
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt =
            BiometricPrompt(
                activity,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(
                        result: BiometricPrompt.AuthenticationResult,
                    ) {
                        pendingOnSuccess = null
                        onSuccess()
                    }
                },
            )
        val promptInfo =
            BiometricPrompt.PromptInfo
                .Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build()
        prompt.authenticate(promptInfo)
    }

    private var pendingOnSuccess: (() -> Unit)? = null
}

/**
 * Creates a [DeviceCredentialAuthenticator] wired into the current composition.
 *
 * On API 28-29 this registers the keyguard
 * [ActivityResultContracts.StartActivityForResult] launcher that the legacy
 * path needs; on API 30+ the launcher is unused. The returned instance is
 * remembered across recompositions.
 *
 * @return A [DeviceCredentialAuthenticator] bound to the hosting activity.
 */
@Composable
fun rememberDeviceCredentialAuthenticator(): DeviceCredentialAuthenticator {
    val context = LocalContext.current
    val authenticatorHolder = remember { arrayOfNulls<DeviceCredentialAuthenticator>(1) }
    val legacyLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            authenticatorHolder[0]?.onLegacyResult(result.resultCode == Activity.RESULT_OK)
        }
    return remember(context, legacyLauncher) {
        DeviceCredentialAuthenticator(
            context = context,
            launchLegacyConfirm = { title, subtitle ->
                val keyguardManager =
                    context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager

                @Suppress("DEPRECATION")
                val intent =
                    keyguardManager?.createConfirmDeviceCredentialIntent(title, subtitle)
                if (intent != null) {
                    legacyLauncher.launch(intent)
                    true
                } else {
                    false
                }
            },
        ).also { authenticatorHolder[0] = it }
    }
}
