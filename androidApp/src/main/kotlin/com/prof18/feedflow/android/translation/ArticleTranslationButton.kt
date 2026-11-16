package com.prof18.feedflow.android.translation

import android.widget.Toast
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * fork (Phase 3): the reader toolbar's translation button. One button with
 * three actions: translate, stop while a translation runs, and retry when
 * blocks failed or were cancelled.
 */
@Composable
internal fun ArticleTranslationButton(
    ui: ArticleTranslationUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val modelMissing = (ui as? ArticleTranslationUi.Finished)?.modelMissing == true
    LaunchedEffect(modelMissing) {
        if (modelMissing) {
            Toast.makeText(
                context,
                "翻訳モデルが未インストールです(設定 → 読み方 → 翻訳)",
                Toast.LENGTH_LONG,
            ).show()
        }
    }
    FilledTonalButton(
        modifier = modifier.padding(end = 8.dp),
        enabled = ui != ArticleTranslationUi.Collecting,
        onClick = onClick,
    ) {
        Text(
            when (ui) {
                ArticleTranslationUi.Idle, ArticleTranslationUi.Collecting -> "翻訳"
                is ArticleTranslationUi.Translating ->
                    if (ui.total > 0) "中止 ${ui.done}/${ui.total}" else "中止"
                is ArticleTranslationUi.Finished -> if (ui.canRetry) "再試行" else "翻訳"
            },
        )
    }
}
