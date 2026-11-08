package com.ebookreader.app.ui.reader

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FormatColorReset
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.ebookreader.app.data.parser.epub.ChapterTextSelection
import com.ebookreader.app.data.parser.epub.EpubBook
import com.ebookreader.app.data.parser.epub.EpubChapter
import com.ebookreader.app.data.parser.epub.EpubPage
import com.ebookreader.app.data.parser.epub.EpubPageItem
import com.ebookreader.app.data.parser.epub.EpubPaginator
import com.ebookreader.app.data.parser.epub.EpubSelectionHelper
import com.ebookreader.app.data.parser.epub.EpubTextKind
import com.ebookreader.app.domain.model.Bookmark
import com.ebookreader.app.domain.model.HighlightColor
import com.ebookreader.app.domain.model.ReaderSettings
import com.ebookreader.app.domain.model.SearchMatch
import com.ebookreader.app.domain.model.TextHighlight
import com.ebookreader.app.ui.theme.ReaderPalette
import kotlinx.coroutines.launch
import kotlin.math.abs

private data class SliceHitTarget(
    val slice: EpubPageItem.TextSlice,
    val layoutResult: TextLayoutResult,
    val coords: LayoutCoordinates
)

@Composable
fun EpubPagerView(
    epubBook: EpubBook,
    pages: List<EpubPage>,
    pagerState: PagerState,
    settings: ReaderSettings,
    palette: ReaderPalette,
    bookmarks: List<Bookmark>,
    highlights: List<TextHighlight>,
    searchMatches: List<SearchMatch>,
    activeMatchIndex: Int,
    loadImage: suspend (String) -> Bitmap?,
    onApplyHighlight: (chapterIndex: Int, startOffset: Int, endOffset: Int, selectedText: String, color: HighlightColor) -> Unit,
    onClearHighlight: (chapterIndex: Int, startOffset: Int, endOffset: Int) -> Unit,
    onBookmarkSelection: (chapterIndex: Int, startOffset: Int, endOffset: Int, selectedText: String) -> Unit,
    onToggleHud: () -> Unit,
    onSelectionActiveChanged: ((Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    var activeSelection by remember { mutableStateOf<ChapterTextSelection?>(null) }
    var selectionAnchorOffset by remember { mutableIntStateOf(0) }
    var selectionPlacedNearTop by remember { mutableStateOf(false) }

    LaunchedEffect(activeSelection) {
        onSelectionActiveChanged?.invoke(activeSelection != null)
    }

    // Clear live text selection if the user navigates to another page
    LaunchedEffect(pagerState.currentPage) {
        activeSelection = null
    }

    val pageBookmarksSet = remember(bookmarks) {
        bookmarks.filter { !it.isTextSelection }.map { it.pageIndex }.toSet()
    }

    HorizontalPager(
        state = pagerState,
        beyondViewportPageCount = 1,
        userScrollEnabled = activeSelection == null,
        modifier = modifier
            .fillMaxSize()
            .background(palette.background)
    ) { pageIndex ->
        val page = pages.getOrNull(pageIndex)
        if (page != null) {
            val chapter = epubBook.chapters.getOrNull(page.chapterIndex)
            val sliceTargets = remember(pageIndex, page) { mutableStateMapOf<Int, SliceHitTarget>() }
            var pageBoxCoords by remember(pageIndex) { mutableStateOf<LayoutCoordinates?>(null) }

            val pageMatches = remember(searchMatches, pageIndex) {
                searchMatches.filter { it.pageIndex == pageIndex }
            }
            val activeMatch = searchMatches.getOrNull(activeMatchIndex)
                ?.takeIf { it.pageIndex == pageIndex }

            val chapterHighlights = remember(highlights, page.chapterIndex) {
                highlights.filter { it.chapterIndex == page.chapterIndex }
            }
            val chapterSelectionBookmarks = remember(bookmarks, page.chapterIndex) {
                bookmarks.filter { it.isTextSelection && it.epubChapterIndex == page.chapterIndex }
            }

            val currentSelectionOnPage = activeSelection?.takeIf {
                pageIndex == pagerState.currentPage && it.chapterIndex == page.chapterIndex
            }

            val currentChapterUpdated by rememberUpdatedState(chapter)
            val currentSelectionUpdated by rememberUpdatedState(activeSelection)
            val pagesSizeUpdated by rememberUpdatedState(pages.size)

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { pageBoxCoords = it }
                    .pointerInput(pageIndex, page) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val downPos = down.position
                            val longPress = awaitLongPressOrCancellation(down.id)

                            if (longPress != null) {
                                val ch = currentChapterUpdated
                                val box = pageBoxCoords
                                if (ch != null && box != null) {
                                    val hit = resolveChapterOffsetAtPosition(
                                        touchInPageBox = longPress.position,
                                        pageBoxCoords = box,
                                        targets = sliceTargets.values
                                    )
                                    if (hit != null) {
                                        selectionAnchorOffset = hit
                                        selectionPlacedNearTop = longPress.position.y < size.height * 0.32f
                                        val (sStart, sEnd) = EpubSelectionHelper.expandToSentence(
                                            ch.plainText,
                                            hit
                                        )
                                        if (sEnd > sStart) {
                                            activeSelection = ChapterTextSelection(
                                                chapterIndex = ch.index,
                                                startCharOffset = sStart,
                                                endCharOffset = sEnd,
                                                selectedText = ch.plainText.substring(sStart, sEnd)
                                            )
                                        }
                                        var didDragMove = false
                                        drag(longPress.id) { change ->
                                            val moveDist = (change.position - longPress.position).getDistance()
                                            if (moveDist > viewConfiguration.touchSlop * 0.6f) {
                                                didDragMove = true
                                            }
                                            change.consume()
                                            if (didDragMove) {
                                                val dragHit = resolveChapterOffsetAtPosition(
                                                    touchInPageBox = change.position,
                                                    pageBoxCoords = box,
                                                    targets = sliceTargets.values
                                                )
                                                if (dragHit != null) {
                                                    val range = EpubSelectionHelper.expandRangeToWordBoundaries(
                                                        plainText = ch.plainText,
                                                        offsetA = selectionAnchorOffset,
                                                        offsetB = dragHit
                                                    )
                                                    if (range.second > range.first) {
                                                        activeSelection = ChapterTextSelection(
                                                            chapterIndex = ch.index,
                                                            startCharOffset = range.first,
                                                            endCharOffset = range.second,
                                                            selectedText = ch.plainText.substring(
                                                                range.first,
                                                                range.second
                                                            )
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            } else {
                                val changes = currentEvent.changes
                                val releasedCleanly = changes.all { !it.pressed } &&
                                    changes.none { it.isConsumed }
                                val upPos = changes.firstOrNull()?.position ?: downPos
                                val withinSlop = (upPos - downPos).getDistance() <= viewConfiguration.touchSlop * 1.5f

                                if (releasedCleanly && withinSlop) {
                                    if (currentSelectionUpdated != null) {
                                        activeSelection = null
                                    } else {
                                        val width = size.width.toFloat().coerceAtLeast(1f)
                                        val xFraction = downPos.x / width
                                        when {
                                            xFraction < 0.20f -> {
                                                val prev = pagerState.currentPage - 1
                                                if (prev >= 0) {
                                                    coroutineScope.launch { pagerState.animateScrollToPage(prev) }
                                                } else {
                                                    onToggleHud()
                                                }
                                            }
                                            xFraction > 0.80f -> {
                                                val next = pagerState.currentPage + 1
                                                if (next < pagesSizeUpdated) {
                                                    coroutineScope.launch { pagerState.animateScrollToPage(next) }
                                                } else {
                                                    onToggleHud()
                                                }
                                            }
                                            else -> onToggleHud()
                                        }
                                    }
                                }
                            }
                        }
                    }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 22.dp, vertical = 12.dp)
                ) {
                    page.items.forEachIndexed { itemIdx, item ->
                        when (item) {
                            is EpubPageItem.TextSlice -> {
                                val style = EpubPaginator.textStyleForKind(item.kind, settings)
                                    .copy(color = palette.onBackground)

                                val displayAnnotated = remember(
                                    item,
                                    chapterHighlights,
                                    chapterSelectionBookmarks,
                                    pageMatches,
                                    activeMatch,
                                    currentSelectionOnPage,
                                    palette
                                ) {
                                    buildDecoratedSliceText(
                                        slice = item,
                                        chapterIndex = page.chapterIndex,
                                        chapterHighlights = chapterHighlights,
                                        chapterSelectionBookmarks = chapterSelectionBookmarks,
                                        pageMatches = pageMatches,
                                        activeMatch = activeMatch,
                                        activeSelection = currentSelectionOnPage,
                                        palette = palette
                                    )
                                }

                                val gapDp = when (item.kind) {
                                    EpubTextKind.HEADING_1,
                                    EpubTextKind.HEADING_2,
                                    EpubTextKind.HEADING_3 -> 16.dp
                                    else -> 12.dp
                                }

                                var latestLayout by remember(itemIdx, item) {
                                    mutableStateOf<TextLayoutResult?>(null)
                                }
                                var latestCoords by remember(itemIdx, item) {
                                    mutableStateOf<LayoutCoordinates?>(null)
                                }

                                if (item.kind == EpubTextKind.QUOTE) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 6.dp, bottom = gapDp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .width(3.dp)
                                                .height(24.dp)
                                                .background(palette.accent.copy(alpha = 0.6f))
                                        )
                                        Spacer(Modifier.width(11.dp))
                                        Text(
                                            text = displayAnnotated,
                                            style = style,
                                            color = palette.onBackground.copy(alpha = 0.9f),
                                            onTextLayout = { res ->
                                                latestLayout = res
                                                latestCoords?.let { c ->
                                                    sliceTargets[itemIdx] = SliceHitTarget(item, res, c)
                                                }
                                            },
                                            modifier = Modifier
                                                .weight(1f)
                                                .onGloballyPositioned { coords ->
                                                    latestCoords = coords
                                                    latestLayout?.let { res ->
                                                        sliceTargets[itemIdx] = SliceHitTarget(item, res, coords)
                                                    }
                                                }
                                        )
                                    }
                                } else {
                                    Text(
                                        text = displayAnnotated,
                                        style = style,
                                        onTextLayout = { res ->
                                            latestLayout = res
                                            latestCoords?.let { c ->
                                                sliceTargets[itemIdx] = SliceHitTarget(item, res, c)
                                            }
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = gapDp)
                                            .onGloballyPositioned { coords ->
                                                latestCoords = coords
                                                latestLayout?.let { res ->
                                                    sliceTargets[itemIdx] = SliceHitTarget(item, res, coords)
                                                }
                                            }
                                    )
                                }
                            }

                            is EpubPageItem.ImageSlice -> {
                                EpubInlineImage(
                                    zipEntryPath = item.zipEntryPath,
                                    altText = item.altText,
                                    maxHeightDp = item.maxHeightDp,
                                    loadImage = loadImage,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 12.dp)
                                )
                            }

                            EpubPageItem.DividerSlice -> {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(28.dp)
                                ) {
                                    HorizontalDivider(
                                        color = palette.subtleText.copy(alpha = 0.3f),
                                        modifier = Modifier.fillMaxWidth(0.35f)
                                    )
                                }
                            }
                        }
                    }
                }

                if (pageIndex in pageBookmarksSet) {
                    Icon(
                        imageVector = Icons.Default.Bookmark,
                        contentDescription = "Bookmarked page",
                        tint = palette.accent,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(end = 14.dp)
                            .size(24.dp)
                    )
                }

                if (currentSelectionOnPage != null && chapter != null) {
                    val hasOverlappingHighlight = chapterHighlights.any { hl ->
                        hl.startCharOffset < currentSelectionOnPage.endCharOffset &&
                            hl.endCharOffset > currentSelectionOnPage.startCharOffset
                    }
                    val isExactSelectionBookmarked = chapterSelectionBookmarks.any { bm ->
                        bm.epubCharOffset == currentSelectionOnPage.startCharOffset &&
                            bm.epubEndCharOffset == currentSelectionOnPage.endCharOffset
                    }

                    SelectionFloatingBar(
                        palette = palette,
                        hasOverlappingHighlight = hasOverlappingHighlight,
                        isSelectionBookmarked = isExactSelectionBookmarked,
                        onExpandSentence = {
                            val (s, e) = EpubSelectionHelper.expandToSentence(
                                chapter.plainText,
                                currentSelectionOnPage.startCharOffset
                            )
                            if (e > s) {
                                activeSelection = currentSelectionOnPage.copy(
                                    startCharOffset = s,
                                    endCharOffset = e,
                                    selectedText = chapter.plainText.substring(s, e)
                                )
                            }
                        },
                        onExpandParagraph = {
                            val (s, e) = EpubSelectionHelper.expandToParagraph(
                                chapter,
                                currentSelectionOnPage.startCharOffset
                            )
                            if (e > s) {
                                activeSelection = currentSelectionOnPage.copy(
                                    startCharOffset = s,
                                    endCharOffset = e,
                                    selectedText = chapter.plainText.substring(s, e)
                                )
                            }
                        },
                        onPickHighlightColor = { color ->
                            onApplyHighlight(
                                currentSelectionOnPage.chapterIndex,
                                currentSelectionOnPage.startCharOffset,
                                currentSelectionOnPage.endCharOffset,
                                currentSelectionOnPage.selectedText,
                                color
                            )
                            activeSelection = null
                        },
                        onClearHighlight = {
                            onClearHighlight(
                                currentSelectionOnPage.chapterIndex,
                                currentSelectionOnPage.startCharOffset,
                                currentSelectionOnPage.endCharOffset
                            )
                            activeSelection = null
                        },
                        onBookmarkSelection = {
                            onBookmarkSelection(
                                currentSelectionOnPage.chapterIndex,
                                currentSelectionOnPage.startCharOffset,
                                currentSelectionOnPage.endCharOffset,
                                currentSelectionOnPage.selectedText
                            )
                            activeSelection = null
                        },
                        onDismiss = { activeSelection = null },
                        modifier = Modifier
                            .align(if (selectionPlacedNearTop) Alignment.BottomCenter else Alignment.TopCenter)
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    )
                }
            }
        }
    }
}

@Composable
internal fun SelectionFloatingBar(
    palette: ReaderPalette,
    hasOverlappingHighlight: Boolean,
    isSelectionBookmarked: Boolean,
    onExpandSentence: () -> Unit,
    onExpandParagraph: () -> Unit,
    onPickHighlightColor: (HighlightColor) -> Unit,
    onClearHighlight: () -> Unit,
    onBookmarkSelection: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = palette.hudContainer,
        contentColor = palette.onHudContainer,
        tonalElevation = 8.dp,
        shadowElevation = 10.dp,
        modifier = modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
            }
        }
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Quick expand pills: Sentence | Paragraph
                ExpandScopeChip(
                    label = "Sentence",
                    palette = palette,
                    onClick = onExpandSentence
                )
                ExpandScopeChip(
                    label = "Paragraph",
                    palette = palette,
                    onClick = onExpandParagraph
                )

                Spacer(Modifier.weight(1f))

                // Bookmark selected sentence / paragraph (no margin notes)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(palette.accent.copy(alpha = 0.16f))
                        .clickable(onClick = onBookmarkSelection)
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Icon(
                        imageVector = if (isSelectionBookmarked) {
                            Icons.Default.Bookmark
                        } else {
                            Icons.Default.BookmarkBorder
                        },
                        contentDescription = "Bookmark selection",
                        tint = palette.accent,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (isSelectionBookmarked) "Saved" else "Bookmark",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.accent
                    )
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close selection",
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Highlight:",
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.onHudContainer.copy(alpha = 0.75f)
                )
                HighlightColor.entries.forEach { color ->
                    val swatchSolid = Color((color.argbHex or 0xFF000000L))
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(swatchSolid)
                            .border(1.dp, palette.onHudContainer.copy(alpha = 0.35f), CircleShape)
                            .clickable { onPickHighlightColor(color) }
                    )
                }
                if (hasOverlappingHighlight) {
                    IconButton(
                        onClick = onClearHighlight,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FormatColorReset,
                            contentDescription = "Remove highlight",
                            tint = palette.onHudContainer,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExpandScopeChip(
    label: String,
    palette: ReaderPalette,
    onClick: () -> Unit
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = palette.onHudContainer,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(palette.onHudContainer.copy(alpha = 0.1f))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp)
    )
}

@Composable
private fun EpubInlineImage(
    zipEntryPath: String,
    altText: String?,
    maxHeightDp: Float,
    loadImage: suspend (String) -> Bitmap?,
    modifier: Modifier = Modifier
) {
    var bitmap by remember(zipEntryPath) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(zipEntryPath) {
        bitmap = loadImage(zipEntryPath)
    }

    val loaded = bitmap
    if (loaded != null) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier
        ) {
            Image(
                bitmap = loaded.asImageBitmap(),
                contentDescription = altText,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .heightIn(max = maxHeightDp.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
        }
    }
}

private fun resolveChapterOffsetAtPosition(
    touchInPageBox: Offset,
    pageBoxCoords: LayoutCoordinates,
    targets: Collection<SliceHitTarget>
): Int? {
    if (!pageBoxCoords.isAttached || targets.isEmpty()) return null
    val validTargets = targets.filter { it.coords.isAttached }
    if (validTargets.isEmpty()) return null

    var bestTarget: SliceHitTarget? = null
    var bestDistanceY = Float.MAX_VALUE
    var bestLocalOffset = Offset.Zero

    for (target in validTargets) {
        val local = runCatching {
            target.coords.localPositionOf(pageBoxCoords, touchInPageBox)
        }.getOrNull() ?: continue
        val w = target.coords.size.width.toFloat()
        val h = target.coords.size.height.toFloat()
        val dy = when {
            local.y < 0f -> -local.y
            local.y > h -> local.y - h
            else -> 0f
        }
        val dx = when {
            local.x < 0f -> -local.x
            local.x > w -> local.x - w
            else -> 0f
        }
        val score = dy * 2f + dx * 0.25f
        if (score < bestDistanceY) {
            bestDistanceY = score
            bestTarget = target
            bestLocalOffset = Offset(
                x = local.x.coerceIn(0f, w.coerceAtLeast(1f)),
                y = local.y.coerceIn(0f, h.coerceAtLeast(1f))
            )
        }
    }

    val chosen = bestTarget ?: return null
    if (bestDistanceY > 140f) return null
    val localCharIdx = chosen.layoutResult.getOffsetForPosition(bestLocalOffset)
        .coerceIn(0, chosen.slice.text.length)
    return chosen.slice.chapterCharStart + localCharIdx
}

private fun buildDecoratedSliceText(
    slice: EpubPageItem.TextSlice,
    chapterIndex: Int,
    chapterHighlights: List<TextHighlight>,
    chapterSelectionBookmarks: List<Bookmark>,
    pageMatches: List<SearchMatch>,
    activeMatch: SearchMatch?,
    activeSelection: ChapterTextSelection?,
    palette: ReaderPalette
): AnnotatedString {
    if (slice.text.text.isEmpty()) return slice.text

    val overlappingHighlights = chapterHighlights.filter { hl ->
        hl.startCharOffset < slice.chapterCharEnd && hl.endCharOffset > slice.chapterCharStart
    }
    val overlappingBookmarks = chapterSelectionBookmarks.filter { bm ->
        bm.epubCharOffset < slice.chapterCharEnd && bm.epubEndCharOffset > slice.chapterCharStart
    }
    val overlappingMatches = pageMatches.filter { match ->
        match.chapterIndex == chapterIndex &&
            match.charOffsetInChapter < slice.chapterCharEnd &&
            (match.charOffsetInChapter + match.matchLength) > slice.chapterCharStart
    }
    val selectionOverlaps = activeSelection != null &&
        activeSelection.chapterIndex == chapterIndex &&
        activeSelection.startCharOffset < slice.chapterCharEnd &&
        activeSelection.endCharOffset > slice.chapterCharStart

    if (overlappingHighlights.isEmpty() &&
        overlappingBookmarks.isEmpty() &&
        overlappingMatches.isEmpty() &&
        !selectionOverlaps
    ) {
        return slice.text
    }

    val builder = AnnotatedString.Builder(slice.text)

    // 1. Saved colored text highlights
    for (hl in overlappingHighlights) {
        val localStart = (hl.startCharOffset - slice.chapterCharStart).coerceIn(0, slice.text.length)
        val localEnd = (hl.endCharOffset - slice.chapterCharStart).coerceIn(localStart, slice.text.length)
        if (localEnd > localStart) {
            builder.addStyle(
                style = SpanStyle(background = Color(hl.color.argbHex)),
                start = localStart,
                end = localEnd
            )
        }
    }

    // 2. Saved passage/sentence bookmarks (subtle accent underline + soft tint)
    for (bm in overlappingBookmarks) {
        val localStart = (bm.epubCharOffset - slice.chapterCharStart).coerceIn(0, slice.text.length)
        val localEnd = (bm.epubEndCharOffset - slice.chapterCharStart).coerceIn(localStart, slice.text.length)
        if (localEnd > localStart) {
            builder.addStyle(
                style = SpanStyle(
                    background = palette.accent.copy(alpha = 0.12f),
                    textDecoration = TextDecoration.Underline
                ),
                start = localStart,
                end = localEnd
            )
        }
    }

    // 3. In-book search matches
    for (match in overlappingMatches) {
        val localStart = (match.charOffsetInChapter - slice.chapterCharStart)
            .coerceIn(0, slice.text.length)
        val localEnd = (match.charOffsetInChapter + match.matchLength - slice.chapterCharStart)
            .coerceIn(localStart, slice.text.length)

        if (localEnd > localStart) {
            val isFocused = activeMatch != null && activeMatch.index == match.index
            builder.addStyle(
                style = SpanStyle(
                    background = if (isFocused) palette.activeSearchHighlight else palette.searchHighlight
                ),
                start = localStart,
                end = localEnd
            )
        }
    }

    // 4. Live active text selection range
    if (selectionOverlaps) {
        activeSelection?.let { sel ->
            val localStart = (sel.startCharOffset - slice.chapterCharStart)
                .coerceIn(0, slice.text.length)
            val localEnd = (sel.endCharOffset - slice.chapterCharStart)
                .coerceIn(localStart, slice.text.length)
            if (localEnd > localStart) {
                builder.addStyle(
                    style = SpanStyle(background = palette.accent.copy(alpha = 0.28f)),
                    start = localStart,
                    end = localEnd
                )
            }
        }
    }

    return builder.toAnnotatedString()
}
