/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.model

import androidx.core.app.NotificationCompat

/**
 * Priority levels for agent-posted notifications.
 *
 * Maps to the corresponding [NotificationCompat] priority constants
 * so the bridge layer can translate without exposing Android framework
 * types to callers.
 *
 * @property compatPriority The [NotificationCompat] priority constant
 *     corresponding to this level.
 */
enum class NotificationPriority(
    val compatPriority: Int,
) {
    /** Low priority: no sound, appears below default notifications. */
    LOW(NotificationCompat.PRIORITY_LOW),

    /** Default priority: may produce sound depending on channel settings. */
    DEFAULT(NotificationCompat.PRIORITY_DEFAULT),

    /** High priority: may produce heads-up display depending on channel settings. */
    HIGH(NotificationCompat.PRIORITY_HIGH),
}
