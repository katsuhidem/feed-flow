package com.prof18.feedflow.android.duplicates

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.prof18.feedflow.shared.domain.feed.DuplicateReadMarker

/**
 * fork (Phase 4e): the switch for marking duplicate articles as read, shown at the end of the
 * reading behaviour screen. Strings are not translated: this is a personal build.
 */
@Composable
internal fun DuplicateReadSettingsSection(marker: DuplicateReadMarker, modifier: Modifier = Modifier) {
    var enabled by remember { mutableStateOf(marker.enabled) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HorizontalDivider()
        Text(text = "重複した記事", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "同じURLの記事を自動で既読にする", modifier = Modifier.weight(1f))
            Switch(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    marker.enabled = it
                },
            )
        }
        Text(
            text = "同期の後に、同じURLの記事が別のフィードなどから入っていたら、あとから入った方を既読にします。" +
                "どれかを読んでいれば、残りも既読にします。",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
