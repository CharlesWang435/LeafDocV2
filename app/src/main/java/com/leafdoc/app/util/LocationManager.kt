package com.leafdoc.app.util

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellableContinuation
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class LocationManager @Inject constructor(
    private val context: Context
) {
    private val fusedLocationClient: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(context)
    }

    companion object {
        // Cap how long a single fresh-location request waits. Without this, indoors / GPS-off
        // the callback never fires, the request leaks, and the coroutine hangs forever.
        private const val SINGLE_LOCATION_TIMEOUT_MS = 10_000L
    }

    /**
     * Checks if location permission is granted.
     */
    fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Gets the current location if permission is granted.
     * Returns null if permission is not granted or location is unavailable.
     */
    suspend fun getCurrentLocation(): LocationData? {
        if (!hasLocationPermission()) {
            return null
        }
        return getCurrentLocationInternal()
    }

    @SuppressLint("MissingPermission")
    private suspend fun getCurrentLocationInternal(): LocationData? = suspendCancellableCoroutine { continuation ->
        val resumed = java.util.concurrent.atomic.AtomicBoolean(false)
        fun finish(data: LocationData?) {
            if (resumed.compareAndSet(false, true) && continuation.isActive) {
                continuation.resume(data)
            }
        }
        try {
            fusedLocationClient.lastLocation
                .addOnSuccessListener { location: Location? ->
                    if (location != null) {
                        finish(location.toLocationData())
                    } else {
                        // No cached fix — request a fresh one (with its own timeout/cleanup).
                        requestSingleLocationInternal(continuation) { freshLocation -> finish(freshLocation) }
                    }
                }
                .addOnFailureListener { finish(null) }
        } catch (e: SecurityException) {
            finish(null)
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestSingleLocationInternal(
        continuation: CancellableContinuation<LocationData?>,
        callback: (LocationData?) -> Unit
    ) {
        try {
            val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
                .setMaxUpdates(1)
                .build()

            val handler = Handler(Looper.getMainLooper())
            val done = java.util.concurrent.atomic.AtomicBoolean(false)
            lateinit var locationCallback: LocationCallback

            // Fires if no fix arrives in time: unregister and resume with null instead of hanging.
            val timeout = Runnable {
                if (done.compareAndSet(false, true)) {
                    fusedLocationClient.removeLocationUpdates(locationCallback)
                    callback(null)
                }
            }

            locationCallback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    if (done.compareAndSet(false, true)) {
                        handler.removeCallbacks(timeout)
                        fusedLocationClient.removeLocationUpdates(this)
                        callback(result.lastLocation?.toLocationData())
                    }
                }
            }

            // If the caller's scope is cancelled, drop the request so the listener can't leak.
            continuation.invokeOnCancellation {
                if (done.compareAndSet(false, true)) {
                    handler.removeCallbacks(timeout)
                    fusedLocationClient.removeLocationUpdates(locationCallback)
                }
            }

            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
            handler.postDelayed(timeout, SINGLE_LOCATION_TIMEOUT_MS)
        } catch (e: SecurityException) {
            callback(null)
        }
    }

    /**
     * Returns a flow of location updates if permission is granted.
     * Returns empty flow if permission is not granted.
     */
    fun locationUpdates(): Flow<LocationData> {
        if (!hasLocationPermission()) {
            return emptyFlow()
        }
        return locationUpdatesInternal()
    }

    @SuppressLint("MissingPermission")
    private fun locationUpdatesInternal(): Flow<LocationData> = callbackFlow {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000)
            .setMinUpdateIntervalMillis(2000)
            .build()

        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.toLocationData()?.let { location ->
                    trySend(location)
                }
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )

            awaitClose {
                fusedLocationClient.removeLocationUpdates(locationCallback)
            }
        } catch (e: SecurityException) {
            close()
        }
    }

    private fun Location.toLocationData(): LocationData {
        return LocationData(
            latitude = latitude,
            longitude = longitude,
            altitude = if (hasAltitude()) altitude else null,
            accuracy = if (hasAccuracy()) accuracy else null,
            timestamp = time
        )
    }
}

data class LocationData(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double?,
    val accuracy: Float?,
    val timestamp: Long
)
