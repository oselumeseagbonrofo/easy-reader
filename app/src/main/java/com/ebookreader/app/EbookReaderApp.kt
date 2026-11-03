package com.ebookreader.app

import android.app.Application
import com.ebookreader.app.data.local.AppDatabase
import com.ebookreader.app.data.local.ReaderPreferencesRepository
import com.ebookreader.app.data.repository.LibraryRepository
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class EbookReaderApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
        container = AppContainer(applicationContext)
    }
}

class AppContainer(context: android.content.Context) {
    private val database: AppDatabase by lazy { AppDatabase.getInstance(context) }

    val readerPreferencesRepository: ReaderPreferencesRepository by lazy {
        ReaderPreferencesRepository(context)
    }

    val libraryRepository: LibraryRepository by lazy {
        LibraryRepository(
            context = context,
            bookDao = database.bookDao(),
            bookmarkDao = database.bookmarkDao(),
            highlightDao = database.highlightDao()
        )
    }
}
