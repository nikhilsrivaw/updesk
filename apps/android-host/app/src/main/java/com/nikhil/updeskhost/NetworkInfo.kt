package com.nikhil.updeskhost

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.NetworkInterface

/**
 * Phone network state for the forensic Network panel — works WITHOUT root:
 *  - VPN detection (is the device tunnelling its traffic, via which app)
 *  - connection type (WiFi / mobile / ethernet), local IP, WiFi SSID
 *
 * System-wide connection lists (netstat) are NOT available without root on
 * Android 10+, so those need the root path.
 */
object NetworkInfo {

    fun vpn(ctx: Context): JSONObject {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        var active = false
        val processes = JSONArray()
        for (net in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(net) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) active = true
        }
        // Which installed apps hold VPN permission (candidates for the tunnel).
        // (We can't always name the *active* one without more privilege, so we
        // report that a VPN is up; app attribution needs root.)
        return JSONObject().put("active", active).put("processes", processes)
    }

    fun info(ctx: Context): JSONObject {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val type = when {
            caps == null -> "offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Unknown"
        }
        var ip = ""
        runCatching {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr.hostAddress?.contains('.') == true) {
                        ip = addr.hostAddress ?: ""
                    }
                }
            }
        }
        // SSID needs location permission on Android 8+; falls back to unknown.
        var ssid = ""
        runCatching {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val s = wm.connectionInfo?.ssid ?: ""
            if (s.isNotEmpty() && !s.contains("unknown", true)) ssid = s.trim('"')
        }
        return JSONObject().put("type", type).put("ip", ip).put("ssid", ssid)
    }
}
