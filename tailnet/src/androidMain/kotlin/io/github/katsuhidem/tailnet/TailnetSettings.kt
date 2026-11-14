package io.github.katsuhidem.tailnet

import android.content.Context

/** Connection mode and node name, kept in app-private preferences. */
class TailnetSettings(context: Context) {
    private val prefs = context.getSharedPreferences("tailnet", Context.MODE_PRIVATE)

    var mode: TailnetMode
        get() = runCatching { TailnetMode.valueOf(prefs.getString(KEY_MODE, null) ?: "") }
            .getOrDefault(TailnetMode.SYSTEM)
        set(value) = prefs.edit().putString(KEY_MODE, value.name).apply()

    var nodeName: String
        get() = prefs.getString(KEY_NODE_NAME, null) ?: DEFAULT_NODE_NAME
        set(value) = prefs.edit().putString(KEY_NODE_NAME, value).apply()

    /** Debug builds only: 10 s stop delay, for the Phase 1 checks. */
    var debugShortStop: Boolean
        get() = prefs.getBoolean(KEY_DEBUG_STOP, false)
        set(value) = prefs.edit().putBoolean(KEY_DEBUG_STOP, value).apply()

    /** Debug builds only: 0.5 s lease limit, for the Phase 1 checks. */
    var debugShortLease: Boolean
        get() = prefs.getBoolean(KEY_DEBUG_LEASE, false)
        set(value) = prefs.edit().putBoolean(KEY_DEBUG_LEASE, value).apply()

    /** Debug builds only: wait 2 s before reading each FreshRSS response body (a slow server). */
    var debugSlowBody: Boolean
        get() = prefs.getBoolean(KEY_DEBUG_SLOW, false)
        set(value) = prefs.edit().putBoolean(KEY_DEBUG_SLOW, value).apply()

    private companion object {
        const val KEY_DEBUG_SLOW = "debug_slow_body"
        const val KEY_DEBUG_STOP = "debug_short_stop"
        const val KEY_DEBUG_LEASE = "debug_short_lease"
        const val KEY_MODE = "mode"
        const val KEY_NODE_NAME = "node_name"
        const val DEFAULT_NODE_NAME = "tailfeed"
    }
}
