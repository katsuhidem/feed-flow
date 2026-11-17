package com.prof18.feedflow.android.datause

import android.app.Application

/**
 * fork (Phase 4a): the parts that limit data use on mobile connections.
 * For now only the network monitor; the settings and what they control follow.
 */
class DataUseSetup(app: Application) {
    val network = NetworkKindMonitor(app)

    init {
        network.start()
    }
}
