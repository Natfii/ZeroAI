/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.zeroclaw.android.MainActivity
import com.zeroclaw.android.R
import com.zeroclaw.android.model.NotificationPriority
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bridge for agent-posted notifications.
 *
 * Manages a dedicated notification channel ("Agent Notifications")
 * separate from the daemon foreground service channel. The agent can
 * post informational, reminder, or alert notifications through this
 * bridge without interfering with the always-on service notification.
 *
 * Notifications are automatically grouped under a summary when three
 * or more are active. A hard cap of [MAX_ACTIVE_NOTIFICATIONS] ensures
 * the notification shade is not overwhelmed; when the limit is reached
 * the oldest notification is cancelled (FIFO).
 *
 * @param context Application context for system services and resources.
 */
class AgentNotificationBridge(
    private val context: Context,
) {
    /** System notification manager. */
    private val notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE)
            as NotificationManager

    /** Monotonically increasing notification ID counter. */
    private val nextId: AtomicInteger = AtomicInteger(FIRST_NOTIFICATION_ID)

    /**
     * Ordered list of active notification IDs, oldest first.
     *
     * Guarded by synchronisation on [activeIds] itself.
     */
    private val activeIds: MutableList<Int> = mutableListOf()

    init {
        createChannel()
    }

    /**
     * Posts a notification from the agent.
     *
     * The notification uses [NotificationCompat.BigTextStyle] so that
     * long messages are fully visible when expanded. Each notification
     * receives a unique ID. When the number of active notifications
     * exceeds [MAX_ACTIVE_NOTIFICATIONS], the oldest is cancelled.
     *
     * @param title Short title displayed in the notification header.
     * @param body Message body; may be arbitrarily long thanks to
     *     [NotificationCompat.BigTextStyle].
     * @param priority Notification priority level. Defaults to
     *     [NotificationPriority.DEFAULT].
     * @return The notification ID assigned to this notification.
     */
    fun notify(
        title: String,
        body: String,
        priority: NotificationPriority = NotificationPriority.DEFAULT,
    ): Int {
        val safeTitle = title.take(MAX_TITLE_LENGTH)
        val safeBody = body.take(MAX_BODY_LENGTH)
        val id = nextId.getAndIncrement()
        val notification = buildNotification(safeTitle, safeBody, priority)
        trackAndEnforce(id)
        notificationManager.notify(id, notification)
        updateSummaryIfNeeded()
        return id
    }

    /**
     * Posts a notification with a tap action.
     *
     * Behaves like [notify] but adds an action button with the given
     * label and intent. The notification is also made auto-cancel so
     * it dismisses when the action is tapped.
     *
     * @param title Short title displayed in the notification header.
     * @param body Message body.
     * @param actionLabel Label for the action button.
     * @param actionIntent [PendingIntent] fired when the action is tapped.
     * @param priority Notification priority bucket used for channel routing.
     * @return The notification ID assigned to this notification.
     */
    fun notifyWithAction(
        title: String,
        body: String,
        actionLabel: String,
        actionIntent: PendingIntent,
        priority: NotificationPriority = NotificationPriority.DEFAULT,
    ): Int {
        val safeTitle = title.take(MAX_TITLE_LENGTH)
        val safeBody = body.take(MAX_BODY_LENGTH)
        val id = nextId.getAndIncrement()
        val notification =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(safeTitle)
                .setContentText(safeBody)
                .setStyle(NotificationCompat.BigTextStyle().bigText(safeBody))
                .setPriority(priority.compatPriority)
                .setContentIntent(buildContentIntent())
                .setAutoCancel(true)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true)
                .setGroup(GROUP_KEY)
                .addAction(0, actionLabel, actionIntent)
                .build()
        trackAndEnforce(id)
        notificationManager.notify(id, notification)
        updateSummaryIfNeeded()
        return id
    }

    /**
     * Cancels a specific agent notification by its ID.
     *
     * @param id The notification ID returned by [notify] or
     *     [notifyWithAction].
     */
    fun cancelNotification(id: Int) {
        notificationManager.cancel(id)
        synchronized(activeIds) {
            activeIds.remove(id)
        }
        updateSummaryIfNeeded()
    }

    /**
     * Cancels all agent notifications, including the summary.
     */
    fun cancelAll() {
        synchronized(activeIds) {
            for (id in activeIds) {
                notificationManager.cancel(id)
            }
            activeIds.clear()
        }
        notificationManager.cancel(SUMMARY_NOTIFICATION_ID)
    }

    /**
     * Creates the agent notification channel if it does not already exist.
     *
     * Uses [NotificationManager.IMPORTANCE_LOW] so that agent-generated
     * notifications appear in the shade but do not produce sound or
     * heads-up display. This prevents agent-controlled content from
     * appearing prominently on the lock screen. Users can raise the
     * importance in system notification settings if desired.
     */
    private fun createChannel() {
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = CHANNEL_DESCRIPTION
                setShowBadge(true)
            }
        notificationManager.createNotificationChannel(channel)
    }

    /**
     * Builds a standard agent notification.
     *
     * @param title Notification title.
     * @param body Notification body text.
     * @param priority Priority level.
     * @return A built [android.app.Notification].
     */
    private fun buildNotification(
        title: String,
        body: String,
        priority: NotificationPriority,
    ): android.app.Notification =
        NotificationCompat
            .Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(priority.compatPriority)
            .setContentIntent(buildContentIntent())
            .setAutoCancel(true)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setGroup(GROUP_KEY)
            .build()

    /**
     * Builds a [PendingIntent] that opens [MainActivity] when the
     * notification is tapped.
     *
     * @return An immutable [PendingIntent] targeting the main activity.
     */
    private fun buildContentIntent(): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /**
     * Tracks a new notification ID and enforces the FIFO cap.
     *
     * If [activeIds] exceeds [MAX_ACTIVE_NOTIFICATIONS] after adding
     * the new [id], the oldest notification is cancelled.
     *
     * @param id The notification ID to track.
     */
    private fun trackAndEnforce(id: Int) {
        synchronized(activeIds) {
            activeIds.add(id)
            while (activeIds.size > MAX_ACTIVE_NOTIFICATIONS) {
                val oldest = activeIds.removeAt(0)
                notificationManager.cancel(oldest)
            }
        }
    }

    /**
     * Posts or cancels the summary notification based on active count.
     *
     * A summary notification groups individual notifications under a
     * single expandable entry once [GROUP_THRESHOLD] or more are active.
     * When the count drops below the threshold, the summary is removed.
     */
    private fun updateSummaryIfNeeded() {
        val count =
            synchronized(activeIds) {
                activeIds.size
            }
        if (count >= GROUP_THRESHOLD) {
            val summary =
                NotificationCompat
                    .Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("ZeroAI")
                    .setContentText("$count agent notifications")
                    .setStyle(
                        NotificationCompat
                            .InboxStyle()
                            .setSummaryText("$count notifications"),
                    ).setGroup(GROUP_KEY)
                    .setGroupSummary(true)
                    .setAutoCancel(true)
                    .build()
            notificationManager.notify(SUMMARY_NOTIFICATION_ID, summary)
        } else {
            notificationManager.cancel(SUMMARY_NOTIFICATION_ID)
        }
    }

    /** Constants for [AgentNotificationBridge]. */
    companion object {
        /** Notification channel identifier for agent notifications. */
        const val CHANNEL_ID = "agent_notifications"

        /** Human-readable channel name. */
        private const val CHANNEL_NAME = "Agent Notifications"

        /** Channel description shown in system settings. */
        private const val CHANNEL_DESCRIPTION =
            "Notifications posted by the AI agent"

        /** Group key for bundling agent notifications. */
        private const val GROUP_KEY = "com.zeroclaw.android.AGENT_NOTIFICATIONS"

        /** Maximum number of active agent notifications (FIFO). */
        const val MAX_ACTIVE_NOTIFICATIONS = 25

        /**
         * Minimum number of active notifications before a summary
         * group is created.
         */
        private const val GROUP_THRESHOLD = 3

        /**
         * Starting notification ID for agent notifications.
         *
         * Offset from the daemon notification ID (1) to avoid collisions.
         */
        private const val FIRST_NOTIFICATION_ID = 1000

        /** Notification ID for the group summary. */
        private const val SUMMARY_NOTIFICATION_ID = 999

        /** Maximum allowed length for notification titles. */
        private const val MAX_TITLE_LENGTH = 100

        /** Maximum allowed length for notification bodies. */
        private const val MAX_BODY_LENGTH = 500
    }
}
