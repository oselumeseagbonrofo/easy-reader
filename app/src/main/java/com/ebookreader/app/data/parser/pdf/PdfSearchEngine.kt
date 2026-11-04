package com.ebookreader.app.data.parser.pdf

import android.content.Context
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

object PdfSearchEngine {

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
