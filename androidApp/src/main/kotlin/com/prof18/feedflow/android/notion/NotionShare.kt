package com.prof18.feedflow.android.notion

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import co.touchlab.kermit.Logger

/**
 * fork (Phase 4d): sends an article straight to the Notion app's share
 * screen (its quick capture), skipping the system share sheet. Saving is
 * done in Notion. The extras are the same as upstream's share button.
 */
private const val NOTION_PACKAGE = "notion.id"

private fun notionIntent(title: String?, url: String): Intent {
    val subject = if (!title.isNullOrBlank()) title else url
    return Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, url)
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TITLE, subject)
        setPackage(NOTION_PACKAGE)
    }
}

/** True when the Notion app is installed and accepts shared links. */
internal fun Context.canShareToNotion(): Boolean =
    packageManager.queryIntentActivities(notionIntent(null, "https://example.com"), 0).isNotEmpty()

internal fun Context.shareToNotion(title: String?, url: String) {
    val log = Logger.withTag("NotionShare")
    try {
        startActivity(notionIntent(title, url))
        log.i { "sent to Notion" }
    } catch (e: ActivityNotFoundException) {
        log.w { "Notion app not found" }
        Toast.makeText(this, "Notionアプリが見つかりません", Toast.LENGTH_SHORT).show()
    }
}
