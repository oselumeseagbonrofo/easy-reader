package com.ebookreader.app.data.parser.epub

data class ChapterTextSelection(
    val chapterIndex: Int,
    val startCharOffset: Int,
    val endCharOffset: Int,
    val selectedText: String
) {
    val isValid: Boolean
        get() = endCharOffset > startCharOffset && selectedText.isNotBlank()
}

object EpubSelectionHelper {

    private val SENTENCE_TERMINATORS = setOf('.', '!', '?')
    private val TRAILING_QUOTES_OR_PARENS = setOf('"', '\'', '”', '’', ')', ']')

    /**
     * Expands a character offset inside [chapterText] to the surrounding sentence boundaries
     * `[start, endExclusive)`.
     */
    fun expandToSentence(chapterText: String, charOffset: Int): Pair<Int, Int> {
        if (chapterText.isEmpty()) return 0 to 0
        val pos = charOffset.coerceIn(0, chapterText.lastIndex)

        var start = pos
        while (start > 0) {
            val prev = chapterText[start - 1]
            if (prev == '\n') break
            if (prev in SENTENCE_TERMINATORS) {
                // Break after sentence terminator followed by whitespace/quote
                if (chapterText[start].isWhitespace() || prev == '!' || prev == '?') {
                    break
                }
            }
            start--
        }
        while (start < chapterText.length &&
            (chapterText[start].isWhitespace() || chapterText[start] in setOf('“', '"'))
                && start < pos
        ) {
            if (!chapterText[start].isWhitespace()) break
            start++
        }

        var end = pos
        while (end < chapterText.length) {
            val ch = chapterText[end]
            if (ch == '\n') break
            if (ch in SENTENCE_TERMINATORS) {
                end++
                while (end < chapterText.length && chapterText[end] in TRAILING_QUOTES_OR_PARENS) {
                    end++
                }
                break
            }
            end++
        }

        while (start < end && chapterText[start].isWhitespace()) start++
        while (end > start && chapterText[end - 1].isWhitespace()) end--
        return start to end
    }

    /**
     * Expands a character offset inside [chapter] to the full containing paragraph block
     * `[start, endExclusive)`.
     */
    fun expandToParagraph(chapter: EpubChapter, charOffset: Int): Pair<Int, Int> {
        val text = chapter.plainText
        if (text.isEmpty()) return 0 to 0
        val pos = charOffset.coerceIn(0, text.lastIndex)

        val matchingBlock = chapter.blocks
            .filterIsInstance<EpubBlock.ParagraphBlock>()
            .firstOrNull { block -> pos in block.charStartInChapter..block.charEndInChapter }

        if (matchingBlock != null) {
            var s = matchingBlock.charStartInChapter.coerceIn(0, text.length)
            var e = matchingBlock.charEndInChapter.coerceIn(s, text.length)
            while (s < e && text[s].isWhitespace()) s++
            while (e > s && text[e - 1].isWhitespace()) e--
            if (e > s) return s to e
        }

        // Fallback to newline boundaries
        var start = pos
        while (start > 0 && text[start - 1] != '\n') start--
        var end = pos
        while (end < text.length && text[end] != '\n') end++
        while (start < end && text[start].isWhitespace()) start++
        while (end > start && text[end - 1].isWhitespace()) end--
        return start to end
    }
}
