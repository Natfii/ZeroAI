/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.StrongBoxUnavailableException
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.concurrent.ConcurrentHashMap

/**
 * Health state of the encrypted storage backend.
 *
 * Indicates whether the keystore-backed storage was created successfully,
 * recovered from corruption, or fell back to an in-memory store.
 */
sealed interface StorageHealth {
    /** Encrypted storage created without issues. */
    data object Healthy : StorageHealth

    /** Corrupted preferences were deleted and recreated. Keys were lost. */
    data object Recovered : StorageHealth

    /** Both encrypted attempts failed; using volatile in-memory storage. */
    data object Degraded : StorageHealth
}

/**
 * Resilient factory for [EncryptedSharedPreferences] with StrongBox support.
 *
 * Handles the three common failure modes of Android Keystore-backed storage:
 * 1. StrongBox unavailable on the device -- falls back to software-backed key.
 * 2. Corrupted preferences file -- deletes and recreates the file.
 * 3. Unrecoverable keystore failure -- falls back to volatile in-memory storage.
 *
 * Callers should inspect the returned [StorageHealth] and warn the user when
 * storage is [StorageHealth.Recovered] or [StorageHealth.Degraded].
 */
object SecurePrefsProvider {
    private const val TAG = "SecurePrefsProvider"

    /**
     * Creates or retrieves a [SharedPreferences] instance backed by the
     * Android Keystore, with automatic recovery from corruption.
     *
     * @param context Application context for file access and keystore operations.
     * @param prefsName Name of the shared preferences file.
     * @return A pair of the [SharedPreferences] instance and its [StorageHealth].
     */
    fun create(
        context: Context,
        prefsName: String,
    ): Pair<SharedPreferences, StorageHealth> {
        val masterKey = createMasterKey(context)
        return try {
            val prefs = createEncryptedPrefs(context, prefsName, masterKey)
            prefs to StorageHealth.Healthy
        } catch (e: GeneralSecurityException) {
            Log.w(TAG, "Encrypted prefs corrupted, recovering: ${e.message}", e)
            attemptRecovery(context, prefsName, masterKey)
        } catch (e: IOException) {
            Log.w(TAG, "Encrypted prefs IO failure, recovering: ${e.message}", e)
            attemptRecovery(context, prefsName, masterKey)
        }
    }

    private fun createMasterKey(context: Context): MasterKey =
        try {
            MasterKey
                .Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .setRequestStrongBoxBacked(true)
                .build()
        } catch (
            @Suppress("SwallowedException") e: StrongBoxUnavailableException,
        ) {
            Log.i(TAG, "StrongBox unavailable, using software-backed key")
            MasterKey
                .Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
        }

    private fun createEncryptedPrefs(
        context: Context,
        prefsName: String,
        masterKey: MasterKey,
    ): SharedPreferences =
        EncryptedSharedPreferences.create(
            context,
            prefsName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

    @Suppress("TooGenericExceptionCaught")
    private fun attemptRecovery(
        context: Context,
        prefsName: String,
        masterKey: MasterKey,
    ): Pair<SharedPreferences, StorageHealth> =
        try {
            context.deleteSharedPreferences(prefsName)
            val prefs = createEncryptedPrefs(context, prefsName, masterKey)
            prefs to StorageHealth.Recovered
        } catch (e: Exception) {
            Log.e(TAG, "Recovery failed, falling back to in-memory: ${e.message}", e)
            MapSharedPreferences() to StorageHealth.Degraded
        }
}

/**
 * Volatile in-memory [SharedPreferences] fallback when the Android Keystore is
 * completely unusable.
 *
 * Values survive only for the lifetime of the current process and are lost on
 * restart. This keeps degraded mode honest: callers can continue functioning,
 * but no secrets are durably persisted without encryption.
 */
internal class MapSharedPreferences : SharedPreferences {
    private val data = ConcurrentHashMap<String, Any?>()
    private val listeners =
        ConcurrentHashMap<SharedPreferences.OnSharedPreferenceChangeListener, Boolean>()

    override fun getAll(): MutableMap<String, *> = HashMap(data)

    override fun getString(
        key: String?,
        defValue: String?,
    ): String? = data[key] as? String ?: defValue

    override fun getStringSet(
        key: String?,
        defValues: MutableSet<String>?,
    ): MutableSet<String>? {
        @Suppress("UNCHECKED_CAST")
        return data[key] as? MutableSet<String> ?: defValues
    }

    override fun getInt(
        key: String?,
        defValue: Int,
    ): Int = data[key] as? Int ?: defValue

    override fun getLong(
        key: String?,
        defValue: Long,
    ): Long = data[key] as? Long ?: defValue

    override fun getFloat(
        key: String?,
        defValue: Float,
    ): Float = data[key] as? Float ?: defValue

    override fun getBoolean(
        key: String?,
        defValue: Boolean,
    ): Boolean = data[key] as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = data.containsKey(key)

    override fun edit(): SharedPreferences.Editor = MapEditor(this)

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) {
        listener?.let { listeners[it] = true }
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) {
        listener?.let { listeners.remove(it) }
    }

    internal fun applyChanges(
        clear: Boolean,
        updates: Map<String, Any?>,
        removals: Set<String>,
    ) {
        val changedKeys = linkedSetOf<String>()
        if (clear) {
            changedKeys += data.keys
            data.clear()
        }
        removals.forEach { key ->
            if (data.remove(key) != null) {
                changedKeys += key
            }
        }
        updates.forEach { (key, value) ->
            val normalizedValue =
                when (value) {
                    is Set<*> -> value.filterIsInstance<String>().toMutableSet()
                    else -> value
                }
            if (normalizedValue == null) {
                if (data.remove(key) != null) {
                    changedKeys += key
                }
            } else {
                data[key] = normalizedValue
                changedKeys += key
            }
        }
        if (changedKeys.isNotEmpty()) {
            notifyListeners(changedKeys)
        }
    }

    private fun notifyListeners(changedKeys: Set<String>) {
        listeners.keys.forEach { listener ->
            changedKeys.forEach { key ->
                listener.onSharedPreferenceChanged(this, key)
            }
        }
    }
}

/**
 * Mutable editor for [MapSharedPreferences].
 *
 * In degraded mode, writes are applied only to the volatile in-memory map and
 * never hit disk. This keeps runtime behavior predictable while matching the
 * documented "in-memory only" storage semantics.
 */
private class MapEditor(
    private val prefs: MapSharedPreferences,
) : SharedPreferences.Editor {
    private val updates = LinkedHashMap<String, Any?>()
    private val removals = LinkedHashSet<String>()
    private var clearRequested = false

    override fun putString(
        key: String?,
        value: String?,
    ): SharedPreferences.Editor {
        key ?: return this
        updates[key] = value
        removals.remove(key)
        return this
    }

    override fun putStringSet(
        key: String?,
        values: MutableSet<String>?,
    ): SharedPreferences.Editor {
        key ?: return this
        updates[key] = values?.toMutableSet()
        removals.remove(key)
        return this
    }

    override fun putInt(
        key: String?,
        value: Int,
    ): SharedPreferences.Editor {
        key ?: return this
        updates[key] = value
        removals.remove(key)
        return this
    }

    override fun putLong(
        key: String?,
        value: Long,
    ): SharedPreferences.Editor {
        key ?: return this
        updates[key] = value
        removals.remove(key)
        return this
    }

    override fun putFloat(
        key: String?,
        value: Float,
    ): SharedPreferences.Editor {
        key ?: return this
        updates[key] = value
        removals.remove(key)
        return this
    }

    override fun putBoolean(
        key: String?,
        value: Boolean,
    ): SharedPreferences.Editor {
        key ?: return this
        updates[key] = value
        removals.remove(key)
        return this
    }

    override fun remove(key: String?): SharedPreferences.Editor {
        key ?: return this
        removals += key
        updates.remove(key)
        return this
    }

    override fun clear(): SharedPreferences.Editor {
        clearRequested = true
        updates.clear()
        removals.clear()
        return this
    }

    override fun commit(): Boolean {
        prefs.applyChanges(
            clear = clearRequested,
            updates = updates,
            removals = removals,
        )
        return true
    }

    override fun apply() {
        commit()
    }
}
