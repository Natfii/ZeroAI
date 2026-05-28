/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.data

import com.zeroclaw.android.model.Agent

/**
 * Stable identifiers and seed data for the on-device large model agent.
 *
 * Unlike the cloud provider slots, this agent has no credentials — its
 * availability comes from a LiteRT-LM `.litertlm` file on disk plus a
 * runtime memory probe. Kept outside [ProviderSlotRegistry] because
 * the slot registry's validation pipeline assumes a backing
 * [ProviderRegistry] entry plus a credential mode, neither of which
 * applies here.
 *
 * Stored as a real [Agent] row so the "one agent enabled at a time"
 * invariant — enforced by `AgentDao.toggleExclusive` — covers it
 * uniformly alongside slot-backed agents.
 */
object OnDeviceLargeAgent {
    /** Stable agent row ID. */
    const val ID: String = "on-device-large"

    /** Human-readable name shown on the Agents tab card. */
    const val DISPLAY_NAME: String = "On-device large model"

    /**
     * Logical provider name used inside the [Agent.provider] field.
     *
     * Distinct from cloud provider names so daemon-side code can
     * filter out on-device agents when assembling cloud routing
     * configuration. The `litert-` prefix matches the runtime
     * actually hosting the model.
     */
    const val PROVIDER: String = "litert-on-device"

    /**
     * Builds the seed row inserted on first launch. Subsequent launches
     * preserve any user-set enabled state via the dao's insert-ignore
     * conflict strategy.
     *
     * @return Disabled placeholder [Agent] row.
     */
    fun seedAgent(): Agent =
        Agent(
            id = ID,
            name = DISPLAY_NAME,
            provider = PROVIDER,
            modelName = "",
            isEnabled = false,
            slotId = ID,
        )
}
