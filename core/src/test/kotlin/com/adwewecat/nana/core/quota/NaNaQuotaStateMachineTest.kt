package com.adwewecat.nana.core.quota

import com.adwewecat.nana.core.model.ApiKeyEntry
import com.adwewecat.nana.core.model.KeyStatus
import com.adwewecat.nana.core.model.PersonaMode
import com.adwewecat.nana.core.model.ProviderType
import com.adwewecat.nana.core.model.QuotaState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NaNaQuotaStateMachineTest {

    private fun paidKey(
        id: String,
        priority: Int,
        isTtsOnly: Boolean = false,
        ttsSupported: Boolean = true,
        enabled: Boolean = true,
    ) = ApiKeyEntry(
        id = id,
        label = "Paid $id",
        provider = ProviderType.GEMINI_VERTEX_EXPRESS,
        apiKey = "AQ.synthetic-test-key-$id",
        modelId = "gemini-2.5-flash",
        priority = priority,
        isFreeTier = false,
        isTtsOnly = isTtsOnly,
        enabled = enabled,
        status = KeyStatus.HEALTHY,
        cooldownUntilMs = 0L,
        ttsSupported = ttsSupported,
    )

    private fun freeKey(
        id: String,
        priority: Int,
        isTtsOnly: Boolean = false,
    ) = ApiKeyEntry(
        id = id,
        label = "Free $id",
        provider = ProviderType.FREE_GEMINI,
        apiKey = "AIzaSyntheticFreeKey-$id",
        modelId = "gemini-2.5-flash-lite",
        priority = priority,
        isFreeTier = true,
        isTtsOnly = isTtsOnly,
        enabled = true,
        status = KeyStatus.HEALTHY,
        cooldownUntilMs = 0L,
        ttsSupported = true,
    )

    @Test
    fun paidKeysFailoverInPriorityOrder() {
        var nowMs = 1_000_000L
        val pool = KeyPoolManager(
            initialKeys = listOf(
                paidKey(id = "paid-2", priority = 2),
                paidKey(id = "paid-1", priority = 1),
                freeKey(id = "free-1", priority = 0),
            ),
            clockMs = { nowMs },
            defaultCooldownMs = 3_600_000L,
        )

        assertEquals("paid-1", pool.nextHealthyPaidChatKey()?.id)
        assertEquals("free-1", pool.nextHealthyFreeChatKey()?.id)

        pool.markChatQuotaExhausted("paid-1")
        assertEquals("paid-2", pool.nextHealthyPaidChatKey()?.id)
        assertTrue(pool.hasAnyHealthyPaidChatKey())

        pool.markChatQuotaExhausted("paid-2")
        assertNull(pool.nextHealthyPaidChatKey())
        assertFalse(pool.hasAnyHealthyPaidChatKey())

        // Advancing clock past cooldown restores paid-1 as highest-priority key.
        nowMs += 3_600_000L
        assertTrue(pool.hasAnyHealthyPaidChatKey())
        assertEquals("paid-1", pool.nextHealthyPaidChatKey()?.id)
    }

    @Test
    fun ttsUnsupportedDoesNotBenchChatKey() {
        val nowMs = 5_000L
        val pool = KeyPoolManager(
            initialKeys = listOf(
                paidKey(id = "chat-primary", priority = 1, isTtsOnly = false),
                paidKey(id = "tts-dedicated", priority = 10, isTtsOnly = true),
                freeKey(id = "free-tts", priority = 2, isTtsOnly = false),
            ),
            clockMs = { nowMs },
        )

        // TTS-only keys must be ordered before general keys, then by priority.
        assertEquals(
            listOf("tts-dedicated", "chat-primary", "free-tts"),
            pool.nextHealthyTtsKeys().map { it.id },
        )

        // Mark primary chat key unsupported for TTS (e.g. HTTP 400/404/429 on TTS endpoint).
        pool.markTtsUnsupported("chat-primary")

        val snapshotPrimary = pool.snapshot().first { it.id == "chat-primary" }
        assertEquals(KeyStatus.HEALTHY, snapshotPrimary.status)
        assertEquals(0L, snapshotPrimary.cooldownUntilMs)
        assertFalse(snapshotPrimary.ttsSupported)

        // Chat routing MUST still select chat-primary as healthy.
        assertEquals("chat-primary", pool.nextHealthyPaidChatKey()?.id)
        assertTrue(pool.hasAnyHealthyPaidChatKey())

        // TTS routing skips chat-primary while keeping tts-dedicated and free-tts.
        assertEquals(
            listOf("tts-dedicated", "free-tts"),
            pool.nextHealthyTtsKeys().map { it.id },
        )
    }

    @Test
    fun allPaidExhaustedTriggersTiredMessageAndEmGaiConsentFlow() {
        val pool = KeyPoolManager(
            initialKeys = listOf(
                paidKey(id = "paid-1", priority = 1),
                freeKey(id = "free-1", priority = 1),
            ),
            clockMs = { 10_000L },
        )
        val machine = NaNaQuotaStateMachine(pool)

        assertEquals(QuotaState.ACTIVE_NANA, machine.state)
        assertEquals(PersonaMode.NANA, machine.activePersona)

        pool.markChatQuotaExhausted("paid-1")
        val exhaustedNotice = machine.onAllPaidChatKeysExhausted()

        assertEquals(
            "Em đã hết lưu lượng rồi anh ơi, em chuyển qua trạng thái bình thường đây. Em mệt rồi, em nghỉ ngơi xíu nhé...",
            exhaustedNotice,
        )
        assertEquals(QuotaState.NANA_TIRED, machine.state)
        assertEquals(PersonaMode.NANA, machine.activePersona)

        val wakeResult = machine.onWakeWord()
        assertEquals(QuotaState.NANA_TIRED_AWAITING_CONSENT, wakeResult.state)
        assertEquals(PersonaMode.NANA, wakeResult.persona)
        assertEquals(
            "Em vẫn đang mệt, anh có muốn tâm sự với em gái em không?",
            wakeResult.spokenPrompt,
        )
        assertTrue(wakeResult.awaitConsent)
    }

    @Test
    fun sayingOkSwitchesToLittleSister() {
        val affirmativeInputs = listOf(
            "ok",
            "ô kê",
            "ừ",
            "ok em ơi",
            "được",
            "đồng ý",
            "có",
            "nói chuyện đi",
        )

        affirmativeInputs.forEach { utterance ->
            assertEquals(
                "Expected AFFIRMATIVE for '$utterance'",
                ConsentDecision.AFFIRMATIVE,
                VietnameseConsentParser.parse(utterance),
            )

            val pool = KeyPoolManager(
                initialKeys = listOf(paidKey(id = "paid-1", priority = 1)),
                clockMs = { 10_000L },
            )
            pool.markChatQuotaExhausted("paid-1")
            val machine = NaNaQuotaStateMachine(pool)
            machine.onAllPaidChatKeysExhausted()

            val wake = machine.onWakeWord()
            assertTrue(wake.awaitConsent)

            val consentResult = machine.onConsentUtterance(utterance)
            assertEquals(QuotaState.ACTIVE_LITTLE_SISTER, consentResult.state)
            assertEquals(PersonaMode.LITTLE_SISTER, consentResult.persona)
            assertEquals(NaNaQuotaStateMachine.LITTLE_SISTER_GREETING, consentResult.spokenPrompt)
            assertFalse(consentResult.awaitConsent)
            assertEquals(QuotaState.ACTIVE_LITTLE_SISTER, machine.state)
            assertEquals(PersonaMode.LITTLE_SISTER, machine.activePersona)

            // Ending session while paid keys remain exhausted returns to NANA_TIRED.
            machine.onSessionEnded()
            assertEquals(QuotaState.NANA_TIRED, machine.state)
            assertEquals(PersonaMode.NANA, machine.activePersona)
        }
    }

    @Test
    fun negativeOrAmbiguousDeclinesSafely() {
        val negativeInputs = listOf("không", "thôi", "không ok", "không đâu", "tắt Na Na", "nghỉ thôi", "dừng lại", "tạm biệt")
        negativeInputs.forEach { utterance ->
            assertEquals(
                "Expected NEGATIVE for '$utterance'",
                ConsentDecision.NEGATIVE,
                VietnameseConsentParser.parse(utterance),
            )

            val pool = KeyPoolManager(
                initialKeys = listOf(paidKey(id = "paid-1", priority = 1)),
                clockMs = { 20_000L },
            )
            pool.markChatQuotaExhausted("paid-1")
            val machine = NaNaQuotaStateMachine(pool)
            machine.onAllPaidChatKeysExhausted()
            machine.onWakeWord()

            val result = machine.onConsentUtterance(utterance)
            assertEquals(QuotaState.NANA_TIRED, result.state)
            assertEquals(PersonaMode.NANA, result.persona)
            assertEquals(NaNaQuotaStateMachine.DECLINED_GOODBYE, result.spokenPrompt)
            assertFalse(result.awaitConsent)
        }

        // Substrings inside unrelated words must NOT falsely trigger affirmative/negative.
        val ambiguousInputs = listOf("tokyo", "con mèo", "hôm nay trời đẹp", "")
        ambiguousInputs.forEach { utterance ->
            assertEquals(
                "Expected UNKNOWN for '$utterance'",
                ConsentDecision.UNKNOWN,
                VietnameseConsentParser.parse(utterance),
            )
        }

        val pool = KeyPoolManager(
            initialKeys = listOf(paidKey(id = "paid-1", priority = 1)),
            clockMs = { 20_000L },
        )
        pool.markChatQuotaExhausted("paid-1")
        val machine = NaNaQuotaStateMachine(pool)
        machine.onAllPaidChatKeysExhausted()
        machine.onWakeWord()

        val unknownResult = machine.onConsentUtterance("tokyo")
        assertEquals(QuotaState.NANA_TIRED_AWAITING_CONSENT, unknownResult.state)
        assertEquals(PersonaMode.NANA, unknownResult.persona)
        assertEquals(NaNaQuotaStateMachine.TIRED_CONSENT_PROMPT, unknownResult.spokenPrompt)
        assertTrue(unknownResult.awaitConsent)

        val timeoutResult = machine.onConsentTimeout()
        assertEquals(QuotaState.NANA_TIRED, timeoutResult.state)
        assertEquals(PersonaMode.NANA, timeoutResult.persona)
        assertEquals(NaNaQuotaStateMachine.DECLINED_GOODBYE, timeoutResult.spokenPrompt)
        assertFalse(timeoutResult.awaitConsent)
    }

    @Test
    fun paidKeyRecoveryRestoresNaNa() {
        var nowMs = 100_000L
        val pool = KeyPoolManager(
            initialKeys = listOf(paidKey(id = "paid-1", priority = 1)),
            clockMs = { nowMs },
            defaultCooldownMs = 60_000L,
        )
        val machine = NaNaQuotaStateMachine(pool)

        pool.markChatQuotaExhausted("paid-1")
        machine.onAllPaidChatKeysExhausted()
        machine.onWakeWord()
        val sisterSession = machine.onConsentUtterance("ok")
        assertEquals(QuotaState.ACTIVE_LITTLE_SISTER, sisterSession.state)

        // Advance fake clock past cooldown while session ends -> restores ACTIVE_NANA.
        nowMs += 60_000L
        machine.onSessionEnded()
        assertEquals(QuotaState.ACTIVE_NANA, machine.state)
        assertEquals(PersonaMode.NANA, machine.activePersona)

        // Also test wake-word recovery from NANA_TIRED directly.
        pool.markChatQuotaExhausted("paid-1")
        machine.onAllPaidChatKeysExhausted()
        assertEquals(QuotaState.NANA_TIRED, machine.state)

        nowMs += 60_000L
        val recoveredWake = machine.onWakeWord()
        assertEquals(QuotaState.ACTIVE_NANA, recoveredWake.state)
        assertEquals(PersonaMode.NANA, recoveredWake.persona)
        assertNull(recoveredWake.spokenPrompt)
        assertFalse(recoveredWake.awaitConsent)
    }

    @Test
    fun invalidResetAndReplaceKeysRespectPoolRules() {
        val nowMs = 10_000L
        val pool = KeyPoolManager(
            initialKeys = listOf(
                paidKey(id = "paid-1", priority = 1),
                paidKey(id = "paid-2", priority = 2),
                paidKey(id = "disabled", priority = 0, enabled = false),
                paidKey(id = "blank", priority = 0).copy(apiKey = "   "),
            ),
            clockMs = { nowMs },
            defaultCooldownMs = 60_000L,
        )

        // Disabled and blank keys are never returned.
        assertEquals("paid-1", pool.nextHealthyPaidChatKey()?.id)

        // Mark paid-1 invalid and paid-2 exhausted; resetAllCooldowns must recover paid-2 but NOT paid-1.
        pool.markChatInvalid("paid-1")
        pool.markChatQuotaExhausted("paid-2")
        // markTtsUnsupported on a cooldown key must preserve COOLDOWN and cooldownUntilMs.
        pool.markTtsUnsupported("paid-2")
        val paid2DuringCooldown = pool.snapshot().first { it.id == "paid-2" }
        assertEquals(KeyStatus.COOLDOWN, paid2DuringCooldown.status)
        assertEquals(70_000L, paid2DuringCooldown.cooldownUntilMs)
        assertFalse(paid2DuringCooldown.ttsSupported)

        pool.resetAllCooldowns()
        assertEquals("paid-2", pool.nextHealthyPaidChatKey()?.id)
        assertEquals(KeyStatus.INVALID, pool.snapshot().first { it.id == "paid-1" }.status)

        // replaceKeys re-sorts by ascending priority.
        pool.replaceKeys(
            listOf(
                paidKey(id = "new-low", priority = 5),
                paidKey(id = "new-high", priority = 1),
            ),
        )
        assertEquals("new-high", pool.nextHealthyPaidChatKey()?.id)
        assertEquals(listOf("new-high", "new-low"), pool.snapshot().map { it.id })
    }
}
