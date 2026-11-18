package com.prof18.feedflow.android.translation

import kotlinx.coroutines.awaitAll
import io.github.katsuhidem.translation.TranslationQueue.Priority
import io.github.katsuhidem.translation.TitleItem
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import com.prof18.feedflow.android.BuildConfig
import io.github.katsuhidem.translation.ArticleBlock
import io.github.katsuhidem.translation.ArticleSession
import io.github.katsuhidem.translation.BlockState
import io.github.katsuhidem.translation.BlockUpdate
import io.github.katsuhidem.translation.ProtectedSpan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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

    /** Debug builds: reader mode shows the built-in check article instead of the opened one. */
    val debugCheckArticle: StateFlow<Boolean> =
        if (BuildConfig.DEBUG) setup.prefs.debugCheckArticle else MutableStateFlow(false)

    private val commands = Channel<String>(Channel.UNLIMITED)

    /** JavaScript to evaluate in the reader page, in order. */
    val jsCommands: Flow<String> = commands.receiveAsFlow()

    private val ids = AtomicLong(0)
    private var articleId: String? = null
    private var pageToken: String? = null

    /** The article whose page last reported itself; the button waits until it is the shown article. */
    private var pageArticleId: String? = null
    private var collectTimeout: Job? = null
    private var title: String? = null
    private var languageTag: String? = null
    private var sessionId: String? = null
    private var session: ArticleSession? = null
    private var job: Job? = null

    /** fork (Phase 4h): the block of the page that is the article title, when the page has one. */
    private var titleBlockId: Int? = null
    private val titleOwner = Any()

    /**
     * True while the user wants this article translated: from a press of
     * "translate" or "retry" until they stop the run or leave the reader.
     * A page that reloads meanwhile (theme or inset change) is translated again.
     */
    private var active = false

    /**
     * The reader shows [articleId] (its page may still be loading). A new
     * article resets the translation state.
     */
    fun setArticle(articleId: String, title: String?, languageTag: String?) {
        this.title = title
        this.languageTag = languageTag
        if (articleId == this.articleId) return
        titleBlockId = null
        this.articleId = articleId
        stopRun()
        session = null
        sessionId = null
        active = false
        _ui.value = ArticleTranslationUi.Idle
    }

    /**
     * The reader left the screen. This view model outlives the reader, so
     * without this an article opened again later would still count as "being
     * translated" and continue by itself. A running translation is stopped;
     * what is stored shows again when the article is reopened.
     */
    fun onReaderClosed() {
        log.d { "reader closed: active=$active" }
        stopRun()
        articleId = null
        pageArticleId = null
        pageToken = null
        session = null
        sessionId = null
        active = false
        _ui.value = ArticleTranslationUi.Idle
    }

    /**
     * The reader page reported itself after loading.
     *
     * @param pageToken identifies the loaded document. "Page finished" is
     *   also signalled without a reload (late images, for example), and the
     *   page of the previous article answers once more while the next one
     *   loads. The token last seen means nothing changed: a running
     *   translation is not restarted, and the old page is never listed under
     *   the new article.
     */
    fun onPageReady(pageToken: String) {
        if (pageToken == this.pageToken) return
        log.d { "page ready: active=$active" }
        this.pageToken = pageToken
        pageArticleId = articleId
        stopRun()
        session = null
        sessionId = null
        // Always list the blocks: what the cache already has is shown at once, without a model.
        // Only a press of the button translates the rest. A reload of the same article
        // (theme or inset change) gets its translations back the same way.
        if (active || setup.prefs.enabled.value) collect() else _ui.value = ArticleTranslationUi.Idle
    }

    /** The toolbar button: translate, stop a running translation, or retry what failed. */
    fun onButtonClick() {
        if (articleId == null || pageArticleId != articleId) {
            log.d { "button ignored: the page of this article has not reported yet" }
            return
        }
        when (val state = _ui.value) {
            ArticleTranslationUi.Collecting -> Unit
            is ArticleTranslationUi.Translating -> {
                // Stopped by the user: a reload must not start it again.
                active = false
                stopRun()
                log.i { "run stopped by the user" }
                _ui.value = ArticleTranslationUi.Finished(canRetry = true, modelMissing = false)
            }
            ArticleTranslationUi.Idle -> {
                active = true
                if (session == null) collect() else startRun(retryFailed = false)
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
            collectTimeout?.cancel()
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
            titleBlockId = blocks.firstOrNull()?.takeIf { sameText(it.text, title) }?.id
            startRun(retryFailed = false, cachedOnly = !active)
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
        // If the page never answers, give the button back.
        collectTimeout?.cancel()
        collectTimeout = viewModelScope.launch {
            delay(COLLECT_TIMEOUT_MILLIS)
            if (sessionId == newSession && session == null && _ui.value == ArticleTranslationUi.Collecting) {
                log.w { "the page did not report its blocks" }
                _ui.value = ArticleTranslationUi.Idle
            }
        }
    }

    private fun startRun(retryFailed: Boolean, cachedOnly: Boolean = false) {
        val current = session ?: return
        val sid = sessionId ?: return
        stopRun()
        val runId = "r${ids.incrementAndGet()}"
        val s = JSONObject.quote(sid)
        val r = JSONObject.quote(runId)
        commands.trySend("window.feedflowTranslation.beginRun($s, $r);")
        if (!cachedOnly) _ui.value = ArticleTranslationUi.Translating(0, 0)
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
                val summary = current.run(retryFailed, cachedOnly) { update ->
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
                    "run cachedOnly=$cachedOnly done=${summary.done} failed=${summary.failed} interrupted=${summary.interrupted} " +
                        "skipped=${summary.skipped} modelMissing=${summary.modelMissing}"
                }
                _ui.value = if (cachedOnly) {
                    // Shown from the cache on opening; the button still offers to translate (the rest).
                    ArticleTranslationUi.Idle
                } else {
                    ArticleTranslationUi.Finished(summary.canRetry || summary.modelMissing, summary.modelMissing)
                }
                // fork (Phase 4h): on opening, show the title translation of the article list
                val titleBlock = titleBlockId
                if (cachedOnly && titleBlock != null && current.states.value[titleBlock] != BlockState.DONE) {
                    showListTitleTranslation(titleBlock, s, r)
                }
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

    /**
     * fork (Phase 4h): puts the title translation used in the article list under the title,
     * translating the title first when it has none yet. Same decision as the list (feed setting,
     * feed language), and the same cache keys, so no language tag is passed (the list passes none).
     */
    private suspend fun showListTitleTranslation(blockId: Int, s: String, r: String) {
        val id = articleId ?: return
        val text = title?.takeIf { it.isNotBlank() } ?: return
        if (!setup.prefs.enabled.value) return
        val feedId = setup.feedIdOf(id) ?: return
        val item = TitleItem(urlHash = id, title = text, feedId = feedId)
        var translated = setup.titleTranslator.lookup(listOf(item))[id]
        val from = if (translated != null) "cache" else "translated"
        if (translated == null) {
            val pending = setup.titleTranslator.request(listOf(item), owner = titleOwner, priority = Priority.VISIBLE)
            if (pending.isEmpty()) {
                log.d { "title: nothing to show (not translated for this feed or no model)" }
                return
            }
            pending.awaitAll()
            translated = setup.titleTranslator.lookup(listOf(item))[id]
        }
        if (translated == null || articleId != id) return
        commands.trySend("window.feedflowTranslation.apply($s, $r, $blockId, ${JSONObject.quote(translated)}, false);")
        log.i { "title shown from=$from" }
    }

    private fun sameText(a: String, b: String?): Boolean {
        fun norm(x: String) = x.trim().split(Regex("\\s+")).joinToString(" ")
        return b != null && norm(a) == norm(b)
    }

    /** Stops the running translation. Only this screen's requests are cancelled. */
    private fun stopRun() {
        job?.cancel()
        job = null
    }

    override fun onCleared() {
        stopRun()
    }

    private companion object {
        const val COLLECT_TIMEOUT_MILLIS = 5_000L
    }
}
