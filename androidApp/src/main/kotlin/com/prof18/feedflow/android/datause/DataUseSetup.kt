package com.prof18.feedflow.android.datause

import android.app.Application
import co.touchlab.kermit.Logger
import coil3.intercept.Interceptor
import coil3.request.CachePolicy
import com.prof18.feedflow.android.BuildConfig
import com.prof18.feedflow.shared.domain.contentprefetch.ContentPrefetchGate
import com.prof18.feedflow.shared.domain.contentprefetch.ContentPrefetchRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.mp.KoinPlatform
import java.util.Collections

/**
 * fork (Phase 4a): the parts that limit data use on mobile connections.
 *
 * - "Prefetch article text on Wi-Fi only" ([ContentPrefetchGate]).
 * - "No images or video on mobile connections": the list shows only images
 *   that are already cached ([imageInterceptor]); reader mode blocks network
 *   loads until the user asks for the images of that article (ReaderMedia.kt).
 */
class DataUseSetup(app: Application) {
    private val log = Logger.withTag("DataUse")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val prefs = DataUsePrefs(app)
    val network = NetworkKindMonitor(app)

    /** The kind that the settings act on: the real one, unless a debug build is told to pretend. */
    private fun effective(kind: NetworkKind, treatAsMobile: Boolean): NetworkKind =
        if (BuildConfig.DEBUG && treatAsMobile && kind != NetworkKind.NONE) NetworkKind.MOBILE else kind

    private fun currentKind(): NetworkKind = effective(network.current(), prefs.debugTreatAsMobile.value)

    /** True while images and video must not be fetched from the network. Without a connection nothing is blocked. */
    val mediaBlocked: StateFlow<Boolean> =
        combine(prefs.blockMediaOnMobile, network.kind, prefs.debugTreatAsMobile) { on, kind, pretend ->
            on && effective(kind, pretend) == NetworkKind.MOBILE
        }.stateIn(scope, SharingStarted.Eagerly, false)

    private val prefetchGate = object : ContentPrefetchGate {
        override fun wifiOnly(): Boolean = prefs.prefetchWifiOnly.value

        override fun allowsPrefetch(): Boolean {
            if (!wifiOnly()) return true
            val kind = currentKind()
            if (kind != NetworkKind.WIFI) log.i { "prefetch held: wifi_only=true kind=$kind" }
            return kind == NetworkKind.WIFI
        }
    }

    /** Images the user opened by hand (the full-screen viewer); these load on any connection. */
    private val allowedImages: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())

    fun allowImage(url: String) {
        if (allowedImages.size >= MAX_ALLOWED_IMAGES) allowedImages.clear()
        allowedImages += url
    }

    /**
     * For the Coil image loader (list thumbnails, feed icons, notification images): while media
     * is blocked a request may be answered from the caches but not from the network.
     */
    val imageInterceptor = Interceptor { chain ->
        val request = chain.request
        if (mediaBlocked.value && request.data.toString() !in allowedImages) {
            chain.withRequest(request.newBuilder().networkCachePolicy(CachePolicy.DISABLED).build()).proceed()
        } else {
            chain.proceed()
        }
    }

    val koinModule: Module = module {
        single { this@DataUseSetup }
        single<ContentPrefetchGate> { prefetchGate }
    }

    init {
        network.start()
        mediaBlocked.drop(1).onEach { log.i { "media blocked=$it" } }.launchIn(scope)
    }

    /**
     * Stores the setting and schedules the queued prefetching again, so the waiting work
     * gets the network condition that matches the new value. Call after Koin has started.
     */
    fun setPrefetchWifiOnly(value: Boolean) {
        prefs.setPrefetchWifiOnly(value)
        log.i { "prefetch wifi_only=$value" }
        KoinPlatform.getKoin().get<ContentPrefetchRepository>().startBackgroundFetching()
    }

    private companion object {
        const val MAX_ALLOWED_IMAGES = 200
    }
}
