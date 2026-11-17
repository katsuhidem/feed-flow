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
     * Only articles that arrived since the last check ([lastCheckedRowId]) can be marked; an
     * article that was already there is never touched again, whether it is read (by the user or
     * by an earlier check) or not. In each group of articles with the same URL:
     * - when an article that was already there is in the group, every unread new one is marked
     *   as read (the old one stays as it is);
     * - when all are new: if one of them is read, the unread ones are marked as read; otherwise
     *   the one published first is kept unread and the others are marked as read.
     * Nothing is ever marked as unread.
     *
     * @param lastCheckedRowId the highest row id seen at the last check; null when never checked,
     *   which treats every article as new
     */
    fun articlesToMarkRead(rows: List<DuplicateReadRow>, lastCheckedRowId: Long?): Set<String> {
        val out = mutableSetOf<String>()
        fun isNew(row: DuplicateReadRow) = lastCheckedRowId == null || row.rowId > lastCheckedRowId
        rows.groupBy { normalizeUrl(it.url) }
            .filterKeys { it != null }
            .values
            .filter { it.size > 1 }
            .forEach { group ->
                val new = group.filter(::isNew)
                val newUnread = new.filterNot { it.isRead }
                if (newUnread.isEmpty()) return@forEach
                val hasOld = new.size < group.size
                if (hasOld || new.any { it.isRead }) {
                    newUnread.mapTo(out) { it.id }
                    return@forEach
                }
                val keep = new.minWith(compareBy<DuplicateReadRow>({ it.pubDate ?: Long.MAX_VALUE }, { it.rowId }))
                new.filter { it !== keep }.mapTo(out) { it.id }
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
        val rows = databaseHelper.getDuplicateReadRows()
        val lastChecked = settings.getLongOrNull(KEY_LAST_ROW_ID)
        // Recorded also while switched off, so turning it on later does not reach back to
        // the articles that arrived in the meantime.
        rows.maxOfOrNull { it.rowId }?.let { settings.putLong(KEY_LAST_ROW_ID, maxOf(it, lastChecked ?: it)) }
        if (!enabled) {
            logger.i { "duplicate check skipped: disabled" }
            return
        }
        val ids = DuplicateRead.articlesToMarkRead(rows, lastChecked)
        logger.i { "duplicate check: articles=${rows.size} marked_read=${ids.size}" }
        if (ids.isNotEmpty()) markAsRead(ids.mapTo(HashSet()) { FeedItemId(it) })
    }

    private companion object {
        const val KEY_ENABLED = "fork_duplicate_read_enabled"
        const val KEY_LAST_ROW_ID = "fork_duplicate_read_last_row_id"
    }
}
