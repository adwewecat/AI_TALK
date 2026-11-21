package com.adwewecat.nana.data.local

import com.adwewecat.nana.core.model.ApiKeyEntry
import com.adwewecat.nana.core.model.KeyStatus
import com.adwewecat.nana.core.model.ProviderType
import java.security.MessageDigest

/**
 * Seeds optional personal Gemini API keys supplied via gitignored `local.properties` -> `BuildConfig`
 * into [EncryptedApiKeyVault] once per unique seed bundle, while leaving CI/public builds empty
 * and preserving subsequent user edits or deletions.
 */
object LocalSeedKeyBootstrapper {

    private const val PRIMARY_SEED_ID = "seed-gemini-pro-1"
    private const val BACKUP_SEED_ID = "seed-gemini-pro-2"

    fun applyIfNeeded(
        vault: EncryptedApiKeyVault,
        primaryKey: String,
        backupKey: String,
        primaryModelId: String,
        backupModelId: String,
    ) {
        val cleanPrimary = primaryKey.trim()
        val cleanBackup = backupKey.trim()
        if (cleanPrimary.isEmpty() && cleanBackup.isEmpty()) {
            return
        }

        val resolvedPrimaryModel = primaryModelId.trim().ifEmpty { "gemini-3-flash-preview" }
        val resolvedBackupModel = backupModelId.trim().ifEmpty { "gemini-3.5-flash-lite" }
        val bundleFingerprint = sha256("$cleanPrimary|$cleanBackup|$resolvedPrimaryModel|$resolvedBackupModel")
        if (vault.loadSeedBundleFingerprint() == bundleFingerprint) {
            return
        }

        val current = vault.loadKeys().toMutableList()
        if (cleanPrimary.isNotEmpty()) {
            upsertSeedEntry(
                entries = current,
                seed = ApiKeyEntry(
                    id = PRIMARY_SEED_ID,
                    label = "Gemini Pro #1",
                    provider = ProviderType.GEMINI_AI_STUDIO,
                    apiKey = cleanPrimary,
                    modelId = resolvedPrimaryModel,
                    priority = 1,
                    isFreeTier = false,
                    enabled = true,
                    status = KeyStatus.HEALTHY,
                    cooldownUntilMs = 0L,
                    ttsSupported = true,
                ),
            )
        }
        if (cleanBackup.isNotEmpty()) {
            upsertSeedEntry(
                entries = current,
                seed = ApiKeyEntry(
                    id = BACKUP_SEED_ID,
                    label = "Gemini Pro #2 (Backup)",
                    provider = ProviderType.GEMINI_AI_STUDIO,
                    apiKey = cleanBackup,
                    modelId = resolvedBackupModel,
                    priority = 2,
                    isFreeTier = false,
                    enabled = true,
                    status = KeyStatus.HEALTHY,
                    cooldownUntilMs = 0L,
                    ttsSupported = true,
                ),
            )
        }

        vault.saveKeys(current.sortedBy { it.priority })
        vault.saveSeedBundleFingerprint(bundleFingerprint)
    }

    private fun upsertSeedEntry(entries: MutableList<ApiKeyEntry>, seed: ApiKeyEntry) {
        val bySecret = entries.indexOfFirst { it.apiKey.trim() == seed.apiKey }
        if (bySecret >= 0) {
            val existing = entries[bySecret]
            val shouldUpgradeDeprecatedModel =
                existing.modelId.isBlank() || existing.modelId.startsWith("gemini-2.5", ignoreCase = true)
            entries[bySecret] = existing.copy(
                provider = seed.provider,
                modelId = if (shouldUpgradeDeprecatedModel) seed.modelId else existing.modelId,
                status = KeyStatus.HEALTHY,
                cooldownUntilMs = 0L,
                ttsSupported = true,
            )
            return
        }
        val byId = entries.indexOfFirst { it.id == seed.id }
        if (byId >= 0) {
            entries[byId] = seed
        } else {
            entries.add(seed)
        }
    }

    private fun sha256(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
