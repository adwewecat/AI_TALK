package com.adwewecat.nana.core.ai

import com.adwewecat.nana.core.model.ApiKeyEntry
import com.adwewecat.nana.core.model.DeviceCommand
import java.util.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * OpenAI client supporting Chat Completions (`/v1/chat/completions`),
 * raw PCM neural TTS (`/v1/audio/speech`), and Whisper STT (`/v1/audio/transcriptions`).
 */
class OpenAiClient(
    private val httpClient: OkHttpClient,
    private val baseUrl: String = "https://api.openai.com",
) : LlmProviderClient {

    override suspend fun generate(key: ApiKeyEntry, request: LlmRequest): LlmResult {
        val trimmedKey = key.apiKey.trim()
        if (trimmedKey.isEmpty()) {
            return LlmResult.InvalidKey(httpCode = 401, detail = "Missing OpenAI API key")
        }

        val payload = buildChatPayload(modelId = key.modelId.trim(), request = request)
        val url = "${baseUrl.trimEnd('/')}/v1/chat/completions"
        val outcome = LlmHttpSupport.postJson(
            client = httpClient,
            url = url,
            headers = mapOf("Authorization" to "Bearer $trimmedKey"),
            payload = payload,
            apiKeyToRedact = trimmedKey,
        )

        return when (outcome) {
            is HttpCallResult.Failure -> LlmResult.TransientError(outcome.detail)
            is HttpCallResult.Response -> parseChatOutcome(outcome, trimmedKey)
        }
    }

    /** Synthesizes raw 24kHz PCM speech via OpenAI's `/v1/audio/speech` endpoint. */
    suspend fun synthesizeSpeech(
        key: ApiKeyEntry,
        text: String,
        voice: String = "nova",
        model: String = "tts-1",
    ): TtsResult {
        val trimmedKey = key.apiKey.trim()
        if (trimmedKey.isEmpty()) {
            return TtsResult.Error("Missing OpenAI API key")
        }

        val payload = buildJsonObject {
            put("model", model)
            put("input", text)
            put("voice", voice)
            put("response_format", "pcm")
        }
        val url = "${baseUrl.trimEnd('/')}/v1/audio/speech"
        val outcome = LlmHttpSupport.postJson(
            client = httpClient,
            url = url,
            headers = mapOf("Authorization" to "Bearer $trimmedKey"),
            payload = payload,
            apiKeyToRedact = trimmedKey,
        )

        return when (outcome) {
            is HttpCallResult.Failure -> TtsResult.Error(outcome.detail)
            is HttpCallResult.Response -> {
                if (outcome.code !in 200..299) {
                    val rawBody = outcome.bodyText()
                    val detail = LlmHttpSupport.formatHttpError(outcome.code, rawBody, trimmedKey)
                    when {
                        outcome.code == 429 || hasQuotaMarker(rawBody) -> TtsResult.QuotaExceeded(detail)
                        outcome.code == 400 || outcome.code == 404 -> TtsResult.Unsupported(detail)
                        else -> TtsResult.Error(detail)
                    }
                } else if (outcome.bodyBytes.isEmpty()) {
                    TtsResult.Error("Empty OpenAI TTS audio body")
                } else {
                    TtsResult.Success(
                        TtsAudio(
                            pcmBytes = outcome.bodyBytes,
                            sampleRateHz = DEFAULT_SAMPLE_RATE_HZ,
                            mimeType = DEFAULT_PCM_MIME,
                        ),
                    )
                }
            }
        }
    }

    /** Transcribes Vietnamese WAV speech via OpenAI Whisper (`whisper-1`). */
    suspend fun transcribeAudio(key: ApiKeyEntry, wavBytes: ByteArray): String? {
        val trimmedKey = key.apiKey.trim()
        if (trimmedKey.isEmpty() || wavBytes.isEmpty()) return null

        val multipartBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                "audio.wav",
                wavBytes.toRequestBody("audio/wav".toMediaType()),
            )
            .addFormDataPart("model", "whisper-1")
            .addFormDataPart("language", "vi")
            .build()

        val url = "${baseUrl.trimEnd('/')}/v1/audio/transcriptions"
        val outcome = LlmHttpSupport.executePost(
            client = httpClient,
            url = url,
            headers = mapOf("Authorization" to "Bearer $trimmedKey"),
            body = multipartBody,
            apiKeyToRedact = trimmedKey,
        )

        val response = outcome as? HttpCallResult.Response ?: return null
        if (response.code !in 200..299) return null

        return try {
            LlmHttpSupport.json
                .decodeFromString<WhisperResponse>(response.bodyText())
                .text
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        } catch (_: SerializationException) {
            null
        }
    }

    private fun buildChatPayload(modelId: String, request: LlmRequest): JsonObject = buildJsonObject {
        put("model", modelId)
        putJsonArray("messages") {
            if (request.systemPrompt.isNotBlank()) {
                addJsonObject {
                    put("role", "system")
                    put("content", request.systemPrompt)
                }
            }

            request.history.forEach { message ->
                val role = LlmHttpSupport.mapHistoryRole(message.role, assistantRoleName = "assistant")
                if (role != null && message.content.isNotBlank()) {
                    addJsonObject {
                        put("role", role)
                        put("content", message.content)
                    }
                }
            }

            val hasText = !request.userText.isNullOrBlank()
            val audio = request.userAudio
            if (audio != null) {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        if (hasText) {
                            addJsonObject {
                                put("type", "text")
                                put("text", request.userText)
                            }
                        }
                        addJsonObject {
                            put("type", "input_audio")
                            putJsonObject("input_audio") {
                                put("data", Base64.getEncoder().encodeToString(audio.bytes))
                                put("format", audioFormatFromMime(audio.mimeType))
                            }
                        }
                    }
                }
            } else if (hasText) {
                addJsonObject {
                    put("role", "user")
                    put("content", request.userText)
                }
            }
        }

        if (request.enableTools) {
            put("tools", DeviceToolSchemas.toOpenAiToolsArray())
        }
    }

    private fun parseChatOutcome(outcome: HttpCallResult.Response, trimmedKey: String): LlmResult {
        val rawBody = outcome.bodyText()
        if (outcome.code !in 200..299) {
            return LlmHttpSupport.mapHttpError(
                code = outcome.code,
                rawBody = rawBody,
                trimmedKey = trimmedKey,
                quotaMarkers = OPENAI_QUOTA_MARKERS,
            )
        }

        val parsed = try {
            LlmHttpSupport.json.decodeFromString<OpenAiChatResponse>(rawBody)
        } catch (e: SerializationException) {
            return LlmResult.TransientError(
                LlmHttpSupport.redactKey(e.message ?: "Malformed OpenAI JSON", trimmedKey),
            )
        }

        val message = parsed.choices.firstOrNull()?.message
        val replyText = message?.content?.trim().orEmpty()
        val commands = mutableListOf<DeviceCommand>()

        message?.toolCalls.orEmpty().forEach { toolCall ->
            val function = toolCall.function ?: return@forEach
            val argsObject = decodeArgumentsObject(function.arguments)
            ToolSchemaParser.parseCommand(function.name, argsObject)?.let(commands::add)
        }

        return LlmResult.Success(replyText = replyText, toolCalls = commands)
    }

    private fun decodeArgumentsObject(element: JsonElement?): JsonObject = when (element) {
        is JsonObject -> element
        is JsonPrimitive -> {
            val raw = element.content.trim()
            if (raw.isEmpty()) {
                LlmHttpSupport.emptyJsonObject
            } else {
                try {
                    LlmHttpSupport.json.parseToJsonElement(raw) as? JsonObject
                        ?: LlmHttpSupport.emptyJsonObject
                } catch (_: SerializationException) {
                    LlmHttpSupport.emptyJsonObject
                } catch (_: IllegalArgumentException) {
                    LlmHttpSupport.emptyJsonObject
                }
            }
        }

        else -> LlmHttpSupport.emptyJsonObject
    }

    private fun hasQuotaMarker(body: String): Boolean =
        LlmHttpSupport.containsAnyMarker(body, *OPENAI_QUOTA_MARKERS)

    private fun audioFormatFromMime(mimeType: String): String =
        when {
            mimeType.contains("mp3", ignoreCase = true) ||
                mimeType.contains("mpeg", ignoreCase = true) -> "mp3"
            else -> "wav"
        }

    @Serializable
    private data class OpenAiChatResponse(
        val choices: List<OpenAiChoice> = emptyList(),
    )

    @Serializable
    private data class OpenAiChoice(
        val message: OpenAiMessage? = null,
    )

    @Serializable
    private data class OpenAiMessage(
        val content: String? = null,
        @SerialName("tool_calls")
        val toolCalls: List<OpenAiToolCall> = emptyList(),
    )

    @Serializable
    private data class OpenAiToolCall(
        val function: OpenAiFunctionCall? = null,
    )

    @Serializable
    private data class OpenAiFunctionCall(
        val name: String = "",
        val arguments: JsonElement? = null,
    )

    @Serializable
    private data class WhisperResponse(
        val text: String? = null,
    )

    private companion object {
        const val DEFAULT_SAMPLE_RATE_HZ = 24_000
        const val DEFAULT_PCM_MIME = "audio/pcm;rate=24000"
        val OPENAI_QUOTA_MARKERS = arrayOf("insufficient_quota", "rate_limit", "quota")
    }
}
