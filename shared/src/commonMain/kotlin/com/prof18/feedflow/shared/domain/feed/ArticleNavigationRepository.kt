package com.prof18.feedflow.shared.domain.feed

import com.prof18.feedflow.core.model.FeedItem
import com.prof18.feedflow.shared.presentation.model.ArticleListSource
import kotlinx.collections.immutable.ImmutableList

internal class ArticleNavigationRepository(
    private val feedStateRepository: FeedStateRepository,
) {
    private var searchResults: ImmutableList<FeedItem>? = null

    fun setSearchResults(items: ImmutableList<FeedItem>) {
        searchResults = items
    }

    fun clearSearchResults() {
        searchResults = null
    }

    suspend fun getNextArticle(currentArticleId: String): FeedItem? {
        val searchList = searchListContaining(currentArticleId)
            ?: return readingOrderHolding(currentArticleId)?.let { order -> // fork (Phase 4i)
                nextInReadingOrder(order, currentArticleId)
            } ?: feedStateRepository.getNextArticle(currentArticleId)
        val index = searchList.indexOfFirst { it.id == currentArticleId }
        return searchList.getOrNull(index + 1)
    }

    fun getPreviousArticle(currentArticleId: String): FeedItem? {
        val searchList = searchListContaining(currentArticleId)
            ?: return readingOrderHolding(currentArticleId).let { order -> // fork (Phase 4i)
                if (order != null) {
                    neighbour(order, currentArticleId, step = -1)
                } else {
                    feedStateRepository.getPreviousArticle(currentArticleId)
                }
            }
        val index = searchList.indexOfFirst { it.id == currentArticleId }
        return if (index > 0) searchList.getOrNull(index - 1) else null
    }

    fun getArticlePosition(currentArticleId: String): ArticlePosition? {
        val searchList = searchListContaining(currentArticleId)
            ?: return readingOrderHolding(currentArticleId)?.let { order -> // fork (Phase 4i)
                ArticlePosition(currentPosition = order.indexOfFirst { it.id == currentArticleId } + 1, totalArticles = order.size)
            } ?: feedStateRepository.getArticlePosition(currentArticleId)
        val index = searchList.indexOfFirst { it.id == currentArticleId }
        return ArticlePosition(currentPosition = index + 1, totalArticles = searchList.size)
    }

    fun listSourceOf(articleId: String): ArticleListSource =
        if (searchListContaining(articleId) != null) ArticleListSource.SEARCH else ArticleListSource.TIMELINE

    /**
     * fork (Phase 4i): the timeline as it was when the reader opened, kept until the reader
     * closes. Read articles drop out of the timeline when it reloads (a sync, for example) with
     * read articles hidden; without this the previous article, read a moment ago, could not be
     * reached again, and the open article itself could drop out so that no neighbour was found.
     * The reader moves in the order the user started reading in, both with the buttons and by
     * swiping. Past its end it continues with what the timeline has loaded since.
     */
    private var readingOrder: List<FeedItem>? = null

    fun beginReadingSession() {
        readingOrder = feedStateRepository.feedState.value
    }

    fun endReadingSession() {
        readingOrder = null
    }

    private fun readingOrderHolding(articleId: String): List<FeedItem>? =
        readingOrder?.takeIf { order -> order.any { it.id == articleId } }

    private fun neighbour(order: List<FeedItem>, articleId: String, step: Int): FeedItem? =
        order.getOrNull(order.indexOfFirst { it.id == articleId } + step)

    /**
     * Near the end of the reading order, the timeline loads its next page (as upstream does when
     * moving on) and what it adds is appended to the order.
     */
    private suspend fun nextInReadingOrder(order: List<FeedItem>, articleId: String): FeedItem? {
        val index = order.indexOfFirst { it.id == articleId }
        if (index >= order.size - LOAD_MORE_THRESHOLD) {
            feedStateRepository.loadMoreFeeds()
            val known = order.mapTo(HashSet()) { it.id }
            readingOrder = order + feedStateRepository.feedState.value.filterNot { it.id in known }
        }
        return neighbour(readingOrder ?: order, articleId, step = 1)
    }

    private companion object {
        const val LOAD_MORE_THRESHOLD = 5
    }

    private fun searchListContaining(articleId: String): ImmutableList<FeedItem>? {
        val list = searchResults ?: return null
        return list.takeIf { items -> items.any { it.id == articleId } }
    }
}
