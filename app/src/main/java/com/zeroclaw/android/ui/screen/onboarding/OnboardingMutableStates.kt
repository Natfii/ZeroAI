/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.onboarding

import com.zeroclaw.android.model.ChannelType
import com.zeroclaw.android.model.OnDeviceAiUiState
import com.zeroclaw.android.ui.screen.onboarding.state.ActivationStepState
import com.zeroclaw.android.ui.screen.onboarding.state.ChannelSelectionState
import com.zeroclaw.android.ui.screen.onboarding.state.ChannelSubFlowState
import com.zeroclaw.android.ui.screen.onboarding.state.IdentityStepState
import com.zeroclaw.android.ui.screen.onboarding.state.MemoryStepState
import com.zeroclaw.android.ui.screen.onboarding.state.PersonalityStepState
import com.zeroclaw.android.ui.screen.onboarding.state.ProviderStepState
import com.zeroclaw.android.ui.screen.onboarding.state.SecurityStepState
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Single-source bundle of every mutable wizard-step state flow.
 *
 * Held by [OnboardingCoordinator] and passed to handlers that need to
 * mutate multiple steps in one place (notably the upcoming prefill /
 * completion paths). Each field is exposed as a top-level property so
 * call sites read like `states.provider.value` instead of needing a
 * separate constructor parameter per step.
 *
 * Created internally with default values; the coordinator constructs
 * exactly one instance, no callers should construct extras.
 */
internal class OnboardingMutableStates {
    /** Current wizard step index (zero-based). */
    val currentStep: MutableStateFlow<Int> = MutableStateFlow(OnboardingCoordinator.STEP_PERMISSIONS)

    /** PIN hash from the security step, empty when no PIN is set. */
    val pinHash: MutableStateFlow<String> = MutableStateFlow("")

    /** Whether the session lock is enabled. */
    val lockEnabled: MutableStateFlow<Boolean> = MutableStateFlow(false)

    /** Provider step state. */
    val provider: MutableStateFlow<ProviderStepState> = MutableStateFlow(ProviderStepState())

    /** On-device AI step state. */
    val onDeviceAi: MutableStateFlow<OnDeviceAiUiState> =
        MutableStateFlow(OnDeviceAiUiState())

    /** Channel selection step state. */
    val channelSelection: MutableStateFlow<ChannelSelectionState> =
        MutableStateFlow(ChannelSelectionState())

    /** Per-channel sub-flow states keyed by channel type. */
    val channelSubFlows: MutableStateFlow<Map<ChannelType, ChannelSubFlowState>> =
        MutableStateFlow(emptyMap())

    /** Security / autonomy step state. */
    val security: MutableStateFlow<SecurityStepState> = MutableStateFlow(SecurityStepState())

    /** Memory configuration step state. */
    val memory: MutableStateFlow<MemoryStepState> = MutableStateFlow(MemoryStepState())

    /** Identity step state. */
    val identity: MutableStateFlow<IdentityStepState> = MutableStateFlow(IdentityStepState())

    /** Personality step state. */
    val personality: MutableStateFlow<PersonalityStepState> =
        MutableStateFlow(PersonalityStepState())

    /** Activation step state (isCompleting flag + completeError message). */
    val activation: MutableStateFlow<ActivationStepState> =
        MutableStateFlow(ActivationStepState())
}
