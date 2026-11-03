package com.ebookreader.app

import androidx.compose.ui.text.AnnotatedString
import com.ebookreader.app.data.parser.epub.EpubBlock
import com.ebookreader.app.data.parser.epub.EpubPage
import com.ebookreader.app.data.parser.epub.EpubPageItem
import com.ebookreader.app.data.parser.epub.EpubPaginator
import com.ebookreader.app.data.parser.epub.EpubParser
import com.ebookreader.app.data.parser.epub.EpubRandomAccessArchive
import com.ebookreader.app.data.parser.epub.EpubSelectionHelper
import com.ebookreader.app.data.parser.epub.EpubTextKind
import com.ebookreader.app.data.parser.pdf.PdfDocumentController
import com.ebookreader.app.domain.model.Bookmark
import com.ebookreader.app.domain.model.HighlightColor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.StandardOpenOption
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class V2EnhancementsTest {

    private fun createSyntheticMultiChapterEpub(file: File, chapterCount: Int = 60) {
        ZipOutputStream(FileOutputStream(file)).use { zos ->
            fun putEntry(path: String, bytes: ByteArray, stored: Boolean = false) {
                val entry = ZipEntry(path)
                if (stored) {
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    val crc = java.util.zip.CRC32().apply { update(bytes) }
                    entry.crc = crc.value
                }
                zos.putNextEntry(entry)
                zos.write(bytes)
                zos.closeEntry()
            }

            putEntry(
                "mimetype",
                "application/epub+zip".toByteArray(StandardCharsets.UTF_8),
                stored = true
            )
            putEntry(
                "META-INF/container.xml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>
                """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            )

            val manifestItems = (1..chapterCount).joinToString("\n") { idx ->
                """<item id="ch$idx" href="Text/ch$idx.xhtml" media-type="application/xhtml+xml"/>"""
            }
            val spineRefs = (1..chapterCount).joinToString("\n") { idx ->
                """<itemref idref="ch$idx"/>"""
            }
            val navPoints = (1..chapterCount).joinToString("\n") { idx ->
                """
                <navPoint id="np-$idx" playOrder="$idx">
                  <navLabel><text>Chapter $idx</text></navLabel>
                  <content src="Text/ch$idx.xhtml"/>
                </navPoint>
                """.trimIndent()
            }

            putEntry(
                "OEBPS/content.opf",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>Deep Space Chronicles</dc:title>
                    <dc:creator>Grace Hopper</dc:creator>
                    <meta name="cover" content="cover-img"/>
                  </metadata>
                  <manifest>
                    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                    <item id="cover-img" href="Images/cover.png" media-type="image/png"/>
                    <item id="tail-diagram" href="Images/diagram_end.png" media-type="image/png"/>
                    $manifestItems
                  </manifest>
                  <spine toc="ncx">
                    $spineRefs
                  </spine>
                </package>
                """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            )

            putEntry(
                "OEBPS/toc.ncx",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
                  <navMap>$navPoints</navMap>
                </ncx>
                """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            )

            val coverBytes = ByteArray(4096) { i -> ((i * 31 + 7) and 0xFF).toByte() }
            putEntry("OEBPS/Images/cover.png", coverBytes)

            val paragraphSeed = buildString {
                repeat(18) { p ->
                    append("<p>Paragraph ${p + 1}: Starlight navigation relies on precise angular measurements across deep orbital sectors. ")
                    append("Every sensor reading refines velocity vectors without duplicating massive telemetry archives in local memory! ")
                    append("Does the horizon shift when magnification changes? Only the viewport window adapts.</p>\n")
                }
            }

            for (idx in 1..chapterCount) {
                val html = """
                    <?xml version="1.0" encoding="utf-8"?>
                    <html xmlns="http://www.w3.org/1999/xhtml">
                    <body>
                      <h1>Chapter $idx: Sector $idx</h1>
                      $paragraphSeed
                    </body>
                    </html>
                """.trimIndent()
                putEntry("OEBPS/Text/ch$idx.xhtml", html.toByteArray(StandardCharsets.UTF_8))
            }

            val tailImageBytes = ByteArray(8192) { i -> ((i * 17 + 3) and 0xFF).toByte() }
            putEntry("OEBPS/Images/diagram_end.png", tailImageBytes)
        }
    }

    @Test
    fun `EpubRandomAccessArchive achieves O(1) lookup parity with O(N) ZipInputStream`() {
        val tempEpub = File.createTempFile("v2_bench_", ".epub")
        try {
            createSyntheticMultiChapterEpub(tempEpub, chapterCount = 60)

            FileChannel.open(tempEpub.toPath(), StandardOpenOption.READ).use { channel ->
                val archive = EpubRandomAccessArchive.openOrNull(channel, closeChannelOnClose = false)
                assertNotNull("Expected Central Directory archive to open", archive)
                archive!!

                // 1. Stored entry ("mimetype") and deflated entry parity
                assertEquals(
                    "application/epub+zip",
                    archive.readEntry("mimetype")?.toString(StandardCharsets.UTF_8)
                )

                val summaryRandom = EpubParser.extractMetadataAndCover(archive, "Fallback")
                val summaryStream = EpubParser.extractMetadataAndCover(
                    inputStreamProvider = { FileInputStream(tempEpub) },
                    fallbackTitle = "Fallback"
                )
                assertEquals(summaryStream.title, summaryRandom.title)
                assertEquals(summaryStream.author, summaryRandom.author)
                assertEquals(summaryStream.estimatedChapters, summaryRandom.estimatedChapters)
                assertArrayEquals(summaryStream.coverImageBytes, summaryRandom.coverImageBytes)

                val bookRandom = EpubParser.parseBook(archive, "Fallback")
                val bookStream = EpubParser.parseBook(
                    inputStreamProvider = { FileInputStream(tempEpub) },
                    fallbackTitle = "Fallback"
                )
                assertEquals(60, bookRandom.chapters.size)
                assertEquals(bookStream.chapters.last().plainText, bookRandom.chapters.last().plainText)

                // 2. Benchmark O(N) sequential stream vs O(1) Central Directory lookup across positions
                val probeTargets = listOf(
                    "OEBPS/Text/ch1.xhtml",
                    "OEBPS/Text/ch15.xhtml",
                    "OEBPS/Text/ch30.xhtml",
                    "OEBPS/Text/ch45.xhtml",
                    "OEBPS/Text/ch60.xhtml"
                )

                // Warmup JIT
                repeat(12) {
                    EpubParser.readSingleZipEntry({ FileInputStream(tempEpub) }, "OEBPS/Text/ch30.xhtml")
                    archive.readEntry("OEBPS/Text/ch30.xhtml")
                }

                val iterations = 31
                val streamMs = ArrayList<Double>()
                val randomMs = ArrayList<Double>()

                for (target in probeTargets) {
                    val sSamples = DoubleArray(iterations)
                    val rSamples = DoubleArray(iterations)
                    for (i in 0 until iterations) {
                        val t0 = System.nanoTime()
                        val b1 = EpubParser.readSingleZipEntry({ FileInputStream(tempEpub) }, target)
                        val t1 = System.nanoTime()
                        assertNotNull(b1)
                        sSamples[i] = (t1 - t0) / 1_000_000.0

                        val t2 = System.nanoTime()
                        val b2 = archive.readEntry(target)
                        val t3 = System.nanoTime()
                        assertNotNull(b2)
                        rSamples[i] = (t3 - t2) / 1_000_000.0
                    }
                    sSamples.sort()
                    rSamples.sort()
                    streamMs += sSamples[iterations / 2]
                    randomMs += rSamples[iterations / 2]
                }

                // Write benchmark report so we can plot exact measured numbers
                val reportFile = File("build/reports/v2_benchmarks_epub.txt").apply {
                    parentFile?.mkdirs()
                }
                reportFile.writeText(
                    buildString {
                        appendLine("targets=ch1,ch15,ch30,ch45,ch60")
                        appendLine(
                            "stream_ms=" + streamMs.joinToString(",") {
                                String.format(Locale.US, "%.4f", it)
                            }
                        )
                        appendLine(
                            "random_ms=" + randomMs.joinToString(",") {
                                String.format(Locale.US, "%.4f", it)
                            }
                        )
                    }
                )

                // At the tail of the archive (ch60), O(1) lookup must be strictly faster than scanning 60 entries
                assertTrue(
                    "Expected O(1) lookup (${randomMs.last()} ms) < O(N) scan (${streamMs.last()} ms)",
                    randomMs.last() < streamMs.last()
                )
            }
        } finally {
            tempEpub.delete()
        }
    }

    @Test
    fun `Lazy radial chapter pagination preserves anchor page when earlier chapters merge`() {
        val radial = EpubPaginator.radialChapterOrder(totalChapters = 7, centerChapter = 3)
        assertEquals(listOf(3, 4, 2, 5, 1, 6, 0), radial)

        val tempEpub = File.createTempFile("v2_lazy_", ".epub")
        try {
            createSyntheticMultiChapterEpub(tempEpub, chapterCount = 60)
            FileChannel.open(tempEpub.toPath(), StandardOpenOption.READ).use { ch ->
                val archive = EpubRandomAccessArchive.openOrNull(ch, closeChannelOnClose = false)!!
                val book = EpubParser.parseBook(archive, "Fallback")

                fun fakePaginateChapter(chapterIndex: Int): List<EpubPage> {
                    val chapter = book.chapters[chapterIndex]
                    // Split paragraphs 3 per page deterministically to benchmark & test assembly
                    val paras = chapter.blocks.filterIsInstance<EpubBlock.ParagraphBlock>()
                    return paras.chunked(3).mapIndexed { pageInCh, group ->
                        val start = group.first().charStartInChapter
                        val end = group.last().charEndInChapter
                        EpubPage(
                            globalPageIndex = pageInCh,
                            chapterIndex = chapterIndex,
                            chapterTitle = chapter.title,
                            pageInChapter = pageInCh,
                            startCharOffset = start,
                            endCharOffset = end,
                            items = group.map {
                                EpubPageItem.TextSlice(
                                    text = it.annotatedText,
                                    kind = it.kind,
                                    chapterCharStart = it.charStartInChapter,
                                    chapterCharEnd = it.charEndInChapter
                                )
                            },
                            previewText = group.first().annotatedText.text.take(80)
                        )
                    }
                }

                // Anchor in Chapter 25 (0-based), paragraph offset ~900
                val anchorChapter = 25
                val targetPara = book.chapters[anchorChapter].blocks
                    .filterIsInstance<EpubBlock.ParagraphBlock>()[7]
                val anchorOffset = targetPara.charStartInChapter

                // Phase 1: Only anchor chapter paginated
                repeat(5) {
                    val warmMap = LinkedHashMap<Int, List<EpubPage>>()
                    warmMap[anchorChapter] = fakePaginateChapter(anchorChapter)
                    EpubPaginator.assemblePaginatedEpub(book, warmMap)
                }

                val lazySamples = DoubleArray(25)
                val eagerSamples = DoubleArray(25)
                for (run in lazySamples.indices) {
                    val t0 = System.nanoTime()
                    val m1 = LinkedHashMap<Int, List<EpubPage>>()
                    m1[anchorChapter] = fakePaginateChapter(anchorChapter)
                    EpubPaginator.assemblePaginatedEpub(book, m1)
                    val t1 = System.nanoTime()
                    lazySamples[run] = (t1 - t0) / 1_000_000.0

                    val t2 = System.nanoTime()
                    val mAll = LinkedHashMap<Int, List<EpubPage>>(book.chapters.size)
                    for (cIdx in book.chapters.indices) {
                        mAll[cIdx] = fakePaginateChapter(cIdx)
                    }
                    EpubPaginator.assemblePaginatedEpub(book, mAll)
                    val t3 = System.nanoTime()
                    eagerSamples[run] = (t3 - t2) / 1_000_000.0
                }
                lazySamples.sort()
                eagerSamples.sort()
                File("build/reports/v2_benchmarks_lazy.txt").apply { parentFile?.mkdirs() }.writeText(
                    String.format(
                        Locale.US,
                        "lazy_phase1_ms=%.4f\neager_all60_ms=%.4f\n",
                        lazySamples[lazySamples.size / 2],
                        eagerSamples[eagerSamples.size / 2]
                    )
                )

                val byChapter = LinkedHashMap<Int, List<EpubPage>>()
                byChapter[anchorChapter] = fakePaginateChapter(anchorChapter)
                val phase1 = EpubPaginator.assemblePaginatedEpub(book, byChapter)

                val phase1PageIdx = phase1.findPageForAnchor(anchorChapter, anchorOffset)
                val phase1Page = phase1.pages[phase1PageIdx]
                assertEquals(anchorChapter, phase1Page.chapterIndex)

                // Phase 2: Remaining chapters inserted radially out-of-order
                for (idx in EpubPaginator.radialChapterOrder(book.chapters.size, anchorChapter).drop(1)) {
                    byChapter[idx] = fakePaginateChapter(idx)
                }
                val phase2 = EpubPaginator.assemblePaginatedEpub(book, byChapter)

                // Global page index shifted because chapters 0..24 were prepended,
                // yet findPageForAnchor lands on the EXACT same chapter & paragraph slice!
                val phase2PageIdx = phase2.findPageForAnchor(anchorChapter, anchorOffset)
                assertTrue(phase2PageIdx > phase1PageIdx)
                val phase2Page = phase2.pages[phase2PageIdx]
                assertEquals(phase1Page.chapterIndex, phase2Page.chapterIndex)
                assertEquals(phase1Page.pageInChapter, phase2Page.pageInChapter)
                assertEquals(phase1Page.startCharOffset, phase2Page.startCharOffset)
                assertEquals(60, phase2.tocEntries.size)
            }
        } finally {
            tempEpub.delete()
        }
    }

    @Test
    fun `EpubSelectionHelper expands accurately to sentence and paragraph boundaries and Bookmark tracks selection`() {
        val p1 = "First short sentence. Easy Reader bookmarks any sentence or paragraph cleanly! Final line?"
        val p2 = "Second paragraph stays completely separate from the first."
        val fullText = "$p1\n\n$p2"

        val chapter = com.ebookreader.app.data.parser.epub.EpubChapter(
            index = 0,
            zipPath = "OEBPS/ch1.xhtml",
            title = "Chapter 1",
            blocks = listOf(
                EpubBlock.ParagraphBlock(
                    annotatedText = AnnotatedString(p1),
                    kind = EpubTextKind.PARAGRAPH,
                    charStartInChapter = 0,
                    charEndInChapter = p1.length
                ),
                EpubBlock.ParagraphBlock(
                    annotatedText = AnnotatedString(p2),
                    kind = EpubTextKind.PARAGRAPH,
                    charStartInChapter = p1.length + 2,
                    charEndInChapter = fullText.length
                )
            ),
            plainText = fullText,
            anchorOffsets = emptyMap()
        )

        val midSecondSentence = fullText.indexOf("bookmarks")
        val (sentStart, sentEnd) = EpubSelectionHelper.expandToSentence(fullText, midSecondSentence)
        assertEquals(
            "Easy Reader bookmarks any sentence or paragraph cleanly!",
            fullText.substring(sentStart, sentEnd)
        )

        val (paraStart, paraEnd) = EpubSelectionHelper.expandToParagraph(chapter, midSecondSentence)
        assertEquals(p1, fullText.substring(paraStart, paraEnd))

        val pageBookmark = Bookmark(
            bookId = "b1",
            pageIndex = 4,
            epubChapterIndex = 0,
            epubCharOffset = 0,
            epubEndCharOffset = 0,
            progress = 0.25f,
            chapterTitle = "Chapter 1",
            snippet = "Page preview"
        )
        val passageBookmark = pageBookmark.copy(
            epubCharOffset = sentStart,
            epubEndCharOffset = sentEnd,
            snippet = fullText.substring(sentStart, sentEnd)
        )

        assertFalse(pageBookmark.isTextSelection)
        assertTrue(passageBookmark.isTextSelection)
        assertEquals(4, HighlightColor.entries.size)
    }

    @Test
    fun `PdfDocumentController computeTileTransform caps zoomed bitmap RAM while preserving focal center`() {
        val viewportW = 1080
        val viewportH = 2280
        val pagePtsW = 612
        val pagePtsH = 792
        val fitW = 1080f
        val fitH = 1397.65f
        val fitLeft = 0f
        val fitTop = (viewportH - fitH) / 2f

        val zoomLevels = listOf(1.0f, 2.0f, 3.0f, 4.0f, 5.0f)
        val naiveMbList = ArrayList<Double>()
        val tileMbList = ArrayList<Double>()

        for (zoom in zoomLevels) {
            val tile = PdfDocumentController.computeTileTransform(
                pageWidthPts = pagePtsW,
                pageHeightPts = pagePtsH,
                viewportWidthPx = viewportW,
                viewportHeightPx = viewportH,
                fitLeft = fitLeft,
                fitTop = fitTop,
                fitWidth = fitW,
                fitHeight = fitH,
                zoomScale = zoom,
                panOffsetX = 0f,
                panOffsetY = 0f
            )

            // Center of PDF page (pagePtsW/2, pagePtsH/2) must map to center of tile when pan = 0
            val mappedCenterX = (pagePtsW / 2f) * tile.scaleX + tile.translateX
            val mappedCenterY = (pagePtsH / 2f) * tile.scaleY + tile.translateY
            assertEquals(tile.tileWidthPx / 2f, mappedCenterX, 1.5f)
            assertEquals(tile.tileHeightPx / 2f, mappedCenterY, 1.5f)

            // Tile dimensions stay capped at viewport resolution regardless of 1x..5x zoom!
            assertEquals(1080, tile.tileWidthPx)
            assertEquals(2280, tile.tileHeightPx)

            val naiveFullPageBytes = (fitW * zoom).toDouble() * (fitH * zoom).toDouble() * 4.0
            val tileBytes = tile.tileWidthPx.toDouble() * tile.tileHeightPx.toDouble() * 4.0
            naiveMbList += naiveFullPageBytes / (1024.0 * 1024.0)
            tileMbList += tileBytes / (1024.0 * 1024.0)
        }

        val pdfReport = File("build/reports/v2_benchmarks_pdf.txt").apply {
            parentFile?.mkdirs()
        }
        pdfReport.writeText(
            buildString {
                appendLine("zooms=1x,2x,3x,4x,5x")
                appendLine(
                    "naive_mb=" + naiveMbList.joinToString(",") {
                        String.format(Locale.US, "%.2f", it)
                    }
                )
                appendLine(
                    "tile_mb=" + tileMbList.joinToString(",") {
                        String.format(Locale.US, "%.2f", it)
                    }
                )
            }
        )

        // At 5x zoom, viewport tile uses ~9.39 MB instead of ~143.95 MB (>15x RAM reduction!)
        assertTrue(tileMbList.last() < 10.5)
        assertTrue(naiveMbList.last() > 140.0)
    }
}
