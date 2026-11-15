package com.prof18.feedflow.android.translation

import com.prof18.feedflow.core.model.FeedItem
import com.prof18.feedflow.shared.domain.feed.TimelineTitleTranslations
import io.github.katsuhidem.translation.TitleItem
import io.github.katsuhidem.translation.TitleTranslator
import io.github.katsuhidem.translation.TranslationCache
import io.github.katsuhidem.translation.TranslationQueue
import io.github.katsuhidem.translation.batched
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * fork (Phase 2): connects the timeline to [TitleTranslator].
 *
 * - Titles put in the list are looked up in the cache; the ones without a
 *   translation are queued with the "visible" priority.
 * - Stored translations reach the list in groups every 300 ms, so a run of
 *   translations redraws the list a few times a second at most.
 */
class TimelineTitleTranslationsImpl(
    private val translator: TitleTranslator,
    cache: TranslationCache,
    private val titles: FeedTitleRegistry,
    private val scope: CoroutineScope,
    private val isEnabled: () -> Boolean,
    private val onItemsSeen: () -> Unit,
) : TimelineTitleTranslations {
    private val tracked = LinkedHashMap<String, TitleItem>()

    @Volatile
    private var listener: ((Set<String>, Map<String, String>) -> Unit)? = null

    init {
        scope.launch {
            cache.changes.batched(BATCH_MILLIS).collect { ids -> push(ids) }
        }
    }

    override suspend fun lookup(items: List<FeedItem>): Map<String, String> {
        val titleItems = items.map { it.toTitleItem() }
        track(titleItems)
        return lookupIfEnabled(titleItems)
    }

    /** With translation switched off, nothing is shown translated, even titles already in the cache. */
    private suspend fun lookupIfEnabled(items: List<TitleItem>): Map<String, String> =
        if (isEnabled()) translator.lookup(items) else emptyMap()

    override fun onItemsLoaded(items: List<FeedItem>) {
        val titleItems = items.map { it.toTitleItem() }
        scope.launch {
            translator.request(titleItems, owner = OWNER, priority = TranslationQueue.Priority.VISIBLE)
        }
        onItemsSeen()
    }

    override fun setListener(listener: (ids: Set<String>, translations: Map<String, String>) -> Unit) {
        this.listener = listener
    }

    /** After a settings change: re-read every title in the list and queue what is now missing. */
    fun refreshAll() {
        scope.launch {
            val all = synchronized(tracked) { tracked.values.toList() }
            push(all.mapTo(HashSet()) { it.urlHash })
            translator.request(all.takeLast(REFRESH_REQUEST_LIMIT), OWNER, TranslationQueue.Priority.VISIBLE)
        }
    }

    private suspend fun push(ids: Set<String>) {
        val items = synchronized(tracked) { ids.mapNotNull { tracked[it] } }
        if (items.isEmpty()) return
        val translations = lookupIfEnabled(items)
        listener?.invoke(items.mapTo(HashSet()) { it.urlHash }, translations)
    }

    private fun track(items: List<TitleItem>) {
        titles.record(items)
        synchronized(tracked) {
            items.forEach { tracked[it.urlHash] = it }
            val extra = tracked.size - MAX_TRACKED
            if (extra > 0) {
                val iterator = tracked.keys.iterator()
                repeat(extra) {
                    iterator.next()
                    iterator.remove()
                }
            }
        }
    }

    private fun FeedItem.toTitleItem() = TitleItem(urlHash = id, title = title, feedId = feedSource.id)

    private companion object {
        const val OWNER = "timeline"
        const val BATCH_MILLIS = 300L
        const val MAX_TRACKED = 2_000
        const val REFRESH_REQUEST_LIMIT = 80
    }
}

/**
 * Recent titles per feed, as seen in the timeline and in batch runs. Used
 * once per feed to estimate its language.
 */
class FeedTitleRegistry {
    private val byFeed = HashMap<String, LinkedHashMap<String, String>>()

    fun record(items: Collection<TitleItem>) = synchronized(byFeed) {
        for (item in items) {
            val title = item.title ?: continue
            val titles = byFeed.getOrPut(item.feedId) { LinkedHashMap() }
            titles[item.urlHash] = title
            if (titles.size > MAX_PER_FEED) titles.remove(titles.keys.first())
        }
    }

    fun recent(feedId: String): List<String> = synchronized(byFeed) { byFeed[feedId]?.values?.toList().orEmpty() }

    private companion object {
        const val MAX_PER_FEED = 30
    }
}
