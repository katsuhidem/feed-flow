package com.prof18.feedflow.android.tailnet

import android.app.Application
import com.prof18.feedflow.core.model.SyncAccounts
import com.prof18.feedflow.feedsync.networkcore.NetworkSettings
import com.prof18.feedflow.shared.domain.SyncNetworkGate
import io.github.katsuhidem.tailnet.TailnetController
import io.github.katsuhidem.tailnet.TailnetManager
import io.github.katsuhidem.tailnet.TailnetSettings
import io.github.katsuhidem.tailnet.applyTailnet
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.Job
import okhttp3.OkHttpClient
import org.koin.core.context.GlobalContext
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * fork (Phase 1): wires the embedded Tailscale node into the app. Built
 * before Koin starts so the FreshRSS client can get its engine.
 */
class TailnetSetup(app: Application) {

    /**
     * The URL typed on the FreshRSS login screen. The account URL is saved only
     * after a successful login, so the first login uses this one.
     */
    @Volatile
    var pendingLoginUrl: String? = null

    val manager = TailnetManager(
        context = app,
        settings = TailnetSettings(app),
        targetUrl = ::freshRssUrl,
    )

    /** OkHttp client with proxy selection, waiting, leases and the proxy token. */
    val okHttpClient: OkHttpClient = OkHttpClient.Builder().applyTailnet(manager).build()

    /** Engine for the GReader (FreshRSS) client. Shares [okHttpClient]'s setup. */
    val greaderEngine: HttpClientEngine = OkHttp.create { preconfigured = okHttpClient }

    val koinModule: Module = module {
        single { this@TailnetSetup }
        single { manager }
        single<TailnetController> { manager }
        single<SyncNetworkGate> { TailnetSyncGate(manager) }
    }

    fun install() = manager.install()

    /** The FreshRSS base URL when the sync account is FreshRSS; otherwise nothing goes through the tailnet. */
    private fun freshRssUrl(): String? = runCatching {
        val settings = GlobalContext.get().get<NetworkSettings>()
        if (settings.getSyncAccountType() == SyncAccounts.FRESH_RSS) settings.getSyncUrl() else null
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: pendingLoginUrl
}

/**
 * Brings the node up before a background sync and keeps it up until the
 * sync ends. In system mode, or without a FreshRSS account, it does nothing.
 */
private class TailnetSyncGate(private val manager: TailnetManager) : SyncNetworkGate {
    override suspend fun <T> withNetwork(block: suspend () -> T): T {
        if (!manager.isEmbedded() || !manager.hasTarget()) return block()
        val job = coroutineContext[Job]
        val lease = manager.acquire(SYNC_MAX_HOLD) { job?.cancel() }
        try {
            manager.ensureUp(ENSURE_TIMEOUT)
            return block()
        } finally {
            lease.release()
        }
    }

    private companion object {
        val SYNC_MAX_HOLD = 15.minutes
        val ENSURE_TIMEOUT = 60.seconds
    }
}
