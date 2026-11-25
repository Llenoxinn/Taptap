package io.github.llenoxinn.taptap.android.mesh

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location as AndroidLocation
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority as GmsPriority
import io.github.llenoxinn.taptap.mesh.Location
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.roundToInt

interface LocationProvider {
    suspend fun lastKnownOrNull(nowEpochS: Long): Location
    fun watchFixes(nowEpochSProvider: () -> Long): Flow<Location>
}

class AndroidLocationProvider(private val context: Context) : LocationProvider {
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(context) }

    @SuppressLint("MissingPermission")
    override suspend fun lastKnownOrNull(nowEpochS: Long): Location {
        if (!hasLocationPermission()) return NO_FIX
        val fusedFix = suspendCancellableCoroutine<AndroidLocation?> { cont ->
            try {
                fused.lastLocation
                    .addOnSuccessListener { loc -> if (cont.isActive) cont.resume(loc) }
                    .addOnFailureListener { if (cont.isActive) cont.resume(null) }
            } catch (_: SecurityException) {
                if (cont.isActive) cont.resume(null)
            }
        }
        val best = fusedFix ?: bestLocationManagerFix()
        return best?.toMeshLocation(nowEpochS) ?: NO_FIX
    }

    @SuppressLint("MissingPermission")
    override fun watchFixes(nowEpochSProvider: () -> Long): Flow<Location> = callbackFlow {
        if (!hasLocationPermission()) {
            close()
            return@callbackFlow
        }
        val request = LocationRequest.Builder(GmsPriority.PRIORITY_HIGH_ACCURACY, 5_000L)
            .setMinUpdateIntervalMillis(2_000L)
            .setMaxUpdates(12)
            .build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { trySend(it.toMeshLocation(nowEpochSProvider())) }
            }
        }
        try {
            fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
        } catch (_: SecurityException) {
            close()
        }
        awaitClose { fused.removeLocationUpdates(callback) }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun bestLocationManagerFix(): AndroidLocation? {
        val mgr = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        return providers.mapNotNull { provider ->
            runCatching { mgr.getLastKnownLocation(provider) }.getOrNull()
        }.maxByOrNull { it.time }
    }

    private fun AndroidLocation.toMeshLocation(nowEpochS: Long): Location {
        val latE7 = (latitude * 1e7).roundToInt()
        val lonE7 = (longitude * 1e7).roundToInt()
        val acc = if (hasAccuracy()) accuracy.roundToInt().coerceIn(1, 0xFFFF) else 0xFFFF
        val ageS = (nowEpochS - (time / 1000L)).coerceIn(0L, 0xFFFFL).toInt()
        return Location(lat = latE7, lon = lonE7, accuracyM = acc, fixAgeS = ageS)
    }

    companion object {
        val NO_FIX = Location(lat = null, lon = null, accuracyM = 0, fixAgeS = 0)
    }
}
