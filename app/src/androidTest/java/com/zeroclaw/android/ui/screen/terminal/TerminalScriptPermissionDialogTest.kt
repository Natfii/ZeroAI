/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.terminal

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose tests for [TerminalScriptPermissionDialog].
 */
@RunWith(AndroidJUnit4::class)
class TerminalScriptPermissionDialogTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun dialog_showsScriptMetadataAndCapabilityControls() {
        val request =
            TerminalScriptPermissionRequest(
                relativePath = "workflows/cleanup.rhai",
                manifestName = "Cleanup workspace",
                runtime = "rhai",
                requestedCapabilities = listOf("fs.read", "net.http"),
                grantedCapabilities = listOf("fs.read"),
                missingCapabilities = listOf("shell.exec"),
                warnings = listOf("Uses network access"),
            )

        composeTestRule.setContent {
            TerminalScriptPermissionDialog(
                request = request,
                onToggleCapability = {},
                onGrantAll = {},
                onDenyAll = {},
                onConfirm = {},
                onDismiss = {},
            )
        }

        composeTestRule.onNodeWithText("Review script permissions").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cleanup workspace").assertIsDisplayed()
        composeTestRule.onNodeWithText("Path: workflows/cleanup.rhai").assertIsDisplayed()
        composeTestRule.onNodeWithText("Runtime: rhai").assertIsDisplayed()
        composeTestRule.onNodeWithText("Warnings").assertIsDisplayed()
        composeTestRule.onNodeWithText("• Uses network access").assertIsDisplayed()
        composeTestRule.onNodeWithText("Missing from manifest").assertIsDisplayed()
        composeTestRule.onNodeWithText("• shell.exec").assertIsDisplayed()
        composeTestRule.onNodeWithText("Grant all").assertIsDisplayed()
        composeTestRule.onNodeWithText("Deny all").assertIsDisplayed()
        composeTestRule.onNodeWithText("Run script").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cancel").assertIsDisplayed()
        composeTestRule.onNodeWithText("fs.read").assertIsDisplayed()
        composeTestRule.onNodeWithText("net.http").assertIsDisplayed()
    }

    @Test
    fun capabilityRow_click_invokesToggleCallback() {
        val request =
            TerminalScriptPermissionRequest(
                relativePath = "workflows/cleanup.rhai",
                manifestName = "Cleanup workspace",
                runtime = "rhai",
                requestedCapabilities = listOf("fs.read"),
                grantedCapabilities = listOf("fs.read"),
                missingCapabilities = emptyList(),
                warnings = emptyList(),
            )
        var toggledCapability: String? = null

        composeTestRule.setContent {
            TerminalScriptPermissionDialog(
                request = request,
                onToggleCapability = { toggledCapability = it },
                onGrantAll = {},
                onDenyAll = {},
                onConfirm = {},
                onDismiss = {},
            )
        }

        composeTestRule
            .onNodeWithContentDescription("fs.read permission granted")
            .performClick()

        assertEquals("fs.read", toggledCapability)
    }
}
