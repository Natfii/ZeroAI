/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data

import android.content.SharedPreferences
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for [MapSharedPreferences], the volatile in-memory fallback
 * used when the Android Keystore is completely unusable.
 *
 * [SecurePrefsProvider] itself requires Android Keystore APIs and is
 * tested via instrumented tests. These tests verify the volatile
 * [SharedPreferences] implementation that runs in the [StorageHealth.Degraded] path.
 */
@DisplayName("MapSharedPreferences")
class SecurePrefsProviderTest {
    @Test
    @DisplayName("put string stores value in memory")
    fun `put string stores value in memory`() {
        val prefs = MapSharedPreferences()
        prefs.edit().putString("key", "value").apply()
        assertEquals("value", prefs.getString("key", "default"))
    }

    @Test
    @DisplayName("put boolean stores value in memory")
    fun `put boolean stores value in memory`() {
        val prefs = MapSharedPreferences()
        prefs.edit().putBoolean("flag", true).apply()
        assertTrue(prefs.getBoolean("flag", false))
    }

    @Test
    @DisplayName("put int stores value in memory")
    fun `put int stores value in memory`() {
        val prefs = MapSharedPreferences()
        prefs.edit().putInt("count", 42).apply()
        assertEquals(42, prefs.getInt("count", 0))
    }

    @Test
    @DisplayName("put long stores value in memory")
    fun `put long stores value in memory`() {
        val prefs = MapSharedPreferences()
        prefs.edit().putLong("time", 123456789L).apply()
        assertEquals(123456789L, prefs.getLong("time", 0L))
    }

    @Test
    @DisplayName("remove is a no-op on empty prefs")
    fun `remove is a no-op`() {
        val prefs = MapSharedPreferences()
        prefs.edit().remove("key").apply()
        assertTrue(prefs.all.isEmpty())
    }

    @Test
    @DisplayName("clear is a no-op on empty prefs")
    fun `clear is a no-op`() {
        val prefs = MapSharedPreferences()
        prefs.edit().clear().apply()
        assertTrue(prefs.all.isEmpty())
    }

    @Test
    @DisplayName("commit returns true")
    fun `commit returns true`() {
        val prefs = MapSharedPreferences()
        val result = prefs.edit().putString("key", "value").commit()
        assertTrue(result)
    }

    @Test
    @DisplayName("getAll returns in-memory values")
    fun `getAll returns in-memory values`() {
        val prefs = MapSharedPreferences()
        prefs.edit().putString("a", "1").apply()
        prefs.edit().putInt("b", 2).apply()
        assertEquals("1", prefs.all["a"])
        assertEquals(2, prefs.all["b"])
    }

    @Test
    @DisplayName("contains returns true for stored keys")
    fun `contains returns true for stored keys`() {
        val prefs = MapSharedPreferences()
        prefs.edit().putString("exists", "yes").apply()
        assertTrue(prefs.contains("exists"))
    }

    @Test
    @DisplayName("listener is notified when values change")
    fun `listener is notified`() {
        val prefs = MapSharedPreferences()
        val changed = mutableListOf<String?>()
        val listener =
            SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                changed.add(key)
            }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        prefs.edit().putString("test", "value").apply()
        assertEquals(listOf("test"), changed)
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }
}
