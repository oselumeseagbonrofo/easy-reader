package com.ebookreader.app

import android.content.ContextWrapper
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.compose.ui.text.AnnotatedString
import com.ebookreader.app.data.local.BookDao
import com.ebookreader.app.data.local.BookEntity
import com.ebookreader.app.data.local.BookmarkDao
import com.ebookreader.app.data.local.BookmarkEntity
import com.ebookreader.app.data.local.HighlightDao
import com.ebookreader.app.data.local.HighlightEntity
import com.ebookreader.app.data.local.ReaderPreferencesSource
import com.ebookreader.app.data.parser.epub.EpubBlock
import com.ebookreader.app.data.parser.epub.EpubBook
import com.ebookreader.app.data.parser.epub.EpubChapter
import com.ebookreader.app.data.parser.epub.EpubPage
import com.ebookreader.app.data.parser.epub.EpubPageItem
import com.ebookreader.app.data.parser.epub.EpubPaginator
import com.ebookreader.app.data.parser.epub.EpubRandomAccessArchive
import com.ebookreader.app.data.parser.epub.EpubRawTocItem
import com.ebookreader.app.data.parser.epub.EpubSelectionHelper
import com.ebookreader.app.data.parser.epub.EpubTextKind
import com.ebookreader.app.data.parser.pdf.PdfDocumentAccess
import com.ebookreader.app.data.parser.pdf.PdfSearchEngine
import com.ebookreader.app.data.parser.pdf.PdfSessionLifecycle
import com.ebookreader.app.data.repository.LibraryExternalContent
import com.ebookreader.app.data.repository.LibraryRepository
import com.ebookreader.app.data.repository.LibraryShelfSource
import com.ebookreader.app.data.repository.ReaderBookRepository
import com.ebookreader.app.domain.model.Book
import com.ebookreader.app.domain.model.BookFormat
import com.ebookreader.app.domain.model.Bookmark
import com.ebookreader.app.domain.model.HighlightColor
import com.ebookreader.app.domain.model.PdfFitMode
import com.ebookreader.app.domain.model.ReaderFontFamily
import com.ebookreader.app.domain.model.ReaderSettings
import com.ebookreader.app.domain.model.ReaderTheme
import com.ebookreader.app.domain.model.SearchMatch
import com.ebookreader.app.domain.model.TextHighlight
import com.ebookreader.app.ui.library.LibraryEvent
import com.ebookreader.app.ui.library.LibraryViewModel
import com.ebookreader.app.ui.reader.PdfZoomGestureHelper
import com.ebookreader.app.ui.reader.ReaderEffect
import com.ebookreader.app.ui.reader.ReaderViewModel
import com.ebookreader.app.ui.reader.ZoomPanState
import com.ebookreader.app.ui.reader.applyScrollToPageEffect
import com.ebookreader.app.ui.reader.isBottomScrubberVisible
import com.ebookreader.app.ui.reader.nextHudStateOnContentTap
import com.ebookreader.app.ui.reader.resetScrubberUiOnHide
import com.ebookreader.app.ui.reader.resolveBookmarkedEpubPageIndices
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.StandardOpenOption
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
class StateMachineBugReproductionTest {

    private lateinit var mainThread: kotlinx.coroutines.ExecutorCoroutineDispatcher

    @Before
    fun setUpMainDispatcher() {
        mainThread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        Dispatchers.setMain(mainThread)
    }

    @After
    fun tearDownMainDispatcher() {
        Dispatchers.resetMain()
        mainThread.close()
    }

    // --- Test Fakes ---

    private class FakePreferencesSource : ReaderPreferencesSource {
        override val settingsFlow = MutableStateFlow(ReaderSettings())
        override suspend fun updateTheme(theme: ReaderTheme) = Unit
        override suspend fun updateFontSize(sizeSp: Float) = Unit
        override suspend fun updateLineHeight(multiplier: Float) = Unit
        override suspend fun updateFontFamily(family: ReaderFontFamily) = Unit
        override suspend fun updatePdfInvertInDark(invert: Boolean) = Unit
        override suspend fun updatePdfFitMode(mode: PdfFitMode) = Unit
        override suspend fun updatePdfTrimMargins(trim: Boolean) = Unit
    }

    private data class ProgressWriteRecord(
        val currentPage: Int,
        val totalPages: Int,
        val progress: Float,
        val chapterIndex: Int,
        val charOffset: Int
    )

    private class FakeReaderRepository(initialBook: Book) : ReaderBookRepository {
        val bookState = MutableStateFlow(initialBook)
        val bookmarksFlow = MutableStateFlow<List<Bookmark>>(emptyList())
        val highlightsFlow = MutableStateFlow<List<TextHighlight>>(emptyList())
        val progressWrites = CopyOnWriteArrayList<ProgressWriteRecord>()
        var onSaveProgressDelay: suspend (currentPage: Int) -> Unit = {}
        private val nextId = AtomicLong(1L)

        override suspend fun getBookById(bookId: String): Book? =
            bookState.value.takeIf { it.id == bookId }

        override suspend fun saveReadingProgress(
            bookId: String,
            currentPage: Int,
            totalPages: Int,
            epubChapterIndex: Int,
            epubCharOffset: Int
        ) {
            onSaveProgressDelay(currentPage)
            if (totalPages <= 0) return
            val clamped = currentPage.coerceIn(0, totalPages - 1)
            val prog = com.ebookreader.app.domain.model.ReadingProgressCalculator.computeProgress(clamped, totalPages)
            progressWrites += ProgressWriteRecord(clamped, totalPages, prog, epubChapterIndex, epubCharOffset)
            bookState.value = bookState.value.copy(
                currentPage = clamped,
                totalPages = totalPages,
                progress = prog,
                epubChapterIndex = epubChapterIndex,
                epubCharOffset = epubCharOffset,
                lastReadAt = System.currentTimeMillis()
            )
        }

        override fun observeBookmarks(bookId: String): Flow<List<Bookmark>> = bookmarksFlow
        override fun observeHighlights(bookId: String): Flow<List<TextHighlight>> = highlightsFlow

        override suspend fun toggleBookmark(
            bookId: String,
            pageIndex: Int,
            totalPages: Int,
            epubChapterIndex: Int,
            epubCharOffset: Int,
            chapterTitle: String,
            snippet: String
        ): Boolean {
            val list = bookmarksFlow.value.toMutableList()
            val existing = list.firstOrNull { !it.isTextSelection && it.pageIndex == pageIndex }
            return if (existing != null) {
                list.remove(existing)
                bookmarksFlow.value = list
                false
            } else {
                list += Bookmark(
                    id = nextId.getAndIncrement(),
                    bookId = bookId,
                    pageIndex = pageIndex,
                    epubChapterIndex = epubChapterIndex,
                    epubCharOffset = epubCharOffset,
                    epubEndCharOffset = epubCharOffset,
                    progress = com.ebookreader.app.domain.model.ReadingProgressCalculator.computeProgress(
                        pageIndex,
                        totalPages
                    ),
                    chapterTitle = chapterTitle,
                    snippet = snippet
                )
                bookmarksFlow.value = list
                true
            }
        }

        override suspend fun toggleSelectionBookmark(
            bookId: String,
            pageIndex: Int,
            totalPages: Int,
            chapterIndex: Int,
            startCharOffset: Int,
            endCharOffset: Int,
            chapterTitle: String,
            selectedText: String
        ): Boolean = true

        override suspend fun deleteBookmark(bookmarkId: Long) {
            bookmarksFlow.value = bookmarksFlow.value.filterNot { it.id == bookmarkId }
        }

        override suspend fun saveHighlight(
            bookId: String,
            pageIndex: Int,
            chapterIndex: Int,
            startCharOffset: Int,
            endCharOffset: Int,
            chapterTitle: String,
            selectedText: String,
            color: HighlightColor
        ) = Unit

        override suspend fun clearHighlightsInRange(
            bookId: String,
            chapterIndex: Int,
            startCharOffset: Int,
            endCharOffset: Int
        ) = Unit

        override suspend fun deleteHighlight(highlightId: Long) = Unit
    }

    private fun buildSyntheticEpubBook(chapterCount: Int, searchTerm: String? = null): EpubBook {
        val chapters = (0 until chapterCount).map { idx ->
            val p1 = if (searchTerm != null && idx == chapterCount - 1) {
                "Intro paragraph $idx with $searchTerm inside."
            } else {
                "First section of chapter $idx."
            }
            val p2 = "Second section of chapter $idx continues reading."
            val p3 = "Third section of chapter $idx ends chapter."
            val plain = "$p1\n\n$p2\n\n$p3"
            val s1 = 0
            val e1 = p1.length
            val s2 = e1 + 2
            val e2 = s2 + p2.length
            val s3 = e2 + 2
            val e3 = s3 + p3.length
            EpubChapter(
                index = idx,
                zipPath = "Text/ch$idx.xhtml",
                title = "Chapter ${idx + 1}",
                blocks = listOf(
                    EpubBlock.ParagraphBlock(AnnotatedString(p1), EpubTextKind.PARAGRAPH, s1, e1),
                    EpubBlock.ParagraphBlock(AnnotatedString(p2), EpubTextKind.PARAGRAPH, s2, e2),
                    EpubBlock.ParagraphBlock(AnnotatedString(p3), EpubTextKind.PARAGRAPH, s3, e3)
                ),
                plainText = plain
            )
        }
        val rawToc = chapters.map {
            EpubRawTocItem(title = it.title, zipPath = it.zipPath, fragmentId = null, depth = 0)
        }
        return EpubBook(title = "Test Book", author = "Author", chapters = chapters, rawToc = rawToc)
    }

    private fun paginateChapterOneParaPerPage(chapter: EpubChapter): List<EpubPage> {
        val paras = chapter.blocks.filterIsInstance<EpubBlock.ParagraphBlock>()
        return paras.mapIndexed { idx, para ->
            EpubPage(
                globalPageIndex = idx,
                chapterIndex = chapter.index,
                chapterTitle = chapter.title,
                pageInChapter = idx,
                startCharOffset = para.charStartInChapter,
                endCharOffset = para.charEndInChapter,
                items = listOf(
                    EpubPageItem.TextSlice(
                        text = para.annotatedText,
                        kind = para.kind,
                        chapterCharStart = para.charStartInChapter,
                        chapterCharEnd = para.charEndInChapter
                    )
                ),
                previewText = para.annotatedText.text
            )
        }
    }

    private suspend fun awaitState(
        vm: ReaderViewModel,
        timeoutMs: Long = 2_000,
        predicate: (com.ebookreader.app.ui.reader.ReaderUiState) -> Boolean
    ) {
        withTimeout(timeoutMs) {
            vm.uiState.first(predicate)
        }
    }

    // =====================================================================
    // ISSUE 1: Reader Pager Scroll Sync, Effect Collector & Exit Persistence
    // =====================================================================

    @Test
    fun issue1_bug1_1_animateScrollCancellationMustNotKillEffectCollectorAndFreezePageSettled(): Unit = runBlocking {
        val book = Book(
            id = "b1", title = "T", author = "A", format = BookFormat.EPUB,
            fileUri = "file:///b1.epub", coverPath = null,
            currentPage = 0, totalPages = 3, progress = 0f,
            epubChapterIndex = 0, epubCharOffset = 0, addedAt = 1L, lastReadAt = null
        )
        val repo = FakeReaderRepository(book)
        val epub = buildSyntheticEpubBook(chapterCount = 1)
        val vm = ReaderViewModel(
            appContext = ContextWrapper(null),
            bookId = "b1",
            libraryRepository = repo,
            preferencesRepository = FakePreferencesSource(),
            epubLoader = { epub to null },
            chapterPaginator = { ch, _, _, _, _, _ -> paginateChapterOneParaPerPage(ch) }
        )
        awaitState(vm) { !it.isLoading && it.epubBook != null }
        vm.onEpubViewportReadyForTest(800, 1200)
        awaitState(vm) { !it.isPaginating && it.totalPages == 3 }

        val appliedEffects = CopyOnWriteArrayList<Int>()
        var collectorDied = false
        val collectorJob = launch {
            try {
                vm.effects.collect { effect ->
                    if (effect is ReaderEffect.ScrollToPage) {
                        var firstCall = true
                        applyScrollToPageEffect(
                            effect = effect,
                            liveTotalPages = { vm.uiState.value.totalPages },
                            livePagerPageCount = { vm.uiState.value.totalPages },
                            pageCountFlow = MutableStateFlow(vm.uiState.value.totalPages),
                            livePagerCurrentPage = { 0 },
                            performScroll = { _, animate ->
                                if (animate && firstCall) {
                                    firstCall = false
                                    // Simulates Compose MutatorMutex cancelling animateScrollToPage when user touches pager
                                    throw CancellationException("MutatorMutex interrupted")
                                }
                            },
                            onApplied = { target ->
                                appliedEffects += target
                                vm.onScrollEffectApplied(target)
                            }
                        )
                    }
                }
            } catch (_: CancellationException) {
                collectorDied = true
            }
        }
        delay(30)

        // Trigger animated jump that gets interrupted by user touch
        vm.jumpToPage(2, recordReturnPoint = false, animate = true)
        delay(50)

        assertFalse("BUG-1.1: Effect collector died on MutatorMutex CancellationException!", collectorDied)

        // Subsequent page swipe settle must still be accepted (pendingScrollTarget not stuck!)
        vm.onPageSettled(1)
        assertEquals("BUG-1.1: pendingScrollTarget stayed stuck, blocking subsequent onPageSettled!", 1, vm.uiState.value.currentPage)

        // Next scroll effect must still be received by collector
        vm.jumpToPage(0, recordReturnPoint = false, animate = false)
        delay(50)
        assertTrue("BUG-1.1: Collector stopped receiving subsequent ScrollToPage effects!", appliedEffects.contains(0))

        collectorJob.cancel()
    }

    @Test
    fun issue1_bug1_2_scrollToPageWaiterMustNotHangWhenTotalPagesShrinks(): Unit = runBlocking {
        var liveTotal = 10
        val pageCountFlow = MutableStateFlow(2)
        val effect = ReaderEffect.ScrollToPage(pageIndex = 8, animate = false)

        val finished = withTimeoutOrNull(300) {
            launch {
                delay(40)
                // Re-pagination shrunk book to 2 pages before Compose ever reached 9
                liveTotal = 2
                pageCountFlow.value = 2
            }
            var applied = -1
            applyScrollToPageEffect(
                effect = effect,
                liveTotalPages = { liveTotal },
                livePagerPageCount = { pageCountFlow.value },
                pageCountFlow = pageCountFlow,
                livePagerCurrentPage = { 0 },
                performScroll = { _, _ -> },
                onApplied = { applied = it }
            )
            applied
        }
        assertNotNull("BUG-1.2: applyScrollToPageEffect hung forever when totalPages shrunk below desired!", finished)
    }

    @Test
    fun issue1_bug1_3_staleScrollAppliedAndExitSyncMustNotClobberPendingPage(): Unit = runBlocking {
        // Saved on chapter 1, paragraph 2 (offset in second para) -> initialTargetPage = 1 inside Phase 1
        val epub = buildSyntheticEpubBook(chapterCount = 2)
        val ch1 = epub.chapters[1]
        val secondParaOffset = (ch1.blocks[1] as EpubBlock.ParagraphBlock).charStartInChapter
        val savedBook = Book(
            id = "b1_3", title = "T", author = "A", format = BookFormat.EPUB,
            fileUri = "file:///b1_3.epub", coverPath = null,
            currentPage = 4, totalPages = 6, progress = 0.8f,
            epubChapterIndex = 1, epubCharOffset = secondParaOffset, addedAt = 1L, lastReadAt = 100L
        )
        val repo = FakeReaderRepository(savedBook)
        val phase2Gate = CompletableDeferred<Unit>()
        val vm = ReaderViewModel(
            appContext = ContextWrapper(null),
            bookId = "b1_3",
            libraryRepository = repo,
            preferencesRepository = FakePreferencesSource(),
            epubLoader = { epub to null },
            chapterPaginator = { ch, _, _, _, _, _ ->
                if (ch.index != 1) {
                    runBlocking { phase2Gate.await() }
                }
                paginateChapterOneParaPerPage(ch)
            }
        )

        awaitState(vm) { !it.isLoading && it.epubBook != null }
        vm.onEpubViewportReadyForTest(800, 1200)
        // Phase 1 finished: currentPage restored to 1 (ch 1 para 2), pendingScrollTarget == 1
        awaitState(vm) { !it.isPaginating && it.paginatedEpub != null }
        assertEquals(1, vm.uiState.value.currentPage)

        // Before Compose pager has scrolled from 0 -> 1, user backgrounds app / hits back with pagerState.targetPage == 0
        vm.syncCurrentPageBeforeExit(livePageIndex = 0)
        assertEquals(
            "BUG-1.3a: syncCurrentPageBeforeExit(0) overwrote restored page 1 while ScrollToPage(1) was still pending!",
            1,
            vm.uiState.value.currentPage
        )

        // Also test older ScrollToPage(1) completion clobbering newer jumpToPage(2)
        vm.jumpToPage(2, recordReturnPoint = false, animate = false)
        assertEquals(2, vm.uiState.value.currentPage)
        // Older scroll effect (1) finishes late:
        vm.onScrollEffectApplied(1)
        // Stale pager settled event on page 1 arrives:
        vm.onPageSettled(1)
        assertEquals(
            "BUG-1.3b: Older onScrollEffectApplied(1) cleared pendingScrollTarget=2, letting onPageSettled(1) clobber page 2!",
            2,
            vm.uiState.value.currentPage
        )

        phase2Gate.complete(Unit)
        Unit
    }

    @Test
    fun issue1_bug1_4_exitFlushesMustBeSerializedInOrder() = runBlocking {
        val epub = buildSyntheticEpubBook(chapterCount = 1)
        val book = Book(
            id = "b1_4", title = "T", author = "A", format = BookFormat.EPUB,
            fileUri = "file:///b1_4.epub", coverPath = null,
            currentPage = 0, totalPages = 3, progress = 0f,
            epubChapterIndex = 0, epubCharOffset = 0, addedAt = 1L, lastReadAt = 10L
        )
        val repo = FakeReaderRepository(book)
        repo.onSaveProgressDelay = { page ->
            if (page == 0) delay(100) else delay(5)
        }
        val vm = ReaderViewModel(
            appContext = ContextWrapper(null),
            bookId = "b1_4",
            libraryRepository = repo,
            preferencesRepository = FakePreferencesSource(),
            epubLoader = { epub to null },
            chapterPaginator = { ch, _, _, _, _, _ -> paginateChapterOneParaPerPage(ch) }
        )
        awaitState(vm) { !it.isLoading && it.epubBook != null }
        vm.onEpubViewportReadyForTest(800, 1200)
        awaitState(vm) { !it.isPaginating && it.totalPages == 3 }

        // Flush #1 at page 0 (slow: 100ms), then immediately move to page 2 and flush #2 (fast: 5ms)
        vm.flushProgressImmediately()
        delay(10)
        vm.jumpToPage(2, recordReturnPoint = false, animate = false)
        vm.flushProgressImmediately()

        delay(180)
        assertEquals(
            "BUG-1.4: Slow exit flush for page 0 overwrote newer page 2 in DB!",
            2,
            repo.bookState.value.currentPage
        )
    }

    // =====================================================================
    // ISSUE 2: EPUB Two-Phase Pagination Contamination & Reflow Desync
    // =====================================================================

    @Test
    fun issue2_bug2_1_phase1PartialPaginationMustNotPersistFalseCompletion() = runBlocking {
        val epub = buildSyntheticEpubBook(chapterCount = 4)
        val book = Book(
            id = "b2_1", title = "T", author = "A", format = BookFormat.EPUB,
            fileUri = "file:///b2_1.epub", coverPath = null,
            currentPage = 0, totalPages = 4, progress = 0.08f,
            epubChapterIndex = 0, epubCharOffset = 0, addedAt = 1L, lastReadAt = 10L
        )
        val repo = FakeReaderRepository(book)
        val phase2Gate = CompletableDeferred<Unit>()
        val vm = ReaderViewModel(
            appContext = ContextWrapper(null),
            bookId = "b2_1",
            libraryRepository = repo,
            preferencesRepository = FakePreferencesSource(),
            epubLoader = { epub to null },
            chapterPaginator = { ch, _, _, _, _, _ ->
                if (ch.index != 0) runBlocking { phase2Gate.await() }
                paginateChapterOneParaPerPage(ch)
            }
        )
        awaitState(vm) { !it.isLoading && it.epubBook != null }
        vm.onEpubViewportReadyForTest(800, 1200)
        awaitState(vm) { !it.isPaginating && it.isBackgroundPaginating }

        // User reaches last page (page 2) of Chapter 0 while Phase 2 is STILL running (3 more chapters left!)
        vm.onScrollEffectApplied(0)
        vm.onPageSettled(2)
        vm.flushProgressImmediately()
        delay(80)

        assertFalse(
            "BUG-2.1: Partial Phase-1 pagination persisted totalPages=${repo.bookState.value.totalPages}, progress=${repo.bookState.value.progress} marking 4-chapter book COMPLETED!",
            repo.bookState.value.isCompleted
        )

        phase2Gate.complete(Unit)
        Unit
    }

    @Test
    fun issue2_bug2_2_tocAndBookmarkJumpDuringPhase2MustReachTargetChapterAfterFullPagination() = runBlocking {
        val epub = buildSyntheticEpubBook(chapterCount = 3) // 3 chapters * 3 pages = 9 pages total
        val book = Book(
            id = "b2_2", title = "T", author = "A", format = BookFormat.EPUB,
            fileUri = "file:///b2_2.epub", coverPath = null,
            currentPage = 0, totalPages = 3, progress = 0f,
            epubChapterIndex = 0, epubCharOffset = 0, addedAt = 1L, lastReadAt = null
        )
        val repo = FakeReaderRepository(book)
        val phase2Gate = CompletableDeferred<Unit>()
        val vm = ReaderViewModel(
            appContext = ContextWrapper(null),
            bookId = "b2_2",
            libraryRepository = repo,
            preferencesRepository = FakePreferencesSource(),
            epubLoader = { epub to null },
            chapterPaginator = { ch, _, _, _, _, _ ->
                if (ch.index != 0) runBlocking { phase2Gate.await() }
                paginateChapterOneParaPerPage(ch)
            }
        )
        awaitState(vm) { !it.isLoading && it.epubBook != null }
        vm.onEpubViewportReadyForTest(800, 1200)
        awaitState(vm) { !it.isPaginating && it.isBackgroundPaginating }

        // User selects Chapter 3 TOC entry while Phase 2 is still running
        val chapter3Toc = vm.uiState.value.tocEntries.last()
        assertEquals(2, chapter3Toc.chapterIndex)
        vm.jumpToTocEntry(chapter3Toc)

        // Release Phase 2 to finish paginating all 3 chapters
        phase2Gate.complete(Unit)
        awaitState(vm) { !it.isBackgroundPaginating && it.totalPages == 9 }

        assertEquals(
            "BUG-2.2: TOC jump to Chapter 3 during Phase 2 landed on page ${vm.uiState.value.currentPage} (ch ${vm.uiState.value.paginatedEpub?.pages?.getOrNull(vm.uiState.value.currentPage)?.chapterIndex}) instead of Chapter 3 (page 6)!",
            6,
            vm.uiState.value.currentPage
        )
    }

    @Test
    fun issue2_bug2_3_epubSearchCompletingAcrossPhase2MustReflectFullPageIndices() = runBlocking {
        val epub = buildSyntheticEpubBook(chapterCount = 2, searchTerm = "SupernovaTarget")
        val ch1 = epub.chapters[1]
        val startOffset = (ch1.blocks[0] as EpubBlock.ParagraphBlock).charStartInChapter
        val book = Book(
            id = "b2_3", title = "T", author = "A", format = BookFormat.EPUB,
            fileUri = "file:///b2_3.epub", coverPath = null,
            currentPage = 0, totalPages = 6, progress = 0.5f,
            epubChapterIndex = 1, epubCharOffset = startOffset, addedAt = 1L, lastReadAt = 100L
        )
        val repo = FakeReaderRepository(book)
        val phase2Gate = CompletableDeferred<Unit>()
        val searchEntered = CompletableDeferred<Unit>()
        val phase2Finished = CompletableDeferred<Unit>()

        val vm = ReaderViewModel(
            appContext = ContextWrapper(null),
            bookId = "b2_3",
            libraryRepository = repo,
            preferencesRepository = FakePreferencesSource(),
            epubLoader = { epub to null },
            chapterPaginator = { ch, _, _, _, _, _ ->
                if (ch.index == 0) runBlocking { phase2Gate.await() }
                paginateChapterOneParaPerPage(ch)
            },
            epubSearcher = { eb, pag, q ->
                val matches = com.ebookreader.app.data.parser.epub.EpubSearchEngine.search(eb, pag, q)
                searchEntered.complete(Unit)
                runBlocking { phase2Finished.await() }
                matches
            }
        )
        awaitState(vm) { !it.isLoading && it.epubBook != null }
        vm.onEpubViewportReadyForTest(800, 1200)
        awaitState(vm) { !it.isPaginating && it.isBackgroundPaginating }
        // In Phase 1, only Chapter 1 is paginated so match appears on page 0

        vm.onSearchQueryChange("SupernovaTarget")
        searchEntered.await()
        // Now let Phase 2 finish (adds 3 pages from Ch 0 -> Ch 1 match moves from page 0 to page 3)
        phase2Gate.complete(Unit)
        awaitState(vm) { !it.isBackgroundPaginating && it.totalPages == 6 }
        phase2Finished.complete(Unit)

        awaitState(vm) { !it.isSearching && it.searchMatches.isNotEmpty() }
        assertEquals(
            "BUG-2.3: EPUB search finishing after Phase 2 wrote stale Phase-1 pageIndex=${vm.uiState.value.searchMatches.first().pageIndex} instead of full page 3!",
            3,
            vm.uiState.value.searchMatches.first().pageIndex
        )
    }

    @Test
    fun issue2_bug2_4_epubPageBookmarkMustSurviveReflowAndReturnAnchorMustTrackReflow() = runBlocking {
        val epub = buildSyntheticEpubBook(chapterCount = 2) // 3 pages each = 6 pages total
        val book = Book(
            id = "b2_4", title = "T", author = "A", format = BookFormat.EPUB,
            fileUri = "file:///b2_4.epub", coverPath = null,
            currentPage = 0, totalPages = 6, progress = 0.5f,
            epubChapterIndex = 1, epubCharOffset = 0, addedAt = 1L, lastReadAt = 100L
        )
        val repo = FakeReaderRepository(book)
        val phase2Gate = CompletableDeferred<Unit>()
        val vm = ReaderViewModel(
            appContext = ContextWrapper(null),
            bookId = "b2_4",
            libraryRepository = repo,
            preferencesRepository = FakePreferencesSource(),
            epubLoader = { epub to null },
            chapterPaginator = { ch, _, _, _, _, _ ->
                if (ch.index == 0) runBlocking { phase2Gate.await() }
                paginateChapterOneParaPerPage(ch)
            }
        )
        awaitState(vm) { !it.isLoading && it.epubBook != null }
        vm.onEpubViewportReadyForTest(800, 1200)
        awaitState(vm) { !it.isPaginating && it.isBackgroundPaginating }

        // Phase 1: Ch 1 page 0 -> bookmark it & jump 0 -> 2 (sets return chip origin at Ch 1 page 0)
        vm.onScrollEffectApplied(0)
        vm.toggleBookmarkAtCurrentPage()
        delay(40)
        assertEquals(1, vm.uiState.value.bookmarks.size)

        vm.jumpToPage(2, recordReturnPoint = true, animate = false)
        assertEquals(0, vm.uiState.value.returnPageBeforeJump)

        // Complete Phase 2: Ch 0 adds 3 pages before Ch 1 -> Ch 1 start shifts from page 0 to page 3!
        phase2Gate.complete(Unit)
        awaitState(vm) { !it.isBackgroundPaginating && it.totalPages == 6 }

        // Jump back via return chip: must return to page 3 (Ch 1 start), NOT page 0 (Ch 0 start)!
        vm.jumpBackToPreviousSpot()
        assertEquals(
            "BUG-2.4a: returnPageBeforeJump pointed to stale raw page 0 (Ch 0) instead of anchored Ch 1 page 3!",
            3,
            vm.uiState.value.currentPage
        )

        // Now standing at Ch 1 start (page 3):
        val renderedBookmarks = resolveBookmarkedEpubPageIndices(
            vm.uiState.value.bookmarks,
            vm.uiState.value.paginatedEpub!!.pages
        )
        assertTrue(
            "BUG-2.4b: EpubPagerView bookmark ribbon stayed on stale page $renderedBookmarks instead of anchored page 3!",
            3 in renderedBookmarks && 0 !in renderedBookmarks
        )
        assertTrue(
            "BUG-2.4c: isCurrentPageBookmarked is false on page 3 after reflow!",
            vm.uiState.value.isCurrentPageBookmarked
        )

        // Toggling bookmark on page 3 must remove the Ch 1 bookmark instead of adding a duplicate!
        vm.toggleBookmarkAtCurrentPage()
        delay(40)
        assertEquals(
            "BUG-2.4d: Re-toggling bookmark after reflow created a duplicate bookmark instead of removing original!",
            0,
            vm.uiState.value.bookmarks.size
        )
    }

    // =====================================================================
    // ISSUE 3: Native PDF/Archive Concurrency, Search Cancellation & Recycling
    // =====================================================================

    private class FakeCloseableDoc {
        @Volatile var closed = false
    }

    @Test
    fun issue3_bug3_1_pdfSessionCloseMustSerializeAndNeverTouchClosedOrLeakedDoc() = runBlocking {
        val openedDocs = CopyOnWriteArrayList<FakeCloseableDoc>()
        val touchedClosed = AtomicBoolean(false)
        val lifecycle = PdfSessionLifecycle<FakeCloseableDoc>(
            openDoc = { FakeCloseableDoc().also { openedDocs += it } },
            closeDoc = { it.closed = true },
            onCloseTransport = {}
        )

        val readerJob = launch(Dispatchers.IO) {
            lifecycle.withDoc { doc ->
                delay(60)
                if (doc.closed) touchedClosed.set(true)
                "done"
            }
        }
        delay(15)
        val closeJob = launch(Dispatchers.IO) {
            lifecycle.close()
        }
        readerJob.join()
        closeJob.join()

        assertFalse("BUG-3.1: PdfSessionLifecycle.close() closed PDDocument while in-flight reader block was using it!", touchedClosed.get())
        assertTrue("All opened docs must be closed after close()", openedDocs.all { it.closed })
    }

    @Test
    fun issue3_bug3_2_cancelledPdfSearchMustPropagateCancellationAndNotFallBackOrClobberState() = runBlocking {
        val pdfBoxFallbackEntered = AtomicBoolean(false)
        val fakeAccess = object : PdfDocumentAccess {
            override val pageCount: Int = 5
            override suspend fun <T> withPdfBoxDocument(block: suspend (PDDocument) -> T): T? {
                pdfBoxFallbackEntered.set(true)
                return null
            }
            override suspend fun <T> withPage(pageIndex: Int, block: (PdfRenderer.Page) -> T): T {
                error("unused")
            }
        }

        // 1) CancellationException inside native API 35 path must propagate instead of falling back to PDFBox
        var caughtCancellation = false
        try {
            PdfSearchEngine.searchWithAccess(
                controller = fakeAccess,
                rawQuery = "quantum",
                supportsNativeApi35 = true,
                nativeApi35Runner = { _, _, _ ->
                    throw CancellationException("job cancelled")
                }
            )
        } catch (_: CancellationException) {
            caughtCancellation = true
        }
        assertTrue("BUG-3.2a: PdfSearchEngine swallowed CancellationException and fell back!", caughtCancellation)
        assertFalse("BUG-3.2b: PdfSearchEngine entered PDFBox fallback after CancellationException!", pdfBoxFallbackEntered.get())

        // 2) Cancellation inside PdfSessionLifecycle.withDoc must propagate, NOT return null
        val lifecycle = PdfSessionLifecycle(
            openDoc = { FakeCloseableDoc() },
            closeDoc = { it.closed = true },
            onCloseTransport = {}
        )
        var withDocCancelled = false
        try {
            lifecycle.withDoc<String> {
                throw CancellationException("cancelled inside pdfbox loop")
            }
        } catch (_: CancellationException) {
            withDocCancelled = true
        }
        assertTrue("BUG-3.2c: PdfSessionLifecycle.withDoc swallowed CancellationException via runCatching!", withDocCancelled)

        // 3) ReaderViewModel cancelled search must never clobber closed search state if cancellation propagates cleanly
        val epubBookRecord = Book(
            id = "pdf1", title = "Book", author = "A", format = BookFormat.EPUB,
            fileUri = "file:///a.epub", coverPath = null,
            currentPage = 0, totalPages = 5, progress = 0f,
            epubChapterIndex = 0, epubCharOffset = 0, addedAt = 1L, lastReadAt = null
        )
        val repo = FakeReaderRepository(epubBookRecord)
        val searchStarted = CompletableDeferred<Unit>()
        val vmEpub = ReaderViewModel(
            appContext = ContextWrapper(null),
            bookId = "pdf1",
            libraryRepository = repo,
            preferencesRepository = FakePreferencesSource(),
            epubLoader = { buildSyntheticEpubBook(1, "target") to null },
            epubSearcher = { _, _, q ->
                if (q == "oldquery") {
                    searchStarted.complete(Unit)
                    Thread.sleep(60)
                    listOf(SearchMatch(0, 0, snippet = "stale", matchStartInSnippet = 0, matchEndInSnippet = 5))
                } else {
                    emptyList()
                }
            }
        )
        awaitState(vmEpub) { !it.isLoading }
        vmEpub.openSearch()
        vmEpub.onSearchQueryChange("oldquery")
        searchStarted.await()
        // Close search immediately while oldquery is blocked
        vmEpub.closeSearch()
        delay(100)
        assertTrue(
            "BUG-3.2d: Cancelled/closed searchJob wrote stale searchMatches after closeSearch()!",
            vmEpub.uiState.value.searchMatches.isEmpty() && !vmEpub.uiState.value.isSearchVisible
        )
    }

    @Test
    fun issue3_bug3_3_epubArchiveReadEntryDuringConcurrentCloseReturnsNullSafely() {
        val tempZip = File.createTempFile("sm_archive_", ".epub")
        try {
            ZipOutputStream(FileOutputStream(tempZip)).use { zos ->
                repeat(20) { i ->
                    zos.putNextEntry(ZipEntry("OEBPS/img$i.bin"))
                    zos.write(ByteArray(16_384) { (i + it).toByte() })
                    zos.closeEntry()
                }
            }
            FileChannel.open(tempZip.toPath(), StandardOpenOption.READ).use { ch ->
                val archive = EpubRandomAccessArchive.openOrNull(ch, closeChannelOnClose = true)!!
                // Close archive cleanly then attempt readEntry (same as late image load racing with closeEpubArchive)
                archive.close()
                val result = try {
                    archive.readEntry("OEBPS/img0.bin")
                } catch (e: Throwable) {
                    fail("BUG-3.3: readEntry threw ${e::class.java.simpleName} after close instead of returning null!")
                    null
                }
                assertEquals(null, result)
            }
        } finally {
            tempZip.delete()
        }
    }

    @Test
    fun issue3_bug3_4_pdfZoomTileMustNotSynchronouslyRecycleComposeBitmap() {
        assertFalse(
            "BUG-3.4: PdfPagerView still synchronously calls Bitmap.recycle() on Compose zoomTile bitmaps!",
            PdfZoomGestureHelper.shouldRecycleDisposedZoomTileBitmap()
        )
    }

    // =====================================================================
    // ISSUE 4: Library Import / Relink / Delete Concurrency & Events
    // =====================================================================

    private class FakeLibraryStore(
        private val sleepOnHashMs: Long = 0L,
        private val deleteDelayInHighlight: Long = 0L,
        private val headerByUri: Map<String, ByteArray> = emptyMap(),
        private val hashByUri: Map<String, String> = emptyMap()
    ) {
        val books = LinkedHashMap<String, BookEntity>()
        val bookmarks = CopyOnWriteArrayList<BookmarkEntity>()
        val highlights = CopyOnWriteArrayList<HighlightEntity>()
        val upsertCount = AtomicInteger(0)
        private val nextId = AtomicLong(1L)

        val bookDao = object : BookDao {
            override fun observeAllBooks(): Flow<List<BookEntity>> =
                MutableStateFlow(books.values.toList())
            override suspend fun getBookById(bookId: String): BookEntity? =
                synchronized(books) { books[bookId] }
            override fun observeBookById(bookId: String): Flow<BookEntity?> =
                MutableStateFlow(books[bookId])
            override suspend fun getBookByUri(uri: String): BookEntity? =
                synchronized(books) { books.values.firstOrNull { it.fileUri == uri } }
            override suspend fun upsertBook(book: BookEntity) {
                upsertCount.incrementAndGet()
                synchronized(books) {
                    val existed = books.containsKey(book.id)
                    books[book.id] = book
                    if (existed) {
                        // Simulate SQLite @Insert(onConflict = REPLACE) ON DELETE CASCADE!
                        bookmarks.removeAll { it.bookId == book.id }
                        highlights.removeAll { it.bookId == book.id }
                    }
                }
            }
            override suspend fun updateProgress(
                bookId: String,
                currentPage: Int,
                totalPages: Int,
                progress: Float,
                chapterIndex: Int,
                charOffset: Int,
                timestamp: Long
            ) {
                synchronized(books) {
                    books[bookId]?.let {
                        books[bookId] = it.copy(
                            currentPage = currentPage,
                            totalPages = totalPages,
                            progress = progress,
                            epubChapterIndex = chapterIndex,
                            epubCharOffset = charOffset,
                            lastReadAt = timestamp
                        )
                    }
                }
            }
            override suspend fun updateBookUri(bookId: String, newUri: String) {
                synchronized(books) {
                    books[bookId]?.let { books[bookId] = it.copy(fileUri = newUri) }
                }
            }
            override suspend fun deleteBookById(bookId: String) {
                synchronized(books) {
                    books.remove(bookId)
                    bookmarks.removeAll { it.bookId == bookId }
                    highlights.removeAll { it.bookId == bookId }
                }
            }
        }

        val bookmarkDao = object : BookmarkDao {
            override fun observeBookmarksForBook(bookId: String): Flow<List<BookmarkEntity>> =
                MutableStateFlow(emptyList())
            override suspend fun getBookmarksForBook(bookId: String): List<BookmarkEntity> =
                bookmarks.filter { it.bookId == bookId }
            override suspend fun insertBookmark(bookmark: BookmarkEntity): Long {
                synchronized(books) {
                    if (!books.containsKey(bookmark.bookId)) {
                        throw IllegalStateException("FOREIGN KEY constraint failed (bookmarks.bookId)")
                    }
                    val id = nextId.getAndIncrement()
                    bookmarks += bookmark.copy(id = id)
                    return id
                }
            }
            override suspend fun deleteBookmarkById(bookmarkId: Long) {
                bookmarks.removeAll { it.id == bookmarkId }
            }
        }

        val highlightDao = object : HighlightDao {
            override fun observeHighlightsForBook(bookId: String): Flow<List<HighlightEntity>> =
                MutableStateFlow(emptyList())
            override suspend fun getHighlightsForChapter(bookId: String, chapterIndex: Int): List<HighlightEntity> =
                highlights.filter { it.bookId == bookId && it.chapterIndex == chapterIndex }
            override suspend fun insertHighlight(highlight: HighlightEntity): Long {
                synchronized(books) {
                    if (!books.containsKey(highlight.bookId)) {
                        throw IllegalStateException("FOREIGN KEY constraint failed (highlights.bookId)")
                    }
                    val id = nextId.getAndIncrement()
                    highlights += highlight.copy(id = id)
                    return id
                }
            }
            override suspend fun deleteHighlightById(highlightId: Long) {
                highlights.removeAll { it.id == highlightId }
            }
            override suspend fun deleteOverlappingHighlights(
                bookId: String,
                chapterIndex: Int,
                startOffset: Int,
                endOffset: Int
            ) {
                if (deleteDelayInHighlight > 0) delay(deleteDelayInHighlight)
                highlights.removeAll {
                    it.bookId == bookId &&
                        it.chapterIndex == chapterIndex &&
                        it.startCharOffset < endOffset &&
                        it.endCharOffset > startOffset
                }
            }
        }

        val externalContent = object : LibraryExternalContent {
            override fun isUriAccessible(uriString: String): Boolean = true
            override fun takePersistableReadPermission(uriString: String, originalUri: Uri?) = Unit
            override fun releasePersistableReadPermission(uriString: String) = Unit
            override fun queryDisplayName(uriString: String, originalUri: Uri?): String =
                uriString.substringAfterLast('/')
            override fun computeSha256AndHeader(uriString: String, originalUri: Uri?): Pair<String, ByteArray> {
                if (sleepOnHashMs > 0) Thread.sleep(sleepOnHashMs)
                val hash = hashByUri[uriString] ?: "same-sha-256"
                val header = headerByUri[uriString] ?: byteArrayOf(0x50, 0x4B, 0x03, 0x04) // EPUB/ZIP magic
                return hash to header
            }
            override fun detectBookFormat(
                uriString: String,
                originalUri: Uri?,
                displayName: String?,
                headerBytes: ByteArray
            ): BookFormat {
                return if (headerBytes.size >= 4 && headerBytes[0] == 0x25.toByte()) {
                    BookFormat.PDF
                } else {
                    BookFormat.EPUB
                }
            }
            override fun buildBookEntity(
                bookId: String,
                uriString: String,
                originalUri: Uri?,
                fallbackTitle: String,
                format: BookFormat
            ): BookEntity = BookEntity(
                id = bookId,
                title = fallbackTitle,
                author = "Author",
                format = format.name,
                fileUri = uriString,
                coverPath = null,
                currentPage = 0,
                totalPages = 10,
                progress = 0f,
                addedAt = 1L
            )
        }
    }

    @Test
    fun issue4_bug4_1_openBookEventMustNotDropWhenReaderRouteIsActive() = runBlocking {
        val sampleBook = Book(
            id = "open-id", title = "External", author = "A", format = BookFormat.EPUB,
            fileUri = "content://ext", coverPath = null,
            currentPage = 0, totalPages = 5, progress = 0f,
            epubChapterIndex = 0, epubCharOffset = 0, addedAt = 1L, lastReadAt = null
        )
        val shelf = object : LibraryShelfSource {
            override fun observeBooks(): Flow<List<Book>> = MutableStateFlow(emptyList())
            override suspend fun linkExternalBookUri(uriString: String, originalUri: Uri?): Result<Book> =
                Result.success(sampleBook)
            override suspend fun relinkBookUriString(bookId: String, newUriString: String, originalUri: Uri?): Result<Book> =
                Result.success(sampleBook)
            override suspend fun removeBookFromShelf(book: Book) = Unit
        }
        val vm = LibraryViewModel(shelf)
        // ReaderScreen is active, so LibraryScreen collector is NOT running yet when ACTION_VIEW imports
        vm.importExternalBookUri("content://ext", openImmediately = true)
        delay(50)

        val received = withTimeoutOrNull(300) {
            vm.openBookEvents.first()
        }
        assertNotNull("BUG-4.1: OpenBook event was dropped while no LibraryScreen collector was subscribed!", received)
        assertEquals("open-id", received?.bookId)
    }

    @Test
    fun issue4_bug4_2_concurrentImportsMustKeepIsImportingTrueUntilAllFinish() = runBlocking {
        val gate1 = CompletableDeferred<Unit>()
        val gate2 = CompletableDeferred<Unit>()
        val shelf = object : LibraryShelfSource {
            override fun observeBooks(): Flow<List<Book>> = MutableStateFlow(emptyList())
            override suspend fun linkExternalBookUri(uriString: String, originalUri: Uri?): Result<Book> {
                if (uriString.endsWith("1")) gate1.await() else gate2.await()
                return Result.success(
                    Book(
                        id = uriString, title = uriString, author = "A", format = BookFormat.EPUB,
                        fileUri = uriString, coverPath = null, currentPage = 0, totalPages = 5,
                        progress = 0f, epubChapterIndex = 0, epubCharOffset = 0, addedAt = 1L, lastReadAt = null
                    )
                )
            }
            override suspend fun relinkBookUriString(bookId: String, newUriString: String, originalUri: Uri?): Result<Book> =
                error("unused")
            override suspend fun removeBookFromShelf(book: Book) = Unit
        }
        val vm = LibraryViewModel(shelf)
        val collectJob = launch { vm.uiState.collect {} }
        delay(20)

        vm.importExternalBookUri("content://book1")
        vm.importExternalBookUri("content://book2")
        delay(30)
        assertTrue(vm.uiState.value.isImporting)

        // Finish import #1 while #2 is still in flight
        gate1.complete(Unit)
        delay(40)
        assertTrue(
            "BUG-4.2: isImporting flipped to false while second concurrent import was still active!",
            vm.uiState.value.isImporting
        )

        gate2.complete(Unit)
        delay(40)
        assertFalse(vm.uiState.value.isImporting)
        collectJob.cancel()
    }

    @Test
    fun issue4_bug4_3_concurrentSameBookImportMustSerializeAndPreserveAnnotations() = runBlocking {
        val store = FakeLibraryStore(sleepOnHashMs = 60L)
        val repo = LibraryRepository(
            context = ContextWrapper(null),
            bookDao = store.bookDao,
            bookmarkDao = store.bookmarkDao,
            highlightDao = store.highlightDao,
            externalContent = store.externalContent
        )

        val jobs = listOf(
            async(Dispatchers.IO) { repo.linkExternalBookUri("content://copyA/book.epub") },
            async(Dispatchers.IO) { repo.linkExternalBookUri("content://copyB/book.epub") }
        )
        // Even if concurrent before bookmark, upsertBook must execute ONLY ONCE via serialization + IGNORE
        val results = jobs.awaitAll()
        assertTrue(results.all { it.isSuccess })
        assertEquals(
            "BUG-4.3: Concurrent imports of same SHA-256 both called upsertBook (${store.upsertCount.get()}x), risking REPLACE CASCADE wipe!",
            1,
            store.upsertCount.get()
        )
    }

    @Test
    fun issue4_bug4_4_relinkMustRejectFormatMismatch() = runBlocking {
        val store = FakeLibraryStore(
            headerByUri = mapOf(
                "content://wrong.pdf" to byteArrayOf(0x25, 0x50, 0x44, 0x46) // %PDF
            )
        )
        store.books["epub-1"] = BookEntity(
            id = "epub-1", title = "My EPUB", author = "A", format = BookFormat.EPUB.name,
            fileUri = "content://old.epub", coverPath = null, currentPage = 3, totalPages = 10, progress = 0.4f
        )
        val repo = LibraryRepository(
            context = ContextWrapper(null),
            bookDao = store.bookDao,
            bookmarkDao = store.bookmarkDao,
            highlightDao = store.highlightDao,
            externalContent = store.externalContent
        )

        val result = repo.relinkBookUriString("epub-1", "content://wrong.pdf")
        assertTrue(
            "BUG-4.4: relinkBookUri accepted a PDF file for an EPUB bookshelf entry!",
            result.isFailure
        )
        assertEquals("content://old.epub", store.bookDao.getBookById("epub-1")?.fileUri)
    }

    @Test
    fun issue4_bug4_5_concurrentRemoveAndAnnotationWriteMustNotFailFkConstraint() = runBlocking {
        val store = FakeLibraryStore(deleteDelayInHighlight = 45L)
        val bookEntity = BookEntity(
            id = "book-del", title = "Delete Me", author = "A", format = BookFormat.EPUB.name,
            fileUri = "content://del.epub", coverPath = null, currentPage = 1, totalPages = 10, progress = 0.2f
        )
        store.books[bookEntity.id] = bookEntity
        val repo = LibraryRepository(
            context = ContextWrapper(null),
            bookDao = store.bookDao,
            bookmarkDao = store.bookmarkDao,
            highlightDao = store.highlightDao,
            externalContent = store.externalContent
        )

        val saveJob = async(Dispatchers.IO) {
            runCatching {
                repo.saveHighlight(
                    bookId = "book-del",
                    pageIndex = 1,
                    chapterIndex = 0,
                    startCharOffset = 0,
                    endCharOffset = 10,
                    chapterTitle = "Ch 1",
                    selectedText = "Highlight",
                    color = HighlightColor.YELLOW
                )
            }
        }
        delay(10) // Highlight save is inside deleteOverlappingHighlights while removeBookFromShelf deletes parent book row
        val removeJob = async(Dispatchers.IO) {
            repo.removeBookFromShelf(bookEntity.toDomain())
        }

        val saveResult = saveJob.await()
        removeJob.await()

        assertTrue(
            "BUG-4.5: Concurrent removeBookFromShelf + saveHighlight threw ${saveResult.exceptionOrNull()?.message}!",
            saveResult.isSuccess
        )
        assertEquals(0, store.highlights.size)
        assertEquals(null, store.bookDao.getBookById("book-del"))
    }

    // =====================================================================
    // ISSUE 5: PDF Zoom/Selection Lock, HUD Asymmetry & Sentence Expansion
    // =====================================================================

    @Test
    fun issue5_bug5_1_pdfSettledAndLayoutEffectMustPreserveZoomAndNotifyWhenInactive() {
        assertFalse(
            "BUG-5.1a: Settled event on already-zoomed active page wiped activePageZoomed!",
            PdfZoomGestureHelper.shouldResetZoomOnSettledPage(settledPage = 2, currentPage = 2)
        )

        val zoomedPan = ZoomPanState(scale = 2.2f, offsetX = 80f, offsetY = 140f, isZoomed = true)
        val (samePageState, _) = PdfZoomGestureHelper.reconcileLayoutEffect(zoomedPan, isActivePage = true)
        assertEquals(
            "BUG-5.1b: Layout effect zeroed vertical pan offsetY while page remained zoomed!",
            140f,
            samePageState.offsetY,
            0.01f
        )

        val (inactiveState, notifiedReset) = PdfZoomGestureHelper.reconcileLayoutEffect(zoomedPan, isActivePage = false)
        assertFalse(inactiveState.isZoomed)
        assertTrue(
            "BUG-5.1c: Leaving zoomed page did not notify onZoomStateChanged(false)!",
            notifiedReset
        )
    }

    @Test
    fun issue5_bug5_2_asyncPdfSelectionStalePageMustNotLockPager() {
        assertFalse(
            "BUG-5.2a: Async text layout completion for inactive page was allowed to install activeSelection!",
            PdfZoomGestureHelper.canApplyAsyncSelection(isActivePage = false, isGestureInProgress = false)
        )
        assertTrue(
            "BUG-5.2b: Stale selection from another page locked userScrollEnabled on currentPage!",
            PdfZoomGestureHelper.isPdfPagerScrollEnabled(
                activePageZoomed = false,
                activeSelectionPage = 1,
                currentPage = 2
            )
        )
    }

    @Test
    fun issue5_bug5_3_searchCollapsedHudAndScrubberHideMustStayConsistent() {
        assertFalse(
            "BUG-5.3a: Bottom scrubber remained visible while InBookSearchOverlay was open!",
            isBottomScrubberVisible(isHudVisible = true, isSearchVisible = true)
        )
        val (hudAfterTap, _) = nextHudStateOnContentTap(
            isHudVisible = false,
            isSearchVisible = true,
            isSearchResultsExpanded = false
        )
        assertFalse(
            "BUG-5.3b: Tapping content while search bar was open toggled isHudVisible=true asymmetrically!",
            hudAfterTap
        )
        val (draggingOnHide, editingOnHide) = resetScrubberUiOnHide(isDragging = true, isEditingPage = true)
        assertFalse("BUG-5.3c: Scrubber kept isDragging=true after hiding!", draggingOnHide)
        assertFalse(editingOnHide)
    }

    @Test
    fun issue5_bug5_4_sentenceExpansionMustRespectClosingQuotesAndParens() {
        val sample = "He said, \"Stop!\" She left calmly."
        val targetIdx = sample.indexOf("left")
        val (start, end) = EpubSelectionHelper.expandToSentence(sample, targetIdx)
        val expanded = sample.substring(start, end)
        assertEquals(
            "BUG-5.4: Backward sentence scan crossed closing quote after terminator, returning \"$expanded\"",
            "She left calmly.",
            expanded
        )
    }
}
