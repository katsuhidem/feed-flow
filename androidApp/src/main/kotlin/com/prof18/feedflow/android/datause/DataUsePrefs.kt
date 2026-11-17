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

    private val _blockMediaOnMobile = MutableStateFlow(prefs.getBoolean(KEY_BLOCK_MEDIA_ON_MOBILE, false))

    /** Images and video are not loaded on mobile connections; reader mode offers a button to load them. */
    val blockMediaOnMobile: StateFlow<Boolean> = _blockMediaOnMobile.asStateFlow()

    fun setBlockMediaOnMobile(value: Boolean) {
        prefs.edit().putBoolean(KEY_BLOCK_MEDIA_ON_MOBILE, value).apply()
        _blockMediaOnMobile.value = value
    }

    private val _debugTreatAsMobile = MutableStateFlow(prefs.getBoolean(KEY_DEBUG_TREAT_AS_MOBILE, false))

    /** Debug builds: any connection counts as mobile, so the checks do not need Wi-Fi switched off. */
    val debugTreatAsMobile: StateFlow<Boolean> = _debugTreatAsMobile.asStateFlow()

    fun setDebugTreatAsMobile(value: Boolean) {
        prefs.edit().putBoolean(KEY_DEBUG_TREAT_AS_MOBILE, value).apply()
        _debugTreatAsMobile.value = value
    }

    private companion object {
        const val KEY_BLOCK_MEDIA_ON_MOBILE = "block_media_on_mobile"
        const val KEY_DEBUG_TREAT_AS_MOBILE = "debug_treat_as_mobile"
        const val KEY_PREFETCH_WIFI_ONLY = "prefetch_wifi_only"
    }
}
