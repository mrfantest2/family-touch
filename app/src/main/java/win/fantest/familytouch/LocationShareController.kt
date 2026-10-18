package win.fantest.familytouch

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

internal object LocationPolicy {
    fun accepts(lat: Double, lon: Double, accuracy: Float, fixMs: Long, nowMs: Long): Boolean =
        lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0 &&
            accuracy.isFinite() && accuracy >= 0 && nowMs >= fixMs && nowMs - fixMs <= 30_000
}

/** Requests a fresh fix on all supported versions; cancellation removes the listener. */
internal object LocationShareController {
    @SuppressLint("MissingPermission")
    suspend fun request(context: Context): Location = withTimeout(30_000) {
        val manager = context.getSystemService(LocationManager::class.java)
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter { manager.isProviderEnabled(it) }
        require(providers.isNotEmpty()) { "Enable Location in Android settings" }
        suspendCancellableCoroutine { continuation ->
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    val ageMs = location.elapsedRealtimeNanos / 1_000_000
                    if (LocationPolicy.accepts(location.latitude, location.longitude, location.accuracy,
                            ageMs, SystemClock.elapsedRealtime()) && continuation.isActive) {
                        manager.removeUpdates(this)
                        continuation.resume(location)
                    }
                }
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
                @Deprecated("Required for Android 10")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            }
            continuation.invokeOnCancellation { manager.removeUpdates(listener) }
            var requested = false
            providers.forEach { provider ->
                try {
                    manager.requestLocationUpdates(provider, 1000L, 0f, listener, Looper.getMainLooper())
                    requested = true
                } catch (_: SecurityException) { /* Approximate permission may exclude GPS. */ }
            }
            if (!requested) continuation.cancel(SecurityException("Location permission unavailable"))
        }
    }
}
