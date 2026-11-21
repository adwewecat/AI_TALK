package com.adwewecat.nana.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.adwewecat.nana.core.sync.WifiNetworkChecker

/**
 * Android [WifiNetworkChecker] requiring an active unmetered Wi-Fi network with internet capability.
 */
class AndroidWifiNetworkChecker(
    private val context: Context,
) : WifiNetworkChecker {

    override fun isUnmeteredWifiConnected(): Boolean {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(activeNetwork)
        return isUnmeteredWifi(capabilities)
    }

    companion object {
        /** Evaluates whether [capabilities] represent an unmetered Wi-Fi link with internet access. */
        fun isUnmeteredWifi(capabilities: NetworkCapabilities?): Boolean {
            if (capabilities == null) return false
            return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }
    }
}
