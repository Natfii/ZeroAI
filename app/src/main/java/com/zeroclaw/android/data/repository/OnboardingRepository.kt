/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data.repository

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Repository interface for tracking onboarding completion state and
 * the last-visible wizard step.
 *
 * The current-step value is persisted on every step transition so users
 * who background the app mid-onboarding return to the same step instead
 * of being kicked back to Step 1.
 */
interface OnboardingRepository {
    /** Whether the user has completed the onboarding wizard. */
    val isCompleted: Flow<Boolean>

    /**
     * Last-saved onboarding step index (zero-based). Emits `0` until a
     * step has been explicitly saved.
     */
    val savedStep: Flow<Int>

    /**
     * Last-saved non-sensitive draft of in-progress onboarding selections.
     * Emits `null` when no draft has ever been saved (or the saved blob
     * was malformed) so callers can distinguish "user never saved" from
     * "user saved all-default values".
     */
    val savedDraft: Flow<OnboardingDraft?>

    /** Marks the onboarding wizard as completed. */
    suspend fun markComplete()

    /** Persists [step] as the user's current onboarding position. */
    suspend fun saveStep(step: Int)

    /** Persists [draft] as the user's in-progress onboarding selections. */
    suspend fun saveDraft(draft: OnboardingDraft)

    /** Resets onboarding so the wizard is shown again. */
    suspend fun reset()
}

/** Extension property providing the singleton [DataStore] for onboarding. */
private val Context.onboardingDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "onboarding",
)

/**
 * Lenient JSON codec shared by [DataStoreOnboardingRepository] and tests.
 *
 * `ignoreUnknownKeys` lets newer app builds tolerate fields written by
 * older drafts; `encodeDefaults` keeps the on-disk shape stable.
 */
private val DRAFT_JSON =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

/**
 * Decodes a persisted onboarding draft, returning `null` when [raw] is
 * absent or malformed. Extracted from [DataStoreOnboardingRepository] so
 * the corruption-recovery path can be exercised in plain unit tests.
 *
 * @param raw The raw JSON string from DataStore, or `null` when no draft
 *   has been saved yet.
 */
internal fun decodeDraft(raw: String?): OnboardingDraft? {
    if (raw == null) return null
    return try {
        DRAFT_JSON.decodeFromString(OnboardingDraft.serializer(), raw)
    } catch (ex: SerializationException) {
        Log.w("OnboardingRepository", "Discarding malformed onboarding draft", ex)
        null
    }
}

/**
 * [OnboardingRepository] implementation backed by Jetpack DataStore Preferences.
 *
 * @param context Application context for DataStore initialization.
 */
class DataStoreOnboardingRepository(
    private val context: Context,
) : OnboardingRepository {
    override val isCompleted: Flow<Boolean> =
        context.onboardingDataStore.data.map { prefs ->
            prefs[KEY_COMPLETED] ?: false
        }

    override val savedStep: Flow<Int> =
        context.onboardingDataStore.data.map { prefs ->
            prefs[KEY_CURRENT_STEP] ?: 0
        }

    override val savedDraft: Flow<OnboardingDraft?> =
        context.onboardingDataStore.data.map { prefs ->
            decodeDraft(prefs[KEY_DRAFT_JSON])
        }

    override suspend fun markComplete() {
        context.onboardingDataStore.edit { prefs ->
            prefs[KEY_COMPLETED] = true
        }
    }

    override suspend fun saveStep(step: Int) {
        context.onboardingDataStore.edit { prefs ->
            prefs[KEY_CURRENT_STEP] = step
        }
    }

    override suspend fun saveDraft(draft: OnboardingDraft) {
        val encoded = DRAFT_JSON.encodeToString(OnboardingDraft.serializer(), draft)
        context.onboardingDataStore.edit { prefs ->
            prefs[KEY_DRAFT_JSON] = encoded
        }
    }

    override suspend fun reset() {
        context.onboardingDataStore.edit { prefs ->
            prefs[KEY_COMPLETED] = false
            prefs[KEY_CURRENT_STEP] = 0
            prefs.remove(KEY_DRAFT_JSON)
        }
    }

    /** DataStore preference keys for [DataStoreOnboardingRepository]. */
    companion object {
        /** Preference key for onboarding completion state. */
        val KEY_COMPLETED = booleanPreferencesKey("onboarding_completed")

        /** Preference key for last-saved step index. */
        val KEY_CURRENT_STEP = intPreferencesKey("current_step")

        /** Preference key for the JSON-encoded onboarding draft. */
        val KEY_DRAFT_JSON = stringPreferencesKey("draft_json_v1")
    }
}
