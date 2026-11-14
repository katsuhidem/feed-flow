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

    private companion object {
        const val KEY_MODE = "mode"
        const val KEY_NODE_NAME = "node_name"
        const val DEFAULT_NODE_NAME = "tailfeed"
    }
}
