/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.terminal

import com.zeroclaw.android.ui.canvas.CanvasElement
import com.zeroclaw.android.ui.canvas.CanvasFrame
import com.zeroclaw.android.ui.canvas.CanvasMascotState
import com.zeroclaw.android.ui.canvas.CanvasTextStyle
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Builds fenced canvas payloads for terminal messages that feature the mini Zero mascot. */
object MiniZeroCanvasPayloads {
    /**
     * Builds the terminal welcome banner shown when the REPL opens.
     *
     * @param hasChatProvider Whether a chat provider is configured.
     * @param isDaemonRunning Whether the daemon foreground service is active.
     * @return Response text containing a canvas payload for inline rendering.
     */
    fun welcomeBanner(
        hasChatProvider: Boolean,
        isDaemonRunning: Boolean,
    ): String {
        val mascotState: CanvasMascotState
        val statusLabel: String
        val subtitle: String

        when {
            isDaemonRunning && hasChatProvider -> {
                mascotState = CanvasMascotState.PEEK
                statusLabel = "Online and ready."
                subtitle = "Ready to help with chat, tools, and terminal commands."
            }
            isDaemonRunning && !hasChatProvider -> {
                mascotState = CanvasMascotState.IDLE
                statusLabel = "Online, no provider."
                subtitle = "Admin console only right now. Add a provider to unlock chat."
            }
            !isDaemonRunning && hasChatProvider -> {
                mascotState = CanvasMascotState.SLEEPING
                statusLabel = "Sleeping. Ready when you need me."
                subtitle = "Daemon is off but your provider is configured. Start the service to chat."
            }
            else -> {
                mascotState = CanvasMascotState.SLEEPING
                statusLabel = "Sleeping."
                subtitle = "Daemon is off and no provider is set. Configure one in Settings to get started."
            }
        }

        val frame =
            CanvasFrame(
                title = "Mini Zero",
                elements =
                    listOf(
                        CanvasElement.Mascot(
                            state = mascotState,
                            label = statusLabel,
                        ),
                        CanvasElement.Text(content = subtitle),
                        CanvasElement.Text(
                            content = "Type /help for commands or send a normal message to chat.",
                            style = CanvasTextStyle.CAPTION,
                        ),
                    ),
            )

        return buildResponse(
            plainText = statusLabel,
            frame = frame,
        )
    }

    /**
     * Builds a terminal message for a cancelled request.
     *
     * @return Response text containing a canvas payload for inline rendering.
     */
    fun cancelledNotice(): String =
        buildResponse(
            plainText = "Request cancelled.",
            frame =
                CanvasFrame(
                    title = "Request stopped",
                    elements =
                        listOf(
                            CanvasElement.Mascot(
                                state = CanvasMascotState.ERROR,
                                label = "Mini Zero stopped the current run.",
                            ),
                            CanvasElement.Text(
                                content = "You can send a new prompt whenever you are ready.",
                            ),
                        ),
                ),
        )

    /**
     * Builds a terminal response for a session error.
     *
     * @param message Sanitized error message shown to the user.
     * @return Response text containing a canvas payload for inline rendering.
     */
    fun errorNotice(message: String): String =
        buildResponse(
            plainText = message,
            frame =
                CanvasFrame(
                    title = "Something went wrong",
                    elements =
                        listOf(
                            CanvasElement.Mascot(
                                state = CanvasMascotState.ERROR,
                                label = "Mini Zero hit a snag.",
                            ),
                            CanvasElement.Text(content = message),
                        ),
                ),
        )

    /**
     * Builds a terminal response for a successful local action.
     *
     * @param title Headline shown in the canvas frame.
     * @param message Body text shown in both plain text and canvas form.
     * @return Response text containing a canvas payload for inline rendering.
     */
    fun successNotice(
        title: String,
        message: String,
    ): String =
        buildResponse(
            plainText = "",
            frame =
                CanvasFrame(
                    title = title,
                    elements =
                        listOf(
                            CanvasElement.Mascot(
                                state = CanvasMascotState.SUCCESS,
                                label = "Mini Zero wrapped it up cleanly.",
                            ),
                            CanvasElement.Text(content = message),
                        ),
                ),
        )

    /**
     * Builds a terminal response summarizing a completed tool call.
     *
     * @param name Tool name shown in the frame.
     * @param success Whether the tool succeeded.
     * @param durationSecs Execution duration in seconds.
     * @return Response text containing a canvas payload for inline rendering.
     */
    fun toolResultNotice(
        name: String,
        success: Boolean,
        durationSecs: Long,
    ): String {
        val title = if (success) "$name finished" else "$name failed"
        val outcome = if (success) "completed successfully" else "reported a failure"
        val durationSuffix =
            if (durationSecs > 0) {
                " in ${durationSecs}s"
            } else {
                ""
            }
        val detail = "Tool $name $outcome$durationSuffix."

        return buildResponse(
            plainText = detail,
            frame =
                CanvasFrame(
                    title = title,
                    elements =
                        listOf(
                            CanvasElement.Mascot(
                                state =
                                    if (success) {
                                        CanvasMascotState.SUCCESS
                                    } else {
                                        CanvasMascotState.ERROR
                                    },
                                label = detail,
                            ),
                            CanvasElement.Text(content = detail),
                        ),
                ),
        )
    }

    /**
     * Builds a terminal response for in-progress tool execution.
     *
     * @param label Short status label for the tool work.
     * @param detail More detailed description of the current activity.
     * @return Response text containing a canvas payload for inline rendering.
     */
    fun typingStatus(
        label: String,
        detail: String,
    ): String =
        buildResponse(
            plainText = label,
            frame =
                CanvasFrame(
                    title = label,
                    elements =
                        listOf(
                            CanvasElement.Mascot(
                                state = CanvasMascotState.TYPING,
                                label = detail,
                            ),
                            CanvasElement.Text(content = detail),
                        ),
                ),
        )

    /**
     * Wraps plain text with a fenced canvas block.
     *
     * The plain-text prefix keeps copy/paste and text-only fallbacks readable while the fenced
     * block enables native canvas rendering in supported terminal surfaces.
     *
     * @param plainText Plain-text prefix shown before the canvas fence.
     * @param frame Structured canvas frame.
     * @return Combined response string for terminal persistence.
     */
    private fun buildResponse(
        plainText: String,
        frame: CanvasFrame,
    ): String =
        buildString {
            appendLine(plainText)
            appendLine()
            appendLine("```canvas")
            append(canvasJson.encodeToString(CanvasEnvelope(canvas = frame)))
            appendLine()
            append("```")
        }
}

/** JSON encoder shared by terminal canvas payload helpers. */
private val canvasJson =
    Json {
        encodeDefaults = true
    }

/**
 * Envelope format matching the `"canvas"` wrapper accepted by the terminal parser.
 *
 * @property canvas Inner structured canvas frame.
 */
@Serializable
private data class CanvasEnvelope(
    val canvas: CanvasFrame,
)
