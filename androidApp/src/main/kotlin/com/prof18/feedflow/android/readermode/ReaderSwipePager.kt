package com.prof18.feedflow.android.readermode

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import co.touchlab.kermit.Logger
import coil3.compose.AsyncImage
import com.prof18.feedflow.shared.domain.feed.ArticlePreview
import com.prof18.feedflow.shared.domain.feed.ReaderNeighbors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * fork (Phase 4i): what the reader page says about the finger it has just seen. The page's
 * script reports at touchstart whether the touch started where a sideways drag belongs to the
 * page (code blocks, tables) or while text is selected.
 */
@Stable
class ReaderSwipeState {
    internal var pageBlocked: Boolean? by mutableStateOf(null)

    fun onPageTouch(blocked: Boolean) {
        pageBlocked = blocked
    }
}

private val log = Logger.withTag("ReaderSwipe")

/**
 * fork (Phase 4i): lays the open article and previews of the articles before and after it side
 * by side and moves them with the finger (docs/PLAN.md "Phase 4i"). The finger is watched before
 * the WebView sees it; once the drag is clearly sideways it is taken from the WebView.
 *
 * @param currentId the article shown, null while nothing is shown
 * @param enabled false where swiping is not offered (loading, the original website)
 * @param shownId the article whose page has finished loading; the preview of an article moved to
 *   stays in front until then
 */
@Composable
internal fun ReaderSwipePager(
    currentId: String?,
    shownId: String?,
    enabled: Boolean,
    swipeState: ReaderSwipeState,
    neighbors: ReaderNeighbors,
    contentPadding: PaddingValues,
    onNavigate: (next: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val edgePx = with(density) { EDGE_DP.dp.toPx() }
    val flickPx = with(density) { FLICK_MIN_DP.dp.toPx() }
    val flickVelocity = with(density) { FLICK_VELOCITY_DP.dp.toPx() }
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    var width by remember { mutableStateOf(0f) }
    var arriving by remember { mutableStateOf<ArticlePreview?>(null) }
    var settling by remember { mutableStateOf(false) }

    val previous by produceState<ArticlePreview?>(null, currentId) {
        value = currentId?.let { runCatching { neighbors.previous(it) }.getOrNull() }
    }
    val next by produceState<ArticlePreview?>(null, currentId) {
        value = currentId?.let { runCatching { neighbors.next(it) }.getOrNull() }
    }
    val latestPrevious by rememberUpdatedState(previous)
    val latestNext by rememberUpdatedState(next)
    val latestEnabled by rememberUpdatedState(enabled)
    val latestNavigate by rememberUpdatedState(onNavigate)

    // The preview of the article moved to stays in front until its page is ready.
    val target = arriving
    LaunchedEffect(target?.id, currentId, shownId, enabled) {
        if (target == null) return@LaunchedEffect
        if (currentId == target.id && (shownId == target.id || !enabled)) {
            arriving = null
        } else {
            delay(ARRIVAL_TIMEOUT_MS)
            log.w { "the page of the article moved to did not show in time" }
            arriving = null
        }
    }

    fun settle(dx: Float, vx: Float) {
        val toNext = dx < 0
        val neighbor = if (toNext) latestNext else latestPrevious
        val far = abs(dx) >= width * GO_FRACTION
        val flick = abs(vx) > flickVelocity && abs(dx) > flickPx && (vx < 0) == toNext
        settling = true
        scope.launch {
            if (neighbor != null && (far || flick)) {
                offset.animateTo(if (toNext) -width else width, tween(SLIDE_MS, easing = FastOutSlowInEasing))
                log.i { "swipe ${if (toNext) "next" else "previous"}" }
                arriving = neighbor
                latestNavigate(toNext)
                offset.snapTo(0f)
            } else {
                offset.animateTo(0f, tween(BACK_MS, easing = FastOutSlowInEasing))
            }
            settling = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { width = it.width.toFloat() }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitPointerEvent(PointerEventPass.Initial).changes
                            .firstOrNull { it.changedToDownIgnoreConsumed() } ?: continue
                        if (!latestEnabled || arriving != null || settling ||
                            down.position.x < edgePx || down.position.x > width - edgePx
                        ) {
                            continue
                        }
                        swipeState.pageBlocked = null
                        val start = down.position
                        val tracker = VelocityTracker()
                        tracker.addPosition(down.uptimeMillis, start)
                        var dragging = false
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.count { it.pressed } > 1) {
                                if (dragging) settle(0f, 0f)
                                break
                            }
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                if (dragging) {
                                    change.consume()
                                    settle(change.position.x - start.x, tracker.calculateVelocity().x)
                                }
                                break
                            }
                            tracker.addPosition(change.uptimeMillis, change.position)
                            val dx = change.position.x - start.x
                            val dy = change.position.y - start.y
                            if (!dragging) {
                                if (abs(dy) > touchSlop && abs(dy) >= abs(dx)) break
                                if (abs(dx) <= touchSlop || abs(dx) <= abs(dy) * LOCK_RATIO) continue
                                val elapsed = change.uptimeMillis - down.uptimeMillis
                                // A long press starts a text selection: its drag belongs to the page.
                                if (elapsed > LONG_PRESS_MS) break
                                val blocked = swipeState.pageBlocked
                                if (blocked == true) break
                                if (blocked == null && elapsed < VERDICT_WAIT_MS) continue
                                dragging = true
                            }
                            change.consume()
                            val allowed = if (dx < 0) latestNext != null else latestPrevious != null
                            scope.launch { offset.snapTo(if (allowed) dx else dx * RESIST) }
                        }
                    }
                }
            },
    ) {
        Box(Modifier.fillMaxSize().graphicsLayer { translationX = offset.value }) { content() }
        val moved = offset.value != 0f
        previous?.takeIf { moved }?.let {
            ArticlePreviewPage(it, contentPadding, Modifier.graphicsLayer { translationX = offset.value - width })
        }
        next?.takeIf { moved }?.let {
            ArticlePreviewPage(it, contentPadding, Modifier.graphicsLayer { translationX = offset.value + width })
        }
        arriving?.let { ArticlePreviewPage(it, contentPadding) }
    }
}

/** A stand-in for an article page: its title, feed, date, image and description. */
@Composable
private fun ArticlePreviewPage(preview: ArticlePreview, contentPadding: PaddingValues, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(contentPadding),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 700.dp)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = preview.title.orEmpty(),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
            )
            preview.translatedTitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = listOfNotNull(preview.feedTitle, preview.dateString).joinToString("  "),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            preview.imageUrl?.let {
                AsyncImage(
                    model = it,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                        .clip(RoundedCornerShape(7.dp)),
                )
            }
            preview.summary?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 14,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private const val EDGE_DP = 24
private const val FLICK_MIN_DP = 40
private const val FLICK_VELOCITY_DP = 800
private const val GO_FRACTION = 0.22f
private const val LOCK_RATIO = 1.2f
private const val RESIST = 0.25f
private const val SLIDE_MS = 220
private const val BACK_MS = 180
private const val LONG_PRESS_MS = 400L
private const val VERDICT_WAIT_MS = 150L
private const val ARRIVAL_TIMEOUT_MS = 4_000L
