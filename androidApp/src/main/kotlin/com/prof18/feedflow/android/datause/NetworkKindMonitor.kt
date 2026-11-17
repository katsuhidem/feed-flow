package com.prof18.feedflow.android.datause

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How the connection counts for data use. */
enum class NetworkKind {
    /** No default network. */
    NONE,

    /** Wi-Fi or Ethernet that Android does not consider metered. */
    WIFI,

    /** Everything else: mobile data, and Wi-Fi that is metered (a phone's hotspot, for example). */
    MOBILE,
}

/** What Android reports about the default network; only what the decision needs, nothing that identifies it. */
internal data class NetworkFacts(
    val transports: Set<String>,
    val notMetered: Boolean,
    val temporarilyNotMetered: Boolean,
    val validated: Boolean,
) {
    override fun toString(): String =
        "transports=${transports.sorted().joinToString("|").ifEmpty { "-" }} not_metered=$notMetered " +
            "temporarily_not_metered=$temporarilyNotMetered validated=$validated"
}

/**
 * The rule of docs/PLAN.md "Phase 4a": Wi-Fi means Wi-Fi or Ethernet that
 * is not metered. A connection with a mobile transport is mobile even when
 * the carrier marks it as not metered, and so is anything unknown.
 *
 * A VPN reports the transports of the network under it next to its own,
 * so "VPN" is not looked at here.
 */
internal fun classify(facts: NetworkFacts?): NetworkKind = when {
    facts == null -> NetworkKind.NONE
    "CELLULAR" in facts.transports -> NetworkKind.MOBILE
    ("WIFI" in facts.transports || "ETHERNET" in facts.transports) && facts.notMetered -> NetworkKind.WIFI
    else -> NetworkKind.MOBILE
}

/**
 * fork (Phase 4a): follows the default network and says whether it counts
 * as Wi-Fi or as mobile data. Every change is logged as
 * "default network: ... -> KIND", which is what the device checks read.
 */
class NetworkKindMonitor(context: Context) {
    private val log = Logger.withTag("DataUse")
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    private val _kind = MutableStateFlow(NetworkKind.NONE)
    val kind: StateFlow<NetworkKind> = _kind.asStateFlow()

    private var lastFacts: NetworkFacts? = null
    private var started = false

    fun start() {
        if (started) return
        started = true
        log.i { "default network: none yet -> ${NetworkKind.NONE}" }
        cm.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, nc: NetworkCapabilities) = update(facts(nc))
                override fun onLost(network: Network) = update(null)
            },
        )
    }

    /** The kind right now, asked from the system rather than from the last callback. */
    fun current(): NetworkKind = classify(cm.getNetworkCapabilities(cm.activeNetwork)?.let(::facts))

    @Synchronized
    private fun update(facts: NetworkFacts?) {
        if (facts == lastFacts && started && _kind.value == classify(facts)) return
        lastFacts = facts
        val kind = classify(facts)
        _kind.value = kind
        log.i { "default network: ${facts ?: "lost"} active_metered=${cm.isActiveNetworkMetered} -> $kind" }
    }

    private fun facts(nc: NetworkCapabilities): NetworkFacts = NetworkFacts(
        transports = TRANSPORTS.filterKeys { nc.hasTransport(it) }.values.toSet(),
        notMetered = nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
        temporarilyNotMetered = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED),
        validated = nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
    )

    private companion object {
        val TRANSPORTS = mapOf(
            NetworkCapabilities.TRANSPORT_CELLULAR to "CELLULAR",
            NetworkCapabilities.TRANSPORT_WIFI to "WIFI",
            NetworkCapabilities.TRANSPORT_ETHERNET to "ETHERNET",
            NetworkCapabilities.TRANSPORT_BLUETOOTH to "BLUETOOTH",
            NetworkCapabilities.TRANSPORT_VPN to "VPN",
        )
    }
}
