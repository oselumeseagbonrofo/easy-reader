package com.ebookreader.app

import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.ebookreader.app.data.parser.epub.EpubBlock
import com.ebookreader.app.data.parser.epub.EpubParser
import com.ebookreader.app.data.parser.epub.EpubTextKind
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubParserTest {

    private val fakeCoverBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private fun buildSampleEpubBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            fun putText(path: String, text: String) {
                zos.putNextEntry(ZipEntry(path))
                zos.write(text.toByteArray(StandardCharsets.UTF_8))
                zos.closeEntry()
            }

            fun putBytes(path: String, bytes: ByteArray) {
                zos.putNextEntry(ZipEntry(path))
                zos.write(bytes)
                zos.closeEntry()
            }

            putText("mimetype", "application/epub+zip")
            putText(
                "META-INF/container.xml",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>
                """.trimIndent()
            )
            putText(
                "OEBPS/content.opf",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="BookId">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>The Starlight Navigator</dc:title>
                    <dc:creator>Ada Lovelace</dc:creator>
                    <meta name="cover" content="cover-img"/>
                  </metadata>
                  <manifest>
                    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                    <item id="cover-img" href="Images/cover.png" media-type="image/png"/>
                    <item id="ch1" href="Text/Chapter%201.xhtml" media-type="application/xhtml+xml"/>
                    <item id="ch2" href="Text/chapter2.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine toc="ncx">
                    <itemref idref="ch1"/>
                    <itemref idref="ch2"/>
                  </spine>
                </package>
                """.trimIndent()
            )
            putText(
                "OEBPS/toc.ncx",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
                  <navMap>
                    <navPoint id="navPoint-1" playOrder="1">
                      <navLabel><text>1. Departure</text></navLabel>
                      <content src="Text/Chapter%201.xhtml"/>
                    </navPoint>
                    <navPoint id="navPoint-2" playOrder="2">
                      <navLabel><text>2. Deep Orbit</text></navLabel>
                      <content src="Text/chapter2.xhtml#sec-orbit"/>
                    </navPoint>
                  </navMap>
                </ncx>
                """.trimIndent()
            )
            putBytes("OEBPS/Images/cover.png", fakeCoverBytes)
            putText(
                "OEBPS/Text/Chapter 1.xhtml",
                """
                <?xml version="1.0" encoding="utf-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml">
                <body>
                  <h1>1. Departure</h1>
                  <p>The ship launched into <strong>bright</strong> and <em>silent</em> skies.</p>
                  <img src="../Images/cover.png" alt="Starship illustration"/>
                </body>
                </html>
                """.trimIndent()
            )
            putText(
                "OEBPS/Text/chapter2.xhtml",
                """
                <?xml version="1.0" encoding="utf-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml">
                <body>
                  <h2>2. Deep Orbit</h2>
                  <p>First introductory paragraph before the orbital maneuver.</p>
                  <p id="sec-orbit">Telemetry confirmed stable orbital velocity around the horizon.</p>
                  <blockquote>Navigation is the art of patience.</blockquote>
                </body>
                </html>
                """.trimIndent()
            )
        }
        return out.toByteArray()
    }

    @Test
    fun `extractMetadataAndCover streams metadata and cover without file copy`() {
        val epubBytes = buildSampleEpubBytes()
        val summary = EpubParser.extractMetadataAndCover(
            inputStreamProvider = { ByteArrayInputStream(epubBytes) },
            fallbackTitle = "Fallback"
        )

        assertEquals("The Starlight Navigator", summary.title)
        assertEquals("Ada Lovelace", summary.author)
        assertEquals(2, summary.estimatedChapters)
        assertNotNull(summary.coverImageBytes)
        assertArrayEquals(fakeCoverBytes, summary.coverImageBytes)
    }

    @Test
    fun `parseBook extracts chapters, inline styles, relative images, and NCX anchors`() {
        val epubBytes = buildSampleEpubBytes()
        val book = EpubParser.parseBook(
            inputStreamProvider = { ByteArrayInputStream(epubBytes) },
            fallbackTitle = "Fallback"
        )

        assertEquals("The Starlight Navigator", book.title)
        assertEquals("Ada Lovelace", book.author)
        assertEquals(2, book.chapters.size)
        assertEquals(2, book.rawToc.size)

        val ch1 = book.chapters[0]
        assertEquals("OEBPS/Text/Chapter 1.xhtml", ch1.zipPath)
        assertEquals("1. Departure", ch1.title)

        val paragraphsCh1 = ch1.blocks.filterIsInstance<EpubBlock.ParagraphBlock>()
        assertEquals(2, paragraphsCh1.size)
        assertEquals(EpubTextKind.HEADING_1, paragraphsCh1[0].kind)

        val bodyPara = paragraphsCh1[1].annotatedText
        assertEquals("The ship launched into bright and silent skies.", bodyPara.text)
        assertTrue(bodyPara.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        assertTrue(bodyPara.spanStyles.any { it.item.fontStyle == FontStyle.Italic })

        val imagesCh1 = ch1.blocks.filterIsInstance<EpubBlock.ImageBlock>()
        assertEquals(1, imagesCh1.size)
        assertEquals("OEBPS/Images/cover.png", imagesCh1[0].zipEntryPath)

        // Verify on-demand single entry extraction
        val loadedImg = EpubParser.readSingleZipEntry(
            inputStreamProvider = { ByteArrayInputStream(epubBytes) },
            targetZipPath = imagesCh1[0].zipEntryPath
        )
        assertArrayEquals(fakeCoverBytes, loadedImg)

        // Verify anchor offset registration in chapter 2
        val ch2 = book.chapters[1]
        val anchorOffset = ch2.anchorOffsets["sec-orbit"]
        assertNotNull(anchorOffset)
        assertTrue(anchorOffset!! > 0)
        assertTrue(ch2.plainText.substring(anchorOffset).startsWith("Telemetry confirmed"))
    }
}
