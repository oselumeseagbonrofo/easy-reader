package com.ebookreader.app.ui.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ebookreader.app.data.repository.LibraryRepository
import com.ebookreader.app.data.repository.LibraryShelfSource
import com.ebookreader.app.domain.model.Book
import com.ebookreader.app.domain.model.BookFormat
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class BookshelfFilter(val label: String) {
    ALL("All"),
    READING("Reading"),
    COMPLETED("Finished"),
    EPUB("EPUB"),
    PDF("PDF")
}

data class LibraryUiState(
    val allBooks: List<Book> = emptyList(),
    val filteredBooks: List<Book> = emptyList(),
    val continueReadingBook: Book? = null,
    val selectedFilter: BookshelfFilter = BookshelfFilter.ALL,
    val searchQuery: String = "",
    val isImporting: Boolean = false
)

sealed interface LibraryEvent {
    data class ShowMessage(val message: String) : LibraryEvent
    data class OpenBook(val bookId: String) : LibraryEvent
}

class LibraryViewModel internal constructor(
    private val libraryRepository: LibraryShelfSource
) : ViewModel() {

    constructor(libraryRepository: LibraryRepository) : this(libraryRepository as LibraryShelfSource)

    private val selectedFilter = MutableStateFlow(BookshelfFilter.ALL)
    private val searchQuery = MutableStateFlow("")
    private var activeImportsCount = 0
    private val isImporting = MutableStateFlow(false)

    private val _events = Channel<LibraryEvent>(Channel.BUFFERED)
    val events: Flow<LibraryEvent> = _events.receiveAsFlow()

    private val _openBookEvents = Channel<LibraryEvent.OpenBook>(Channel.BUFFERED)
    val openBookEvents: Flow<LibraryEvent.OpenBook> = _openBookEvents.receiveAsFlow()

    val uiState: StateFlow<LibraryUiState> = combine(
        libraryRepository.observeBooks(),
        selectedFilter,
        searchQuery,
        isImporting
    ) { books, filter, query, importing ->
        val normalizedQuery = query.trim()
        val filtered = books.filter { book ->
            val matchesFilter = when (filter) {
                BookshelfFilter.ALL -> true
                BookshelfFilter.READING -> book.isStarted && !book.isCompleted
                BookshelfFilter.COMPLETED -> book.isCompleted
                BookshelfFilter.EPUB -> book.format == BookFormat.EPUB
                BookshelfFilter.PDF -> book.format == BookFormat.PDF
            }
            val matchesQuery = normalizedQuery.isEmpty() ||
                book.title.contains(normalizedQuery, ignoreCase = true) ||
                book.author.contains(normalizedQuery, ignoreCase = true)
            matchesFilter && matchesQuery
        }

        val heroBook = books
            .filter { it.isStarted && !it.isCompleted && it.isFileAccessible }
            .maxByOrNull { it.lastReadAt ?: 0L }

        LibraryUiState(
            allBooks = books,
            filteredBooks = filtered,
            continueReadingBook = heroBook,
            selectedFilter = filter,
            searchQuery = query,
            isImporting = importing
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LibraryUiState()
    )

    fun setFilter(filter: BookshelfFilter) {
        selectedFilter.value = filter
    }

    fun setSearchQuery(query: String) {
        searchQuery.value = query
    }

    fun importExternalBook(uri: Uri, openImmediately: Boolean = false) {
        importExternalBookUri(uri.toString(), openImmediately = openImmediately, originalUri = uri)
    }

    internal fun importExternalBookUri(
        uriString: String,
        openImmediately: Boolean = false,
        originalUri: Uri? = null
    ) {
        viewModelScope.launch {
            activeImportsCount++
            isImporting.value = true
            try {
                val result = libraryRepository.linkExternalBookUri(uriString, originalUri)
                result.onSuccess { book ->
                    if (openImmediately) {
                        _openBookEvents.send(LibraryEvent.OpenBook(book.id))
                    } else {
                        _events.send(LibraryEvent.ShowMessage("Added \"${book.title}\""))
                    }
                }.onFailure { err ->
                    _events.send(
                        LibraryEvent.ShowMessage(
                            err.message ?: "Could not open the selected ebook file."
                        )
                    )
                }
            } finally {
                activeImportsCount = (activeImportsCount - 1).coerceAtLeast(0)
                isImporting.value = activeImportsCount > 0
            }
        }
    }

    fun relinkMissingBook(bookId: String, newUri: Uri) {
        relinkMissingBookUri(bookId, newUri.toString(), newUri)
    }

    internal fun relinkMissingBookUri(bookId: String, newUriString: String, originalUri: Uri? = null) {
        viewModelScope.launch {
            libraryRepository.relinkBookUriString(bookId, newUriString, originalUri)
                .onSuccess { book ->
                    _events.send(LibraryEvent.ShowMessage("Re-linked \"${book.title}\""))
                }
                .onFailure { err ->
                    _events.send(
                        LibraryEvent.ShowMessage(err.message ?: "Failed to re-link file.")
                    )
                }
        }
    }

    fun removeBookFromShelf(book: Book) {
        viewModelScope.launch {
            libraryRepository.removeBookFromShelf(book)
            _events.send(LibraryEvent.ShowMessage("Removed \"${book.title}\" from shelf"))
        }
    }

    companion object {
        fun factory(libraryRepository: LibraryRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return LibraryViewModel(libraryRepository) as T
                }
            }
    }
}
