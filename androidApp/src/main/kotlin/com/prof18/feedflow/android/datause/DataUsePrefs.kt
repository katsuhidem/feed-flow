package com.prof18.feedflow.android.datause

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** fork (Phase 4a): settings that limit data use, in app-private preferences. */
class DataUsePrefs(context: Context) {
    private val prefs = context.getSharedPreferences("data_use", Context.MODE_PRIVATE)

    private val _prefetchWifiOnly = MutableStateFlow(prefs.getBoolean(KEY_PREFETCH_WIFI_ONLY, true))

    /** Article text is prefetched only on Wi-Fi. Prefetching itself is switched on by the upstream setting. */
    val prefetchWifiOnly: StateFlow<Boolean> = _prefetchWifiOnly.asStateFlow()

    fun setPrefetchWifiOnly(value: Boolean) {
        prefs.edit().putBoolean(KEY_PREFETCH_WIFI_ONLY, value).apply()
        _prefetchWifiOnly.value = value
    }

    private companion object {
        const val KEY_PREFETCH_WIFI_ONLY = "prefetch_wifi_only"
    }
}
