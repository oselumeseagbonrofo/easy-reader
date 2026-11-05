package com.ebookreader.app.data.parser.pdf

import android.content.Context
import android.graphics.Point
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.models.selection.SelectionBoundary
import android.os.Build
import androidx.annotation.RequiresApi
import com.ebookreader.app.domain.model.NormalizedRect
import com.ebookreader.app.domain.model.SearchMatch
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
            if (!lastMidY.isNaN() && kotlin.math.abs(midY - lastMidY) > maxOf(h, lastHeight) * 0.7f) {
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

        val charBuilder = StringBuilder()
        val charBounds = ArrayList<NormalizedRect?>()
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

        // First try fast native SelectionBoundary(0)..SelectionBoundary(length) or PdfPageTextContent bounds
        val allRects = ArrayList<android.graphics.RectF>()
        for (c in contents) {
            allRects.addAll(c.bounds)
        }
        if (allRects.isEmpty()) {
            runCatching {
                val sel = page.selectContent(
                    SelectionBoundary(0),
                    SelectionBoundary(fullText.length.coerceAtLeast(1))
                )
                for (sc in sel?.selectedTextContents.orEmpty()) {
                    allRects.addAll(sc.bounds)
                }
            }
        }

        // Detect paragraph breaks vs single line wraps inside the PDF page
        val rawLines = fullText.split('\n')
        for (lineIdx in rawLines.indices) {
            val line = rawLines[lineIdx].trimEnd('\r').trim()
            if (line.isBlank()) {
                if (charBuilder.isNotEmpty() && charBuilder.last() != '\n') {
                    charBuilder.append('\n')
                    charBounds.add(null)
                }
                continue
            }
            if (charBuilder.isNotEmpty() && charBuilder.last() != '\n') {
                val prevLine = rawLines.getOrNull(lineIdx - 1)?.trimEnd('\r')?.trim().orEmpty()
                val prevEndsSentence = prevLine.endsWith('.') || prevLine.endsWith('!') ||
                    prevLine.endsWith('?') || prevLine.endsWith(':')
                val prevIsShort = prevLine.length < 48
                if ((prevEndsSentence && prevLine.length < 68) || prevIsShort) {
                    charBuilder.append('\n')
                    charBounds.add(null)
                } else {
                    charBuilder.append(' ')
                    charBounds.add(null)
                }
            }
            charBuilder.append(line)
            repeat(line.length) { charBounds.add(null) }
        }

        val plain = charBuilder.toString()
        // Populate word-level bounds using native page.searchText for unique tokens or line rects
        val wordRegex = Regex("\\S+")
        val wordsByText = LinkedHashMap<String, MutableList<IntRange>>()
        for (m in wordRegex.findAll(plain)) {
            val token = m.value
            if (token.isNotEmpty()) {
                wordsByText.getOrPut(token) { ArrayList() }.add(m.range)
            }
        }

        var matchedChars = 0
        for ((token, ranges) in wordsByText) {
            val matches = runCatching { page.searchText(token) }.getOrNull().orEmpty()
            if (matches.isNotEmpty()) {
                for (i in ranges.indices) {
                    val range = ranges[i]
                    val matchBound = matches.getOrNull(i) ?: matches.last()
                    val rList = matchBound.bounds
                    if (rList.isNotEmpty()) {
                        val unionLeft = rList.minOf { it.left } / pageWidth
                        val unionTop = rList.minOf { it.top } / pageHeight
                        val unionRight = rList.maxOf { it.right } / pageWidth
                        val unionBottom = rList.maxOf { it.bottom } / pageHeight
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
                            matchedChars++
                        }
                    }
                }
            }
        }

        // Fallback interpolation if any characters didn't get bounds from searchText
        if (matchedChars == 0 && allRects.isNotEmpty() && plain.isNotEmpty()) {
            val rectCount = allRects.size
            for (i in plain.indices) {
                if (plain[i].isWhitespace()) continue
                val rIdx = ((i.toLong() * rectCount) / plain.length).toInt().coerceIn(0, rectCount - 1)
                val r = allRects[rIdx]
                charBounds[i] = NormalizedRect(
                    left = (r.left / pageWidth).coerceIn(0f, 1f),
                    top = (r.top / pageHeight).coerceIn(0f, 1f),
                    right = (r.right / pageWidth).coerceIn(0f, 1f),
                    bottom = (r.bottom / pageHeight).coerceIn(0f, 1f)
                )
            }
        } else if (matchedChars > 0) {
            // Fill gaps between matched tokens on the same line
            var lastRect: NormalizedRect? = null
            for (i in charBounds.indices) {
                val cur = charBounds[i]
                if (cur != null) {
                    lastRect = cur
                } else if (plain[i] != '\n' && lastRect != null) {
                    charBounds[i] = lastRect
                }
            }
        }

        return PdfPageTextLayout(
            pageIndex = pageIndex,
            plainText = plain,
            charBounds = charBounds,
            pageWidthPts = page.width,
            pageHeightPts = page.height
        )
    }

    suspend fun search(
        context: Context,
        controller: PdfDocumentController,
        rawQuery: String,
        maxResults: Int = 300
    ): List<SearchMatch> = withContext(Dispatchers.IO) {
        val query = rawQuery.trim()
        if (query.length < 2 || controller.pageCount <= 0) return@withContext emptyList()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            val api35Matches = runCatching {
                searchWithNativeApi35(controller, query, maxResults)
            }.getOrNull()
            if (api35Matches != null) {
                return@withContext api35Matches
            }
        }

        searchWithPdfBox(context, controller, query, maxResults)
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private suspend fun searchWithNativeApi35(
        controller: PdfDocumentController,
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
        context: Context,
        controller: PdfDocumentController,
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
