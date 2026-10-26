package com.ebookreader.app

import androidx.compose.ui.text.AnnotatedString
import com.ebookreader.app.data.parser.epub.EpubBlock
import com.ebookreader.app.data.parser.epub.EpubBook
import com.ebookreader.app.data.parser.epub.EpubChapter
import com.ebookreader.app.data.parser.epub.EpubPage
import com.ebookreader.app.data.parser.epub.EpubPageItem
import com.ebookreader.app.data.parser.epub.EpubSearchEngine
import com.ebookreader.app.data.parser.epub.EpubTextKind
import com.ebookreader.app.data.parser.epub.PaginatedEpub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubSearchEngineTest {

    @Test
    fun `search finds case-insensitive matches across chapters and maps to paginated pages`() {
        val ch0Text = "Sunrise illuminated the quiet harbor before the voyage began."
        val ch1Part1 = "Three leagues out, the compass needle shimmered."
        val ch1Part2 = "Every sailor trusted that ancient COMPASS through the storm."
        val ch1Full = "$ch1Part1\n$ch1Part2"

        val book = EpubBook(
            title = "Harbor Chronicles",
            author = "M. Mariner",
            chapters = listOf(
                EpubChapter(
                    index = 0,
                    zipPath = "ch0.xhtml",
                    title = "Harbor Dawn",
                    blocks = listOf(
                        EpubBlock.ParagraphBlock(
                            annotatedText = AnnotatedString(ch0Text),
                            kind = EpubTextKind.PARAGRAPH,
                            charStartInChapter = 0,
                            charEndInChapter = ch0Text.length
                        )
                    ),
                    plainText = ch0Text
                ),
                EpubChapter(
                    index = 1,
                    zipPath = "ch1.xhtml",
                    title = "Open Sea",
                    blocks = emptyList(),
                    plainText = ch1Full
                )
            ),
            rawToc = emptyList()
        )

        val paginated = PaginatedEpub(
            pages = listOf(
                EpubPage(
                    globalPageIndex = 0,
                    chapterIndex = 0,
                    chapterTitle = "Harbor Dawn",
                    pageInChapter = 0,
                    startCharOffset = 0,
                    endCharOffset = ch0Text.length,
                    items = listOf(
                        EpubPageItem.TextSlice(
                            AnnotatedString(ch0Text),
                            EpubTextKind.PARAGRAPH,
                            0,
                            ch0Text.length
                        )
                    ),
                    previewText = ch0Text
                ),
                EpubPage(
                    globalPageIndex = 1,
                    chapterIndex = 1,
                    chapterTitle = "Open Sea",
                    pageInChapter = 0,
                    startCharOffset = 0,
                    endCharOffset = ch1Part1.length,
                    items = emptyList(),
                    previewText = ch1Part1
                ),
                EpubPage(
                    globalPageIndex = 2,
                    chapterIndex = 1,
                    chapterTitle = "Open Sea",
                    pageInChapter = 1,
                    startCharOffset = ch1Part1.length + 1,
                    endCharOffset = ch1Full.length,
                    items = emptyList(),
                    previewText = ch1Part2
                )
            ),
            tocEntries = emptyList()
        )

        assertTrue(EpubSearchEngine.search(book, paginated, "c").isEmpty())

        val matches = EpubSearchEngine.search(book, paginated, "compass")
        assertEquals(2, matches.size)

        assertEquals(1, matches[0].pageIndex)
        assertEquals("compass", matches[0].snippet.substring(matches[0].matchStartInSnippet, matches[0].matchEndInSnippet))

        assertEquals(2, matches[1].pageIndex)
        assertEquals("COMPASS", matches[1].snippet.substring(matches[1].matchStartInSnippet, matches[1].matchEndInSnippet))
    }
}
