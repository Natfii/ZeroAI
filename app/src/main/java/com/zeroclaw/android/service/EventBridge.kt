/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import android.util.Log
import com.zeroclaw.android.data.repository.ActivityRepository
import com.zeroclaw.android.model.ActivityType
import com.zeroclaw.android.model.DaemonEvent
import com.zeroclaw.android.tailscale.PeerMatchResult
import com.zeroclaw.android.tailscale.PeerMessageRouter
import com.zeroclaw.android.tailscale.PeerRouteEntry
import com.zeroclaw.ffi.FfiEventListener
import com.zeroclaw.ffi.FfiException
import com.zeroclaw.ffi.PeerChannelKind
import com.zeroclaw.ffi.TailnetServiceKind
import com.zeroclaw.ffi.peerSendChannelResponse
import com.zeroclaw.ffi.peerSendMessage
import com.zeroclaw.ffi.resolveCapabilityRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Bridge between the Rust event callback interface and the Kotlin reactive layer.
 *
 * Implements [FfiEventListener] so it can be registered with the native daemon via
 * [com.zeroclaw.ffi.registerEventListener]. Incoming JSON event strings are parsed
 * into [DaemonEvent] instances and emitted on a [SharedFlow] for ViewModel consumption.
 * Each event is also persisted to the [ActivityRepository] for the dashboard feed.
 *
 * Incoming `channel_message` events with an `@alias` prefix are intercepted before
 * normal flow emission and routed to the matching tailnet peer via [peerSendMessage].
 * The peer response is relayed back through [peerSendChannelResponse] to the originating
 * channel. If the peer is unreachable an error reply is sent instead.
 *
 * The internal [MutableSharedFlow] uses [BufferOverflow.DROP_OLDEST] with a capacity of
 * 64 events to avoid back-pressure blocking the native callback thread.
 *
 * @param activityRepository Repository for persisting events to the activity feed.
 * @param scope Coroutine scope for asynchronous emission and persistence.
 * @param getPeers Supplier for the current list of enabled peer route entries.
 * @param getPeerToken Retrieves a stored bearer token for a peer by IP and port.
 * @param notifier Notifier for capability approval Android notifications.
 */
class EventBridge(
    private val activityRepository: ActivityRepository,
    private val scope: CoroutineScope,
    private val getPeers: () -> List<PeerRouteEntry> = { emptyList() },
    private val getPeerToken: (String, Int) -> String? = { _, _ -> null },
    private val notifier: CapabilityApprovalNotifier? = null,
) : FfiEventListener {
    private val _events =
        MutableSharedFlow<DaemonEvent>(
            extraBufferCapacity = BUFFER_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    /** Observable stream of daemon events, parsed from the native callback JSON. */
    val events: SharedFlow<DaemonEvent> = _events.asSharedFlow()

    /**
     * Called by the native layer when a new event is produced.
     *
     * Parses the JSON string into a [DaemonEvent]. Incoming `channel_message` events
     * whose content starts with an `@alias` prefix are intercepted for peer routing via
     * [handlePeerRoute]; all other events are emitted on [events] and recorded in the
     * [ActivityRepository]. Malformed JSON is logged at warning level and dropped.
     *
     * @param eventJson Raw JSON event string from the Rust daemon.
     */
    override fun onEvent(eventJson: String) {
        val event = parseEvent(eventJson) ?: return

        if (event.kind == "channel_message" && event.data["direction"] == "in") {
            val content = event.data["content"]
            val channel = event.data["channel"]
            val sender = event.data["sender"]
            if (content != null && channel != null && sender != null) {
                val match = PeerMessageRouter.matchAlias(content, getPeers())
                if (match != null) {
                    scope.launch(Dispatchers.IO) {
                        handlePeerRoute(match, channel, sender)
                    }
                    return
                }
            }
        }

        if (event.kind == "capability_approval_required") {
            scope.launch(Dispatchers.IO) {
                handleCapabilityApproval(event.data)
            }
            return
        }

        scope.launch {
            _events.emit(event)
            val (type, message) = event.toActivityRecord()
            activityRepository.record(type, message)
        }
    }

    /**
     * Routes a matched peer message and relays the response through the originating channel.
     *
     * @param match The alias match result containing peer info and stripped message.
     * @param channel The originating channel name (e.g. "telegram", "discord").
     * @param sender The message sender identifier for reply routing.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun handlePeerRoute(
        match: PeerMatchResult,
        channel: String,
        sender: String,
    ) {
        try {
            val token = getPeerToken(match.peer.ip, match.peer.port)
            val kind =
                when (match.peer.kind.lowercase()) {
                    "openclaw" -> TailnetServiceKind.OPEN_CLAW
                    else -> TailnetServiceKind.ZEROCLAW
                }
            val response =
                withContext(Dispatchers.IO) {
                    peerSendMessage(
                        match.peer.ip,
                        match.peer.port.toUShort(),
                        kind,
                        token,
                        match.strippedMessage,
                    )
                }
            val channelKind =
                when (channel) {
                    "telegram" -> PeerChannelKind.TELEGRAM
                    "discord" -> PeerChannelKind.DISCORD
                    else -> return
                }
            peerSendChannelResponse(
                channelKind,
                sender,
                "{@${match.alias}} $response",
            )
        } catch (e: Exception) {
            Log.w(EVENT_BRIDGE_TAG, "Peer route to ${match.alias} failed", e)
            val channelKind =
                when (channel) {
                    "telegram" -> PeerChannelKind.TELEGRAM
                    "discord" -> PeerChannelKind.DISCORD
                    else -> return
                }
            try {
                peerSendChannelResponse(
                    channelKind,
                    sender,
                    "{@${match.alias}} not reachable right now.",
                )
            } catch (
                @Suppress("SwallowedException") relayErr: Exception,
            ) {
                Log.w(EVENT_BRIDGE_TAG, "Failed to relay peer error to $channel")
            }
        }
    }

    /**
     * Routes a capability approval request to the appropriate handler.
     *
     * Terminal/REPL sessions are auto-approved (device owner initiated).
     * All other surfaces dispatch an Android notification with Approve/Deny
     * action buttons via [CapabilityApprovalNotifier].
     *
     * @param data Event data map containing request_id, skill_name, capability, triggered_via.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun handleCapabilityApproval(data: Map<String, String>) {
        val requestId = data["request_id"] ?: return
        val skillName = data["skill_name"] ?: "unknown"
        val capability = data["capability"] ?: "unknown"
        val triggeredVia = data["triggered_via"] ?: "unknown"

        Log.d(
            EVENT_BRIDGE_TAG,
            "Capability approval requested: $skillName wants $capability (via $triggeredVia)",
        )

        if (triggeredVia == "terminal" || triggeredVia == "repl") {
            scope.launch(Dispatchers.IO) {
                try {
                    resolveCapabilityRequest(requestId, true)
                } catch (e: Exception) {
                    Log.w(EVENT_BRIDGE_TAG, "Failed to auto-approve $requestId", e)
                }
            }
            return
        }

        notifier?.notifyPendingApproval(requestId, skillName, capability)
    }

    /**
     * Registers this bridge as the active event listener with the native daemon.
     *
     * **Must be called from a background thread** ([kotlinx.coroutines.Dispatchers.IO]);
     * this is a blocking FFI call that acquires a native mutex.
     *
     * Only one listener may be registered at a time; calling this replaces any
     * previously registered listener.
     *
     * @throws com.zeroclaw.ffi.FfiException.StateException if the native event
     *   listener mutex is permanently poisoned.
     */
    @Throws(FfiException::class)
    fun register() {
        com.zeroclaw.ffi.registerEventListener(this)
    }

    /**
     * Removes this bridge as the active event listener from the native daemon.
     *
     * **Must be called from a background thread** ([kotlinx.coroutines.Dispatchers.IO]);
     * this is a blocking FFI call that acquires a native mutex.
     *
     * After calling this method, no further [onEvent] callbacks will be received.
     *
     * @throws com.zeroclaw.ffi.FfiException.StateException if the native event
     *   listener mutex is permanently poisoned.
     */
    @Throws(FfiException::class)
    fun unregister() {
        com.zeroclaw.ffi.unregisterEventListener()
    }

    /** Constants for [EventBridge]. */
    companion object {
        private const val BUFFER_CAPACITY = 64
    }
}

private const val EVENT_BRIDGE_TAG = "EventBridge"

/**
 * Parses a raw JSON event string into a [DaemonEvent].
 *
 * Expected schema: `{"id":N,"timestamp_ms":N,"kind":"...","data":{...}}`.
 * Malformed JSON is logged at warning level and returns null to avoid
 * crashing the native callback thread.
 *
 * @param json Raw JSON string from the native callback.
 * @return Parsed [DaemonEvent], or `null` if the JSON is malformed.
 */
private fun parseEvent(json: String): DaemonEvent? =
    try {
        val obj = JSONObject(json)
        val dataObj = obj.optJSONObject("data") ?: JSONObject()
        val dataMap = mutableMapOf<String, String>()
        for (key in dataObj.keys()) {
            dataMap[key] = dataObj.optString(key, "")
        }
        DaemonEvent(
            id = obj.getLong("id"),
            timestampMs = obj.getLong("timestamp_ms"),
            kind = obj.getString("kind"),
            data = dataMap,
        )
    } catch (
        @Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception,
    ) {
        Log.w(EVENT_BRIDGE_TAG, "Malformed event JSON — skipping")
        null
    }

/**
 * Maps a [DaemonEvent] to an [ActivityType] and human-readable message for persistence.
 *
 * @receiver The daemon event to convert.
 * @return Pair of [ActivityType] and formatted message string.
 */
private fun DaemonEvent.toActivityRecord(): Pair<ActivityType, String> {
    val type =
        when (kind) {
            "error" -> ActivityType.DAEMON_ERROR
            else -> ActivityType.FFI_CALL
        }
    val message =
        when (kind) {
            "llm_request" -> "LLM Request: ${data["provider"]} / ${data["model"]}"
            "llm_response" -> "LLM Response: ${data["provider"]} (${data["duration_ms"]}ms)"
            "tool_call" -> "Tool: ${data["tool"]} (${data["duration_ms"]}ms)"
            "tool_call_start" -> "Tool Starting: ${data["tool"]}"
            "channel_message" -> "Channel: ${data["channel"]} (${data["direction"]})"
            "error" -> "Error: ${data["component"]} — ${sanitizeActivityMessage(data["message"])}"
            "heartbeat_tick" -> "Heartbeat"
            "turn_complete" -> "Turn Complete"
            "agent_start" -> "Agent Start: ${data["provider"]} / ${data["model"]}"
            "agent_end" -> "Agent End (${data["duration_ms"]}ms)"
            else -> "Event: $kind"
        }
    return type to message
}

/** Maximum length for error messages recorded in the activity feed. */
private const val MAX_ACTIVITY_MESSAGE_LENGTH = 120

/** Pattern matching URLs in error messages. */
private val URL_PATTERN = Regex("""https?://\S+""")

/**
 * Truncates and strips URLs from an error message for activity feed display.
 *
 * @param msg Raw error message from the daemon event, or `null`.
 * @return Sanitised message safe for the activity feed.
 */
private fun sanitizeActivityMessage(msg: String?): String {
    if (msg.isNullOrBlank()) return "unknown"
    val stripped = msg.replace(URL_PATTERN, "[url]")
    return if (stripped.length > MAX_ACTIVITY_MESSAGE_LENGTH) {
        stripped.take(MAX_ACTIVITY_MESSAGE_LENGTH) + "..."
    } else {
        stripped
    }
}
