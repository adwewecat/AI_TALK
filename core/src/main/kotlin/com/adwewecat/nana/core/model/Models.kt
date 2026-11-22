package com.adwewecat.nana.core.model

import kotlinx.serialization.Serializable

/** Supported LLM/TTS API provider backends. */
enum class ProviderType {
    GEMINI_VERTEX_EXPRESS,
    GEMINI_AI_STUDIO,
    CLAUDE,
    OPENAI,
    FREE_GEMINI,
    FREE_OPENAI_COMPAT,
}

/** Health state of an individual API key inside the rotation pool. */
enum class KeyStatus {
    HEALTHY,
    COOLDOWN,
    INVALID,
}

/** Active assistant persona mode. */
enum class PersonaMode {
    NANA,
    LITTLE_SISTER,
}

/** Quota and persona transition state across primary and fallback tiers. */
enum class QuotaState {
    ACTIVE_NANA,
    NANA_TIRED,
    NANA_TIRED_AWAITING_CONSENT,
    ACTIVE_LITTLE_SISTER,
}

/** Upload/sync lifecycle state for staged call recordings. */
enum class SyncStatus {
    PENDING,
    UPLOADING,
    SYNCED,
    FAILED,
}

/** Origin of a captured call recording artifact. */
enum class RecordingSource {
    ORIGINOS_NATIVE,
    IN_APP,
}

/** Classification of durable user memory facts extracted across sessions. */
enum class MemoryCategory {
    PREFERENCE,
    HABIT,
    RELATIONSHIP,
    SCHEDULE,
    EMOTION,
    FACT,
}

/** Configured API credential and routing metadata for an AI provider key. */
@Serializable
data class ApiKeyEntry(
    val id: String,
    val label: String,
    val provider: ProviderType,
    val apiKey: String,
    val modelId: String,
    val priority: Int,
    val isFreeTier: Boolean,
    val isTtsOnly: Boolean = false,
    val enabled: Boolean = true,
    val status: KeyStatus = KeyStatus.HEALTHY,
    val cooldownUntilMs: Long = 0L,
    val ttsSupported: Boolean = true,
)

/** Persistent conversation message synchronized between local Room storage and Drive. */
@Serializable
data class ChatMessage(
    val id: String,
    val sessionId: String,
    val role: String,
    val persona: PersonaMode,
    val content: String,
    val createdAtMs: Long,
    val synced: Boolean = false,
)

/** Durable user memory fact extracted from conversations. */
@Serializable
data class MemoryFact(
    val id: String,
    val category: MemoryCategory,
    val key: String,
    val value: String,
    val confidence: Float,
    val updatedAtMs: Long,
    val synced: Boolean = false,
)

/** Metadata and resumable Drive sync progress for a call recording. */
@Serializable
data class CallRecordingRecord(
    val id: String,
    val phoneNumber: String,
    val contactName: String,
    val source: RecordingSource,
    val startedAtMs: Long,
    val durationSec: Int,
    val sourceUri: String,
    val stagedPath: String?,
    val fileSizeBytes: Long,
    val mimeType: String,
    val driveFileId: String? = null,
    val resumeSessionUri: String? = null,
    val uploadedBytes: Long = 0L,
    val syncStatus: SyncStatus = SyncStatus.PENDING,
    val syncedAtMs: Long? = null,
    val aiSummary: String? = null,
)

/** Device-control intent parsed from assistant tool/command output. */
@Serializable
sealed class DeviceCommand {
    @Serializable
    data class OpenYouTubeSearch(val query: String, val autoPlay: Boolean = true) : DeviceCommand()

    @Serializable
    data object AnswerIncomingCall : DeviceCommand()

    @Serializable
    data object EndCall : DeviceCommand()

    @Serializable
    data class ToggleSpeaker(val enabled: Boolean) : DeviceCommand()

    @Serializable
    data class MakePhoneCall(val target: String) : DeviceCommand()

    @Serializable
    data class MakeZaloCall(val target: String) : DeviceCommand()

    @Serializable
    data class OpenApp(val appName: String) : DeviceCommand()

    @Serializable
    data class SetAlarm(val hour: Int, val minute: Int, val label: String = "") : DeviceCommand()

    @Serializable
    data class SetTimer(val seconds: Int, val label: String = "") : DeviceCommand()

    @Serializable
    data class AdjustVolume(
        val stream: String = "music",
        val direction: String = "up",
        val percent: Int? = null,
    ) : DeviceCommand()

    @Serializable
    data class ToggleFlashlight(val enabled: Boolean) : DeviceCommand()

    @Serializable
    data class SilentWebSearch(val query: String) : DeviceCommand()

    @Serializable
    data class WebSearch(val query: String) : DeviceCommand()

    @Serializable
    data object StopSession : DeviceCommand()
}

/** Outcome of executing a [DeviceCommand] on the Android device. */
data class ActionExecutionResult(
    val succeeded: Boolean,
    val spokenFeedback: String,
    val missingPermissionAction: String? = null,
)

/** Platform contract for executing parsed [DeviceCommand] actions. */
interface DeviceActionExecutor {
    suspend fun execute(command: DeviceCommand): ActionExecutionResult
}

/** Routes `AQ.` keys to Vertex AI Express and other Gemini keys to AI Studio. */
fun detectGeminiProvider(apiKey: String): ProviderType =
    if (apiKey.trim().startsWith("AQ.")) {
        ProviderType.GEMINI_VERTEX_EXPRESS
    } else {
        ProviderType.GEMINI_AI_STUDIO
    }
