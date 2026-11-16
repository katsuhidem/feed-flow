package com.prof18.feedflow.android.translation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import io.github.katsuhidem.translation.ArticleBlock
import io.github.katsuhidem.translation.ArticleSession
import io.github.katsuhidem.translation.BlockState
import io.github.katsuhidem.translation.BlockUpdate
import io.github.katsuhidem.translation.ProtectedSpan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

sealed interface ArticleTranslationUi {
    /** Nothing translated yet for the shown article. */
    data object Idle : ArticleTranslationUi

    /** The page is listing its blocks. */
    data object Collecting : ArticleTranslationUi
    data class Translating(val done: Int, val total: Int) : ArticleTranslationUi

    /** A run ended. [canRetry]: some blocks failed or were cancelled. */
    data class Finished(val canRetry: Boolean, val modelMissing: Boolean) : ArticleTranslationUi
}

/**
 * fork (Phase 3): translation state of the article shown in reader mode,
 * kept apart from the upstream ReaderModeViewModel.
 *
 * The page and this class talk in messages that carry a session id (new for
 * every listing of the page's blocks) and a run id (new for every
 * translation run). The page drops anything with another id, so results of
 * an earlier article or run cannot show up in the current one.
 */
class ArticleTranslationViewModel(private val setup: TranslationSetup) : ViewModel() {
    private val log = Logger.withTag("ArticleTranslation")

    private val _ui = MutableStateFlow<ArticleTranslationUi>(ArticleTranslationUi.Idle)
    val ui: StateFlow<ArticleTranslationUi> = _ui.asStateFlow()

    private val commands = Channel<String>(Channel.UNLIMITED)

    /** JavaScript to evaluate in the reader page, in order. */
    val jsCommands: Flow<String> = commands.receiveAsFlow()

    private val ids = AtomicLong(0)
    private var articleId: String? = null
    private var title: String? = null
    private var languageTag: String? = null
    private var sessionId: String? = null
    private var session: ArticleSession? = null
    private var job: Job? = null

    /** True once the user asked for a translation of this article; a reloaded page is then translated again. */
    private var active = false

    /**
     * The reader page finished loading. A new article resets everything; a
     * reload of the same article (theme or inset change) lost the inserted
     * translations, so they are put back from the cache.
     */
    fun onPageReady(articleId: String, title: String?, languageTag: String?) {
        val sameArticle = articleId == this.articleId
        stopRun()
        this.articleId = articleId
        this.title = title
        this.languageTag = languageTag
        session = null
        sessionId = null
        if (!sameArticle) active = false
        if (active) collect() else _ui.value = ArticleTranslationUi.Idle
    }

    /** The toolbar button: translate, stop a running translation, or retry what failed. */
    fun onButtonClick() {
        when (val state = _ui.value) {
            ArticleTranslationUi.Collecting -> Unit
            is ArticleTranslationUi.Translating -> {
                stopRun()
                _ui.value = ArticleTranslationUi.Finished(canRetry = true, modelMissing = false)
            }
            ArticleTranslationUi.Idle -> {
                active = true
                collect()
            }
            is ArticleTranslationUi.Finished -> {
                active = true
                if (session == null) collect() else startRun(retryFailed = state.canRetry)
            }
        }
    }

    /** The page reported its blocks (JSON from feedflowTranslation.collect). */
    fun onBlocks(json: String) {
        val id = articleId ?: return
        try {
            val message = JSONObject(json)
            if (message.getString("session") != sessionId) return
            val array = message.getJSONArray("blocks")
            val blocks = (0 until array.length()).map { i ->
                val block = array.getJSONObject(i)
                val codes = block.getJSONArray("codes")
                ArticleBlock(
                    id = block.getInt("id"),
                    text = block.getString("text"),
                    codes = (0 until codes.length()).map { k ->
                        val code = codes.getJSONObject(k)
                        ProtectedSpan(code.getInt("start"), code.getString("text"))
                    },
                )
            }
            session = setup.articleTranslator.open(id, title, blocks, languageTag)
            startRun(retryFailed = false)
        } catch (e: Exception) {
            log.w(e) { "could not read the page's blocks" }
            _ui.value = ArticleTranslationUi.Idle
        }
    }

    private fun collect() {
        val newSession = "s${ids.incrementAndGet()}"
        sessionId = newSession
        session = null
        _ui.value = ArticleTranslationUi.Collecting
        commands.trySend("window.feedflowTranslation && window.feedflowTranslation.collect(${JSONObject.quote(newSession)});")
    }

    private fun startRun(retryFailed: Boolean) {
        val current = session ?: return
        val sid = sessionId ?: return
        stopRun()
        val runId = "r${ids.incrementAndGet()}"
        val s = JSONObject.quote(sid)
        val r = JSONObject.quote(runId)
        commands.trySend("window.feedflowTranslation.beginRun($s, $r);")
        _ui.value = ArticleTranslationUi.Translating(0, 0)
        job = viewModelScope.launch {
            val progress = launch {
                current.states.collect { states ->
                    val total = states.values.count { it != BlockState.SKIPPED }
                    val done = states.values.count { it == BlockState.DONE }
                    if (_ui.value is ArticleTranslationUi.Translating) {
                        _ui.value = ArticleTranslationUi.Translating(done, total)
                    }
                }
            }
            try {
                val summary = current.run(retryFailed) { update ->
                    commands.trySend(
                        when (update) {
                            is BlockUpdate.Partial ->
                                "window.feedflowTranslation.apply($s, $r, ${update.blockId}, ${JSONObject.quote(update.text)}, true);"
                            is BlockUpdate.Done ->
                                "window.feedflowTranslation.apply($s, $r, ${update.blockId}, ${JSONObject.quote(update.text)}, false);"
                            is BlockUpdate.Cleared ->
                                "window.feedflowTranslation.remove($s, $r, ${update.blockId});"
                        },
                    )
                }
                log.i {
                    "run done=${summary.done} failed=${summary.failed} interrupted=${summary.interrupted} " +
                        "skipped=${summary.skipped} modelMissing=${summary.modelMissing}"
                }
                _ui.value = ArticleTranslationUi.Finished(summary.canRetry || summary.modelMissing, summary.modelMissing)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w(e) { "translation run failed" }
                _ui.value = ArticleTranslationUi.Finished(canRetry = true, modelMissing = false)
            } finally {
                progress.cancel()
            }
        }
    }

    /** Stops the running translation. Only this screen's requests are cancelled. */
    private fun stopRun() {
        job?.cancel()
        job = null
    }

    override fun onCleared() {
        stopRun()
    }
}
