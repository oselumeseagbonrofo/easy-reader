package com.ebookreader.app.ui.reader

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.dp
import com.ebookreader.app.data.parser.epub.EpubPage
import com.ebookreader.app.data.parser.epub.EpubPageItem
import com.ebookreader.app.data.parser.epub.EpubPaginator
import com.ebookreader.app.data.parser.epub.EpubTextKind
import com.ebookreader.app.domain.model.ReaderSettings
import com.ebookreader.app.domain.model.SearchMatch
import com.ebookreader.app.ui.theme.ReaderPalette
import kotlinx.coroutines.launch

@Composable
fun EpubPagerView(
    pages: List<EpubPage>,
    pagerState: PagerState,
    settings: ReaderSettings,
    palette: ReaderPalette,
    bookmarkedPages: Set<Int>,
    searchMatches: List<SearchMatch>,
    activeMatchIndex: Int,
    loadImage: suspend (String) -> Bitmap?,
    onToggleHud: () -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()

    HorizontalPager(
        state = pagerState,
        beyondViewportPageCount = 1,
        modifier = modifier
            .fillMaxSize()
            .background(palette.background)
            .pointerInput(pages.size) {
                detectTapGestures { offset ->
                    val width = size.width.toFloat().coerceAtLeast(1f)
                    val xFraction = offset.x / width
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
                            if (next < pages.size) {
                                coroutineScope.launch { pagerState.animateScrollToPage(next) }
                            } else {
                                onToggleHud()
                            }
                        }
                        else -> onToggleHud()
                    }
                }
            }
    ) { pageIndex ->
        val page = pages.getOrNull(pageIndex)
        if (page != null) {
            val pageMatches = remember(searchMatches, pageIndex) {
                searchMatches.filter { it.pageIndex == pageIndex }
            }
            val activeMatch = searchMatches.getOrNull(activeMatchIndex)
                ?.takeIf { it.pageIndex == pageIndex }

            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 22.dp, vertical = 12.dp)
                ) {
                    page.items.forEach { item ->
                        when (item) {
                            is EpubPageItem.TextSlice -> {
                                val style = EpubPaginator.textStyleForKind(item.kind, settings)
                                    .copy(color = palette.onBackground)

                                val displayAnnotated = remember(
                                    item,
                                    pageMatches,
                                    activeMatch,
                                    palette
                                ) {
                                    applySearchHighlights(
                                        slice = item,
                                        chapterIndex = page.chapterIndex,
                                        pageMatches = pageMatches,
                                        activeMatch = activeMatch,
                                        palette = palette
                                    )
                                }

                                val gapDp = when (item.kind) {
                                    EpubTextKind.HEADING_1,
                                    EpubTextKind.HEADING_2,
                                    EpubTextKind.HEADING_3 -> 16.dp
                                    else -> 12.dp
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
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                } else {
                                    Text(
                                        text = displayAnnotated,
                                        style = style,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = gapDp)
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

                if (pageIndex in bookmarkedPages) {
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
            }
        }
    }
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

private fun applySearchHighlights(
    slice: EpubPageItem.TextSlice,
    chapterIndex: Int,
    pageMatches: List<SearchMatch>,
    activeMatch: SearchMatch?,
    palette: ReaderPalette
): AnnotatedString {
    if (pageMatches.isEmpty() || slice.text.text.isEmpty()) return slice.text

    val relevant = pageMatches.filter { match ->
        match.chapterIndex == chapterIndex &&
            match.charOffsetInChapter < slice.chapterCharEnd &&
            (match.charOffsetInChapter + match.matchLength) > slice.chapterCharStart
    }
    if (relevant.isEmpty()) return slice.text

    val builder = AnnotatedString.Builder(slice.text)
    for (match in relevant) {
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
    return builder.toAnnotatedString()
}
