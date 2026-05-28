/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.service.ondevice

import android.util.Log
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Compensating layer that sits between LiteRT-LM and the OpenAI-
 * compatible HTTP server. Applies model-specific recovery so the
 * daemon's agent loop sees clean tool_calls / text even when the
 * underlying model emits sloppy syntax.
 *
 * Currently focused on Gemma 4 (E2B / E4B). The Gemma 4 FC parser
 * shipped in LiteRT-LM 0.11 is strict — it requires special quote
 * tokens (`<|"|>`) and single-segment identifiers — but the model
 * (especially at E2B size) doesn't reliably emit either. Without
 * compensation, every malformed tool call surfaces as an `HTTP 500`
 * that the daemon then retries, exhausts, and reports as "provider
 * error" to the user.
 *
 * Recovery patterns:
 *  - [salvageToolCallsFromError]: when LiteRT-LM throws a parse
 *    error, extract the raw model output from the exception message
 *    and parse it permissively. Plain `"..."` quotes, dotted /
 *    colon-separated names, and trailing whitespace are all
 *    tolerated.
 *
 * Future patterns the harness is positioned for (not yet
 * implemented; ordered by anticipated value):
 *  - argument-shape repair (model passes full convo history as a
 *    tool arg → trim to the actual query)
 *  - loop detection (same tool + same args 3 turns in a row →
 *    emit a synthetic "try a different approach" failure)
 *  - format-hint retry (first parse failure swallowed; re-issue
 *    with a transient `<|"|>` reminder)
 *  - per-model adapter registry (Gemma vs Phi-4 vs Qwen quirks)
 *
 * The harness lives on the on-device path only. Cloud providers
 * (Anthropic / OpenAI / etc.) speak strict JSON and need no
 * compensation; routing them through the harness would just add
 * latency.
 */
internal object GemmaResponseHarness {
    /** Logcat tag for harness operations. */
    private const val TAG = "GemmaHarness"

    /**
     * Debug-only log helper. Centralises the [BuildConfig.DEBUG]
     * gate so every payload-touching log line in the harness
     * stays release-safe without scattering `if` blocks. New log
     * additions in this file should use [dbg], not raw `Log.d`,
     * because most of what the harness sees is partially user-
     * controlled model output (recovered tool args, raw response
     * fragments, etc.).
     */
    private inline fun dbg(message: () -> String) {
        if (com.zeroclaw.android.BuildConfig.DEBUG) {
            Log.d(TAG, message())
        }
    }

    /** Cap on logged-output length to keep logcat readable. */
    private const val LOG_PREVIEW_CHARS = 600

    /** Truncation length for short debug snippets of raw body/argument text. */
    private const val DEBUG_SNIPPET_CHARS = 120

    /** Truncation length for individual mangled-value previews. */
    private const val MANGLED_VALUE_PREVIEW_CHARS = 30

    /**
     * Cap on the size of the error-message string we feed into the
     * salvage regex. Bounds the worst-case regex runtime in the
     * face of adversarial model output. See [salvageToolCallsFromError].
     */
    private const val SALVAGE_MESSAGE_MAX_CHARS: Int = 8 * 1024

    /**
     * Returns true when [modelId] is in the Gemma 4 family. The
     * harness is **only** safe to apply to Gemma 4 — its recovery
     * patterns assume the Gemma-4 FC code-fence (`<|tool_call>` /
     * `<tool_call|>`) and the special quote token (`<|"|>`). Other
     * models (Phi-4, Qwen, etc.) emit different formats; running
     * Gemma-4 salvage against their output could produce nonsense
     * tool calls or mangle correct ones.
     */
    fun appliesTo(modelId: String): Boolean = modelId.startsWith("gemma-4") || modelId.startsWith("gemma4")

    /** Permissive JSON used for argument-body parse attempts. */
    private val lenientJson =
        Json {
            isLenient = true
            ignoreUnknownKeys = true
            allowTrailingComma = true
        }

    /**
     * Fallback text emitted when the model produces an empty
     * response after a SUCCESSFUL tool call. Better than silence;
     * the user sees that the agent took action, even if the model
     * couldn't be bothered to write a confirmation sentence.
     *
     * Deliberately short. Anything longer would imply specific
     * knowledge of what the tool actually did, which we don't have
     * at this layer.
     */
    const val EMPTY_TOOL_CHAIN_FALLBACK_SUCCESS: String = "Done."

    /**
     * Fallback text emitted when the model produces an empty
     * response after a tool call that FAILED. The earlier "Done."
     * version was misleading — it told the user "memory saved"
     * when the underlying memory_store actually errored out with
     * "Missing 'key' parameter". Honest acknowledgement of failure
     * is better UX than silent success, and lets the user retry
     * with a different phrasing.
     */
    const val EMPTY_TOOL_CHAIN_FALLBACK_FAILURE: String =
        "That didn't quite work — could you try rephrasing?"

    /**
     * Intent-steering hint prepended to user messages that carry
     * an `<image>` block. Without this, E2B sees the image caption
     * + memory context + 14 tool specs and reaches for a tool (we
     * observed it calling `memory_store` on a dog photo because
     * "remember" was salient in recall context). The hint nudges
     * it back to natural describe-first behaviour while leaving
     * tool calls available for explicit asks like "remember this
     * dog's breed".
     *
     * Kept terse on purpose — E2B's attention is fragile under
     * long instructions and we don't want to dilute the actual
     * user message that follows.
     */
    private const val IMAGE_INTENT_HINT =
        "When the message contains an <image>...</image> block, " +
            "describe what you see in plain prose unless the user " +
            "explicitly asks for a tool action. Do NOT call memory_store " +
            "or other tools just because the image arrived.\n\n"

    /**
     * Returns [stimulus] with the image-intent hint prepended when
     * the message contains an `<image>` tag. No-op otherwise so
     * non-image turns stay clean.
     */
    fun applyImageIntentHint(stimulus: String): String =
        if (stimulus.contains("<image>", ignoreCase = false)) {
            IMAGE_INTENT_HINT + stimulus
        } else {
            stimulus
        }

    /**
     * Picks the right fallback text based on the most recent tool
     * result in [messages]. Looks for the conventional error prefix
     * emitted by upstream zeroclaw's tool dispatcher when a Tool
     * trait returns `Err` (`"Tool execution error: …"`). Any other
     * non-empty content is treated as success.
     */
    fun selectEmptyFallback(messages: List<OpenAiChatMessage>): String {
        val lastToolResult =
            messages.lastOrNull { it.role == "tool" }?.textContent.orEmpty()
        val looksLikeError =
            lastToolResult.isBlank() ||
                lastToolResult.startsWith("Tool execution error", ignoreCase = true) ||
                lastToolResult.startsWith("Error:", ignoreCase = true)
        return if (looksLikeError) {
            EMPTY_TOOL_CHAIN_FALLBACK_FAILURE
        } else {
            EMPTY_TOOL_CHAIN_FALLBACK_SUCCESS
        }
    }

    /**
     * Returns true when [messages] contains at least one prior
     * assistant turn carrying `tool_calls`. That's the signal
     * that this HTTP call is N≥2 in an agent-loop chain — the
     * previous iteration emitted tool calls, the daemon executed
     * them, and we're now being asked to synthesise a final
     * response from the accumulated tool results.
     *
     * Used by the manager's empty-response recovery path: when
     * the model emits nothing on iteration N, we want to know
     * whether to give up (no prior tool calls — this is a turn-1
     * shrug) or to nudge for synthesis (we have tool results
     * waiting to be synthesised into prose).
     */
    fun isMidToolChain(messages: List<OpenAiChatMessage>): Boolean =
        messages.any { msg ->
            val calls = msg.toolCalls
            calls != null && calls.isNotEmpty()
        }

    /**
     * Attempts to recover one or more tool calls from a LiteRT-LM
     * `Failed to parse FC tool calls` exception.
     *
     * The exception's `message` field contains the verbatim model
     * output framed as `Failed to parse tool calls from response:
     * <RAW> with error: ...`. We re-parse `<RAW>` with our own
     * lenient grammar — accepting plain quotes, namespaced names,
     * and unanchored bodies — and reconstruct OpenAI-shaped
     * tool_calls so the daemon's agent loop can dispatch them.
     *
     * @param error The exception thrown by `Conversation.sendMessageAsync`'s
     *   error callback.
     * @return A non-empty list of recovered tool calls, or `null`
     *   when nothing salvageable was found (caller should surface
     *   the original error verbatim).
     */
    fun salvageToolCallsFromError(error: Throwable): List<OpenAiToolCall>? {
        // Cap the message at SALVAGE_MESSAGE_MAX_CHARS before
        // running any regex against it. The error message is
        // derived from model output, which is partially attacker-
        // controllable through prompt injection — a long crafted
        // string could trigger catastrophic backtracking in the
        // `DOT_MATCHES_ALL` regex. 8 KB is far above the largest
        // legitimate Gemma 4 tool-call body we've seen (~400 chars).
        val message =
            error.message?.take(SALVAGE_MESSAGE_MAX_CHARS) ?: return null
        if (!message.contains("Failed to parse tool calls from response")) return null
        val raw = extractRawResponse(message) ?: return null
        // Raw model output is debug-only: it carries tool arguments
        // that may include user-dictated content (queries, memory
        // values, secrets passed as args). The Log.i recovery count
        // below is safe — counts only, no payload.
        dbg { "Salvage: raw model output = ${raw.take(LOG_PREVIEW_CHARS)}" }
        val calls = parsePermissive(raw)
        return if (calls.isEmpty()) {
            dbg { "Salvage: no tool calls extractable from raw output" }
            null
        } else {
            Log.i(TAG, "Salvage: recovered ${calls.size} tool call(s) from parse failure")
            calls
        }
    }

    /**
     * Extracts the raw model output substring from LiteRT-LM's
     * verbose parse-error message format.
     */
    private fun extractRawResponse(errorMessage: String): String? {
        val pattern =
            Regex(
                "Failed to parse tool calls from response: (.*?) with error:",
                RegexOption.DOT_MATCHES_ALL,
            )
        return pattern
            .find(errorMessage)
            ?.groupValues
            ?.get(1)
            ?.trim()
    }

    /**
     * Parses zero or more tool calls out of [raw] using a permissive
     * regex grammar. Each fenced `<|tool_call>...<tool_call|>` block
     * becomes one [OpenAiToolCall] entry.
     */
    private fun parsePermissive(raw: String): List<OpenAiToolCall> {
        val fencePattern =
            Regex(
                "<\\|tool_call>(.*?)<tool_call\\|>",
                RegexOption.DOT_MATCHES_ALL,
            )
        return fencePattern
            .findAll(raw)
            .mapNotNull { match -> parseFencedToolCall(match.groupValues[1]) }
            .toList()
    }

    /**
     * Parses a single tool-call body of the shape
     * `call:<name>{<args>}` (with optional whitespace and either
     * special or plain quotes inside the args).
     */
    private fun parseFencedToolCall(body: String): OpenAiToolCall? {
        val callPattern =
            Regex("^\\s*call:(\\S+?)\\s*\\{(.*)\\}\\s*$", RegexOption.DOT_MATCHES_ALL)
        val match =
            callPattern.find(body) ?: run {
                dbg {
                    "Salvage: body did not match call:name{args} shape: " +
                        body.take(DEBUG_SNIPPET_CHARS)
                }
                return null
            }
        val rawName = match.groupValues[1]
        val argsBody = match.groupValues[2]

        // Reverse the colon-to-dot rewrite from openAiToolsToLiteRt
        // so the daemon's tool dispatcher sees its canonical
        // namespace:func identifier. Idempotent on names that have
        // no separator at all.
        val canonicalName = rawName.replace('.', ':')

        val argumentsJson = normalizeArgumentsToJson(argsBody)
        return OpenAiToolCall(
            id = "call_${UUID.randomUUID()}",
            type = "function",
            function =
                OpenAiFunctionCall(
                    name = canonicalName,
                    arguments = argumentsJson,
                ),
        )
    }

    /**
     * Best-effort conversion of a Gemma-4 FC argument body into a
     * JSON string suitable for OpenAI's `arguments` field.
     *
     * Strategy is layered (cheapest → most permissive):
     *
     *  1) Normalise Gemma's special quote tokens (`<|"|>`) to plain
     *     `"`, wrap in `{...}`, and try lenient JSON parse. This is
     *     the happy path — clean args round-trip without info loss.
     *
     *  2) Manual key:value pair extraction via regex. Gemma 4's FC
     *     body format is reliably `key:<|"|>value<|"|>` (or bare
     *     number / `true`/`false`) separated by commas. When the
     *     value of one key contains broken characters (e.g., an
     *     emoji that the model failed to tokenise into args — the
     *     content field can come out as `:<|"|>is<|"|>` with the
     *     emoji dropped), the lenient JSON parse fails AT THAT
     *     KEY, taking the rest of the args down with it.
     *
     *     The pair extractor recovers anything it can read on
     *     either side of a broken value, so a tool like
     *     `memory_store` still receives `{"key":"favorite_emoji",
     *     "category":"conversation"}` even when `content` is lost.
     *     The daemon's tool dispatcher then either succeeds with
     *     whatever fields the schema requires, or fails with a
     *     specific missing-field error the model can react to.
     *
     *  3) Last-resort `{"raw": "..."}` envelope. Reached only when
     *     the args body has no recognisable key:value structure at
     *     all. Preserves something for downstream observation; the
     *     tool will fail but loudly.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun normalizeArgumentsToJson(argsBody: String): String {
        val normalized = argsBody.replace("<|\"|>", "\"")
        val wrapped = "{$normalized}"
        val parsedCleanly =
            try {
                lenientJson.parseToJsonElement(wrapped)
                true
            } catch (_: Throwable) {
                false
            }
        if (parsedCleanly) return wrapped
        val pairs = extractKeyValuePairs(argsBody)
        if (pairs.isNotEmpty()) {
            val obj = JsonObject(pairs)
            dbg {
                "Salvage: lenient JSON parse failed; recovered ${pairs.size} key/value " +
                    "pair(s) via manual extraction: ${pairs.keys.joinToString()}"
            }
            return lenientJson.encodeToString(JsonObject.serializer(), obj)
        }
        dbg {
            "Salvage: no key/value structure extractable; wrapping raw body: " +
                argsBody.take(DEBUG_SNIPPET_CHARS)
        }
        val fallback = JsonObject(mapOf("raw" to JsonPrimitive(argsBody)))
        return lenientJson.encodeToString(JsonObject.serializer(), fallback)
    }

    /**
     * Extracts `key:<|"|>value<|"|>` pairs from a Gemma-4 FC arg
     * body using a forgiving regex. Returns a map of recovered
     * fields suitable for wrapping as a [JsonObject].
     *
     * The value match is non-greedy and uses both opening and
     * closing `<|"|>` tokens as anchors — so when one key's value
     * is mangled (broken quote pairs, missing chars), the regex
     * skips that key but still picks up correctly-formed pairs
     * before and after it.
     *
     * Bare numeric and boolean values are also accepted via a
     * fallback pattern, in case the model emits `limit:5` or
     * `enabled:true` without quote tokens.
     */
    private fun extractKeyValuePairs(argsBody: String): Map<String, JsonElement> {
        val out = linkedMapOf<String, JsonElement>()
        // Pattern A: key:<|"|>value<|"|>
        val quotedPattern =
            Regex(
                "([A-Za-z_][A-Za-z0-9_]*)\\s*:\\s*<\\|\"\\|>(.*?)<\\|\"\\|>",
                RegexOption.DOT_MATCHES_ALL,
            )
        quotedPattern.findAll(argsBody).forEach { match ->
            val key = match.groupValues[1]
            val value = match.groupValues[2]
            // Skip keys whose extracted "value" is itself a stray
            // colon-and-letters fragment from a mangled prior value
            // — those carry no signal and would just confuse the
            // downstream tool.
            if (value.startsWith(":") || value.isBlank()) {
                dbg {
                    "Salvage: skipping mangled value for key='$key': '" +
                        value.take(MANGLED_VALUE_PREVIEW_CHARS) + "'"
                }
                return@forEach
            }
            out[key] = JsonPrimitive(value)
        }
        // Pattern B: key:literal (number or true/false/null), used
        // when the model omits quote tokens around primitive values.
        val literalPattern =
            Regex("([A-Za-z_][A-Za-z0-9_]*)\\s*:\\s*(-?\\d+(?:\\.\\d+)?|true|false|null)\\b")
        literalPattern.findAll(argsBody).forEach { match ->
            val key = match.groupValues[1]
            // Don't clobber a higher-confidence quoted-string value
            // we already captured for this key.
            if (out.containsKey(key)) return@forEach
            val literal = match.groupValues[2]
            val element: JsonElement =
                when {
                    literal == "true" -> JsonPrimitive(true)
                    literal == "false" -> JsonPrimitive(false)
                    literal == "null" -> JsonNull
                    literal.contains('.') -> JsonPrimitive(literal.toDouble())
                    else -> JsonPrimitive(literal.toLong())
                }
            out[key] = element
        }
        return out
    }
}
