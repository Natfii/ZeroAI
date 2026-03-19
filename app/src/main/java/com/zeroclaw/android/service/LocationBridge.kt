/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

@file:Suppress("TooGenericExceptionCaught")

package com.zeroclaw.android.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.zeroclaw.android.model.LocationInfo
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Bridge between the Android location subsystem and the app layer.
 *
 * Uses the Google Play Services [FusedLocationProviderClient] for
 * battery-efficient location access. All operations verify that the
 * required location permission is granted before proceeding;
 * callers never need to suppress lint warnings themselves.
 *
 * @param context Application context used for permission checks and
 *     obtaining the [FusedLocationProviderClient].
 */
class LocationBridge(
    private val context: Context,
) {
    /** Fused location client from Google Play Services. */
    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    /** Active callback for continuous location updates, or `null` when idle. */
    @Volatile
    private var activeCallback: LocationCallback? = null

    /**
     * Retrieves the last known (cached) location without activating GPS.
     *
     * This is the cheapest possible location query. It returns whatever
     * the system already has cached, which may be stale or unavailable
     * on a freshly booted device.
     *
     * @return [Result.success] containing a [LocationInfo] if a cached
     *     location is available, or [Result.failure] with a
     *     [SecurityException] if permission is not granted, or a
     *     [IllegalStateException] if no cached location exists.
     */
    suspend fun getLastKnownLocation(): Result<LocationInfo> {
        if (!hasLocationPermission()) {
            return Result.failure(
                SecurityException("Location permission required"),
            )
        }
        return runCatching { awaitLastLocation() }.fold(
            onSuccess = { location ->
                if (location != null) {
                    Result.success(location)
                } else {
                    Result.failure(
                        IllegalStateException("No cached location available"),
                    )
                }
            },
            onFailure = Result.Companion::failure,
        )
    }

    /**
     * Requests a fresh location fix from the fused provider.
     *
     * Activates the appropriate hardware (GPS, WiFi, cell) based on the
     * requested [priority]. This may take several seconds depending on
     * conditions and priority level.
     *
     * @param priority Location request priority from
     *     [Priority]. Defaults to
     *     [Priority.PRIORITY_BALANCED_POWER_ACCURACY].
     * @return [Result.success] containing a [LocationInfo], or
     *     [Result.failure] with a [SecurityException] if permission is
     *     not granted.
     */
    suspend fun getCurrentLocation(
        priority: Int = Priority.PRIORITY_BALANCED_POWER_ACCURACY,
    ): Result<LocationInfo> {
        if (!hasLocationPermission()) {
            return Result.failure(
                SecurityException("Location permission required"),
            )
        }
        return runCatching { awaitCurrentLocation(priority) }.fold(
            onSuccess = Result.Companion::success,
            onFailure = Result.Companion::failure,
        )
    }

    /**
     * Starts continuous location updates at the specified interval.
     *
     * If updates are already active, they are stopped and restarted
     * with the new parameters. Each fix is delivered to [callback] as
     * a [LocationInfo]. Updates continue until [stopLocationUpdates]
     * is called.
     *
     * Does nothing if location permission is not granted.
     *
     * @param intervalMs Desired interval between updates in milliseconds.
     *     Defaults to 60,000 ms (one minute).
     * @param callback Invoked on each location fix with a [LocationInfo].
     */
    @SuppressLint("MissingPermission")
    fun startLocationUpdates(
        intervalMs: Long = DEFAULT_INTERVAL_MS,
        callback: (LocationInfo) -> Unit,
    ) {
        if (!hasLocationPermission()) return
        stopLocationUpdates()

        val safeIntervalMs = intervalMs.coerceAtLeast(MIN_UPDATE_INTERVAL_MS)
        val request =
            LocationRequest
                .Builder(
                    Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                    safeIntervalMs,
                ).setMinUpdateIntervalMillis(safeIntervalMs / MIN_INTERVAL_DIVISOR)
                .build()

        val locationCallback =
            object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val location = result.lastLocation ?: return
                    callback(location.toLocationInfo())
                }
            }

        activeCallback = locationCallback
        fusedClient.requestLocationUpdates(
            request,
            locationCallback,
            Looper.getMainLooper(),
        )
    }

    /**
     * Stops continuous location updates previously started by
     * [startLocationUpdates].
     *
     * Safe to call when no updates are active.
     */
    fun stopLocationUpdates() {
        activeCallback?.let { cb ->
            fusedClient.removeLocationUpdates(cb)
            activeCallback = null
        }
    }

    /**
     * Checks whether either fine or coarse location permission is granted.
     *
     * @return `true` if at least one location permission is granted.
     */
    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED

    /**
     * Awaits the last known location from the fused provider.
     *
     * @return A [LocationInfo] if available, or `null`.
     */
    @SuppressLint("MissingPermission")
    private suspend fun awaitLastLocation(): LocationInfo? =
        suspendCancellableCoroutine { continuation ->
            fusedClient.lastLocation
                .addOnSuccessListener { location ->
                    continuation.resume(location?.toLocationInfo())
                }.addOnFailureListener { exception ->
                    continuation.resumeWithException(exception)
                }
        }

    /**
     * Awaits a fresh location fix with the given priority.
     *
     * @param priority Location request priority constant.
     * @return A [LocationInfo] for the obtained fix.
     */
    @SuppressLint("MissingPermission")
    private suspend fun awaitCurrentLocation(priority: Int): LocationInfo =
        suspendCancellableCoroutine { continuation ->
            val cancellationSource = CancellationTokenSource()
            continuation.invokeOnCancellation {
                cancellationSource.cancel()
            }
            fusedClient
                .getCurrentLocation(priority, cancellationSource.token)
                .addOnSuccessListener { location ->
                    if (location != null) {
                        continuation.resume(location.toLocationInfo())
                    } else {
                        continuation.resumeWithException(
                            IllegalStateException(
                                "Location provider returned null",
                            ),
                        )
                    }
                }.addOnFailureListener { exception ->
                    continuation.resumeWithException(exception)
                }
        }

    /** Constants for [LocationBridge]. */
    companion object {
        /** Default interval between continuous location updates. */
        private const val DEFAULT_INTERVAL_MS = 60_000L

        /**
         * Divisor applied to the update interval to compute the minimum
         * update interval (fastest rate the app will accept).
         */
        private const val MIN_INTERVAL_DIVISOR = 2L

        /** Minimum allowed interval between location updates (10 seconds). */
        private const val MIN_UPDATE_INTERVAL_MS = 10_000L
    }
}

/**
 * Converts an Android [android.location.Location] to a [LocationInfo].
 *
 * @receiver The platform location object.
 * @return An immutable [LocationInfo] snapshot.
 */
private fun android.location.Location.toLocationInfo(): LocationInfo =
    LocationInfo(
        latitude = latitude,
        longitude = longitude,
        accuracy = accuracy,
        altitude = altitude,
        speed = speed,
        bearing = bearing,
        timestamp = time,
        provider = provider.orEmpty(),
    )
