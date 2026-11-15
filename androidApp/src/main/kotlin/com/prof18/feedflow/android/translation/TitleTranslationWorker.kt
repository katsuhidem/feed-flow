package com.prof18.feedflow.android.translation

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import co.touchlab.kermit.Logger
import com.prof18.feedflow.core.model.FeedFilter
import com.prof18.feedflow.core.model.FeedOrder
import com.prof18.feedflow.database.DatabaseHelper
import io.github.katsuhidem.translation.TitleItem
import kotlinx.coroutines.CancellationException
import org.koin.core.context.GlobalContext
import java.util.concurrent.TimeUnit

/**
 * fork (Phase 2): translates titles that are not translated yet, while the
 * device is charging. One run handles about twenty titles; each one is
 * stored as it finishes, and a follow-up run is queued while more remain,
 * so a run that is cut short loses nothing.
 */
class TitleTranslationWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val log = Logger.withTag("TitleTranslation")

    override suspend fun doWork(): Result {
        val koin = GlobalContext.getOrNull() ?: return Result.success()
        val setup = koin.get<TranslationSetup>()
        val database = koin.get<DatabaseHelper>()
        return try {
            val items = database.getFeedItems(
                feedFilter = FeedFilter.Timeline,
                pageSize = CANDIDATES,
                showReadItems = false,
                sortOrder = FeedOrder.NEWEST_FIRST,
            ).map { TitleItem(urlHash = it.url_hash, title = it.title, feedId = it.feed_source_id) }
            setup.recordTitles(items)

            val result = setup.titleTranslator.translateBatch(items, owner = OWNER)
            log.i { "batch: translated=${result.translated} failed=${result.failed} remaining=${result.remaining}" }
            // Go on only while this run made progress; otherwise wait for the next trigger.
            if (result.remaining > 0 && result.translated > 0) scheduleNext()
            Result.success()
        } catch (e: CancellationException) {
            setup.titleTranslator.cancel(OWNER)
            throw e
        } catch (e: Exception) {
            log.w(e) { "batch failed" }
            Result.failure()
        }
    }

    private fun scheduleNext() {
        val request = OneTimeWorkRequestBuilder<TitleTranslationWorker>()
            .setConstraints(androidx.work.Constraints.Builder().setRequiresCharging(true).build())
            .setInitialDelay(NEXT_DELAY_SECONDS, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(applicationContext)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    companion object {
        const val WORK_NAME = "title-translation-batch"
        private const val OWNER = "batch"
        private const val CANDIDATES = 200L
        private const val NEXT_DELAY_SECONDS = 2L
    }
}
