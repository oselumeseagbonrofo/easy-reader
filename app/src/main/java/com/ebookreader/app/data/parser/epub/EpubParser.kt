package com.ebookreader.app.data.parser.epub

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream

object EpubParser {

    private val TEXT_EXTENSIONS = setOf(
        "xml", "opf", "ncx", "xhtml", "html", "htm", "svg"
    )

    private val BLOCK_TAGS = setOf(
        "p", "div", "section", "article", "header", "footer", "main", "aside",
        "h1", "h2", "h3", "h4", "h5", "h6",
        "blockquote", "pre", "ul", "ol", "li", "dl", "dt", "dd",
        "hr", "table", "tr", "figure", "figcaption"
    )

    data class EpubMetadataSummary(
        val title: String,
        val author: String,
        val estimatedChapters: Int,
        val coverImageBytes: ByteArray?
    )

    /**
     * Lightweight streaming metadata + cover extractor used when linking a book onto the shelf.
     * Streams the external URI in-place without writing any copy of the EPUB archive.
     */
    fun extractMetadataAndCover(
        inputStreamProvider: () -> InputStream,
        fallbackTitle: String
    ): EpubMetadataSummary {
        val textEntries = mutableMapOf<String, ByteArray>()
        val allEntryPaths = mutableSetOf<String>()

        ZipInputStream(BufferedInputStream(inputStreamProvider())).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val normalized = normalizeZipPath(entry.name)
                    allEntryPaths += normalized
                    val ext = normalized.substringAfterLast('.', "").lowercase()
                    if (ext in TEXT_EXTENSIONS || normalized == "META-INF/container.xml") {
                        textEntries[normalized] = readCurrentEntryBytes(zis, maxBytes = 4 * 1024 * 1024)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        val opfPath = findOpfPath(textEntries) ?: return EpubMetadataSummary(
            title = fallbackTitle,
            author = "Unknown Author",
            estimatedChapters = 1,
            coverImageBytes = null
        )
        val opfBytes = textEntries[opfPath] ?: return EpubMetadataSummary(
            title = fallbackTitle,
            author = "Unknown Author",
            estimatedChapters = 1,
            coverImageBytes = null
        )

        val opfDoc = Jsoup.parse(String(opfBytes, StandardCharsets.UTF_8), "", Parser.xmlParser())
        val parsedOpf = parseOpfDocument(opfDoc, opfPath, fallbackTitle)
        val coverPath = parsedOpf.coverZipPath ?: allEntryPaths.firstOrNull { path ->
            val lower = path.lowercase()
            ("cover" in lower) && (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp"))
        }

        val coverBytes = coverPath?.let { readSingleZipEntry(inputStreamProvider, it) }

        return EpubMetadataSummary(
            title = parsedOpf.title,
            author = parsedOpf.author,
            estimatedChapters = parsedOpf.spinePaths.size.coerceAtLeast(1),
            coverImageBytes = coverBytes
        )
    }

    /**
     * Full streaming EPUB parser producing structured chapters, inline formatting, and TOC items.
     */
    fun parseBook(
        inputStreamProvider: () -> InputStream,
        fallbackTitle: String = "Untitled Book",
        includeCoverBytes: Boolean = false
    ): EpubBook {
        val textEntries = mutableMapOf<String, ByteArray>()
        val imageEntries = mutableSetOf<String>()

        ZipInputStream(BufferedInputStream(inputStreamProvider())).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val normalized = normalizeZipPath(entry.name)
                    val ext = normalized.substringAfterLast('.', "").lowercase()
                    if (ext in TEXT_EXTENSIONS || normalized == "META-INF/container.xml") {
                        textEntries[normalized] = readCurrentEntryBytes(zis, maxBytes = 8 * 1024 * 1024)
                    } else if (ext in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")) {
                        imageEntries += normalized
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        val opfPath = findOpfPath(textEntries)
            ?: throw IllegalArgumentException("Invalid EPUB: missing OPF package document")
        val opfBytes = textEntries[opfPath]
            ?: throw IllegalArgumentException("Invalid EPUB: unreadable OPF at $opfPath")

        val opfDoc = Jsoup.parse(String(opfBytes, StandardCharsets.UTF_8), "", Parser.xmlParser())
        val parsedOpf = parseOpfDocument(opfDoc, opfPath, fallbackTitle)

        // Parse TOC (EPUB 3 nav first, then EPUB 2 NCX)
        val rawToc = buildList {
            if (parsedOpf.navZipPath != null) {
                textEntries[parsedOpf.navZipPath]?.let { navBytes ->
                    addAll(parseEpub3Nav(String(navBytes, StandardCharsets.UTF_8), parsedOpf.navZipPath))
                }
            }
            if (isEmpty() && parsedOpf.ncxZipPath != null) {
                textEntries[parsedOpf.ncxZipPath]?.let { ncxBytes ->
                    addAll(parseEpub2Ncx(String(ncxBytes, StandardCharsets.UTF_8), parsedOpf.ncxZipPath))
                }
            }
        }

        val tocTitleByPath = rawToc.associate { it.zipPath to it.title }

        val chapters = mutableListOf<EpubChapter>()
        parsedOpf.spinePaths.forEach { spinePath ->
            val rawBytes = textEntries[spinePath]
                ?: textEntries.entries.firstOrNull { it.key.equals(spinePath, ignoreCase = true) }?.value
                ?: return@forEach

            val html = String(rawBytes, StandardCharsets.UTF_8)
            val chapterIndex = chapters.size
            val chapter = parseChapterHtml(
                index = chapterIndex,
                zipPath = spinePath,
                html = html,
                fallbackTitle = tocTitleByPath[spinePath] ?: "Section ${chapterIndex + 1}",
                availableImages = imageEntries
            )
            if (chapter.blocks.isNotEmpty()) {
                chapters += chapter
            }
        }

        val finalToc = rawToc.ifEmpty {
            chapters.map { ch ->
                EpubRawTocItem(
                    title = ch.title,
                    zipPath = ch.zipPath,
                    fragmentId = null,
                    depth = 0
                )
            }
        }

        val coverBytes = if (includeCoverBytes && parsedOpf.coverZipPath != null) {
            readSingleZipEntry(inputStreamProvider, parsedOpf.coverZipPath)
        } else {
            null
        }

        return EpubBook(
            title = parsedOpf.title,
            author = parsedOpf.author,
            chapters = chapters,
            rawToc = finalToc,
            coverImageBytes = coverBytes
        )
    }

    /**
     * Streams a single entry (e.g. an illustration image) on-demand from the external EPUB URI.
     */
    fun readSingleZipEntry(
        inputStreamProvider: () -> InputStream,
        targetZipPath: String
    ): ByteArray? {
        val normalizedTarget = normalizeZipPath(targetZipPath)
        ZipInputStream(BufferedInputStream(inputStreamProvider())).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val name = normalizeZipPath(entry.name)
                    if (name == normalizedTarget || name.equals(normalizedTarget, ignoreCase = true)) {
                        return readCurrentEntryBytes(zis, maxBytes = 12 * 1024 * 1024)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return null
    }

    private data class ManifestItem(
        val id: String,
        val zipPath: String,
        val mediaType: String,
        val properties: Set<String>
    )

    private data class ParsedOpf(
        val title: String,
        val author: String,
        val spinePaths: List<String>,
        val navZipPath: String?,
        val ncxZipPath: String?,
        val coverZipPath: String?
    )

    private fun findOpfPath(textEntries: Map<String, ByteArray>): String? {
        val containerBytes = textEntries["META-INF/container.xml"]
            ?: textEntries.entries.firstOrNull { it.key.equals("META-INF/container.xml", ignoreCase = true) }?.value

        if (containerBytes != null) {
            val doc = Jsoup.parse(String(containerBytes, StandardCharsets.UTF_8), "", Parser.xmlParser())
            val fullPath = doc.selectFirst("rootfile")?.attr("full-path")?.trim()
            if (!fullPath.isNullOrEmpty()) {
                val normalized = normalizeZipPath(fullPath)
                if (normalized in textEntries) return normalized
                textEntries.keys.firstOrNull { it.equals(normalized, ignoreCase = true) }?.let { return it }
            }
        }
        return textEntries.keys.firstOrNull { it.endsWith(".opf", ignoreCase = true) }
    }

    private fun parseOpfDocument(opfDoc: Document, opfPath: String, fallbackTitle: String): ParsedOpf {
        val title = opfDoc.select("metadata > dc|title, metadata > title, dc|title, title")
            .firstOrNull { it.text().isNotBlank() }
            ?.text()
            ?.trim()
            .takeUnless { it.isNullOrBlank() }
            ?: fallbackTitle

        val author = opfDoc.select("metadata > dc|creator, metadata > creator, dc|creator, creator")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .joinToString(", ")
            .ifBlank { "Unknown Author" }

        val manifestById = LinkedHashMap<String, ManifestItem>()
        opfDoc.select("manifest > item, item").forEach { itemEl ->
            val id = itemEl.attr("id").trim()
            val href = itemEl.attr("href").trim()
            if (id.isNotEmpty() && href.isNotEmpty()) {
                val mediaType = itemEl.attr("media-type").trim().lowercase()
                val props = itemEl.attr("properties")
                    .split(Regex("\\s+"))
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toSet()
                manifestById[id] = ManifestItem(
                    id = id,
                    zipPath = resolveRelativeZipPath(opfPath, href),
                    mediaType = mediaType,
                    properties = props
                )
            }
        }

        val spinePaths = mutableListOf<String>()
        opfDoc.select("spine > itemref, itemref").forEach { refEl ->
            if (refEl.attr("linear").equals("no", ignoreCase = true)) return@forEach
            val idref = refEl.attr("idref").trim()
            val item = manifestById[idref] ?: return@forEach
            if (item.zipPath !in spinePaths) {
                spinePaths += item.zipPath
            }
        }

        // Fallback if spine was empty
        if (spinePaths.isEmpty()) {
            manifestById.values
                .filter { it.mediaType.contains("html") || it.zipPath.endsWith(".xhtml") || it.zipPath.endsWith(".html") }
                .forEach { spinePaths += it.zipPath }
        }

        val navItem = manifestById.values.firstOrNull { "nav" in it.properties }
        val spineTocId = opfDoc.selectFirst("spine")?.attr("toc")?.trim()
        val ncxItem = (spineTocId?.let { manifestById[it] })
            ?: manifestById.values.firstOrNull {
                it.mediaType == "application/x-dtbncx+xml" || it.zipPath.endsWith(".ncx", ignoreCase = true)
            }

        val coverMetaId = opfDoc.select("metadata > meta[name=cover], meta[name=cover]")
            .firstOrNull()
            ?.attr("content")
            ?.trim()

        val coverItem = manifestById.values.firstOrNull { "cover-image" in it.properties }
            ?: coverMetaId?.let { manifestById[it] }
                ?.takeIf { it.mediaType.startsWith("image/") }
            ?: manifestById.values.firstOrNull {
                it.mediaType.startsWith("image/") &&
                    (it.id.contains("cover", ignoreCase = true) || it.zipPath.contains("cover", ignoreCase = true))
            }

        return ParsedOpf(
            title = title,
            author = author,
            spinePaths = spinePaths,
            navZipPath = navItem?.zipPath,
            ncxZipPath = ncxItem?.zipPath,
            coverZipPath = coverItem?.zipPath
        )
    }

    private fun parseEpub3Nav(navHtml: String, navZipPath: String): List<EpubRawTocItem> {
        val doc = Jsoup.parse(navHtml)
        val tocNav = doc.select("nav").firstOrNull { nav ->
            nav.attributes().any { attr ->
                attr.key.endsWith("type", ignoreCase = true) && attr.value.equals("toc", ignoreCase = true)
            }
        } ?: doc.selectFirst("nav") ?: return emptyList()

        val result = mutableListOf<EpubRawTocItem>()
        val rootOl = tocNav.selectFirst("ol") ?: return emptyList()

        fun walkList(ol: Element, depth: Int) {
            for (li in ol.children().filter { it.tagName().equals("li", ignoreCase = true) }) {
                val link = li.children().firstOrNull { it.tagName().equals("a", ignoreCase = true) }
                    ?: li.selectFirst("a")
                if (link != null) {
                    val label = link.text().trim()
                    val rawHref = link.attr("href").trim()
                    if (label.isNotEmpty() && rawHref.isNotEmpty()) {
                        val (resolvedPath, fragment) = splitAndResolveHref(navZipPath, rawHref)
                        result += EpubRawTocItem(
                            title = label,
                            zipPath = resolvedPath,
                            fragmentId = fragment,
                            depth = depth
                        )
                    }
                }
                li.children()
                    .filter { it.tagName().equals("ol", ignoreCase = true) }
                    .forEach { childOl -> walkList(childOl, depth + 1) }
            }
        }

        walkList(rootOl, 0)
        return result
    }

    private fun parseEpub2Ncx(ncxXml: String, ncxZipPath: String): List<EpubRawTocItem> {
        val doc = Jsoup.parse(ncxXml, "", Parser.xmlParser())
        val navMap = doc.selectFirst("navMap") ?: return emptyList()
        val result = mutableListOf<EpubRawTocItem>()

        fun walkNavPoints(parent: Element, depth: Int) {
            for (np in parent.children().filter { it.tagName().equals("navPoint", ignoreCase = true) }) {
                val label = np.selectFirst("navLabel > text, text")?.text()?.trim().orEmpty()
                val src = np.selectFirst("content")?.attr("src")?.trim().orEmpty()
                if (label.isNotEmpty() && src.isNotEmpty()) {
                    val (resolvedPath, fragment) = splitAndResolveHref(ncxZipPath, src)
                    result += EpubRawTocItem(
                        title = label,
                        zipPath = resolvedPath,
                        fragmentId = fragment,
                        depth = depth
                    )
                }
                walkNavPoints(np, depth + 1)
            }
        }

        walkNavPoints(navMap, 0)
        return result
    }

    private fun parseChapterHtml(
        index: Int,
        zipPath: String,
        html: String,
        fallbackTitle: String,
        availableImages: Set<String>
    ): EpubChapter {
        val doc = Jsoup.parse(html)
        doc.select("script, style, head > meta, head > link, nav[epub\\:type=landmarks]").remove()

        val body = doc.body() ?: doc
        val blocks = mutableListOf<EpubBlock>()
        val plainBuilder = StringBuilder()
        val anchorOffsets = LinkedHashMap<String, Int>()

        var pendingBuilder = AnnotatedString.Builder()
        var pendingKind = EpubTextKind.PARAGRAPH
        val pendingAnchors = LinkedHashSet<String>()

        fun registerAnchor(id: String?) {
            val trimmed = id?.trim().takeUnless { it.isNullOrEmpty() } ?: return
            anchorOffsets.putIfAbsent(trimmed, plainBuilder.length)
            pendingAnchors += trimmed
        }

        fun flushPendingParagraph() {
            val raw = pendingBuilder.toAnnotatedString()
            val trimmed = trimAnnotatedString(raw)
            if (trimmed.text.isNotBlank()) {
                if (plainBuilder.isNotEmpty()) {
                    plainBuilder.append('\n')
                }
                val startOffset = plainBuilder.length
                plainBuilder.append(trimmed.text)
                val endOffset = plainBuilder.length
                pendingAnchors.forEach { anchor -> anchorOffsets[anchor] = startOffset }

                blocks += EpubBlock.ParagraphBlock(
                    annotatedText = trimmed,
                    kind = pendingKind,
                    charStartInChapter = startOffset,
                    charEndInChapter = endOffset,
                    anchorIds = pendingAnchors.toSet()
                )
            }
            pendingBuilder = AnnotatedString.Builder()
            pendingKind = EpubTextKind.PARAGRAPH
            pendingAnchors.clear()
        }

        val walker = object {
            fun appendInlineNode(node: Node, preserveWhitespace: Boolean = false) {
                when (node) {
                    is TextNode -> {
                        val chunk = if (preserveWhitespace) node.wholeText else collapseWhitespace(node.wholeText)
                        if (chunk.isNotEmpty()) {
                            pendingBuilder.append(chunk)
                        }
                    }
                    is Element -> {
                        registerAnchor(node.id())
                        registerAnchor(node.attr("name"))
                        val tag = node.tagName().lowercase()
                        when (tag) {
                            "br" -> pendingBuilder.append("\n")
                            "img", "image" -> {
                                val rawSrc = node.attr("src").ifBlank {
                                    node.attr("xlink:href").ifBlank { node.attr("href") }
                                }.trim()
                                if (rawSrc.isNotEmpty()) {
                                    flushPendingParagraph()
                                    val resolvedImg = resolveRelativeZipPath(zipPath, rawSrc)
                                    val matchedImg = if (resolvedImg in availableImages || availableImages.isEmpty()) {
                                        resolvedImg
                                    } else {
                                        availableImages.firstOrNull { it.equals(resolvedImg, ignoreCase = true) } ?: resolvedImg
                                    }
                                    blocks += EpubBlock.ImageBlock(
                                        zipEntryPath = matchedImg,
                                        altText = node.attr("alt").takeIf { it.isNotBlank() },
                                        charOffsetInChapter = plainBuilder.length,
                                        anchorIds = pendingAnchors.toSet()
                                    )
                                    pendingAnchors.clear()
                                }
                            }
                            in BLOCK_TAGS -> {
                                walkBlockElement(node)
                            }
                            else -> {
                                val style = inlineSpanStyleForTag(tag)
                                if (style != null) {
                                    val idx = pendingBuilder.pushStyle(style)
                                    node.childNodes().forEach { appendInlineNode(it, preserveWhitespace || tag == "code") }
                                    pendingBuilder.pop(idx)
                                } else {
                                    node.childNodes().forEach { appendInlineNode(it, preserveWhitespace) }
                                }
                            }
                        }
                    }
                }
            }

            fun walkBlockElement(element: Element) {
                val tag = element.tagName().lowercase()
                flushPendingParagraph()
                registerAnchor(element.id())

                if (tag == "hr") {
                    blocks += EpubBlock.DividerBlock(charOffsetInChapter = plainBuilder.length)
                    return
                }

                val hasNestedBlock = element.children().any { child ->
                    val childTag = child.tagName().lowercase()
                    childTag in BLOCK_TAGS && childTag !in setOf("h1", "h2", "h3", "h4", "h5", "h6")
                }

                if (tag in setOf("div", "section", "article", "main", "header", "footer", "aside", "ul", "ol", "dl", "table", "tr", "figure") && hasNestedBlock) {
                    element.childNodes().forEach { child ->
                        if (child is Element && child.tagName().lowercase() in BLOCK_TAGS) {
                            walkBlockElement(child)
                        } else {
                            appendInlineNode(child, preserveWhitespace = false)
                        }
                    }
                    flushPendingParagraph()
                    return
                }

                pendingKind = when (tag) {
                    "h1" -> EpubTextKind.HEADING_1
                    "h2" -> EpubTextKind.HEADING_2
                    "h3", "h4", "h5", "h6" -> EpubTextKind.HEADING_3
                    "blockquote" -> EpubTextKind.QUOTE
                    "li" -> EpubTextKind.LIST_ITEM
                    "pre" -> EpubTextKind.CODE
                    else -> EpubTextKind.PARAGRAPH
                }

                if (pendingKind == EpubTextKind.LIST_ITEM) {
                    pendingBuilder.append("• ")
                }

                element.childNodes().forEach { child ->
                    appendInlineNode(child, preserveWhitespace = (tag == "pre"))
                }
                flushPendingParagraph()
            }
        }

        body.childNodes().forEach { node ->
            if (node is Element && node.tagName().lowercase() in BLOCK_TAGS) {
                walker.walkBlockElement(node)
            } else {
                walker.appendInlineNode(node, preserveWhitespace = false)
            }
        }
        flushPendingParagraph()

        val inferredHeading = blocks.filterIsInstance<EpubBlock.ParagraphBlock>()
            .firstOrNull { it.kind == EpubTextKind.HEADING_1 || it.kind == EpubTextKind.HEADING_2 }
            ?.annotatedText
            ?.text
            ?.lines()
            ?.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() && it.length <= 100 }

        val docTitle = doc.title().trim().takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }

        return EpubChapter(
            index = index,
            zipPath = zipPath,
            title = inferredHeading ?: docTitle ?: fallbackTitle,
            blocks = blocks,
            plainText = plainBuilder.toString(),
            anchorOffsets = anchorOffsets
        )
    }

    private fun inlineSpanStyleForTag(tag: String): SpanStyle? = when (tag) {
        "b", "strong" -> SpanStyle(fontWeight = FontWeight.Bold)
        "i", "em", "cite", "dfn" -> SpanStyle(fontStyle = FontStyle.Italic)
        "u", "ins" -> SpanStyle(textDecoration = TextDecoration.Underline)
        "s", "strike", "del" -> SpanStyle(textDecoration = TextDecoration.LineThrough)
        "code", "kbd", "samp", "tt" -> SpanStyle(fontFamily = FontFamily.Monospace)
        "sup" -> SpanStyle(baselineShift = BaselineShift.Superscript)
        "sub" -> SpanStyle(baselineShift = BaselineShift.Subscript)
        else -> null
    }

    private fun collapseWhitespace(text: String): String {
        if (text.isEmpty()) return ""
        return text.replace(Regex("[\\r\\n\\t ]+"), " ")
    }

    private fun trimAnnotatedString(source: AnnotatedString): AnnotatedString {
        val raw = source.text
        var start = 0
        var end = raw.length
        while (start < end && raw[start].isWhitespace()) start++
        while (end > start && raw[end - 1].isWhitespace()) end--
        if (start >= end) return AnnotatedString("")
        return source.subSequence(start, end)
    }

    private fun splitAndResolveHref(baseZipPath: String, rawHref: String): Pair<String, String?> {
        val hashIndex = rawHref.indexOf('#')
        val pathPart = if (hashIndex >= 0) rawHref.substring(0, hashIndex) else rawHref
        val fragment = if (hashIndex >= 0) rawHref.substring(hashIndex + 1).takeIf { it.isNotBlank() } else null
        val resolved = if (pathPart.isBlank()) {
            normalizeZipPath(baseZipPath)
        } else {
            resolveRelativeZipPath(baseZipPath, pathPart)
        }
        return resolved to fragment
    }

    internal fun resolveRelativeZipPath(baseFileZipPath: String, relativeHref: String): String {
        val withoutFragment = relativeHref.substringBefore('#').substringBefore('?').trim()
        val decoded = runCatching {
            URLDecoder.decode(withoutFragment, StandardCharsets.UTF_8.name())
        }.getOrDefault(withoutFragment)

        if (decoded.startsWith("/")) {
            return normalizeZipPath(decoded)
        }
        val baseDir = normalizeZipPath(baseFileZipPath).substringBeforeLast('/', "")
        val combined = if (baseDir.isEmpty()) decoded else "$baseDir/$decoded"
        return normalizeZipPath(combined)
    }

    internal fun normalizeZipPath(rawPath: String): String {
        val slashFixed = rawPath.replace('\\', '/').trimStart('/')
        val segments = ArrayDeque<String>()
        for (part in slashFixed.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (segments.isNotEmpty()) segments.removeLast()
                else -> segments.addLast(part)
            }
        }
        return segments.joinToString("/")
    }

    private fun readCurrentEntryBytes(zis: ZipInputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = zis.read(buffer)
            if (read <= 0) break
            total += read
            if (total > maxBytes) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}
