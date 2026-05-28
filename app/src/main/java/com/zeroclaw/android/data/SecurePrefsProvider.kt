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
     * Name of the single consolidated `EncryptedSharedPreferences` file
     * that all `create()` calls now back onto. Keys from each legacy
     * file are prefixed with a per-scope namespace (see [scopePrefix]).
     */
    internal const val CONSOLIDATED_PREFS_NAME = "zeroai_secure_v1"

    /**
     * Cached [MasterKey] shared across all [EncryptedSharedPreferences]
     * instances. The Android Keystore call inside [createMasterKey]
     * serialises on `MasterKeys.getOrCreate`; building one key per
     * prefs file used to make every parallel `create()` block on that
     * mutex (logged as 180-589ms contentions during cold start). Caching
     * collapses N keystore calls into one per process.
     */
    @Volatile
    private var cachedMasterKey: MasterKey? = null
    private val masterKeyLock = Any()

    /**
     * Cached consolidated [SharedPreferences] instance. The first
     * `create()` call builds it; later calls return the same instance
     * wrapped in a fresh [PrefixedSharedPreferences] view for the
     * requested scope.
     */
    @Volatile
    private var cachedConsolidated: SharedPreferences? = null
    private val consolidatedLock = Any()

    /** Tracks scopes whose legacy-file migration has run this process. */
    private val migrated = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * Per-scope migration locks so two concurrent callers for different
     * scopes don't serialize on a single global lock during the
     * expensive Tink/Keystore init for the legacy file.
     */
    private val scopeLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    /**
     * Maps a legacy prefs file name to the key prefix it now uses in
     * the consolidated file. Unknown names fall back to the legacy name
     * itself (no migration runs for them).
     */
    private fun scopePrefix(legacyName: String): String =
        when (legacyName) {
            "secure_settings" -> "settings."
            "zeroclaw_api_keys" -> "api."
            "zeroclaw_secure_prefs" -> "daemon."
            "zeroclaw_channel_secrets" -> "channel."
            "zeroclaw_email_secrets" -> "email."
            "zeroclaw_db_passphrase" -> "db."
            else -> "$legacyName."
        }

    private fun getOrCreateMasterKey(context: Context): MasterKey {
        cachedMasterKey?.let { return it }
        return synchronized(masterKeyLock) {
            cachedMasterKey ?: createMasterKey(context).also { cachedMasterKey = it }
        }
    }

    /**
     * Returns the single consolidated [EncryptedSharedPreferences] file
     * shared by all scopes, building it once per process.
     */
    private fun getOrCreateConsolidated(context: Context): SharedPreferences {
        cachedConsolidated?.let { return it }
        return synchronized(consolidatedLock) {
            cachedConsolidated ?: run {
                val masterKey = getOrCreateMasterKey(context)
                val prefs = createEncryptedPrefs(context, CONSOLIDATED_PREFS_NAME, masterKey)
                cachedConsolidated = prefs
                prefs
            }
        }
    }

    /**
     * Returns a prefixed view of the consolidated secure prefs for the
     * given legacy scope name, running a one-time copy from the legacy
     * file on first access.
     *
     * The legacy file is left on disk as rollback safety; a follow-up
     * leg deletes it once the consolidated layout has been validated in
     * production.
     *
     * @param context Application context for file access.
     * @param prefsName Legacy prefs file name (see [scopePrefix]).
     * @return The (prefixed) view and the storage health of the
     *   consolidated backing file.
     */
    fun create(
        context: Context,
        prefsName: String,
    ): Pair<SharedPreferences, StorageHealth> {
        val (consolidated, health) =
            try {
                getOrCreateConsolidated(context) to StorageHealth.Healthy
            } catch (e: GeneralSecurityException) {
                Log.w(TAG, "Consolidated prefs corrupted, recovering: ${e.message}", e)
                recoverConsolidated(context)
            } catch (e: IOException) {
                Log.w(TAG, "Consolidated prefs IO failure, recovering: ${e.message}", e)
                recoverConsolidated(context)
            }

        val prefix = scopePrefix(prefsName)
        maybeMigrateLegacy(context, prefsName, prefix, consolidated)
        return PrefixedSharedPreferences(consolidated, prefix) to health
    }

    /**
     * Copies key/value pairs from `<prefsName>` (a legacy
     * EncryptedSharedPreferences file) into the consolidated file
     * under `prefix`. Runs at most once per scope per process. Leaves
     * the legacy file untouched so a follow-up leg can delete it after
     * the consolidated layout proves stable.
     */
    @Suppress(
        "ReturnCount",
        "LongMethod",
        "CyclomaticComplexMethod",
        "CognitiveComplexMethod",
    )
    private fun maybeMigrateLegacy(
        context: Context,
        legacyName: String,
        prefix: String,
        consolidated: SharedPreferences,
    ) {
        if (migrated[legacyName] == true) return
        val migratedKey = migrationSentinelKey(legacyName)
        if (consolidated.getBoolean(migratedKey, false)) {
            migrated[legacyName] = true
            return
        }
        if (legacyName == CONSOLIDATED_PREFS_NAME) {
            migrated[legacyName] = true
            return
        }

        val perScopeLock = scopeLocks.getOrPut(legacyName) { Any() }
        synchronized(perScopeLock) {
            if (migrated[legacyName] == true) return
            if (consolidated.getBoolean(migratedKey, false)) {
                migrated[legacyName] = true
                return
            }

            val legacy =
                try {
                    val masterKey = getOrCreateMasterKey(context)
                    createEncryptedPrefs(context, legacyName, masterKey)
                } catch (e: GeneralSecurityException) {
                    Log.w(TAG, "Legacy '$legacyName' unreadable, skipping migration: ${e.message}")
                    consolidated.edit().putBoolean(migratedKey, true).apply()
                    migrated[legacyName] = true
                    return
                } catch (e: IOException) {
                    Log.w(TAG, "Legacy '$legacyName' IO error, skipping migration: ${e.message}")
                    consolidated.edit().putBoolean(migratedKey, true).apply()
                    migrated[legacyName] = true
                    return
                }

            val entries = legacy.all
            if (entries.isNotEmpty()) {
                val editor = consolidated.edit()
                for ((key, value) in entries) {
                    val targetKey = prefix + key
                    when (value) {
                        is String -> editor.putString(targetKey, value)
                        is Int -> editor.putInt(targetKey, value)
                        is Long -> editor.putLong(targetKey, value)
                        is Float -> editor.putFloat(targetKey, value)
                        is Boolean -> editor.putBoolean(targetKey, value)
                        is Set<*> -> {
                            @Suppress("UNCHECKED_CAST")
                            editor.putStringSet(
                                targetKey,
                                (value as Set<String>).toMutableSet(),
                            )
                        }
                        else -> Log.w(TAG, "Skipping unsupported value type for $legacyName/$key")
                    }
                }
                editor.putBoolean(migratedKey, true)
                editor.apply()
                Log.i(TAG, "Migrated ${entries.size} keys from '$legacyName' to consolidated prefs")
            } else {
                consolidated.edit().putBoolean(migratedKey, true).apply()
            }
            migrated[legacyName] = true
        }
    }

    /**
     * Reserved bookkeeping namespace for migration sentinels. Lives outside
     * every scope's prefix so [PrefixedSharedPreferences.getAll] /
     * [PrefixedSharedPreferences.contains] never expose it to callers.
     */
    internal const val MIGRATION_SENTINEL_PREFIX = "__zeroai_migration__."

    private fun migrationSentinelKey(legacyName: String): String = "$MIGRATION_SENTINEL_PREFIX$legacyName"

    @Suppress("TooGenericExceptionCaught")
    private fun recoverConsolidated(context: Context): Pair<SharedPreferences, StorageHealth> {
        cachedConsolidated = null
        return try {
            context.deleteSharedPreferences(CONSOLIDATED_PREFS_NAME)
            val masterKey = getOrCreateMasterKey(context)
            val prefs = createEncryptedPrefs(context, CONSOLIDATED_PREFS_NAME, masterKey)
            cachedConsolidated = prefs
            prefs to StorageHealth.Recovered
        } catch (e: Exception) {
            Log.e(TAG, "Consolidated recovery failed, falling back to in-memory: ${e.message}", e)
            MapSharedPreferences() to StorageHealth.Degraded
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
}

/**
 * A [SharedPreferences] view that transparently prefixes all key
 * accesses with a fixed scope string, backed by a shared underlying
 * [SharedPreferences] instance.
 *
 * Used by [SecurePrefsProvider] to give each legacy prefs scope its
 * own namespace inside the consolidated `zeroai_secure_v1` file
 * without forcing callers to know about the prefix.
 */
internal class PrefixedSharedPreferences(
    private val delegate: SharedPreferences,
    private val prefix: String,
) : SharedPreferences {
    private val listenerWrappers =
        java.util.IdentityHashMap<
            SharedPreferences.OnSharedPreferenceChangeListener,
            SharedPreferences.OnSharedPreferenceChangeListener,
        >()

    private fun k(key: String?): String? = key?.let { prefix + it }

    private fun unprefix(key: String): String = if (key.startsWith(prefix)) key.substring(prefix.length) else key

    override fun getAll(): MutableMap<String, *> {
        val out = HashMap<String, Any?>()
        for ((k, v) in delegate.all) {
            if (k.startsWith(prefix)) {
                out[unprefix(k)] = v
            }
        }
        return out
    }

    override fun getString(
        key: String?,
        defValue: String?,
    ): String? = delegate.getString(k(key), defValue)

    override fun getStringSet(
        key: String?,
        defValues: MutableSet<String>?,
    ): MutableSet<String>? = delegate.getStringSet(k(key), defValues)

    override fun getInt(
        key: String?,
        defValue: Int,
    ): Int = delegate.getInt(k(key), defValue)

    override fun getLong(
        key: String?,
        defValue: Long,
    ): Long = delegate.getLong(k(key), defValue)

    override fun getFloat(
        key: String?,
        defValue: Float,
    ): Float = delegate.getFloat(k(key), defValue)

    override fun getBoolean(
        key: String?,
        defValue: Boolean,
    ): Boolean = delegate.getBoolean(k(key), defValue)

    override fun contains(key: String?): Boolean = delegate.contains(k(key))

    override fun edit(): SharedPreferences.Editor = PrefixedEditor(delegate, delegate.edit(), prefix)

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) {
        listener ?: return
        val wrapper =
            SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
                if (changedKey == null || changedKey.startsWith(prefix)) {
                    listener.onSharedPreferenceChanged(
                        this,
                        changedKey?.let(::unprefix),
                    )
                }
            }
        synchronized(listenerWrappers) {
            listenerWrappers[listener]?.let(delegate::unregisterOnSharedPreferenceChangeListener)
            listenerWrappers[listener] = wrapper
        }
        delegate.registerOnSharedPreferenceChangeListener(wrapper)
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) {
        listener ?: return
        val wrapper = synchronized(listenerWrappers) { listenerWrappers.remove(listener) }
        wrapper?.let(delegate::unregisterOnSharedPreferenceChangeListener)
    }
}

/** Mutable editor that prepends [prefix] to every key. */
private class PrefixedEditor(
    private val backing: SharedPreferences,
    private val delegate: SharedPreferences.Editor,
    private val prefix: String,
) : SharedPreferences.Editor {
    private fun k(key: String?): String? = key?.let { prefix + it }

    override fun putString(
        key: String?,
        value: String?,
    ): SharedPreferences.Editor {
        delegate.putString(k(key), value)
        return this
    }

    override fun putStringSet(
        key: String?,
        values: MutableSet<String>?,
    ): SharedPreferences.Editor {
        delegate.putStringSet(k(key), values)
        return this
    }

    override fun putInt(
        key: String?,
        value: Int,
    ): SharedPreferences.Editor {
        delegate.putInt(k(key), value)
        return this
    }

    override fun putLong(
        key: String?,
        value: Long,
    ): SharedPreferences.Editor {
        delegate.putLong(k(key), value)
        return this
    }

    override fun putFloat(
        key: String?,
        value: Float,
    ): SharedPreferences.Editor {
        delegate.putFloat(k(key), value)
        return this
    }

    override fun putBoolean(
        key: String?,
        value: Boolean,
    ): SharedPreferences.Editor {
        delegate.putBoolean(k(key), value)
        return this
    }

    override fun remove(key: String?): SharedPreferences.Editor {
        delegate.remove(k(key))
        return this
    }

    override fun clear(): SharedPreferences.Editor {
        // Scope-only clear: remove every key under this prefix. Using
        // delegate.clear() here would wipe every other scope sharing the
        // consolidated backing file.
        for (key in backing.all.keys) {
            if (key.startsWith(prefix)) {
                delegate.remove(key)
            }
        }
        return this
    }

    override fun commit(): Boolean = delegate.commit()

    override fun apply() = delegate.apply()
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
