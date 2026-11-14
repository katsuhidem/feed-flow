package io.github.katsuhidem.tailnet

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import co.touchlab.kermit.Logger
import io.github.katsuhidem.tsbridge.Callbacks
import io.github.katsuhidem.tsbridge.Tsbridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.ConnectionPool
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Starts and stops the embedded tsnet node (tsbridge) and decides which
 * requests go through its loopback proxy.
 *
 * Rules (docs/PLAN.md "接続のライフサイクル"):
 * - start and stop run on one serial queue; concurrent ensureUp calls share one start
 * - every stop discards the tsnet instance; the state directory keeps the login
 * - the node is not stopped while a lease is held; the stop timer starts when
 *   leases reach zero and the app is in the background
 * - proxy port and token change on restart; callers read them per request
 * - idle pooled connections are evicted on stop and restart
 * - no automatic fallback to a direct connection
 *
 * @param targetUrl the FreshRSS base URL; only requests to its scheme, host and port use the proxy
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TailnetManager(
    context: Context,
    private val settings: TailnetSettings,
    private val targetUrl: () -> String?,
) : TailnetController {

    private val appContext = context.applicationContext
    private val log = Logger.withTag("Tailnet")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val serial = Dispatchers.IO.limitedParallelism(1)
    private val stateDir = File(appContext.filesDir, "tailnet")
    private val reporter = NetworkStateReporter(appContext)
    private val pools = CopyOnWriteArrayList<ConnectionPool>()

    private val _state = MutableStateFlow<TailnetState>(TailnetState.Stopped)
    override val state: StateFlow<TailnetState> = _state.asStateFlow()

    private val _mode = MutableStateFlow(settings.mode)
    override val mode: StateFlow<TailnetMode> = _mode.asStateFlow()

    @Volatile private var running = false
    @Volatile private var foreground = false
    @Volatile private var authUrl: String? = null
    private val leases = AtomicInteger(0)
    private var stopJob: Job? = null

    private val callbacks = object : Callbacks {
        override fun onAuthURL(url: String) {
            authUrl = url
            if (_state.value is TailnetState.NeedsLogin) _state.value = TailnetState.NeedsLogin(url)
        }

        override fun onState(s: String) {
            _state.value = when (s) {
                "running" -> TailnetState.Connected(Tsbridge.tailnetIP())
                "needs-login" -> TailnetState.NeedsLogin(authUrl)
                "starting" -> TailnetState.Starting
                "stopped" -> TailnetState.Stopped
                "needs-machine-auth" -> TailnetState.Error("device approval required")
                else -> TailnetState.Error("tsnet state: $s")
            }
            if (s == "running") applyTarget()
        }

        override fun log(line: String) {
            // The login URL is single-use but still a credential; keep it out of logcat.
            if (line.contains("login.tailscale.com") || line.startsWith("To start this tsnet")) return
            log.d { line }
        }
    }

    /** Call once from Application.onCreate. */
    fun install() {
        reporter.register()
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    log.i { "app foreground" }
                    foreground = true
                    cancelStopTimer()
                    if (_mode.value == TailnetMode.EMBEDDED) scope.launch { start() }
                }

                override fun onStop(owner: LifecycleOwner) {
                    log.i { "app background (leases=${leases.get()})" }
                    foreground = false
                    maybeScheduleStop()
                }
            },
        )
    }

    // ---- TailnetController ----

    override fun setMode(mode: TailnetMode) {
        settings.mode = mode
        _mode.value = mode
        evictPools()
        if (mode == TailnetMode.SYSTEM) stop()
    }

    override suspend fun ensureUp(timeout: Duration) {
        if (_mode.value != TailnetMode.EMBEDDED) return
        start()
        val result = withTimeoutOrNull(timeout) {
            state.first {
                it is TailnetState.Connected ||
                    it is TailnetState.Error ||
                    (it is TailnetState.NeedsLogin && it.authUrl != null)
            }
        }
        when (result) {
            is TailnetState.Connected -> return
            is TailnetState.NeedsLogin -> throw TailnetUnavailableException("Tailscale login required")
            is TailnetState.Error -> throw TailnetUnavailableException(result.message)
            else -> throw TailnetUnavailableException("Tailscale did not connect within $timeout")
        }
    }

    override fun acquire(maxHold: Duration, onExpire: () -> Unit): TailnetLease {
        val n = leases.incrementAndGet()
        log.d { "lease acquired (leases=$n)" }
        cancelStopTimer()
        val released = AtomicBoolean(false)
        lateinit var lease: TailnetLease
        val expiry = scope.launch {
            delay(maxHold)
            log.w { "lease held longer than $maxHold; cancelling the call" }
            // Cancel the work first, then give the lease back (PLAN: never release while the call runs).
            runCatching(onExpire)
            lease.release()
        }
        lease = object : TailnetLease {
            override fun release() {
                if (!released.compareAndSet(false, true)) return
                expiry.cancel()
                val n = leases.decrementAndGet()
                log.d { "lease released (leases=$n)" }
                if (n == 0) maybeScheduleStop()
            }
        }
        return lease
    }

    override fun startLogin() {
        scope.launch { start() }
    }

    override fun stop() {
        cancelStopTimer()
        scope.launch {
            withContext(serial) {
                if (!running) return@withContext
                log.i { "stopping tsnet" }
                Tsbridge.stop()
                running = false
                authUrl = null
                evictPools()
                _state.value = TailnetState.Stopped
            }
        }
    }

    // ---- used by the OkHttp setup ----

    fun isEmbedded(): Boolean = _mode.value == TailnetMode.EMBEDDED

    /** True when a FreshRSS URL is configured. */
    fun hasTarget(): Boolean = target() != null

    /** True when the URL has the same scheme, host and port as the FreshRSS base URL. */
    fun matchesTarget(scheme: String, host: String, port: Int): Boolean {
        val t = target() ?: return false
        return t.scheme == scheme.lowercase() && t.host == normHost(host) && t.port == port
    }

    fun matchesTarget(url: HttpUrl): Boolean = matchesTarget(url.scheme, url.host, url.port)

    /** Current proxy port, 0 when stopped. Read per request: it changes on restart. */
    fun proxyPort(): Int = if (running) Tsbridge.proxyPort().toInt() else 0

    fun proxyToken(): String = if (running) Tsbridge.proxyToken() else ""

    fun registerPool(pool: ConnectionPool) {
        pools += pool
    }

    /** Debug builds only: short stop delay (10 s) for the Phase 1 checks. */
    var debugShortStop: Boolean
        get() = settings.debugShortStop
        set(value) {
            settings.debugShortStop = value
        }

    /** Debug builds only: delay each FreshRSS response body by 2 s. */
    var debugSlowBody: Boolean
        get() = settings.debugSlowBody
        set(value) {
            settings.debugSlowBody = value
        }

    /** Debug builds only: short lease limit (0.5 s) for the Phase 1 checks. */
    var debugShortLease: Boolean
        get() = settings.debugShortLease
        set(value) {
            settings.debugShortLease = value
        }

    /** Upper limit for holding a lease per request. Shortened only by the debug test option. */
    fun leaseMaxHold(): Duration = if (settings.debugShortLease) DEBUG_MAX_HOLD else DEFAULT_MAX_HOLD

    private fun stopDelay(): Duration = if (settings.debugShortStop) DEBUG_STOP_DELAY else DEFAULT_STOP_DELAY

    // ---- internals ----

    private data class Target(val scheme: String, val host: String, val port: Int)

    private fun target(): Target? {
        val raw = targetUrl()?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val url = raw.toHttpUrlOrNull() ?: return null
        return Target(url.scheme, normHost(url.host), url.port)
    }

    private fun normHost(h: String) = h.lowercase().trimEnd('.')

    private fun applyTarget() {
        val t = target() ?: return
        Tsbridge.setAllowedTarget("${t.host}:${t.port}")
    }

    private suspend fun start() = withContext(serial) {
        if (running || _mode.value != TailnetMode.EMBEDDED) return@withContext
        log.i { "starting tsnet" }
        _state.value = TailnetState.Starting
        stateDir.mkdirs()
        reporter.report()
        try {
            Tsbridge.start(stateDir.absolutePath, settings.nodeName, "", callbacks)
            running = true
            applyTarget()
            evictPools()
        } catch (e: Exception) {
            log.e(e) { "tsnet start failed" }
            _state.value = TailnetState.Error(e.message ?: "start failed")
        }
    }

    private fun maybeScheduleStop() {
        if (foreground || leases.get() > 0 || !running) return
        synchronized(this) {
            stopJob?.cancel()
            log.i { "no leases in background; stopping in ${stopDelay()}" }
            stopJob = scope.launch {
                delay(stopDelay())
                if (!foreground && leases.get() == 0) stop()
            }
        }
    }

    private fun cancelStopTimer() {
        synchronized(this) {
            stopJob?.cancel()
            stopJob = null
        }
    }

    /** Closing pooled TLS sockets does network I/O, so never on the main thread. */
    private fun evictPools() {
        scope.launch(Dispatchers.IO) { pools.forEach { it.evictAll() } }
    }

    private companion object {
        val DEFAULT_STOP_DELAY = 3.minutes
        val DEFAULT_MAX_HOLD = 3.minutes
        val DEBUG_STOP_DELAY = kotlin.time.Duration.parse("10s")
        val DEBUG_MAX_HOLD = kotlin.time.Duration.parse("500ms")
    }
}
