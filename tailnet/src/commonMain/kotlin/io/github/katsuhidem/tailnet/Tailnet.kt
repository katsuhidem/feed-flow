package io.github.katsuhidem.tailnet

import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration

/** How FreshRSS is reached. SYSTEM is the official Tailscale app (direct connection). */
enum class TailnetMode { SYSTEM, EMBEDDED }

sealed interface TailnetState {
    data object Stopped : TailnetState
    data object Starting : TailnetState

    /** Interactive login is required. [authUrl] is null until the control server returns it. */
    data class NeedsLogin(val authUrl: String?) : TailnetState
    data class Connected(val ip: String) : TailnetState
    data class Error(val message: String) : TailnetState
}

class TailnetUnavailableException(message: String) : Exception(message)

/** A mark that the embedded node is in use. Released exactly once. */
interface TailnetLease {
    fun release()
}

/** Platform-neutral control of the embedded Tailscale node. */
interface TailnetController {
    val state: StateFlow<TailnetState>
    val mode: StateFlow<TailnetMode>

    fun setMode(mode: TailnetMode)

    /** Starts the node if needed and waits until it is connected. Returns at once in SYSTEM mode. */
    suspend fun ensureUp(timeout: Duration)

    /**
     * Marks the node as in use. While any lease is held the node is not stopped.
     * After [maxHold], [onExpire] runs (cancel the work) and then the lease is released.
     */
    fun acquire(maxHold: Duration, onExpire: () -> Unit = {}): TailnetLease

    /** Starts the node so that a login URL can be shown. */
    fun startLogin()

    fun stop()
}
