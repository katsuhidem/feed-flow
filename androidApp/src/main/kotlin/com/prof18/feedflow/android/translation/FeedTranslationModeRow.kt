package com.prof18.feedflow.android.translation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.katsuhidem.translation.FeedTranslationMode
import org.koin.compose.koinInject

/**
 * fork (Phase 2): the translation setting of one feed, shown on the feed's
 * edit screen. It is saved at once, independently of the screen's save button.
 */
@Composable
internal fun FeedTranslationModeRow(feedId: String, modifier: Modifier = Modifier) {
    val setup = koinInject<TranslationSetup>()
    var mode by remember(feedId) { mutableStateOf<FeedTranslationMode?>(null) }
    LaunchedEffect(feedId) { mode = setup.feedMode(feedId) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text = "タイトルの翻訳")
        Text(
            text = "自動では、日本語のフィードは翻訳しません。",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((label, value) in OPTIONS) {
                FilterChip(
                    selected = mode == value,
                    enabled = mode != null,
                    onClick = {
                        mode = value
                        setup.setFeedMode(feedId, value)
                    },
                    label = { Text(label) },
                )
            }
        }
    }
}

private val OPTIONS = listOf(
    "自動" to FeedTranslationMode.AUTO,
    "常に翻訳する" to FeedTranslationMode.ON,
    "翻訳しない" to FeedTranslationMode.OFF,
)
