package com.adwewecat.nana.core.quota

import com.adwewecat.nana.core.model.ApiKeyEntry
import com.adwewecat.nana.core.model.KeyStatus

/**
 * Thread-safe pool of configured API keys with priority routing, cooldown auto-recovery,
 * and decoupled chat vs. TTS capability health tracking.
 */
class KeyPoolManager(
    initialKeys: List<ApiKeyEntry>,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val defaultCooldownMs: Long = 60_000L,
) {
    private val lock = Any()
    private var keys: List<ApiKeyEntry> = initialKeys.sortedBy { it.priority }

    /** Returns the highest-priority enabled healthy paid key eligible for chat. */
    fun nextHealthyPaidChatKey(): ApiKeyEntry? = synchronized(lock) {
        nextHealthyChatKeyLocked(isFreeTier = false)
    }

    /** Returns the highest-priority enabled healthy free-tier key eligible for chat. */
    fun nextHealthyFreeChatKey(): ApiKeyEntry? = synchronized(lock) {
        nextHealthyChatKeyLocked(isFreeTier = true)
    }

    /** Returns healthy enabled TTS-capable keys, prioritizing dedicated TTS-only entries first. */
    fun nextHealthyTtsKeys(): List<ApiKeyEntry> = synchronized(lock) {
        recoverExpiredCooldownsLocked(clockMs())
        keys
            .filter {
                it.enabled &&
                    it.ttsSupported &&
                    it.status == KeyStatus.HEALTHY &&
                    it.apiKey.isNotBlank()
            }
            .sortedWith(compareBy<ApiKeyEntry> { !it.isTtsOnly }.thenBy { it.priority })
    }

    /** Moves [keyId] into cooldown for chat requests until `clockMs() + defaultCooldownMs`. */
    fun markChatQuotaExhausted(keyId: String) = synchronized(lock) {
        val cooldownUntil = clockMs() + defaultCooldownMs
        updateKeyLocked(keyId) {
            it.copy(status = KeyStatus.COOLDOWN, cooldownUntilMs = cooldownUntil)
        }
    }

    /** Permanently marks [keyId] as invalid for requests until replaced or reconfigured. */
    fun markChatInvalid(keyId: String) = synchronized(lock) {
        updateKeyLocked(keyId) {
            it.copy(status = KeyStatus.INVALID, cooldownUntilMs = 0L)
        }
    }

    /**
     * Disables TTS routing on [keyId] without altering chat [KeyStatus] or cooldown state,
     * so TTS endpoint errors never bench a healthy chat key.
     */
    fun markTtsUnsupported(keyId: String) = synchronized(lock) {
        updateKeyLocked(keyId) {
            it.copy(ttsSupported = false)
        }
    }

    /** Clears cooldown state across all keys currently in [KeyStatus.COOLDOWN]. */
    fun resetAllCooldowns() = synchronized(lock) {
        keys = keys.map { entry ->
            if (entry.status == KeyStatus.COOLDOWN) {
                entry.copy(status = KeyStatus.HEALTHY, cooldownUntilMs = 0L)
            } else {
                entry
            }
        }
    }

    /** Replaces the managed key pool, sorted by ascending [ApiKeyEntry.priority]. */
    fun replaceKeys(keys: List<ApiKeyEntry>) = synchronized(lock) {
        this.keys = keys.sortedBy { it.priority }
    }

    /** Returns true if at least one enabled paid chat key is healthy (including recovered keys). */
    fun hasAnyHealthyPaidChatKey(): Boolean = nextHealthyPaidChatKey() != null

    /** Returns an immutable snapshot of the current key pool after auto-recovering expired cooldowns. */
    fun snapshot(): List<ApiKeyEntry> = synchronized(lock) {
        recoverExpiredCooldownsLocked(clockMs())
        keys.toList()
    }

    private fun nextHealthyChatKeyLocked(isFreeTier: Boolean): ApiKeyEntry? {
        recoverExpiredCooldownsLocked(clockMs())
        return keys.firstOrNull {
            it.enabled &&
                it.isFreeTier == isFreeTier &&
                !it.isTtsOnly &&
                it.status == KeyStatus.HEALTHY &&
                it.apiKey.isNotBlank()
        }
    }

    private fun recoverExpiredCooldownsLocked(nowMs: Long) {
        keys = keys.map { entry ->
            if (entry.status == KeyStatus.COOLDOWN && entry.cooldownUntilMs <= nowMs) {
                entry.copy(status = KeyStatus.HEALTHY, cooldownUntilMs = 0L)
            } else {
                entry
            }
        }
    }

    private inline fun updateKeyLocked(keyId: String, transform: (ApiKeyEntry) -> ApiKeyEntry) {
        keys = keys.map { entry ->
            if (entry.id == keyId) transform(entry) else entry
        }
    }
}
