package com.prof18.feedflow.android.datause

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.prof18.feedflow.android.BuildConfig

/**
 * fork (Phase 4a): data use settings, shown at the end of the reading
 * behaviour screen. Strings are not translated: this is a personal build.
 */
@Composable
internal fun DataUseSettingsSection(setup: DataUseSetup, modifier: Modifier = Modifier) {
    val prefetchWifiOnly by setup.prefs.prefetchWifiOnly.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HorizontalDivider()
        Text(text = "通信量", style = MaterialTheme.typography.titleMedium)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "本文の先読みは Wi-Fi のときだけ行う", modifier = Modifier.weight(1f))
            Switch(checked = prefetchWifiOnly, onCheckedChange = { setup.setPrefetchWifiOnly(it) })
        }
        Text(
            text = "本文の先読み(オフライン用の保存)そのものは、この画面の上のほうにある" +
                "「フィードの更新時にオフライン用のコピーを作る」でオンにします。" +
                "モバイル回線で止めた分は、Wi-Fi につながったときに取得します。",
            style = MaterialTheme.typography.bodySmall,
        )

        val blockMedia by setup.prefs.blockMediaOnMobile.collectAsStateWithLifecycle()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "モバイル回線では画像と動画を読み込まない", modifier = Modifier.weight(1f))
            Switch(checked = blockMedia, onCheckedChange = { setup.prefs.setBlockMediaOnMobile(it) })
        }
        Text(
            text = "一覧では、すでに端末に保存されている画像だけを表示します。" +
                "リーダーモードでは、ツールバーの「画像を読み込む」を押した記事だけ読み込みます。",
            style = MaterialTheme.typography.bodySmall,
        )

        if (BuildConfig.DEBUG) {
            val treatAsMobile by setup.prefs.debugTreatAsMobile.collectAsStateWithLifecycle()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "検証用: いまの回線をモバイル回線として扱う",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = treatAsMobile, onCheckedChange = { setup.prefs.setDebugTreatAsMobile(it) })
            }
        }
    }
}
