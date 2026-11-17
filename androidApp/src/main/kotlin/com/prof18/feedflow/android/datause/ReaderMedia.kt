package com.prof18.feedflow.android.datause

import android.webkit.WebView
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.touchlab.kermit.Logger

/**
 * fork (Phase 4a): whether the article shown in reader mode may load its
 * images and video, and the action behind the "load images" button.
 */
class ReaderMediaBlock(
    /** True while this article must not load images or video from the network. */
    val blocked: Boolean,
    /** The user asks for the images of this article. */
    val load: () -> Unit,
)

/**
 * @param articleKey identifies the shown article (or the page shown in its place); the user's
 *   request to load images holds for that article only and ends when another one is shown
 */
@Composable
internal fun rememberReaderMediaBlock(setup: DataUseSetup, articleKey: String?): ReaderMediaBlock {
    val mediaBlocked by setup.mediaBlocked.collectAsStateWithLifecycle()
    var allowedKey by rememberSaveable { mutableStateOf<String?>(null) }
    return ReaderMediaBlock(
        blocked = mediaBlocked && articleKey != null && allowedKey != articleKey,
        load = {
            Logger.withTag("DataUse").i { "reader media: load requested" }
            allowedKey = articleKey
        },
    )
}

/** The toolbar button shown while the article's images are held back. */
@Composable
internal fun LoadImagesButton(onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text("画像を読み込む") }
}

/**
 * Applies the block to a reader WebView. Safe to call on every recomposition: it only acts on a change.
 *
 * @param localPage true for reader mode, whose page is handed to the WebView as text: everything
 *   it would fetch is an image, a video or an embed, so all network loads are blocked. False for
 *   the original website, which has to come from the network: only its images are blocked.
 */
internal fun WebView.applyMediaBlock(blocked: Boolean, localPage: Boolean) {
    val settings = settings
    val wasBlocked = settings.blockNetworkImage
    if (wasBlocked == blocked && (!localPage || settings.blockNetworkLoads == blocked)) return
    settings.blockNetworkImage = blocked
    if (localPage) settings.blockNetworkLoads = blocked
    Logger.withTag("DataUse").i { "reader media: blocked=$blocked local_page=$localPage" }
    // Images that were only held back start by themselves; what already failed is asked for again.
    if (wasBlocked && !blocked && localPage) evaluateJavascript(RELOAD_MEDIA_JS, null)
}

private const val RELOAD_MEDIA_JS = """
(function () {
  var images = document.querySelectorAll('img');
  for (var i = 0; i < images.length; i++) {
    var img = images[i];
    if (img.complete && img.naturalWidth > 0) continue;
    var srcset = img.getAttribute('srcset');
    var src = img.getAttribute('src');
    if (srcset) img.setAttribute('srcset', srcset);
    if (src) img.setAttribute('src', src);
  }
  var frames = document.querySelectorAll('iframe[src]');
  for (var k = 0; k < frames.length; k++) frames[k].setAttribute('src', frames[k].getAttribute('src'));
  var videos = document.querySelectorAll('video, audio');
  for (var v = 0; v < videos.length; v++) { try { videos[v].load(); } catch (e) {} }
})();
"""
