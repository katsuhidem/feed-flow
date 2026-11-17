package com.prof18.feedflow.database

/** fork (Phase 4e): one article as the duplicate check sees it. */
data class DuplicateReadRow(
    /** Insertion order of the article in the database. */
    val rowId: Long,
    val id: String,
    val url: String,
    val isRead: Boolean,
    val pubDate: Long?,
)
