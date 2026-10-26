package com.ebookreader.app

import com.ebookreader.app.data.parser.epub.EpubPage
import com.ebookreader.app.data.parser.epub.PaginatedEpub
import com.ebookreader.app.domain.model.Book
import com.ebookreader.app.domain.model.BookFormat
import com.ebookreader.app.domain.model.ReadingProgressCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressCalculationTest {

    @Test
    fun `computeProgress handles empty, single-page, first-page, and final-page boundaries`() {
        assertEquals(0f, ReadingProgressCalculator.computeProgress(0, 0), 0.0001f)
        assertEquals(1f, ReadingProgressCalculator.computeProgress(0, 1), 0.0001f)
        assertEquals(0.01f, ReadingProgressCalculator.computeProgress(0, 100), 0.0001f)
        assertEquals(0.50f, ReadingProgressCalculator.computeProgress(49, 100), 0.0001f)
        assertEquals(1.00f, ReadingProgressCalculator.computeProgress(99, 100), 0.0001f)
        assertEquals(1.00f, ReadingProgressCalculator.computeProgress(250, 100), 0.0001f)
    }

    @Test
    fun `pageForProgress maps normalized slider ratio to valid 0-based page index`() {
        assertEquals(0, ReadingProgressCalculator.pageForProgress(0f, 200))
        assertEquals(100, ReadingProgressCalculator.pageForProgress(0.5f, 201))
        assertEquals(199, ReadingProgressCalculator.pageForProgress(1f, 200))
    }

    @Test
    fun `Book completion and unread status reflect reading state accurately`() {
        val unread = Book(
            id = "hash1",
            title = "Sample",
            author = "Author",
            format = BookFormat.EPUB,
            fileUri = "content://downloads/1",
            coverPath = null,
            currentPage = 0,
            totalPages = 120,
            progress = 0f,
            epubChapterIndex = 0,
            epubCharOffset = 0,
            addedAt = 1000L,
            lastReadAt = null
        )
        assertFalse(unread.isStarted)
        assertFalse(unread.isCompleted)
        assertEquals(0, unread.progressPercent)

        val finished = unread.copy(
            currentPage = 119,
            progress = 1f,
            lastReadAt = 2000L
        )
        assertTrue(finished.isStarted)
        assertTrue(finished.isCompleted)
        assertEquals(100, finished.progressPercent)
    }

    @Test
    fun `PaginatedEpub findPageForAnchor recovers reading spot after font-size reflow`() {
        val paginated = PaginatedEpub(
            pages = listOf(
                EpubPage(0, 0, "Ch 1", 0, 0, 350, emptyList(), ""),
                EpubPage(1, 0, "Ch 1", 1, 351, 720, emptyList(), ""),
                EpubPage(2, 1, "Ch 2", 0, 0, 400, emptyList(), ""),
                EpubPage(3, 1, "Ch 2", 1, 401, 900, emptyList(), "")
            ),
            tocEntries = emptyList()
        )

        assertEquals(0, paginated.findPageForAnchor(chapterIndex = 0, charOffset = 120))
        assertEquals(1, paginated.findPageForAnchor(chapterIndex = 0, charOffset = 500))
        assertEquals(3, paginated.findPageForAnchor(chapterIndex = 1, charOffset = 650))
    }
}
