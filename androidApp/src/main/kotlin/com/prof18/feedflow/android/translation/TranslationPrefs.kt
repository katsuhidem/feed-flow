package com.prof18.feedflow.android.translation

import android.content.Context
import io.github.katsuhidem.translation.TranslationConfig
import io.github.katsuhidem.translation.TranslationStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** fork (Phase 2): translation settings, in app-private preferences. */
class TranslationPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("translation", Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, true))

    /** Title translation on or off. It only runs once a model is installed. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _style = MutableStateFlow(
        runCatching { TranslationStyle.valueOf(prefs.getString(KEY_STYLE, null) ?: "") }
            .getOrDefault(TranslationStyle.UNSPECIFIED),
    )
    val style: StateFlow<TranslationStyle> = _style.asStateFlow()

    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        _enabled.value = value
    }

    fun setStyle(value: TranslationStyle) {
        prefs.edit().putString(KEY_STYLE, value.name).apply()
        _style.value = value
    }

    /**
     * Tuning read once at start: inference threads and their nice value. Null means the default.
     * There is no UI for these; they were used to measure scrolling during translation (see
     * docs/STATUS.md, 2026-11-16) and neither changed the result, so the defaults stay.
     */
    val debugThreads: Int? = prefs.getInt(KEY_DEBUG_THREADS, 0).takeIf { it > 0 }
    val debugThreadPriority: Int? = prefs.getInt(KEY_DEBUG_PRIORITY, NO_VALUE).takeIf { it != NO_VALUE }

    private val _debugCheckArticle = MutableStateFlow(prefs.getBoolean(KEY_DEBUG_CHECK_ARTICLE, false))

    /** Debug builds: reader mode shows the built-in check article (see TranslationDebug). */
    val debugCheckArticle: StateFlow<Boolean> = _debugCheckArticle.asStateFlow()

    private val _debugInjectFailures = MutableStateFlow(prefs.getBoolean(KEY_DEBUG_INJECT_FAILURES, false))

    /** Debug builds: the marked paragraphs of the check article fail. */
    val debugInjectFailures: StateFlow<Boolean> = _debugInjectFailures.asStateFlow()

    fun setDebugCheckArticle(value: Boolean) {
        prefs.edit().putBoolean(KEY_DEBUG_CHECK_ARTICLE, value).apply()
        _debugCheckArticle.value = value
    }

    fun setDebugInjectFailures(value: Boolean) {
        prefs.edit().putBoolean(KEY_DEBUG_INJECT_FAILURES, value).apply()
        _debugInjectFailures.value = value
    }

    fun config(): TranslationConfig = TranslationConfig(style = _style.value)

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_STYLE = "style"
        const val KEY_DEBUG_THREADS = "debug_threads"
        const val KEY_DEBUG_PRIORITY = "debug_thread_priority"
        const val KEY_DEBUG_CHECK_ARTICLE = "debug_check_article"
        const val KEY_DEBUG_INJECT_FAILURES = "debug_inject_failures"
        const val NO_VALUE = Int.MIN_VALUE
    }
}
