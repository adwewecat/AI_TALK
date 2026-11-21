package com.adwewecat.nana.core.quota

import com.adwewecat.nana.core.model.PersonaMode
import com.adwewecat.nana.core.model.QuotaState

/** Result of handling a wake word, consent utterance, or consent timeout transition. */
data class WakeResult(
    val state: QuotaState,
    val persona: PersonaMode,
    val spokenPrompt: String?,
    val awaitConsent: Boolean,
)

/**
 * Coordinates Na Na's paid-tier quota exhaustion, tired state, Vietnamese voice consent,
 * and temporary fallback to the "Em gái" free-tier persona.
 */
class NaNaQuotaStateMachine(
    private val keyPool: KeyPoolManager,
) {
    private val lock = Any()

    @Volatile
    private var _state: QuotaState = QuotaState.ACTIVE_NANA

    @Volatile
    private var _activePersona: PersonaMode = PersonaMode.NANA

    /** Current quota lifecycle state. */
    val state: QuotaState
        get() = _state

    /** Currently active assistant persona. */
    val activePersona: PersonaMode
        get() = _activePersona

    /** Evaluates key availability upon wake word and either resumes Na Na or asks for consent. */
    fun onWakeWord(): WakeResult = synchronized(lock) {
        if (keyPool.hasAnyHealthyPaidChatKey()) {
            transition(
                state = QuotaState.ACTIVE_NANA,
                persona = PersonaMode.NANA,
                spokenPrompt = null,
                awaitConsent = false,
            )
        } else {
            transition(
                state = QuotaState.NANA_TIRED_AWAITING_CONSENT,
                persona = PersonaMode.NANA,
                spokenPrompt = TIRED_CONSENT_PROMPT,
                awaitConsent = true,
            )
        }
    }

    /** Transitions Na Na into [QuotaState.NANA_TIRED] and returns the exact exhaustion notice. */
    fun onAllPaidChatKeysExhausted(): String = synchronized(lock) {
        _state = QuotaState.NANA_TIRED
        _activePersona = PersonaMode.NANA
        QUOTA_EXHAUSTED_NOTICE
    }

    /** Processes a user's spoken reply while awaiting consent to speak with Na Na's little sister. */
    fun onConsentUtterance(transcript: String): WakeResult = synchronized(lock) {
        when (VietnameseConsentParser.parse(transcript)) {
            ConsentDecision.AFFIRMATIVE -> transition(
                state = QuotaState.ACTIVE_LITTLE_SISTER,
                persona = PersonaMode.LITTLE_SISTER,
                spokenPrompt = LITTLE_SISTER_GREETING,
                awaitConsent = false,
            )

            ConsentDecision.NEGATIVE -> transition(
                state = QuotaState.NANA_TIRED,
                persona = PersonaMode.NANA,
                spokenPrompt = DECLINED_GOODBYE,
                awaitConsent = false,
            )

            ConsentDecision.UNKNOWN -> transition(
                state = QuotaState.NANA_TIRED_AWAITING_CONSENT,
                persona = PersonaMode.NANA,
                spokenPrompt = TIRED_CONSENT_PROMPT,
                awaitConsent = true,
            )
        }
    }

    /** Handles consent timeout by returning safely to [QuotaState.NANA_TIRED] without switching persona. */
    fun onConsentTimeout(): WakeResult = synchronized(lock) {
        transition(
            state = QuotaState.NANA_TIRED,
            persona = PersonaMode.NANA,
            spokenPrompt = DECLINED_GOODBYE,
            awaitConsent = false,
        )
    }

    /** Restores Na Na if paid keys recovered, or returns Little Sister sessions to [QuotaState.NANA_TIRED]. */
    fun onSessionEnded() = synchronized(lock) {
        if (keyPool.hasAnyHealthyPaidChatKey()) {
            _state = QuotaState.ACTIVE_NANA
            _activePersona = PersonaMode.NANA
        } else if (_state == QuotaState.ACTIVE_LITTLE_SISTER || _state == QuotaState.NANA_TIRED_AWAITING_CONSENT) {
            _state = QuotaState.NANA_TIRED
            _activePersona = PersonaMode.NANA
        }
    }

    private fun transition(
        state: QuotaState,
        persona: PersonaMode,
        spokenPrompt: String?,
        awaitConsent: Boolean,
    ): WakeResult {
        _state = state
        _activePersona = persona
        return WakeResult(
            state = state,
            persona = persona,
            spokenPrompt = spokenPrompt,
            awaitConsent = awaitConsent,
        )
    }

    companion object {
        const val QUOTA_EXHAUSTED_NOTICE =
            "Em đã hết lưu lượng rồi anh ơi, em chuyển qua trạng thái bình thường đây. Em mệt rồi, em nghỉ ngơi xíu nhé..."
        const val TIRED_CONSENT_PROMPT =
            "Em vẫn đang mệt, anh có muốn tâm sự với em gái em không?"
        const val LITTLE_SISTER_GREETING =
            "Dạ em gái Na Na đây anh ơi! Chị Na Na đang nghỉ một xíu, để em tâm sự với anh nha!"
        const val DECLINED_GOODBYE =
            "Dạ vâng, vậy khi nào anh cần cứ gọi em nha."
    }
}
