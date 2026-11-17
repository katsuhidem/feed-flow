package com.prof18.feedflow.shared.domain.contentprefetch

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import co.touchlab.kermit.Logger

internal class WorkManagerBackgroundPrefetchScheduler(
    private val appContext: Context,
    private val logger: Logger,
    // fork (Phase 4a)
    private val gate: ContentPrefetchGate? = null,
) : BackgroundPrefetchScheduler {

    override fun start() {
        // fork (Phase 4a): with "Wi-Fi only" the work waits for a connection that is not metered,
        // also when the app is not running by then. The gate still checks before every article.
        val wifiOnly = gate?.wifiOnly() == true
        logger.d { "Enqueueing background content prefetch (unmetered only: $wifiOnly)" }

        val workRequest = OneTimeWorkRequestBuilder<ContentPrefetchWorker>()
            .addTag(WORKER_TAG)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .build(),
            )
            .build()

        WorkManager.getInstance(appContext)
            .enqueueUniqueWork(
                WORKER_TAG,
                ExistingWorkPolicy.REPLACE,
                workRequest,
            )
    }

    // Only iOS pauses prefetching when the app moves to the background.
    override fun pause() = Unit

    override suspend fun cancel() {
        WorkManager.getInstance(appContext).cancelUniqueWork(WORKER_TAG)
    }

    private companion object {
        const val WORKER_TAG = "ContentPrefetchWorker"
    }
}
