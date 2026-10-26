package com.ebookreader.app.data.parser.epub

import androidx.compose.ui.text.AnnotatedString
import com.ebookreader.app.domain.model.TocEntry

enum class EpubTextKind {
    HEADING_1,
    HEADING_2,
    HEADING_3,
    PARAGRAPH,
    QUOTE,
    LIST_ITEM,
    CODE
}

sealed interface EpubBlock {
    data class ParagraphBlock(
        val annotatedText: AnnotatedString,
        val kind: EpubTextKind,
        val charStartInChapter: Int,
        val charEndInChapter: Int,
        val anchorIds: Set<String> = emptySet()
    ) : EpubBlock

    data class ImageBlock(
        val zipEntryPath: String,
        val altText: String?,
        val charOffsetInChapter: Int,
        val anchorIds: Set<String> = emptySet()
    ) : EpubBlock

    data class DividerBlock(
        val charOffsetInChapter: Int
    ) : EpubBlock
}

data class EpubChapter(
    val index: Int,
    val zipPath: String,
    val title: String,
    val blocks: List<EpubBlock>,
    val plainText: String,
    val anchorOffsets: Map<String, Int> = emptyMap()
)

data class EpubRawTocItem(
    val title: String,
    val zipPath: String,
    val fragmentId: String?,
    val depth: Int
)

data class EpubBook(
    val title: String,
    val author: String,
    val chapters: List<EpubChapter>,
    val rawToc: List<EpubRawTocItem>,
    val coverImageBytes: ByteArray? = null
)

sealed interface EpubPageItem {
    data class TextSlice(
        val text: AnnotatedString,
        val kind: EpubTextKind,
        val chapterCharStart: Int,
        val chapterCharEnd: Int
    ) : EpubPageItem

    data class ImageSlice(
        val zipEntryPath: String,
        val altText: String?,
        val maxHeightDp: Float
    ) : EpubPageItem

    data object DividerSlice : EpubPageItem
}

data class EpubPage(
    val globalPageIndex: Int,
    val chapterIndex: Int,
    val chapterTitle: String,
    val pageInChapter: Int,
    val startCharOffset: Int,
    val endCharOffset: Int,
    val items: List<EpubPageItem>,
    val previewText: String
)

data class PaginatedEpub(
    val pages: List<EpubPage>,
    val tocEntries: List<TocEntry>
) {
    fun findPageForAnchor(chapterIndex: Int, charOffset: Int): Int {
        if (pages.isEmpty()) return 0
        val chapterPages = pages.filter { it.chapterIndex == chapterIndex }
        if (chapterPages.isEmpty()) {
            return pages.firstOrNull { it.chapterIndex >= chapterIndex }?.globalPageIndex
                ?: pages.last().globalPageIndex
        }
        val containing = chapterPages.firstOrNull { page ->
            charOffset in page.startCharOffset..maxOf(page.startCharOffset, page.endCharOffset)
        }
        if (containing != null) return containing.globalPageIndex

        val closestBefore = chapterPages.lastOrNull { it.startCharOffset <= charOffset }
        return (closestBefore ?: chapterPages.first()).globalPageIndex
    }
}
