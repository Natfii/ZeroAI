/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.service

import android.content.Context
import android.content.SharedPreferences

/**
 * Shared plain-text preferences used by the daemon service lifecycle.
 *
 * Only non-sensitive state belongs here so the boot receiver and foreground
 * service can coordinate without touching encrypted storage.
 */
object DaemonServicePrefs {
    /** SharedPreferences file name for non-sensitive daemon service state. */
    const val PREFS_NAME = "zeroclaw_service"

    /** Key controlling whether the daemon should auto-start after boot. */
    const val KEY_AUTO_START = "auto_start_on_boot"

    /**
     * Returns whether daemon auto-start after boot is enabled.
     *
     * @param context Application context.
     * @return `true` when the daemon should be started after boot completes.
     */
    fun isAutoStartOnBootEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO_START, false)

    /**
     * Persists the daemon auto-start-on-boot setting synchronously.
     *
     * @param context Application context.
     * @param enabled Whether boot auto-start should be enabled.
     * @return `true` when the preference write committed successfully.
     */
    fun setAutoStartOnBoot(
        context: Context,
        enabled: Boolean,
    ): Boolean =
        prefs(context)
            .edit()
            .putBoolean(KEY_AUTO_START, enabled)
            .commit()

    private fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
