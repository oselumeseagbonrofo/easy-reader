package com.ebookreader.app.data.parser.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.LruCache
import com.ebookreader.app.domain.model.PdfFitMode
import com.ebookreader.app.domain.model.TocEntry
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.FileNotFoundException
import java.io.InputStream
import kotlin.math.roundToInt

data class PdfTileTransform(
    val tileWidthPx: Int,
    val tileHeightPx: Int,
    val scaleX: Float,
    val scaleY: Float,
    val translateX: Float,
    val translateY: Float
)

data class PdfContentBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    companion object {
        val FULL = PdfContentBounds(0f, 0f, 1f, 1f)
    }
}

data class PdfDisplayRect(
    val pageLeft: Float,
    val pageTop: Float,
    val pageWidth: Float,
    val pageHeight: Float,
    val contentDisplayWidth: Float,
    val contentDisplayHeight: Float
) {
    fun screenCoverageRatio(viewportWidth: Float, viewportHeight: Float): Float {
        val area = (viewportWidth * viewportHeight).coerceAtLeast(1f)
        val visW = contentDisplayWidth.coerceIn(0f, viewportWidth)
        val visH = contentDisplayHeight.coerceIn(0f, viewportHeight)
        return ((visW * visH) / area).coerceIn(0f, 1f)
    }
}

class PdfDocumentController private constructor(
    private val context: Context,
    val uri: Uri,
    private val parcelFileDescriptor: ParcelFileDescriptor,
    private val pdfRenderer: PdfRenderer
) : Closeable {

    private val renderMutex = Mutex()
    private val pdfBoxMutex = Mutex()

    @Volatile
    private var isClosed = false
    private var pdfBoxStream: InputStream? = null
    private var cachedPdDocument: PDDocument? = null

    val pageCount: Int
        get() = if (isClosed) 0 else pdfRenderer.pageCount

    private val bitmapCache: LruCache<String, Bitmap> by lazy {
        val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        val cacheSizeKb = (maxMemoryKb / 8).coerceAtLeast(8 * 1024)
        object : LruCache<String, Bitmap>(cacheSizeKb) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
        }
    }

    private val boundsCache = LruCache<Int, PdfContentBounds>(256)
    private val snippetCache = LruCache<Int, String>(128)

    private fun cacheKeyFor(pageIndex: Int, targetWidthPx: Int, highResMultiplier: Float): String {
        val requestedWidth = (targetWidthPx.coerceAtLeast(360) * highResMultiplier)
            .toInt()
            .coerceIn(480, 1600)
        return "$pageIndex@$requestedWidth"
    }

    fun getCachedPage(
        pageIndex: Int,
        targetWidthPx: Int,
        highResMultiplier: Float = 1.25f
    ): Bitmap? {
        val key = cacheKeyFor(pageIndex, targetWidthPx, highResMultiplier)
        return synchronized(bitmapCache) {
            bitmapCache.get(key)?.takeIf { !it.isRecycled }
        }
    }

    fun getContentBounds(pageIndex: Int): PdfContentBounds =
        synchronized(boundsCache) { boundsCache.get(pageIndex) } ?: PdfContentBounds.FULL

    suspend fun <T> withPage(pageIndex: Int, block: (PdfRenderer.Page) -> T): T {
        return withContext(Dispatchers.IO) {
            renderMutex.withLock {
                check(!isClosed) { "PdfDocumentController is closed" }
                val safeIndex = pageIndex.coerceIn(0, (pdfRenderer.pageCount - 1).coerceAtLeast(0))
                pdfRenderer.openPage(safeIndex).use { page ->
                    block(page)
                }
            }
        }
    }

    /**
     * Serializes PdfBox access across a single temp-file-backed [PDDocument] so rapid
     * bookmarking, outline loading, or search never allocates parallel heap copies of the PDF.
     */
    suspend fun <T> withPdfBoxDocument(block: suspend (PDDocument) -> T): T? =
        withContext(Dispatchers.IO) {
            pdfBoxMutex.withLock {
                if (isClosed) return@withLock null
                val doc = cachedPdDocument ?: runCatching {
                    val stream = context.contentResolver.openInputStream(uri) ?: return@runCatching null
                    PDDocument.load(stream, MemoryUsageSetting.setupTempFileOnly()).also { loaded ->
                        pdfBoxStream = stream
                        cachedPdDocument = loaded
                    }
                }.getOrNull() ?: return@withLock null
                runCatching { block(doc) }.getOrNull()
            }
        }

    /**
     * Renders the base bitmap for fast 1x horizontal swiping and records normalized ink bounds.
     */
    suspend fun renderPage(
        pageIndex: Int,
        targetWidthPx: Int,
        highResMultiplier: Float = 1.25f
    ): Bitmap? {
        if (isClosed || pageCount <= 0 || pageIndex !in 0 until pageCount) return null
        val requestedWidth = (targetWidthPx.coerceAtLeast(360) * highResMultiplier)
            .toInt()
            .coerceIn(480, 1600)
        val cacheKey = "$pageIndex@$requestedWidth"

        synchronized(bitmapCache) {
            bitmapCache.get(cacheKey)?.takeIf { !it.isRecycled }?.let { return it }
        }

        return runCatching {
            withPage(pageIndex) { page ->
                val aspectRatio = if (page.width > 0) {
                    page.height.toFloat() / page.width.toFloat()
                } else {
                    1.414f
                }
                val bitmapWidth = requestedWidth
                val bitmapHeight = (bitmapWidth * aspectRatio).toInt().coerceIn(320, 3200)

                val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
                Canvas(bitmap).drawColor(Color.WHITE)

                page.render(
                    bitmap,
                    null,
                    null,
                    PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                )

                synchronized(boundsCache) {
                    if (boundsCache.get(pageIndex) == null) {
                        boundsCache.put(pageIndex, detectContentBounds(bitmap))
                    }
                }
                synchronized(bitmapCache) {
                    bitmapCache.put(cacheKey, bitmap)
                }
                bitmap
            }
        }.getOrNull()
    }

    /**
     * Renders ONLY the currently visible zoomed viewport region into a screen-sized tile bitmap.
     */
    suspend fun renderViewportTile(
        pageIndex: Int,
        viewportWidthPx: Int,
        viewportHeightPx: Int,
        fitLeft: Float,
        fitTop: Float,
        fitWidth: Float,
        fitHeight: Float,
        zoomScale: Float,
        panOffsetX: Float,
        panOffsetY: Float
    ): Bitmap? {
        if (isClosed || pageCount <= 0 || pageIndex !in 0 until pageCount) return null
        if (viewportWidthPx <= 0 || viewportHeightPx <= 0 || fitWidth <= 0f || fitHeight <= 0f) return null

        return runCatching {
            withPage(pageIndex) { page ->
                val transform = computeTileTransform(
                    pageWidthPts = page.width.coerceAtLeast(1),
                    pageHeightPts = page.height.coerceAtLeast(1),
                    viewportWidthPx = viewportWidthPx,
                    viewportHeightPx = viewportHeightPx,
                    fitLeft = fitLeft,
                    fitTop = fitTop,
                    fitWidth = fitWidth,
                    fitHeight = fitHeight,
                    zoomScale = zoomScale,
                    panOffsetX = panOffsetX,
                    panOffsetY = panOffsetY
                )

                val tileBitmap = Bitmap.createBitmap(
                    transform.tileWidthPx,
                    transform.tileHeightPx,
                    Bitmap.Config.ARGB_8888
                )
                Canvas(tileBitmap).drawColor(Color.WHITE)

                val matrix = Matrix().apply {
                    setScale(transform.scaleX, transform.scaleY)
                    postTranslate(transform.translateX, transform.translateY)
                }

                page.render(
                    tileBitmap,
                    null,
                    matrix,
                    PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                )
                tileBitmap
            }
        }.getOrNull()
    }

    suspend fun extractOutline(): List<TocEntry> =
        withPdfBoxDocument { doc ->
            val root = doc.documentCatalog?.documentOutline ?: return@withPdfBoxDocument emptyList()
            val entries = ArrayList<TocEntry>()

            fun walkOutline(node: PDOutlineNode, depth: Int) {
                var current: PDOutlineItem? = node.firstChild
                while (current != null) {
                    val title = current.title?.trim().orEmpty()
                    val destPage = runCatching { current.findDestinationPage(doc) }.getOrNull()
                    val pageIdx = if (destPage != null) {
                        doc.pages.indexOf(destPage).coerceAtLeast(0)
                    } else {
                        0
                    }
                    if (title.isNotEmpty()) {
                        entries += TocEntry(
                            title = title,
                            pageIndex = pageIdx,
                            chapterIndex = pageIdx,
                            charOffset = 0,
                            depth = depth
                        )
                    }
                    walkOutline(current, depth + 1)
                    current = current.nextSibling
                }
            }

            walkOutline(root, 0)
            entries
        } ?: emptyList()

    suspend fun extractPageSnippet(pageIndex: Int): String = withContext(Dispatchers.IO) {
        synchronized(snippetCache) { snippetCache.get(pageIndex) }?.let { return@withContext it }

        val nativeText = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            runCatching {
                withPage(pageIndex) { page ->
                    page.textContents.joinToString(" ") { it.text }
                }.replace(Regex("\\s+"), " ").trim().take(140)
            }.getOrNull()
        } else {
            null
        }

        val resolved = if (!nativeText.isNullOrBlank()) {
            nativeText
        } else {
            withPdfBoxDocument { doc ->
                val oneBased = (pageIndex + 1).coerceIn(1, doc.numberOfPages.coerceAtLeast(1))
                val stripper = PDFTextStripper().apply {
                    startPage = oneBased
                    endPage = oneBased
                }
                stripper.getText(doc)
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .take(140)
            }.orEmpty()
        }

        if (resolved.isNotBlank()) {
            synchronized(snippetCache) { snippetCache.put(pageIndex, resolved) }
        }
        resolved
    }

    override fun close() {
        isClosed = true
        synchronized(bitmapCache) { bitmapCache.evictAll() }
        synchronized(boundsCache) { boundsCache.evictAll() }
        synchronized(snippetCache) { snippetCache.evictAll() }
        runCatching { cachedPdDocument?.close() }
        cachedPdDocument = null
        runCatching { pdfBoxStream?.close() }
        pdfBoxStream = null
        runCatching { pdfRenderer.close() }
        runCatching { parcelFileDescriptor.close() }
    }

    companion object {
        private const val MAX_TILE_DIMENSION_PX = 1440

        fun detectContentBounds(bitmap: Bitmap): PdfContentBounds {
            val w = bitmap.width
            val h = bitmap.height
            if (w < 16 || h < 16) return PdfContentBounds.FULL

            val step = 4
            val rowPixels = IntArray(w)
            var minX = w
            var minY = h
            var maxX = -1
            var maxY = -1

            var y = 0
            while (y < h) {
                bitmap.getPixels(rowPixels, 0, w, 0, y, w, 1)
                var x = 0
                while (x < w) {
                    val px = rowPixels[x]
                    val r = (px shr 16) and 0xFF
                    val g = (px shr 8) and 0xFF
                    val b = px and 0xFF
                    if (r < 245 || g < 245 || b < 245) {
                        if (x < minX) minX = x
                        if (x > maxX) maxX = x
                        if (y < minY) minY = y
                        if (y > maxY) maxY = y
                    }
                    x += step
                }
                y += step
            }

            if (maxX < minX || maxY < minY) return PdfContentBounds.FULL

            val pad = 0.015f
            return PdfContentBounds(
                left = (minX.toFloat() / w - pad).coerceIn(0f, 0.42f),
                top = (minY.toFloat() / h - pad).coerceIn(0f, 0.42f),
                right = (maxX.toFloat() / w + pad).coerceIn(0.58f, 1f),
                bottom = (maxY.toFloat() / h + pad).coerceIn(0.58f, 1f)
            )
        }

        fun computePdfDisplayRect(
            viewportWidth: Float,
            viewportHeight: Float,
            bitmapWidth: Float,
            bitmapHeight: Float,
            contentBounds: PdfContentBounds,
            fitMode: PdfFitMode,
            trimMargins: Boolean
        ): PdfDisplayRect {
            val viewW = viewportWidth.coerceAtLeast(1f)
            val viewH = viewportHeight.coerceAtLeast(1f)
            val bmpW = bitmapWidth.coerceAtLeast(1f)
            val bmpH = bitmapHeight.coerceAtLeast(1f)

            val bounds = if (trimMargins && fitMode != PdfFitMode.FIT_PAGE) {
                contentBounds
            } else {
                PdfContentBounds.FULL
            }
            val cW = (bounds.right - bounds.left).coerceIn(0.2f, 1f)
            val cH = (bounds.bottom - bounds.top).coerceIn(0.2f, 1f)
            val inset = minOf(viewW, viewH) * 0.008f

            return when (fitMode) {
                PdfFitMode.FIT_SCREEN -> {
                    val targetW = (viewW - inset * 2f).coerceAtLeast(1f)
                    val targetH = (viewH - inset * 2f).coerceAtLeast(1f)
                    val pageW = targetW / cW
                    val pageH = targetH / cH
                    PdfDisplayRect(
                        pageLeft = inset - bounds.left * pageW,
                        pageTop = inset - bounds.top * pageH,
                        pageWidth = pageW,
                        pageHeight = pageH,
                        contentDisplayWidth = targetW,
                        contentDisplayHeight = targetH
                    )
                }

                PdfFitMode.FIT_WIDTH -> {
                    val targetW = (viewW - inset * 2f).coerceAtLeast(1f)
                    val scale = targetW / (bmpW * cW).coerceAtLeast(1f)
                    val pageW = bmpW * scale
                    val pageH = bmpH * scale
                    val targetH = pageH * cH
                    val contentTop = if (targetH <= viewH) {
                        (viewH - targetH) / 2f
                    } else {
                        inset
                    }
                    PdfDisplayRect(
                        pageLeft = inset - bounds.left * pageW,
                        pageTop = contentTop - bounds.top * pageH,
                        pageWidth = pageW,
                        pageHeight = pageH,
                        contentDisplayWidth = targetW,
                        contentDisplayHeight = targetH
                    )
                }

                PdfFitMode.FIT_PAGE -> {
                    val scale = minOf(viewW / bmpW, viewH / bmpH)
                    val pageW = bmpW * scale
                    val pageH = bmpH * scale
                    PdfDisplayRect(
                        pageLeft = (viewW - pageW) / 2f,
                        pageTop = (viewH - pageH) / 2f,
                        pageWidth = pageW,
                        pageHeight = pageH,
                        contentDisplayWidth = pageW * (contentBounds.right - contentBounds.left).coerceIn(0.2f, 1f),
                        contentDisplayHeight = pageH * (contentBounds.bottom - contentBounds.top).coerceIn(0.2f, 1f)
                    )
                }
            }
        }

        fun computeTileTransform(
            pageWidthPts: Int,
            pageHeightPts: Int,
            viewportWidthPx: Int,
            viewportHeightPx: Int,
            fitLeft: Float,
            fitTop: Float,
            fitWidth: Float,
            fitHeight: Float,
            zoomScale: Float,
            panOffsetX: Float,
            panOffsetY: Float
        ): PdfTileTransform {
            val viewW = viewportWidthPx.coerceAtLeast(1).toFloat()
            val viewH = viewportHeightPx.coerceAtLeast(1).toFloat()
            val downscale = minOf(
                1f,
                MAX_TILE_DIMENSION_PX.toFloat() / viewW,
                (MAX_TILE_DIMENSION_PX * 2f) / viewH
            )
            val tileW = (viewW * downscale).roundToInt().coerceAtLeast(64)
            val tileH = (viewH * downscale).roundToInt().coerceAtLeast(64)

            val pivotX = viewW / 2f
            val pivotY = viewH / 2f

            val safePageW = pageWidthPts.coerceAtLeast(1).toFloat()
            val safePageH = pageHeightPts.coerceAtLeast(1).toFloat()

            val scaleX = (fitWidth * zoomScale / safePageW) * downscale
            val scaleY = (fitHeight * zoomScale / safePageH) * downscale
            val translateX = (pivotX + (fitLeft - pivotX) * zoomScale + panOffsetX) * downscale
            val translateY = (pivotY + (fitTop - pivotY) * zoomScale + panOffsetY) * downscale

            return PdfTileTransform(
                tileWidthPx = tileW,
                tileHeightPx = tileH,
                scaleX = scaleX,
                scaleY = scaleY,
                translateX = translateX,
                translateY = translateY
            )
        }

        fun open(context: Context, uri: Uri): PdfDocumentController {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw FileNotFoundException("Unable to open PDF URI: $uri")
            return try {
                val renderer = PdfRenderer(pfd)
                PdfDocumentController(context.applicationContext, uri, pfd, renderer)
            } catch (t: Throwable) {
                runCatching { pfd.close() }
                throw t
            }
        }
    }
}
