package org.mihomet.app.core.vpn

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Tracks the underlying (non-VPN) network and keeps the TUN's underlying
 * networks pinned to it, so WiFi <-> Mobile roaming switches the tunnel's
 * egress dynamically; also flushes the core's connections on every switch
 * so Mihomo drops stale sockets and reconnects on the new interface.
 *
 * Extracted verbatim from [MihometVpnService] — pure move, no behavior change.
 */
class UnderlyingNetworkTracker(
    private val service: android.net.VpnService,
    private val scope: CoroutineScope,
    private val apiClientProvider: org.mihomet.app.data.api.MihomoApiClientProvider
) {

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var currentActiveNetwork: Network? = null

    fun register() {
        try {
            val cm = service.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            connectivityManager = cm

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (currentActiveNetwork != network) {
                        android.util.Log.d("MihometVpn", "Default network changed to: $network")
                        currentActiveNetwork = network
                        updateUnderlyingNetwork(network)
                    }
                }

                override fun onLost(network: Network) {
                    android.util.Log.d("MihometVpn", "Default network lost: $network")
                    if (currentActiveNetwork == network) {
                        currentActiveNetwork = null
                        val fallback = cm.activeNetwork
                        if (fallback != null) {
                            currentActiveNetwork = fallback
                            updateUnderlyingNetwork(fallback)
                        } else {
                            updateUnderlyingNetwork(null)
                        }
                    }
                }

                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    if (currentActiveNetwork != network &&
                        networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        !networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    ) {
                        currentActiveNetwork = network
                        updateUnderlyingNetwork(network)
                    }
                }
            }
            networkCallback = callback
            cm.registerDefaultNetworkCallback(callback)
            currentActiveNetwork = cm.activeNetwork
            updateUnderlyingNetwork(currentActiveNetwork)
        } catch (e: Exception) {
            android.util.Log.w("MihometVpn", "Failed to register network callback: ${e.message}")
        }
    }

    fun unregister() {
        runCatching {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        }
        networkCallback = null
    }

    private fun updateUnderlyingNetwork(network: Network?) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                val networks = if (network != null) arrayOf(network) else null
                service.setUnderlyingNetworks(networks)
                android.util.Log.d("MihometVpn", "setUnderlyingNetworks updated: $network")
            }
            // Trigger connection flush so Mihomo drops stale sockets and reconnects on new interface
            scope.launch {
                runCatching {
                    apiClientProvider.current().closeAllConnections()
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("MihometVpn", "updateUnderlyingNetwork error: ${e.message}")
        }
    }
}
