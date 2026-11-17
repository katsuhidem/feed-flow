package com.prof18.feedflow.android.datause

import android.app.Application
import co.touchlab.kermit.Logger
import com.prof18.feedflow.shared.domain.contentprefetch.ContentPrefetchGate
import com.prof18.feedflow.shared.domain.contentprefetch.ContentPrefetchRepository
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.mp.KoinPlatform

/**
 * fork (Phase 4a): the parts that limit data use on mobile connections.
 * So far: the network monitor and "prefetch article text on Wi-Fi only".
 */
class DataUseSetup(app: Application) {
    private val log = Logger.withTag("DataUse")
    val prefs = DataUsePrefs(app)
    val network = NetworkKindMonitor(app)

    private val prefetchGate = object : ContentPrefetchGate {
        override fun wifiOnly(): Boolean = prefs.prefetchWifiOnly.value

        override fun allowsPrefetch(): Boolean {
            if (!wifiOnly()) return true
            val kind = network.current()
            if (kind != NetworkKind.WIFI) log.i { "prefetch held: wifi_only=true kind=$kind" }
            return kind == NetworkKind.WIFI
        }
    }

    val koinModule: Module = module {
        single { this@DataUseSetup }
        single<ContentPrefetchGate> { prefetchGate }
    }

    init {
        network.start()
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
}
