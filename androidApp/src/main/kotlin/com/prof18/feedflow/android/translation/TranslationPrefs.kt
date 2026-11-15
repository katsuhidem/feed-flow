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

    fun config(): TranslationConfig = TranslationConfig(style = _style.value)

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_STYLE = "style"
    }
}
