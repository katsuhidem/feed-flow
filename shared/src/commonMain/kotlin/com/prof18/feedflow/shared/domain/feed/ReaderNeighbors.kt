package com.prof18.feedflow.shared.domain.feed

import com.prof18.feedflow.core.model.FeedItem

/** fork (Phase 4i): what the reader shows of the article next to the open one while swiping. */
data class ArticlePreview(
    val id: String,
    val title: String?,
    /** The title translation of the article list, when there is one. */
    val translatedTitle: String?,
    val feedTitle: String,
    val dateString: String?,
    val imageUrl: String?,
    /** The description the feed gives. */
    val summary: String?,
)

/**
 * fork (Phase 4i): the articles before and after the open one, picked the same way as the
 * reader's previous and next buttons (the list order, or the search results).
 */
class ReaderNeighbors internal constructor(
    private val navigation: ArticleNavigationRepository,
) {
    /** The reader opened: keep the timeline order until it closes (see ArticleNavigationRepository). */
    fun beginReadingSession() = navigation.beginReadingSession()

    fun endReadingSession() = navigation.endReadingSession()

    suspend fun previous(articleId: String): ArticlePreview? = navigation.getPreviousArticle(articleId)?.toPreview()

    suspend fun next(articleId: String): ArticlePreview? = navigation.getNextArticle(articleId)?.toPreview()

    private fun FeedItem.toPreview() = ArticlePreview(
        id = id,
        title = title,
        translatedTitle = translatedTitle,
        feedTitle = feedSource.title,
        dateString = dateString,
        imageUrl = imageUrl?.takeUnless { feedSource.isHideImagesEnabled },
        summary = subtitle?.takeIf { it.isNotBlank() },
    )
}
