package com.adwewecat.nana.core.ai

import com.adwewecat.nana.core.model.ApiKeyEntry
import com.adwewecat.nana.core.model.DeviceCommand
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.OkHttpClient

/** Anthropic Claude Messages API (`/v1/messages`) client with device tool-use parsing. */
class ClaudeClient(
    private val httpClient: OkHttpClient,
    private val baseUrl: String = "https://api.anthropic.com",
) : LlmProviderClient {

    override suspend fun generate(key: ApiKeyEntry, request: LlmRequest): LlmResult {
        val trimmedKey = key.apiKey.trim()
        if (trimmedKey.isEmpty()) {
            return LlmResult.InvalidKey(httpCode = 401, detail = "Missing Claude API key")
        }

        if (request.userText.isNullOrBlank() && request.userAudio != null) {
            return LlmResult.TransientError("Claude Messages API requires transcribed text input")
        }

        val payload = buildPayload(modelId = key.modelId.trim(), request = request)
        val url = "${baseUrl.trimEnd('/')}/v1/messages"
        val outcome = LlmHttpSupport.postJson(
            client = httpClient,
            url = url,
            headers = mapOf(
                "x-api-key" to trimmedKey,
                "anthropic-version" to ANTHROPIC_VERSION,
            ),
            payload = payload,
            apiKeyToRedact = trimmedKey,
        )

        return when (outcome) {
            is HttpCallResult.Failure -> LlmResult.TransientError(outcome.detail)
            is HttpCallResult.Response -> parseOutcome(outcome, trimmedKey)
        }
    }

    private fun buildPayload(modelId: String, request: LlmRequest): JsonObject = buildJsonObject {
        put("model", modelId)
        put("max_tokens", DEFAULT_MAX_TOKENS)
        if (request.systemPrompt.isNotBlank()) {
            put("system", request.systemPrompt)
        }

        putJsonArray("messages") {
            request.history.forEach { message ->
                val role = LlmHttpSupport.mapHistoryRole(message.role, assistantRoleName = "assistant")
                if (role != null && message.content.isNotBlank()) {
                    addJsonObject {
                        put("role", role)
                        put("content", message.content)
                    }
                }
            }

            if (!request.userText.isNullOrBlank()) {
                addJsonObject {
                    put("role", "user")
                    put("content", request.userText)
                }
            }
        }

        if (request.enableTools) {
            put("tools", DeviceToolSchemas.toClaudeToolsArray())
        }
    }

    private fun parseOutcome(outcome: HttpCallResult.Response, trimmedKey: String): LlmResult {
        val rawBody = outcome.bodyText()
        if (outcome.code !in 200..299) {
            return LlmHttpSupport.mapHttpError(
                code = outcome.code,
                rawBody = rawBody,
                trimmedKey = trimmedKey,
                extraQuotaCodes = setOf(529),
                quotaMarkers = CLAUDE_QUOTA_MARKERS,
            )
        }

        val parsed = try {
            LlmHttpSupport.json.decodeFromString<ClaudeMessagesResponse>(rawBody)
        } catch (e: SerializationException) {
            return LlmResult.TransientError(
                LlmHttpSupport.redactKey(e.message ?: "Malformed Claude JSON", trimmedKey),
            )
        }

        val textSegments = mutableListOf<String>()
        val commands = mutableListOf<DeviceCommand>()

        parsed.content.forEach { block ->
            when (block.type) {
                "text" -> block.text?.trim()?.takeIf { it.isNotEmpty() }?.let(textSegments::add)
                "tool_use" -> {
                    val toolName = block.name ?: return@forEach
                    ToolSchemaParser.parseCommand(
                        name = toolName,
                        args = block.input ?: LlmHttpSupport.emptyJsonObject,
                    )?.let(commands::add)
                }
            }
        }

        return LlmResult.Success(
            replyText = textSegments.joinToString("\n"),
            toolCalls = commands,
        )
    }

    @Serializable
    private data class ClaudeMessagesResponse(
        val content: List<ClaudeContentBlock> = emptyList(),
    )

    @Serializable
    private data class ClaudeContentBlock(
        val type: String = "",
        val text: String? = null,
        val name: String? = null,
        val input: JsonObject? = null,
    )

    private companion object {
        const val ANTHROPIC_VERSION = "2023-06-01"
        const val DEFAULT_MAX_TOKENS = 1024
        val CLAUDE_QUOTA_MARKERS = arrayOf("rate_limit", "overloaded", "quota")
    }
}
