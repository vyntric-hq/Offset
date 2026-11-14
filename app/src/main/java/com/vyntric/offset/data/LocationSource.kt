/*
 * Offset, an Android radio programming client.
 * Copyright (C) 2026 Offset contributors. Licensed under GPL-3.0-or-later.
 */
package com.vyntric.offset.data

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

data class Fix(val latitude: Double, val longitude: Double, val accuracyM: Float?, val timeMillis: Long, val provider: String)

/**
 * One shot position from the platform LocationManager (no Play Services). Tries GPS, then the
 * network provider, then the freshest last known fix.
 */
class LocationSource(private val context: Context) {
    private val manager = context.getSystemService(LocationManager::class.java)

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    fun isEnabled(): Boolean = manager != null && LocationManagerCompat.isLocationEnabled(manager)

    @SuppressLint("MissingPermission")
    suspend fun current(timeoutMs: Long = 20_000): Fix? {
        val m = manager ?: return null
        if (!hasPermission()) return null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { m.isProviderEnabled(it) }.getOrDefault(false) }
        for (p in providers) {
            val loc = withTimeoutOrNull(timeoutMs / providers.size.coerceAtLeast(1)) { single(m, p) }
            if (loc != null) return loc.toFix()
        }
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { m.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }?.toFix()
    }

    @SuppressLint("MissingPermission")
    private suspend fun single(m: LocationManager, provider: String): Location? = suspendCancellableCoroutine { cont ->
        val signal = CancellationSignal()
        cont.invokeOnCancellation { signal.cancel() }
        LocationManagerCompat.getCurrentLocation(m, provider, signal, ContextCompat.getMainExecutor(context)) { loc ->
            if (cont.isActive) cont.resume(loc)
        }
    }

    private fun Location.toFix() = Fix(latitude, longitude, if (hasAccuracy()) accuracy else null, time, provider ?: "")
}
