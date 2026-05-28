/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.service.ondevice

import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.Role
import com.google.ai.edge.litertlm.ToolCall
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.tool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull

/*
 * Adapters that turn OpenAI chat-completion shapes into the
 * LiteRT-LM types `Conversation` expects, and vice-versa for the
 * response side. Kept in its own file because the conversion logic
 * spans two conventions (OpenAI's JSON shapes + LiteRT-LM's typed
 * Kotlin data classes).
 */

/**
 * Translates an OpenAI message role string into LiteRT-LM's [Role]
 * enum. Falls back to `USER` for unknown roles so a malformed
 * request still routes the message somewhere instead of throwing.
 *
 * @param role OpenAI role string (`system` / `user` / `assistant` /
 *   `tool`).
 */
fun openAiRoleToLiteRt(role: String): Role =
    when (role.lowercase()) {
        "system" -> Role.SYSTEM
        "user" -> Role.USER
        "assistant" -> Role.MODEL
        "tool" -> Role.TOOL
        else -> Role.USER
    }

/**
 * Maps an OpenAI chat message into a LiteRT-LM [Message] suitable
 * for [com.google.ai.edge.litertlm.ConversationConfig.initialMessages].
 *
 * Only the textual portion of `content` is forwarded — image and
 * audio parts are dropped at this layer (Slice 3d is text + tools;
 * vision wiring lands separately).
 *
 * For `role: "tool"` messages the OpenAI client is supplying a tool
 * execution result; we put the result text into [Contents] and rely
 * on LiteRT-LM's tool-call protocol to associate it with the prior
 * tool call by position.
 */
fun openAiMessageToLiteRt(message: OpenAiChatMessage): Message {
    val contents = Contents.of(message.textContent)
    return when (openAiRoleToLiteRt(message.role)) {
        Role.SYSTEM -> Message.system(contents)
        Role.USER -> Message.user(contents)
        Role.MODEL -> {
            // Preserve any tool_calls the prior assistant turn carried —
            // without this the model loses sight of which tool it just
            // asked for, and the subsequent role:"tool" result message
            // becomes ungrounded. That breaks every multi-turn tool
            // flow (memory recall, web fetch, peer send, …).
            val priorToolCalls = parseOpenAiToolCalls(message.toolCalls)
            if (priorToolCalls.isEmpty()) {
                Message.model(contents)
            } else {
                Message.model(contents, priorToolCalls, emptyMap())
            }
        }
        Role.TOOL -> Message.tool(contents)
    }
}

/**
 * Parses the OpenAI `assistant.tool_calls` array (as the daemon
 * relays it back to us mid-multi-turn) into the LiteRT-LM
 * [ToolCall] shape so we can stitch it onto the prior assistant
 * turn via `Message.model(contents, toolCalls, channels)`.
 *
 * Returns an empty list when [toolCalls] is null, empty, or
 * contains entries with no `function` object.
 */
private fun parseOpenAiToolCalls(toolCalls: JsonArray?): List<ToolCall> {
    if (toolCalls.isNullOrEmpty()) return emptyList()
    return toolCalls.mapNotNull { entry ->
        val obj = entry as? JsonObject ?: return@mapNotNull null
        val function = obj["function"] as? JsonObject ?: return@mapNotNull null
        val name =
            (function["name"] as? JsonPrimitive)?.content ?: return@mapNotNull null
        val argumentsRaw = function["arguments"]
        val argumentsMap: Map<String, Any> =
            when (argumentsRaw) {
                is JsonPrimitive ->
                    runCatching {
                        Json.parseToJsonElement(argumentsRaw.content) as? JsonObject
                    }.getOrNull()?.toRuntimeMap() ?: emptyMap()
                is JsonObject -> argumentsRaw.toRuntimeMap()
                else -> emptyMap()
            }
        ToolCall(name, argumentsMap)
    }
}

/**
 * Lowers a [JsonObject] into the runtime `Map<String, Any>` shape
 * LiteRT-LM's [ToolCall] expects. Values keep their original types:
 * numbers stay numbers, booleans stay booleans, nested objects/arrays
 * stay as `JsonElement` so downstream consumers can re-introspect.
 */
private fun JsonObject.toRuntimeMap(): Map<String, Any> = entries.associate { (k, v) -> k to (jsonElementToAny(v) ?: "") }

/**
 * Inverse of [anyToJsonElement] for the values that LiteRT-LM
 * inspects at runtime. Primitives demote to typed Kotlin values;
 * objects and arrays stay as [JsonElement] so consumers can keep
 * walking the tree without re-parsing.
 */
private fun jsonElementToAny(element: JsonElement): Any? =
    when (element) {
        is JsonPrimitive ->
            when {
                element is kotlinx.serialization.json.JsonNull -> null
                element.isString -> element.content
                element.booleanOrNull != null -> element.boolean
                element.longOrNull != null -> element.long
                element.doubleOrNull != null -> element.double
                else -> element.content
            }
        else -> element
    }

/**
 * Wraps an OpenAI tool definition as a LiteRT-LM [OpenApiTool] so
 * the model sees its schema at prefill time. The bridge does NOT
 * execute the tool — `automaticToolCalling` stays disabled on the
 * conversation, so the model emits a `Message.toolCalls` entry and
 * waits for the daemon's agent loop to supply results on the next
 * request.
 */
private class DaemonProxyTool(
    private val descriptionJson: String,
) : OpenApiTool {
    /**
     * Returns the JSON schema string OpenAI provided. LiteRT-LM
     * forwards this verbatim to the model during prefill.
     */
    override fun getToolDescriptionJsonString(): String = descriptionJson

    /**
     * Never invoked in normal flow because `automaticToolCalling`
     * is false on conversations built for HTTP serving. Throws
     * loudly so any future code-path regression that flips that
     * flag fails fast instead of silently swallowing the call.
     */
    override fun execute(arguments: String): String =
        throw UnsupportedOperationException(
            "Tool execution is delegated to the daemon; LiteRT-LM should not " +
                "invoke this path while automaticToolCalling is false.",
        )
}

/**
 * Separator used by the upstream zeroclaw daemon to namespace tool
 * names (e.g., `memory:memory_recall`, `web:web_search`). The colon
 * breaks Gemma 4's FC parser, which expects exactly one `:` in a
 * tool call (`call:<name>{...}`).
 */
private const val DAEMON_TOOL_NAMESPACE_SEPARATOR = ':'

/**
 * Separator we substitute when sending tool specs to LiteRT-LM. Dot
 * is allowed inside `ID` tokens by Gemma 4's FC lexer grammar
 * (`[a-zA-Z_] [a-zA-Z0-9_.-]*`), so the rewrite produces a name the
 * parser accepts. We reverse-rewrite in [toOpenAiToolCall] so the
 * daemon receives its expected colon-separated identifier.
 *
 * Why dot specifically: LiteRT-LM's `AntlrFcParser.g4` defines
 * `functionCall: CALL COLON ID object?;` — a single ID token after
 * the leading `call:`. Any second `:` in the name triggers the
 * `Failed to parse FC tool calls` exception that surfaces as HTTP
 * 500 → daemon retry storm → user-visible "provider error".
 */
private const val LITERT_TOOL_NAMESPACE_SEPARATOR = '.'

/**
 * Rewrites a daemon-style namespaced tool name (`memory:memory_recall`)
 * into a Gemma-4-FC-parser-safe form (`memory.memory_recall`). No-op
 * for names that don't contain the daemon separator.
 */
private fun encodeToolNameForLiteRt(name: String): String = name.replace(DAEMON_TOOL_NAMESPACE_SEPARATOR, LITERT_TOOL_NAMESPACE_SEPARATOR)

/**
 * Reverses [encodeToolNameForLiteRt]: turns the parser-safe form back
 * into the daemon's canonical namespace:name shape so the agent
 * loop's dispatcher can route it correctly.
 *
 * Caveat: tools whose original name legitimately contained a dot
 * (none exist in the current zeroclaw tool registry as of writing)
 * would round-trip incorrectly. Not a real concern today; revisit
 * if upstream introduces dotted names.
 */
private fun decodeToolNameFromLiteRt(name: String): String = name.replace(LITERT_TOOL_NAMESPACE_SEPARATOR, DAEMON_TOOL_NAMESPACE_SEPARATOR)

/**
 * Converts an OpenAI `tools` array into a list of LiteRT-LM
 * [ToolProvider] instances. Each `function` object is forwarded as
 * a single OpenAPI tool — LiteRT-LM uses the JSON schema to bias
 * the model toward emitting a valid `tool_calls` response.
 *
 * Tool names containing the daemon's `:` namespace separator are
 * rewritten to use `.` instead so they parse cleanly through Gemma
 * 4's FC ANTLR grammar. The reverse rewrite happens in
 * [toOpenAiToolCall].
 *
 * Returns an empty list when [tools] is null or empty so callers
 * can pass the result straight through to `ConversationConfig`.
 */
fun openAiToolsToLiteRt(tools: JsonArray?): List<ToolProvider> {
    if (tools.isNullOrEmpty()) return emptyList()
    return tools.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val function = obj["function"] as? JsonObject ?: return@mapNotNull null
        // Rewrite function.name to escape the colon namespace separator.
        // Without this, models like Gemma 4 emit `call:foo:bar{...}`
        // which the FC parser rejects with `Failed to parse FC tool
        // calls` (offending token: the second colon).
        val originalName = (function["name"] as? JsonPrimitive)?.content
        val rewritten =
            if (originalName != null && originalName.contains(DAEMON_TOOL_NAMESPACE_SEPARATOR)) {
                JsonObject(
                    function.toMutableMap().apply {
                        this["name"] = JsonPrimitive(encodeToolNameForLiteRt(originalName))
                    },
                )
            } else {
                function
            }
        tool(DaemonProxyTool(rewritten.toString()))
    }
}

/**
 * Translates a LiteRT-LM [ToolCall] into an OpenAI [OpenAiToolCall].
 *
 * Argument values are converted through [anyToJsonElement] so types
 * survive the round-trip — numbers stay numbers, booleans stay
 * booleans, nested objects survive. The resulting JSON is encoded
 * once as a string because OpenAI's protocol carries
 * `tool_calls[].function.arguments` as a string-of-JSON, not a
 * structured object.
 */
fun toOpenAiToolCall(call: ToolCall): OpenAiToolCall {
    val argumentsObject =
        JsonObject(call.arguments.mapValues { (_, v) -> anyToJsonElement(v) })
    return OpenAiToolCall(
        id = "call_${java.util.UUID.randomUUID()}",
        type = "function",
        function =
            OpenAiFunctionCall(
                // Reverse the colon-to-dot rewrite from
                // [openAiToolsToLiteRt] so the daemon sees its
                // canonical `<namespace>:<func>` identifier and the
                // tool dispatcher can route the call.
                name = decodeToolNameFromLiteRt(call.name),
                arguments = Json.encodeToString(JsonObject.serializer(), argumentsObject),
            ),
    )
}

/**
 * Recursively converts an arbitrary Kotlin value (the runtime shape
 * LiteRT-LM hands back from `Conversation.toolCalls[].arguments`)
 * into a [JsonElement] preserving real types — numbers stay numbers,
 * booleans stay booleans, nested objects survive. Earlier shortcuts
 * that stringified everything broke the daemon's tool dispatcher
 * for any non-string parameter.
 */
fun anyToJsonElement(value: Any?): JsonElement =
    when (value) {
        null -> JsonNull
        is JsonElement -> value
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is String -> JsonPrimitive(value)
        is List<*> -> JsonArray(value.map(::anyToJsonElement))
        is Map<*, *> ->
            JsonObject(
                value.entries.associate { (k, v) ->
                    k.toString() to anyToJsonElement(v)
                },
            )
        else -> JsonPrimitive(value.toString())
    }
