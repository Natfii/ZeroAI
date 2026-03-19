/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Starts [ZeroAIDaemonService] after the device finishes booting.
 *
 * Registered in the manifest with [Intent.ACTION_BOOT_COMPLETED]. The
 * service only auto-starts if the user has previously enabled the
 * auto-start preference stored by [DaemonServicePrefs].
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val autoStart = DaemonServicePrefs.isAutoStartOnBootEnabled(context)
        if (!autoStart) return

        val serviceIntent =
            Intent(
                context,
                ZeroAIDaemonService::class.java,
            ).apply {
                action = ZeroAIDaemonService.ACTION_START
            }
        context.startForegroundService(serviceIntent)
    }
}
