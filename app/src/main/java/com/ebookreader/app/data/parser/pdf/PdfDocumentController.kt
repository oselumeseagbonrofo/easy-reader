package com.ebookreader.app.data.parser.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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

    suspend fun renderPage(
        pageIndex: Int,
        targetWidthPx: Int,
        highResMultiplier: Float = 1.5f
    ): Bitmap? {
        if (pageCount <= 0 || pageIndex !in 0 until pageCount) return null
        val requestedWidth = (targetWidthPx.coerceAtLeast(360) * highResMultiplier)
            .toInt()
            .coerceIn(480, 2560)
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
            val bitmapHeight = (bitmapWidth * aspectRatio).toInt().coerceIn(320, 4096)

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
