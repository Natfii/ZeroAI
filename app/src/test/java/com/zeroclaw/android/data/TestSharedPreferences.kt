/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data

import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

/**
 * Writable in-memory [SharedPreferences] for unit tests.
 *
 * Unlike the production [MapSharedPreferences] (which is read-only in degraded
 * mode), this implementation supports full read/write semantics so that
 * repository and integration tests can inject a working [SharedPreferences]
 * without requiring the Android Keystore.
 */
internal class TestSharedPreferences : SharedPreferences {
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

    override fun edit(): SharedPreferences.Editor = TestEditor(data, listeners)

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
}

/**
 * Writable editor for [TestSharedPreferences] that batches changes and
 * applies them atomically via [apply] or [commit].
 *
 * @param data The backing map to apply edits to.
 * @param listeners Registered preference change listeners to notify.
 */
private class TestEditor(
    private val data: ConcurrentHashMap<String, Any?>,
    private val listeners: ConcurrentHashMap<SharedPreferences.OnSharedPreferenceChangeListener, Boolean>,
) : SharedPreferences.Editor {
    private val pending = mutableMapOf<String, Any?>()
    private val removals = mutableSetOf<String>()
    private var clearAll = false

    override fun putString(
        key: String?,
        value: String?,
    ): SharedPreferences.Editor {
        key?.let { pending[it] = value }
        return this
    }

    override fun putStringSet(
        key: String?,
        values: MutableSet<String>?,
    ): SharedPreferences.Editor {
        key?.let { pending[it] = values }
        return this
    }

    override fun putInt(
        key: String?,
        value: Int,
    ): SharedPreferences.Editor {
        key?.let { pending[it] = value }
        return this
    }

    override fun putLong(
        key: String?,
        value: Long,
    ): SharedPreferences.Editor {
        key?.let { pending[it] = value }
        return this
    }

    override fun putFloat(
        key: String?,
        value: Float,
    ): SharedPreferences.Editor {
        key?.let { pending[it] = value }
        return this
    }

    override fun putBoolean(
        key: String?,
        value: Boolean,
    ): SharedPreferences.Editor {
        key?.let { pending[it] = value }
        return this
    }

    override fun remove(key: String?): SharedPreferences.Editor {
        key?.let { removals.add(it) }
        return this
    }

    override fun clear(): SharedPreferences.Editor {
        clearAll = true
        return this
    }

    override fun commit(): Boolean {
        applyChanges()
        return true
    }

    override fun apply() {
        applyChanges()
    }

    private fun applyChanges() {
        val changedKeys = mutableSetOf<String>()
        if (clearAll) {
            changedKeys.addAll(data.keys)
            data.clear()
        }
        removals.forEach { key ->
            data.remove(key)
            changedKeys.add(key)
        }
        pending.forEach { (key, value) ->
            data[key] = value
            changedKeys.add(key)
        }
        changedKeys.forEach { key ->
            listeners.keys.forEach { it.onSharedPreferenceChanged(null, key) }
        }
    }
}
