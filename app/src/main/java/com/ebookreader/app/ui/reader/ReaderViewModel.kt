package com.ebookreader.app.ui.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ebookreader.app.data.local.ReaderPreferencesRepository
import com.ebookreader.app.data.parser.epub.EpubBook
import com.ebookreader.app.data.parser.epub.EpubPaginator
import com.ebookreader.app.data.parser.epub.EpubParser
import com.ebookreader.app.data.parser.epub.EpubSearchEngine
import com.ebookreader.app.data.parser.epub.PaginatedEpub
import com.ebookreader.app.data.parser.pdf.PdfDocumentController
import com.ebookreader.app.data.parser.pdf.PdfSearchEngine
import com.ebookreader.app.data.repository.LibraryRepository
import com.ebookreader.app.domain.model.Book
import com.ebookreader.app.domain.model.BookFormat
import com.ebookreader.app.domain.model.Bookmark
import com.ebookreader.app.domain.model.ReaderFontFamily
import com.ebookreader.app.domain.model.ReaderSettings
import com.ebookreader.app.domain.model.ReaderTheme
import com.ebookreader.app.domain.model.ReadingProgressCalculator
import com.ebookreader.app.domain.model.SearchMatch
import com.ebookreader.app.domain.model.TocEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import kotlin.math.abs

data class ReaderUiState(
    val book: Book? = null,
    val isLoading: Boolean = true,
    val isPaginating: Boolean = false,
    val errorMessage: String? = null,
    val epubBook: EpubBook? = null,
    val paginatedEpub: PaginatedEpub? = null,
    val pdfController: PdfDocumentController? = null,
    val tocEntries: List<TocEntry> = emptyList(),
    val bookmarks: List<Bookmark> = emptyList(),
    val currentPage: Int = 0,
    val totalPages: Int = 0,
    val progress: Float = 0f,
    val currentChapterTitle: String = "",
    val settings: ReaderSettings = ReaderSettings(),
    val returnPageBeforeJump: Int? = null,
    val isSearchVisible: Boolean = false,
    val isSearchResultsExpanded: Boolean = true,
    val searchQuery: String = "",
    val isSearching: Boolean = false,
    val searchMatches: List<SearchMatch> = emptyList(),
    val activeMatchIndex: Int = -1
) {
    val isCurrentPageBookmarked: Boolean
        get() = bookmarks.any { it.pageIndex == currentPage }
}

sealed interface ReaderEffect {
    data class ScrollToPage(val pageIndex: Int, val animate: Boolean = false) : ReaderEffect
    data class ShowSnackbar(val message: String) : ReaderEffect
}

class ReaderViewModel(
    private val appContext: Context,
    private val bookId: String,
    private val libraryRepository: LibraryRepository,
    private val preferencesRepository: ReaderPreferencesRepository
) : ViewModel() {

    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<ReaderEffect>(extraBufferCapacity = 8)
    val effects = _effects.asSharedFlow()

    private var saveProgressJob: Job? = null
    private var searchJob: Job? = null
    private var returnChipTimeoutJob: Job? = null
    private var paginationJob: Job? = null

    private var lastPaginationSignature: String? = null
    private var initialPositionRestored: Boolean = false
    private var savedAnchorChapter: Int = 0
    private var savedAnchorCharOffset: Int = 0

    private val epubImageCache = object : LruCache<String, Bitmap>(12 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    init {
        observePreferences()
        observeBookmarks()
        loadBook()
    }

    private fun observePreferences() {
        viewModelScope.launch {
            preferencesRepository.settingsFlow.collect { newSettings ->
                _uiState.update { it.copy(settings = newSettings) }
            }
        }
    }

    private fun observeBookmarks() {
        viewModelScope.launch {
            libraryRepository.observeBookmarks(bookId).collect { list ->
                _uiState.update { it.copy(bookmarks = list) }
            }
        }
    }

    private fun loadBook() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }

            val initialSettings = preferencesRepository.settingsFlow.first()
            _uiState.update { it.copy(settings = initialSettings) }

            val book = libraryRepository.getBookById(bookId)
            if (book == null) {
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = "Book not found in library.")
                }
                return@launch
            }

            if (!book.isFileAccessible) {
                _uiState.update {
                    it.copy(
                        book = book,
                        isLoading = false,
                        errorMessage = "The external ebook file was moved or is currently inaccessible. Return to the Bookshelf to re-link it."
                    )
                }
                return@launch
            }

            savedAnchorChapter = book.epubChapterIndex
            savedAnchorCharOffset = book.epubCharOffset

            when (book.format) {
                BookFormat.PDF -> openPdfBook(book)
                BookFormat.EPUB -> openEpubBook(book)
            }
        }
    }

    private suspend fun openPdfBook(book: Book) {
        runCatching {
            val uri = Uri.parse(book.fileUri)
            val controller = withContext(Dispatchers.IO) {
                PdfDocumentController.open(appContext, uri)
            }
            val total = controller.pageCount.coerceAtLeast(1)
            val startPage = book.currentPage.coerceIn(0, total - 1)
            val progress = ReadingProgressCalculator.computeProgress(startPage, total)

            _uiState.update {
                it.copy(
                    book = book,
                    isLoading = false,
                    pdfController = controller,
                    currentPage = startPage,
                    totalPages = total,
                    progress = progress,
                    currentChapterTitle = "Page ${startPage + 1}"
                )
            }
            initialPositionRestored = true
            _effects.emit(ReaderEffect.ScrollToPage(startPage, animate = false))
            scheduleProgressSave(startPage, total, startPage, 0)

            // Load PDF outline asynchronously so first page renders immediately
            viewModelScope.launch {
                val outline = controller.extractOutline()
                if (outline.isNotEmpty()) {
                    _uiState.update { state ->
                        val chapterTitle = resolvePdfSectionTitle(outline, state.currentPage)
                        state.copy(
                            tocEntries = outline,
                            currentChapterTitle = chapterTitle
                        )
                    }
                }
            }
        }.onFailure { err ->
            _uiState.update {
                it.copy(
                    book = book,
                    isLoading = false,
                    errorMessage = err.message ?: "Failed to open PDF document."
                )
            }
        }
    }

    private suspend fun openEpubBook(book: Book) {
        runCatching {
            val uri = Uri.parse(book.fileUri)
            val parsedEpub = withContext(Dispatchers.IO) {
                EpubParser.parseBook(
                    inputStreamProvider = {
                        appContext.contentResolver.openInputStream(uri)
                            ?: throw FileNotFoundException("Unable to read EPUB URI: $uri")
                    },
                    fallbackTitle = book.title
                )
            }
            _uiState.update {
                it.copy(
                    book = book,
                    epubBook = parsedEpub,
                    isLoading = false,
                    isPaginating = true
                )
            }
        }.onFailure { err ->
            _uiState.update {
                it.copy(
                    book = book,
                    isLoading = false,
                    errorMessage = err.message ?: "Failed to parse EPUB book."
                )
            }
        }
    }

    fun onEpubViewportReady(
        widthPx: Int,
        heightPx: Int,
        density: Density,
        textMeasurer: TextMeasurer
    ) {
        val state = _uiState.value
        val epub = state.epubBook ?: return
        if (widthPx <= 0 || heightPx <= 0) return

        val settings = state.settings
        val signature = "$widthPx:$heightPx:${settings.fontSizeSp}:${settings.lineHeightMultiplier}:${settings.fontFamily.name}"
        if (signature == lastPaginationSignature && state.paginatedEpub != null) return

        // Capture current anchor before re-paginating on font/size/rotation change
        state.paginatedEpub?.pages?.getOrNull(state.currentPage)?.let { currentPageObj ->
            if (initialPositionRestored) {
                savedAnchorChapter = currentPageObj.chapterIndex
                savedAnchorCharOffset = currentPageObj.startCharOffset
            }
        }

        lastPaginationSignature = signature
        paginationJob?.cancel()
        paginationJob = viewModelScope.launch {
            _uiState.update { it.copy(isPaginating = true) }

            val paginated = withContext(Dispatchers.Default) {
                EpubPaginator.paginate(
                    book = epub,
                    textMeasurer = textMeasurer,
                    viewportWidthPx = widthPx,
                    viewportHeightPx = heightPx,
                    density = density,
                    settings = settings
                )
            }

            val total = paginated.pages.size.coerceAtLeast(1)
            val targetPage = if (!initialPositionRestored) {
                val book = state.book
                if (book != null && book.isStarted) {
                    paginated.findPageForAnchor(savedAnchorChapter, savedAnchorCharOffset)
                } else {
                    0
                }
            } else {
                paginated.findPageForAnchor(savedAnchorChapter, savedAnchorCharOffset)
            }.coerceIn(0, total - 1)

            initialPositionRestored = true
            val pageObj = paginated.pages[targetPage]
            val progress = ReadingProgressCalculator.computeProgress(targetPage, total)

            // Re-map any active EPUB search matches to new page numbers
            val remappedMatches = if (state.searchMatches.isNotEmpty()) {
                state.searchMatches.map { match ->
                    match.copy(
                        pageIndex = paginated.findPageForAnchor(
                            match.chapterIndex,
                            match.charOffsetInChapter
                        )
                    )
                }
            } else {
                emptyList()
            }

            _uiState.update {
                it.copy(
                    isPaginating = false,
                    paginatedEpub = paginated,
                    tocEntries = paginated.tocEntries,
                    currentPage = targetPage,
                    totalPages = total,
                    progress = progress,
                    currentChapterTitle = pageObj.chapterTitle,
                    searchMatches = remappedMatches
                )
            }

            _effects.emit(ReaderEffect.ScrollToPage(targetPage, animate = false))
            scheduleProgressSave(
                currentPage = targetPage,
                totalPages = total,
                chapterIndex = pageObj.chapterIndex,
                charOffset = pageObj.startCharOffset
            )
        }
    }

    fun onPageSettled(pageIndex: Int) {
        val state = _uiState.value
        if (state.totalPages <= 0) return
        val clamped = pageIndex.coerceIn(0, state.totalPages - 1)
        val progress = ReadingProgressCalculator.computeProgress(clamped, state.totalPages)

        val (chapterTitle, chapterIdx, charOffset) = when (state.book?.format) {
            BookFormat.EPUB -> {
                val epubPage = state.paginatedEpub?.pages?.getOrNull(clamped)
                val chIdx = epubPage?.chapterIndex ?: 0
                val chOff = epubPage?.startCharOffset ?: 0
                savedAnchorChapter = chIdx
                savedAnchorCharOffset = chOff
                Triple(epubPage?.chapterTitle.orEmpty(), chIdx, chOff)
            }
            BookFormat.PDF -> {
                val title = resolvePdfSectionTitle(state.tocEntries, clamped)
                Triple(title, clamped, 0)
            }
            null -> Triple("", 0, 0)
        }

        _uiState.update {
            it.copy(
                currentPage = clamped,
                progress = progress,
                currentChapterTitle = chapterTitle
            )
        }

        scheduleProgressSave(clamped, state.totalPages, chapterIdx, charOffset)
    }

    fun jumpToPage(targetPage: Int, recordReturnPoint: Boolean = true, animate: Boolean = false) {
        val state = _uiState.value
        if (state.totalPages <= 0) return
        val clamped = targetPage.coerceIn(0, state.totalPages - 1)
        val originPage = state.currentPage

        if (recordReturnPoint && abs(clamped - originPage) >= 2) {
            _uiState.update { it.copy(returnPageBeforeJump = originPage) }
            returnChipTimeoutJob?.cancel()
            returnChipTimeoutJob = viewModelScope.launch {
                delay(8_500)
                _uiState.update { it.copy(returnPageBeforeJump = null) }
            }
        }

        onPageSettled(clamped)
        viewModelScope.launch {
            _effects.emit(ReaderEffect.ScrollToPage(clamped, animate = animate))
        }
    }

    fun jumpBackToPreviousSpot() {
        val origin = _uiState.value.returnPageBeforeJump ?: return
        returnChipTimeoutJob?.cancel()
        _uiState.update { it.copy(returnPageBeforeJump = null) }
        jumpToPage(origin, recordReturnPoint = false, animate = true)
    }

    fun jumpToBookmark(bookmark: Bookmark) {
        val state = _uiState.value
        val resolvedPage = if (state.book?.format == BookFormat.EPUB && state.paginatedEpub != null) {
            state.paginatedEpub.findPageForAnchor(bookmark.epubChapterIndex, bookmark.epubCharOffset)
        } else {
            bookmark.pageIndex
        }
        jumpToPage(resolvedPage, recordReturnPoint = true, animate = false)
    }

    fun toggleBookmarkAtCurrentPage() {
        val state = _uiState.value
        val book = state.book ?: return
        if (state.totalPages <= 0) return

        viewModelScope.launch {
            val pageIdx = state.currentPage
            val (chapterIdx, charOffset, chapterTitle, snippet) = when (book.format) {
                BookFormat.EPUB -> {
                    val page = state.paginatedEpub?.pages?.getOrNull(pageIdx)
                    BookmarkCapture(
                        chapterIdx = page?.chapterIndex ?: 0,
                        charOffset = page?.startCharOffset ?: 0,
                        chapterTitle = page?.chapterTitle ?: book.title,
                        snippet = page?.previewText ?: "Page ${pageIdx + 1}"
                    )
                }
                BookFormat.PDF -> {
                    val excerpt = state.pdfController?.extractPageSnippet(pageIdx).orEmpty()
                    BookmarkCapture(
                        chapterIdx = pageIdx,
                        charOffset = 0,
                        chapterTitle = state.currentChapterTitle.ifBlank { "Page ${pageIdx + 1}" },
                        snippet = excerpt.ifBlank { "Page ${pageIdx + 1}" }
                    )
                }
            }

            val added = libraryRepository.toggleBookmark(
                bookId = book.id,
                pageIndex = pageIdx,
                totalPages = state.totalPages,
                epubChapterIndex = chapterIdx,
                epubCharOffset = charOffset,
                chapterTitle = chapterTitle,
                snippet = snippet
            )
            _effects.emit(
                ReaderEffect.ShowSnackbar(
                    if (added) "Bookmark saved for page ${pageIdx + 1}" else "Bookmark removed"
                )
            )
        }
    }

    fun deleteBookmark(bookmarkId: Long) {
        viewModelScope.launch {
            libraryRepository.deleteBookmark(bookmarkId)
        }
    }

    // --- Search ---

    fun openSearch() {
        _uiState.update {
            it.copy(isSearchVisible = true, isSearchResultsExpanded = true)
        }
    }

    fun closeSearch() {
        searchJob?.cancel()
        _uiState.update {
            it.copy(
                isSearchVisible = false,
                isSearchResultsExpanded = false,
                searchQuery = "",
                isSearching = false,
                searchMatches = emptyList(),
                activeMatchIndex = -1
            )
        }
    }

    fun setSearchResultsExpanded(expanded: Boolean) {
        _uiState.update { it.copy(isSearchResultsExpanded = expanded) }
    }

    fun onSearchQueryChange(newQuery: String) {
        _uiState.update {
            it.copy(searchQuery = newQuery, isSearchResultsExpanded = true)
        }
        searchJob?.cancel()
        if (newQuery.trim().length < 2) {
            _uiState.update {
                it.copy(isSearching = false, searchMatches = emptyList(), activeMatchIndex = -1)
            }
            return
        }

        searchJob = viewModelScope.launch {
            delay(280)
            _uiState.update { it.copy(isSearching = true) }

            val state = _uiState.value
            val matches = when (state.book?.format) {
                BookFormat.EPUB -> {
                    val epub = state.epubBook
                    if (epub != null) {
                        withContext(Dispatchers.Default) {
                            EpubSearchEngine.search(epub, state.paginatedEpub, newQuery)
                        }
                    } else {
                        emptyList()
                    }
                }
                BookFormat.PDF -> {
                    val controller = state.pdfController
                    if (controller != null) {
                        PdfSearchEngine.search(appContext, controller, newQuery)
                    } else {
                        emptyList()
                    }
                }
                null -> emptyList()
            }

            _uiState.update {
                it.copy(
                    isSearching = false,
                    searchMatches = matches,
                    activeMatchIndex = if (matches.isNotEmpty()) 0 else -1
                )
            }
        }
    }

    fun selectSearchMatch(matchIndex: Int, collapseResultsList: Boolean = true) {
        val state = _uiState.value
        val match = state.searchMatches.getOrNull(matchIndex) ?: return
        _uiState.update {
            it.copy(
                activeMatchIndex = matchIndex,
                isSearchResultsExpanded = if (collapseResultsList) false else it.isSearchResultsExpanded
            )
        }
        jumpToPage(match.pageIndex, recordReturnPoint = false, animate = false)
    }

    fun navigateSearchMatch(delta: Int) {
        val matches = _uiState.value.searchMatches
        if (matches.isEmpty()) return
        val current = _uiState.value.activeMatchIndex.coerceAtLeast(0)
        val next = (current + delta).mod(matches.size)
        selectSearchMatch(next, collapseResultsList = true)
    }

    // --- Reader Appearance Settings ---

    fun setTheme(theme: ReaderTheme) {
        viewModelScope.launch { preferencesRepository.updateTheme(theme) }
    }

    fun setFontSize(sizeSp: Float) {
        viewModelScope.launch { preferencesRepository.updateFontSize(sizeSp) }
    }

    fun setLineHeight(multiplier: Float) {
        viewModelScope.launch { preferencesRepository.updateLineHeight(multiplier) }
    }

    fun setFontFamily(family: ReaderFontFamily) {
        viewModelScope.launch { preferencesRepository.updateFontFamily(family) }
    }

    fun setPdfInvertInDark(invert: Boolean) {
        viewModelScope.launch { preferencesRepository.updatePdfInvertInDark(invert) }
    }

    // --- EPUB On-Demand Image Loading ---

    suspend fun loadEpubImage(zipEntryPath: String): Bitmap? = withContext(Dispatchers.IO) {
        synchronized(epubImageCache) {
            epubImageCache.get(zipEntryPath)?.takeIf { !it.isRecycled }?.let { return@withContext it }
        }
        val uriString = _uiState.value.book?.fileUri ?: return@withContext null
        val uri = Uri.parse(uriString)
        val bytes = runCatching {
            EpubParser.readSingleZipEntry(
                inputStreamProvider = {
                    appContext.contentResolver.openInputStream(uri)
                        ?: throw FileNotFoundException("Cannot open EPUB image stream")
                },
                targetZipPath = zipEntryPath
            )
        }.getOrNull() ?: return@withContext null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

        var sample = 1
        while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) {
            sample *= 2
        }
        val bmp = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return@withContext null

        synchronized(epubImageCache) {
            epubImageCache.put(zipEntryPath, bmp)
        }
        bmp
    }

    private fun resolvePdfSectionTitle(outline: List<TocEntry>, pageIndex: Int): String {
        if (outline.isEmpty()) return "Page ${pageIndex + 1}"
        val activeEntry = outline.lastOrNull { it.pageIndex <= pageIndex }
        return activeEntry?.title ?: "Page ${pageIndex + 1}"
    }

    private fun scheduleProgressSave(
        currentPage: Int,
        totalPages: Int,
        chapterIndex: Int,
        charOffset: Int
    ) {
        saveProgressJob?.cancel()
        saveProgressJob = viewModelScope.launch {
            delay(250)
            libraryRepository.saveReadingProgress(
                bookId = bookId,
                currentPage = currentPage,
                totalPages = totalPages,
                epubChapterIndex = chapterIndex,
                epubCharOffset = charOffset
            )
        }
    }

    fun flushProgressImmediately() {
        val state = _uiState.value
        val book = state.book ?: return
        if (state.totalPages <= 0) return

        val (chapterIdx, charOffset) = if (book.format == BookFormat.EPUB) {
            val epubPage = state.paginatedEpub?.pages?.getOrNull(state.currentPage)
            (epubPage?.chapterIndex ?: savedAnchorChapter) to (epubPage?.startCharOffset ?: savedAnchorCharOffset)
        } else {
            state.currentPage to 0
        }

        persistenceScope.launch {
            libraryRepository.saveReadingProgress(
                bookId = book.id,
                currentPage = state.currentPage,
                totalPages = state.totalPages,
                epubChapterIndex = chapterIdx,
                epubCharOffset = charOffset
            )
        }
    }

    override fun onCleared() {
        flushProgressImmediately()
        synchronized(epubImageCache) {
            epubImageCache.evictAll()
        }
        _uiState.value.pdfController?.close()
        super.onCleared()
    }

    private data class BookmarkCapture(
        val chapterIdx: Int,
        val charOffset: Int,
        val chapterTitle: String,
        val snippet: String
    )

    companion object {
        fun factory(
            context: Context,
            bookId: String,
            libraryRepository: LibraryRepository,
            preferencesRepository: ReaderPreferencesRepository
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ReaderViewModel(
                    appContext = context.applicationContext,
                    bookId = bookId,
                    libraryRepository = libraryRepository,
                    preferencesRepository = preferencesRepository
                ) as T
            }
        }
    }
}
