package com.adwewecat.nana.core.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsSmokeTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun detectGeminiProvider_routesVertexExpressAndAiStudioKeys() {
        assertEquals(
            ProviderType.GEMINI_VERTEX_EXPRESS,
            detectGeminiProvider("AQ.example123")
        )
        assertEquals(
            ProviderType.GEMINI_VERTEX_EXPRESS,
            detectGeminiProvider("  AQ.trimmed456  ")
        )
        assertEquals(
            ProviderType.GEMINI_AI_STUDIO,
            detectGeminiProvider("AIzaExample123")
        )
        assertEquals(
            ProviderType.GEMINI_AI_STUDIO,
            detectGeminiProvider("unknown-format-key")
        )
    }

    @Test
    fun apiKeyEntry_jsonRoundTrip() {
        val original = ApiKeyEntry(
            id = "key-1",
            label = "Primary Vertex Key",
            provider = ProviderType.GEMINI_VERTEX_EXPRESS,
            apiKey = "AQ.example-secret",
            modelId = "gemini-2.5-flash",
            priority = 1,
            isFreeTier = false,
            isTtsOnly = false,
            enabled = true,
            status = KeyStatus.HEALTHY,
            cooldownUntilMs = 0L,
            ttsSupported = true
        )

        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<ApiKeyEntry>(encoded)

        assertEquals(original, decoded)
    }

    @Test
    fun chatMessage_jsonRoundTrip() {
        val original = ChatMessage(
            id = "msg-1",
            sessionId = "session-1",
            role = "assistant",
            persona = PersonaMode.NANA,
            content = "Chào anh, Na Na nghe nè!",
            createdAtMs = 1_760_000_000_000L,
            synced = false
        )

        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<ChatMessage>(encoded)

        assertEquals(original, decoded)
    }

    @Test
    fun memoryFact_jsonRoundTrip() {
        val original = MemoryFact(
            id = "fact-1",
            category = MemoryCategory.PREFERENCE,
            key = "music_artist",
            value = "Lofi Chill",
            confidence = 0.92f,
            updatedAtMs = 1_760_000_000_500L,
            synced = true
        )

        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<MemoryFact>(encoded)

        assertEquals(original, decoded)
    }

    @Test
    fun callRecordingRecord_jsonRoundTrip() {
        val original = CallRecordingRecord(
            id = "rec-1",
            phoneNumber = "+84901234567",
            contactName = "Minh",
            source = RecordingSource.ORIGINOS_NATIVE,
            startedAtMs = 1_760_000_001_000L,
            durationSec = 95,
            sourceUri = "content://media/external/audio/media/42",
            stagedPath = "/data/user/0/com.adwewecat.nana/files/staging/rec-1.m4a",
            fileSizeBytes = 1_048_576L,
            mimeType = "audio/mp4",
            driveFileId = null,
            resumeSessionUri = "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable",
            uploadedBytes = 262_144L,
            syncStatus = SyncStatus.UPLOADING,
            syncedAtMs = null,
            aiSummary = null
        )

        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<CallRecordingRecord>(encoded)

        assertEquals(original, decoded)
    }

    @Test
    fun deviceCommand_polymorphicJsonRoundTrip() {
        val commands: List<DeviceCommand> = listOf(
            DeviceCommand.OpenYouTubeSearch(query = "nhạc chill", autoPlay = true),
            DeviceCommand.AnswerIncomingCall,
            DeviceCommand.EndCall,
            DeviceCommand.ToggleSpeaker(enabled = true),
            DeviceCommand.MakePhoneCall(target = "Mẹ"),
            DeviceCommand.OpenApp(appName = "Zalo"),
            DeviceCommand.SetAlarm(hour = 7, minute = 30, label = "Dậy đi làm"),
            DeviceCommand.SetTimer(seconds = 300, label = "Úp mì"),
            DeviceCommand.AdjustVolume(stream = "music", direction = "up", percent = 80),
            DeviceCommand.ToggleFlashlight(enabled = false),
            DeviceCommand.WebSearch(query = "thời tiết Hà Nội"),
            DeviceCommand.StopSession
        )

        val encoded = json.encodeToString(commands)
        val decoded = json.decodeFromString<List<DeviceCommand>>(encoded)

        assertEquals(commands, decoded)
    }

    @Test
    fun quotaStateAndActionExecutionResult_existWithExpectedContract() {
        val states = QuotaState.entries
        assertEquals(4, states.size)

        val result = ActionExecutionResult(
            succeeded = true,
            spokenFeedback = "Đang mở YouTube",
            missingPermissionAction = null
        )
        assertTrue(result.succeeded)
        assertNull(result.missingPermissionAction)
    }
}
