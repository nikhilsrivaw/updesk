package com.nikhil.updeskfield

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import org.json.JSONObject

/**
 * Streams the device's live position using the plain Android [LocationManager]
 * (GPS + network providers). No Google Play Services dependency, so it works on
 * de-Googled / custody / AOSP handsets too.
 *
 * Each fix is delivered as a JSON object to [onFix]:
 *   { kind:"location", lat, lon, accuracy, altitude, speed, bearing,
 *     provider, time }
 * which the caller forwards over the WebRTC `location` data channel.
 */
class LocationStreamer(
    private val context: Context,
    private val onFix: (JSONObject) -> Unit,
) {
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val main = Handler(Looper.getMainLooper())
    private var listening = false

    private val listener = object : LocationListener {
        override fun onLocationChanged(loc: Location) = emit(loc)
        // Older API levels call these; safe no-ops.
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    /** Begin location updates. Caller must already hold ACCESS_FINE/COARSE_LOCATION. */
    @SuppressLint("MissingPermission")
    fun start() {
        if (listening) return
        listening = true
        val providers = mutableListOf<String>()
        if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) providers.add(LocationManager.GPS_PROVIDER)
        if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) providers.add(LocationManager.NETWORK_PROVIDER)
        if (providers.isEmpty()) providers.add(LocationManager.GPS_PROVIDER) // best effort
        try {
            for (p in providers) {
                lm.requestLocationUpdates(p, 1500L, 0f, listener, Looper.getMainLooper())
            }
            // Seed the controller immediately with the last known fix, if any.
            for (p in providers) {
                val last = lm.getLastKnownLocation(p)
                if (last != null) { emit(last); break }
            }
        } catch (t: Throwable) {
            // Permission revoked or provider unavailable — stay quiet, video/audio
            // keep flowing regardless.
        }
    }

    private fun emit(loc: Location) {
        val o = JSONObject()
            .put("kind", "location")
            .put("lat", loc.latitude)
            .put("lon", loc.longitude)
            .put("accuracy", if (loc.hasAccuracy()) loc.accuracy.toDouble() else JSONObject.NULL)
            .put("altitude", if (loc.hasAltitude()) loc.altitude else JSONObject.NULL)
            .put("speed", if (loc.hasSpeed()) loc.speed.toDouble() else JSONObject.NULL)
            .put("bearing", if (loc.hasBearing()) loc.bearing.toDouble() else JSONObject.NULL)
            .put("provider", loc.provider ?: "")
            .put("time", System.currentTimeMillis())
        main.post { onFix(o) }
    }

    fun stop() {
        if (!listening) return
        listening = false
        try { lm.removeUpdates(listener) } catch (_: Throwable) {}
    }
}
