package com.adwewecat.nana.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.adwewecat.nana.core.model.ApiKeyEntry
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Persisted user preferences for wake-word detection, voice, call capture, and retention. */
@Serializable
data class AppSettings(
    val wakeWordEnabled: Boolean = true,
    val screenOffWakeLock: Boolean = false,
    val silenceTimeoutSec: Int = 15,
    val preferredGeminiVoice: String = "Aoede",
    val emGaiHandoffEnabled: Boolean = true,
    val originOsRecordingFolderUri: String? = null,
    val inAppCallRecorderFallback: Boolean = false,
    val aiCallSummaryEnabled: Boolean = false,
    val keepRecordingsForever: Boolean = false,
    val recordingRetentionDays: Int = 7,
    val autoAnswerCalls: Boolean = false,
    val announceCallerAloud: Boolean = true,
)

/**
 * Keystore-backed vault storing API keys, user settings, and serialized AppAuth state.
 * Accepts any [SharedPreferences] implementation so JVM/Robolectric tests can inject standard prefs.
 */
class EncryptedApiKeyVault(
    private val prefs: SharedPreferences,
) {
    /** Loads configured API keys from encrypted storage, returning an empty list if absent or unreadable. */
    fun loadKeys(): List<ApiKeyEntry> {
        val raw = prefs.getString(KEY_API_KEYS, null)?.takeIf { it.isNotBlank() } ?: return emptyList()
        return try {
            json.decodeFromString<List<ApiKeyEntry>>(raw)
        } catch (_: SerializationException) {
            emptyList()
        }
    }

    /** Persists the full API key pool list to encrypted storage. */
    fun saveKeys(keys: List<ApiKeyEntry>) {
        prefs.edit().putString(KEY_API_KEYS, json.encodeToString(keys)).apply()
    }

    /** Loads persisted [AppSettings], falling back to defaults when unset or corrupted. */
    fun loadSettings(): AppSettings {
        val raw = prefs.getString(KEY_APP_SETTINGS, null)?.takeIf { it.isNotBlank() } ?: return AppSettings()
        return try {
            json.decodeFromString<AppSettings>(raw)
        } catch (_: SerializationException) {
            AppSettings()
        }
    }

    /** Persists [AppSettings] to encrypted storage. */
    fun saveSettings(settings: AppSettings) {
        prefs.edit().putString(KEY_APP_SETTINGS, json.encodeToString(settings)).apply()
    }

    /** Loads serialized AppAuth `AuthState` JSON, or `null` when signed out. */
    fun loadAuthStateJson(): String? =
        prefs.getString(KEY_AUTH_STATE_JSON, null)?.takeIf { it.isNotBlank() }

    /** Saves or clears serialized AppAuth `AuthState` JSON. */
    fun saveAuthStateJson(json: String?) {
        prefs.edit().apply {
            if (json.isNullOrBlank()) {
                remove(KEY_AUTH_STATE_JSON)
            } else {
                putString(KEY_AUTH_STATE_JSON, json)
            }
        }.apply()
    }

    /** Returns whether the persisted Drive OAuth session requires interactive re-authentication. */
    fun loadNeedsReauth(): Boolean = prefs.getBoolean(KEY_AUTH_NEEDS_REAUTH, false)

    /** Persists the Drive OAuth re-authentication flag without touching keys or settings. */
    fun saveNeedsReauth(needsReauth: Boolean) {
        prefs.edit().putBoolean(KEY_AUTH_NEEDS_REAUTH, needsReauth).apply()
    }

    /** Returns the fingerprint of the last local build seed bundle applied to this vault. */
    fun loadSeedBundleFingerprint(): String? =
        prefs.getString(KEY_SEED_BUNDLE_FINGERPRINT, null)?.takeIf { it.isNotBlank() }

    /** Records that a local build seed bundle has been applied so user edits/deletions persist. */
    fun saveSeedBundleFingerprint(fingerprint: String) {
        prefs.edit().putString(KEY_SEED_BUNDLE_FINGERPRINT, fingerprint).apply()
    }

    companion object {
        private const val PREFS_FILE_NAME = "nana_encrypted_vault"
        private const val KEY_API_KEYS = "api_keys_json"
        private const val KEY_APP_SETTINGS = "app_settings_json"
        private const val KEY_AUTH_STATE_JSON = "drive_auth_state_json"
        private const val KEY_AUTH_NEEDS_REAUTH = "drive_auth_needs_reauth"
        private const val KEY_SEED_BUNDLE_FINGERPRINT = "local_seed_bundle_fingerprint"

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /** Creates an Android Keystore-backed [EncryptedApiKeyVault] using AES-256 GCM/SIV encryption. */
        fun create(context: Context): EncryptedApiKeyVault {
            val appContext = context.applicationContext
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val encryptedPrefs = EncryptedSharedPreferences.create(
                appContext,
                PREFS_FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            return EncryptedApiKeyVault(encryptedPrefs)
        }
    }
}
