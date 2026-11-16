package com.prof18.feedflow.android.translation

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.prof18.feedflow.shared.domain.feed.TimelineTitleTranslations
import io.github.katsuhidem.translation.FeedTranslationMode
import io.github.katsuhidem.translation.LlamaCppEngine
import io.github.katsuhidem.translation.ModelCatalog
import io.github.katsuhidem.translation.TitleTranslator
import io.github.katsuhidem.translation.TranslationQueue
import io.github.katsuhidem.translation.createModelStore
import io.github.katsuhidem.translation.createTranslationCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * fork (Phase 2): builds the translation parts and offers them to Koin.
 * Nothing heavy happens here: the database opens on first use and the model
 * loads only when a title is actually translated.
 */
class TranslationSetup(private val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val prefs = TranslationPrefs(app)
    val modelStore = createModelStore(app)
    val cache by lazy { createTranslationCache(app) }

    /** Titles (and, by default, article text) use the 0.8B model; see docs/PLAN.md "Phase 0 の結果". */
    val titleModel = ModelCatalog.DEFAULT

    private val titles = FeedTitleRegistry()
    private val engine = LlamaCppEngine(
        modelFile = { modelStore.file(titleModel) },
        threads = prefs.debugThreads ?: LlamaCppEngine.DEFAULT_THREADS,
        threadPriority = prefs.debugThreadPriority ?: LlamaCppEngine.DEFAULT_THREAD_PRIORITY,
    )
    private val queue by lazy { TranslationQueue(engine, cache, scope) }

    val titleTranslator by lazy {
        TitleTranslator(
            cache = cache,
            queue = queue,
            config = { prefs.config() },
            modelRev = { titleModel.sha256 },
            canTranslate = { prefs.enabled.value && modelStore.isInstalled(titleModel) },
            recentTitles = { feedId -> titles.recent(feedId) },
        )
    }

    val timeline by lazy {
        TimelineTitleTranslationsImpl(
            translator = titleTranslator,
            cache = cache,
            titles = titles,
            scope = scope,
            isEnabled = { prefs.enabled.value },
            onItemsSeen = ::scheduleBatch,
        )
    }

    val koinModule: Module = module {
        single { this@TranslationSetup }
        single<TimelineTitleTranslations> { timeline }
    }

    /** Call after Koin has started. */
    fun install() {
        // A changed style or switch changes which translations apply: re-read the list.
        scope.launch {
            merge(prefs.style.drop(1), prefs.enabled.drop(1)).collect { timeline.refreshAll() }
        }
        scope.launch { cache.prune() }
        scheduleBatch()
    }

    private var downloadJob: kotlinx.coroutines.Job? = null

    /** Starts or continues the model download. It keeps running when the settings screen is left. */
    @Synchronized
    fun startDownload() {
        if (downloadJob?.isActive == true) return
        downloadJob = scope.launch(Dispatchers.IO) {
            if (modelStore.download(titleModel)) {
                timeline.refreshAll()
                scheduleBatch()
            }
        }
    }

    /** Stops the download. The part already on disk is kept and the next start continues from it. */
    @Synchronized
    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
    }

    fun deleteModel() {
        cancelDownload()
        scope.launch { modelStore.delete(titleModel) }
    }

    suspend fun feedMode(feedId: String): FeedTranslationMode =
        cache.feedMeta(listOf(feedId))[feedId]?.mode ?: FeedTranslationMode.AUTO

    /** Stores the per-feed setting and re-reads the list, so titles appear or disappear at once. */
    fun setFeedMode(feedId: String, mode: FeedTranslationMode) {
        scope.launch {
            cache.setFeedMode(feedId, mode)
            timeline.refreshAll()
            scheduleBatch()
        }
    }

    fun recordTitles(items: Collection<io.github.katsuhidem.translation.TitleItem>) = titles.record(items)

    /** Queues the batch job; it runs while the device is charging. A job already queued is kept. */
    fun scheduleBatch() {
        if (!prefs.enabled.value || !modelStore.isInstalled(titleModel)) return
        val request = OneTimeWorkRequestBuilder<TitleTranslationWorker>()
            .setConstraints(Constraints.Builder().setRequiresCharging(true).build())
            .build()
        WorkManager.getInstance(app)
            .enqueueUniqueWork(TitleTranslationWorker.WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }
}
