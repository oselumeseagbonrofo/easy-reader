package com.ebookreader.app.data.parser.pdf

import android.content.Context
import android.graphics.Point
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.models.selection.SelectionBoundary
import android.os.Build
import androidx.annotation.RequiresApi
import com.ebookreader.app.domain.model.NormalizedRect
import com.ebookreader.app.domain.model.SearchMatch
import com.ebookreader.app.domain.model.runCatchingCancellable
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.StringWriter
import kotlin.math.roundToInt

data class PdfPageTextLayout(
    val pageIndex: Int,
    val plainText: String,
    val charBounds: List<NormalizedRect?>,
    val pageWidthPts: Int = 0,
    val pageHeightPts: Int = 0
) {
    val hasText: Boolean
        get() = plainText.isNotBlank() && charBounds.any { it != null }

    fun hitTestCharOffset(
        normX: Float,
        normY: Float,
        allowLooseDrag: Boolean = false
    ): Int? {
        if (plainText.isEmpty() || charBounds.isEmpty()) return null
        var bestIdx = -1
        var bestScore = Float.MAX_VALUE
        var bestDy = Float.MAX_VALUE
        var bestDx = Float.MAX_VALUE

        val limit = minOf(plainText.length, charBounds.size)
        for (i in 0 until limit) {
            val b = charBounds[i] ?: continue
            val dy = when {
                normY < b.top -> b.top - normY
                normY > b.bottom -> normY - b.bottom
                else -> 0f
            }
            val dx = when {
                normX < b.left -> b.left - normX
                normX > b.right -> normX - b.right
                else -> 0f
            }
            val score = dy * 4f + dx * 0.35f
            if (score < bestScore) {
                bestScore = score
                bestIdx = i
                bestDy = dy
                bestDx = dx
            }
        }

        if (bestIdx < 0) return null
        if (!allowLooseDrag && (bestDy > 0.16f || bestDx > 0.35f)) {
            return null
        }
        return bestIdx
    }

    fun rectsForRange(startCharOffset: Int, endCharOffset: Int): List<NormalizedRect> {
        if (plainText.isEmpty() || charBounds.isEmpty()) return emptyList()
        val safeStart = startCharOffset.coerceIn(0, charBounds.size)
        val safeEnd = endCharOffset.coerceIn(safeStart, charBounds.size)
        if (safeEnd <= safeStart) return emptyList()

        val rects = ArrayList<NormalizedRect>()
        var curLeft = Float.MAX_VALUE
        var curTop = Float.MAX_VALUE
        var curRight = -Float.MAX_VALUE
        var curBottom = -Float.MAX_VALUE
        var lastMidY = Float.NaN
        var lastHeight = 0.015f

        fun flushLine() {
            if (curLeft < curRight && curTop < curBottom) {
                rects += NormalizedRect(
                    left = curLeft.coerceIn(0f, 1f),
                    top = curTop.coerceIn(0f, 1f),
                    right = curRight.coerceIn(0f, 1f),
                    bottom = curBottom.coerceIn(0f, 1f)
                )
            }
            curLeft = Float.MAX_VALUE
            curTop = Float.MAX_VALUE
            curRight = -Float.MAX_VALUE
            curBottom = -Float.MAX_VALUE
        }

        for (i in safeStart until safeEnd) {
            if (plainText.getOrNull(i) == '\n') {
                flushLine()
                lastMidY = Float.NaN
                continue
            }
            val b = charBounds[i] ?: continue
            val h = (b.bottom - b.top).coerceAtLeast(0.006f)
            val midY = (b.top + b.bottom) * 0.5f
            val isNewLineWrap = !lastMidY.isNaN() && (
                kotlin.math.abs(midY - lastMidY) > maxOf(h, lastHeight) * 0.65f ||
                    (b.left < curRight - 0.18f && kotlin.math.abs(midY - lastMidY) > maxOf(h, lastHeight) * 0.35f)
                )
            if (isNewLineWrap) {
                flushLine()
            }
            lastMidY = midY
            lastHeight = h
            curLeft = minOf(curLeft, b.left)
            curTop = minOf(curTop, b.top)
            curRight = maxOf(curRight, b.right)
            curBottom = maxOf(curBottom, b.bottom)
        }
        flushLine()
        return rects
    }
}

object PdfSearchEngine {

    fun extractPageTextLayout(doc: PDDocument, pageIndex: Int): PdfPageTextLayout {
        if (pageIndex !in 0 until doc.numberOfPages) {
            return PdfPageTextLayout(pageIndex, "", emptyList())
        }
        val pdPage = doc.getPage(pageIndex)
        val cropBox = pdPage.cropBox ?: pdPage.mediaBox
        val pageWidth = cropBox.width.coerceAtLeast(1f)
        val pageHeight = cropBox.height.coerceAtLeast(1f)

        val charBuilder = StringBuilder()
        val charBounds = ArrayList<NormalizedRect?>()
        var lastLineY = Float.NaN
        var lastLineH = 10f
        var pendingNewlineCheck = false

        val stripper = object : PDFTextStripper() {
            override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
                if (text.isEmpty()) return
                val firstTp = textPositions.firstOrNull()
                if (pendingNewlineCheck && firstTp != null && !lastLineY.isNaN()) {
                    val dy = kotlin.math.abs(firstTp.yDirAdj - lastLineY)
                    val refH = maxOf(firstTp.heightDir, lastLineH).coerceAtLeast(6f)
                    if (dy > refH * 1.65f && charBuilder.isNotEmpty() && charBuilder.last() == ' ') {
                        charBuilder.setCharAt(charBuilder.lastIndex, '\n')
                    }
                }
                pendingNewlineCheck = false

                var tpIdx = 0
                for (ch in text) {
                    val tp = textPositions.getOrNull(tpIdx)
                    charBuilder.append(ch)
                    if (tp != null) {
                        val pw = tp.pageWidth.takeIf { it > 0f } ?: pageWidth
                        val ph = tp.pageHeight.takeIf { it > 0f } ?: pageHeight
                        val y = tp.yDirAdj
                        val rawH = maxOf(tp.heightDir, tp.yScale, tp.fontSizeInPt * 0.85f)
                        val h = rawH.coerceIn(6f, ph * 0.08f)
                        val w = maxOf(tp.widthDirAdj, tp.xScale * 0.4f).coerceAtLeast(1f)
                        lastLineY = y
                        lastLineH = h
                        charBounds.add(
                            NormalizedRect(
                                left = (tp.xDirAdj / pw).coerceIn(0f, 1f),
                                top = ((y - h * 1.15f) / ph).coerceIn(0f, 1f),
                                right = ((tp.xDirAdj + w) / pw).coerceIn(0f, 1f),
                                bottom = ((y + h * 0.25f) / ph).coerceIn(0f, 1f)
                            )
                        )
                    } else {
                        charBounds.add(null)
                    }
                    if (tpIdx < textPositions.lastIndex) {
                        tpIdx++
                    }
                }
                super.writeString(text, textPositions)
            }

            override fun writeWordSeparator() {
                if (charBuilder.isNotEmpty() && !charBuilder.last().isWhitespace()) {
                    charBuilder.append(' ')
                    charBounds.add(null)
                }
                super.writeWordSeparator()
            }

            override fun writeLineSeparator() {
                if (charBuilder.isNotEmpty() && !charBuilder.last().isWhitespace()) {
                    charBuilder.append(' ')
                    charBounds.add(null)
                    pendingNewlineCheck = true
                }
                super.writeLineSeparator()
            }

            override fun writeParagraphEnd() {
                if (charBuilder.isNotEmpty()) {
                    if (charBuilder.last() == ' ') {
                        charBuilder.setCharAt(charBuilder.lastIndex, '\n')
                    } else if (charBuilder.last() != '\n') {
                        charBuilder.append('\n')
                        charBounds.add(null)
                    }
                }
                pendingNewlineCheck = false
                super.writeParagraphEnd()
            }
        }.apply {
            sortByPosition = true
            startPage = pageIndex + 1
            endPage = pageIndex + 1
        }

        stripper.writeText(doc, StringWriter())
        return PdfPageTextLayout(
            pageIndex = pageIndex,
            plainText = charBuilder.toString(),
            charBounds = charBounds,
            pageWidthPts = pageWidth.roundToInt(),
            pageHeightPts = pageHeight.roundToInt()
        )
    }

    data class RawTextMatch(
        val textStartIndex: Int,
        val bounds: List<NormalizedRect>
    )

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    fun extractPageTextLayoutNativeApi35(
        page: PdfRenderer.Page,
        pageIndex: Int
    ): PdfPageTextLayout {
        val pageWidth = page.width.toFloat().coerceAtLeast(1f)
        val pageHeight = page.height.toFloat().coerceAtLeast(1f)
        val contents = page.textContents
        if (contents.isEmpty()) {
            return PdfPageTextLayout(
                pageIndex = pageIndex,
                plainText = "",
                charBounds = emptyList(),
                pageWidthPts = page.width,
                pageHeightPts = page.height
            )
        }
        val fullText = contents.joinToString("\n") { it.text }
        if (fullText.isBlank()) {
            return PdfPageTextLayout(
                pageIndex = pageIndex,
                plainText = "",
                charBounds = emptyList(),
                pageWidthPts = page.width,
                pageHeightPts = page.height
            )
        }

        return buildNativeTextLayout(
            pageIndex = pageIndex,
            rawText = fullText,
            pageWidthPts = page.width,
            pageHeightPts = page.height,
            lineBoundsProvider = { rawStart, rawEnd ->
                val rects = ArrayList<NormalizedRect>()
                runCatching {
                    val sel = page.selectContent(
                        SelectionBoundary(rawStart.coerceAtLeast(0)),
                        SelectionBoundary(rawEnd.coerceAtLeast(rawStart + 1))
                    )
                    for (sc in sel?.selectedTextContents.orEmpty()) {
                        for (r in sc.bounds) {
                            if (r.right > r.left && r.bottom > r.top) {
                                rects += NormalizedRect(
                                    left = (r.left / pageWidth).coerceIn(0f, 1f),
                                    top = (r.top / pageHeight).coerceIn(0f, 1f),
                                    right = (r.right / pageWidth).coerceIn(0f, 1f),
                                    bottom = (r.bottom / pageHeight).coerceIn(0f, 1f)
                                )
                            }
                        }
                    }
                }
                rects
            },
            tokenMatchesProvider = { token ->
                runCatching {
                    page.searchText(token).mapNotNull { mb ->
                        val normRects = mb.bounds.mapNotNull { r ->
                            if (r.right > r.left && r.bottom > r.top) {
                                NormalizedRect(
                                    left = (r.left / pageWidth).coerceIn(0f, 1f),
                                    top = (r.top / pageHeight).coerceIn(0f, 1f),
                                    right = (r.right / pageWidth).coerceIn(0f, 1f),
                                    bottom = (r.bottom / pageHeight).coerceIn(0f, 1f)
                                )
                            } else {
                                null
                            }
                        }
                        if (normRects.isNotEmpty()) {
                            RawTextMatch(textStartIndex = mb.textStartIndex, bounds = normRects)
                        } else {
                            null
                        }
                    }
                }.getOrDefault(emptyList())
            }
        )
    }

    private data class RawLineSpan(
        val rawStart: Int,
        val rawEnd: Int,
        val text: String,
        val precededByBlankLine: Boolean,
        val lineRects: List<NormalizedRect>
    )

    fun buildNativeTextLayout(
        pageIndex: Int,
        rawText: String,
        pageWidthPts: Int,
        pageHeightPts: Int,
        lineBoundsProvider: (rawLineStart: Int, rawLineEnd: Int) -> List<NormalizedRect>,
        tokenMatchesProvider: (token: String) -> List<RawTextMatch>
    ): PdfPageTextLayout {
        val rawLineSpans = ArrayList<RawLineSpan>()
        var cursor = 0
        var sawBlankLine = false
        while (cursor < rawText.length) {
            val nlIdx = rawText.indexOf('\n', cursor).let { if (it < 0) rawText.length else it }
            var trimStart = cursor
            var trimEnd = nlIdx
            while (trimStart < trimEnd && rawText[trimStart].isWhitespace()) trimStart++
            while (trimEnd > trimStart && rawText[trimEnd - 1].isWhitespace()) trimEnd--
            if (trimStart >= trimEnd) {
                sawBlankLine = true
            } else {
                val lineRects = lineBoundsProvider(trimStart, trimEnd)
                rawLineSpans += RawLineSpan(
                    rawStart = trimStart,
                    rawEnd = trimEnd,
                    text = rawText.substring(trimStart, trimEnd),
                    precededByBlankLine = sawBlankLine,
                    lineRects = lineRects
                )
                sawBlankLine = false
            }
            cursor = nlIdx + 1
        }

        if (rawLineSpans.isEmpty()) {
            return PdfPageTextLayout(
                pageIndex = pageIndex,
                plainText = "",
                charBounds = emptyList(),
                pageWidthPts = pageWidthPts,
                pageHeightPts = pageHeightPts
            )
        }

        val allRects = if (rawLineSpans.all { it.lineRects.isEmpty() }) {
            lineBoundsProvider(0, rawText.length.coerceAtLeast(1))
        } else {
            emptyList()
        }

        val maxLineRight = rawLineSpans.mapNotNull { span ->
            span.lineRects.maxOfOrNull { it.right }
        }.maxOrNull() ?: 0.85f
        val minLineLeft = rawLineSpans.mapNotNull { span ->
            span.lineRects.minOfOrNull { it.left }
        }.minOrNull() ?: 0.10f
        val maxLineChars = rawLineSpans.maxOfOrNull { it.text.length } ?: 72

        val charBuilder = StringBuilder()
        val charBounds = ArrayList<NormalizedRect?>()
        val plainToRaw = ArrayList<Int>()
        val linePlainRanges = ArrayList<IntRange>(rawLineSpans.size)

        for (lineIdx in rawLineSpans.indices) {
            val cur = rawLineSpans[lineIdx]
            if (lineIdx > 0) {
                val prev = rawLineSpans[lineIdx - 1]
                val prevTrimmed = prev.text.trimEnd('"', '\'', '”', '’', ')')
                val prevEndsSentence = prevTrimmed.endsWith('.') || prevTrimmed.endsWith('!') ||
                    prevTrimmed.endsWith('?') || prevTrimmed.endsWith(':')

                val isParagraphBreak = if (cur.precededByBlankLine) {
                    true
                } else if (prev.lineRects.isNotEmpty() && cur.lineRects.isNotEmpty()) {
                    val prevTop = prev.lineRects.minOf { it.top }
                    val prevBottom = prev.lineRects.maxOf { it.bottom }
                    val prevLeft = prev.lineRects.minOf { it.left }
                    val prevRight = prev.lineRects.maxOf { it.right }
                    val curTop = cur.lineRects.minOf { it.top }
                    val curBottom = cur.lineRects.maxOf { it.bottom }
                    val curLeft = cur.lineRects.minOf { it.left }
                    val refH = maxOf(prevBottom - prevTop, curBottom - curTop).coerceAtLeast(0.01f)

                    val verticalGapBreak = (curTop - prevTop) > refH * 1.65f
                    val firstLineIndentBreak = prevEndsSentence &&
                        (curLeft > prevLeft + 0.022f || curLeft > minLineLeft + 0.025f)
                    val shortLineBreak = prevEndsSentence &&
                        prevRight < maxLineRight - 0.10f &&
                        prev.text.length < (maxLineChars * 0.82f).toInt().coerceAtLeast(45)
                    val headingBreak = prev.text.length < 40 && prevRight < maxLineRight - 0.18f

                    verticalGapBreak || firstLineIndentBreak || shortLineBreak || headingBreak
                } else {
                    val prevIsShort = prev.text.length < 48
                    (prevEndsSentence && prev.text.length < 68) || prevIsShort
                }

                charBuilder.append(if (isParagraphBreak) '\n' else ' ')
                charBounds.add(null)
                plainToRaw.add(cur.rawStart - 1)
            }

            val linePlainStart = charBuilder.length
            for (rIdx in cur.rawStart until cur.rawEnd) {
                val ch = rawText[rIdx]
                if (ch == '\r') continue
                charBuilder.append(ch)
                charBounds.add(null)
                plainToRaw.add(rIdx)
            }
            val linePlainEnd = charBuilder.length
            linePlainRanges += (linePlainStart until linePlainEnd)

            // Seed baseline character bounds for this specific line from its native line selection rect(s)
            val effectiveLineRects = if (cur.lineRects.isNotEmpty()) {
                cur.lineRects
            } else if (allRects.isNotEmpty()) {
                val fallbackIdx = ((lineIdx.toLong() * allRects.size) / rawLineSpans.size)
                    .toInt()
                    .coerceIn(0, allRects.lastIndex)
                listOf(allRects[fallbackIdx])
            } else {
                emptyList()
            }

            val lineLen = linePlainEnd - linePlainStart
            if (lineLen > 0 && effectiveLineRects.isNotEmpty()) {
                val unionLeft = effectiveLineRects.minOf { it.left }
                val unionTop = effectiveLineRects.minOf { it.top }
                val unionRight = effectiveLineRects.maxOf { it.right }
                val unionBottom = effectiveLineRects.maxOf { it.bottom }
                val stepW = (unionRight - unionLeft) / lineLen.toFloat()
                for (pIdx in linePlainStart until linePlainEnd) {
                    val k = pIdx - linePlainStart
                    charBounds[pIdx] = NormalizedRect(
                        left = (unionLeft + k * stepW).coerceIn(0f, 1f),
                        top = unionTop.coerceIn(0f, 1f),
                        right = (unionLeft + (k + 1) * stepW).coerceIn(0f, 1f),
                        bottom = unionBottom.coerceIn(0f, 1f)
                    )
                }
            }
        }

        val plain = charBuilder.toString()
        val wordRegex = Regex("\\S+")
        val wordsByText = LinkedHashMap<String, MutableList<IntRange>>()
        for (m in wordRegex.findAll(plain)) {
            val token = m.value
            if (token.isNotEmpty()) {
                wordsByText.getOrPut(token) { ArrayList() }.add(m.range)
            }
        }

        for ((token, ranges) in wordsByText) {
            val matches = tokenMatchesProvider(token)
            if (matches.isEmpty()) continue
            for (range in ranges) {
                val expectedRawStart = plainToRaw.getOrNull(range.first) ?: continue
                val baseRect = charBounds.getOrNull(range.first)

                // Match strictly by textStartIndex and same-line vertical bounds so earlier occurrences
                // or substrings on the page are never assigned to this range.
                val matchBound = matches
                    .filter { mb ->
                        val rawDist = kotlin.math.abs(mb.textStartIndex - expectedRawStart)
                        if (rawDist > 3) return@filter false
                        if (baseRect != null && mb.bounds.isNotEmpty()) {
                            val mbTop = mb.bounds.minOf { it.top }
                            val mbBottom = mb.bounds.maxOf { it.bottom }
                            val mbMidY = (mbTop + mbBottom) * 0.5f
                            val baseMidY = (baseRect.top + baseRect.bottom) * 0.5f
                            val refH = maxOf(mbBottom - mbTop, baseRect.bottom - baseRect.top).coerceAtLeast(0.008f)
                            kotlin.math.abs(mbMidY - baseMidY) <= refH * 1.25f
                        } else {
                            true
                        }
                    }
                    .minByOrNull { kotlin.math.abs(it.textStartIndex - expectedRawStart) }
                    ?: continue

                val rList = matchBound.bounds
                if (rList.isNotEmpty()) {
                    val unionLeft = rList.minOf { it.left }
                    val unionTop = rList.minOf { it.top }
                    val unionRight = rList.maxOf { it.right }
                    val unionBottom = rList.maxOf { it.bottom }
                    val len = (range.last - range.first + 1).coerceAtLeast(1)
                    val stepW = (unionRight - unionLeft) / len
                    for (offset in range) {
                        val k = offset - range.first
                        charBounds[offset] = NormalizedRect(
                            left = (unionLeft + k * stepW).coerceIn(0f, 1f),
                            top = unionTop.coerceIn(0f, 1f),
                            right = (unionLeft + (k + 1) * stepW).coerceIn(0f, 1f),
                            bottom = unionBottom.coerceIn(0f, 1f)
                        )
                    }
                }
            }
        }

        // Fill intra-line whitespace gaps strictly within each line (never across lines)
        for (lineRange in linePlainRanges) {
            var lastRectOnLine: NormalizedRect? = null
            for (i in lineRange) {
                val cur = charBounds[i]
                if (cur != null) {
                    lastRectOnLine = cur
                } else if (plain[i] != '\n' && lastRectOnLine != null) {
                    charBounds[i] = lastRectOnLine
                }
            }
        }

        return PdfPageTextLayout(
            pageIndex = pageIndex,
            plainText = plain,
            charBounds = charBounds,
            pageWidthPts = pageWidthPts,
            pageHeightPts = pageHeightPts
        )
    }

    suspend fun search(
        context: Context,
        controller: PdfDocumentController,
        rawQuery: String,
        maxResults: Int = 300
    ): List<SearchMatch> = searchWithAccess(
        controller = controller,
        rawQuery = rawQuery,
        maxResults = maxResults,
        supportsNativeApi35 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM
    )

    internal suspend fun searchWithAccess(
        controller: PdfDocumentAccess,
        rawQuery: String,
        maxResults: Int = 300,
        supportsNativeApi35: Boolean = false,
        nativeApi35Runner: (suspend (PdfDocumentAccess, String, Int) -> List<SearchMatch>)? = null
    ): List<SearchMatch> = withContext(Dispatchers.IO) {
        val query = rawQuery.trim()
        if (query.length < 2 || controller.pageCount <= 0) return@withContext emptyList()

        if (supportsNativeApi35) {
            val api35Matches = runCatchingCancellable {
                if (nativeApi35Runner != null) {
                    nativeApi35Runner.invoke(controller, query, maxResults)
                } else {
                    searchWithNativeApi35(controller, query, maxResults)
                }
            }.getOrNull()
            if (api35Matches != null) {
                return@withContext api35Matches
            }
        }

        searchWithPdfBox(controller, query, maxResults)
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private suspend fun searchWithNativeApi35(
        controller: PdfDocumentAccess,
        query: String,
        maxResults: Int
    ): List<SearchMatch> {
        val results = ArrayList<SearchMatch>()
        val snippetRadius = 45

        for (pageIndex in 0 until controller.pageCount) {
            currentCoroutineContext().ensureActive()
            if (results.size >= maxResults) break

            controller.withPage(pageIndex) { page ->
                val matchBoundsList = page.searchText(query)
                if (matchBoundsList.isNotEmpty()) {
                    val pageWidth = page.width.toFloat().coerceAtLeast(1f)
                    val pageHeight = page.height.toFloat().coerceAtLeast(1f)
                    val fullPageText = page.textContents
                        .joinToString(" ") { it.text }
                        .replace(Regex("\\s+"), " ")
                        .trim()

                    var textSearchCursor = 0
                    for (matchBound in matchBoundsList) {
                        if (results.size >= maxResults) break

                        val normalizedRects = matchBound.bounds.map { rectF ->
                            NormalizedRect(
                                left = (rectF.left / pageWidth).coerceIn(0f, 1f),
                                top = (rectF.top / pageHeight).coerceIn(0f, 1f),
                                right = (rectF.right / pageWidth).coerceIn(0f, 1f),
                                bottom = (rectF.bottom / pageHeight).coerceIn(0f, 1f)
                            )
                        }

                        val foundIdx = if (fullPageText.isNotEmpty()) {
                            fullPageText.indexOf(query, startIndex = textSearchCursor, ignoreCase = true)
                                .takeIf { it >= 0 }
                                ?: matchBound.textStartIndex.coerceIn(0, (fullPageText.length - 1).coerceAtLeast(0))
                        } else {
                            0
                        }
                        if (foundIdx >= 0 && fullPageText.isNotEmpty()) {
                            textSearchCursor = (foundIdx + query.length).coerceAtMost(fullPageText.length)
                        }

                        val (snippet, snippetStart, snippetEnd) = buildSnippet(
                            pageText = fullPageText,
                            matchIdx = foundIdx,
                            query = query,
                            radius = snippetRadius
                        )

                        results += SearchMatch(
                            index = results.size,
                            pageIndex = pageIndex,
                            chapterIndex = pageIndex,
                            chapterTitle = "Page ${pageIndex + 1}",
                            snippet = snippet,
                            matchStartInSnippet = snippetStart,
                            matchEndInSnippet = snippetEnd,
                            charOffsetInChapter = foundIdx,
                            matchLength = query.length,
                            pdfRects = normalizedRects
                        )
                    }
                }
            }
        }
        return results
    }

    private suspend fun searchWithPdfBox(
        controller: PdfDocumentAccess,
        query: String,
        maxResults: Int
    ): List<SearchMatch> {
        val results = ArrayList<SearchMatch>()
        val snippetRadius = 45

        controller.withPdfBoxDocument { doc ->
            run {
                val totalPages = doc.numberOfPages
                for (pageIndex in 0 until totalPages) {
                    currentCoroutineContext().ensureActive()
                    if (results.size >= maxResults) break

                    val pdPage = doc.getPage(pageIndex)
                    val cropBox = pdPage.cropBox ?: pdPage.mediaBox
                    val pageWidth = cropBox.width.coerceAtLeast(1f)
                    val pageHeight = cropBox.height.coerceAtLeast(1f)

                    val charBuilder = StringBuilder()
                    val charPositions = ArrayList<TextPosition?>()

                    val stripper = object : PDFTextStripper() {
                        override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
                            var tpIdx = 0
                            for (ch in text) {
                                charBuilder.append(ch)
                                charPositions.add(textPositions.getOrNull(tpIdx))
                                if (tpIdx < textPositions.lastIndex) {
                                    tpIdx++
                                }
                            }
                            super.writeString(text, textPositions)
                        }

                        override fun writeWordSeparator() {
                            charBuilder.append(' ')
                            charPositions.add(null)
                            super.writeWordSeparator()
                        }

                        override fun writeLineSeparator() {
                            charBuilder.append(' ')
                            charPositions.add(null)
                            super.writeLineSeparator()
                        }
                    }.apply {
                        startPage = pageIndex + 1
                        endPage = pageIndex + 1
                    }

                    stripper.writeText(doc, StringWriter())
                    val pageText = charBuilder.toString()
                    if (pageText.isBlank()) continue

                    var cursor = 0
                    while (cursor < pageText.length && results.size < maxResults) {
                        val foundIdx = pageText.indexOf(query, startIndex = cursor, ignoreCase = true)
                        if (foundIdx < 0) break

                        val matchEnd = (foundIdx + query.length).coerceAtMost(charPositions.size)
                        val matchedPositions = charPositions.subList(foundIdx, matchEnd).filterNotNull()
                        val rects = mergePositionsToRects(matchedPositions, pageWidth, pageHeight)

                        val (snippet, snippetStart, snippetEnd) = buildSnippet(
                            pageText = pageText,
                            matchIdx = foundIdx,
                            query = query,
                            radius = snippetRadius
                        )

                        results += SearchMatch(
                            index = results.size,
                            pageIndex = pageIndex,
                            chapterIndex = pageIndex,
                            chapterTitle = "Page ${pageIndex + 1}",
                            snippet = snippet,
                            matchStartInSnippet = snippetStart,
                            matchEndInSnippet = snippetEnd,
                            charOffsetInChapter = foundIdx,
                            matchLength = query.length,
                            pdfRects = rects
                        )

                        cursor = foundIdx + query.length.coerceAtLeast(1)
                    }
                }
            }
        }
        return results
    }

    private fun mergePositionsToRects(
        positions: List<TextPosition>,
        pageWidth: Float,
        pageHeight: Float
    ): List<NormalizedRect> {
        if (positions.isEmpty()) return emptyList()
        val rects = ArrayList<NormalizedRect>()

        var curLeft = Float.MAX_VALUE
        var curTop = Float.MAX_VALUE
        var curRight = -Float.MAX_VALUE
        var curBottom = -Float.MAX_VALUE
        var lastY = Float.NaN

        fun flushLineRect() {
            if (curLeft < curRight && curTop < curBottom) {
                rects += NormalizedRect(
                    left = (curLeft / pageWidth).coerceIn(0f, 1f),
                    top = (curTop / pageHeight).coerceIn(0f, 1f),
                    right = (curRight / pageWidth).coerceIn(0f, 1f),
                    bottom = (curBottom / pageHeight).coerceIn(0f, 1f)
                )
            }
            curLeft = Float.MAX_VALUE
            curTop = Float.MAX_VALUE
            curRight = -Float.MAX_VALUE
            curBottom = -Float.MAX_VALUE
        }

        for (tp in positions) {
            val y = tp.yDirAdj
            val h = tp.heightDir.coerceAtLeast(6f)
            if (!lastY.isNaN() && kotlin.math.abs(y - lastY) > h * 0.8f) {
                flushLineRect()
            }
            lastY = y
            curLeft = minOf(curLeft, tp.xDirAdj)
            curTop = minOf(curTop, y - h * 1.15f)
            curRight = maxOf(curRight, tp.xDirAdj + tp.widthDirAdj)
            curBottom = maxOf(curBottom, y + h * 0.25f)
        }
        flushLineRect()
        return rects
    }

    private fun buildSnippet(
        pageText: String,
        matchIdx: Int,
        query: String,
        radius: Int
    ): Triple<String, Int, Int> {
        if (pageText.isBlank()) {
            return Triple(query, 0, query.length)
        }
        val safeStart = matchIdx.coerceIn(0, (pageText.length - 1).coerceAtLeast(0))
        val safeEnd = (safeStart + query.length).coerceAtMost(pageText.length)
        val rawStart = (safeStart - radius).coerceAtLeast(0)
        val rawEnd = (safeEnd + radius).coerceAtMost(pageText.length)

        val prefix = if (rawStart > 0) "…" else ""
        val suffix = if (rawEnd < pageText.length) "…" else ""
        val slice = pageText.substring(rawStart, rawEnd).replace('\n', ' ')
        val snippet = "$prefix$slice$suffix"
        val startInSnippet = (prefix.length + (safeStart - rawStart)).coerceIn(0, snippet.length)
        val endInSnippet = (startInSnippet + query.length).coerceIn(startInSnippet, snippet.length)
        return Triple(snippet, startInSnippet, endInSnippet)
    }
}
