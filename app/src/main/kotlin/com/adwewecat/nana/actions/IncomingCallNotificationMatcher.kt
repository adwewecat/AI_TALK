package com.adwewecat.nana.actions

import com.adwewecat.nana.core.voice.FastCommandParser

/** Matched incoming/active call action metadata extracted from a call notification. */
data class CallNotificationAction(
    val packageName: String,
    val title: String,
    val isAnswer: Boolean,
    val actionIndex: Int,
)

/**
 * Matches Vietnamese and English Answer/Decline labels across OriginOS SIM dialers and
 * popular VoIP apps (Zalo, Messenger, Telegram) without taking over the default dialer role.
 */
object IncomingCallNotificationMatcher {

    val SUPPORTED_CALL_PACKAGES = setOf(
        "com.android.incallui",
        "com.android.dialer",
        "com.vivo.dialer",
        "com.google.android.dialer",
        "com.zing.zalo",
        "com.facebook.orca",
        "org.telegram.messenger",
    )

    private val ANSWER_LABELS = listOf(
        "tra loi",
        "nghe may",
        "nghe",
        "chap nhan",
        "answer",
        "accept",
        "bat may",
    )

    private val DECLINE_LABELS = listOf(
        "tu choi",
        "cup may",
        "ket thuc",
        "decline",
        "reject",
        "end",
        "hang up",
        "tat cuoc goi",
    )

    private val QUICK_MESSAGE_EXCLUSIONS = listOf(
        "tin nhan",
        "sms",
        "message",
    )

    /**
     * Returns the index inside [actionTitles] matching Answer ([wantAnswer] = true) or
     * Decline/End ([wantAnswer] = false) for a supported [packageName], or `null` otherwise.
     */
    fun matchAction(
        packageName: String,
        actionTitles: List<String>,
        wantAnswer: Boolean,
    ): Int? {
        if (packageName !in SUPPORTED_CALL_PACKAGES) {
            return null
        }
        return actionTitles.indexOfFirst { title -> matchesLabel(title, wantAnswer) }
            .takeIf { it >= 0 }
    }

    /** Returns full [CallNotificationAction] details when [matchAction] finds a match. */
    fun findCallAction(
        packageName: String,
        actionTitles: List<String>,
        wantAnswer: Boolean,
    ): CallNotificationAction? {
        val index = matchAction(packageName, actionTitles, wantAnswer) ?: return null
        return CallNotificationAction(
            packageName = packageName,
            title = actionTitles[index],
            isAnswer = wantAnswer,
            actionIndex = index,
        )
    }

    /** Returns true when [labelText] matches an Answer or Decline button label on word boundaries. */
    fun matchesLabel(labelText: String, wantAnswer: Boolean): Boolean {
        val normalized = FastCommandParser.normalizeVietnamese(labelText)
        if (normalized.isEmpty()) {
            return false
        }
        val padded = " $normalized "
        if (wantAnswer && QUICK_MESSAGE_EXCLUSIONS.any { padded.contains(" $it ") }) {
            return false
        }
        val targets = if (wantAnswer) ANSWER_LABELS else DECLINE_LABELS
        return targets.any { label -> padded.contains(" $label ") }
    }
}
