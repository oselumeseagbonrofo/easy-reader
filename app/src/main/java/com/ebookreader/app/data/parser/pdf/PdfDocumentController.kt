package com.ebookreader.app.data.parser.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import com.ebookreader.app.domain.model.TocEntry
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
import kotlin.math.roundToInt

data class PdfTileTransform(
    val tileWidthPx: Int,
    val tileHeightPx: Int,
    val scaleX: Float,
    val scaleY: Float,
    val translateX: Float,
    val translateY: Float
)

class PdfDocumentController private constructor(
    private val context: Context,
    val uri: Uri,
    private val parcelFileDescriptor: ParcelFileDescriptor,
    private val pdfRenderer: PdfRenderer
) : Closeable {

    private val renderMutex = Mutex()

    val pageCount: Int
        get() = pdfRenderer.pageCount

    private val bitmapCache: LruCache<String, Bitmap> by lazy {
        val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        val cacheSizeKb = (maxMemoryKb / 8).coerceAtLeast(8 * 1024)
        object : LruCache<String, Bitmap>(cacheSizeKb) {
            override fun sizeOf(key: String, value: Bitmap): Int {
                return value.byteCount / 1024
            }
        }
    }

    suspend fun <T> withPage(pageIndex: Int, block: (PdfRenderer.Page) -> T): T {
        return withContext(Dispatchers.IO) {
            renderMutex.withLock {
                val safeIndex = pageIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
                pdfRenderer.openPage(safeIndex).use { page ->
                    block(page)
                }
            }
        }
    }

    /**
     * Renders the base fit-to-screen bitmap for fast 1x horizontal swiping and backdrop panning.
     */
    suspend fun renderPage(
        pageIndex: Int,
        targetWidthPx: Int,
        highResMultiplier: Float = 1.25f
    ): Bitmap? {
        if (pageCount <= 0 || pageIndex !in 0 until pageCount) return null
        val requestedWidth = (targetWidthPx.coerceAtLeast(360) * highResMultiplier)
            .toInt()
            .coerceIn(480, 1600)
        val cacheKey = "$pageIndex@$requestedWidth"

        synchronized(bitmapCache) {
            bitmapCache.get(cacheKey)?.takeIf { !it.isRecycled }?.let { return it }
        }

        return withPage(pageIndex) { page ->
            val aspectRatio = if (page.width > 0) {
                page.height.toFloat() / page.width.toFloat()
            } else {
                1.414f
            }
            val bitmapWidth = requestedWidth
            val bitmapHeight = (bitmapWidth * aspectRatio).toInt().coerceIn(320, 3200)

            val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)

            page.render(
                bitmap,
                null,
                null,
                PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
            )

            synchronized(bitmapCache) {
                bitmapCache.put(cacheKey, bitmap)
            }
            bitmap
        }
    }

    /**
     * Renders ONLY the currently visible zoomed viewport region into a screen-sized tile bitmap,
     * keeping memory flat (e.g. ~8 MB at 1080p) even at 4x–5x zoom instead of allocating a
     * giant full-page bitmap.
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
        if (pageCount <= 0 || pageIndex !in 0 until pageCount) return null
        if (viewportWidthPx <= 0 || viewportHeightPx <= 0 || fitWidth <= 0f || fitHeight <= 0f) return null

        return withPage(pageIndex) { page ->
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
    }

    suspend fun extractOutline(): List<TocEntry> = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                PDDocument.load(stream).use { doc ->
                    val root = doc.documentCatalog?.documentOutline ?: return@use emptyList()
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
                }
            } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    suspend fun extractPageSnippet(pageIndex: Int): String = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                PDDocument.load(stream).use { doc ->
                    val oneBased = (pageIndex + 1).coerceIn(1, doc.numberOfPages.coerceAtLeast(1))
                    val stripper = PDFTextStripper().apply {
                        startPage = oneBased
                        endPage = oneBased
                    }
                    stripper.getText(doc)
                        .replace(Regex("\\s+"), " ")
                        .trim()
                        .take(140)
                }
            }.orEmpty()
        }.getOrDefault("")
    }

    override fun close() {
        synchronized(bitmapCache) {
            bitmapCache.evictAll()
        }
        runCatching { pdfRenderer.close() }
        runCatching { parcelFileDescriptor.close() }
    }

    companion object {
        private const val MAX_TILE_DIMENSION_PX = 1440

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
