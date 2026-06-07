/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.service.ondevice

/**
 * One event in an OpenAI-compatible streaming completion.
 *
 * The HTTP server collects these from
 * [OnDeviceInferenceManager.serveCompletion] and turns them into:
 *  - SSE deltas (`data: {...}\n\n`) when `stream=true`
 *  - a single non-streaming envelope when `stream=false`
 *
 * The two variants exist because LiteRT-LM's `Conversation` can
 * settle on either plain text *or* a structured tool-call response —
 * we hand the daemon's agent loop either one, and the daemon
 * dispatches tool calls back through its existing Tool trait.
 */
sealed interface OpenAiResponseEvent {
    /**
     * A text chunk produced by the model. Streaming paths emit several
     * as the model generates; the on-device [OnDeviceInferenceManager.serveCompletion]
     * path instead buffers the full reply and emits a single cleaned chunk
     * (so the Gemma reply scrub can operate on the whole text). Either way
     * the daemon concatenates chunks into one assistant message.
     *
     * @property text The text chunk.
     */
    data class TextDelta(
        val text: String,
    ) : OpenAiResponseEvent

    /**
     * The model emitted a tool-call response instead of text. The
     * HTTP server returns this in the `choices[0].message.tool_calls`
     * field with `finish_reason = "tool_calls"`. The daemon's agent
     * loop then dispatches each call and re-invokes the endpoint
     * with the results as `role: "tool"` messages.
     *
     * Emitted at most once per completion — once the model goes
     * tool-call, the rest of the turn is held until the daemon
     * supplies results.
     *
     * @property toolCalls One or more OpenAI-shaped tool calls.
     */
    data class ToolCallsEmitted(
        val toolCalls: List<OpenAiToolCall>,
    ) : OpenAiResponseEvent

    /**
     * Generation failed. The HTTP server surfaces this as a `500`
     * with the reason in the response body so the daemon's agent
     * loop can log it without crashing the channel session.
     *
     * @property reason User-facing failure description.
     */
    data class Failed(
        val reason: String,
    ) : OpenAiResponseEvent
}
