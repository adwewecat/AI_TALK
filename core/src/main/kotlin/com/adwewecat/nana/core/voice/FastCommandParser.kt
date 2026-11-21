package com.adwewecat.nana.core.voice

import com.adwewecat.nana.core.model.DeviceCommand
import java.text.Normalizer
import java.util.Locale

/**
 * Offline Vietnamese normalizer, wake-word detector, and local fast-command parser.
 * Supports both diacritic and non-diacritic transcripts for devices without Google Speech Services.
 */
object FastCommandParser {

    private val COMBINING_MARKS_REGEX = Regex("\\p{M}+")
    private val NON_ALNUM_REGEX = Regex("[^a-z0-9]+")
    private val TOKEN_REGEX = Regex("[\\p{L}\\p{M}\\p{N}]+")

    private val WAKE_WORDS = listOf(
        "na na oi",
        "nana oi",
        "na na",
        "nana",
    )

    private val YOUTUBE_PREFIXES = listOf(
        listOf("mo", "youtube", "tim"),
        listOf("mo", "youtube", "phat"),
        listOf("bat", "nhac"),
        listOf("phat", "nhac"),
    )

    private val COMMAND_RULES: List<Pair<List<String>, DeviceCommand>> = listOf(
        listOf("bat may", "nghe may", "nhan cuoc goi") to DeviceCommand.AnswerIncomingCall,
        listOf("cup may", "tat cuoc goi", "tu choi cuoc goi") to DeviceCommand.EndCall,
        listOf("bat loa ngoai") to DeviceCommand.ToggleSpeaker(true),
        listOf("tat loa ngoai") to DeviceCommand.ToggleSpeaker(false),
        listOf("bat den pin") to DeviceCommand.ToggleFlashlight(true),
        listOf("tat den pin") to DeviceCommand.ToggleFlashlight(false),
        listOf("tam biet", "nghi nhe", "thoi nha", "dung lai", "im di") to DeviceCommand.StopSession,
    )

    /** Lowercases [text], maps `đ`/`Đ` to `d`, strips diacritics, and collapses whitespace. */
    fun normalizeVietnamese(text: String): String {
        val lowered = text.lowercase(Locale.ROOT).replace('đ', 'd')
        val decomposed = Normalizer.normalize(lowered, Normalizer.Form.NFD)
        val ascii = COMBINING_MARKS_REGEX.replace(decomposed, "")
        return NON_ALNUM_REGEX.replace(ascii, " ").trim()
    }

    /** Returns true when [transcript] contains a supported Na Na wake phrase on word boundaries. */
    fun matchesWakeWord(transcript: String): Boolean {
        val normalized = normalizeVietnamese(transcript)
        if (normalized.isEmpty()) {
            return false
        }
        val padded = " $normalized "
        return WAKE_WORDS.any { phrase -> padded.contains(" $phrase ") }
    }

    /**
     * Parses immediate local device commands from [transcript] without calling an LLM.
     * Returns `null` for ordinary conversation or incomplete media queries.
     */
    fun parseLocalCommand(transcript: String): DeviceCommand? {
        val normalized = normalizeVietnamese(transcript)
        if (normalized.isEmpty()) {
            return null
        }

        // Evaluate YouTube/music prefixes before static phrases so song titles like
        // "Tạm biệt mùa hè" do not accidentally trigger StopSession.
        for (prefix in YOUTUBE_PREFIXES) {
            val query = extractQueryAfterPrefix(transcript, prefix)
            if (query != null) {
                return DeviceCommand.OpenYouTubeSearch(query = query, autoPlay = true)
            }
        }

        val padded = " $normalized "
        for ((phrases, command) in COMMAND_RULES) {
            if (phrases.any { phrase -> padded.contains(" $phrase ") }) {
                return command
            }
        }
        return null
    }

    private fun extractQueryAfterPrefix(transcript: String, prefixTokens: List<String>): String? {
        val tokens = TOKEN_REGEX.findAll(transcript).map { match ->
            normalizeVietnamese(match.value) to (match.range.last + 1)
        }.toList()

        if (tokens.size < prefixTokens.size) {
            return null
        }

        for (start in 0..(tokens.size - prefixTokens.size)) {
            val matches = prefixTokens.indices.all { offset ->
                tokens[start + offset].first == prefixTokens[offset]
            }
            if (matches) {
                val endOffset = tokens[start + prefixTokens.size - 1].second
                val query = transcript.substring(endOffset)
                    .trimStart { it.isWhitespace() || it == ':' || it == ',' }
                    .trim()
                return query.takeIf { it.isNotBlank() }
            }
        }
        return null
    }
}
