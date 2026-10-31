package org.mihomet.app.core.vpn

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    android.util.Log.d("MihometVpn", "Network available: $network")
                    currentActiveNetwork = network
                    updateUnderlyingNetwork(network)
                }

                override fun onLost(network: Network) {
                    android.util.Log.d("MihometVpn", "Network lost: $network")
                    if (currentActiveNetwork == network) {
                        currentActiveNetwork = null
                        val fallback = cm.activeNetwork
                        updateUnderlyingNetwork(fallback)
                    }
                }

                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        !networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    ) {
                        currentActiveNetwork = network
                        updateUnderlyingNetwork(network)
                    }
                }
            }
            networkCallback = callback
            cm.registerNetworkCallback(request, callback)
            cm.activeNetwork?.let { updateUnderlyingNetwork(it) }
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
