package com.ebookreader.app

import android.content.ContextWrapper
import com.ebookreader.app.data.local.BookDao
import com.ebookreader.app.data.local.BookEntity
import com.ebookreader.app.data.local.BookmarkDao
import com.ebookreader.app.data.local.BookmarkEntity
import com.ebookreader.app.data.local.HighlightDao
import com.ebookreader.app.data.local.HighlightEntity
import com.ebookreader.app.data.parser.pdf.PdfContentBounds
import com.ebookreader.app.data.parser.pdf.PdfDocumentController
import com.ebookreader.app.data.repository.LibraryRepository
import com.ebookreader.app.domain.model.HighlightColor
import com.ebookreader.app.domain.model.PdfFitMode
import com.ebookreader.app.domain.model.ReaderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class PdfAndAnnotationFixesTest {

    private class DelayedInMemoryBookDao : BookDao {
        private val state = MutableStateFlow<List<BookEntity>>(emptyList())
        override fun observeAllBooks(): Flow<List<BookEntity>> = state
        override suspend fun getBookById(bookId: String): BookEntity? = state.value.find { it.id == bookId }
        override fun observeBookById(bookId: String): Flow<BookEntity?> = MutableStateFlow(null)
        override suspend fun getBookByUri(uri: String): BookEntity? = state.value.find { it.fileUri == uri }
        override suspend fun upsertBook(book: BookEntity) {
            state.value = state.value.filterNot { it.id == book.id } + book
        }
        override suspend fun updateProgress(
            bookId: String,
            currentPage: Int,
            totalPages: Int,
            progress: Float,
            chapterIndex: Int,
            charOffset: Int,
            timestamp: Long
        ) = Unit
        override suspend fun updateBookUri(bookId: String, newUri: String) = Unit
        override suspend fun deleteBookById(bookId: String) = Unit
    }

    private class DelayedInMemoryBookmarkDao : BookmarkDao {
        private val nextId = AtomicLong(1L)
        private val items = ArrayList<BookmarkEntity>()
        private val flow = MutableStateFlow<List<BookmarkEntity>>(emptyList())

        override fun observeBookmarksForBook(bookId: String): Flow<List<BookmarkEntity>> = flow

        override suspend fun getBookmarksForBook(bookId: String): List<BookmarkEntity> {
            delay(8) // Simulate SQLite IO latency under burst load
            return synchronized(items) { items.filter { it.bookId == bookId } }
        }

        override suspend fun insertBookmark(bookmark: BookmarkEntity): Long {
            delay(8)
            val id = if (bookmark.id == 0L) nextId.getAndIncrement() else bookmark.id
            synchronized(items) {
                items.removeAll { it.id == id }
                items += bookmark.copy(id = id)
                flow.value = items.toList()
            }
            return id
        }

        override suspend fun deleteBookmarkById(bookmarkId: Long) {
            delay(8)
            synchronized(items) {
                items.removeAll { it.id == bookmarkId }
                flow.value = items.toList()
            }
        }
    }

    private class DelayedInMemoryHighlightDao : HighlightDao {
        private val nextId = AtomicLong(1L)
        private val items = ArrayList<HighlightEntity>()
        private val flow = MutableStateFlow<List<HighlightEntity>>(emptyList())

        override fun observeHighlightsForBook(bookId: String): Flow<List<HighlightEntity>> = flow

        override suspend fun getHighlightsForChapter(bookId: String, chapterIndex: Int): List<HighlightEntity> {
            return synchronized(items) { items.filter { it.bookId == bookId && it.chapterIndex == chapterIndex } }
        }

        override suspend fun insertHighlight(highlight: HighlightEntity): Long {
            delay(8)
            val id = if (highlight.id == 0L) nextId.getAndIncrement() else highlight.id
            synchronized(items) {
                items.removeAll { it.id == id }
                items += highlight.copy(id = id)
                flow.value = items.toList()
            }
            return id
        }

        override suspend fun deleteHighlightById(highlightId: Long) {
            synchronized(items) {
                items.removeAll { it.id == highlightId }
                flow.value = items.toList()
            }
        }

        override suspend fun deleteOverlappingHighlights(
            bookId: String,
            chapterIndex: Int,
            startOffset: Int,
            endOffset: Int
        ) {
            delay(8)
            synchronized(items) {
                items.removeAll {
                    it.bookId == bookId &&
                        it.chapterIndex == chapterIndex &&
                        it.startCharOffset < endOffset &&
                        it.endCharOffset > startOffset
                }
                flow.value = items.toList()
            }
        }
    }

    @Test
    fun `rapid concurrent bookmark and highlight creation does not duplicate or corrupt annotations`() = runBlocking {
        val bookmarkDao = DelayedInMemoryBookmarkDao()
        val highlightDao = DelayedInMemoryHighlightDao()
        val repo = LibraryRepository(
            context = ContextWrapper(null),
            bookDao = DelayedInMemoryBookDao(),
            bookmarkDao = bookmarkDao,
            highlightDao = highlightDao
        )

        // 1. 10 rapid bookmark toggles on the same page: must never leave >1 bookmark for that page
        val toggleJobs = (1..10).map {
            async(Dispatchers.IO) {
                repo.toggleBookmark(
                    bookId = "book-1",
                    pageIndex = 12,
                    totalPages = 200,
                    epubChapterIndex = 12,
                    epubCharOffset = 0,
                    chapterTitle = "Page 13",
                    snippet = "Sample snippet"
                )
            }
        }
        toggleJobs.awaitAll()
        val remainingBookmarks = bookmarkDao.getBookmarksForBook("book-1")
        assertTrue(
            "Expected at most 1 bookmark (0 after 10 toggles), but got ${remainingBookmarks.size} duplicates!",
            remainingBookmarks.size <= 1
        )
        assertEquals(0, remainingBookmarks.size)

        // 2. 12 rapid overlapping highlight color changes on the same passage: must leave exactly 1 highlight
        val colors = HighlightColor.entries
        val highlightJobs = (0 until 12).map { idx ->
            async(Dispatchers.IO) {
                repo.saveHighlight(
                    bookId = "book-1",
                    pageIndex = 3,
                    chapterIndex = 1,
                    startCharOffset = 40,
                    endCharOffset = 120,
                    chapterTitle = "Chapter 1",
                    selectedText = "Overlapping rapid highlight range",
                    color = colors[idx % colors.size]
                )
            }
        }
        highlightJobs.awaitAll()
        val savedHighlights = highlightDao.getHighlightsForChapter("book-1", 1)
        assertEquals(
            "Expected exactly 1 non-duplicated highlight after rapid color taps, got ${savedHighlights.size}",
            1,
            savedHighlights.size
        )

        // 3. 9 rapid passage selection bookmark toggles: odd count -> must leave exactly 1 passage bookmark
        val selectionJobs = (1..9).map {
            async(Dispatchers.IO) {
                repo.toggleSelectionBookmark(
                    bookId = "book-1",
                    pageIndex = 5,
                    totalPages = 200,
                    chapterIndex = 2,
                    startCharOffset = 10,
                    endCharOffset = 85,
                    chapterTitle = "Chapter 2",
                    selectedText = "Selected sentence for rapid toggle"
                )
            }
        }
        selectionJobs.awaitAll()
        val finalBookmarks = bookmarkDao.getBookmarksForBook("book-1")
        assertEquals(1, finalBookmarks.size)
        assertTrue(finalBookmarks.single().toDomain().isTextSelection)
    }

    @Test
    fun `first load PDF fit defaults to full screen and expands viewport coverage cleanly`() {
        val defaults = ReaderSettings()
        assertEquals(PdfFitMode.FIT_SCREEN, defaults.pdfFitMode)
        assertTrue(defaults.pdfTrimMargins)

        // Realistic phone viewport (1080 x 2255) and US Letter PDF page (612 x 792) with standard ~12% margins
        val viewW = 1080f
        val viewH = 2255f
        val bmpW = 1224f
        val bmpH = 1584f
        val margins = PdfContentBounds(left = 0.12f, top = 0.11f, right = 0.88f, bottom = 0.89f)

        val fitPage = PdfDocumentController.computePdfDisplayRect(
            viewportWidth = viewW,
            viewportHeight = viewH,
            bitmapWidth = bmpW,
            bitmapHeight = bmpH,
            contentBounds = margins,
            fitMode = PdfFitMode.FIT_PAGE,
            trimMargins = true
        )
        val fitWidth = PdfDocumentController.computePdfDisplayRect(
            viewportWidth = viewW,
            viewportHeight = viewH,
            bitmapWidth = bmpW,
            bitmapHeight = bmpH,
            contentBounds = margins,
            fitMode = PdfFitMode.FIT_WIDTH,
            trimMargins = true
        )
        val fitScreen = PdfDocumentController.computePdfDisplayRect(
            viewportWidth = viewW,
            viewportHeight = viewH,
            bitmapWidth = bmpW,
            bitmapHeight = bmpH,
            contentBounds = margins,
            fitMode = PdfFitMode.FIT_SCREEN,
            trimMargins = true
        )

        val pageCov = fitPage.screenCoverageRatio(viewW, viewH)
        val widthCov = fitWidth.screenCoverageRatio(viewW, viewH)
        val screenCov = fitScreen.screenCoverageRatio(viewW, viewH)

        // Original fit leaves >60% of the phone screen blank/margin (~36.8% coverage on 20:9 phone)
        assertTrue("Expected FIT_PAGE coverage < 0.45, got $pageCov", pageCov < 0.45f)
        // FIT_WIDTH preserves 1:1 aspect ratio and boosts coverage by >1.6x (~61.6% on 20:9 phone)
        assertEquals(fitWidth.pageWidth / bmpW, fitWidth.pageHeight / bmpH, 0.001f)
        assertTrue("Expected FIT_WIDTH coverage > 0.60 (got $widthCov)", widthCov > 0.60f && widthCov > pageCov * 1.6f)
        // FIT_SCREEN fills >96% of the phone screen on first load (~97.6%)
        assertTrue("Expected FIT_SCREEN coverage > 0.96, got $screenCov", screenCov > 0.96f)
    }
}
