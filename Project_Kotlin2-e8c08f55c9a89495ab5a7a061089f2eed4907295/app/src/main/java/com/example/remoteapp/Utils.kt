package com.example.remoteapp

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.util.Collections

object Utils {

    private const val TAG = "Utils"

    /**
     * Gets the local IP address of the device.
     * Prefers IPv4 site-local addresses.
     * Returns "127.0.0.1" if no suitable network interface is found (e.g., in an emulator without network).
     */
    fun getLocalIpAddress(context: Context): String {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // For Android M (API 23) and above
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val activeNetwork = connectivityManager.activeNetwork ?: run {
                Log.w(TAG, "No active network found.")
                return "127.0.0.1"
            }
            val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: run {
                Log.w(TAG, "No network capabilities for active network.")
                return "127.0.0.1"
            }

            if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {

                try {
                    val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
                    for (intf in interfaces) {
                        // Exclude loopback and non-up interfaces
                        if (intf.isLoopback || !intf.isUp) continue

                        val addrs = Collections.list(intf.inetAddresses)
                        for (addr in addrs) {
                            if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                                Log.d(TAG, "Found IP via ConnectivityManager/NetworkInterface: ${addr.hostAddress}")
                                return addr.hostAddress
                            }
                        }
                    }
                } catch (e: SocketException) {
                    Log.e(TAG, "SocketException in getLocalIpAddress (ConnectivityManager path)", e)
                }
            } else {
                Log.w(TAG, "Active network not WIFI, CELLULAR, or ETHERNET.")
            }
        }

        // Fallback for older Android versions or if ConnectivityManager path fails
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress) {
                        // Prioritize IPv4 site-local addresses
                        if (addr is Inet4Address && addr.isSiteLocalAddress) {
                            Log.d(TAG, "Found IP via NetworkInterface (fallback): ${addr.hostAddress}")
                            return addr.hostAddress
                        }
                    }
                }
            }
        } catch (ex: SocketException) {
            Log.e(TAG, "SocketException in getLocalIpAddress (fallback path)", ex)
        }

        // If no suitable IP is found, especially common in AVD emulators without proper network setup
        val isEmulator = Build.FINGERPRINT.startsWith("generic") ||
                Build.FINGERPRINT.startsWith("unknown") ||
                Build.MODEL.contains("google_sdk") ||
                Build.MODEL.contains("Emulator") ||
                Build.MODEL.contains("Android SDK built for x86") ||
                Build.MANUFACTURER.contains("Genymotion") ||
                Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic") ||
                "google_sdk" == Build.PRODUCT

        if (isEmulator) {
            Log.w(TAG, "Running on emulator, returning 127.0.0.1 as local IP fallback.")
            return "127.0.0.1"
        }

        Log.w(TAG, "No suitable local IP address found, returning 127.0.0.1 as default.")
        return "127.0.0.1"
    }
}