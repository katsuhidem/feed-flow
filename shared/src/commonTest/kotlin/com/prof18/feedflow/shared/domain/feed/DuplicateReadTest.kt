package com.prof18.feedflow.shared.domain.feed

import com.prof18.feedflow.database.DuplicateReadRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** fork (Phase 4e) */
class DuplicateReadTest {
    private fun row(rowId: Long, url: String, isRead: Boolean = false, pubDate: Long? = rowId) =
        DuplicateReadRow(rowId = rowId, id = "id$rowId", url = url, isRead = isRead, pubDate = pubDate)

    @Test
    fun urlsThatDifferOnlyInFormAreTheSame() {
        val base = DuplicateRead.normalizeUrl("https://example.com/news/123")
        listOf(
            "http://example.com/news/123",
            "https://www.example.com/news/123",
            "https://EXAMPLE.com/news/123/",
            "https://example.com/news/123#comments",
            "https://example.com/news/123?utm_source=rss&utm_medium=feed",
            "https://example.com/news/123?fbclid=abc",
        ).forEach { assertEquals(base, DuplicateRead.normalizeUrl(it), it) }
    }

    @Test
    fun urlsThatPointElsewhereStayDifferent() {
        val base = DuplicateRead.normalizeUrl("https://example.com/news/123")
        listOf(
            "https://example.com/news/124",
            "https://example.com/News/123",
            "https://example.org/news/123",
            "https://example.com/news/123?page=2",
        ).forEach { assert(base != DuplicateRead.normalizeUrl(it)) { it } }
        // Parameter order does not matter, the parameters themselves do.
        assertEquals(
            DuplicateRead.normalizeUrl("https://example.com/a?x=1&y=2"),
            DuplicateRead.normalizeUrl("https://example.com/a?y=2&x=1"),
        )
        assertNull(DuplicateRead.normalizeUrl("  "))
    }

    @Test
    fun theLaterArticleIsMarkedReadAndTheFirstOneKept() {
        val rows = listOf(row(1, "https://example.com/a"), row(2, "https://example.com/a?utm_source=x"), row(3, "https://example.com/b"))
        assertEquals(setOf("id2"), DuplicateRead.articlesToMarkRead(rows, lastCheckedRowId = 1))
    }

    @Test
    fun whenOneCopyIsAlreadyReadEveryUnreadCopyIsMarkedRead() {
        val rows = listOf(row(1, "https://example.com/a", isRead = true), row(5, "https://example.com/a"), row(6, "http://www.example.com/a/"))
        assertEquals(setOf("id5", "id6"), DuplicateRead.articlesToMarkRead(rows, lastCheckedRowId = 1))
    }

    @Test
    fun articlesThatArriveTogetherKeepTheOnePublishedFirst() {
        // Both new since the last check; row 8 was inserted first but published later.
        val rows = listOf(row(8, "https://example.com/a", pubDate = 2_000), row(9, "https://example.com/a", pubDate = 1_000))
        assertEquals(setOf("id8"), DuplicateRead.articlesToMarkRead(rows, lastCheckedRowId = 5))
    }

    @Test
    fun anArticleThatWasAlreadyThereWinsOverANewOneEvenIfPublishedLater() {
        val rows = listOf(row(4, "https://example.com/a", pubDate = 2_000), row(9, "https://example.com/a", pubDate = 1_000))
        assertEquals(setOf("id9"), DuplicateRead.articlesToMarkRead(rows, lastCheckedRowId = 5))
    }

    @Test
    fun withoutAnEarlierCheckTheFirstInsertedIsKept() {
        val rows = listOf(row(3, "https://example.com/a", pubDate = 2_000), row(4, "https://example.com/a", pubDate = 1_000))
        assertEquals(setOf("id4"), DuplicateRead.articlesToMarkRead(rows, lastCheckedRowId = null))
    }

    @Test
    fun nothingIsMarkedWhenThereIsNoDuplicateOrAllCopiesAreRead() {
        assertEquals(emptySet(), DuplicateRead.articlesToMarkRead(listOf(row(1, "https://example.com/a"), row(2, "https://example.com/b")), 2))
        val allRead = listOf(row(1, "https://example.com/a", isRead = true), row(2, "https://example.com/a", isRead = true))
        assertEquals(emptySet(), DuplicateRead.articlesToMarkRead(allRead, 2))
    }
}
