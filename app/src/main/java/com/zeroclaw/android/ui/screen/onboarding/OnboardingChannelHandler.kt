/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.onboarding

import com.zeroclaw.android.data.channel.ChannelSetupSpecs
import com.zeroclaw.android.data.validation.ChannelValidator
import com.zeroclaw.android.data.validation.ValidationResult
import com.zeroclaw.android.model.ChannelType
import com.zeroclaw.android.ui.screen.onboarding.state.ChannelSelectionState
import com.zeroclaw.android.ui.screen.onboarding.state.ChannelSubFlowState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the channel-step side of onboarding: which channel types are
 * selected, each channel's configuration sub-flow state, and per-channel
 * credential validation. Extracted from [OnboardingCoordinator] so the
 * coordinator no longer carries ~130 lines of channel plumbing.
 *
 * @property selectionState The shared channel-selection state flow.
 * @property subFlowStates The shared map of per-channel sub-flow states.
 * @property scope Coroutine scope (the coordinator's `viewModelScope`).
 */
@Suppress("OutdatedDocumentation")
internal class OnboardingChannelHandler(
    private val selectionState: MutableStateFlow<ChannelSelectionState>,
    private val subFlowStates: MutableStateFlow<Map<ChannelType, ChannelSubFlowState>>,
    private val scope: CoroutineScope,
) {
    /**
     * Toggles a channel type in the selection set.
     *
     * Adding a type initialises its sub-flow state. Removing a type
     * preserves the sub-flow state so it can be restored if re-selected.
     */
    fun toggleChannelSelection(type: ChannelType) {
        val current = selectionState.value.selectedTypes
        val updated = if (type in current) current - type else current + type
        selectionState.value = selectionState.value.copy(selectedTypes = updated)
        if (type !in subFlowStates.value) {
            subFlowStates.value = subFlowStates.value + (type to ChannelSubFlowState())
        }
    }

    /** Opens the configuration sub-flow for a specific channel type. */
    fun startChannelSubFlow(type: ChannelType) {
        selectionState.value = selectionState.value.copy(activeSubFlowType = type)
        if (type !in subFlowStates.value) {
            subFlowStates.value = subFlowStates.value + (type to ChannelSubFlowState())
        }
    }

    /** Exits the current channel sub-flow and marks it as completed. */
    fun exitChannelSubFlow() {
        val activeType = selectionState.value.activeSubFlowType ?: return
        val subFlow = subFlowStates.value[activeType] ?: return
        subFlowStates.value =
            subFlowStates.value + (activeType to subFlow.copy(completed = true))
        selectionState.value = selectionState.value.copy(activeSubFlowType = null)
    }

    /**
     * Updates a single field value within a channel's sub-flow state.
     *
     * @param type The channel type whose field to update.
     * @param key The field key matching a TOML configuration key.
     * @param value The field value.
     */
    fun setChannelField(
        type: ChannelType,
        key: String,
        value: String,
    ) {
        val subFlow = subFlowStates.value[type] ?: ChannelSubFlowState()
        subFlowStates.value =
            subFlowStates.value +
            (type to subFlow.copy(fieldValues = subFlow.fieldValues + (key to value)))
    }

    /**
     * Advances to the next sub-step within a channel's sub-flow,
     * bounds-checked against [ChannelSetupSpecs] for the given type.
     */
    fun nextChannelSubStep(type: ChannelType) {
        val subFlow = subFlowStates.value[type] ?: return
        val maxSteps = ChannelSetupSpecs.forType(type)?.steps?.size ?: return
        if (subFlow.currentSubStep < maxSteps - 1) {
            subFlowStates.value =
                subFlowStates.value +
                (type to subFlow.copy(currentSubStep = subFlow.currentSubStep + 1))
        }
    }

    /** Returns to the previous sub-step within a channel's sub-flow. */
    fun previousChannelSubStep(type: ChannelType) {
        val subFlow = subFlowStates.value[type] ?: return
        if (subFlow.currentSubStep > 0) {
            subFlowStates.value =
                subFlowStates.value +
                (type to subFlow.copy(currentSubStep = subFlow.currentSubStep - 1))
        }
    }

    /**
     * Validates the current channel's token or credentials. Sets the
     * sub-flow's validationResult to [ValidationResult.Loading] immediately,
     * then launches a coroutine that calls [ChannelValidator.validate]
     * and updates the result to a terminal state.
     */
    fun validateChannel(type: ChannelType) {
        val subFlow = subFlowStates.value[type] ?: return
        subFlowStates.value =
            subFlowStates.value +
            (type to subFlow.copy(validationResult = ValidationResult.Loading))
        scope.launch {
            val result =
                ChannelValidator.validate(
                    channelType = type,
                    fields = subFlow.fieldValues,
                )
            val current = subFlowStates.value[type] ?: return@launch
            subFlowStates.value =
                subFlowStates.value + (type to current.copy(validationResult = result))
        }
    }
}
