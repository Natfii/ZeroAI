/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.model

/**
 * Represents the current state of the voice input/output subsystem.
 *
 * The voice pipeline transitions through these states as the user
 * interacts with the microphone FAB on the terminal screen:
 *
 * [Idle] -> [Listening] -> [Processing] -> [Speaking] -> [Idle]
 *
 * Any active state may transition to [Error] on recognition or TTS
 * failure, after which the system returns to [Idle].
 */
sealed interface VoiceState {
    /**
     * Voice subsystem is inactive.
     *
     * The microphone FAB displays a static microphone icon and is
     * ready to begin speech recognition on tap.
     */
    data object Idle : VoiceState

    /**
     * Actively capturing speech from the microphone.
     *
     * The FAB displays an animated pulsing indicator to signal that
     * audio is being recorded and streamed to the speech recognizer.
     *
     * @property partialText Intermediate transcription updated as the
     *   recognizer produces partial results. Empty until the first
     *   partial result arrives.
     */
    data class Listening(
        val partialText: String = "",
    ) : VoiceState

    /**
     * Speech has been captured and is awaiting the AI response.
     *
     * The recognizer has delivered a final result and the text has been
     * submitted to the agent. The FAB displays a circular progress
     * indicator until the response arrives.
     */
    data object Processing : VoiceState

    /**
     * Text-to-speech is reading the AI response aloud.
     *
     * The FAB displays a speaker/volume icon. Tapping the FAB while
     * in this state interrupts playback and returns to [Idle].
     *
     * @property text The full response text currently being spoken.
     */
    data class Speaking(
        val text: String,
    ) : VoiceState

    /**
     * A recognition or TTS error occurred.
     *
     * The FAB displays a mic-off icon. The system returns to [Idle]
     * automatically after the error is acknowledged or on the next
     * FAB tap.
     *
     * @property message Human-readable description of the failure.
     */
    data class Error(
        val message: String,
    ) : VoiceState
}
