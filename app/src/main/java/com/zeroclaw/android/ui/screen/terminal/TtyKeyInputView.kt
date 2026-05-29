/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.terminal

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.core.content.getSystemService
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/** DEL byte (0x7f) sent for a backspace key press. */
private const val ASCII_DEL: Byte = 0x7F

/** ESC byte introducing a control sequence. */
private const val ASCII_ESC: Byte = 0x1B

/** `[` byte in a CSI sequence. */
private const val CSI_BRACKET: Byte = 0x5B

/** `3` byte in the forward-delete CSI sequence `ESC [ 3 ~`. */
private const val CSI_THREE: Byte = 0x33

/** `~` byte terminating the forward-delete CSI sequence `ESC [ 3 ~`. */
private const val CSI_TILDE: Byte = 0x7E

/**
 * Invisible focusable input proxy that feeds keystrokes to the active PTY.
 *
 * This view owns the soft-keyboard connection for the TTY/Shell surface so
 * that every keystroke reaches the pseudo-terminal immediately, instead of
 * being buffered in a Compose text field and flushed on Enter. With the line
 * living in the PTY, the interactive shell's own line editor handles cursor
 * movement, so the arrow keys (and Home/End/Ctrl-A/E, history, tab
 * completion, and full-screen apps such as `vim`) all behave like a real
 * terminal.
 *
 * It draws nothing and is sized to a single pixel by the host; it must never
 * be in the touch path so the GPU canvas keeps its own tap/selection/zoom
 * gestures. Input arrives by two disjoint streams that the IME never uses for
 * the same character: physical/soft key events ([onKeyDown]) and committed
 * text ([InputConnection.commitText]). [InputType.TYPE_NULL] keeps the IME in
 * raw mode (no autocorrect, suggestions, or swipe typing — correct for a
 * shell), which routes ordinary typing through key events.
 *
 * @param context The hosting context, supplied by the Compose `AndroidView`
 *   factory.
 */
class TtyKeyInputView(
    context: Context,
) : View(context) {
    /** Invoked with printable text typed or committed by the IME. */
    var onText: (String) -> Unit = {}

    /** Invoked with a named special key (Enter, arrows, Tab, Esc, Home/End, Page Up/Down). */
    var onNamedKey: (TtySpecialKey) -> Unit = {}

    /** Invoked with raw bytes for keys without a [TtySpecialKey] (backspace, forward delete). */
    var onBytes: (ByteArray) -> Unit = {}

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /**
     * Declares this view as a text editor so the framework requests an
     * [InputConnection] and shows the soft keyboard when it gains focus.
     *
     * @return Always `true`.
     */
    override fun onCheckIsTextEditor(): Boolean = true

    /**
     * Creates the raw input connection that forwards committed text to the PTY.
     *
     * Uses [InputType.TYPE_NULL] so the IME sends ordinary typing as key
     * events (handled in [onKeyDown]); [commitText] covers the remaining cases
     * (clipboard, predictions) for keyboards that commit text directly. The
     * connection deliberately does not call `super.commitText`, so committed
     * text is never also re-synthesised as key events (avoiding duplicates).
     *
     * @param outAttrs Editor attributes to populate for the IME.
     * @return The input connection bound to this view.
     */
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_NULL
        outAttrs.imeOptions =
            EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, false) {
            override fun commitText(
                text: CharSequence?,
                newCursorPosition: Int,
            ): Boolean {
                val committed = text?.toString().orEmpty()
                if (committed.isNotEmpty()) onText(committed)
                return true
            }
        }
    }

    /**
     * Routes key events to the PTY: named keys via [onNamedKey], backspace and
     * forward delete via [onBytes], and any other printable key as text.
     *
     * @param keyCode The pressed key code.
     * @param event The key event carrying modifier and unicode metadata.
     * @return `true` when the key was consumed and forwarded to the PTY.
     */
    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        val named = namedKeyFor(keyCode)
        if (named != null) {
            onNamedKey(named)
            return true
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DEL -> {
                onBytes(byteArrayOf(ASCII_DEL))
                return true
            }
            KeyEvent.KEYCODE_FORWARD_DEL -> {
                onBytes(byteArrayOf(ASCII_ESC, CSI_BRACKET, CSI_THREE, CSI_TILDE))
                return true
            }
        }
        val codePoint = event.getUnicodeChar(event.metaState)
        if (codePoint != 0) {
            onText(String(Character.toChars(codePoint)))
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * Requests focus and shows the soft keyboard for this view.
     *
     * Posts the request so it runs after the view is attached and laid out,
     * preferring [WindowInsetsCompat] (more reliable across OEM keyboards)
     * with an [InputMethodManager] fallback.
     */
    fun showKeyboard() {
        requestFocus()
        post {
            val window = activityWindow()
            if (window != null) {
                WindowCompat.getInsetsController(window, this).show(WindowInsetsCompat.Type.ime())
            } else {
                inputMethodManager()?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    /** Hides the soft keyboard. */
    fun hideKeyboard() {
        val window = activityWindow()
        if (window != null) {
            WindowCompat.getInsetsController(window, this).hide(WindowInsetsCompat.Type.ime())
        } else {
            inputMethodManager()?.hideSoftInputFromWindow(windowToken, 0)
        }
    }

    private fun inputMethodManager(): InputMethodManager? = context.getSystemService<InputMethodManager>()

    private fun activityWindow(): Window? {
        var current: Context? = context
        while (current is ContextWrapper) {
            if (current is Activity) return current.window
            current = current.baseContext
        }
        return null
    }

    private companion object {
        /** Maps a key code to its [TtySpecialKey], or `null` if it is not a named key. */
        fun namedKeyFor(keyCode: Int): TtySpecialKey? =
            when (keyCode) {
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> TtySpecialKey.ENTER
                KeyEvent.KEYCODE_TAB -> TtySpecialKey.TAB
                KeyEvent.KEYCODE_ESCAPE -> TtySpecialKey.ESC
                KeyEvent.KEYCODE_DPAD_UP -> TtySpecialKey.UP
                KeyEvent.KEYCODE_DPAD_DOWN -> TtySpecialKey.DOWN
                KeyEvent.KEYCODE_DPAD_LEFT -> TtySpecialKey.LEFT
                KeyEvent.KEYCODE_DPAD_RIGHT -> TtySpecialKey.RIGHT
                KeyEvent.KEYCODE_MOVE_HOME -> TtySpecialKey.HOME
                KeyEvent.KEYCODE_MOVE_END -> TtySpecialKey.END
                KeyEvent.KEYCODE_PAGE_UP -> TtySpecialKey.PAGE_UP
                KeyEvent.KEYCODE_PAGE_DOWN -> TtySpecialKey.PAGE_DOWN
                else -> null
            }
    }
}
