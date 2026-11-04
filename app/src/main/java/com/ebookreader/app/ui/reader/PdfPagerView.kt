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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
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
import com.ebookreader.app.domain.model.PdfFitMode
import com.ebookreader.app.domain.model.ReaderSettings
import com.ebookreader.app.domain.model.SearchMatch
import com.ebookreader.app.ui.theme.ReaderPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private val NIGHT_INVERT_MATRIX = ColorMatrix(
    floatArrayOf(
        -0.88f, 0f, 0f, 0f, 238f,
        0f, -0.88f, 0f, 0f, 238f,
        0f, 0f, -0.88f, 0f, 238f,
        0f, 0f, 0f, 1f, 0f
    )
)

private data class RenderedZoomTile(
    val bitmap: Bitmap,
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
    val fitMode: PdfFitMode,
    val trimMargins: Boolean
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
            fitMode = settings.pdfFitMode,
            trimMargins = settings.pdfTrimMargins,
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
    fitMode: PdfFitMode,
    trimMargins: Boolean,
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
    var zoomTile by remember(pageIndex) { mutableStateOf<RenderedZoomTile?>(null) }

    fun clearZoomTile() {
        zoomTile?.bitmap?.let { bmp ->
            if (!bmp.isRecycled) bmp.recycle()
        }
        zoomTile = null
    }

    DisposableEffect(pageIndex) {
        onDispose { clearZoomTile() }
    }

    LaunchedEffect(isActivePage, fitMode, trimMargins) {
        if ((!isActivePage && scale != 1f) || scale != 1f) {
            if (!isActivePage) {
                scale = 1f
                offsetX = 0f
                offsetY = 0f
                clearZoomTile()
            }
        }
        offsetY = 0f
    }

    val paddingMod = if (fitMode == PdfFitMode.FIT_PAGE) {
        Modifier.padding(horizontal = 6.dp, vertical = 8.dp)
    } else {
        Modifier
    }

    BoxWithConstraints(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .then(paddingMod)
            .clipToBounds()
    ) {
        val viewportWidthPx = with(density) { maxWidth.roundToPx() }.coerceAtLeast(360)
        val viewportHeightPx = with(density) { maxHeight.roundToPx() }.coerceAtLeast(480)
        var pageBitmap by remember(controller, pageIndex, viewportWidthPx) {
            mutableStateOf(controller.getCachedPage(pageIndex, viewportWidthPx))
        }

        LaunchedEffect(controller, pageIndex, viewportWidthPx, isActivePage) {
            if (pageBitmap != null && !pageBitmap!!.isRecycled) return@LaunchedEffect
            if (!isActivePage) delay(35)
            val rendered = controller.renderPage(
                pageIndex = pageIndex,
                targetWidthPx = viewportWidthPx,
                highResMultiplier = 1.25f
            )
            if (rendered != null) {
                pageBitmap = rendered
            }
        }

        val baseBmp = pageBitmap?.takeIf { !it.isRecycled }
        val contentBounds = remember(baseBmp, pageIndex) {
            if (baseBmp != null) controller.getContentBounds(pageIndex)
            else com.ebookreader.app.data.parser.pdf.PdfContentBounds.FULL
        }

        LaunchedEffect(
            controller, pageIndex, isActivePage, baseBmp,
            scale, offsetX, offsetY, fitMode, trimMargins, viewportWidthPx, viewportHeightPx
        ) {
            if (!isActivePage || baseBmp == null || scale <= 1.25f) {
                clearZoomTile()
                return@LaunchedEffect
            }
            delay(110)

            val rect = PdfDocumentController.computePdfDisplayRect(
                viewportWidth = viewportWidthPx.toFloat(),
                viewportHeight = viewportHeightPx.toFloat(),
                bitmapWidth = baseBmp.width.toFloat(),
                bitmapHeight = baseBmp.height.toFloat(),
                contentBounds = contentBounds,
                fitMode = fitMode,
                trimMargins = trimMargins
            )

            val targetScale = scale
            val targetOx = offsetX
            val targetOy = offsetY

            val renderedTile = controller.renderViewportTile(
                pageIndex = pageIndex,
                viewportWidthPx = viewportWidthPx,
                viewportHeightPx = viewportHeightPx,
                fitLeft = rect.pageLeft,
                fitTop = rect.pageTop,
                fitWidth = rect.pageWidth,
                fitHeight = rect.pageHeight,
                zoomScale = targetScale,
                panOffsetX = targetOx,
                panOffsetY = targetOy
            )

            if (renderedTile != null) {
                val previous = zoomTile
                zoomTile = RenderedZoomTile(
                    bitmap = renderedTile,
                    scale = targetScale,
                    offsetX = targetOx,
                    offsetY = targetOy,
                    fitMode = fitMode,
                    trimMargins = trimMargins
                )
                if (previous != null && previous.bitmap !== renderedTile && !previous.bitmap.isRecycled) {
                    previous.bitmap.recycle()
                }
            }
        }

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(pageIndex, totalPages) {
                    detectTapGestures(
                        onDoubleTap = { tapOffset ->
                            if (scale > 1.05f) {
                                scale = 1f
                                offsetX = 0f
                                offsetY = 0f
                                clearZoomTile()
                                onZoomStateChanged(false)
                            } else {
                                val targetScale = 2.2f
                                val maxX = (size.width * (targetScale - 1f)) / 2f
                                val maxY = (size.height * (targetScale - 1f)) / 2f
                                val centerX = size.width / 2f
                                val centerY = size.height / 2f
                                scale = targetScale
                                offsetX = ((centerX - tapOffset.x) * (targetScale - 1f)).coerceIn(-maxX, maxX)
                                offsetY = ((centerY - tapOffset.y) * (targetScale - 1f)).coerceIn(-maxY, maxY)
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
                .pointerInput(pageIndex, fitMode, trimMargins, baseBmp) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            val pointerCount = event.changes.count { it.pressed }
                            if (pointerCount >= 2 || scale > 1.02f) {
                                val zoomChange = event.calculateZoom()
                                val panChange = event.calculatePan()

                                val newScale = (scale * zoomChange).coerceIn(1f, 5f)
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
                                    clearZoomTile()
                                    onZoomStateChanged(false)
                                }
                            } else if (fitMode == PdfFitMode.FIT_WIDTH && baseBmp != null) {
                                val rect = PdfDocumentController.computePdfDisplayRect(
                                    viewportWidth = size.width.toFloat(),
                                    viewportHeight = size.height.toFloat(),
                                    bitmapWidth = baseBmp.width.toFloat(),
                                    bitmapHeight = baseBmp.height.toFloat(),
                                    contentBounds = contentBounds,
                                    fitMode = fitMode,
                                    trimMargins = trimMargins
                                )
                                val overflowY = (rect.contentDisplayHeight - size.height).coerceAtLeast(0f)
                                if (overflowY > 4f) {
                                    val pan = event.calculatePan()
                                    if (abs(pan.y) > abs(pan.x) * 1.15f && abs(pan.y) > 1f) {
                                        offsetY = (offsetY + pan.y).coerceIn(-overflowY, 0f)
                                        event.changes.forEach { it.consume() }
                                    }
                                }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
        ) {
            if (baseBmp == null) {
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
                    val rect = PdfDocumentController.computePdfDisplayRect(
                        viewportWidth = size.width,
                        viewportHeight = size.height,
                        bitmapWidth = baseBmp.width.toFloat(),
                        bitmapHeight = baseBmp.height.toFloat(),
                        contentBounds = contentBounds,
                        fitMode = fitMode,
                        trimMargins = trimMargins
                    )

                    drawImage(
                        image = baseBmp.asImageBitmap(),
                        dstOffset = IntOffset(rect.pageLeft.roundToInt(), rect.pageTop.roundToInt()),
                        dstSize = IntSize(
                            rect.pageWidth.roundToInt().coerceAtLeast(1),
                            rect.pageHeight.roundToInt().coerceAtLeast(1)
                        ),
                        colorFilter = colorFilter
                    )

                    drawPdfSearchHighlights(
                        pageMatches = pageMatches,
                        activeMatch = activeMatch,
                        palette = palette,
                        left = rect.pageLeft,
                        top = rect.pageTop,
                        drawW = rect.pageWidth,
                        drawH = rect.pageHeight
                    )
                }

                val activeTile = zoomTile
                if (activeTile != null &&
                    !activeTile.bitmap.isRecycled &&
                    activeTile.fitMode == fitMode &&
                    activeTile.trimMargins == trimMargins &&
                    abs(activeTile.scale - scale) < 0.015f &&
                    abs(activeTile.offsetX - offsetX) < 2f &&
                    abs(activeTile.offsetY - offsetY) < 2f
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawImage(
                            image = activeTile.bitmap.asImageBitmap(),
                            dstOffset = IntOffset.Zero,
                            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                            colorFilter = colorFilter
                        )

                        if (pageMatches.isNotEmpty()) {
                            val rect = PdfDocumentController.computePdfDisplayRect(
                                viewportWidth = size.width,
                                viewportHeight = size.height,
                                bitmapWidth = baseBmp.width.toFloat(),
                                bitmapHeight = baseBmp.height.toFloat(),
                                contentBounds = contentBounds,
                                fitMode = fitMode,
                                trimMargins = trimMargins
                            )
                            val px = size.width / 2f
                            val py = size.height / 2f

                            drawPdfSearchHighlights(
                                pageMatches = pageMatches,
                                activeMatch = activeMatch,
                                palette = palette,
                                left = px + (rect.pageLeft - px) * scale + offsetX,
                                top = py + (rect.pageTop - py) * scale + offsetY,
                                drawW = rect.pageWidth * scale,
                                drawH = rect.pageHeight * scale
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

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPdfSearchHighlights(
    pageMatches: List<SearchMatch>,
    activeMatch: SearchMatch?,
    palette: ReaderPalette,
    left: Float,
    top: Float,
    drawW: Float,
    drawH: Float
) {
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
            drawRect(
                color = highlightColor.copy(alpha = 0.22f),
                topLeft = Offset(left, top),
                size = Size(drawW, 10f)
            )
        }
    }
}
