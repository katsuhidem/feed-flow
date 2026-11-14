package com.prof18.feedflow.android.tailnet

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.prof18.feedflow.android.BuildConfig
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.katsuhidem.tailnet.TailnetManager
import io.github.katsuhidem.tailnet.TailnetMode
import io.github.katsuhidem.tailnet.TailnetState

/**
 * fork (Phase 1): connection mode, login and status for the embedded
 * Tailscale node, shown on the FreshRSS account screen. Strings are not
 * translated: this is a personal build.
 */
@Composable
internal fun TailnetSection(manager: TailnetManager, modifier: Modifier = Modifier) {
    val mode by manager.mode.collectAsStateWithLifecycle()
    val state by manager.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = "Tailscale", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "アプリ内のTailscaleで接続する",
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = mode == TailnetMode.EMBEDDED,
                onCheckedChange = { on ->
                    manager.setMode(if (on) TailnetMode.EMBEDDED else TailnetMode.SYSTEM)
                    if (on) manager.startLogin()
                },
            )
        }

        if (mode == TailnetMode.SYSTEM) {
            Text(
                text = "公式のTailscaleアプリの接続を使います。",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Text(text = statusText(state), style = MaterialTheme.typography.bodyMedium)
            when (val s = state) {
                is TailnetState.NeedsLogin -> Button(
                    enabled = s.authUrl != null,
                    onClick = {
                        s.authUrl?.let { url ->
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    },
                ) { Text("Tailscaleにログイン") }

                TailnetState.Stopped, is TailnetState.Error -> Button(onClick = { manager.startLogin() }) {
                    Text("接続")
                }

                is TailnetState.Connected -> OutlinedButton(onClick = { manager.stop() }) { Text("切断") }
                TailnetState.Starting -> Unit
            }
            if (BuildConfig.DEBUG) {
                DebugSwitch("検証用: 停止まで10秒", manager.debugShortStop) { manager.debugShortStop = it }
                DebugSwitch("検証用: 印の上限0.5秒", manager.debugShortLease) { manager.debugShortLease = it }
            }
        }
        HorizontalDivider()
    }
}

private fun statusText(state: TailnetState): String = when (state) {
    TailnetState.Stopped -> "停止中"
    TailnetState.Starting -> "起動中…"
    is TailnetState.NeedsLogin ->
        if (state.authUrl == null) "ログインの準備中…" else "ログインが必要です。ログインして承認したら、このアプリに戻ってください。"
    is TailnetState.Connected -> "接続済み(${state.ip})"
    is TailnetState.Error -> "エラー: ${state.message}"
}

@Composable
private fun DebugSwitch(label: String, initial: Boolean, onChange: (Boolean) -> Unit) {
    var checked by remember { mutableStateOf(initial) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = {
                checked = it
                onChange(it)
            },
        )
    }
}
