package com.ebookreader.app.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ebookreader.app.domain.model.Book
import com.ebookreader.app.domain.model.BookFormat
import com.ebookreader.app.domain.model.Bookmark
import com.ebookreader.app.domain.model.HighlightColor
import com.ebookreader.app.domain.model.TextHighlight

@Entity(
    tableName = "books",
    indices = [Index(value = ["fileUri"])]
)
data class BookEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val author: String,
    val format: String,
    val fileUri: String,
    val coverPath: String?,
    val currentPage: Int = 0,
    val totalPages: Int = 0,
    val progress: Float = 0f,
    val epubChapterIndex: Int = 0,
    val epubCharOffset: Int = 0,
    val addedAt: Long = System.currentTimeMillis(),
    val lastReadAt: Long? = null
) {
    fun toDomain(isFileAccessible: Boolean = true): Book = Book(
        id = id,
        title = title,
        author = author,
        format = runCatching { BookFormat.valueOf(format) }.getOrDefault(BookFormat.EPUB),
        fileUri = fileUri,
        coverPath = coverPath,
        currentPage = currentPage,
        totalPages = totalPages,
        progress = progress,
        epubChapterIndex = epubChapterIndex,
        epubCharOffset = epubCharOffset,
        addedAt = addedAt,
        lastReadAt = lastReadAt,
        isFileAccessible = isFileAccessible
    )
}

@Entity(
    tableName = "bookmarks",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["bookId"])]
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val bookId: String,
    val pageIndex: Int,
    val epubChapterIndex: Int,
    val epubCharOffset: Int,
    val epubEndCharOffset: Int = epubCharOffset,
    val progress: Float,
    val chapterTitle: String,
    val snippet: String,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toDomain(): Bookmark = Bookmark(
        id = id,
        bookId = bookId,
        pageIndex = pageIndex,
        epubChapterIndex = epubChapterIndex,
        epubCharOffset = epubCharOffset,
        epubEndCharOffset = epubEndCharOffset,
        progress = progress,
        chapterTitle = chapterTitle,
        snippet = snippet,
        createdAt = createdAt
    )

    companion object {
        fun fromDomain(bookmark: Bookmark): BookmarkEntity = BookmarkEntity(
            id = bookmark.id,
            bookId = bookmark.bookId,
            pageIndex = bookmark.pageIndex,
            epubChapterIndex = bookmark.epubChapterIndex,
            epubCharOffset = bookmark.epubCharOffset,
            epubEndCharOffset = bookmark.epubEndCharOffset,
            progress = bookmark.progress,
            chapterTitle = bookmark.chapterTitle,
            snippet = bookmark.snippet,
            createdAt = bookmark.createdAt
        )
    }
}

@Entity(
    tableName = "highlights",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["bookId", "chapterIndex"])]
)
data class HighlightEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val bookId: String,
    val pageIndex: Int,
    val chapterIndex: Int,
    val startCharOffset: Int,
    val endCharOffset: Int,
    val chapterTitle: String,
    val selectedText: String,
    val colorName: String = HighlightColor.YELLOW.name,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toDomain(): TextHighlight = TextHighlight(
        id = id,
        bookId = bookId,
        pageIndex = pageIndex,
        chapterIndex = chapterIndex,
        startCharOffset = startCharOffset,
        endCharOffset = endCharOffset,
        chapterTitle = chapterTitle,
        selectedText = selectedText,
        color = runCatching { HighlightColor.valueOf(colorName) }.getOrDefault(HighlightColor.YELLOW),
        createdAt = createdAt
    )
}
