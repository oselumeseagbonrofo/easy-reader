package com.ebookreader.app.domain.model

import kotlin.math.roundToInt

enum class BookFormat {
    EPUB,
    PDF
}

data class Book(
    val id: String,
    val title: String,
    val author: String,
    val format: BookFormat,
    val fileUri: String,
    val coverPath: String?,
    val currentPage: Int,
    val totalPages: Int,
    val progress: Float,
    val epubChapterIndex: Int,
    val epubCharOffset: Int,
    val addedAt: Long,
    val lastReadAt: Long?,
    val isFileAccessible: Boolean = true
) {
    val isStarted: Boolean
        get() = lastReadAt != null || currentPage > 0 || progress > 0f

    val isCompleted: Boolean
        get() = isStarted && totalPages > 0 && currentPage >= totalPages - 1

    val progressPercent: Int
        get() = if (!isStarted) 0 else (progress.coerceIn(0f, 1f) * 100f).roundToInt()
}

object ReadingProgressCalculator {
    fun computeProgress(currentPage: Int, totalPages: Int): Float {
        if (totalPages <= 0) return 0f
        if (totalPages == 1) return 1f
        val clampedPage = currentPage.coerceIn(0, totalPages - 1)
        return ((clampedPage + 1).toFloat() / totalPages.toFloat()).coerceIn(0f, 1f)
    }

    fun pageForProgress(progress: Float, totalPages: Int): Int {
        if (totalPages <= 1) return 0
        val clamped = progress.coerceIn(0f, 1f)
        return ((clamped * (totalPages - 1)).roundToInt()).coerceIn(0, totalPages - 1)
    }
}

data class Bookmark(
    val id: Long = 0L,
    val bookId: String,
    val pageIndex: Int,
    val epubChapterIndex: Int,
    val epubCharOffset: Int,
    val epubEndCharOffset: Int = epubCharOffset,
    val progress: Float,
    val chapterTitle: String,
    val snippet: String,
    val createdAt: Long = System.currentTimeMillis()
) {
    val isTextSelection: Boolean
        get() = epubEndCharOffset > epubCharOffset

    val progressPercent: Int
        get() = (progress.coerceIn(0f, 1f) * 100f).roundToInt()
}

enum class HighlightColor(val label: String, val argbHex: Long) {
    YELLOW("Yellow", 0x66FACC15),
    GREEN("Green", 0x664ADE80),
    BLUE("Blue", 0x6638BDF8),
    PINK("Pink", 0x66FB7185)
}

data class TextHighlight(
    val id: Long = 0L,
    val bookId: String,
    val pageIndex: Int,
    val chapterIndex: Int,
    val startCharOffset: Int,
    val endCharOffset: Int,
    val chapterTitle: String,
    val selectedText: String,
    val color: HighlightColor = HighlightColor.YELLOW,
    val createdAt: Long = System.currentTimeMillis()
)

data class TocEntry(
    val title: String,
    val pageIndex: Int,
    val chapterIndex: Int = 0,
    val charOffset: Int = 0,
    val depth: Int = 0
)

/**
 * Coordinates normalized to 0f..1f relative to a PDF page's width and height.
 */
data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

data class SearchMatch(
    val index: Int,
    val pageIndex: Int,
    val chapterIndex: Int = 0,
    val chapterTitle: String = "",
    val snippet: String,
    val matchStartInSnippet: Int,
    val matchEndInSnippet: Int,
    val charOffsetInChapter: Int = 0,
    val matchLength: Int = 0,
    val pdfRects: List<NormalizedRect> = emptyList()
)

enum class ReaderTheme(val label: String) {
    PAPER("Paper"),
    SEPIA("Sepia"),
    DARK("Dark"),
    AMOLED("AMOLED")
}

enum class ReaderFontFamily(val label: String) {
    SERIF("Serif"),
    SANS("Sans"),
    MONO("Mono")
}

enum class PdfFitMode(val label: String) {
    FIT_SCREEN("Full Screen"),
    FIT_WIDTH("Fit Content"),
    FIT_PAGE("Original")
}

data class ReaderSettings(
    val theme: ReaderTheme = ReaderTheme.PAPER,
    val fontSizeSp: Float = 18f,
    val lineHeightMultiplier: Float = 1.5f,
    val fontFamily: ReaderFontFamily = ReaderFontFamily.SERIF,
    val pdfInvertInDark: Boolean = false,
    val pdfFitMode: PdfFitMode = PdfFitMode.FIT_SCREEN,
    val pdfTrimMargins: Boolean = true
)
