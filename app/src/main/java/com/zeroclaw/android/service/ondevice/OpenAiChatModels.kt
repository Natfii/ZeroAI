/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.service.ondevice

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * Minimal subset of OpenAI's `/v1/chat/completions` request shape
 * that [LiteRtHttpServer] understands.
 *
 * The Rust daemon's existing OpenAI-compatible provider client
 * (and any external tooling like `curl` or LangChain) emits this
 * shape; we accept enough to route the last user message into the
 * loaded LiteRT-LM engine. Unsupported fields (tools, functions,
 * temperature, top_p, …) are deliberately not modelled — they're
 * ignored when present so future client updates don't break us.
 *
 * @property model Model name string. Echoed back in the response
 *   but otherwise ignored — the loaded engine is the only model
 *   available on this endpoint regardless of what the client asks
 *   for.
 * @property messages Ordered conversation history. Only the latest
 *   `user` message is sent to the engine; prior turns rely on the
 *   underlying [com.google.ai.edge.litertlm.Conversation]'s own
 *   history. Slice 3c will reconcile these.
 * @property stream When `true`, the server responds with SSE
 *   `text/event-stream` chunks; otherwise a single JSON envelope.
 * @property tools Optional list of OpenAI tool/function definitions
 *   the model is allowed to invoke. Passed through raw to the engine.
 */
@Serializable
data class OpenAiChatRequest(
    val model: String? = null,
    val messages: List<OpenAiChatMessage> = emptyList(),
    val stream: Boolean = false,
    val tools: JsonArray? = null,
)

/**
 * Single turn in an OpenAI chat-completion request or response.
 *
 * The `content` field is intentionally a raw [JsonElement] rather
 * than a `String`: OpenAI's chat API accepts both shapes —
 *  - a plain string for text-only turns, or
 *  - an array of `{type:"text", text:...}` / `{type:"image_url", ...}`
 *    objects for vision/multimodal turns.
 *
 * If we model `content` as `String` the serializer fails on the
 * vision shape, the handler then emits a multi-line exception
 * message in the HTTP status line, and the client (hyper) reports
 * "invalid HTTP header parsed" — exactly the crash we hit when the
 * daemon forwarded a Discord image attachment.
 *
 * Use [textContent] from the server to extract a plain-text prompt
 * for engines that don't accept images yet.
 *
 * @property role One of `system`, `user`, `assistant`. Anything
 *   else is treated as `user` by the server.
 * @property content Either a JSON string or an array of OpenAI
 *   content parts.
 * @property toolCalls Optional array of tool-call invocations emitted
 *   by the assistant on this turn. Mirrors OpenAI's `tool_calls` field.
 * @property toolCallId When `role == "tool"`, the id of the tool call
 *   this message is the result for. Null otherwise.
 */
@Serializable
data class OpenAiChatMessage(
    val role: String,
    val content: JsonElement = JsonPrimitive(""),
    @kotlinx.serialization.SerialName("tool_calls")
    val toolCalls: JsonArray? = null,
    @kotlinx.serialization.SerialName("tool_call_id")
    val toolCallId: String? = null,
)

/**
 * A function-call invocation the model emitted, mirrored into the
 * OpenAI `tool_calls[].function` shape. The daemon's agent loop
 * dispatches this back to the configured Tool trait and sends the
 * result on a subsequent request as a `role: "tool"` message.
 *
 * @property name Function name the model wants to call.
 * @property arguments JSON-encoded arguments string per OpenAI's
 *   convention (the value is itself a string containing JSON, not a
 *   JSON object — keep that shape so clients don't have to special-
 *   case our endpoint).
 */
@Serializable
data class OpenAiFunctionCall(
    val name: String,
    val arguments: String,
)

/**
 * One entry in an assistant message's `tool_calls` array.
 *
 * @property id Unique identifier the client uses to correlate the
 *   tool result message back to the call.
 * @property type Always `"function"` — the only call kind OpenAI
 *   currently defines.
 * @property function Concrete function + arguments the model emitted.
 */
@Serializable
data class OpenAiToolCall(
    val id: String,
    val type: String = "function",
    val function: OpenAiFunctionCall,
)

/**
 * Extracts the plain-text portion of [OpenAiChatMessage.content].
 *
 * Handles:
 *  - String shape: returns the string verbatim.
 *  - Array shape: concatenates every entry with `type == "text"`,
 *    skipping `image_url` / `audio` / unknown parts. Returns the
 *    joined text, or empty when the array has no text parts.
 *
 * Slice 3c v1 only routes text to LiteRT-LM. Image and audio parts
 * are dropped silently; once we wire LiteRT-LM's vision/audio
 * backends here, this helper grows return types for those modes.
 */
val OpenAiChatMessage.textContent: String
    get() =
        when (val raw = content) {
            is JsonPrimitive -> if (raw.isString) raw.content else raw.content
            is JsonArray ->
                raw
                    .asSequence()
                    .filterIsInstance<JsonObject>()
                    .filter { it["type"]?.jsonPrimitive?.content == "text" }
                    .mapNotNull { it["text"]?.jsonPrimitive?.content }
                    .joinToString(separator = "\n")
            else -> ""
        }

/**
 * Builds an assistant-role message carrying a plain-text [text]
 * payload. Used by the server to construct streaming and
 * non-streaming response envelopes.
 */
fun assistantTextMessage(text: String): OpenAiChatMessage = OpenAiChatMessage(role = "assistant", content = JsonPrimitive(text))

/**
 * Non-streaming chat-completion response envelope.
 *
 * @property id Stable identifier for the completion. UUID-derived.
 * @property `object` Always `"chat.completion"` — matches OpenAI.
 * @property created Unix seconds when the response was finalised.
 * @property model Echo of the requested model name (or the
 *   server's default when the request omitted it).
 * @property choices Always a single-element list — the LiteRT-LM
 *   engine doesn't produce multiple candidates.
 */
@Suppress("OutdatedDocumentation", "UndocumentedPublicProperty", "ConstructorParameterNaming")
@Serializable
data class OpenAiChatResponse(
    val id: String,
    val `object`: String,
    val created: Long,
    val model: String,
    val choices: List<OpenAiChatChoice>,
)

/**
 * Single completion choice within an [OpenAiChatResponse].
 *
 * @property index Always `0` — we only produce one candidate.
 * @property message The full assistant turn. Populated on
 *   non-streaming responses; null on streaming deltas.
 * @property delta Incremental update used in SSE chunks. Null on
 *   non-streaming responses.
 * @property finishReason `"stop"` on normal completion, null while
 *   streaming, `"error"` on failure.
 */
@Serializable
data class OpenAiChatChoice(
    val index: Int = 0,
    val message: OpenAiChatMessage? = null,
    val delta: OpenAiChatMessage? = null,
    @kotlinx.serialization.SerialName("finish_reason")
    val finishReason: String? = null,
)

/**
 * Builds an assistant message carrying tool_calls. Used when the
 * model emits a `tool_calls` Message instead of plain text — the
 * server returns this envelope with `finish_reason: "tool_calls"`
 * so the daemon's agent loop knows to dispatch the calls.
 */
fun assistantToolCallsMessage(toolCalls: List<OpenAiToolCall>): OpenAiChatMessage {
    val array =
        JsonArray(
            toolCalls.map { call ->
                JsonObject(
                    mapOf(
                        "id" to JsonPrimitive(call.id),
                        "type" to JsonPrimitive(call.type),
                        "function" to
                            JsonObject(
                                mapOf(
                                    "name" to JsonPrimitive(call.function.name),
                                    "arguments" to JsonPrimitive(call.function.arguments),
                                ),
                            ),
                    ),
                )
            },
        )
    return OpenAiChatMessage(
        role = "assistant",
        content = JsonPrimitive(""),
        toolCalls = array,
    )
}
