package io.github.katsuhidem.translation

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.katsuhidem.translation.db.TranslationDB
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

internal fun inMemoryDb(): TranslationDB {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    TranslationDB.Schema.create(driver)
    return TranslationDB(driver)
}

internal class FakeClock(var now: Long = 1_000L) : () -> Long {
    override fun invoke(): Long = now
}

internal fun newCache(dispatcher: CoroutineDispatcher, clock: FakeClock = FakeClock()) =
    TranslationCache(inMemoryDb(), clock, dispatcher)

internal fun planFor(
    text: String,
    urlHash: String = "u-$text",
    config: TranslationConfig = TranslationConfig(),
    modelRev: String = "rev-a",
): TranslationPlan =
    (TranslationPlanner.plan(urlHash, TranslationField.TITLE, text, config, modelRev) as PlanResult.Planned).plan

/**
 * Engine that "translates" by upper-casing the source and records what
 * happened. One token per character keeps the arithmetic obvious.
 */
internal class FakeEngine : TranslationEngine {
    var loaded = false
    var loadCount = 0
    var unloadCount = 0
    var failLoad = false
    val translated = mutableListOf<String>()
    val prompts = mutableListOf<String>()
    var maxTokensSeen = mutableListOf<Int>()

    /** When set, translate() waits for it before finishing; lets a test hold a job in "running". */
    var gate: CompletableDeferred<Unit>? = null
    var started: CompletableDeferred<Unit>? = null

    /** Terminal event to produce for a given source; default is Completed. */
    var terminal: (String) -> TranslationEvent = { TranslationEvent.Completed(it.uppercase()) }

    /** Token count override, for the over-budget case. */
    var tokensFor: (String) -> Int = { it.length + 1 }

    override fun isLoaded(): Boolean = loaded

    override suspend fun ensureLoaded() {
        if (loaded) return
        if (failLoad) error("no model")
        loaded = true
        loadCount++
    }

    override suspend fun countTokens(text: String): Int = tokensFor(text)

    override fun translate(prompt: String, maxTokens: Int): Flow<TranslationEvent> = flow {
        check(loaded) { "translate called while unloaded" }
        val source = prompt.substringAfter("<|plamo:tag|>input").substringAfter("<|plamo:msg|>")
            .substringBefore("<|plamo:tag|>output")
        maxTokensSeen += maxTokens
        prompts += prompt
        started?.complete(Unit)
        emit(TranslationEvent.Delta(source.take(1).uppercase()))
        gate?.await()
        translated += source
        emit(terminal(source))
    }

    override suspend fun unload() {
        loaded = false
        unloadCount++
    }
}
