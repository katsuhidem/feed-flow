package com.prof18.feedflow.shared.domain.feed

import com.prof18.feedflow.core.model.FeedItem

/**
 * fork (Phase 2): source of translated titles for the timeline. The app
 * supplies the implementation; without one the timeline is unchanged.
 */
interface TimelineTitleTranslations {
    /** Translations already known, by [FeedItem.id]. Must not load a model. */
    suspend fun lookup(items: List<FeedItem>): Map<String, String>

    /** Called with items that were just put in the list; may start translating them. */
    fun onItemsLoaded(items: List<FeedItem>)

    /**
     * [listener] is called when translations change: every id in `ids` gets
     * the title in `translations`, or loses its translated title when absent.
     */
    fun setListener(listener: (ids: Set<String>, translations: Map<String, String>) -> Unit)
}
