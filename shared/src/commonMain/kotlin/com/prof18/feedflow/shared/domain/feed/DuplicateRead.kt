package com.prof18.feedflow.shared.domain.feed

import co.touchlab.kermit.Logger
import com.prof18.feedflow.core.model.FeedItemId
import com.prof18.feedflow.database.DatabaseHelper
import com.prof18.feedflow.database.DuplicateReadRow
import com.russhwolf.settings.Settings

/**
 * fork (Phase 4e): marks an article as read when the same article (by URL)
 * is already in the database, as trend and digest feeds repeat articles of
 * other feeds. See docs/PLAN.md "Phase 4e".
 */
object DuplicateRead {
    private val TRACKING_PARAMS = setOf("fbclid", "gclid", "yclid", "dclid", "msclkid", "mc_cid", "mc_eid", "igshid")

    /**
     * The URL in a form where the scheme, "www.", a trailing slash, the fragment and tracking
     * parameters make no difference. Null for an empty URL.
     */
    fun normalizeUrl(url: String): String? {
        var u = url.trim()
        if (u.isEmpty()) return null
        u = u.substringBefore('#')
        val lower = u.lowercase()
        u = when {
            lower.startsWith("https://") -> u.substring("https://".length)
            lower.startsWith("http://") -> u.substring("http://".length)
            else -> return u
        }
        val query = u.substringAfter('?', missingDelimiterValue = "")
        val beforeQuery = u.substringBefore('?')
        var host = beforeQuery.substringBefore('/').lowercase()
        if (host.startsWith("www.")) host = host.removePrefix("www.")
        val path = beforeQuery.substring(beforeQuery.indexOf('/').let { if (it < 0) beforeQuery.length else it })
            .trimEnd('/')
        val kept = query.split('&')
            .filter { it.isNotEmpty() }
            .filterNot { param ->
                val key = param.substringBefore('=').lowercase()
                key.startsWith("utm_") || key in TRACKING_PARAMS
            }
            .sorted()
        return host + path + if (kept.isEmpty()) "" else "?" + kept.joinToString("&")
    }

    /**
     * The articles to mark as read.
     *
     * In each group of articles with the same URL: when one of them is read, every unread one is
     * marked as read. When all are unread, one is kept: the first one inserted among those that
     * were already there at the last check ([lastCheckedRowId]), or, when all are new since then,
     * the one published first. Nothing is ever marked as unread.
     *
     * @param lastCheckedRowId the highest row id seen at the last check; null when never checked,
     *   which treats every article as already there
     */
    fun articlesToMarkRead(rows: List<DuplicateReadRow>, lastCheckedRowId: Long?): Set<String> {
        val out = mutableSetOf<String>()
        rows.groupBy { normalizeUrl(it.url) }
            .filterKeys { it != null }
            .values
            .filter { it.size > 1 }
            .forEach { group ->
                val unread = group.filterNot { it.isRead }
                if (unread.isEmpty()) return@forEach
                if (group.any { it.isRead }) {
                    unread.mapTo(out) { it.id }
                    return@forEach
                }
                val earlier = group.filter { lastCheckedRowId == null || it.rowId <= lastCheckedRowId }
                val keep = if (earlier.isNotEmpty()) {
                    earlier.minBy { it.rowId }
                } else {
                    group.minWith(compareBy<DuplicateReadRow>({ it.pubDate ?: Long.MAX_VALUE }, { it.rowId }))
                }
                group.filter { it !== keep }.mapTo(out) { it.id }
            }
        return out
    }
}

/**
 * fork (Phase 4e): runs the duplicate check after a sync and marks the duplicates as read through
 * [markAsRead], which also sends the change to the sync account (FreshRSS) and retries it later
 * when that fails.
 */
class DuplicateReadMarker internal constructor(
    private val databaseHelper: DatabaseHelper,
    private val settings: Settings,
    private val logger: Logger,
    private val markAsRead: suspend (Set<FeedItemId>) -> Unit,
) {
    var enabled: Boolean
        get() = settings.getBoolean(KEY_ENABLED, true)
        set(value) {
            settings.putBoolean(KEY_ENABLED, value)
            logger.i { "duplicate read enabled=$value" }
        }

    suspend fun afterSync() {
        if (!enabled) {
            logger.i { "duplicate check skipped: disabled" }
            return
        }
        val rows = databaseHelper.getDuplicateReadRows()
        val lastChecked = settings.getLongOrNull(KEY_LAST_ROW_ID)
        val ids = DuplicateRead.articlesToMarkRead(rows, lastChecked)
        rows.maxOfOrNull { it.rowId }?.let { settings.putLong(KEY_LAST_ROW_ID, maxOf(it, lastChecked ?: it)) }
        logger.i { "duplicate check: articles=${rows.size} marked_read=${ids.size}" }
        if (ids.isNotEmpty()) markAsRead(ids.mapTo(HashSet()) { FeedItemId(it) })
    }

    private companion object {
        const val KEY_ENABLED = "fork_duplicate_read_enabled"
        const val KEY_LAST_ROW_ID = "fork_duplicate_read_last_row_id"
    }
}
