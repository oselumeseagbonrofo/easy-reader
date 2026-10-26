package com.ebookreader.app.data.parser.epub

import com.ebookreader.app.domain.model.SearchMatch

object EpubSearchEngine {

    fun search(
        book: EpubBook,
        paginatedEpub: PaginatedEpub?,
        rawQuery: String,
        maxResults: Int = 400
    ): List<SearchMatch> {
        val query = rawQuery.trim()
        if (query.length < 2) return emptyList()

        val matches = ArrayList<SearchMatch>()
        val snippetRadius = 48

        for (chapter in book.chapters) {
            val text = chapter.plainText
            if (text.isEmpty()) continue

            var searchCursor = 0
            while (searchCursor < text.length && matches.size < maxResults) {
                val foundIdx = text.indexOf(query, startIndex = searchCursor, ignoreCase = true)
                if (foundIdx < 0) break

                val matchEnd = foundIdx + query.length
                val rawSnippetStart = (foundIdx - snippetRadius).coerceAtLeast(0)
                val rawSnippetEnd = (matchEnd + snippetRadius).coerceAtMost(text.length)

                val prefix = if (rawSnippetStart > 0) "…" else ""
                val suffix = if (rawSnippetEnd < text.length) "…" else ""
                val rawSlice = text.substring(rawSnippetStart, rawSnippetEnd)
                    .replace('\n', ' ')

                val snippet = "$prefix$rawSlice$suffix"
                val matchStartInSnippet = prefix.length + (foundIdx - rawSnippetStart)
                val matchEndInSnippet = (matchStartInSnippet + query.length).coerceAtMost(snippet.length)

                val pageIdx = paginatedEpub?.findPageForAnchor(chapter.index, foundIdx) ?: chapter.index

                matches += SearchMatch(
                    index = matches.size,
                    pageIndex = pageIdx,
                    chapterIndex = chapter.index,
                    chapterTitle = chapter.title,
                    snippet = snippet,
                    matchStartInSnippet = matchStartInSnippet,
                    matchEndInSnippet = matchEndInSnippet,
                    charOffsetInChapter = foundIdx,
                    matchLength = query.length
                )

                searchCursor = foundIdx + query.length.coerceAtLeast(1)
            }

            if (matches.size >= maxResults) break
        }

        return matches
    }
}
