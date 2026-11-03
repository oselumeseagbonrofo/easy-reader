package com.ebookreader.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [BookEntity::class, BookmarkEntity::class, HighlightEntity::class],
    version = 2,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun highlightDao(): HighlightDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Rebuild bookmarks without unused note column and with epubEndCharOffset for text selections
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `bookmarks_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `bookId` TEXT NOT NULL,
                        `pageIndex` INTEGER NOT NULL,
                        `epubChapterIndex` INTEGER NOT NULL,
                        `epubCharOffset` INTEGER NOT NULL,
                        `epubEndCharOffset` INTEGER NOT NULL,
                        `progress` REAL NOT NULL,
                        `chapterTitle` TEXT NOT NULL,
                        `snippet` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO `bookmarks_new` (
                        `id`, `bookId`, `pageIndex`, `epubChapterIndex`, `epubCharOffset`,
                        `epubEndCharOffset`, `progress`, `chapterTitle`, `snippet`, `createdAt`
                    )
                    SELECT `id`, `bookId`, `pageIndex`, `epubChapterIndex`, `epubCharOffset`,
                           `epubCharOffset`, `progress`, `chapterTitle`, `snippet`, `createdAt`
                    FROM `bookmarks`
                """.trimIndent())
                db.execSQL("DROP TABLE `bookmarks`")
                db.execSQL("ALTER TABLE `bookmarks_new` RENAME TO `bookmarks`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_bookmarks_bookId` ON `bookmarks` (`bookId`)")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `highlights` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `bookId` TEXT NOT NULL,
                        `pageIndex` INTEGER NOT NULL,
                        `chapterIndex` INTEGER NOT NULL,
                        `startCharOffset` INTEGER NOT NULL,
                        `endCharOffset` INTEGER NOT NULL,
                        `chapterTitle` TEXT NOT NULL,
                        `selectedText` TEXT NOT NULL,
                        `colorName` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_highlights_bookId_chapterIndex` ON `highlights` (`bookId`, `chapterIndex`)")
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "easy_reader.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
