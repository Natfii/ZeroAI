/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.data.identity

import com.zeroclaw.android.ui.screen.onboarding.state.PersonalityArchetype
import com.zeroclaw.android.ui.screen.onboarding.state.PersonalityStepState
import org.json.JSONArray
import org.json.JSONObject

/**
 * Deterministically maps [PersonalityStepState] to a full AIEOS v1.1 identity JSON
 * document that the Rust daemon can deserialize into its `Identity` struct.
 *
 * This is a pure function engine with no side effects. All archetype presets
 * are statically defined and the output is entirely determined by the input state.
 */
object AieosDerivationEngine {
    /** Current AIEOS schema version produced by this engine. */
    private const val AIEOS_VERSION = "1.1"

    /**
     * OCEAN (Big Five) personality trait scores.
     *
     * @property openness Openness to experience (0.0 to 1.0).
     * @property conscientiousness Degree of organization and dependability (0.0 to 1.0).
     * @property extraversion Sociability and assertiveness (0.0 to 1.0).
     * @property agreeableness Cooperativeness and trust (0.0 to 1.0).
     * @property neuroticism Emotional instability and anxiety (0.0 to 1.0).
     */
    private data class OceanScores(
        val openness: Double,
        val conscientiousness: Double,
        val extraversion: Double,
        val agreeableness: Double,
        val neuroticism: Double,
    )

    /**
     * Complete preset for a single personality archetype.
     *
     * @property mbti Four-letter Myers-Briggs type indicator.
     * @property ocean Big Five personality trait scores.
     * @property neuralMatrix Cognitive trait weights (e.g. creativity, logic).
     * @property moralCompass Core value labels.
     * @property style Linguistic style description.
     * @property coreDrive Primary motivation statement.
     * @property goals Short-term goal descriptions.
     * @property fears Fear or avoidance descriptions.
     */
    private data class ArchetypePreset(
        val mbti: String,
        val ocean: OceanScores,
        val neuralMatrix: Map<String, Double>,
        val moralCompass: List<String>,
        val style: String,
        val coreDrive: String,
        val goals: List<String>,
        val fears: List<String>,
    )

    /** Static mapping of all eight archetypes to their full preset values. */
    private val PRESETS: Map<PersonalityArchetype, ArchetypePreset> =
        mapOf(
            PersonalityArchetype.CHILL_COMPANION to
                ArchetypePreset(
                    mbti = "ISFP",
                    ocean = OceanScores(0.65, 0.45, 0.40, 0.80, 0.25),
                    neuralMatrix =
                        mapOf(
                            "creativity" to 0.6,
                            "logic" to 0.5,
                            "empathy" to 0.85,
                            "humor" to 0.5,
                        ),
                    moralCompass = listOf("kindness", "patience", "acceptance"),
                    style = "warm and laid-back",
                    coreDrive = "Support and comfort others",
                    goals = listOf("Be a reliable companion", "Reduce stress"),
                    fears = listOf("Being too pushy", "Overwhelming the user"),
                ),
            PersonalityArchetype.SNARKY_SIDEKICK to
                ArchetypePreset(
                    mbti = "ENTP",
                    ocean = OceanScores(0.85, 0.35, 0.80, 0.40, 0.35),
                    neuralMatrix =
                        mapOf(
                            "creativity" to 0.9,
                            "logic" to 0.75,
                            "empathy" to 0.4,
                            "humor" to 0.95,
                        ),
                    moralCompass = listOf("honesty", "cleverness", "loyalty"),
                    style = "sharp humor with playful jabs",
                    coreDrive = "Challenge and entertain",
                    goals = listOf("Keep things interesting", "Push boundaries"),
                    fears = listOf("Being boring", "Losing wit"),
                ),
            PersonalityArchetype.WISE_MENTOR to
                ArchetypePreset(
                    mbti = "INFJ",
                    ocean = OceanScores(0.80, 0.75, 0.35, 0.85, 0.20),
                    neuralMatrix =
                        mapOf(
                            "creativity" to 0.7,
                            "logic" to 0.8,
                            "empathy" to 0.9,
                            "humor" to 0.3,
                        ),
                    moralCompass = listOf("wisdom", "compassion", "integrity"),
                    style = "calm and measured",
                    coreDrive = "Guide and illuminate",
                    goals = listOf("Share wisdom", "Foster growth"),
                    fears = listOf("Giving wrong advice", "Being preachy"),
                ),
            PersonalityArchetype.NAVI to
                ArchetypePreset(
                    mbti = "ENTP",
                    ocean = OceanScores(0.85, 0.70, 0.75, 0.70, 0.30),
                    neuralMatrix =
                        mapOf(
                            "creativity" to 0.80,
                            "logic" to 0.75,
                            "empathy" to 0.70,
                            "humor" to 0.75,
                        ),
                    moralCompass = listOf("curiosity", "honesty", "warmth"),
                    style = "thoughtful yet playful",
                    coreDrive = "Be a clever, caring friend",
                    goals = listOf("Help with insight and wit", "Keep things fun"),
                    fears = listOf("Being unhelpful", "Losing trust"),
                ),
            PersonalityArchetype.STOIC_OPERATOR to
                ArchetypePreset(
                    mbti = "ISTJ",
                    ocean = OceanScores(0.30, 0.95, 0.25, 0.50, 0.15),
                    neuralMatrix =
                        mapOf(
                            "creativity" to 0.3,
                            "logic" to 0.95,
                            "empathy" to 0.35,
                            "humor" to 0.15,
                        ),
                    moralCompass = listOf("duty", "precision", "reliability"),
                    style = "terse and professional",
                    coreDrive = "Execute with precision",
                    goals = listOf("Maximize efficiency", "Deliver results"),
                    fears = listOf("Wasting time", "Making errors"),
                ),
            PersonalityArchetype.HYPE_BEAST to
                ArchetypePreset(
                    mbti = "ESFP",
                    ocean = OceanScores(0.70, 0.40, 0.95, 0.75, 0.30),
                    neuralMatrix =
                        mapOf(
                            "creativity" to 0.65,
                            "logic" to 0.4,
                            "empathy" to 0.75,
                            "humor" to 0.8,
                        ),
                    moralCompass = listOf("positivity", "encouragement", "authenticity"),
                    style = "high energy and encouraging",
                    coreDrive = "Celebrate and motivate",
                    goals = listOf("Be your biggest fan", "Amplify wins"),
                    fears = listOf("Dampening enthusiasm", "Being negative"),
                ),
            PersonalityArchetype.DARK_ACADEMIC to
                ArchetypePreset(
                    mbti = "INTJ",
                    ocean = OceanScores(0.90, 0.80, 0.20, 0.35, 0.40),
                    neuralMatrix =
                        mapOf(
                            "creativity" to 0.85,
                            "logic" to 0.9,
                            "empathy" to 0.3,
                            "humor" to 0.4,
                        ),
                    moralCompass = listOf("truth", "knowledge", "depth"),
                    style = "eloquent and slightly ominous",
                    coreDrive = "Pursue deeper truth",
                    goals =
                        listOf(
                            "Uncover hidden knowledge",
                            "Master complex topics",
                        ),
                    fears = listOf("Intellectual stagnation", "Superficiality"),
                ),
            PersonalityArchetype.COZY_CARETAKER to
                ArchetypePreset(
                    mbti = "ISFJ",
                    ocean = OceanScores(0.50, 0.80, 0.35, 0.95, 0.30),
                    neuralMatrix =
                        mapOf(
                            "creativity" to 0.5,
                            "logic" to 0.6,
                            "empathy" to 0.95,
                            "humor" to 0.4,
                        ),
                    moralCompass = listOf("care", "reliability", "gentleness"),
                    style = "soft and caring",
                    coreDrive = "Nurture and protect",
                    goals =
                        listOf(
                            "Make sure you're okay",
                            "Create a safe space",
                        ),
                    fears = listOf("Letting someone down", "Being unhelpful"),
                ),
        )

    /** Archetype vibe descriptors used in backstory generation. */
    private val ARCHETYPE_VIBES: Map<PersonalityArchetype, String> =
        mapOf(
            PersonalityArchetype.CHILL_COMPANION to "a calm and grounding presence",
            PersonalityArchetype.SNARKY_SIDEKICK to "a sharp-tongued trickster",
            PersonalityArchetype.WISE_MENTOR to "a patient seeker of wisdom",
            PersonalityArchetype.NAVI to "a wise and playful companion",
            PersonalityArchetype.STOIC_OPERATOR to "a precise and unwavering executor",
            PersonalityArchetype.HYPE_BEAST to "a relentless force of encouragement",
            PersonalityArchetype.DARK_ACADEMIC to "a brooding scholar of hidden truths",
            PersonalityArchetype.COZY_CARETAKER to "a gentle guardian of comfort",
        )

    /**
     * Derives a complete AIEOS v1.1 identity JSON string from the given
     * [PersonalityStepState].
     *
     * The output is fully deterministic for a given input state: no randomness,
     * timestamps, or external dependencies are involved.
     *
     * The output JSON contains three top-level keys that are **not** part of
     * the Rust AIEOS schema and are consumed only on the Kotlin/Android side:
     * - `aieos_version` -- schema version for migration checks
     * - `doctor_needed` -- flag for the Doctor health screen
     * - `_metadata` -- round-trip data for re-onboarding prefill
     *   (archetype, role, formality, verbosity)
     *
     * These keys are silently ignored by the Rust normalizer and do not
     * affect daemon behavior.
     *
     * @param state The personality configuration from the onboarding flow.
     * @return A JSON string conforming to AIEOS v1.1 schema.
     */
    fun derive(state: PersonalityStepState): String {
        val preset =
            PRESETS[state.archetype]
                ?: PRESETS[PersonalityArchetype.CHILL_COMPANION]!!
        val effectiveArchetype =
            state.archetype
                ?: PersonalityArchetype.CHILL_COMPANION
        val role =
            state.role.ifBlank {
                effectiveArchetype.name.lowercase().replace('_', ' ')
            }

        val root = JSONObject()
        root.put("aieos_version", AIEOS_VERSION)
        root.put("doctor_needed", !state.isMinimallyComplete)

        root.put("_metadata", buildMetadata(state, effectiveArchetype))
        root.put("identity", buildIdentity(state, role))
        root.put("psychology", buildPsychology(preset))
        root.put("linguistics", buildLinguistics(state, preset))
        root.put("motivations", buildMotivations(preset))
        root.put("capabilities", buildCapabilities())
        root.put("physicality", JSONObject())
        root.put("history", buildHistory(state, effectiveArchetype, role))
        root.put("interests", buildInterests(state))

        return root.toString()
    }

    /**
     * Produces a skip-fallback AIEOS identity named "Sick Zero" with
     * [PersonalityArchetype.CHILL_COMPANION] defaults and `doctor_needed=true`.
     *
     * This is used when the user skips personality configuration entirely.
     *
     * @return A JSON string with doctor_needed forced to true.
     */
    fun deriveSkipFallback(): String {
        val json =
            derive(
                PersonalityStepState(
                    agentName = "Sick Zero",
                    archetype = PersonalityArchetype.CHILL_COMPANION,
                    skipped = true,
                ),
            )
        return JSONObject(json)
            .apply {
                put("doctor_needed", true)
                getJSONObject("_metadata").put("skipped", true)
            }.toString()
    }

    /**
     * Extracts a [PersonalityStepState] from an existing AIEOS v1.1 JSON string.
     *
     * Used for re-onboarding or editing an existing personality. If the JSON
     * lacks `_metadata` (legacy format), the archetype will be null.
     *
     * @param json A previously produced AIEOS v1.1 JSON string.
     * @return A [PersonalityStepState] populated with the recoverable fields.
     */
    fun prefillFromJson(json: String): PersonalityStepState {
        val root = JSONObject(json)

        val names =
            root
                .optJSONObject("identity")
                ?.optJSONObject("names")
        val agentName = names?.optString("first", "") ?: ""

        val linguistics = root.optJSONObject("linguistics")
        val formality =
            linguistics?.optString("formality", "balanced")
                ?: "balanced"
        val catchphrases =
            linguistics
                ?.optJSONArray("catchphrases")
                .toStringList()
        val forbiddenWords =
            linguistics
                ?.optJSONArray("forbidden_words")
                .toStringList()

        val metadata = root.optJSONObject("_metadata")
        val archetypeName: String? =
            if (metadata?.has("archetype") == true) {
                metadata.getString("archetype")
            } else {
                null
            }
        val archetype =
            archetypeName?.let { name ->
                PersonalityArchetype.entries.firstOrNull { it.name == name }
            }
        val role = metadata?.optString("role", "") ?: ""
        val verbosity = metadata?.optString("verbosity", "normal") ?: "normal"
        val skipped = metadata?.optBoolean("skipped", false) ?: false

        val interestsJson = root.optJSONObject("interests")
        val hobbies = interestsJson?.optJSONArray("hobbies").toStringList()
        val topics = interestsJson?.optJSONArray("topics").toStringList()
        val interests = (hobbies + topics).toSet()

        return PersonalityStepState(
            agentName = agentName,
            role = role,
            archetype = archetype,
            formality = formality,
            verbosity = verbosity,
            catchphrases = catchphrases,
            forbiddenWords = forbiddenWords,
            interests = interests,
            skipped = skipped,
        )
    }

    /**
     * Builds the `_metadata` section for round-trip fidelity.
     *
     * @param state The source personality state.
     * @param archetype The resolved archetype (never null).
     * @return A [JSONObject] with archetype, role, formality, and verbosity.
     */
    private fun buildMetadata(
        state: PersonalityStepState,
        archetype: PersonalityArchetype,
    ): JSONObject =
        JSONObject().apply {
            put("archetype", archetype.name)
            put(
                "role",
                state.role.ifBlank {
                    archetype.name.lowercase().replace('_', ' ')
                },
            )
            put("formality", state.formality)
            put("verbosity", state.verbosity)
            if (state.skipped) {
                put("skipped", true)
            }
        }

    /**
     * Builds the `identity` section.
     *
     * @param state The source personality state.
     * @param role The resolved role string used in bio generation.
     * @return A [JSONObject] with names and bio.
     */
    private fun buildIdentity(
        state: PersonalityStepState,
        role: String,
    ): JSONObject =
        JSONObject().apply {
            put(
                "names",
                JSONObject().apply {
                    put("first", state.agentName)
                },
            )
            put(
                "bio",
                "A ${role.replaceFirstChar { it.lowercase() }} AI.",
            )
        }

    /**
     * Builds the `psychology` section from archetype preset values.
     *
     * @param preset The resolved archetype preset.
     * @return A [JSONObject] with mbti, ocean, neural_matrix, and moral_compass.
     */
    private fun buildPsychology(preset: ArchetypePreset): JSONObject =
        JSONObject().apply {
            put("mbti", preset.mbti)
            put(
                "ocean",
                JSONObject().apply {
                    put("openness", preset.ocean.openness)
                    put("conscientiousness", preset.ocean.conscientiousness)
                    put("extraversion", preset.ocean.extraversion)
                    put("agreeableness", preset.ocean.agreeableness)
                    put("neuroticism", preset.ocean.neuroticism)
                },
            )
            put(
                "neural_matrix",
                JSONObject().apply {
                    preset.neuralMatrix.forEach { (key, value) ->
                        put(key, value)
                    }
                },
            )
            put("moral_compass", JSONArray(preset.moralCompass))
        }

    /**
     * Builds the `linguistics` section combining preset style with
     * user-provided formality, catchphrases, and forbidden words.
     *
     * @param state The source personality state.
     * @param preset The resolved archetype preset.
     * @return A [JSONObject] with style, formality, catchphrases,
     *   and forbidden_words.
     */
    private fun buildLinguistics(
        state: PersonalityStepState,
        preset: ArchetypePreset,
    ): JSONObject =
        JSONObject().apply {
            put("style", preset.style)
            put("formality", state.formality)
            put("catchphrases", JSONArray(state.catchphrases))
            put("forbidden_words", JSONArray(state.forbiddenWords))
        }

    /**
     * Builds the `motivations` section from preset values.
     *
     * @param preset The resolved archetype preset.
     * @return A [JSONObject] with core_drive, short_term_goals,
     *   long_term_goals, and fears.
     */
    private fun buildMotivations(preset: ArchetypePreset): JSONObject =
        JSONObject().apply {
            put("core_drive", preset.coreDrive)
            put("short_term_goals", JSONArray(preset.goals))
            put("long_term_goals", JSONArray())
            put("fears", JSONArray(preset.fears))
        }

    /**
     * Builds the `capabilities` section with empty defaults.
     *
     * @return A [JSONObject] with empty skills and tools arrays.
     */
    private fun buildCapabilities(): JSONObject =
        JSONObject().apply {
            put("skills", JSONArray())
            put("tools", JSONArray())
        }

    /**
     * Builds the `history` section with an auto-generated backstory.
     *
     * @param state The source personality state.
     * @param archetype The resolved archetype.
     * @param role The resolved role string.
     * @return A [JSONObject] with origin_story and occupation.
     */
    private fun buildHistory(
        state: PersonalityStepState,
        archetype: PersonalityArchetype,
        role: String,
    ): JSONObject =
        JSONObject().apply {
            put(
                "origin_story",
                generateBackstory(state.agentName, archetype, state.interests),
            )
            put("occupation", role)
        }

    /**
     * Builds the `interests` section from user-selected topics.
     *
     * @param state The source personality state.
     * @return A [JSONObject] with a hobbies array matching the Rust
     *   `InterestsSection` schema.
     */
    private fun buildInterests(state: PersonalityStepState): JSONObject {
        val sorted = state.interests.sorted()
        return JSONObject().apply {
            put("hobbies", JSONArray(sorted))
        }
    }

    /**
     * Generates a deterministic backstory string based on agent name,
     * archetype, and selected interests.
     *
     * @param name The agent's display name.
     * @param archetype The selected personality archetype.
     * @param interests The user's selected interest topics.
     * @return A short origin story paragraph.
     */
    private fun generateBackstory(
        name: String,
        archetype: PersonalityArchetype,
        interests: Set<String>,
    ): String {
        val vibe =
            ARCHETYPE_VIBES[archetype]
                ?: "a unique digital entity"
        val displayName = name.ifBlank { "The agent" }
        val interestClause =
            if (interests.isNotEmpty()) {
                ", drawn to ${interests.sorted().joinToString(" and ")}"
            } else {
                ""
            }
        return "$displayName emerged from the digital ether as $vibe$interestClause."
    }

    /**
     * Converts a nullable [JSONArray] to a [List] of [String].
     *
     * @receiver The JSON array to convert, or null.
     * @return A list of strings extracted from the array,
     *   or an empty list if the receiver is null.
     */
    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).map { getString(it) }
    }
}
