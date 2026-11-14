package io.github.katsuhidem.tailnet

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import io.github.katsuhidem.tsbridge.Tsbridge
import org.json.JSONArray
import org.json.JSONObject
import java.net.NetworkInterface

/**
 * Passes interfaces and DNS servers to tsbridge. Android denies the Go
 * runtime net.Interfaces(), and tsnet needs to hear about network changes.
 */
internal class NetworkStateReporter(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    fun register() {
        cm.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = report()
                override fun onLost(network: Network) = report()
                override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = report()
                override fun onCapabilitiesChanged(network: Network, nc: NetworkCapabilities) = report()
            },
        )
    }

    fun report() {
        val arr = JSONArray()
        val dns = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        @Suppress("DEPRECATION")
        for (n in cm.allNetworks) {
            val lp = cm.getLinkProperties(n) ?: continue
            val name = lp.interfaceName ?: continue
            if (!seen.add(name)) continue
            lp.dnsServers.mapNotNullTo(dns) { it.hostAddress }
            val addrs = lp.linkAddresses.map { "${it.address.hostAddress?.substringBefore('%')}/${it.prefixLength}" }
            arr.put(
                JSONObject()
                    .put("name", name)
                    .put("index", runCatching { NetworkInterface.getByName(name)?.index }.getOrNull() ?: 0)
                    .put("mtu", if (lp.mtu > 0) lp.mtu else DEFAULT_MTU)
                    .put("up", true)
                    .put("addrs", JSONArray(addrs)),
            )
        }
        Tsbridge.setNetworkState(arr.toString(), dns.distinct().joinToString(","))
    }

    private companion object {
        const val DEFAULT_MTU = 1500
    }
}
