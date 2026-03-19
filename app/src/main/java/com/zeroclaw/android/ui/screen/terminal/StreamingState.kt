/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.terminal

/**
 * Phases of a streaming response lifecycle.
 *
 * Transitions follow:
 * [IDLE] -> [SEARCHING_MEMORY] -> [CALLING_PROVIDER] -> [THINKING] ->
 * [TOOL_EXECUTING] -> [RESPONDING] -> [COMPACTING] -> [COMPLETE],
 * or any active phase may transition to [CANCELLED] or [ERROR].
 */
enum class StreamingPhase {
    /** No streaming operation in progress. */
    IDLE,

    /** Building memory context from the vector store. */
    SEARCHING_MEMORY,

    /** Sending the prompt to the LLM provider. */
    CALLING_PROVIDER,

    /** Receiving thinking/reasoning tokens from the model. */
    THINKING,

    /** The agent is executing one or more tools. */
    TOOL_EXECUTING,

    /** Receiving response content tokens from the model. */
    RESPONDING,

    /** Conversation history is being compacted to fit context window. */
    COMPACTING,

    /** Streaming completed successfully. */
    COMPLETE,

    /** Streaming was cancelled by the user. */
    CANCELLED,

    /** An error occurred during streaming. */
    ERROR,
    ;

    /**
     * Whether this phase represents an active (non-terminal) streaming operation.
     *
     * Returns `false` for [IDLE], [COMPLETE], [CANCELLED], and [ERROR].
     */
    val isActive: Boolean
        get() = this != IDLE && this != COMPLETE && this != CANCELLED && this != ERROR
}

/**
 * Represents an in-flight tool execution displayed during [StreamingPhase.TOOL_EXECUTING].
 *
 * @property name Tool identifier (e.g. "memory_recall").
 * @property hint Short argument summary shown alongside the tool name.
 */
data class ToolProgress(
    val name: String,
    val hint: String = "",
)

/**
 * Completed tool execution result for display in the tool activity footer.
 *
 * @property name Tool identifier that was executed.
 * @property success Whether the tool execution succeeded.
 * @property durationSecs Wall-clock execution time in seconds.
 * @property output Full tool output text for expandable display.
 */
data class ToolResultEntry(
    val name: String,
    val success: Boolean,
    val durationSecs: Long,
    val output: String = "",
)

/**
 * Immutable snapshot of the current streaming state.
 *
 * @property phase Current streaming lifecycle phase.
 * @property thinkingText Accumulated thinking/reasoning tokens.
 * @property responseText Accumulated response content tokens.
 * @property errorMessage Error description when [phase] is [StreamingPhase.ERROR].
 * @property activeTools Tools currently executing during [StreamingPhase.TOOL_EXECUTING].
 * @property toolResults Completed tool execution results for the current turn.
 * @property providerRound 1-based LLM call round (round 2+ means tool-loop iteration).
 * @property toolCallCount Number of tool calls returned by the last LLM response.
 * @property llmDurationSecs Wall-clock seconds the LLM took to respond before tool dispatch.
 */
data class StreamingState(
    val phase: StreamingPhase = StreamingPhase.IDLE,
    val thinkingText: String = "",
    val responseText: String = "",
    val errorMessage: String? = null,
    val activeTools: List<ToolProgress> = emptyList(),
    val toolResults: List<ToolResultEntry> = emptyList(),
    val providerRound: Int = 0,
    val toolCallCount: Int = 0,
    val llmDurationSecs: Long = 0,
) {
    /** Constants and factory methods for [StreamingState]. */
    companion object {
        /** Returns a fresh idle state with no accumulated text. */
        fun idle(): StreamingState = StreamingState()
    }
}
