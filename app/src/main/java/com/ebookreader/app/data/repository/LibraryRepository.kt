package com.ebookreader.app.data.repository

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.ebookreader.app.data.local.BookDao
import com.ebookreader.app.data.local.BookEntity
import com.ebookreader.app.data.local.BookmarkDao
import com.ebookreader.app.data.local.BookmarkEntity
import com.ebookreader.app.data.local.HighlightDao
import com.ebookreader.app.data.local.HighlightEntity
import com.ebookreader.app.data.parser.epub.EpubParser
import com.ebookreader.app.data.parser.epub.EpubRandomAccessArchive
import com.ebookreader.app.domain.model.Book
import com.ebookreader.app.domain.model.BookFormat
import com.ebookreader.app.domain.model.Bookmark
import com.ebookreader.app.domain.model.HighlightColor
import com.ebookreader.app.domain.model.ReadingProgressCalculator
import com.ebookreader.app.domain.model.TextHighlight
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.security.MessageDigest

class LibraryRepository(
    private val context: Context,
    private val bookDao: BookDao,
    private val bookmarkDao: BookmarkDao,
    private val highlightDao: HighlightDao
) {
    private val annotationMutex = Mutex()

    fun observeBooks(): Flow<List<Book>> = bookDao.observeAllBooks().map { entities ->
        entities.map { entity ->
            entity.toDomain(isFileAccessible = isUriAccessible(entity.fileUri))
        }
    }

    fun observeBookById(bookId: String): Flow<Book?> = bookDao.observeBookById(bookId).map { entity ->
        entity?.toDomain(isFileAccessible = isUriAccessible(entity.fileUri))
    }

    suspend fun getBookById(bookId: String): Book? = withContext(Dispatchers.IO) {
        val entity = bookDao.getBookById(bookId) ?: return@withContext null
        entity.toDomain(isFileAccessible = isUriAccessible(entity.fileUri))
    }

    /**
     * Links an external EPUB or PDF URI in-place via Storage Access Framework without duplicating
     * the ebook file into app internal storage.
     */
    suspend fun linkExternalBook(uri: Uri): Result<Book> = withContext(Dispatchers.IO) {
        runCatching {
            takePersistableReadPermissionIfPossible(uri)

            val uriString = uri.toString()
            bookDao.getBookByUri(uriString)?.let { existingByUri ->
                if (isUriAccessible(uriString)) {
                    return@runCatching existingByUri.toDomain(isFileAccessible = true)
                }
            }

            val displayName = queryDisplayName(uri)
            val fallbackTitle = displayName
                ?.substringBeforeLast('.')
                ?.replace('_', ' ')
                ?.trim()
                .takeUnless { it.isNullOrBlank() }
                ?: "Untitled Book"

            val (sha256Id, headerBytes) = computeSha256AndHeader(uri)

            // If this exact book content already exists on the shelf (e.g. file moved externally),
            // update its external URI reference while keeping all progress and bookmarks intact.
            val existingByHash = bookDao.getBookById(sha256Id)
            if (existingByHash != null) {
                bookDao.updateBookUri(sha256Id, uriString)
                val updated = bookDao.getBookById(sha256Id) ?: existingByHash.copy(fileUri = uriString)
                return@runCatching updated.toDomain(isFileAccessible = true)
            }

            val format = detectBookFormat(uri, displayName, headerBytes)
            val entity = when (format) {
                BookFormat.PDF -> buildPdfBookEntity(
                    bookId = sha256Id,
                    uri = uri,
                    fallbackTitle = fallbackTitle
                )
                BookFormat.EPUB -> buildEpubBookEntity(
                    bookId = sha256Id,
                    uri = uri,
                    fallbackTitle = fallbackTitle
                )
            }

            bookDao.upsertBook(entity)
            entity.toDomain(isFileAccessible = true)
        }
    }

    /**
     * Re-binds a moved or renamed external file URI to an existing bookshelf entry,
     * preserving reading progress and bookmarks.
     */
    suspend fun relinkBookUri(bookId: String, newUri: Uri): Result<Book> = withContext(Dispatchers.IO) {
        runCatching {
            takePersistableReadPermissionIfPossible(newUri)
            if (!isUriAccessible(newUri.toString())) {
                throw FileNotFoundException("Selected file cannot be read.")
            }
            bookDao.updateBookUri(bookId, newUri.toString())
            val updated = bookDao.getBookById(bookId)
                ?: throw IllegalStateException("Book record not found.")
            updated.toDomain(isFileAccessible = true)
        }
    }

    /**
     * Removes a book from the shelf non-destructively: deletes only local metadata & cached cover
     * thumbnail and releases the SAF URI permission, leaving the user's external file untouched.
     */
    suspend fun removeBookFromShelf(book: Book) = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(book.fileUri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        book.coverPath?.let { path ->
            runCatching { File(path).delete() }
        }
        bookDao.deleteBookById(book.id)
    }

    suspend fun saveReadingProgress(
        bookId: String,
        currentPage: Int,
        totalPages: Int,
        epubChapterIndex: Int = 0,
        epubCharOffset: Int = 0
    ) = withContext(Dispatchers.IO) {
        if (totalPages <= 0) return@withContext
        val clampedPage = currentPage.coerceIn(0, totalPages - 1)
        val progress = ReadingProgressCalculator.computeProgress(clampedPage, totalPages)
        bookDao.updateProgress(
            bookId = bookId,
            currentPage = clampedPage,
            totalPages = totalPages,
            progress = progress,
            chapterIndex = epubChapterIndex.coerceAtLeast(0),
            charOffset = epubCharOffset.coerceAtLeast(0),
            timestamp = System.currentTimeMillis()
        )
    }

    fun observeBookmarks(bookId: String): Flow<List<Bookmark>> =
        bookmarkDao.observeBookmarksForBook(bookId).map { list ->
            list.map { it.toDomain() }
        }

    fun observeHighlights(bookId: String): Flow<List<TextHighlight>> =
        highlightDao.observeHighlightsForBook(bookId).map { list ->
            list.map { it.toDomain() }
        }

    suspend fun toggleBookmark(
        bookId: String,
        pageIndex: Int,
        totalPages: Int,
        epubChapterIndex: Int,
        epubCharOffset: Int,
        chapterTitle: String,
        snippet: String
    ): Boolean = withContext(Dispatchers.IO) {
        annotationMutex.withLock {
            val existing = bookmarkDao.getBookmarksForBook(bookId).firstOrNull { bm ->
                bm.pageIndex == pageIndex && bm.epubEndCharOffset <= bm.epubCharOffset
            }
            if (existing != null) {
                bookmarkDao.deleteBookmarkById(existing.id)
                false
            } else {
                val progress = ReadingProgressCalculator.computeProgress(pageIndex, totalPages)
                bookmarkDao.insertBookmark(
                    BookmarkEntity(
                        bookId = bookId,
                        pageIndex = pageIndex,
                        epubChapterIndex = epubChapterIndex,
                        epubCharOffset = epubCharOffset,
                        epubEndCharOffset = epubCharOffset,
                        progress = progress,
                        chapterTitle = chapterTitle,
                        snippet = snippet.ifBlank { "Page ${pageIndex + 1}" }
                    )
                )
                true
            }
        }
    }

    suspend fun toggleSelectionBookmark(
        bookId: String,
        pageIndex: Int,
        totalPages: Int,
        chapterIndex: Int,
        startCharOffset: Int,
        endCharOffset: Int,
        chapterTitle: String,
        selectedText: String
    ): Boolean = withContext(Dispatchers.IO) {
        annotationMutex.withLock {
            val safeEnd = maxOf(startCharOffset + 1, endCharOffset)
            val existing = bookmarkDao.getBookmarksForBook(bookId).firstOrNull { bm ->
                bm.epubChapterIndex == chapterIndex &&
                    bm.epubEndCharOffset > bm.epubCharOffset &&
                    bm.epubCharOffset == startCharOffset &&
                    bm.epubEndCharOffset == safeEnd
            }
            if (existing != null) {
                bookmarkDao.deleteBookmarkById(existing.id)
                false
            } else {
                val progress = ReadingProgressCalculator.computeProgress(pageIndex, totalPages)
                bookmarkDao.insertBookmark(
                    BookmarkEntity(
                        bookId = bookId,
                        pageIndex = pageIndex,
                        epubChapterIndex = chapterIndex,
                        epubCharOffset = startCharOffset,
                        epubEndCharOffset = safeEnd,
                        progress = progress,
                        chapterTitle = chapterTitle,
                        snippet = selectedText.trim().take(600)
                    )
                )
                true
            }
        }
    }

    suspend fun deleteBookmark(bookmarkId: Long) = withContext(Dispatchers.IO) {
        annotationMutex.withLock {
            bookmarkDao.deleteBookmarkById(bookmarkId)
        }
    }

    suspend fun saveHighlight(
        bookId: String,
        pageIndex: Int,
        chapterIndex: Int,
        startCharOffset: Int,
        endCharOffset: Int,
        chapterTitle: String,
        selectedText: String,
        color: HighlightColor
    ) = withContext(Dispatchers.IO) {
        if (endCharOffset <= startCharOffset) return@withContext
        annotationMutex.withLock {
            highlightDao.deleteOverlappingHighlights(
                bookId = bookId,
                chapterIndex = chapterIndex,
                startOffset = startCharOffset,
                endOffset = endCharOffset
            )
            highlightDao.insertHighlight(
                HighlightEntity(
                    bookId = bookId,
                    pageIndex = pageIndex,
                    chapterIndex = chapterIndex,
                    startCharOffset = startCharOffset,
                    endCharOffset = endCharOffset,
                    chapterTitle = chapterTitle,
                    selectedText = selectedText.trim().take(600),
                    colorName = color.name
                )
            )
        }
    }

    suspend fun clearHighlightsInRange(
        bookId: String,
        chapterIndex: Int,
        startCharOffset: Int,
        endCharOffset: Int
    ) = withContext(Dispatchers.IO) {
        annotationMutex.withLock {
            highlightDao.deleteOverlappingHighlights(
                bookId = bookId,
                chapterIndex = chapterIndex,
                startOffset = startCharOffset,
                endOffset = endCharOffset
            )
        }
    }

    suspend fun deleteHighlight(highlightId: Long) = withContext(Dispatchers.IO) {
        annotationMutex.withLock {
            highlightDao.deleteHighlightById(highlightId)
        }
    }

    fun isUriAccessible(uriString: String): Boolean {
        return runCatching {
            val uri = Uri.parse(uriString)
            context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
        }.getOrDefault(false)
    }

    private fun takePersistableReadPermissionIfPossible(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else {
                    null
                }
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun computeSha256AndHeader(uri: Uri): Pair<String, ByteArray> {
        val digest = MessageDigest.getInstance("SHA-256")
        val header = ByteArray(8)
        var headerFilled = 0

        val stream = context.contentResolver.openInputStream(uri)
            ?: throw FileNotFoundException("Cannot open input stream for $uri")

        stream.use { input ->
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
                if (headerFilled < header.size) {
                    val toCopy = minOf(read, header.size - headerFilled)
                    System.arraycopy(buffer, 0, header, headerFilled, toCopy)
                    headerFilled += toCopy
                }
            }
        }

        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        return hex to header.copyOf(headerFilled)
    }

    private fun detectBookFormat(
        uri: Uri,
        displayName: String?,
        headerBytes: ByteArray
    ): BookFormat {
        // Magic bytes: "%PDF" -> 0x25 0x50 0x44 0x46
        if (headerBytes.size >= 4 &&
            headerBytes[0] == 0x25.toByte() &&
            headerBytes[1] == 0x50.toByte() &&
            headerBytes[2] == 0x44.toByte() &&
            headerBytes[3] == 0x46.toByte()
        ) {
            return BookFormat.PDF
        }

        val mime = context.contentResolver.getType(uri)?.lowercase().orEmpty()
        if ("pdf" in mime || displayName?.endsWith(".pdf", ignoreCase = true) == true) {
            return BookFormat.PDF
        }

        // Magic bytes: "PK\x03\x04" -> ZIP / EPUB
        if (headerBytes.size >= 4 &&
            headerBytes[0] == 0x50.toByte() &&
            headerBytes[1] == 0x4B.toByte()
        ) {
            return BookFormat.EPUB
        }

        if ("epub" in mime || displayName?.endsWith(".epub", ignoreCase = true) == true) {
            return BookFormat.EPUB
        }

        throw IllegalArgumentException("Unsupported file format. Please select an EPUB or PDF file.")
    }

    private fun buildPdfBookEntity(
        bookId: String,
        uri: Uri,
        fallbackTitle: String
    ): BookEntity {
        var pageCount = 0
        var coverPath: String? = null

        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                pageCount = renderer.pageCount
                if (renderer.pageCount > 0) {
                    renderer.openPage(0).use { firstPage ->
                        val targetWidth = 420
                        val ratio = if (firstPage.width > 0) {
                            firstPage.height.toFloat() / firstPage.width.toFloat()
                        } else {
                            1.414f
                        }
                        val targetHeight = (targetWidth * ratio).toInt().coerceIn(280, 900)
                        val bmp = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                        Canvas(bmp).drawColor(Color.WHITE)
                        firstPage.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        coverPath = saveCoverThumbnailToCache(bookId, bmp)
                        bmp.recycle()
                    }
                }
            }
        }

        var title = fallbackTitle
        var author = "PDF Document"
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                PDDocument.load(stream, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
                    val info = doc.documentInformation
                    info?.title?.trim()?.takeIf { it.isNotBlank() }?.let { title = it }
                    info?.author?.trim()?.takeIf { it.isNotBlank() }?.let { author = it }
                }
            }
        }

        return BookEntity(
            id = bookId,
            title = title,
            author = author,
            format = BookFormat.PDF.name,
            fileUri = uri.toString(),
            coverPath = coverPath,
            currentPage = 0,
            totalPages = pageCount,
            progress = 0f,
            epubChapterIndex = 0,
            epubCharOffset = 0,
            addedAt = System.currentTimeMillis(),
            lastReadAt = null
        )
    }

    private fun buildEpubBookEntity(
        bookId: String,
        uri: Uri,
        fallbackTitle: String
    ): BookEntity {
        val summary = runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).use { fis ->
                    val archive = EpubRandomAccessArchive.openOrNull(fis.channel, closeChannelOnClose = false)
                    archive?.let { EpubParser.extractMetadataAndCover(it, fallbackTitle) }
                }
            }
        }.getOrNull() ?: EpubParser.extractMetadataAndCover(
            inputStreamProvider = {
                context.contentResolver.openInputStream(uri)
                    ?: throw FileNotFoundException("Cannot read EPUB stream: $uri")
            },
            fallbackTitle = fallbackTitle
        )

        val coverPath = summary.coverImageBytes?.let { rawBytes ->
            decodeDownscaledBitmap(rawBytes, maxDimension = 540)?.let { bmp ->
                val saved = saveCoverThumbnailToCache(bookId, bmp)
                bmp.recycle()
                saved
            }
        }

        return BookEntity(
            id = bookId,
            title = summary.title,
            author = summary.author,
            format = BookFormat.EPUB.name,
            fileUri = uri.toString(),
            coverPath = coverPath,
            currentPage = 0,
            totalPages = summary.estimatedChapters,
            progress = 0f,
            epubChapterIndex = 0,
            epubCharOffset = 0,
            addedAt = System.currentTimeMillis(),
            lastReadAt = null
        )
    }

    private fun decodeDownscaledBitmap(bytes: ByteArray, maxDimension: Int): Bitmap? {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)
        if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) return null

        var sampleSize = 1
        while (boundsOpts.outWidth / sampleSize > maxDimension * 2 ||
            boundsOpts.outHeight / sampleSize > maxDimension * 2
        ) {
            sampleSize *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
    }

    private fun saveCoverThumbnailToCache(bookId: String, bitmap: Bitmap): String? {
        return runCatching {
            val coversDir = File(context.cacheDir, "covers").apply { mkdirs() }
            val outFile = File(coversDir, "$bookId.webp")
            FileOutputStream(outFile).use { fos ->
                val compressFormat = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP
                }
                bitmap.compress(compressFormat, 82, fos)
            }
            outFile.absolutePath
        }.getOrNull()
    }
}
