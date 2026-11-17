package com.prof18.feedflow.shared.domain.contentprefetch

/**
 * fork (Phase 4a): lets the app hold article prefetching back, for the
 * "prefetch on Wi-Fi only" setting. Without a gate prefetching behaves as
 * upstream does.
 */
interface ContentPrefetchGate {
    /** True when prefetching should wait for a connection that is not metered. */
    fun wifiOnly(): Boolean

    /**
     * Asked right before each article is fetched. On false nothing is
     * fetched and the article stays queued and unfetched, so it is picked up
     * again later.
     */
    fun allowsPrefetch(): Boolean
}
