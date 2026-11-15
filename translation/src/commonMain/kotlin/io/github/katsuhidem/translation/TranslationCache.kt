package io.github.katsuhidem.translation

import io.github.katsuhidem.translation.db.TranslationDB
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Why a translation is recorded as failed. Transient errors are never recorded. */
enum class FailureReason(val dbValue: String) {
    OVER_BUDGET("over_budget"),
    TRUNCATED("truncated"),
    CODE_CHECK("code_check"),
    ;

    companion object {
        fun fromDb(value: String): FailureReason = entries.firstOrNull { it.dbValue == value } ?: TRUNCATED
    }
}

/** The three states of a key, known without loading a model. */
sealed interface CacheResult {
    data class Hit(val text: String) : CacheResult
    data class Failed(val reason: FailureReason) : CacheResult
    data object Missing : CacheResult
}

data class FeedTranslationMeta(val mode: FeedTranslationMode, val language: FeedLanguage?)

/**
 * Stores translations and failure records in the module's own database and
 * tells listeners which articles changed.
 *
 * Only translations that finished normally are stored. There is no foreign
 * key to the article tables (another database file), so old rows are removed
 * by age and by a row limit ([prune]).
 */
class TranslationCache(
    private val db: TranslationDB,
    private val nowMillis: () -> Long,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val queries get() = db.translationQueries

    private val _changes = MutableSharedFlow<Set<String>>(extraBufferCapacity = CHANGE_BUFFER)

    /** URL hashes whose translation was just stored. Feed this through [batched] before touching UI. */
    val changes: SharedFlow<Set<String>> = _changes.asSharedFlow()

    suspend fun lookup(keys: Collection<TranslationKey>): Map<TranslationKey, CacheResult> =
        withContext(dispatcher) {
            val result = keys.associateWithTo(LinkedHashMap()) { CacheResult.Missing as CacheResult }
            keys.groupBy { it.field to it.modelRev }.forEach { (group, groupKeys) ->
                val (field, modelRev) = group
                val wanted = groupKeys.toHashSet()
                groupKeys.map { it.urlHash }.distinct().chunked(SQL_CHUNK).forEach { hashes ->
                    queries.selectFailures(hashes, field.dbValue, modelRev).executeAsList().forEach { row ->
                        val key = TranslationKey(
                            row.url_hash, field, row.source_hash, row.context_hash, row.config_hash, row.model_rev,
                        )
                        if (key in wanted) result[key] = CacheResult.Failed(FailureReason.fromDb(row.reason))
                    }
                    // Read successes last: a success wins if both exist for a key.
                    queries.selectCache(hashes, field.dbValue, modelRev).executeAsList().forEach { row ->
                        val key = TranslationKey(
                            row.url_hash, field, row.source_hash, row.context_hash, row.config_hash, row.model_rev,
                        )
                        if (key in wanted) result[key] = CacheResult.Hit(row.text)
                    }
                }
            }
            result
        }

    /** Stores a finished translation and removes the failure record of the same key, in one transaction. */
    suspend fun saveSuccess(key: TranslationKey, text: String) {
        withContext(dispatcher) {
            db.transaction {
                queries.insertCache(
                    key.urlHash, key.field.dbValue, key.sourceHash, key.contextHash, key.configHash, key.modelRev,
                    text, nowMillis(),
                )
                queries.deleteFailure(
                    key.urlHash, key.field.dbValue, key.sourceHash, key.contextHash, key.configHash, key.modelRev,
                )
            }
        }
        _changes.emit(setOf(key.urlHash))
    }

    suspend fun saveFailure(key: TranslationKey, reason: FailureReason) {
        withContext(dispatcher) {
            queries.insertFailure(
                key.urlHash, key.field.dbValue, key.sourceHash, key.contextHash, key.configHash, key.modelRev,
                reason.dbValue, nowMillis(),
            )
        }
    }

    /** Removes rows older than [maxAgeMillis], then the oldest rows above [maxRows]. */
    suspend fun prune(maxRows: Long = DEFAULT_MAX_ROWS, maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS) {
        withContext(dispatcher) {
            db.transaction {
                val cutoff = nowMillis() - maxAgeMillis
                queries.deleteCacheOlderThan(cutoff)
                queries.deleteFailuresOlderThan(cutoff)
                val extraCache = queries.countCache().executeAsOne() - maxRows
                if (extraCache > 0) queries.deleteOldestCache(extraCache)
                val extraFailures = queries.countFailures().executeAsOne() - maxRows
                if (extraFailures > 0) queries.deleteOldestFailures(extraFailures)
            }
        }
    }

    suspend fun clear() {
        withContext(dispatcher) {
            db.transaction {
                queries.deleteAllCache()
                queries.deleteAllFailures()
            }
        }
    }

    // ---- per-feed settings ----

    suspend fun feedMeta(feedIds: Collection<String>): Map<String, FeedTranslationMeta> =
        withContext(dispatcher) {
            feedIds.distinct().chunked(SQL_CHUNK).flatMap { ids ->
                queries.selectFeedMeta(ids).executeAsList()
            }.associate { row ->
                row.feed_id to FeedTranslationMeta(
                    mode = FeedTranslationMode.fromDb(row.mode),
                    language = FeedLanguage.fromDb(row.estimated_lang),
                )
            }
        }

    suspend fun setFeedMode(feedId: String, mode: FeedTranslationMode) {
        withContext(dispatcher) {
            db.transaction {
                queries.insertFeedMetaIfAbsent(feedId, nowMillis())
                queries.updateFeedMode(mode.dbValue, nowMillis(), feedId)
            }
        }
    }

    /** Stores the estimated language once. A later, different estimate does not replace it. */
    suspend fun setFeedLanguageIfAbsent(feedId: String, language: FeedLanguage) {
        withContext(dispatcher) {
            db.transaction {
                queries.insertFeedMetaIfAbsent(feedId, nowMillis())
                queries.updateFeedLangIfNull(language.dbValue, nowMillis(), feedId)
            }
        }
    }

    companion object {
        private const val SQL_CHUNK = 500
        private const val CHANGE_BUFFER = 256
        const val DEFAULT_MAX_ROWS = 50_000L
        const val DEFAULT_MAX_AGE_MILLIS = 180L * 24 * 60 * 60 * 1000
    }
}

/**
 * Collects changes and passes them on together every [periodMillis], so a
 * batch run storing many titles does not redraw the list once per title.
 * The last change is always delivered. This is not a debounce: a debounce
 * would deliver nothing while changes keep coming.
 */
fun <T> Flow<Set<T>>.batched(periodMillis: Long): Flow<Set<T>> = channelFlow {
    val mutex = Mutex()
    var pending = mutableSetOf<T>()
    suspend fun flush() {
        val out = mutex.withLock {
            if (pending.isEmpty()) null else pending.also { pending = mutableSetOf() }
        }
        if (out != null) send(out)
    }
    val ticker = launch {
        while (true) {
            delay(periodMillis)
            flush()
        }
    }
    collect { change -> mutex.withLock { pending.addAll(change) } }
    // Upstream ended: deliver what is left, then stop.
    ticker.cancel()
    flush()
}
