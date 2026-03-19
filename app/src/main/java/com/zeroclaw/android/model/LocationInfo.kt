/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.model

import org.json.JSONObject

/**
 * Snapshot of a device location fix.
 *
 * Wraps the essential fields from a platform [android.location.Location]
 * into an immutable data class suitable for passing across layers without
 * leaking Android framework types.
 *
 * @property latitude Latitude in decimal degrees (WGS 84).
 * @property longitude Longitude in decimal degrees (WGS 84).
 * @property accuracy Estimated horizontal accuracy radius in metres.
 *     A value of `0.0` means accuracy is unknown.
 * @property altitude Altitude above the WGS 84 ellipsoid in metres.
 *     A value of `0.0` means altitude is unavailable.
 * @property speed Ground speed in metres per second.
 *     A value of `0.0` means speed is unavailable.
 * @property bearing Bearing (direction of travel) in degrees east of
 *     true north. A value of `0.0` means bearing is unavailable.
 * @property timestamp UTC time of the fix in milliseconds since epoch.
 * @property provider Name of the location provider that produced this
 *     fix (e.g. "fused", "gps", "network").
 */
data class LocationInfo(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val altitude: Double,
    val speed: Float,
    val bearing: Float,
    val timestamp: Long,
    val provider: String,
) {
    /**
     * Returns a human-readable summary of this location.
     *
     * Format: `"lat, lon (+/-accuracy m)"`, e.g.
     * `"37.7749, -122.4194 (+/-12.3 m)"`.
     *
     * @return Formatted location string suitable for terminal display.
     */
    fun toReadableString(): String = "%.6f, %.6f (\u00b1%.1f m)".format(latitude, longitude, accuracy)

    /**
     * Returns a JSON representation of this location.
     *
     * The JSON object contains all fields of this data class. Values
     * are serialised as JSON numbers or strings without any external
     * library dependency.
     *
     * @return JSON string suitable for agent context injection.
     */
    fun toJson(): String =
        JSONObject()
            .apply {
                put("latitude", latitude)
                put("longitude", longitude)
                put("accuracy", accuracy.toDouble())
                put("altitude", altitude)
                put("speed", speed.toDouble())
                put("bearing", bearing.toDouble())
                put("timestamp", timestamp)
                put("provider", provider)
            }.toString()
}
