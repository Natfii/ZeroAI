/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.terminal

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** Vertical padding inside the TTY input row in dp. */
private const val TTY_INPUT_V_PAD_DP = 4

/** Horizontal padding inside the TTY input row in dp. */
private const val TTY_INPUT_H_PAD_DP = 8

/**
 * Single-line text entry row for the TTY/Shell terminal surface.
 *
 * Renders a shell prompt prefix (with active `[Ctrl]`/`[Alt]` modifier
 * badges) and an editable field that forwards typed text to the PTY. All
 * colors are resolved from the active [LocalTerminalTheme] so the row
 * matches the GPU canvas above it, falling back to Material colors before
 * the theme has loaded.
 *
 * Owns its own draft [String] state; submitting (Enter / IME Send)
 * appends a carriage return and clears the draft. Paste-like input that
 * contains control characters is routed through [onPasteText] for the
 * paste-safety dialog instead of being sent directly.
 *
 * @param ctrlActive Whether the Ctrl modifier is currently toggled on.
 * @param altActive Whether the Alt modifier is currently toggled on.
 * @param focusRequester Shared focus requester so taps on the canvas can
 *   return focus to this field.
 * @param onTextInput Callback invoked with typed text to send to the PTY.
 * @param onPasteText Callback invoked with intercepted multi-character
 *   input containing control characters, routed through paste safety.
 * @param modifier Modifier applied to the outer [Surface].
 */
@Composable
internal fun TtyInputRow(
    ctrlActive: Boolean,
    altActive: Boolean,
    focusRequester: FocusRequester,
    onTextInput: (String) -> Unit,
    onPasteText: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var inputText by remember { mutableStateOf("") }

    val containerColor = themedReplBackground()
    val textColor = themedRoleColor(BlockRole.INPUT_TEXT)
    val accentColor = themedRoleColor(BlockRole.INPUT_PROMPT)

    Surface(
        color = containerColor,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier.padding(
                    horizontal = TTY_INPUT_H_PAD_DP.dp,
                    vertical = TTY_INPUT_V_PAD_DP.dp,
                ),
        ) {
            Text(
                text =
                    buildString {
                        if (ctrlActive) append("[Ctrl] ")
                        if (altActive) append("[Alt] ")
                        append("\$ ")
                    },
                color = accentColor,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
            BasicTextField(
                value = inputText,
                onValueChange = { newValue ->
                    val inserted = newValue.length - inputText.length
                    val hasControlChars =
                        newValue.any {
                            it == '\r' || it == '\n' || it == '\u001b'
                        }

                    if (hasControlChars && inserted > 1) {
                        val pastedText =
                            if (newValue.startsWith(inputText)) {
                                newValue.removePrefix(inputText)
                            } else {
                                newValue
                            }
                        onPasteText(pastedText)
                        inputText = ""
                    } else if (newValue.contains('\n')) {
                        val text = newValue.replace("\n", "")
                        if (text.isNotEmpty()) {
                            onTextInput(text + "\r")
                        }
                        inputText = ""
                    } else {
                        inputText = newValue
                    }
                },
                textStyle =
                    MaterialTheme.typography.bodySmall.copy(
                        color = textColor,
                        fontFamily = FontFamily.Monospace,
                    ),
                cursorBrush = SolidColor(accentColor),
                maxLines = 1,
                modifier =
                    Modifier
                        .weight(1f)
                        .focusRequester(focusRequester)
                        .semantics {
                            contentDescription = "Terminal input"
                        },
                keyboardOptions =
                    KeyboardOptions(
                        imeAction = ImeAction.Send,
                        autoCorrect = false,
                        keyboardType = KeyboardType.Ascii,
                    ),
                keyboardActions =
                    KeyboardActions(
                        onSend = {
                            if (inputText.isNotEmpty()) {
                                onTextInput(inputText + "\r")
                                inputText = ""
                            }
                        },
                    ),
            )
        }
    }
}
