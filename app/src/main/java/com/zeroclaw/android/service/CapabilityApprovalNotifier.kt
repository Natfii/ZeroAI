/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Manages Android notifications for pending capability approval requests.
 *
 * Creates a dedicated notification channel and posts one notification per
 * pending approval. Each notification has Approve and Deny action buttons
 * that broadcast to [CapabilityApprovalReceiver].
 *
 * @param context Application context for notification access.
 */
class CapabilityApprovalNotifier(
    private val context: Context,
) {
    /** Constants for [CapabilityApprovalNotifier]. */
    companion object {
        /** Notification channel ID for capability approval prompts. */
        const val CHANNEL_ID = "capability_approvals"

        /** Base notification ID — offset by request counter for uniqueness. */
        private const val NOTIFICATION_ID_BASE = 9000
    }

    init {
        createChannel()
    }

    /**
     * Creates the notification channel for capability approval prompts.
     *
     * Uses [NotificationManager.IMPORTANCE_HIGH] so that approval prompts
     * appear as heads-up notifications, ensuring the user sees them promptly.
     * Safe to call multiple times; the system ignores duplicate channel creation.
     */
    private fun createChannel() {
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                "Skill Permissions",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Approval prompts when skills request dangerous capabilities"
            }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    /**
     * Posts a notification for a pending capability approval.
     *
     * Safe to call from any thread. No-ops if notification permission is
     * not granted (Android 13+).
     *
     * @param requestId Unique ID from the Rust pending approval queue.
     * @param skillName Name of the skill requesting the capability.
     * @param capability The capability being requested.
     */
    @Suppress("MissingPermission")
    fun notifyPendingApproval(
        requestId: String,
        skillName: String,
        capability: String,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val approveIntent =
            Intent(context, CapabilityApprovalReceiver::class.java).apply {
                action = "com.zeroclaw.APPROVE_CAPABILITY"
                putExtra("request_id", requestId)
                putExtra("approved", true)
            }
        val denyIntent =
            Intent(context, CapabilityApprovalReceiver::class.java).apply {
                action = "com.zeroclaw.DENY_CAPABILITY"
                putExtra("request_id", requestId)
                putExtra("approved", false)
            }

        val notificationId = NOTIFICATION_ID_BASE + requestId.hashCode()

        val approvePending =
            PendingIntent.getBroadcast(
                context,
                notificationId,
                approveIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val denyPending =
            PendingIntent.getBroadcast(
                context,
                notificationId + 1,
                denyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val notification =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("Skill permission request")
                .setContentText("$skillName wants $capability")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .addAction(0, "Approve", approvePending)
                .addAction(0, "Deny", denyPending)
                .build()

        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }

    /**
     * Dismisses the notification for a resolved approval request.
     *
     * @param requestId The request ID whose notification should be dismissed.
     */
    fun dismissNotification(requestId: String) {
        val notificationId = NOTIFICATION_ID_BASE + requestId.hashCode()
        NotificationManagerCompat.from(context).cancel(notificationId)
    }
}
