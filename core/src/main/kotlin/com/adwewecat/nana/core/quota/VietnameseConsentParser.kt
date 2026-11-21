package com.adwewecat.nana.core.quota

import java.text.Normalizer
import java.util.Locale

/** Parsed user intent when asked whether to switch to Na Na's little sister persona. */
enum class ConsentDecision {
    AFFIRMATIVE,
    NEGATIVE,
    UNKNOWN,
}

/**
 * Parses short Vietnamese and English voice consent responses after normalizing case,
 * diacritics, and word boundaries.
 */
object VietnameseConsentParser {

    private val COMBINING_MARKS_REGEX = Regex("\\p{M}+")
    private val NON_ALNUM_REGEX = Regex("[^a-z0-9]+")

    private val NEGATIVE_PHRASES = listOf(
        "khong muon",
        "khong can",
        "khong dau",
        "khong",
        "thoi",
        "dung",
        "no",
    )

    private val AFFIRMATIVE_PHRASES = listOf(
        "noi chuyen di",
        "chuyen di",
        "dong y",
        "o ke",
        "okay",
        "oke",
        "ok",
        "um",
        "u",
        "co",
        "duoc",
        "yes",
    )

    /** Classifies [transcript] into [ConsentDecision], evaluating negatives before positives. */
    fun parse(transcript: String): ConsentDecision {
        val normalized = normalize(transcript)
        if (normalized.isEmpty()) {
            return ConsentDecision.UNKNOWN
        }

        val padded = " $normalized "
        // Check negative cues before positives so phrases like "không ok" always decline safely.
        if (NEGATIVE_PHRASES.any { phrase -> padded.contains(" $phrase ") }) {
            return ConsentDecision.NEGATIVE
        }
        if (AFFIRMATIVE_PHRASES.any { phrase -> padded.contains(" $phrase ") }) {
            return ConsentDecision.AFFIRMATIVE
        }
        return ConsentDecision.UNKNOWN
    }

    private fun normalize(text: String): String {
        val lowered = text.lowercase(Locale.ROOT).replace('đ', 'd')
        val decomposed = Normalizer.normalize(lowered, Normalizer.Form.NFD)
        val ascii = COMBINING_MARKS_REGEX.replace(decomposed, "")
        return NON_ALNUM_REGEX.replace(ascii, " ").trim()
    }
}
