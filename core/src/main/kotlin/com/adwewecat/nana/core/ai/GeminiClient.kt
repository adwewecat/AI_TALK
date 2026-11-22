package com.adwewecat.nana.core.ai

import com.adwewecat.nana.core.model.ApiKeyEntry
import com.adwewecat.nana.core.model.DeviceCommand
import com.adwewecat.nana.core.model.ProviderType
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
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
 * Gemini client supporting both Vertex AI Express (`aiplatform.googleapis.com`)
 * and Google AI Studio / Gemini Developer API (`generativelanguage.googleapis.com`, which in 2026
 * also issues `AQ.` keys alongside `AIza`), plus Gemini neural audio TTS generation.
 */
class GeminiClient(
    private val httpClient: OkHttpClient,
    private val vertexBaseUrl: String = "https://aiplatform.googleapis.com",
    private val aiStudioBaseUrl: String = "https://generativelanguage.googleapis.com",
) : LlmProviderClient {

    private val preferAiStudioKeyHashes = ConcurrentHashMap<String, Boolean>()

    override suspend fun generate(key: ApiKeyEntry, request: LlmRequest): LlmResult {
        val trimmedKey = key.apiKey.trim()
        if (trimmedKey.isEmpty()) {
            return LlmResult.InvalidKey(httpCode = 401, detail = "Missing Gemini API key")
        }

        val payload = buildGeneratePayload(request)
        val useVertex = shouldUseVertexExpress(key, trimmedKey)
        val outcome = postWithAutoEndpointRouting(
            trimmedKey = trimmedKey,
            modelId = key.modelId,
            payload = payload,
            useVertexInitially = useVertex,
            retryVertexV1Beta1On404 = true,
        )

        return when (outcome) {
            is HttpCallResult.Failure -> LlmResult.TransientError(outcome.detail)
            is HttpCallResult.Response -> parseGenerateOutcome(outcome, trimmedKey)
        }
    }

    /** Calls Gemini multimodal audio output to synthesize PCM speech (raw PCM or RIFF/WAV). */
    suspend fun synthesizeSpeech(
        key: ApiKeyEntry,
        text: String,
        styleInstruction: String,
        voiceName: String = "Aoede",
        ttsModelId: String = DEFAULT_PREVIEW_TTS_MODEL,
    ): TtsResult {
        val trimmedKey = key.apiKey.trim()
        if (trimmedKey.isEmpty()) {
            return TtsResult.Error("Missing Gemini API key")
        }

        val sanitizedText = TtsTextSanitizer.sanitizeForSpeech(text)
        if (sanitizedText.isBlank()) {
            return TtsResult.Error("Empty text for TTS")
        }

        // Send only pure spoken text: Gemini 3.8 TTS reads any prepended style prompt verbatim aloud.
        val payload = buildJsonObject {
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        addJsonObject { put("text", sanitizedText) }
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
        if (primaryResult is TtsResult.Unsupported) {
            // If Gemini 3.8 TTS is not available on a project/endpoint, fall back to 2.5 Flash Preview TTS.
            if (ttsModelId.equals(GEMINI_38_TTS_MODEL, ignoreCase = true)) {
                val previewFallback = postTtsPayload(key, trimmedKey, DEFAULT_PREVIEW_TTS_MODEL, payload)
                if (previewFallback !is TtsResult.Unsupported) {
                    return previewFallback
                }
            }
            // Vertex AI Express (`AQ.`) often exposes GA `gemini-2.5-flash-tts` instead of the preview suffix.
            if (shouldUseVertexExpress(key, trimmedKey) &&
                ttsModelId.equals(DEFAULT_PREVIEW_TTS_MODEL, ignoreCase = true)
            ) {
                return postTtsPayload(key, trimmedKey, VERTEX_GA_TTS_MODEL, payload)
            }
        }
        return primaryResult
    }

    private suspend fun postTtsPayload(
        key: ApiKeyEntry,
        trimmedKey: String,
        modelId: String,
        payload: JsonObject,
    ): TtsResult {
        val outcome = postWithAutoEndpointRouting(
            trimmedKey = trimmedKey,
            modelId = modelId,
            payload = payload,
            useVertexInitially = shouldUseVertexExpress(key, trimmedKey),
            retryVertexV1Beta1On404 = false,
        )

        return when (outcome) {
            is HttpCallResult.Failure -> TtsResult.Error(outcome.detail)
            is HttpCallResult.Response -> parseTtsOutcome(outcome, trimmedKey)
        }
    }

    private suspend fun postWithAutoEndpointRouting(
        trimmedKey: String,
        modelId: String,
        payload: JsonObject,
        useVertexInitially: Boolean,
        retryVertexV1Beta1On404: Boolean,
    ): HttpCallResult {
        val url = resolveGenerateUrl(useVertex = useVertexInitially, modelId = modelId)
        val outcome = LlmHttpSupport.postJson(
            client = httpClient,
            url = url,
            headers = mapOf("x-goog-api-key" to trimmedKey),
            payload = payload,
            apiKeyToRedact = trimmedKey,
        )

        if (useVertexInitially && outcome is HttpCallResult.Response) {
            val bodyText = outcome.bodyText()
            // Google AI Studio also issues `AQ.` keys that return 403 "Agent Platform API has not been used"
            // on aiplatform.googleapis.com; transparently fail over and cache the AI Studio endpoint.
            if (isVertexApiDisabled403(outcome.code, bodyText)) {
                preferAiStudioKeyHashes[keyHash(trimmedKey)] = true
                val aiStudioUrl = resolveGenerateUrl(useVertex = false, modelId = modelId)
                return LlmHttpSupport.postJson(
                    client = httpClient,
                    url = aiStudioUrl,
                    headers = mapOf("x-goog-api-key" to trimmedKey),
                    payload = payload,
                    apiKeyToRedact = trimmedKey,
                )
            }

            // Newer Gemini 3.8 / 4.x models on Vertex AI Express may live under `v1beta1` before `v1`.
            if (retryVertexV1Beta1On404 && outcome.code == 404) {
                val betaUrl = resolveGenerateUrl(
                    useVertex = true,
                    modelId = modelId,
                    vertexApiVersion = "v1beta1",
                )
                return LlmHttpSupport.postJson(
                    client = httpClient,
                    url = betaUrl,
                    headers = mapOf("x-goog-api-key" to trimmedKey),
                    payload = payload,
                    apiKeyToRedact = trimmedKey,
                )
            }
        }

        return outcome
    }

    private fun shouldUseVertexExpress(key: ApiKeyEntry, trimmedKey: String): Boolean {
        if (key.provider == ProviderType.GEMINI_AI_STUDIO || key.provider == ProviderType.FREE_GEMINI) {
            return false
        }
        if (trimmedKey.startsWith("AIza")) {
            return false
        }
        if (preferAiStudioKeyHashes.containsKey(keyHash(trimmedKey))) {
            return false
        }
        return trimmedKey.startsWith("AQ.") || key.provider == ProviderType.GEMINI_VERTEX_EXPRESS
    }

    private fun isVertexApiDisabled403(code: Int, body: String): Boolean =
        code == 403 && LlmHttpSupport.containsAnyMarker(body, *VERTEX_DISABLED_MARKERS)

    private fun resolveGenerateUrl(
        useVertex: Boolean,
        modelId: String,
        vertexApiVersion: String = "v1",
    ): String {
        val sanitizedModel = modelId.trim()
        return if (useVertex) {
            "${vertexBaseUrl.trimEnd('/')}/$vertexApiVersion/publishers/google/models/$sanitizedModel:generateContent"
        } else {
            "${aiStudioBaseUrl.trimEnd('/')}/v1beta/models/$sanitizedModel:generateContent"
        }
    }

    private fun keyHash(trimmedKey: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(trimmedKey.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

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
                            if (!hasUserText) {
                                addJsonObject { put("text", AUDIO_TURN_ANTI_HALLUCINATION_PROMPT) }
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
        val textBuilder = StringBuilder()
        val commands = mutableListOf<DeviceCommand>()

        parts.forEach { part ->
            if (!part.thought) {
                val rawText = part.text
                if (!rawText.isNullOrEmpty()) {
                    if (textBuilder.isNotEmpty() &&
                        !textBuilder.last().isWhitespace() &&
                        !rawText.first().isWhitespace() &&
                        rawText.first() !in ",.;:!?)"
                    ) {
                        textBuilder.append(' ')
                    }
                    textBuilder.append(rawText)
                }
            }
            part.functionCall?.let { call ->
                ToolSchemaParser.parseCommand(
                    name = call.name,
                    args = call.args ?: LlmHttpSupport.emptyJsonObject,
                )?.let(commands::add)
            }
        }

        var cleanedReply = textBuilder.toString().trim()
        var transcriptHint: String? = null

        if (cleanedReply.contains(STOP_SESSION_MARKER, ignoreCase = true)) {
            cleanedReply = cleanedReply.replace(STOP_SESSION_MARKER, "", ignoreCase = true).trim()
            if (commands.none { it is DeviceCommand.StopSession }) {
                commands.add(DeviceCommand.StopSession)
            }
        }
        if (cleanedReply.contains(UNCLEAR_AUDIO_MARKER, ignoreCase = true)) {
            cleanedReply = cleanedReply.replace(UNCLEAR_AUDIO_MARKER, "", ignoreCase = true).trim()
                .ifEmpty { "Dạ em nghe chưa rõ, anh nói lại giúp em nha?" }
            transcriptHint = UNCLEAR_AUDIO_HINT
            commands.clear()
        }

        return LlmResult.Success(
            replyText = cleanedReply,
            toolCalls = commands,
            transcriptHint = transcriptHint,
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

        val decodedBytes = try {
            Base64.getDecoder().decode(inlineData.data!!.replace("\n", "").replace("\r", "").trim())
        } catch (e: IllegalArgumentException) {
            return TtsResult.Error("Invalid base64 audio payload: ${e.message}")
        }

        val mimeType = inlineData.mimeType?.takeIf { it.isNotBlank() } ?: DEFAULT_PCM_MIME
        val (pcmBytes, sampleRateHz) = extractPcmAndSampleRate(decodedBytes, mimeType)
        return TtsResult.Success(
            TtsAudio(
                pcmBytes = pcmBytes,
                sampleRateHz = sampleRateHz,
                mimeType = mimeType,
            ),
        )
    }

    /**
     * Gemini 3.8 TTS returns `audio/wav` with a RIFF container whereas 2.5 TTS returns raw PCM.
     * Unwraps RIFF/WAVE containers so [AudioPlaybackEngine] always receives pure PCM samples and exact rate.
     */
    private fun extractPcmAndSampleRate(bytes: ByteArray, mimeType: String): Pair<ByteArray, Int> {
        if (bytes.size >= 44 &&
            bytes[0] == 'R'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() &&
            bytes[9] == 'A'.code.toByte() &&
            bytes[10] == 'V'.code.toByte() &&
            bytes[11] == 'E'.code.toByte()
        ) {
            var wavRate = parseSampleRate(mimeType)
            var offset = 12
            while (offset + 8 <= bytes.size) {
                val chunkId = String(bytes, offset, 4, Charsets.US_ASCII)
                val chunkSize = readLeInt(bytes, offset + 4).coerceAtLeast(0)
                val dataStart = offset + 8
                if (chunkId == "fmt " && dataStart + 8 <= bytes.size) {
                    val rate = readLeInt(bytes, dataStart + 4)
                    if (rate in 8_000..96_000) {
                        wavRate = rate
                    }
                } else if (chunkId == "data" && dataStart <= bytes.size) {
                    val dataEnd = if (chunkSize > 0) {
                        minOf(dataStart + chunkSize, bytes.size)
                    } else {
                        bytes.size
                    }
                    return bytes.copyOfRange(dataStart, dataEnd) to wavRate
                }
                val step = 8 + chunkSize + (chunkSize and 1)
                if (step <= 8) break
                offset += step
            }
            return bytes.copyOfRange(44, bytes.size) to wavRate
        }
        return bytes to parseSampleRate(mimeType)
    }

    private fun readLeInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

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
        val thought: Boolean = false,
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

    companion object {
        const val UNCLEAR_AUDIO_HINT = "__UNCLEAR_AUDIO__"
        private const val UNCLEAR_AUDIO_MARKER = "[UNCLEAR_AUDIO]"
        private const val STOP_SESSION_MARKER = "[STOP_SESSION]"
        private val AUDIO_TURN_ANTI_HALLUCINATION_PROMPT = """
            Hãy nghe kỹ đoạn thu âm giọng nói tiếng Việt này của anh và làm đúng các quy tắc bắt buộc sau:
            1. TUYỆT ĐỐI KHÔNG tự đoán câu hỏi, KHÔNG tự bịa chủ đề, KHÔNG tự đặt câu hỏi rồi tự trả lời.
            2. Nếu đoạn thu âm chỉ có tiếng ồn, im lặng, quá nhỏ hoặc nghe không rõ chữ cụ thể, BẮT BUỘC chỉ trả lời đúng: "[UNCLEAR_AUDIO] Dạ em nghe chưa rõ, anh nói lại giúp em nha?" và không gọi bất kỳ công cụ nào.
            3. Nếu anh nói "tắt Na Na", "tạm biệt", "nghỉ nhé", "dừng lại" hoặc muốn kết thúc trò chuyện, hãy gọi công cụ stop_conversation (hoặc thêm [STOP_SESSION]) và đáp: "Dạ em tạm nghỉ đây, khi nào cần anh cứ gọi Na Na nha!".
            4. Nếu nghe rõ anh hỏi thông tin thực tế (dự báo thời tiết, giá vàng thế giới, giá vàng Mi Hồng/SJC, tỷ giá, tin tức...), BẮT BUỘC gọi công cụ search_live_info với đúng nội dung câu hỏi vừa nghe được.
        """.trimIndent()
        private const val DEFAULT_SAMPLE_RATE_HZ = 24_000
        private const val DEFAULT_PCM_MIME = "audio/L16;codec=pcm;rate=24000"
        private const val GEMINI_38_TTS_MODEL = "gemini-3.8-flash-tts"
        private const val DEFAULT_PREVIEW_TTS_MODEL = "gemini-2.5-flash-preview-tts"
        private const val VERTEX_GA_TTS_MODEL = "gemini-2.5-flash-tts"
        val RATE_REGEX = Regex("rate=(\\d+)", RegexOption.IGNORE_CASE)
        val GEMINI_QUOTA_MARKERS = arrayOf("RESOURCE_EXHAUSTED", "quota")
        val VERTEX_DISABLED_MARKERS = arrayOf(
            "Agent Platform API",
            "aiplatform.googleapis.com",
            "Vertex AI API",
            "has not been used in project",
            "API_KEY_SERVICE_BLOCKED",
        )
    }
}
