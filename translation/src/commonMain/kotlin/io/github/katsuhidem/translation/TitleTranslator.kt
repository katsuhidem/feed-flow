package io.github.katsuhidem.translation

import io.github.katsuhidem.translation.TranslationQueue.Outcome
import io.github.katsuhidem.translation.TranslationQueue.Priority
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.awaitAll

/** A title as the timeline knows it. */
data class TitleItem(
    val urlHash: String,
    val title: String?,
    val feedId: String,
    /** Language tag of the article when the feed gives one. */
    val languageTag: String? = null,
)

/**
 * Title translation for the timeline.
 *
 * [lookup] answers from the cache alone; it never loads a model. [request]
 * queues only titles the cache reports as missing, so a model is loaded
 * only when there is something new to translate. Titles recorded as failed
 * are left alone unless the user asks to retry.
 *
 * @param modelRev revision of the model titles are translated with (its
 *   file hash). Known without the file, so cached titles show even when the
 *   model is not loaded or not installed.
 * @param canTranslate false while no model is installed; [request] then queues nothing
 * @param recentTitles recent titles of a feed, used once to estimate its language
 */
class TitleTranslator(
    private val cache: TranslationCache,
    private val queue: TranslationQueue,
    private val config: suspend () -> TranslationConfig,
    private val modelRev: () -> String,
    private val canTranslate: () -> Boolean,
    private val recentTitles: suspend (feedId: String) -> List<String>,
) {
    class BatchResult(val translated: Int, val failed: Int, val remaining: Int)

    /** Cached translations by URL hash. Titles that are skipped, failed or not yet translated are absent. */
    suspend fun lookup(items: Collection<TitleItem>): Map<String, String> {
        val plans = plansFor(items)
        if (plans.isEmpty()) return emptyMap()
        val results = cache.lookup(plans.map { it.key })
        return plans.mapNotNull { plan ->
            (results[plan.key] as? CacheResult.Hit)?.let { plan.key.urlHash to it.text }
        }.toMap()
    }

    /**
     * Queues the titles that have no translation yet.
     *
     * @param retryFailed also queue titles recorded as failed (an explicit retry by the user)
     */
    suspend fun request(
        items: Collection<TitleItem>,
        owner: Any,
        priority: Priority,
        retryFailed: Boolean = false,
    ): List<Deferred<Outcome>> {
        if (!canTranslate()) return emptyList()
        return pending(items, retryFailed).map { queue.submit(it, owner, priority) }
    }

    /**
     * Translates up to [limit] missing titles and waits for them. Each one
     * is stored as it finishes, so a job that is cut short loses nothing and
     * the next job continues with what is left.
     */
    suspend fun translateBatch(items: Collection<TitleItem>, owner: Any, limit: Int = DEFAULT_BATCH_SIZE): BatchResult {
        if (!canTranslate()) return BatchResult(0, 0, 0)
        val pending = pending(items, retryFailed = false)
        val outcomes = pending.take(limit).map { queue.submit(it, owner, Priority.BATCH) }.awaitAll()
        return BatchResult(
            translated = outcomes.count { it is Outcome.Success },
            failed = outcomes.count { it is Outcome.Failure },
            remaining = (pending.size - limit).coerceAtLeast(0) +
                outcomes.count { it is Outcome.Cancelled || it is Outcome.Error },
        )
    }

    suspend fun cancel(owner: Any) = queue.cancel(owner)

    private suspend fun pending(items: Collection<TitleItem>, retryFailed: Boolean): List<TranslationPlan> {
        val plans = plansFor(items)
        if (plans.isEmpty()) return emptyList()
        val results = cache.lookup(plans.map { it.key })
        return plans.filter { plan ->
            when (results[plan.key]) {
                is CacheResult.Hit -> false
                is CacheResult.Failed -> retryFailed
                else -> true
            }
        }
    }

    /** Plans for the titles that should be translated, one per URL hash. */
    private suspend fun plansFor(items: Collection<TitleItem>): List<TranslationPlan> {
        if (items.isEmpty()) return emptyList()
        val config = config()
        val rev = modelRev()
        val meta = feedMeta(items.mapTo(LinkedHashSet()) { it.feedId })
        return items.distinctBy { it.urlHash }.mapNotNull { item ->
            val feed = meta[item.feedId]
            val result = TranslationPlanner.plan(
                urlHash = item.urlHash,
                field = TranslationField.TITLE,
                text = item.title,
                config = config,
                modelRev = rev,
                feedMode = feed?.mode ?: FeedTranslationMode.AUTO,
                feedLanguage = feed?.language,
                languageTag = item.languageTag,
            )
            (result as? PlanResult.Planned)?.plan
        }
    }

    /**
     * Per-feed settings, estimating and storing the language of feeds that
     * have none yet. The estimate is stored the first time it can be made
     * and reused from then on: estimating again could flip it and change keys.
     */
    private suspend fun feedMeta(feedIds: Set<String>): Map<String, FeedTranslationMeta> {
        val stored = cache.feedMeta(feedIds)
        val undecided = feedIds.filter { stored[it]?.language == null }
        if (undecided.isEmpty()) return stored
        var changed = false
        for (feedId in undecided) {
            val estimate = LanguageGate.estimateFeedLanguage(recentTitles(feedId)) ?: continue
            cache.setFeedLanguageIfAbsent(feedId, estimate)
            changed = true
        }
        return if (changed) cache.feedMeta(feedIds) else stored
    }

    companion object {
        const val DEFAULT_BATCH_SIZE = 20
    }
}
