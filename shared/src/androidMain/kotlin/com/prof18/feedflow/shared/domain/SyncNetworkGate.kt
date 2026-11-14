package com.prof18.feedflow.shared.domain

/**
 * fork: lets the app prepare the network before a background sync and keep
 * it up until the sync ends (the embedded Tailscale node, Phase 1).
 */
interface SyncNetworkGate {
    suspend fun <T> withNetwork(block: suspend () -> T): T
}
