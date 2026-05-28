/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.data.repository

import kotlinx.serialization.Serializable

/**
 * Non-sensitive snapshot of in-progress onboarding selections.
 *
 * Intentionally scoped to two sections only: the provider step (so a
 * partially-completed OAuth login like ChatGPT or Claude survives
 * backgrounding) and the identity step (agent name, timezone, etc.).
 * Other step values — autonomy, memory, channels, personality — are
 * persisted directly to their respective repositories the moment the
 * user changes them, so there is no separate draft layer to keep in
 * sync.
 *
 * @property provider Persisted provider-step selection, or `null` if
 *   the user has not touched the provider step in this wizard session.
 * @property identity Persisted identity-step values, or `null` if the
 *   user has not touched the identity step in this wizard session.
 */
@Serializable
data class OnboardingDraft(
    val provider: ProviderSection? = null,
    val identity: IdentitySection? = null,
)

/**
 * Persisted subset of [com.zeroclaw.android.ui.screen.onboarding.state.ProviderStepState].
 *
 * @property providerId Canonical provider identifier (e.g. `anthropic`, `openai`).
 * @property slotId Stable identifier for the slot/key entry within the provider.
 * @property baseUrl Optional override of the provider's base URL.
 * @property model Selected model name for the provider.
 */
@Serializable
data class ProviderSection(
    val providerId: String = "",
    val slotId: String = "",
    val baseUrl: String = "",
    val model: String = "",
)

/**
 * Persisted subset of [com.zeroclaw.android.ui.screen.onboarding.state.IdentityStepState].
 *
 * @property agentName Human-facing name for the agent.
 * @property userName Display name the user wants the agent to use for them.
 * @property timezone IANA timezone identifier (e.g. `America/New_York`).
 * @property communicationStyle Preferred tone/style descriptor.
 * @property identityFormat Identity blob format selector for downstream rendering.
 */
@Serializable
data class IdentitySection(
    val agentName: String = "",
    val userName: String = "",
    val timezone: String = "",
    val communicationStyle: String = "",
    val identityFormat: String = "",
)
