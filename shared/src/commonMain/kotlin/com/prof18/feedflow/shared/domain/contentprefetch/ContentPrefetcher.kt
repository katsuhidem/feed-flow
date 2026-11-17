package com.prof18.feedflow.shared.domain.contentprefetch

import co.touchlab.kermit.Logger
import com.prof18.feedflow.core.model.PrefetchQueueItem
import com.prof18.feedflow.database.DatabaseHelper
import com.prof18.feedflow.shared.domain.feeditem.ReaderContentFetcher

internal class ContentPrefetcher(
    private val logger: Logger,
    private val databaseHelper: DatabaseHelper,
    private val readerContentFetcher: ReaderContentFetcher,
    // fork (Phase 4a)
    private val gate: ContentPrefetchGate? = null,
) {
    suspend fun prefetchQueuedBatch() {
        val queuedItems = databaseHelper.getNextPrefetchBatch()
        logger.d { "Processing ${queuedItems.size} queued items" }
        for (item in queuedItems) {
            if (!prefetch(item)) break
        }
    }

    /**
     * @return false when the app holds prefetching back (fork, Phase 4a): nothing was fetched and
     *   the item is still queued and unfetched
     */
    suspend fun prefetch(item: PrefetchQueueItem): Boolean {
        if (gate?.allowsPrefetch() == false) {
            logger.i { "Prefetch held: not allowed on this connection" }
            return false
        }
        logger.d { "Prefetching: ${item.feedItemId}" }
        val content = readerContentFetcher.fetch(item.feedItemId, item.url, save = true)
        if (content != null) {
            logger.d { "Prefetched successfully: ${item.feedItemId}" }
        } else {
            logger.d { "Parse failed for: ${item.feedItemId}, skipping permanently" }
        }
        // Failed items are marked as fetched too: prefetch is best-effort.
        databaseHelper.updateContentFetchedStatus(item.feedItemId, fetched = true)
        databaseHelper.removePrefetchQueueItem(item.feedItemId)
        return true
    }
}
