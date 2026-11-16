package com.prof18.feedflow.android.translation

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.prof18.feedflow.android.BuildConfig
import io.github.katsuhidem.translation.ModelCatalog
import io.github.katsuhidem.translation.ModelState
import io.github.katsuhidem.translation.TranslationStyle

/**
 * fork (Phase 2): translation settings, shown at the end of the reading
 * behaviour screen. Strings are not translated: this is a personal build.
 */
@Composable
internal fun TranslationSettingsSection(setup: TranslationSetup, modifier: Modifier = Modifier) {
    val enabled by setup.prefs.enabled.collectAsStateWithLifecycle()
    val style by setup.prefs.style.collectAsStateWithLifecycle()
    val modelState by setup.modelStore.state(setup.titleModel).collectAsStateWithLifecycle()
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HorizontalDivider()
        Text(text = "翻訳", style = MaterialTheme.typography.titleMedium)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "外国語のタイトルを日本語に翻訳する", modifier = Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = { setup.prefs.setEnabled(it) })
        }

        Text(text = "翻訳モデル: ${setup.titleModel.displayName}", style = MaterialTheme.typography.bodyMedium)
        Text(text = modelStatusText(modelState), style = MaterialTheme.typography.bodySmall)
        when (val state = modelState) {
            is ModelState.Downloading -> {
                LinearProgressIndicator(
                    progress = { (state.bytes.toFloat() / state.totalBytes).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // The same state is shown for a paused download; "continue" restarts from the part on disk.
                    Button(onClick = { setup.startDownload() }) { Text("続きをダウンロード") }
                    OutlinedButton(onClick = { setup.cancelDownload() }) { Text("中断") }
                }
            }

            ModelState.Verifying -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            ModelState.Installed -> OutlinedButton(onClick = { setup.deleteModel() }) { Text("モデルを削除") }
            ModelState.NotInstalled, is ModelState.Failed -> Button(onClick = { setup.startDownload() }) {
                Text("ダウンロード(約${setup.titleModel.sizeBytes / BYTES_PER_MB}MB)")
            }
        }

        Text(text = "訳文の文体", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StyleChip("指定なし", TranslationStyle.UNSPECIFIED, style) { setup.prefs.setStyle(it) }
            StyleChip("常体(だ・である)", TranslationStyle.PLAIN, style) { setup.prefs.setStyle(it) }
            StyleChip("敬体(です・ます)", TranslationStyle.POLITE, style) { setup.prefs.setStyle(it) }
        }

        Text(
            text = "翻訳モデル PLaMo 3 Translate は ${ModelCatalog.LICENSE_NAME} の下で提供されています。" +
                "翻訳は端末の中で行われ、記事の内容は外部に送信されません。",
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(
            onClick = {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(ModelCatalog.LICENSE_URL))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            },
        ) { Text("${ModelCatalog.LICENSE_NAME} を表示") }

        if (BuildConfig.DEBUG) {
            val checkArticle by setup.prefs.debugCheckArticle.collectAsStateWithLifecycle()
            val injectFailures by setup.prefs.debugInjectFailures.collectAsStateWithLifecycle()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "検証用: リーダーモードで確認用の記事を表示する",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = checkArticle, onCheckedChange = { setup.prefs.setDebugCheckArticle(it) })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "検証用: 確認用の記事の、印のある段落を失敗させる",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = injectFailures, onCheckedChange = { setup.prefs.setDebugInjectFailures(it) })
            }
        }
    }
}

@Composable
private fun StyleChip(
    label: String,
    value: TranslationStyle,
    selected: TranslationStyle,
    onSelect: (TranslationStyle) -> Unit,
) {
    FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(label) })
}

private fun modelStatusText(state: ModelState): String = when (state) {
    ModelState.NotInstalled -> "未インストール"
    is ModelState.Downloading ->
        "ダウンロード: ${state.bytes / BYTES_PER_MB}MB / ${state.totalBytes / BYTES_PER_MB}MB"
    ModelState.Verifying -> "ファイルを検証中…"
    ModelState.Installed -> "インストール済み"
    is ModelState.Failed -> "失敗: ${state.message}"
}

private const val BYTES_PER_MB = 1_000_000L
