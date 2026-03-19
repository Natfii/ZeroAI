/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.onboarding.steps.personality

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.zeroclaw.android.ui.screen.onboarding.OnboardingCoordinator
import com.zeroclaw.android.ui.screen.onboarding.state.PersonalityArchetype
import com.zeroclaw.android.ui.screen.onboarding.state.PersonalityStepState

/**
 * Router composable dispatching to the correct personality sub-screen.
 *
 * Reads the [PersonalityStepState.currentSubStep] value and delegates to
 * one of five screens: [NameRoleScreen], [ArchetypeScreen],
 * [CommunicationStyleScreen], [CatchphrasesScreen], or [InterestsScreen].
 *
 * @param state Current personality step state snapshot.
 * @param onAgentNameChanged Callback when the agent name changes.
 * @param onRoleChanged Callback when the role selection changes.
 * @param onArchetypeSelected Callback when an archetype is selected.
 * @param onFormalityChanged Callback when formality selection changes.
 * @param onVerbosityChanged Callback when verbosity selection changes.
 * @param onCatchphrasesChanged Callback with updated catchphrase list.
 * @param onForbiddenWordsChanged Callback with updated forbidden words list.
 * @param onInterestToggled Callback with the topic string to toggle.
 * @param modifier Modifier applied to the active sub-screen.
 */
@Composable
fun PersonalityFlow(
    state: PersonalityStepState,
    onAgentNameChanged: (String) -> Unit,
    onRoleChanged: (String) -> Unit,
    onArchetypeSelected: (PersonalityArchetype) -> Unit,
    onFormalityChanged: (String) -> Unit,
    onVerbosityChanged: (String) -> Unit,
    onCatchphrasesChanged: (List<String>) -> Unit,
    onForbiddenWordsChanged: (List<String>) -> Unit,
    onInterestToggled: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state.currentSubStep) {
        OnboardingCoordinator.PERSONALITY_NAME_ROLE ->
            NameRoleScreen(
                agentName = state.agentName,
                role = state.role,
                onAgentNameChanged = onAgentNameChanged,
                onRoleChanged = onRoleChanged,
                modifier = modifier,
            )
        OnboardingCoordinator.PERSONALITY_ARCHETYPE ->
            ArchetypeScreen(
                selectedArchetype = state.archetype,
                onArchetypeSelected = onArchetypeSelected,
                modifier = modifier,
            )
        OnboardingCoordinator.PERSONALITY_COMMUNICATION ->
            CommunicationStyleScreen(
                formality = state.formality,
                verbosity = state.verbosity,
                onFormalityChanged = onFormalityChanged,
                onVerbosityChanged = onVerbosityChanged,
                modifier = modifier,
            )
        OnboardingCoordinator.PERSONALITY_CATCHPHRASES ->
            CatchphrasesScreen(
                catchphrases = state.catchphrases,
                forbiddenWords = state.forbiddenWords,
                onCatchphrasesChanged = onCatchphrasesChanged,
                onForbiddenWordsChanged = onForbiddenWordsChanged,
                modifier = modifier,
            )
        OnboardingCoordinator.PERSONALITY_INTERESTS ->
            InterestsScreen(
                selectedInterests = state.interests,
                onInterestToggled = onInterestToggled,
                modifier = modifier,
            )
    }
}
