package com.prof18.feedflow.android.readermode

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.touchlab.kermit.Logger
import coil3.compose.AsyncImage
import com.prof18.feedflow.shared.domain.feed.ArticlePreview
import com.prof18.feedflow.shared.domain.feed.ReaderNeighbors
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
 * The reader's text settings, so a preview looks like the page it stands for. The page sizes
 * are CSS pixels, which are dp in the WebView.
 */
internal data class ReaderPreviewText(
    val fontSize: Int,
    val lineHeightStep: Int,
    val titleFontSize: Int,
    /** The reader's fonts: the body for the text, the headline for the title and the feed name (h1, h4). */
    val bodyFont: FontFamily = FontFamily.Default,
    val headlineFont: FontFamily = FontFamily.Default,
)

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
    text: ReaderPreviewText,
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
            // A moment more, so the title translation and cached images are on the page too.
            delay(SETTLE_MS)
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
            ArticlePreviewPage(it, contentPadding, text, Modifier.graphicsLayer { translationX = offset.value - width })
        }
        next?.takeIf { moved }?.let {
            ArticlePreviewPage(it, contentPadding, text, Modifier.graphicsLayer { translationX = offset.value + width })
        }
        arriving?.let { ArticlePreviewPage(it, contentPadding, text) }
    }
}

/**
 * A stand-in for an article page, laid out like the reader page (ReaderModeHtmlAndCss): the
 * title, its translation, the feed name, the image and the description, in the same sizes.
 */
@Composable
private fun ArticlePreviewPage(
    preview: ArticlePreview,
    contentPadding: PaddingValues,
    text: ReaderPreviewText,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    // CSS px are dp in the page; dp.toSp keeps them dp whatever the system font scale.
    fun px(value: Float) = with(density) { value.dp.toSp() }
    val body = text.fontSize.toFloat()
    // The app's text styles add letter spacing; the page has none. The fonts are the reader's: with
    // the system font, both draw Japanese with Samsung's font, which has no bold, and each makes its
    // own fake bold for the headings (weight 800); Noto Sans CJK JP has the real weights in both.
    // Lines are as tall as the CSS line-height, with the text in the middle (see PageText).
    val cssLines = LineHeightStyle(alignment = LineHeightStyle.Alignment.Center, trim = LineHeightStyle.Trim.None)
    val bodyStyle = TextStyle(
        fontFamily = text.bodyFont,
        fontSize = px(body),
        lineHeight = px(body * (LINE_HEIGHT_BASE_TENTHS + text.lineHeightStep) / 10f),
        letterSpacing = 0.sp,
        lineHeightStyle = cssLines,
    )
    val titleStyle = TextStyle(
        fontFamily = text.headlineFont,
        fontSize = px(text.titleFontSize.toFloat()),
        lineHeight = px(text.titleFontSize * 1.2f),
        letterSpacing = 0.sp,
        fontWeight = FontWeight.ExtraBold,
        lineHeightStyle = cssLines,
    )
    val layoutDirection = LocalLayoutDirection.current
    // The page draws under the toolbar and starts with a spacer of the top padding minus 40dp
    // (ReaderMode), then the body margin of 8px.
    val top = (contentPadding.calculateTopPadding() - 40.dp).coerceAtLeast(0.dp) + 8.dp
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(
                start = contentPadding.calculateStartPadding(layoutDirection),
                end = contentPadding.calculateEndPadding(layoutDirection),
                top = top,
            ),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 8.dp)
                // The page's max-width of 700px is for the text: its 16px padding is outside it.
                .widthIn(max = (700 + 16 * 2).dp)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            PageText(
                text = preview.title.orEmpty(),
                style = titleStyle,
                maxLines = 6,
                modifier = Modifier.padding(top = 16.dp, bottom = 16.dp),
            )
            preview.translatedTitle?.let {
                // As the translation under the title in the page (h1 + .__ff_tr): bold, a 3px rule on
                // the left and 0.7em after it, a quieter colour, 1em below (then the site name's 1.33em).
                PageText(
                    text = it,
                    style = bodyStyle,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.88f),
                    maxLines = 4,
                    modifier = Modifier
                        .padding(bottom = body.dp)
                        .drawBehind {
                            drawRect(Color(0x8C808080), size = Size(3.dp.toPx(), size.height))
                        }
                        .padding(start = 3.dp + (body * 0.7f).dp),
                )
            }
            PageText(
                text = preview.feedTitle,
                // h4 in the page: the headline font at the body size, line height 1.2.
                style = bodyStyle.copy(fontFamily = text.headlineFont, lineHeight = px(body * 1.2f)),
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                modifier = Modifier.padding(vertical = with(density) { (body * 1.33f).dp }),
            )
            preview.imageUrl?.let {
                // Images in the page keep their proportions at the full width.
                AsyncImage(
                    model = it,
                    contentDescription = null,
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(7.dp)),
                )
            }
            preview.summary?.let {
                PageText(
                    text = it,
                    style = bodyStyle,
                    maxLines = 14,
                    // A paragraph's 1em margin after the image's; after the site name, it merges
                    // into the site name's larger margin.
                    modifier = Modifier.padding(top = if (preview.imageUrl != null) body.dp else 0.dp),
                )
            }
        }
    }
}

/**
 * Text laid out like a block in the reader page. The browser breaks lines greedily and makes every
 * line box exactly the CSS line-height, even when the font is taller (Noto Sans CJK is 1.45 em);
 * Compose balances the lines and lets the first and last line keep the font's own height, which
 * pushed everything below down by a few dp. The box here is the line count times the line height.
 */
@Composable
private fun PageText(
    text: String,
    style: TextStyle,
    maxLines: Int,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
) {
    val lineCount = remember { IntArray(1) }
    val lineHeightPx = with(LocalDensity.current) { style.lineHeight.toPx() }
    Text(
        text = text,
        style = style.copy(lineBreak = LineBreak.Simple),
        color = color,
        fontWeight = fontWeight,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { lineCount[0] = it.lineCount },
        modifier = modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            val height = (lineCount[0] * lineHeightPx).roundToInt()
            // Compose centres each line in its line height, as CSS does, but the first and last
            // lines keep the font's height, half the extra on each: drop it at both ends.
            val firstLineExtra = placeable.height - height
            layout(placeable.width, height) {
                placeable.place(0, -firstLineExtra / 2)
            }
        },
    )
}

private const val LINE_HEIGHT_BASE_TENTHS = 15
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
private const val SETTLE_MS = 250L
