package com.ebookreader.app.ui.reader

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.ebookreader.app.data.parser.epub.ChapterTextSelection
import com.ebookreader.app.data.parser.epub.EpubSelectionHelper
import com.ebookreader.app.data.parser.pdf.PdfDocumentController
import com.ebookreader.app.data.parser.pdf.PdfPageTextLayout
import com.ebookreader.app.domain.model.Bookmark
import com.ebookreader.app.domain.model.HighlightColor
import com.ebookreader.app.domain.model.NormalizedRect
import com.ebookreader.app.domain.model.PdfFitMode
import com.ebookreader.app.domain.model.ReaderSettings
import com.ebookreader.app.domain.model.SearchMatch
import com.ebookreader.app.domain.model.TextHighlight
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
    bookmarks: List<Bookmark>,
    highlights: List<TextHighlight>,
    searchMatches: List<SearchMatch>,
    activeMatchIndex: Int,
    onApplyHighlight: (chapterIndex: Int, startOffset: Int, endOffset: Int, selectedText: String, color: HighlightColor) -> Unit,
    onClearHighlight: (chapterIndex: Int, startOffset: Int, endOffset: Int) -> Unit,
    onBookmarkSelection: (chapterIndex: Int, startOffset: Int, endOffset: Int, selectedText: String) -> Unit,
    onToggleHud: () -> Unit,
    onSelectionActiveChanged: ((Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    var activePageZoomed by remember { mutableStateOf(false) }
    var activeSelection by remember { mutableStateOf<ChapterTextSelection?>(null) }

    LaunchedEffect(activeSelection) {
        onSelectionActiveChanged?.invoke(activeSelection != null)
    }

    val bookmarkedPages = remember(bookmarks) {
        bookmarks.filter { !it.isTextSelection }.map { it.pageIndex }.toSet()
    }

    LaunchedEffect(pagerState.settledPage) {
        if (PdfZoomGestureHelper.shouldResetZoomOnSettledPage(pagerState.settledPage, pagerState.currentPage)) {
            activePageZoomed = false
        }
    }

    LaunchedEffect(pagerState.currentPage) {
        activeSelection = null
    }

    HorizontalPager(
        state = pagerState,
        beyondViewportPageCount = 1,
        userScrollEnabled = PdfZoomGestureHelper.isPdfPagerScrollEnabled(
            activePageZoomed = activePageZoomed,
            activeSelectionPage = activeSelection?.chapterIndex,
            currentPage = pagerState.currentPage
        ),
        modifier = modifier
            .fillMaxSize()
            .background(palette.background)
    ) { pageIndex ->
        val pageMatches = remember(searchMatches, pageIndex) {
            searchMatches.filter { it.pageIndex == pageIndex }
        }
        val activeMatch = searchMatches.getOrNull(activeMatchIndex)
            ?.takeIf { it.pageIndex == pageIndex }
        val pageHighlights = remember(highlights, pageIndex) {
            highlights.filter { it.pageIndex == pageIndex || it.chapterIndex == pageIndex }
        }
        val pageSelectionBookmarks = remember(bookmarks, pageIndex) {
            bookmarks.filter {
                it.isTextSelection && (it.pageIndex == pageIndex || it.epubChapterIndex == pageIndex)
            }
        }
        val currentSelectionOnPage = activeSelection?.takeIf {
            pageIndex == pagerState.currentPage && it.chapterIndex == pageIndex
        }

        PdfSinglePageSurface(
            controller = controller,
            pageIndex = pageIndex,
            totalPages = controller.pageCount,
            isActivePage = (pageIndex == pagerState.currentPage),
            fitMode = settings.pdfFitMode,
            trimMargins = settings.pdfTrimMargins,
            invertColors = settings.pdfInvertInDark && palette.isDark,
            isBookmarked = pageIndex in bookmarkedPages,
            pageHighlights = pageHighlights,
            pageSelectionBookmarks = pageSelectionBookmarks,
            activeSelection = currentSelectionOnPage,
            pageMatches = pageMatches,
            activeMatch = activeMatch,
            palette = palette,
            onUpdateSelection = { activeSelection = it },
            onApplyHighlight = onApplyHighlight,
            onClearHighlight = onClearHighlight,
            onBookmarkSelection = onBookmarkSelection,
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
    pageHighlights: List<TextHighlight>,
    pageSelectionBookmarks: List<Bookmark>,
    activeSelection: ChapterTextSelection?,
    pageMatches: List<SearchMatch>,
    activeMatch: SearchMatch?,
    palette: ReaderPalette,
    onUpdateSelection: (ChapterTextSelection?) -> Unit,
    onApplyHighlight: (chapterIndex: Int, startOffset: Int, endOffset: Int, selectedText: String, color: HighlightColor) -> Unit,
    onClearHighlight: (chapterIndex: Int, startOffset: Int, endOffset: Int) -> Unit,
    onBookmarkSelection: (chapterIndex: Int, startOffset: Int, endOffset: Int, selectedText: String) -> Unit,
    onZoomStateChanged: (Boolean) -> Unit,
    onTurnPrevious: () -> Unit,
    onTurnNext: () -> Unit,
    onToggleHud: () -> Unit
) {
    val density = LocalDensity.current
    val pageScope = rememberCoroutineScope()
    var scale by remember(pageIndex) { mutableFloatStateOf(1f) }
    var offsetX by remember(pageIndex) { mutableFloatStateOf(0f) }
    var offsetY by remember(pageIndex) { mutableFloatStateOf(0f) }
    var zoomTile by remember(pageIndex) { mutableStateOf<RenderedZoomTile?>(null) }
    var selectionAnchorOffset by remember(pageIndex) { mutableIntStateOf(0) }
    var selectionPlacedNearTop by remember(pageIndex) { mutableStateOf(false) }

    var pageTextLayout by remember(controller, pageIndex) {
        mutableStateOf(controller.getCachedPageTextLayout(pageIndex))
    }

    LaunchedEffect(controller, pageIndex, isActivePage) {
        if (pageTextLayout != null) return@LaunchedEffect
        if (!isActivePage) return@LaunchedEffect
        pageTextLayout = controller.extractPageTextLayout(pageIndex)
    }

    fun clearZoomTile() {
        if (PdfZoomGestureHelper.shouldRecycleDisposedZoomTileBitmap()) {
            zoomTile?.bitmap?.let { bmp ->
                if (!bmp.isRecycled) bmp.recycle()
            }
        }
        zoomTile = null
    }

    DisposableEffect(pageIndex) {
        onDispose { clearZoomTile() }
    }

    LaunchedEffect(isActivePage, fitMode, trimMargins) {
        val (reconciled, notifyNotZoomed) = PdfZoomGestureHelper.reconcileLayoutEffect(
            currentState = ZoomPanState(
                scale = scale,
                offsetX = offsetX,
                offsetY = offsetY,
                isZoomed = scale > PdfZoomGestureHelper.ZOOM_THRESHOLD
            ),
            isActivePage = isActivePage
        )
        val shouldClearTile = !isActivePage && scale != 1f
        scale = reconciled.scale
        offsetX = reconciled.offsetX
        offsetY = reconciled.offsetY
        if (shouldClearTile) {
            clearZoomTile()
        }
        if (notifyNotZoomed) {
            onZoomStateChanged(false)
        }
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
                if (PdfZoomGestureHelper.shouldRecycleDisposedZoomTileBitmap() &&
                    previous != null && previous.bitmap !== renderedTile && !previous.bitmap.isRecycled
                ) {
                    previous.bitmap.recycle()
                }
            }
        }

        val currentTextLayoutUpdated by rememberUpdatedState(pageTextLayout)
        val currentSelectionUpdated by rememberUpdatedState(activeSelection)
        val currentBaseBmpUpdated by rememberUpdatedState(baseBmp)
        val currentContentBoundsUpdated by rememberUpdatedState(contentBounds)
        val currentFitModeUpdated by rememberUpdatedState(fitMode)
        val currentTrimMarginsUpdated by rememberUpdatedState(trimMargins)
        val currentOnUpdateSelection by rememberUpdatedState(onUpdateSelection)
        val currentIsActivePageUpdated by rememberUpdatedState(isActivePage)
        val currentScaleUpdated by rememberUpdatedState(scale)
        val currentOnZoomStateChanged by rememberUpdatedState(onZoomStateChanged)
        val currentOnTurnPrevious by rememberUpdatedState(onTurnPrevious)
        val currentOnTurnNext by rememberUpdatedState(onTurnNext)
        val currentOnToggleHud by rememberUpdatedState(onToggleHud)

        fun touchToNormalizedPdf(touch: Offset, viewportSize: IntSize, bmp: Bitmap): Offset {
            val rect = PdfDocumentController.computePdfDisplayRect(
                viewportWidth = viewportSize.width.toFloat(),
                viewportHeight = viewportSize.height.toFloat(),
                bitmapWidth = bmp.width.toFloat(),
                bitmapHeight = bmp.height.toFloat(),
                contentBounds = currentContentBoundsUpdated,
                fitMode = currentFitModeUpdated,
                trimMargins = currentTrimMarginsUpdated
            )
            val px = viewportSize.width / 2f
            val py = viewportSize.height / 2f
            val left = px + (rect.pageLeft - px) * scale + offsetX
            val top = py + (rect.pageTop - py) * scale + offsetY
            val drawW = (rect.pageWidth * scale).coerceAtLeast(1f)
            val drawH = (rect.pageHeight * scale).coerceAtLeast(1f)
            return Offset(
                x = (touch.x - left) / drawW,
                y = (touch.y - top) / drawH
            )
        }

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(pageIndex, totalPages) {
                    var lastTapTimeMs = 0L
                    var lastTapPos = Offset.Zero

                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val downPos = down.position
                        val touchSlop = viewConfiguration.touchSlop
                        val longPressTimeout = viewConfiguration.longPressTimeoutMillis

                        var secondPointerDown = false
                        var hasMovedPastSlop = false
                        var liftedEarly = false
                        var upPos = downPos
                        var longPressChange: PointerInputChange? = null

                        // When zoomed, a tiny movement starts panning immediately (no long-press race).
                        val moveSlop = if (scale > 1.02f) touchSlop / 4f else touchSlop

                        if (currentEvent.changes.count { it.pressed } > 1) {
                            secondPointerDown = true
                        } else {
                            // NOTE: inside the pointer scope this is AwaitPointerEventScope.withTimeoutOrNull,
                            // whose timeout is NOT a kotlinx TimeoutCancellationException — so never try/catch it.
                            val finishedBeforeTimeout = withTimeoutOrNull(longPressTimeout) {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val pressedCount = event.changes.count { it.pressed }
                                        if (pressedCount > 1) {
                                            secondPointerDown = true
                                            break
                                        }
                                        if (event.changes.all { !it.pressed }) {
                                            liftedEarly = true
                                            upPos = event.changes.firstOrNull()?.position ?: downPos
                                            break
                                        }
                                        val currentInput = event.changes.firstOrNull { it.id == down.id }
                                        if (currentInput == null || !currentInput.pressed) {
                                            liftedEarly = true
                                            upPos = currentInput?.position ?: downPos
                                            break
                                        }
                                        if ((currentInput.position - downPos).getDistance() > moveSlop) {
                                            hasMovedPastSlop = true
                                            if (scale > 1.02f) {
                                                // Apply this first movement as pan and keep it from the pager
                                                val maxX = (size.width * (scale - 1f)) / 2f
                                                val maxY = (size.height * (scale - 1f)) / 2f
                                                val delta = currentInput.position - currentInput.previousPosition
                                                offsetX = (offsetX + delta.x).coerceIn(-maxX, maxX)
                                                offsetY = (offsetY + delta.y).coerceIn(-maxY, maxY)
                                                currentInput.consume()
                                            }
                                            break
                                        }
                                    }
                                    true
                                }
                            if (finishedBeforeTimeout == null) {
                                val currentInput = currentEvent.changes.firstOrNull { it.id == down.id }
                                if (currentInput != null && currentInput.pressed && currentEvent.changes.count { it.pressed } == 1) {
                                    longPressChange = currentInput
                                }
                            }
                        }

                        val bmp = currentBaseBmpUpdated
                        if (longPressChange != null && bmp != null && currentEvent.changes.count { it.pressed } <= 1) {
                            val layout = currentTextLayoutUpdated
                            val longPress = longPressChange
                            if (layout != null && layout.hasText) {
                                val norm = touchToNormalizedPdf(longPress.position, size, bmp)
                                val hit = layout.hitTestCharOffset(norm.x, norm.y, allowLooseDrag = true)
                                if (hit != null) {
                                    longPress.consume()
                                    currentEvent.changes.forEach { it.consume() }
                                    selectionAnchorOffset = hit
                                    selectionPlacedNearTop = longPress.position.y < size.height * 0.32f
                                    var (sStart, sEnd) = EpubSelectionHelper.expandToSentence(
                                        layout.plainText,
                                        hit
                                    )
                                    if (sEnd <= sStart) {
                                        val wordRange = EpubSelectionHelper.expandRangeToWordBoundaries(
                                            layout.plainText,
                                            hit,
                                            hit
                                        )
                                        sStart = wordRange.first
                                        sEnd = wordRange.second
                                    }
                                    if (sEnd > sStart) {
                                        currentOnUpdateSelection(
                                            ChapterTextSelection(
                                                chapterIndex = pageIndex,
                                                startCharOffset = sStart,
                                                endCharOffset = sEnd,
                                                selectedText = layout.plainText.substring(sStart, sEnd)
                                            )
                                        )
                                    }
                                    var didDragMove = false
                                    drag(longPress.id) { change ->
                                        val moveDist = (change.position - longPress.position).getDistance()
                                        if (moveDist > touchSlop * 0.6f) {
                                            didDragMove = true
                                        }
                                        change.consume()
                                        if (didDragMove) {
                                            val dragNorm = touchToNormalizedPdf(change.position, size, bmp)
                                            val dragHit = layout.hitTestCharOffset(
                                                dragNorm.x,
                                                dragNorm.y,
                                                allowLooseDrag = true
                                            )
                                            if (dragHit != null) {
                                                val range = EpubSelectionHelper.expandRangeToWordBoundaries(
                                                    plainText = layout.plainText,
                                                    offsetA = selectionAnchorOffset,
                                                    offsetB = dragHit
                                                )
                                                if (range.second > range.first) {
                                                    currentOnUpdateSelection(
                                                        ChapterTextSelection(
                                                            chapterIndex = pageIndex,
                                                            startCharOffset = range.first,
                                                            endCharOffset = range.second,
                                                            selectedText = layout.plainText.substring(
                                                                range.first,
                                                                range.second
                                                            )
                                                        )
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    currentEvent.changes.forEach { it.consume() }
                                }
                            } else {
                                val holdPos = longPress.position
                                val viewportSz = size
                                pageScope.launch {
                                    val extracted = controller.extractPageTextLayout(pageIndex)
                                    pageTextLayout = extracted
                                    if (!PdfZoomGestureHelper.canApplyAsyncSelection(
                                            isActivePage = currentIsActivePageUpdated,
                                            isGestureInProgress = currentScaleUpdated > PdfZoomGestureHelper.ZOOM_THRESHOLD
                                        )
                                    ) {
                                        return@launch
                                    }
                                    if (extracted.hasText) {
                                        val norm = touchToNormalizedPdf(holdPos, viewportSz, bmp)
                                        val hit = extracted.hitTestCharOffset(norm.x, norm.y, allowLooseDrag = true)
                                        if (hit != null) {
                                            selectionAnchorOffset = hit
                                            selectionPlacedNearTop = holdPos.y < viewportSz.height * 0.32f
                                            var (sStart, sEnd) = EpubSelectionHelper.expandToSentence(
                                                extracted.plainText,
                                                hit
                                            )
                                            if (sEnd <= sStart) {
                                                val wordRange = EpubSelectionHelper.expandRangeToWordBoundaries(
                                                    extracted.plainText,
                                                    hit,
                                                    hit
                                                )
                                                sStart = wordRange.first
                                                sEnd = wordRange.second
                                            }
                                            if (sEnd > sStart) {
                                                currentOnUpdateSelection(
                                                    ChapterTextSelection(
                                                        chapterIndex = pageIndex,
                                                        startCharOffset = sStart,
                                                        endCharOffset = sEnd,
                                                        selectedText = extracted.plainText.substring(sStart, sEnd)
                                                    )
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        } else if (liftedEarly || currentEvent.changes.all { !it.pressed }) {
                            // Finger lifted before timeout -> Tap or Double-Tap
                            val withinSlop = (upPos - downPos).getDistance() <= touchSlop * 1.5f
                            if (withinSlop) {
                                val now = SystemClock.uptimeMillis()
                                val w = size.width.toFloat().coerceAtLeast(1f)
                                val ratio = upPos.x / w
                                val isDoubleTap = (now - lastTapTimeMs) in 40L..380L &&
                                    (upPos - lastTapPos).getDistance() <= touchSlop * 3f &&
                                    (scale > 1.05f || ratio in 0.18f..0.82f)

                                if (isDoubleTap) {
                                    lastTapTimeMs = 0L
                                    currentOnUpdateSelection(null)
                                    val newZoom = PdfZoomGestureHelper.calculateDoubleTapZoom(
                                        currentState = ZoomPanState(scale, offsetX, offsetY, scale > 1.05f),
                                        tapPosition = upPos,
                                        viewportWidth = size.width.toFloat(),
                                        viewportHeight = size.height.toFloat()
                                    )
                                    scale = newZoom.scale
                                    offsetX = newZoom.offsetX
                                    offsetY = newZoom.offsetY
                                    if (!newZoom.isZoomed) {
                                        clearZoomTile()
                                    }
                                    currentOnZoomStateChanged(newZoom.isZoomed)
                                } else if (currentSelectionUpdated != null) {
                                    // Single tap clears active text selection
                                    currentOnUpdateSelection(null)
                                    lastTapTimeMs = now
                                    lastTapPos = upPos
                                } else {
                                    lastTapTimeMs = now
                                    lastTapPos = upPos
                                    if (scale > 1.05f) {
                                        currentOnToggleHud()
                                    } else {
                                        when {
                                            ratio < 0.18f -> currentOnTurnPrevious()
                                            ratio > 0.82f -> currentOnTurnNext()
                                            else -> currentOnToggleHud()
                                        }
                                    }
                                }
                            }
                        } else {
                            // Multi-touch pinch or one-finger drag past slop -> Pinch-zoom / Pan loop
                            if (currentSelectionUpdated != null) {
                                currentOnUpdateSelection(null)
                            }
                            do {
                                val event = awaitPointerEvent()
                                if (scale <= 1.02f && event.changes.any { it.isConsumed }) {
                                    continue
                                }
                                val pointerCount = event.changes.count { it.pressed }
                                if (pointerCount >= 2 || scale > 1.02f) {
                                    val zoomChange = event.calculateZoom()
                                    val panChange = event.calculatePan()

                                    val newZoom = PdfZoomGestureHelper.calculateZoomPan(
                                        currentState = ZoomPanState(scale, offsetX, offsetY, scale > 1.02f),
                                        zoomChange = zoomChange,
                                        panChange = panChange,
                                        viewportWidth = size.width.toFloat(),
                                        viewportHeight = size.height.toFloat()
                                    )
                                    scale = newZoom.scale
                                    offsetX = newZoom.offsetX
                                    offsetY = newZoom.offsetY
                                    if (!newZoom.isZoomed) {
                                        clearZoomTile()
                                    }
                                    currentOnZoomStateChanged(newZoom.isZoomed)
                                    event.changes.forEach { it.consume() }
                                } else if (currentFitModeUpdated == PdfFitMode.FIT_WIDTH && bmp != null) {
                                    val rect = PdfDocumentController.computePdfDisplayRect(
                                        viewportWidth = size.width.toFloat(),
                                        viewportHeight = size.height.toFloat(),
                                        bitmapWidth = bmp.width.toFloat(),
                                        bitmapHeight = bmp.height.toFloat(),
                                        contentBounds = currentContentBoundsUpdated,
                                        fitMode = currentFitModeUpdated,
                                        trimMargins = currentTrimMarginsUpdated
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

                    drawPdfAnnotationsAndHighlights(
                        pageTextLayout = pageTextLayout,
                        pageHighlights = pageHighlights,
                        pageSelectionBookmarks = pageSelectionBookmarks,
                        activeSelection = activeSelection,
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

                        if (pageMatches.isNotEmpty() ||
                            pageHighlights.isNotEmpty() ||
                            pageSelectionBookmarks.isNotEmpty() ||
                            activeSelection != null
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
                            val px = size.width / 2f
                            val py = size.height / 2f

                            drawPdfAnnotationsAndHighlights(
                                pageTextLayout = pageTextLayout,
                                pageHighlights = pageHighlights,
                                pageSelectionBookmarks = pageSelectionBookmarks,
                                activeSelection = activeSelection,
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

        if (scale > 1.05f) {
            Surface(
                onClick = {
                    scale = 1f
                    offsetX = 0f
                    offsetY = 0f
                    clearZoomTile()
                    onZoomStateChanged(false)
                    onUpdateSelection(null)
                },
                shape = CircleShape,
                color = palette.hudContainer.copy(alpha = 0.95f),
                shadowElevation = 4.dp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 24.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ZoomOutMap,
                        contentDescription = "Reset Zoom",
                        tint = palette.onHudContainer,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "${((scale * 10).roundToInt() / 10f)}x • Reset",
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.onHudContainer
                    )
                }
            }
        }

        val layout = pageTextLayout
        if (activeSelection != null && layout != null) {
            val hasOverlappingHighlight = pageHighlights.any { hl ->
                hl.startCharOffset < activeSelection.endCharOffset &&
                    hl.endCharOffset > activeSelection.startCharOffset
            }
            val isExactSelectionBookmarked = pageSelectionBookmarks.any { bm ->
                bm.epubCharOffset == activeSelection.startCharOffset &&
                    bm.epubEndCharOffset == activeSelection.endCharOffset
            }

            SelectionFloatingBar(
                palette = palette,
                hasOverlappingHighlight = hasOverlappingHighlight,
                isSelectionBookmarked = isExactSelectionBookmarked,
                onExpandSentence = {
                    val (s, e) = EpubSelectionHelper.expandToSentence(
                        layout.plainText,
                        activeSelection.startCharOffset
                    )
                    val (safeS, safeE) = if (e > s) {
                        s to e
                    } else {
                        EpubSelectionHelper.expandRangeToWordBoundaries(
                            layout.plainText,
                            activeSelection.startCharOffset,
                            activeSelection.endCharOffset
                        )
                    }
                    if (safeE > safeS) {
                        onUpdateSelection(
                            activeSelection.copy(
                                startCharOffset = safeS,
                                endCharOffset = safeE,
                                selectedText = layout.plainText.substring(safeS, safeE)
                            )
                        )
                    }
                },
                onExpandParagraph = {
                    val (s, e) = EpubSelectionHelper.expandToParagraph(
                        layout.plainText,
                        activeSelection.startCharOffset
                    )
                    if (e > s) {
                        onUpdateSelection(
                            activeSelection.copy(
                                startCharOffset = s,
                                endCharOffset = e,
                                selectedText = layout.plainText.substring(s, e)
                            )
                        )
                    }
                },
                onPickHighlightColor = { color ->
                    onApplyHighlight(
                        pageIndex,
                        activeSelection.startCharOffset,
                        activeSelection.endCharOffset,
                        activeSelection.selectedText,
                        color
                    )
                    onUpdateSelection(null)
                },
                onClearHighlight = {
                    onClearHighlight(
                        pageIndex,
                        activeSelection.startCharOffset,
                        activeSelection.endCharOffset
                    )
                    onUpdateSelection(null)
                },
                onBookmarkSelection = {
                    onBookmarkSelection(
                        pageIndex,
                        activeSelection.startCharOffset,
                        activeSelection.endCharOffset,
                        activeSelection.selectedText
                    )
                    onUpdateSelection(null)
                },
                onDismiss = { onUpdateSelection(null) },
                modifier = Modifier
                    .align(if (selectionPlacedNearTop) Alignment.BottomCenter else Alignment.TopCenter)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawNormalizedRectList(
    rects: List<NormalizedRect>,
    color: Color,
    left: Float,
    top: Float,
    drawW: Float,
    drawH: Float,
    underlineColor: Color? = null
) {
    rects.forEach { norm ->
        val rx = left + norm.left * drawW
        val ry = top + norm.top * drawH
        val rw = ((norm.right - norm.left) * drawW).coerceAtLeast(6f)
        val rh = ((norm.bottom - norm.top) * drawH).coerceAtLeast(10f)
        drawRect(
            color = color,
            topLeft = Offset(rx, ry),
            size = Size(rx.let { rw }, rh)
        )
        if (underlineColor != null) {
            drawRect(
                color = underlineColor,
                topLeft = Offset(rx, ry + rh - 2f),
                size = Size(rw, 2f)
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPdfAnnotationsAndHighlights(
    pageTextLayout: PdfPageTextLayout?,
    pageHighlights: List<TextHighlight>,
    pageSelectionBookmarks: List<Bookmark>,
    activeSelection: ChapterTextSelection?,
    pageMatches: List<SearchMatch>,
    activeMatch: SearchMatch?,
    palette: ReaderPalette,
    left: Float,
    top: Float,
    drawW: Float,
    drawH: Float
) {
    if (pageTextLayout != null && pageTextLayout.hasText) {
        // 1. Saved colored text highlights
        pageHighlights.forEach { hl ->
            val rects = pageTextLayout.rectsForRange(hl.startCharOffset, hl.endCharOffset)
            drawNormalizedRectList(
                rects = rects,
                color = Color(hl.color.argbHex),
                left = left,
                top = top,
                drawW = drawW,
                drawH = drawH
            )
        }

        // 2. Saved passage bookmarks (soft tint + accent underline)
        pageSelectionBookmarks.forEach { bm ->
            val rects = pageTextLayout.rectsForRange(bm.epubCharOffset, bm.epubEndCharOffset)
            drawNormalizedRectList(
                rects = rects,
                color = palette.accent.copy(alpha = 0.14f),
                left = left,
                top = top,
                drawW = drawW,
                drawH = drawH,
                underlineColor = palette.accent.copy(alpha = 0.75f)
            )
        }
    }

    // 3. In-book search matches
    pageMatches.forEach { match ->
        val isFocused = activeMatch != null && activeMatch.index == match.index
        val highlightColor = if (isFocused) {
            palette.activeSearchHighlight
        } else {
            palette.searchHighlight
        }

        if (match.pdfRects.isNotEmpty()) {
            drawNormalizedRectList(
                rects = match.pdfRects,
                color = highlightColor,
                left = left,
                top = top,
                drawW = drawW,
                drawH = drawH
            )
        } else if (isFocused) {
            drawRect(
                color = highlightColor.copy(alpha = 0.22f),
                topLeft = Offset(left, top),
                size = Size(drawW, 10f)
            )
        }
    }

    // 4. Live active text selection range
    if (activeSelection != null && pageTextLayout != null && pageTextLayout.hasText) {
        val rects = pageTextLayout.rectsForRange(
            activeSelection.startCharOffset,
            activeSelection.endCharOffset
        )
        drawNormalizedRectList(
            rects = rects,
            color = palette.accent.copy(alpha = 0.28f),
            left = left,
            top = top,
            drawW = drawW,
            drawH = drawH
        )
    }
}
