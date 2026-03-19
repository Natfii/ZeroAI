/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.data.discord

import android.content.Context
import android.util.Log
import com.zeroclaw.ffi.discordConfigureChannel
import com.zeroclaw.ffi.discordRemoveChannel
import org.json.JSONArray
import org.json.JSONObject

/**
 * Queues Discord archive channel operations when the daemon is offline.
 *
 * Persists a sequential list of add/remove operations in SharedPreferences
 * as a JSON array. Operations are replayed in timestamp order when the
 * daemon starts via [drain].
 */
object PendingDiscordOpsStore {
    private const val TAG = "PendingDiscordOps"
    private const val PREFS_NAME = "discord_pending_ops"
    private const val KEY_OPS = "ops"

    /**
     * Enqueues an add-channel operation.
     *
     * @param context Application context.
     * @param channelId Discord channel snowflake ID.
     * @param guildId Discord guild snowflake ID.
     * @param channelName Human-readable channel name.
     * @param backfillDepth Backfill depth config value.
     */
    fun enqueueAdd(
        context: Context,
        channelId: String,
        guildId: String,
        channelName: String,
        backfillDepth: String,
    ) {
        val op =
            JSONObject().apply {
                put("type", "ADD")
                put("channelId", channelId)
                put("guildId", guildId)
                put("channelName", channelName)
                put("backfillDepth", backfillDepth)
                put("timestamp", System.currentTimeMillis())
            }
        appendOp(context, op)
        Log.i(TAG, "Queued ADD for channel $channelId")
    }

    /**
     * Enqueues a remove-channel operation.
     *
     * @param context Application context.
     * @param channelId Discord channel snowflake ID to remove.
     */
    fun enqueueRemove(
        context: Context,
        channelId: String,
    ) {
        val op =
            JSONObject().apply {
                put("type", "REMOVE")
                put("channelId", channelId)
                put("timestamp", System.currentTimeMillis())
            }
        appendOp(context, op)
        Log.i(TAG, "Queued REMOVE for channel $channelId")
    }

    /**
     * Drains all pending operations by calling the FFI functions.
     *
     * Operations are replayed in timestamp order. Successfully applied
     * ops are removed from the queue. Failed ops remain for the next
     * drain attempt.
     *
     * @param context Application context.
     */
    @Suppress("TooGenericExceptionCaught")
    fun drain(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_OPS, null) ?: return
        val array =
            try {
                JSONArray(raw)
            } catch (_: Exception) {
                prefs.edit().remove(KEY_OPS).apply()
                return
            }
        if (array.length() == 0) return

        Log.i(TAG, "Draining ${array.length()} pending ops")
        val remaining = JSONArray()
        for (i in 0 until array.length()) {
            val op = array.getJSONObject(i)
            try {
                when (op.getString("type")) {
                    "ADD" ->
                        discordConfigureChannel(
                            op.getString("channelId"),
                            op.getString("guildId"),
                            op.getString("channelName"),
                            op.getString("backfillDepth"),
                        )
                    "REMOVE" ->
                        discordRemoveChannel(
                            op.getString("channelId"),
                        )
                }
                Log.i(TAG, "Applied ${op.getString("type")} for ${op.getString("channelId")}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to apply op, will retry: ${e.message}")
                remaining.put(op)
            }
        }
        if (remaining.length() == 0) {
            prefs.edit().remove(KEY_OPS).apply()
        } else {
            prefs.edit().putString(KEY_OPS, remaining.toString()).apply()
        }
    }

    /** Returns `true` if there are pending operations. */
    fun hasPending(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_OPS, null) ?: return false
        return try {
            JSONArray(raw).length() > 0
        } catch (_: Exception) {
            false
        }
    }

    private fun appendOp(
        context: Context,
        op: JSONObject,
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_OPS, null)
        val array =
            if (raw != null) {
                try {
                    JSONArray(raw)
                } catch (_: Exception) {
                    JSONArray()
                }
            } else {
                JSONArray()
            }
        array.put(op)
        prefs.edit().putString(KEY_OPS, array.toString()).apply()
    }
}
