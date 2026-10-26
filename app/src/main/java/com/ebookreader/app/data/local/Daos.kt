package com.ebookreader.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY COALESCE(lastReadAt, 0) DESC, addedAt DESC")
    fun observeAllBooks(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :bookId LIMIT 1")
    suspend fun getBookById(bookId: String): BookEntity?

    @Query("SELECT * FROM books WHERE id = :bookId LIMIT 1")
    fun observeBookById(bookId: String): Flow<BookEntity?>

    @Query("SELECT * FROM books WHERE fileUri = :uri LIMIT 1")
    suspend fun getBookByUri(uri: String): BookEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBook(book: BookEntity)

    @Query("""
        UPDATE books
        SET currentPage = :currentPage,
            totalPages = :totalPages,
            progress = :progress,
            epubChapterIndex = :chapterIndex,
            epubCharOffset = :charOffset,
            lastReadAt = :timestamp
        WHERE id = :bookId
    """)
    suspend fun updateProgress(
        bookId: String,
        currentPage: Int,
        totalPages: Int,
        progress: Float,
        chapterIndex: Int,
        charOffset: Int,
        timestamp: Long
    )

    @Query("UPDATE books SET fileUri = :newUri WHERE id = :bookId")
    suspend fun updateBookUri(bookId: String, newUri: String)

    @Query("DELETE FROM books WHERE id = :bookId")
    suspend fun deleteBookById(bookId: String)
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY pageIndex ASC, createdAt DESC")
    fun observeBookmarksForBook(bookId: String): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId")
    suspend fun getBookmarksForBook(bookId: String): List<BookmarkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(bookmark: BookmarkEntity): Long

    @Query("DELETE FROM bookmarks WHERE id = :bookmarkId")
    suspend fun deleteBookmarkById(bookmarkId: Long)

    @Query("DELETE FROM bookmarks WHERE bookId = :bookId AND pageIndex = :pageIndex")
    suspend fun deleteBookmarkAtPage(bookId: String, pageIndex: Int)
}
