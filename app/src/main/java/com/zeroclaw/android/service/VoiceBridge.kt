/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.service

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import com.zeroclaw.android.BuildConfig
import com.zeroclaw.android.model.VoiceState
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Bridge between the Android speech APIs and the ZeroAI voice pipeline.
 *
 * Wraps [SpeechRecognizer] for speech-to-text and [TextToSpeech] for
 * text-to-speech, exposing a unified [VoiceState] flow that the UI
 * layer observes. All public methods must be called on the **main
 * thread** because [SpeechRecognizer] requires it.
 *
 * When the device is in power-save mode, TTS auto-read is skipped
 * and the response is displayed as text only.
 *
 * @param context Application or Activity context. An application
 *   context is preferred to avoid leaking an Activity reference.
 */
class VoiceBridge(
    private val context: Context,
) {
    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)

    /**
     * Observable voice pipeline state.
     *
     * Emits the current [VoiceState] as the pipeline transitions
     * between idle, listening, processing, speaking, and error states.
     */
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null

    @Volatile
    private var ttsReady = false

    /**
     * Callback invoked when speech recognition produces a final result.
     *
     * Set by the caller (typically the ViewModel) to receive the
     * recognized text for submission to the agent.
     */
    var onSpeechResult: ((String) -> Unit)? = null

    init {
        initTts()
    }

    /**
     * Initialises the [TextToSpeech] engine asynchronously.
     *
     * The engine is not usable until [TextToSpeech.OnInitListener]
     * fires with [TextToSpeech.SUCCESS]. Calls to [speak] before
     * that point are silently ignored.
     */
    private fun initTts() {
        tts =
            TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = Locale.getDefault()
                    tts?.setOnUtteranceProgressListener(TtsProgressListener())
                    ttsReady = true
                } else if (BuildConfig.DEBUG) {
                    android.util.Log.w(TAG, "TTS initialization failed with status: $status")
                }
            }
    }

    /**
     * Begins speech recognition using the on-device or network recognizer.
     *
     * Creates a fresh [SpeechRecognizer] instance, configures it for
     * free-form dictation with partial results, and starts listening.
     * The [state] flow transitions to [VoiceState.Listening].
     *
     * If no recognition service is available on the device, the state
     * transitions directly to [VoiceState.Error].
     *
     * Must be called on the main thread.
     */
    fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            _state.value =
                VoiceState.Error(
                    "Speech recognition is not available on this device",
                )
            return
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            _state.value = VoiceState.Error("Microphone permission revoked")
            return
        }

        releaseSpeechRecognizer()

        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer.setRecognitionListener(SpeechListener())
        speechRecognizer = recognizer

        val intent =
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                )
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            }

        _state.value = VoiceState.Listening()
        recognizer.startListening(intent)
    }

    /**
     * Stops an active speech recognition session.
     *
     * If the recognizer is currently listening, this cancels the
     * session and transitions the state back to [VoiceState.Idle].
     * Safe to call when not listening (no-op).
     *
     * Must be called on the main thread.
     */
    fun stopListening() {
        speechRecognizer?.cancel()
        releaseSpeechRecognizer()
        _state.value = VoiceState.Idle
    }

    /**
     * Reads the given text aloud using the text-to-speech engine.
     *
     * Transitions the state to [VoiceState.Speaking]. When the
     * utterance completes, the state returns to [VoiceState.Idle]
     * automatically via [TtsProgressListener].
     *
     * If the TTS engine is not yet initialised, or the device is in
     * power-save mode, the call is silently skipped and the state
     * returns to [VoiceState.Idle].
     *
     * @param text The response text to speak aloud.
     */
    fun speak(text: String) {
        if (isPowerSaveMode()) {
            _state.value = VoiceState.Idle
            return
        }

        if (!ttsReady) {
            if (BuildConfig.DEBUG) {
                android.util.Log.w(TAG, "TTS not ready, skipping speech")
            }
            _state.value = VoiceState.Idle
            return
        }

        _state.value = VoiceState.Speaking(text)

        @Suppress("DEPRECATION")
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    /**
     * Interrupts any in-progress TTS playback immediately.
     *
     * Transitions the state to [VoiceState.Idle]. Safe to call
     * when TTS is not speaking (no-op on the engine side).
     */
    fun stopSpeaking() {
        tts?.stop()
        _state.value = VoiceState.Idle
    }

    /**
     * Releases all native resources held by this bridge.
     *
     * Must be called when the bridge is no longer needed (e.g. in
     * [android.app.Activity.onDestroy] or ViewModel [onCleared][androidx.lifecycle.ViewModel.onCleared]).
     * After this call, no further methods should be invoked.
     */
    fun destroy() {
        releaseSpeechRecognizer()
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
    }

    /**
     * Checks whether the device is currently in power-save mode.
     *
     * @return `true` if the system power saver is active.
     */
    private fun isPowerSaveMode(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isPowerSaveMode
    }

    /**
     * Releases and nullifies the current [SpeechRecognizer] instance.
     */
    private fun releaseSpeechRecognizer() {
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    /**
     * Translates [SpeechRecognizer] error codes to human-readable messages.
     *
     * @param errorCode One of the `SpeechRecognizer.ERROR_*` constants.
     * @return A user-facing error description.
     */
    private fun mapRecognitionError(errorCode: Int): String =
        when (errorCode) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
            SpeechRecognizer.ERROR_CLIENT -> "Client-side error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                "Microphone permission not granted"
            SpeechRecognizer.ERROR_NETWORK -> "Network error during recognition"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognition service busy"
            SpeechRecognizer.ERROR_SERVER -> "Server error"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
            else -> "Recognition error (code $errorCode)"
        }

    /**
     * Listener that receives callbacks from [SpeechRecognizer] and
     * translates them into [VoiceState] transitions.
     */
    private inner class SpeechListener : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _state.value = VoiceState.Listening()
        }

        override fun onBeginningOfSpeech() {
            /** No state change needed; already [VoiceState.Listening]. */
        }

        override fun onRmsChanged(rmsdB: Float) {
            /** RMS level changes are not surfaced to the UI. */
        }

        override fun onBufferReceived(buffer: ByteArray?) {
            /** Raw audio buffers are not used. */
        }

        override fun onEndOfSpeech() {
            _state.value = VoiceState.Processing
        }

        override fun onError(error: Int) {
            val noMatch = error == SpeechRecognizer.ERROR_NO_MATCH
            val speechTimeout = error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            if (noMatch || speechTimeout) {
                _state.value = VoiceState.Idle
            } else {
                _state.value = VoiceState.Error(mapRecognitionError(error))
            }
            releaseSpeechRecognizer()
        }

        override fun onResults(results: Bundle?) {
            val matches =
                results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull().orEmpty().trim()

            if (text.isNotEmpty()) {
                _state.value = VoiceState.Processing
                onSpeechResult?.invoke(text)
            } else {
                _state.value = VoiceState.Idle
            }
            releaseSpeechRecognizer()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val partial =
                partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
            if (partial.isNotEmpty()) {
                _state.value = VoiceState.Listening(partialText = partial)
            }
        }

        override fun onEvent(
            eventType: Int,
            params: Bundle?,
        ) {
            /** Vendor-specific events are not handled. */
        }
    }

    /**
     * Listener that monitors TTS utterance progress and transitions
     * the [VoiceState] back to [VoiceState.Idle] when playback ends.
     */
    private inner class TtsProgressListener : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            /** Already in [VoiceState.Speaking]; no transition needed. */
        }

        override fun onDone(utteranceId: String?) {
            _state.value = VoiceState.Idle
        }

        @Deprecated("Deprecated in API 21+", ReplaceWith("onError(utteranceId, errorCode)"))
        override fun onError(utteranceId: String?) {
            _state.value = VoiceState.Error("Text-to-speech playback failed")
        }

        override fun onError(
            utteranceId: String?,
            errorCode: Int,
        ) {
            _state.value =
                VoiceState.Error(
                    "Text-to-speech error (code $errorCode)",
                )
        }
    }

    /** Constants for [VoiceBridge]. */
    companion object {
        private const val TAG = "VoiceBridge"
        private const val UTTERANCE_ID = "zeroclaw_tts_response"
    }
}
