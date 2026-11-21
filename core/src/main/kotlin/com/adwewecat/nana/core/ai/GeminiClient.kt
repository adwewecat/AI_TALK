package com.adwewecat.nana.core.ai

import com.adwewecat.nana.core.model.ApiKeyEntry
import com.adwewecat.nana.core.model.DeviceCommand
import com.adwewecat.nana.core.model.ProviderType
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient

/**
 * Gemini client supporting both Vertex AI Express (`AQ.` keys on `aiplatform.googleapis.com`)
 * and Google AI Studio (`AIza` / free keys on `generativelanguage.googleapis.com`),
 * plus Gemini neural audio TTS generation.
 */
class GeminiClient(
    private val httpClient: OkHttpClient,
    private val vertexBaseUrl: String = "https://aiplatform.googleapis.com",
    private val aiStudioBaseUrl: String = "https://generativelanguage.googleapis.com",
) : LlmProviderClient {

    override suspend fun generate(key: ApiKeyEntry, request: LlmRequest): LlmResult {
        val trimmedKey = key.apiKey.trim()
        if (trimmedKey.isEmpty()) {
            return LlmResult.InvalidKey(httpCode = 401, detail = "Missing Gemini API key")
        }

        val payload = buildGeneratePayload(request)
        val url = resolveGenerateUrl(key = key, modelId = key.modelId)
        val outcome = LlmHttpSupport.postJson(
            client = httpClient,
            url = url,
            headers = mapOf("x-goog-api-key" to trimmedKey),
            payload = payload,
            apiKeyToRedact = trimmedKey,
        )

        // Newer Gemini 3.8 / 4.x models on Vertex AI Express (`AQ.`) may live under `v1beta1` before `v1`.
        if (outcome is HttpCallResult.Response &&
            outcome.code == 404 &&
            isVertexExpressKey(key)
        ) {
            val betaUrl = resolveGenerateUrl(key = key, modelId = key.modelId, vertexApiVersion = "v1beta1")
            val betaOutcome = LlmHttpSupport.postJson(
                client = httpClient,
                url = betaUrl,
                headers = mapOf("x-goog-api-key" to trimmedKey),
                payload = payload,
                apiKeyToRedact = trimmedKey,
            )
            return when (betaOutcome) {
                is HttpCallResult.Failure -> LlmResult.TransientError(betaOutcome.detail)
                is HttpCallResult.Response -> parseGenerateOutcome(betaOutcome, trimmedKey)
            }
        }

        return when (outcome) {
            is HttpCallResult.Failure -> LlmResult.TransientError(outcome.detail)
            is HttpCallResult.Response -> parseGenerateOutcome(outcome, trimmedKey)
        }
    }

    /** Calls Gemini multimodal audio output to synthesize 24kHz PCM speech. */
    suspend fun synthesizeSpeech(
        key: ApiKeyEntry,
        text: String,
        styleInstruction: String,
        voiceName: String = "Aoede",
        ttsModelId: String = "gemini-2.5-flash-preview-tts",
    ): TtsResult {
        val trimmedKey = key.apiKey.trim()
        if (trimmedKey.isEmpty()) {
            return TtsResult.Error("Missing Gemini API key")
        }

        val sanitizedText = TtsTextSanitizer.sanitizeForSpeech(text)
        if (sanitizedText.isBlank()) {
            return TtsResult.Error("Empty text for TTS")
        }

        val promptText = if (styleInstruction.isNotBlank()) {
            "${styleInstruction.trim()}\n$sanitizedText"
        } else {
            sanitizedText
        }
        val payload = buildJsonObject {
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        addJsonObject { put("text", promptText) }
                    }
                }
            }
            putJsonObject("generationConfig") {
                putJsonArray("responseModalities") { add(JsonPrimitive("AUDIO")) }
                putJsonObject("speechConfig") {
                    putJsonObject("voiceConfig") {
                        putJsonObject("prebuiltVoiceConfig") {
                            put("voiceName", voiceName)
                        }
                    }
                }
            }
        }

        val primaryResult = postTtsPayload(key, trimmedKey, ttsModelId, payload)
        // Vertex AI Express (`AQ.`) often exposes GA `gemini-2.5-flash-tts` instead of the preview suffix.
        if (primaryResult is TtsResult.Unsupported &&
            isVertexExpressKey(key) &&
            ttsModelId.equals(DEFAULT_PREVIEW_TTS_MODEL, ignoreCase = true)
        ) {
            return postTtsPayload(key, trimmedKey, VERTEX_GA_TTS_MODEL, payload)
        }
        return primaryResult
    }

    private suspend fun postTtsPayload(
        key: ApiKeyEntry,
        trimmedKey: String,
        modelId: String,
        payload: JsonObject,
    ): TtsResult {
        val url = resolveGenerateUrl(key = key, modelId = modelId)
        val outcome = LlmHttpSupport.postJson(
            client = httpClient,
            url = url,
            headers = mapOf("x-goog-api-key" to trimmedKey),
            payload = payload,
            apiKeyToRedact = trimmedKey,
        )

        return when (outcome) {
            is HttpCallResult.Failure -> TtsResult.Error(outcome.detail)
            is HttpCallResult.Response -> parseTtsOutcome(outcome, trimmedKey)
        }
    }

    private fun isVertexExpressKey(key: ApiKeyEntry): Boolean {
        val trimmedKey = key.apiKey.trim()
        return when {
            trimmedKey.startsWith("AQ.") -> true
            trimmedKey.startsWith("AIza") -> false
            else -> key.provider == ProviderType.GEMINI_VERTEX_EXPRESS
        }
    }

    private fun resolveGenerateUrl(
        key: ApiKeyEntry,
        modelId: String,
        vertexApiVersion: String = "v1",
    ): String {
        val useVertexExpress = isVertexExpressKey(key)
        val sanitizedModel = modelId.trim()
        return if (useVertexExpress) {
            "${vertexBaseUrl.trimEnd('/')}/$vertexApiVersion/publishers/google/models/$sanitizedModel:generateContent"
        } else {
            "${aiStudioBaseUrl.trimEnd('/')}/v1beta/models/$sanitizedModel:generateContent"
        }
    }

    private fun buildGeneratePayload(request: LlmRequest): JsonObject = buildJsonObject {
        if (request.systemPrompt.isNotBlank()) {
            putJsonObject("systemInstruction") {
                putJsonArray("parts") {
                    addJsonObject { put("text", request.systemPrompt) }
                }
            }
        }

        putJsonArray("contents") {
            request.history.forEach { message ->
                val mappedRole = LlmHttpSupport.mapHistoryRole(message.role, assistantRoleName = "model")
                if (mappedRole != null && message.content.isNotBlank()) {
                    addJsonObject {
                        put("role", mappedRole)
                        putJsonArray("parts") {
                            addJsonObject { put("text", message.content) }
                        }
                    }
                }
            }

            val hasUserText = !request.userText.isNullOrBlank()
            val userAudio = request.userAudio
            if (hasUserText || userAudio != null) {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        if (hasUserText) {
                            addJsonObject { put("text", request.userText) }
                        }
                        if (userAudio != null) {
                            addJsonObject {
                                putJsonObject("inlineData") {
                                    put("mimeType", userAudio.mimeType)
                                    put("data", Base64.getEncoder().encodeToString(userAudio.bytes))
                                }
                            }
                        }
                    }
                }
            }
        }

        if (request.enableTools) {
            put("tools", DeviceToolSchemas.toGeminiToolsArray())
        }
    }

    private fun parseGenerateOutcome(outcome: HttpCallResult.Response, trimmedKey: String): LlmResult {
        val rawBody = outcome.bodyText()
        if (outcome.code !in 200..299) {
            return mapHttpError(outcome.code, rawBody, trimmedKey)
        }

        val parsed = try {
            LlmHttpSupport.json.decodeFromString<GeminiGenerateResponse>(rawBody)
        } catch (e: SerializationException) {
            return LlmResult.TransientError(
                LlmHttpSupport.redactKey(e.message ?: "Malformed Gemini JSON", trimmedKey),
            )
        }

        if (parsed.candidates.isEmpty() && parsed.error != null) {
            return mapHttpError(parsed.error.code ?: outcome.code, rawBody, trimmedKey)
        }

        val parts = parsed.candidates.firstOrNull()?.content?.parts.orEmpty()
        val textSegments = mutableListOf<String>()
        val commands = mutableListOf<DeviceCommand>()

        parts.forEach { part ->
            part.text?.trim()?.takeIf { it.isNotEmpty() }?.let(textSegments::add)
            part.functionCall?.let { call ->
                ToolSchemaParser.parseCommand(
                    name = call.name,
                    args = call.args ?: LlmHttpSupport.emptyJsonObject,
                )?.let(commands::add)
            }
        }

        return LlmResult.Success(
            replyText = textSegments.joinToString("\n"),
            toolCalls = commands,
        )
    }

    private fun parseTtsOutcome(outcome: HttpCallResult.Response, trimmedKey: String): TtsResult {
        val rawBody = outcome.bodyText()
        if (outcome.code !in 200..299) {
            val detail = LlmHttpSupport.formatHttpError(outcome.code, rawBody, trimmedKey)
            return when {
                outcome.code == 429 || hasQuotaMarker(rawBody) -> TtsResult.QuotaExceeded(detail)
                outcome.code == 400 || outcome.code == 404 -> TtsResult.Unsupported(detail)
                else -> TtsResult.Error(detail)
            }
        }

        val parsed = try {
            LlmHttpSupport.json.decodeFromString<GeminiGenerateResponse>(rawBody)
        } catch (e: SerializationException) {
            return TtsResult.Error(LlmHttpSupport.redactKey(e.message ?: "Malformed TTS JSON", trimmedKey))
        }

        val inlineData = parsed.candidates
            .asSequence()
            .flatMap { it.content?.parts.orEmpty().asSequence() }
            .mapNotNull { it.inlineData }
            .firstOrNull { !it.data.isNullOrBlank() }
            ?: return TtsResult.Unsupported("Gemini response did not contain audio inlineData")

        val pcmBytes = try {
            Base64.getDecoder().decode(inlineData.data!!.replace("\n", "").replace("\r", "").trim())
        } catch (e: IllegalArgumentException) {
            return TtsResult.Error("Invalid base64 audio payload: ${e.message}")
        }

        val mimeType = inlineData.mimeType?.takeIf { it.isNotBlank() } ?: DEFAULT_PCM_MIME
        val sampleRateHz = parseSampleRate(mimeType)
        return TtsResult.Success(
            TtsAudio(
                pcmBytes = pcmBytes,
                sampleRateHz = sampleRateHz,
                mimeType = mimeType,
            ),
        )
    }

    private fun mapHttpError(code: Int, rawBody: String, trimmedKey: String): LlmResult =
        LlmHttpSupport.mapHttpError(
            code = code,
            rawBody = rawBody,
            trimmedKey = trimmedKey,
            quotaMarkers = GEMINI_QUOTA_MARKERS,
        )

    private fun hasQuotaMarker(body: String): Boolean =
        LlmHttpSupport.containsAnyMarker(body, *GEMINI_QUOTA_MARKERS)

    private fun parseSampleRate(mimeType: String): Int =
        RATE_REGEX.find(mimeType)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: DEFAULT_SAMPLE_RATE_HZ

    @Serializable
    private data class GeminiGenerateResponse(
        val candidates: List<GeminiCandidate> = emptyList(),
        val error: GeminiErrorBody? = null,
    )

    @Serializable
    private data class GeminiCandidate(
        val content: GeminiContent? = null,
    )

    @Serializable
    private data class GeminiContent(
        val parts: List<GeminiPart> = emptyList(),
    )

    @Serializable
    private data class GeminiPart(
        val text: String? = null,
        val functionCall: GeminiFunctionCall? = null,
        val inlineData: GeminiInlineData? = null,
    )

    @Serializable
    private data class GeminiFunctionCall(
        val name: String = "",
        val args: JsonObject? = null,
    )

    @Serializable
    private data class GeminiInlineData(
        val mimeType: String? = null,
        val data: String? = null,
    )

    @Serializable
    private data class GeminiErrorBody(
        val code: Int? = null,
        val message: String? = null,
        val status: String? = null,
    )

    private companion object {
        const val DEFAULT_SAMPLE_RATE_HZ = 24_000
        const val DEFAULT_PCM_MIME = "audio/L16;codec=pcm;rate=24000"
        const val DEFAULT_PREVIEW_TTS_MODEL = "gemini-2.5-flash-preview-tts"
        const val VERTEX_GA_TTS_MODEL = "gemini-2.5-flash-tts"
        val RATE_REGEX = Regex("rate=(\\d+)", RegexOption.IGNORE_CASE)
        val GEMINI_QUOTA_MARKERS = arrayOf("RESOURCE_EXHAUSTED", "quota")
    }
}
