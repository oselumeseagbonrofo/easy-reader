package com.ebookreader.app.ui.reader

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.ebookreader.app.data.parser.pdf.PdfDocumentController
import com.ebookreader.app.domain.model.ReaderSettings
import com.ebookreader.app.domain.model.SearchMatch
import com.ebookreader.app.ui.theme.ReaderPalette
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val NIGHT_INVERT_MATRIX = ColorMatrix(
    floatArrayOf(
        -0.88f, 0f, 0f, 0f, 238f,
        0f, -0.88f, 0f, 0f, 238f,
        0f, 0f, -0.88f, 0f, 238f,
        0f, 0f, 0f, 1f, 0f
    )
)

@Composable
fun PdfPagerView(
    controller: PdfDocumentController,
    pagerState: PagerState,
    settings: ReaderSettings,
    palette: ReaderPalette,
    bookmarkedPages: Set<Int>,
    searchMatches: List<SearchMatch>,
    activeMatchIndex: Int,
    onToggleHud: () -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    var activePageZoomed by remember { mutableStateOf(false) }

    LaunchedEffect(pagerState.settledPage) {
        activePageZoomed = false
    }

    HorizontalPager(
        state = pagerState,
        beyondViewportPageCount = 1,
        userScrollEnabled = !activePageZoomed,
        modifier = modifier
            .fillMaxSize()
            .background(palette.background)
    ) { pageIndex ->
        val pageMatches = remember(searchMatches, pageIndex) {
            searchMatches.filter { it.pageIndex == pageIndex }
        }
        val activeMatch = searchMatches.getOrNull(activeMatchIndex)
            ?.takeIf { it.pageIndex == pageIndex }

        PdfSinglePageSurface(
            controller = controller,
            pageIndex = pageIndex,
            totalPages = controller.pageCount,
            isActivePage = (pageIndex == pagerState.currentPage),
            invertColors = settings.pdfInvertInDark && palette.isDark,
            isBookmarked = pageIndex in bookmarkedPages,
            pageMatches = pageMatches,
            activeMatch = activeMatch,
            palette = palette,
            onZoomStateChanged = { zoomed ->
                if (pageIndex == pagerState.currentPage) {
                    activePageZoomed = zoomed
                }
            },
            onTurnPrevious = {
                if (pagerState.currentPage > 0) {
                    coroutineScope.launch {
                        pagerState.animateScrollToPage(pagerState.currentPage - 1)
                    }
                } else {
                    onToggleHud()
                }
            },
            onTurnNext = {
                if (pagerState.currentPage < controller.pageCount - 1) {
                    coroutineScope.launch {
                        pagerState.animateScrollToPage(pagerState.currentPage + 1)
                    }
                } else {
                    onToggleHud()
                }
            },
            onToggleHud = onToggleHud
        )
    }
}

@Composable
private fun PdfSinglePageSurface(
    controller: PdfDocumentController,
    pageIndex: Int,
    totalPages: Int,
    isActivePage: Boolean,
    invertColors: Boolean,
    isBookmarked: Boolean,
    pageMatches: List<SearchMatch>,
    activeMatch: SearchMatch?,
    palette: ReaderPalette,
    onZoomStateChanged: (Boolean) -> Unit,
    onTurnPrevious: () -> Unit,
    onTurnNext: () -> Unit,
    onToggleHud: () -> Unit
) {
    val density = LocalDensity.current
    var scale by remember(pageIndex) { mutableFloatStateOf(1f) }
    var offsetX by remember(pageIndex) { mutableFloatStateOf(0f) }
    var offsetY by remember(pageIndex) { mutableFloatStateOf(0f) }

    LaunchedEffect(isActivePage) {
        if (!isActivePage && scale != 1f) {
            scale = 1f
            offsetX = 0f
            offsetY = 0f
        }
    }

    BoxWithConstraints(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 6.dp, vertical = 8.dp)
    ) {
        val viewportWidthPx = with(density) { maxWidth.roundToPx() }.coerceAtLeast(360)
        var pageBitmap by remember(pageIndex) { mutableStateOf<Bitmap?>(null) }

        val highRes = scale > 1.45f
        LaunchedEffect(controller, pageIndex, viewportWidthPx, highRes) {
            val rendered = controller.renderPage(
                pageIndex = pageIndex,
                targetWidthPx = viewportWidthPx,
                highResMultiplier = if (highRes) 2.25f else 1.45f
            )
            if (rendered != null) {
                pageBitmap = rendered
            }
        }

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(pageIndex, totalPages) {
                    detectTapGestures(
                        onDoubleTap = {
                            if (scale > 1.05f) {
                                scale = 1f
                                offsetX = 0f
                                offsetY = 0f
                                onZoomStateChanged(false)
                            } else {
                                scale = 2.2f
                                onZoomStateChanged(true)
                            }
                        },
                        onTap = { tapOffset ->
                            if (scale > 1.05f) {
                                onToggleHud()
                            } else {
                                val w = size.width.toFloat().coerceAtLeast(1f)
                                val ratio = tapOffset.x / w
                                when {
                                    ratio < 0.18f -> onTurnPrevious()
                                    ratio > 0.82f -> onTurnNext()
                                    else -> onToggleHud()
                                }
                            }
                        }
                    )
                }
                .pointerInput(pageIndex) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            val pointerCount = event.changes.count { it.pressed }
                            if (pointerCount >= 2 || scale > 1.02f) {
                                val zoomChange = event.calculateZoom()
                                val panChange = event.calculatePan()

                                val newScale = (scale * zoomChange).coerceIn(1f, 4f)
                                if (newScale > 1.02f) {
                                    val maxX = (size.width * (newScale - 1f)) / 2f
                                    val maxY = (size.height * (newScale - 1f)) / 2f
                                    scale = newScale
                                    offsetX = (offsetX + panChange.x).coerceIn(-maxX, maxX)
                                    offsetY = (offsetY + panChange.y).coerceIn(-maxY, maxY)
                                    onZoomStateChanged(true)
                                    event.changes.forEach { it.consume() }
                                } else {
                                    scale = 1f
                                    offsetX = 0f
                                    offsetY = 0f
                                    onZoomStateChanged(false)
                                }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
        ) {
            val bmp = pageBitmap
            if (bmp == null) {
                CircularProgressIndicator(color = palette.accent)
            } else {
                val colorFilter = remember(invertColors) {
                    if (invertColors) ColorFilter.colorMatrix(NIGHT_INVERT_MATRIX) else null
                }

                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offsetX
                            translationY = offsetY
                        }
                ) {
                    val bmpW = bmp.width.toFloat().coerceAtLeast(1f)
                    val bmpH = bmp.height.toFloat().coerceAtLeast(1f)
                    val fitScale = minOf(size.width / bmpW, size.height / bmpH)
                    val drawW = bmpW * fitScale
                    val drawH = bmpH * fitScale
                    val left = (size.width - drawW) / 2f
                    val top = (size.height - drawH) / 2f

                    drawImage(
                        image = bmp.asImageBitmap(),
                        dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                        dstSize = IntSize(drawW.roundToInt(), drawH.roundToInt()),
                        colorFilter = colorFilter
                    )

                    pageMatches.forEach { match ->
                        val isFocused = activeMatch != null && activeMatch.index == match.index
                        val highlightColor = if (isFocused) {
                            palette.activeSearchHighlight
                        } else {
                            palette.searchHighlight
                        }

                        if (match.pdfRects.isNotEmpty()) {
                            match.pdfRects.forEach { norm ->
                                drawRect(
                                    color = highlightColor,
                                    topLeft = Offset(
                                        x = left + norm.left * drawW,
                                        y = top + norm.top * drawH
                                    ),
                                    size = Size(
                                        width = ((norm.right - norm.left) * drawW).coerceAtLeast(6f),
                                        height = ((norm.bottom - norm.top) * drawH).coerceAtLeast(10f)
                                    )
                                )
                            }
                        } else if (isFocused) {
                            // Subtle border frame when text matched without character box coordinates
                            drawRect(
                                color = highlightColor.copy(alpha = 0.22f),
                                topLeft = Offset(left, top),
                                size = Size(drawW, 10f)
                            )
                        }
                    }
                }
            }
        }

        if (isBookmarked) {
            Icon(
                imageVector = Icons.Default.Bookmark,
                contentDescription = "Bookmarked page",
                tint = palette.accent,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 10.dp)
                    .size(24.dp)
            )
        }
    }
}
