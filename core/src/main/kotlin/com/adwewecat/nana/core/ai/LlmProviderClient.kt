package com.adwewecat.nana.core.ai

import com.adwewecat.nana.core.model.ApiKeyEntry
import com.adwewecat.nana.core.model.ChatMessage
import com.adwewecat.nana.core.model.DeviceCommand
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/** Raw audio payload attached to a multimodal turn (e.g. 16kHz WAV for Gemini). */
data class AudioAttachment(
    val bytes: ByteArray,
    val mimeType: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioAttachment) return false
        return mimeType == other.mimeType && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + mimeType.hashCode()
}

/** Provider-agnostic chat generation request payload. */
data class LlmRequest(
    val systemPrompt: String,
    val history: List<ChatMessage>,
    val userText: String?,
    val userAudio: AudioAttachment? = null,
    val enableTools: Boolean = true,
)

/** Normalized outcome from an LLM chat completion call. */
sealed class LlmResult {
    data class Success(
        val replyText: String,
        val toolCalls: List<DeviceCommand>,
        val transcriptHint: String? = null,
    ) : LlmResult()

    data class QuotaExceeded(val httpCode: Int, val detail: String) : LlmResult()

    data class InvalidKey(val httpCode: Int, val detail: String) : LlmResult()

    data class TransientError(val detail: String) : LlmResult()
}

/** Contract implemented by each LLM provider backend client. */
interface LlmProviderClient {
    suspend fun generate(key: ApiKeyEntry, request: LlmRequest): LlmResult
}

/** Synthesized PCM audio returned by cloud neural TTS endpoints. */
data class TtsAudio(
    val pcmBytes: ByteArray,
    val sampleRateHz: Int,
    val mimeType: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TtsAudio) return false
        return sampleRateHz == other.sampleRateHz &&
            mimeType == other.mimeType &&
            pcmBytes.contentEquals(other.pcmBytes)
    }

    override fun hashCode(): Int {
        var result = pcmBytes.contentHashCode()
        result = 31 * result + sampleRateHz
        result = 31 * result + mimeType.hashCode()
        return result
    }
}

/** Normalized outcome from cloud TTS synthesis requests. */
sealed class TtsResult {
    data class Success(val audio: TtsAudio) : TtsResult()

    data class Unsupported(val detail: String) : TtsResult()

    data class QuotaExceeded(val detail: String) : TtsResult()

    data class Error(val detail: String) : TtsResult()
}

/** Maps model tool/function calls across Gemini, OpenAI, and Claude into typed [DeviceCommand]s. */
object ToolSchemaParser {

    /** Parses a tool call [name] and JSON [args] into a [DeviceCommand], or `null` if unknown or invalid. */
    fun parseCommand(name: String, args: JsonObject): DeviceCommand? {
        return when (name.trim()) {
            "open_youtube_search" -> {
                val query = args.stringArg("query") ?: return null
                val autoPlay = args.booleanArg("auto_play") ?: true
                DeviceCommand.OpenYouTubeSearch(query = query, autoPlay = autoPlay)
            }

            "answer_incoming_call" -> DeviceCommand.AnswerIncomingCall

            "end_call" -> DeviceCommand.EndCall

            "toggle_speaker" -> {
                val enabled = args.booleanArg("enabled") ?: return null
                DeviceCommand.ToggleSpeaker(enabled = enabled)
            }

            "make_phone_call" -> {
                val target = args.stringArg("target") ?: return null
                DeviceCommand.MakePhoneCall(target = target)
            }

            "open_app" -> {
                val appName = args.stringArg("app_name") ?: return null
                DeviceCommand.OpenApp(appName = appName)
            }

            "set_alarm" -> {
                val hour = args.intArg("hour") ?: return null
                val minute = args.intArg("minute") ?: return null
                if (hour !in 0..23 || minute !in 0..59) return null
                val label = args.optionalStringArg("label").orEmpty()
                DeviceCommand.SetAlarm(hour = hour, minute = minute, label = label)
            }

            "set_timer" -> {
                val seconds = args.intArg("seconds") ?: return null
                if (seconds <= 0) return null
                val label = args.optionalStringArg("label").orEmpty()
                DeviceCommand.SetTimer(seconds = seconds, label = label)
            }

            "adjust_volume" -> {
                val stream = args.optionalStringArg("stream")?.ifBlank { null } ?: "music"
                val direction = args.optionalStringArg("direction")?.ifBlank { null } ?: "up"
                val percent = args.intArg("percent")
                DeviceCommand.AdjustVolume(stream = stream, direction = direction, percent = percent)
            }

            "toggle_flashlight" -> {
                val enabled = args.booleanArg("enabled") ?: return null
                DeviceCommand.ToggleFlashlight(enabled = enabled)
            }

            "web_search" -> {
                val query = args.stringArg("query") ?: return null
                DeviceCommand.WebSearch(query = query)
            }

            else -> null
        }
    }

    private fun JsonObject.stringArg(key: String): String? =
        optionalStringArg(key)?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.optionalStringArg(key: String): String? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        return if (primitive.isString) primitive.contentOrNull else null
    }

    private fun JsonObject.booleanArg(key: String): Boolean? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        return primitive.booleanOrNull
            ?: primitive.contentOrNull?.trim()?.lowercase()?.let {
                when (it) {
                    "true" -> true
                    "false" -> false
                    else -> null
                }
            }
    }

    private fun JsonObject.intArg(key: String): Int? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        return primitive.intOrNull
            ?: primitive.doubleOrNull?.toInt()
            ?: primitive.contentOrNull?.trim()?.toIntOrNull()
    }
}

internal data class ToolParamSpec(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean = false,
)

internal data class ToolSpec(
    val name: String,
    val description: String,
    val params: List<ToolParamSpec> = emptyList(),
)

/** Single source of truth for device-control schemas formatted per LLM provider. */
internal object DeviceToolSchemas {
    private val specs = listOf(
        ToolSpec(
            name = "open_youtube_search",
            description = "Search YouTube and optionally auto-play the top result.",
            params = listOf(
                ToolParamSpec("query", "string", "Search query for YouTube", required = true),
                ToolParamSpec("auto_play", "boolean", "Whether to start playback automatically"),
            ),
        ),
        ToolSpec(
            name = "answer_incoming_call",
            description = "Answer an incoming phone or VoIP call.",
        ),
        ToolSpec(
            name = "end_call",
            description = "End or hang up the active call.",
        ),
        ToolSpec(
            name = "toggle_speaker",
            description = "Turn phone speakerphone on or off.",
            params = listOf(
                ToolParamSpec("enabled", "boolean", "True to turn speaker on, false for off", required = true),
            ),
        ),
        ToolSpec(
            name = "make_phone_call",
            description = "Place a phone call to a contact name or phone number.",
            params = listOf(
                ToolParamSpec("target", "string", "Contact name or phone number", required = true),
            ),
        ),
        ToolSpec(
            name = "open_app",
            description = "Open an installed Android application by name.",
            params = listOf(
                ToolParamSpec("app_name", "string", "Application name to launch", required = true),
            ),
        ),
        ToolSpec(
            name = "set_alarm",
            description = "Set a device alarm for a specific hour and minute.",
            params = listOf(
                ToolParamSpec("hour", "integer", "Hour of day in 24h format (0-23)", required = true),
                ToolParamSpec("minute", "integer", "Minute of hour (0-59)", required = true),
                ToolParamSpec("label", "string", "Optional alarm label"),
            ),
        ),
        ToolSpec(
            name = "set_timer",
            description = "Start a countdown timer in seconds.",
            params = listOf(
                ToolParamSpec("seconds", "integer", "Duration in seconds", required = true),
                ToolParamSpec("label", "string", "Optional timer label"),
            ),
        ),
        ToolSpec(
            name = "adjust_volume",
            description = "Adjust device audio volume up, down, or to a percentage.",
            params = listOf(
                ToolParamSpec("stream", "string", "Audio stream such as music, ring, or call"),
                ToolParamSpec("direction", "string", "Direction: up, down, or set"),
                ToolParamSpec("percent", "integer", "Optional target volume percentage (0-100)"),
            ),
        ),
        ToolSpec(
            name = "toggle_flashlight",
            description = "Turn the camera flashlight torch on or off.",
            params = listOf(
                ToolParamSpec("enabled", "boolean", "True to enable flashlight, false to disable", required = true),
            ),
        ),
        ToolSpec(
            name = "web_search",
            description = "Search the web in the device browser.",
            params = listOf(
                ToolParamSpec("query", "string", "Search query", required = true),
            ),
        ),
    )

    fun toGeminiToolsArray(): JsonArray = buildJsonArray {
        addJsonObject {
            putJsonArray("functionDeclarations") {
                specs.forEach { spec ->
                    addJsonObject {
                        put("name", spec.name)
                        put("description", spec.description)
                        put("parameters", buildSchema(spec, uppercaseTypes = true))
                    }
                }
            }
        }
    }

    fun toOpenAiToolsArray(): JsonArray = buildJsonArray {
        specs.forEach { spec ->
            addJsonObject {
                put("type", "function")
                putJsonObject("function") {
                    put("name", spec.name)
                    put("description", spec.description)
                    put("parameters", buildSchema(spec, uppercaseTypes = false))
                }
            }
        }
    }

    fun toClaudeToolsArray(): JsonArray = buildJsonArray {
        specs.forEach { spec ->
            addJsonObject {
                put("name", spec.name)
                put("description", spec.description)
                put("input_schema", buildSchema(spec, uppercaseTypes = false))
            }
        }
    }

    private fun buildSchema(spec: ToolSpec, uppercaseTypes: Boolean): JsonObject = buildJsonObject {
        put("type", if (uppercaseTypes) "OBJECT" else "object")
        putJsonObject("properties") {
            spec.params.forEach { param ->
                putJsonObject(param.name) {
                    put("type", if (uppercaseTypes) param.type.uppercase() else param.type.lowercase())
                    put("description", param.description)
                }
            }
        }
        val required = spec.params.filter { it.required }
        if (required.isNotEmpty()) {
            putJsonArray("required") {
                required.forEach { add(JsonPrimitive(it.name)) }
            }
        }
    }
}

internal sealed class HttpCallResult {
    data class Response(val code: Int, val bodyBytes: ByteArray) : HttpCallResult() {
        fun bodyText(): String = bodyBytes.toString(Charsets.UTF_8)
    }

    data class Failure(val detail: String) : HttpCallResult()
}

/** Shared JSON, role mapping, blocking-IO dispatch, and API-key redaction helpers. */
internal object LlmHttpSupport {
    val json: Json = Json { ignoreUnknownKeys = true }
    val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    val emptyJsonObject = JsonObject(emptyMap())

    suspend fun postJson(
        client: OkHttpClient,
        url: String,
        headers: Map<String, String>,
        payload: JsonObject,
        apiKeyToRedact: String,
    ): HttpCallResult = executePost(
        client = client,
        url = url,
        headers = headers,
        body = payload.toString().toRequestBody(jsonMediaType),
        apiKeyToRedact = apiKeyToRedact,
    )

    suspend fun executePost(
        client: OkHttpClient,
        url: String,
        headers: Map<String, String>,
        body: RequestBody,
        apiKeyToRedact: String,
    ): HttpCallResult {
        val requestBuilder = Request.Builder().url(url).post(body)
        headers.forEach { (name, value) -> requestBuilder.header(name, value) }
        val request = requestBuilder.build()

        return try {
            withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { response ->
                    val bytes = response.body?.bytes() ?: ByteArray(0)
                    HttpCallResult.Response(code = response.code, bodyBytes = bytes)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            HttpCallResult.Failure(redactKey(e.message ?: "Network error", apiKeyToRedact))
        } catch (e: IllegalArgumentException) {
            HttpCallResult.Failure(redactKey(e.message ?: "Invalid request", apiKeyToRedact))
        }
    }

    fun mapHistoryRole(rawRole: String, assistantRoleName: String): String? =
        when (rawRole.trim().lowercase()) {
            "user" -> "user"
            "assistant", "model" -> assistantRoleName
            else -> null
        }

    fun redactKey(text: String, rawApiKey: String): String {
        val trimmedKey = rawApiKey.trim()
        val sanitized = if (trimmedKey.isNotEmpty()) {
            text.replace(trimmedKey, "[REDACTED]")
        } else {
            text
        }
        return sanitized.trim().take(400)
    }

    fun formatHttpError(code: Int, rawBody: String, rawApiKey: String): String {
        val safeBody = redactKey(rawBody, rawApiKey)
        return if (safeBody.isBlank()) "HTTP $code" else "HTTP $code: $safeBody"
    }

    fun containsAnyMarker(body: String, vararg markers: String): Boolean =
        markers.any { marker -> body.contains(marker, ignoreCase = true) }

    fun mapHttpError(
        code: Int,
        rawBody: String,
        trimmedKey: String,
        extraQuotaCodes: Set<Int> = emptySet(),
        quotaMarkers: Array<String>,
    ): LlmResult {
        val detail = formatHttpError(code, rawBody, trimmedKey)
        return when {
            code == 429 || code in extraQuotaCodes -> LlmResult.QuotaExceeded(code, detail)
            code == 401 || code == 403 -> LlmResult.InvalidKey(code, detail)
            containsAnyMarker(rawBody, *quotaMarkers) -> LlmResult.QuotaExceeded(code, detail)
            else -> LlmResult.TransientError(detail)
        }
    }
}
