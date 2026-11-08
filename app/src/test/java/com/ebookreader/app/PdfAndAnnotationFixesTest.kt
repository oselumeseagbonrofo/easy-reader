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

    @Test
    fun `PDF page text layout hit tests touch coordinates and resolves multi-line highlight rects`() {
        val p1Line1 = "Quantum computing accelerates molecular simulation."
        val p1Line2 = "Second sentence wraps across two visual lines cleanly."
        val p2 = "Final paragraph starts after a vertical break."
        val fullText = "$p1Line1 $p1Line2\n$p2"

        val bounds = ArrayList<com.ebookreader.app.domain.model.NormalizedRect?>(fullText.length)
        for (i in fullText.indices) {
            val ch = fullText[i]
            if (ch == '\n' || ch == ' ') {
                bounds.add(null)
            } else if (i < p1Line1.length) {
                val col = i.toFloat() / p1Line1.length
                bounds.add(
                    com.ebookreader.app.domain.model.NormalizedRect(
                        left = 0.10f + col * 0.75f,
                        top = 0.15f,
                        right = 0.10f + (col + 0.018f) * 0.75f,
                        bottom = 0.175f
                    )
                )
            } else if (i <= p1Line1.length + 1 + p1Line2.length) {
                val localIdx = i - (p1Line1.length + 1)
                val secondLineHalf = p1Line2.length / 2
                val isWrappedLine = localIdx >= secondLineHalf
                val col = if (!isWrappedLine) {
                    localIdx.toFloat() / secondLineHalf
                } else {
                    (localIdx - secondLineHalf).toFloat() / (p1Line2.length - secondLineHalf)
                }
                val top = if (!isWrappedLine) 0.19f else 0.23f
                bounds.add(
                    com.ebookreader.app.domain.model.NormalizedRect(
                        left = 0.10f + col * 0.75f,
                        top = top,
                        right = 0.10f + (col + 0.03f) * 0.75f,
                        bottom = top + 0.025f
                    )
                )
            } else {
                val localIdx = i - (p1Line1.length + 1 + p1Line2.length + 1)
                val col = localIdx.toFloat() / p2.length
                bounds.add(
                    com.ebookreader.app.domain.model.NormalizedRect(
                        left = 0.10f + col * 0.70f,
                        top = 0.32f,
                        right = 0.10f + (col + 0.02f) * 0.70f,
                        bottom = 0.345f
                    )
                )
            }
        }

        val layout = com.ebookreader.app.data.parser.pdf.PdfPageTextLayout(
            pageIndex = 0,
            plainText = fullText,
            charBounds = bounds
        )

        // Hit-test near the middle of the wrapped second sentence on line 2 (top=0.19..0.215)
        val hitOffset = layout.hitTestCharOffset(normX = 0.32f, normY = 0.20f)
        org.junit.Assert.assertNotNull("Expected long-press on PDF text line to hit a character", hitOffset)
        val (sentStart, sentEnd) = com.ebookreader.app.data.parser.epub.EpubSelectionHelper.expandToSentence(
            layout.plainText,
            hitOffset!!
        )
        assertEquals(p1Line2, layout.plainText.substring(sentStart, sentEnd))

        // Paragraph expansion from same hit selects the entire 2-sentence first paragraph
        val (paraStart, paraEnd) = com.ebookreader.app.data.parser.epub.EpubSelectionHelper.expandToParagraph(
            layout.plainText,
            hitOffset
        )
        assertEquals("$p1Line1 $p1Line2", layout.plainText.substring(paraStart, paraEnd))

        // Word-boundary drag selection across sentence 1 and sentence 2
        val (wordStart, wordEnd) = com.ebookreader.app.data.parser.epub.EpubSelectionHelper.expandRangeToWordBoundaries(
            layout.plainText,
            fullText.indexOf("molecular"),
            fullText.indexOf("wraps") + 2
        )
        assertEquals(
            "molecular simulation. Second sentence wraps",
            layout.plainText.substring(wordStart, wordEnd)
        )

        // Multi-line sentence should merge into 2 line highlight rectangles
        val highlightRects = layout.rectsForRange(sentStart, sentEnd)
        assertEquals(2, highlightRects.size)

        // Full paragraph (line 1 + wrapped sentence across lines 2 & 3) merges into 3 line rectangles
        val paraRects = layout.rectsForRange(paraStart, paraEnd)
        assertEquals(3, paraRects.size)

        // Loose drag hit-testing outside direct line bounds resolves to nearest character seamlessly
        val looseDragHit = layout.hitTestCharOffset(normX = 0.92f, normY = 0.38f, allowLooseDrag = true)
        org.junit.Assert.assertNotNull("Expected loose drag hit-test to resolve nearest character", looseDragHit)
        assertEquals(fullText.length - 1, looseDragHit)
    }

    @Test
    fun `purgeStalePdfBoxTempFiles removes leaked PDFBox tmp files without touching covers`() {
        val tempRoot = java.nio.file.Files.createTempDirectory("easy_reader_cache_test").toFile()
        try {
            val stale1 = java.io.File(tempRoot, "PDFBox2980990577159650174.tmp").apply { writeBytes(ByteArray(128)) }
            val stale2 = java.io.File(tempRoot, "PDFBox7577516149433317059.tmp").apply { writeBytes(ByteArray(256)) }
            val dbLock = java.io.File(tempRoot, "easy_reader.db.lck").apply { writeText("lock") }
            val coversDir = java.io.File(tempRoot, "covers").apply { mkdirs() }
            val coverFile = java.io.File(coversDir, "book1.webp").apply { writeText("webp") }

            PdfDocumentController.purgeStalePdfBoxTempFiles(tempRoot)

            org.junit.Assert.assertFalse("Expected PDFBox tmp #1 to be purged", stale1.exists())
            org.junit.Assert.assertFalse("Expected PDFBox tmp #2 to be purged", stale2.exists())
            assertTrue("Expected db lock file to remain untouched", dbLock.exists())
            assertTrue("Expected cover thumbnail to remain untouched", coverFile.exists())
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `native PDF text layout highlights exact selected paragraph instead of first occurrences on page`() {
        val para1Line1 = "The history of the city begins in an ancient valley where people lived in peace."
        val para2Line1 = "In this chapter, we examine the architecture of a house in the old quarter."
        val para2Line2 = "Second line of the target paragraph concludes the discussion."
        val rawText = "$para1Line1\n\n$para2Line1\n$para2Line2"

        val p1Start = 0
        val p1End = para1Line1.length
        val p2L1Start = p1End + 2
        val p2L1End = p2L1Start + para2Line1.length
        val p2L2Start = p2L1End + 1
        val p2L2End = p2L2Start + para2Line2.length

        fun rectForRawRange(rawStart: Int, rawEnd: Int): com.ebookreader.app.domain.model.NormalizedRect {
            return when {
                rawStart < p1End -> {
                    val s = (rawStart - p1Start).coerceAtLeast(0).toFloat() / para1Line1.length
                    val e = (rawEnd - p1Start).coerceAtMost(para1Line1.length).toFloat() / para1Line1.length
                    com.ebookreader.app.domain.model.NormalizedRect(
                        left = 0.10f + s * 0.80f,
                        top = 0.10f,
                        right = 0.10f + e * 0.80f,
                        bottom = 0.125f
                    )
                }
                rawStart < p2L1End -> {
                    val s = (rawStart - p2L1Start).coerceAtLeast(0).toFloat() / para2Line1.length
                    val e = (rawEnd - p2L1Start).coerceAtMost(para2Line1.length).toFloat() / para2Line1.length
                    com.ebookreader.app.domain.model.NormalizedRect(
                        left = 0.10f + s * 0.80f,
                        top = 0.55f,
                        right = 0.10f + e * 0.80f,
                        bottom = 0.575f
                    )
                }
                else -> {
                    val s = (rawStart - p2L2Start).coerceAtLeast(0).toFloat() / para2Line2.length
                    val e = (rawEnd - p2L2Start).coerceAtMost(para2Line2.length).toFloat() / para2Line2.length
                    com.ebookreader.app.domain.model.NormalizedRect(
                        left = 0.10f + s * 0.75f,
                        top = 0.59f,
                        right = 0.10f + e * 0.75f,
                        bottom = 0.615f
                    )
                }
            }
        }

        val layout = com.ebookreader.app.data.parser.pdf.PdfSearchEngine.buildNativeTextLayout(
            pageIndex = 0,
            rawText = rawText,
            pageWidthPts = 600,
            pageHeightPts = 800,
            lineBoundsProvider = { rawStart, rawEnd ->
                if (rawStart == 0 && rawEnd >= rawText.length) {
                    listOf(
                        rectForRawRange(p1Start, p1End),
                        rectForRawRange(p2L1Start, p2L1End),
                        rectForRawRange(p2L2Start, p2L2End)
                    )
                } else {
                    listOf(rectForRawRange(rawStart, rawEnd))
                }
            },
            tokenMatchesProvider = { query ->
                val results = ArrayList<com.ebookreader.app.data.parser.pdf.PdfSearchEngine.RawTextMatch>()
                var cursor = 0
                while (cursor < rawText.length) {
                    val idx = rawText.indexOf(query, startIndex = cursor, ignoreCase = true)
                    if (idx < 0) break
                    results += com.ebookreader.app.data.parser.pdf.PdfSearchEngine.RawTextMatch(
                        textStartIndex = idx,
                        bounds = listOf(rectForRawRange(idx, idx + query.length))
                    )
                    cursor = idx + 1
                }
                results
            }
        )

        // Hit-test inside Paragraph 2 (y = 0.56f) and expand to Paragraph 2
        val hitInPara2 = layout.hitTestCharOffset(normX = 0.35f, normY = 0.56f)
        org.junit.Assert.assertNotNull("Expected hit inside Paragraph 2", hitInPara2)
        val (paraStart, paraEnd) = com.ebookreader.app.data.parser.epub.EpubSelectionHelper.expandToParagraph(
            layout.plainText,
            hitInPara2!!
        )
        val selectedSnippet = layout.plainText.substring(paraStart, paraEnd)
        assertEquals("$para2Line1 $para2Line2", selectedSnippet)

        // Highlight rectangles for Paragraph 2 must ONLY cover Paragraph 2 (top >= 0.50f),
        // NEVER leaking to first occurrences of words ("In", "the", "of", "a", "in") in Paragraph 1 (top = 0.10f).
        val highlightRects = layout.rectsForRange(paraStart, paraEnd)
        assertEquals(
            "Expected exactly 2 highlight line rects for the 2-line second paragraph, but got: $highlightRects",
            2,
            highlightRects.size
        )
        for (rect in highlightRects) {
            assertTrue(
                "Highlight rect leaked outside Paragraph 2 into earlier occurrence on page: $rect",
                rect.top >= 0.50f && rect.bottom <= 0.65f
            )
        }
    }
}
