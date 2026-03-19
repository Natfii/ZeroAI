/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.data.oauth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("AuthProfileStore")
class AuthProfileStoreTest {
    @Test
    @DisplayName("OpenAI aliases resolve to the Codex auth-profile provider")
    fun openAiAliasesResolveToCodexAuthProfileProvider() {
        assertEquals("openai-codex", AuthProfileStore.authProfileProviderFor("openai"))
        assertEquals("openai-codex", AuthProfileStore.authProfileProviderFor("chatgpt"))
        assertEquals("openai-codex", AuthProfileStore.authProfileProviderFor("codex"))
    }

    @Test
    @DisplayName("Anthropic resolves to the Claude auth-profile provider")
    fun anthropicResolvesToClaudeAuthProfileProvider() {
        assertEquals("anthropic", AuthProfileStore.authProfileProviderFor("anthropic"))
    }

    @Test
    @DisplayName("Unknown providers do not use auth profiles")
    fun unknownProvidersDoNotUseAuthProfiles() {
        assertNull(AuthProfileStore.authProfileProviderFor("ollama"))
    }
}
