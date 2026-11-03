package com.ebookreader.app.data.parser.epub

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ebookreader.app.domain.model.ReaderFontFamily
import com.ebookreader.app.domain.model.ReaderSettings
import com.ebookreader.app.domain.model.TocEntry
import kotlin.math.roundToInt

object EpubPaginator {

    fun fontFamilyFor(choice: ReaderFontFamily): FontFamily = when (choice) {
        ReaderFontFamily.SERIF -> FontFamily.Serif
        ReaderFontFamily.SANS -> FontFamily.SansSerif
        ReaderFontFamily.MONO -> FontFamily.Monospace
    }

    fun textStyleForKind(kind: EpubTextKind, settings: ReaderSettings): TextStyle {
        val baseFont = fontFamilyFor(settings.fontFamily)
        val baseSize = settings.fontSizeSp
        val lineMult = settings.lineHeightMultiplier
        return when (kind) {
            EpubTextKind.HEADING_1 -> TextStyle(
                fontFamily = baseFont,
                fontSize = (baseSize * 1.42f).sp,
                lineHeight = (baseSize * 1.42f * 1.25f).sp,
                fontWeight = FontWeight.Bold
            )
            EpubTextKind.HEADING_2 -> TextStyle(
                fontFamily = baseFont,
                fontSize = (baseSize * 1.24f).sp,
                lineHeight = (baseSize * 1.24f * 1.3f).sp,
                fontWeight = FontWeight.SemiBold
            )
            EpubTextKind.HEADING_3 -> TextStyle(
                fontFamily = baseFont,
                fontSize = (baseSize * 1.1f).sp,
                lineHeight = (baseSize * 1.1f * 1.35f).sp,
                fontWeight = FontWeight.SemiBold
            )
            EpubTextKind.QUOTE -> TextStyle(
                fontFamily = baseFont,
                fontSize = (baseSize * 0.96f).sp,
                lineHeight = (baseSize * 0.96f * lineMult).sp,
                fontStyle = FontStyle.Italic
            )
            EpubTextKind.CODE -> TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = (baseSize * 0.88f).sp,
                lineHeight = (baseSize * 0.88f * 1.35f).sp
            )
            EpubTextKind.LIST_ITEM,
            EpubTextKind.PARAGRAPH -> TextStyle(
                fontFamily = baseFont,
                fontSize = baseSize.sp,
                lineHeight = (baseSize * lineMult).sp,
                fontWeight = FontWeight.Normal
            )
        }
    }

    /**
     * Measures a single [EpubChapter] into discrete screen-sized pages.
     * Used by lazy windowed pagination to render the active chapter immediately (<15ms)
     * while surrounding chapters are measured in the background.
     */
    fun paginateChapter(
        chapter: EpubChapter,
        textMeasurer: TextMeasurer,
        viewportWidthPx: Int,
        viewportHeightPx: Int,
        density: Density,
        settings: ReaderSettings
    ): List<EpubPage> {
        val safeWidthPx = viewportWidthPx.coerceAtLeast(240)
        val safeHeightPx = viewportHeightPx.coerceAtLeast(320)

        val paragraphGapPx = with(density) { 12.dp.roundToPx() }
        val headingGapPx = with(density) { 16.dp.roundToPx() }
        val dividerHeightPx = with(density) { 28.dp.roundToPx() }
        val quoteHorizontalInsetPx = with(density) { 20.dp.roundToPx() }
        val imageHeightPx = (safeHeightPx * 0.56f).roundToInt().coerceAtLeast(with(density) { 140.dp.roundToPx() })
        val imageMaxHeightDp = with(density) { imageHeightPx.toDp().value }

        val chapterPages = ArrayList<EpubPage>()
        var pageInChapter = 0
        var availableHeightPx = safeHeightPx
        val currentItems = ArrayList<EpubPageItem>()
        var pageStartChar = -1
        var pageEndChar = 0

        fun flushCurrentPage() {
            if (currentItems.isEmpty()) return
            val preview = currentItems
                .filterIsInstance<EpubPageItem.TextSlice>()
                .joinToString(" ") { it.text.text.trim() }
                .take(140)
                .ifBlank { chapter.title }

            val resolvedStart = if (pageStartChar >= 0) pageStartChar else 0
            val resolvedEnd = maxOf(resolvedStart, pageEndChar)

            chapterPages += EpubPage(
                globalPageIndex = chapterPages.size,
                chapterIndex = chapter.index,
                chapterTitle = chapter.title,
                pageInChapter = pageInChapter,
                startCharOffset = resolvedStart,
                endCharOffset = resolvedEnd,
                items = currentItems.toList(),
                previewText = preview
            )
            pageInChapter++
            currentItems.clear()
            availableHeightPx = safeHeightPx
            pageStartChar = -1
        }

        for (block in chapter.blocks) {
            when (block) {
                is EpubBlock.DividerBlock -> {
                    if (availableHeightPx < dividerHeightPx && currentItems.isNotEmpty()) {
                        flushCurrentPage()
                    }
                    if (pageStartChar < 0) pageStartChar = block.charOffsetInChapter
                    pageEndChar = maxOf(pageEndChar, block.charOffsetInChapter)
                    currentItems += EpubPageItem.DividerSlice
                    availableHeightPx -= dividerHeightPx
                }

                is EpubBlock.ImageBlock -> {
                    val needed = imageHeightPx + paragraphGapPx
                    if (availableHeightPx < needed && currentItems.isNotEmpty()) {
                        flushCurrentPage()
                    }
                    if (pageStartChar < 0) pageStartChar = block.charOffsetInChapter
                    pageEndChar = maxOf(pageEndChar, block.charOffsetInChapter)
                    currentItems += EpubPageItem.ImageSlice(
                        zipEntryPath = block.zipEntryPath,
                        altText = block.altText,
                        maxHeightDp = imageMaxHeightDp
                    )
                    availableHeightPx -= needed
                }

                is EpubBlock.ParagraphBlock -> {
                    val style = textStyleForKind(block.kind, settings)
                    val blockMaxWidth = if (block.kind == EpubTextKind.QUOTE) {
                        (safeWidthPx - quoteHorizontalInsetPx).coerceAtLeast(160)
                    } else {
                        safeWidthPx
                    }
                    val gapAfter = when (block.kind) {
                        EpubTextKind.HEADING_1,
                        EpubTextKind.HEADING_2,
                        EpubTextKind.HEADING_3 -> headingGapPx
                        else -> paragraphGapPx
                    }

                    var remaining: AnnotatedString = block.annotatedText
                    var sliceCharCursor = block.charStartInChapter

                    while (remaining.text.isNotEmpty()) {
                        val layout = textMeasurer.measure(
                            text = remaining,
                            style = style,
                            constraints = Constraints(maxWidth = blockMaxWidth)
                        )

                        if (layout.size.height <= availableHeightPx) {
                            val sliceEnd = sliceCharCursor + remaining.length
                            if (pageStartChar < 0) pageStartChar = sliceCharCursor
                            pageEndChar = maxOf(pageEndChar, sliceEnd)

                            currentItems += EpubPageItem.TextSlice(
                                text = remaining,
                                kind = block.kind,
                                chapterCharStart = sliceCharCursor,
                                chapterCharEnd = sliceEnd
                            )
                            availableHeightPx -= (layout.size.height + gapAfter)
                            break
                        } else {
                            val firstLineHeight = if (layout.lineCount > 0) {
                                layout.getLineBottom(0).roundToInt()
                            } else {
                                availableHeightPx + 1
                            }

                            if (firstLineHeight > availableHeightPx && currentItems.isNotEmpty()) {
                                flushCurrentPage()
                                continue
                            }

                            val targetLine = layout.getLineForVerticalPosition(availableHeightPx.toFloat())
                                .let { line ->
                                    if (line > 0 && layout.getLineBottom(line) > availableHeightPx) {
                                        line - 1
                                    } else {
                                        line
                                    }
                                }
                                .coerceAtLeast(0)

                            val rawSplit = layout.getLineEnd(targetLine, visibleEnd = false)
                                .coerceIn(1, remaining.length)

                            val head = remaining.subSequence(0, rawSplit)
                            val sliceEnd = sliceCharCursor + rawSplit
                            if (pageStartChar < 0) pageStartChar = sliceCharCursor
                            pageEndChar = maxOf(pageEndChar, sliceEnd)

                            currentItems += EpubPageItem.TextSlice(
                                text = head,
                                kind = block.kind,
                                chapterCharStart = sliceCharCursor,
                                chapterCharEnd = sliceEnd
                            )
                            flushCurrentPage()

                            var nextStart = rawSplit
                            while (nextStart < remaining.length &&
                                (remaining.text[nextStart] == '\n' || remaining.text[nextStart] == ' ')
                            ) {
                                nextStart++
                            }
                            sliceCharCursor = block.charStartInChapter + (sliceCharCursor - block.charStartInChapter) + nextStart
                            remaining = if (nextStart < remaining.length) {
                                remaining.subSequence(nextStart, remaining.length)
                            } else {
                                AnnotatedString("")
                            }
                        }
                    }
                }
            }
        }

        flushCurrentPage()
        return chapterPages
    }

    /**
     * Stitches paginated chapter slices in ascending chapter order into a [PaginatedEpub],
     * assigning global page indices and resolving Table of Contents page targets.
     */
    fun assemblePaginatedEpub(
        book: EpubBook,
        paginatedChapters: Map<Int, List<EpubPage>>
    ): PaginatedEpub {
        val pages = ArrayList<EpubPage>()
        for (chapterIdx in paginatedChapters.keys.sorted()) {
            val chPages = paginatedChapters[chapterIdx].orEmpty()
            for (p in chPages) {
                pages += p.copy(globalPageIndex = pages.size)
            }
        }

        if (pages.isEmpty()) {
            pages += EpubPage(
                globalPageIndex = 0,
                chapterIndex = 0,
                chapterTitle = book.title,
                pageInChapter = 0,
                startCharOffset = 0,
                endCharOffset = 0,
                items = listOf(
                    EpubPageItem.TextSlice(
                        text = AnnotatedString("No readable text content found in this EPUB."),
                        kind = EpubTextKind.PARAGRAPH,
                        chapterCharStart = 0,
                        chapterCharEnd = 0
                    )
                ),
                previewText = book.title
            )
        }

        val chapterByZipPath = book.chapters.associateBy { it.zipPath }
        val prelimPaginated = PaginatedEpub(pages = pages, tocEntries = emptyList())

        val resolvedToc = book.rawToc.mapNotNull { raw ->
            val targetChapter = chapterByZipPath[raw.zipPath]
                ?: book.chapters.firstOrNull { it.zipPath.equals(raw.zipPath, ignoreCase = true) }
                ?: return@mapNotNull null

            val charOffset = raw.fragmentId?.let { targetChapter.anchorOffsets[it] } ?: 0
            val pageIdx = prelimPaginated.findPageForAnchor(targetChapter.index, charOffset)
            TocEntry(
                title = raw.title,
                pageIndex = pageIdx,
                chapterIndex = targetChapter.index,
                charOffset = charOffset,
                depth = raw.depth
            )
        }

        return PaginatedEpub(
            pages = pages,
            tocEntries = resolvedToc
        )
    }

    fun paginate(
        book: EpubBook,
        textMeasurer: TextMeasurer,
        viewportWidthPx: Int,
        viewportHeightPx: Int,
        density: Density,
        settings: ReaderSettings
    ): PaginatedEpub {
        val byChapter = LinkedHashMap<Int, List<EpubPage>>(book.chapters.size)
        for (chapter in book.chapters) {
            byChapter[chapter.index] = paginateChapter(
                chapter = chapter,
                textMeasurer = textMeasurer,
                viewportWidthPx = viewportWidthPx,
                viewportHeightPx = viewportHeightPx,
                density = density,
                settings = settings
            )
        }
        return assemblePaginatedEpub(book, byChapter)
    }

    /**
     * Generates chapter indices ordered outward from [centerChapter] (center, +1, -1, +2, -2, ...)
     * so adjacent chapters are ready first when the user swipes forward or backward.
     */
    fun radialChapterOrder(totalChapters: Int, centerChapter: Int): List<Int> {
        if (totalChapters <= 0) return emptyList()
        val clampedCenter = centerChapter.coerceIn(0, totalChapters - 1)
        val result = ArrayList<Int>(totalChapters)
        result += clampedCenter
        var step = 1
        while (result.size < totalChapters) {
            val forward = clampedCenter + step
            if (forward < totalChapters) result += forward
            val backward = clampedCenter - step
            if (backward >= 0) result += backward
            step++
        }
        return result
    }
}
