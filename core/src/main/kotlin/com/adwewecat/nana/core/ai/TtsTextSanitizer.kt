package com.adwewecat.nana.core.ai

/**
 * Cleans LLM replies for spoken Vietnamese TTS output and chunks long utterances cleanly
 * without splitting words or losing Vietnamese diacritics.
 */
object TtsTextSanitizer {

    private val MARKDOWN_LINK_REGEX = Regex("""\[([^\]]+)]\([^)]+\)""")
    private val URL_REGEX = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
    private val LEADING_BULLET_REGEX = Regex("""(?m)^\s*[-•*+]\s+""")
    private val MARKDOWN_SYMBOLS_REGEX = Regex("""[*_`#~]+""")
    private val MULTISPACE_REGEX = Regex("""\s+""")
    private val SENTENCE_BREAK_CHARS = charArrayOf('.', '!', '?', ';', ':', ',', '\n')

    /** Removes markdown syntax and URLs so TTS engines speak clean natural Vietnamese. */
    fun sanitizeForSpeech(raw: String): String {
        if (raw.isBlank()) return ""
        var cleaned = raw
        cleaned = MARKDOWN_LINK_REGEX.replace(cleaned, "$1")
        cleaned = URL_REGEX.replace(cleaned, " ")
        cleaned = LEADING_BULLET_REGEX.replace(cleaned, "")
        cleaned = MARKDOWN_SYMBOLS_REGEX.replace(cleaned, "")
        cleaned = MULTISPACE_REGEX.replace(cleaned, " ")
        return cleaned.trim()
    }

    /**
     * Splits [text] into chunks of at most [maxChunkChars] characters, preferring natural
     * Vietnamese sentence/clause punctuation and falling back to whitespace boundaries.
     */
    fun chunkForWebTts(text: String, maxChunkChars: Int = 180): List<String> {
        require(maxChunkChars >= 20) { "maxChunkChars must be at least 20" }
        val normalized = MULTISPACE_REGEX.replace(text.trim(), " ")
        if (normalized.isEmpty()) return emptyList()
        if (normalized.length <= maxChunkChars) return listOf(normalized)

        val chunks = mutableListOf<String>()
        var remaining = normalized
        while (remaining.isNotEmpty()) {
            if (remaining.length <= maxChunkChars) {
                chunks += remaining
                break
            }

            val splitAt = findBestSplitIndex(remaining, maxChunkChars)
            val piece = remaining.substring(0, splitAt).trim()
            if (piece.isNotEmpty()) {
                chunks += piece
            }
            remaining = remaining.substring(splitAt).trimStart()
        }
        return chunks
    }

    private fun findBestSplitIndex(text: String, maxChunkChars: Int): Int {
        val window = text.substring(0, maxChunkChars)

        // Prefer breaking immediately after sentence/clause punctuation in the second half of the window.
        val minPreferred = maxChunkChars / 3
        for (idx in window.length - 1 downTo minPreferred) {
            if (window[idx] in SENTENCE_BREAK_CHARS) {
                val afterPunct = idx + 1
                if (afterPunct >= text.length || text[afterPunct].isWhitespace()) {
                    return afterPunct
                }
            }
        }

        // Fall back to the last whitespace boundary inside maxChunkChars.
        val lastSpace = window.lastIndexOf(' ')
        if (lastSpace > 0) {
            return lastSpace
        }

        // Hard fallback for unbroken tokens longer than maxChunkChars.
        return maxChunkChars
    }
}
