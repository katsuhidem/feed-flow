package io.github.katsuhidem.tailnet

import co.touchlab.kermit.Logger
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.ConnectionPool
import okhttp3.Credentials
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.Route
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The one place that sets up an OkHttp client for the tailnet: proxy
 * selection, waiting for the node, leases and the proxy token. Both the
 * sync client and the image loader use it, so neither can miss a part.
 */
fun OkHttpClient.Builder.applyTailnet(
    manager: TailnetManager,
    ensureTimeout: Duration = 45.seconds,
): OkHttpClient.Builder {
    val pool = ConnectionPool()
    manager.registerPool(pool)
    return connectionPool(pool)
        .proxySelector(TailnetProxySelector(manager))
        .addInterceptor(TailnetGateInterceptor(manager, ensureTimeout))
        .proxyAuthenticator(TailnetProxyAuthenticator(manager))
}

/**
 * Sends only FreshRSS requests to the loopback proxy. When the node is down
 * it returns an unreachable proxy rather than a direct route, so a FreshRSS
 * request never goes out directly in embedded mode.
 */
class TailnetProxySelector(private val manager: TailnetManager) : ProxySelector() {
    private val log = Logger.withTag("TailnetRoute")

    override fun select(uri: URI): List<Proxy> {
        if (!manager.isEmbedded()) return listOf(Proxy.NO_PROXY)
        val scheme = uri.scheme?.lowercase() ?: return listOf(Proxy.NO_PROXY)
        val port = when {
            uri.port != -1 -> uri.port
            scheme == "https" -> HTTPS_PORT
            else -> HTTP_PORT
        }
        val host = uri.host ?: return listOf(Proxy.NO_PROXY)
        if (!manager.matchesTarget(scheme, host, port)) {
            // Never log tailnet names; public hosts are fine.
            log.d { "route=direct host=${if (host.endsWith(".ts.net")) "<tailnet>" else host}" }
            return listOf(Proxy.NO_PROXY)
        }
        val proxyPort = manager.proxyPort().takeIf { it > 0 } ?: UNREACHABLE_PORT
        log.d { "route=proxy target=freshrss port=$proxyPort" }
        return listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress(LOOPBACK, proxyPort)))
    }

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val HTTPS_PORT = 443
        const val HTTP_PORT = 80
        const val UNREACHABLE_PORT = 9 // discard; nothing listens on loopback
    }
}

/**
 * For FreshRSS requests in embedded mode: waits until the node is up, takes a
 * lease, and gives it back when the response body is fully read or closed,
 * or at once when the call fails. Not released in a finally block: OkHttp
 * returns after the headers while the body is still streaming.
 */
class TailnetGateInterceptor(
    private val manager: TailnetManager,
    private val ensureTimeout: Duration,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!manager.isEmbedded() || !manager.matchesTarget(request.url)) return chain.proceed(request)

        try {
            runBlocking { manager.ensureUp(ensureTimeout) }
        } catch (e: TailnetUnavailableException) {
            throw IOException("Tailscale is not available: ${e.message}", e)
        }

        val responseRef = AtomicReference<Response?>(null)
        val lease = manager.acquire(manager.leaseMaxHold()) {
            chain.call().cancel()
            responseRef.get()?.close()
        }
        val response = try {
            chain.proceed(request)
        } catch (e: Throwable) {
            lease.release()
            throw e
        }
        val slowMs = if (manager.debugSlowBody) DEBUG_SLOW_BODY_MS else 0L
        val wrapped = response.newBuilder().body(LeaseResponseBody(response.body, lease, slowMs)).build()
        responseRef.set(wrapped)
        return wrapped
    }
}

private const val DEBUG_SLOW_BODY_MS = 2_000L

private class LeaseResponseBody(
    private val delegate: ResponseBody,
    private val lease: TailnetLease,
    private val slowMs: Long,
) : ResponseBody() {
    private val source: BufferedSource by lazy {
        object : ForwardingSource(delegate.source()) {
            private var delayed = false

            override fun read(sink: Buffer, byteCount: Long): Long {
                if (slowMs > 0 && !delayed) {
                    delayed = true
                    try {
                        Thread.sleep(slowMs) // debug only: a slow server body
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        lease.release()
                        throw java.io.InterruptedIOException("cancelled")
                    }
                }
                val n = try {
                    super.read(sink, byteCount)
                } catch (e: IOException) {
                    lease.release()
                    throw e
                }
                if (n == -1L) lease.release()
                return n
            }

            override fun close() {
                try {
                    super.close()
                } finally {
                    lease.release()
                }
            }
        }.buffer()
    }

    override fun contentType(): MediaType? = delegate.contentType()
    override fun contentLength(): Long = delegate.contentLength()
    override fun source(): BufferedSource = source
    override fun close() = source.close()
}

/**
 * Adds the proxy token to the CONNECT request. OkHttp calls this before the
 * CONNECT with a preemptive challenge, and again on a real 407 (for example
 * after a restart changed the token). A second 407 with the same token stops.
 */
class TailnetProxyAuthenticator(private val manager: TailnetManager) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        if (route?.proxy?.type() != Proxy.Type.HTTP) return null
        val token = manager.proxyToken()
        if (token.isEmpty()) return null
        val credentials = Credentials.basic(USER, token)
        if (response.request.header(HEADER) == credentials) return null
        return response.request.newBuilder().header(HEADER, credentials).build()
    }

    private companion object {
        const val USER = "tailfeed"
        const val HEADER = "Proxy-Authorization"
    }
}
