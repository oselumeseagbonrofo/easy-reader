package com.ebookreader.app.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TextFormat
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ebookreader.app.domain.model.BookFormat
import com.ebookreader.app.ui.theme.ReaderPalette
import com.ebookreader.app.ui.theme.readerPaletteFor
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun ReaderScreen(
    viewModel: ReaderViewModel,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val palette = remember(uiState.settings.theme) { readerPaletteFor(uiState.settings.theme) }
    val snackbarHostState = remember { SnackbarHostState() }

    var isHudVisible by remember { mutableStateOf(false) }
    var activeSheet by remember { mutableStateOf<ReaderModalSheet?>(null) }
    var tocSheetInitialTab by remember { mutableIntStateOf(0) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
    ) {
        when {
            uiState.isLoading -> {
                Column(
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxSize()
                ) {
                    CircularProgressIndicator(color = palette.accent)
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = "Opening book…",
                        color = palette.onBackground,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            uiState.errorMessage != null -> {
                Column(
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp)
                ) {
                    Text(
                        text = uiState.errorMessage.orEmpty(),
                        color = palette.onBackground,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = onNavigateBack) {
                        Text("Back to Bookshelf")
                    }
                }
            }

            else -> {
                key(uiState.book?.id) {
                    val pagerState = rememberPagerState(
                        initialPage = uiState.currentPage.coerceIn(
                            0,
                            (uiState.totalPages - 1).coerceAtLeast(0)
                        ),
                        pageCount = { uiState.totalPages.coerceAtLeast(1) }
                    )

                    val lifecycleOwner = LocalLifecycleOwner.current
                    DisposableEffect(lifecycleOwner, pagerState) {
                        val observer = LifecycleEventObserver { _, event ->
                            if (event == Lifecycle.Event.ON_STOP) {
                                viewModel.syncCurrentPageBeforeExit(pagerState.targetPage)
                                viewModel.flushProgressImmediately()
                            }
                        }
                        lifecycleOwner.lifecycle.addObserver(observer)
                        onDispose {
                            lifecycleOwner.lifecycle.removeObserver(observer)
                            viewModel.syncCurrentPageBeforeExit(pagerState.targetPage)
                            viewModel.flushProgressImmediately()
                        }
                    }

                    LaunchedEffect(pagerState, uiState.isPaginating) {
                        if (uiState.isPaginating) return@LaunchedEffect
                        snapshotFlow { pagerState.settledPage }
                            .distinctUntilChanged()
                            .collect { settled ->
                                viewModel.onPageSettled(settled)
                            }
                    }

                    LaunchedEffect(viewModel, pagerState) {
                        var snackbarJob: Job? = null
                        viewModel.effects.collect { effect ->
                            when (effect) {
                                is ReaderEffect.ScrollToPage -> {
                                    val expectedTotal = viewModel.uiState.value.totalPages.coerceAtLeast(1)
                                    val desired = effect.pageIndex.coerceIn(0, expectedTotal - 1)
                                    if (pagerState.pageCount <= desired) {
                                        snapshotFlow { pagerState.pageCount }
                                            .first { it > desired || it >= expectedTotal }
                                    }
                                    val maxPage = (pagerState.pageCount - 1).coerceAtLeast(0)
                                    val target = desired.coerceIn(0, maxPage)
                                    if (pagerState.currentPage != target) {
                                        if (effect.animate) {
                                            pagerState.animateScrollToPage(target)
                                        } else {
                                            pagerState.scrollToPage(target)
                                        }
                                    }
                                    viewModel.onScrollEffectApplied(target)
                                }

                                is ReaderEffect.ShowSnackbar -> {
                                    snackbarJob?.cancel()
                                    snackbarJob = launch {
                                        snackbarHostState.currentSnackbarData?.dismiss()
                                        snackbarHostState.showSnackbar(effect.message)
                                    }
                                }
                            }
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                    ) {
                        BoxWithConstraints(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        ) {
                            val density = LocalDensity.current
                            val textMeasurer = rememberTextMeasurer()

                            val contentWidthPx = with(density) { (maxWidth - 44.dp).roundToPx() }
                            val contentHeightPx = with(density) { (maxHeight - 24.dp).roundToPx() }

                            if (uiState.book?.format == BookFormat.EPUB && uiState.epubBook != null) {
                                LaunchedEffect(
                                    uiState.epubBook,
                                    contentWidthPx,
                                    contentHeightPx,
                                    uiState.settings.fontSizeSp,
                                    uiState.settings.lineHeightMultiplier,
                                    uiState.settings.fontFamily
                                ) {
                                    viewModel.onEpubViewportReady(
                                        widthPx = contentWidthPx,
                                        heightPx = contentHeightPx,
                                        density = density,
                                        textMeasurer = textMeasurer
                                    )
                                }

                                val paginated = uiState.paginatedEpub
                                if (paginated == null || uiState.isPaginating) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        CircularProgressIndicator(color = palette.accent)
                                    }
                                } else {
                                    EpubPagerView(
                                        epubBook = uiState.epubBook!!,
                                        pages = paginated.pages,
                                        pagerState = pagerState,
                                        settings = uiState.settings,
                                        palette = palette,
                                        bookmarks = uiState.bookmarks,
                                        highlights = uiState.highlights,
                                        searchMatches = uiState.searchMatches,
                                        activeMatchIndex = uiState.activeMatchIndex,
                                        loadImage = viewModel::loadEpubImage,
                                        onApplyHighlight = viewModel::applyHighlight,
                                        onClearHighlight = viewModel::clearHighlightRange,
                                        onBookmarkSelection = viewModel::toggleSelectionBookmark,
                                        onSelectionActiveChanged = { active ->
                                            if (active) isHudVisible = false
                                        },
                                        onToggleHud = {
                                            if (uiState.isSearchVisible && uiState.isSearchResultsExpanded) {
                                                viewModel.setSearchResultsExpanded(false)
                                            } else {
                                                isHudVisible = !isHudVisible
                                            }
                                        }
                                    )
                                }
                            } else if (uiState.book?.format == BookFormat.PDF && uiState.pdfController != null) {
                                PdfPagerView(
                                    controller = uiState.pdfController!!,
                                    pagerState = pagerState,
                                    settings = uiState.settings,
                                    palette = palette,
                                    bookmarks = uiState.bookmarks,
                                    highlights = uiState.highlights,
                                    searchMatches = uiState.searchMatches,
                                    activeMatchIndex = uiState.activeMatchIndex,
                                    onApplyHighlight = viewModel::applyHighlight,
                                    onClearHighlight = viewModel::clearHighlightRange,
                                    onBookmarkSelection = viewModel::toggleSelectionBookmark,
                                    onSelectionActiveChanged = { active ->
                                        if (active) isHudVisible = false
                                    },
                                    onToggleHud = {
                                        if (uiState.isSearchVisible && uiState.isSearchResultsExpanded) {
                                            viewModel.setSearchResultsExpanded(false)
                                        } else {
                                            isHudVisible = !isHudVisible
                                        }
                                    }
                                )
                            }
                        }

                        AmbientReadingProgressStrip(
                            currentPage = uiState.currentPage,
                            totalPages = uiState.totalPages,
                            progress = uiState.progress,
                            chapterTitle = uiState.currentChapterTitle,
                            palette = palette
                        )
                    }

                    ReaderTopHudBar(
                        visible = isHudVisible && !uiState.isSearchVisible,
                        bookTitle = uiState.book?.title.orEmpty(),
                        chapterTitle = uiState.currentChapterTitle,
                        isBookmarked = uiState.isCurrentPageBookmarked,
                        palette = palette,
                        onBack = {
                            viewModel.syncCurrentPageBeforeExit(pagerState.targetPage)
                            viewModel.flushProgressImmediately()
                            onNavigateBack()
                        },
                        onOpenSearch = {
                            isHudVisible = false
                            viewModel.openSearch()
                        },
                        onToggleBookmark = viewModel::toggleBookmarkAtCurrentPage,
                        onOpenTocAndBookmarks = {
                            tocSheetInitialTab = 0
                            activeSheet = ReaderModalSheet.TOC_BOOKMARKS
                        },
                        onOpenSettings = {
                            activeSheet = ReaderModalSheet.SETTINGS
                        },
                        modifier = Modifier.align(Alignment.TopCenter)
                    )
                }

                AnimatedVisibility(
                    visible = uiState.isSearchVisible,
                    enter = fadeIn() + slideInVertically(initialOffsetY = { -it / 2 }),
                    exit = fadeOut() + slideOutVertically(targetOffsetY = { -it / 2 }),
                    modifier = Modifier.align(Alignment.TopCenter)
                ) {
                    InBookSearchOverlay(
                        query = uiState.searchQuery,
                        isSearching = uiState.isSearching,
                        matches = uiState.searchMatches,
                        activeMatchIndex = uiState.activeMatchIndex,
                        isResultsExpanded = uiState.isSearchResultsExpanded,
                        palette = palette,
                        onQueryChange = viewModel::onSearchQueryChange,
                        onSelectMatch = { idx -> viewModel.selectSearchMatch(idx) },
                        onStepMatch = { delta -> viewModel.navigateSearchMatch(delta) },
                        onToggleResultsExpanded = viewModel::setSearchResultsExpanded,
                        onClose = viewModel::closeSearch
                    )
                }

                InteractiveProgressScrubberHud(
                    visible = isHudVisible,
                    currentPage = uiState.currentPage,
                    totalPages = uiState.totalPages,
                    chapterTitleForPage = { pageIdx ->
                        when (uiState.book?.format) {
                            BookFormat.EPUB -> {
                                uiState.paginatedEpub?.pages?.getOrNull(pageIdx)?.chapterTitle.orEmpty()
                            }
                            BookFormat.PDF -> {
                                uiState.tocEntries.lastOrNull { it.pageIndex <= pageIdx }?.title
                                    ?: "Page ${pageIdx + 1}"
                            }
                            null -> ""
                        }
                    },
                    returnPageBeforeJump = uiState.returnPageBeforeJump,
                    palette = palette,
                    onJumpToPage = { target ->
                        viewModel.jumpToPage(target, recordReturnPoint = true, animate = false)
                    },
                    onJumpBack = viewModel::jumpBackToPreviousSpot,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = if (isHudVisible) 184.dp else 84.dp)
        )
    }

    when (activeSheet) {
        ReaderModalSheet.TOC_BOOKMARKS -> {
            TocAndBookmarksSheet(
                initialTab = tocSheetInitialTab,
                tocEntries = uiState.tocEntries,
                bookmarks = uiState.bookmarks,
                highlights = uiState.highlights,
                currentPage = uiState.currentPage,
                onSelectTocEntry = { entry ->
                    viewModel.jumpToPage(entry.pageIndex, recordReturnPoint = true, animate = false)
                },
                onSelectBookmark = { bm ->
                    viewModel.jumpToBookmark(bm)
                },
                onDeleteBookmark = viewModel::deleteBookmark,
                onSelectHighlight = { hl ->
                    viewModel.jumpToHighlight(hl)
                },
                onDeleteHighlight = viewModel::deleteHighlight,
                onDismiss = { activeSheet = null }
            )
        }

        ReaderModalSheet.SETTINGS -> {
            ReaderSettingsSheet(
                bookFormat = uiState.book?.format ?: BookFormat.EPUB,
                settings = uiState.settings,
                onSelectTheme = viewModel::setTheme,
                onSelectFontFamily = viewModel::setFontFamily,
                onFontSizeChange = viewModel::setFontSize,
                onLineHeightChange = viewModel::setLineHeight,
                onPdfFitModeChange = viewModel::setPdfFitMode,
                onPdfTrimMarginsChange = viewModel::setPdfTrimMargins,
                onPdfInvertChange = viewModel::setPdfInvertInDark,
                onDismiss = { activeSheet = null }
            )
        }

        null -> Unit
    }
}

private enum class ReaderModalSheet {
    TOC_BOOKMARKS,
    SETTINGS
}

@Composable
private fun ReaderTopHudBar(
    visible: Boolean,
    bookTitle: String,
    chapterTitle: String,
    isBookmarked: Boolean,
    palette: ReaderPalette,
    onBack: () -> Unit,
    onOpenSearch: () -> Unit,
    onToggleBookmark: () -> Unit,
    onOpenTocAndBookmarks: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically(initialOffsetY = { -it / 2 }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { -it / 2 }),
        modifier = modifier
    ) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = palette.hudContainer,
            contentColor = palette.onHudContainer,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 6.dp)
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back to bookshelf"
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = bookTitle,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (chapterTitle.isNotBlank()) {
                        Text(
                            text = chapterTitle,
                            style = MaterialTheme.typography.labelMedium,
                            color = palette.onHudContainer.copy(alpha = 0.72f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(Modifier.width(4.dp))

                IconButton(onClick = onOpenSearch) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search in book"
                    )
                }

                IconButton(onClick = onToggleBookmark) {
                    Icon(
                        imageVector = if (isBookmarked) {
                            Icons.Default.Bookmark
                        } else {
                            Icons.Default.BookmarkBorder
                        },
                        tint = if (isBookmarked) palette.accent else palette.onHudContainer,
                        contentDescription = "Toggle bookmark"
                    )
                }

                IconButton(onClick = onOpenTocAndBookmarks) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.FormatListBulleted,
                        contentDescription = "Contents and bookmarks"
                    )
                }

                IconButton(onClick = onOpenSettings) {
                    Icon(
                        imageVector = Icons.Default.TextFormat,
                        contentDescription = "Appearance settings"
                    )
                }
            }
        }
    }
}
