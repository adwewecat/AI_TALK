package com.adwewecat.nana.core.ai

import com.adwewecat.nana.core.model.ChatMessage
import com.adwewecat.nana.core.model.MemoryCategory
import com.adwewecat.nana.core.model.MemoryFact
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull

/**
 * Builds background memory-extraction prompts from recent turns and safely parses
 * durable [MemoryFact] records from LLM JSON output.
 */
class MemoryExtractor(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    /** Formats [recentMessages] into an extraction prompt requesting a strict JSON array of facts. */
    fun buildExtractionPrompt(recentMessages: List<ChatMessage>): String {
        val transcript = recentMessages
            .filter { it.content.isNotBlank() }
            .joinToString(separator = "\n") { msg -> "${msg.role}: ${msg.content.trim()}" }

        val categories = MemoryCategory.entries.joinToString(", ") { it.name }

        return """
            Hãy trích xuất các thông tin đáng nhớ và lâu dài về người dùng từ đoạn hội thoại sau.
            Chỉ trả về một mảng JSON hợp lệ gồm các object với cấu trúc:
            [{"category": "<$categories>", "key": "<snake_case_key>", "value": "<giá trị ngắn gọn>", "confidence": 0.0_to_1.0}]
            Nếu không có thông tin mới đáng nhớ, trả về [].

            Hội thoại:
            $transcript
        """.trimIndent()
    }

    /**
     * Parses extracted [MemoryFact] entries from [rawJson], stripping optional markdown code fences
     * and skipping any malformed entries without throwing.
     */
    fun parseExtractedFacts(rawJson: String, nowMs: Long): List<MemoryFact> {
        val cleaned = extractJsonPayload(rawJson)
        if (cleaned.isEmpty()) return emptyList()

        val root = try {
            json.parseToJsonElement(cleaned)
        } catch (_: SerializationException) {
            return emptyList()
        } catch (_: IllegalArgumentException) {
            return emptyList()
        }

        val elements: List<JsonElement> = when (root) {
            is JsonArray -> root
            is JsonObject -> {
                val nested = root["facts"] ?: root["memories"] ?: root["items"]
                when (nested) {
                    is JsonArray -> nested
                    null -> listOf(root)
                    else -> emptyList()
                }
            }
            else -> emptyList()
        }

        val facts = mutableListOf<MemoryFact>()
        elements.forEachIndexed { index, element ->
            val obj = element as? JsonObject ?: return@forEachIndexed
            val key = obj.stringField("key") ?: return@forEachIndexed
            val value = obj.stringField("value") ?: return@forEachIndexed
            val category = parseCategory(obj.stringField("category"))
            val confidence = parseConfidence(obj["confidence"])

            facts.add(
                MemoryFact(
                    id = buildFactId(nowMs, index, key),
                    category = category,
                    key = key,
                    value = value,
                    confidence = confidence,
                    updatedAtMs = nowMs,
                    synced = false,
                ),
            )
        }
        return facts
    }

    private fun extractJsonPayload(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""

        val unfenced = FENCE_REGEX.find(trimmed)?.groupValues?.getOrNull(1)?.trim() ?: trimmed
        val firstBracket = unfenced.indexOf('[')
        val firstBrace = unfenced.indexOf('{')

        val preferObject = firstBrace >= 0 && (firstBracket < 0 || firstBrace < firstBracket)
        if (preferObject) {
            val lastBrace = unfenced.lastIndexOf('}')
            if (firstBrace < lastBrace) {
                return unfenced.substring(firstBrace, lastBrace + 1)
            }
        } else if (firstBracket >= 0) {
            val lastBracket = unfenced.lastIndexOf(']')
            if (firstBracket < lastBracket) {
                return unfenced.substring(firstBracket, lastBracket + 1)
            }
        }

        return unfenced
    }

    private fun JsonObject.stringField(field: String): String? {
        val primitive = this[field] as? JsonPrimitive ?: return null
        if (!primitive.isString) return null
        return primitive.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun parseCategory(rawCategory: String?): MemoryCategory {
        if (rawCategory == null) return MemoryCategory.FACT
        return MemoryCategory.entries.firstOrNull {
            it.name.equals(rawCategory, ignoreCase = true)
        } ?: MemoryCategory.FACT
    }

    private fun parseConfidence(element: JsonElement?): Float {
        val primitive = element as? JsonPrimitive ?: return DEFAULT_CONFIDENCE
        val parsed = primitive.floatOrNull
            ?: primitive.contentOrNull?.trim()?.toFloatOrNull()
            ?: return DEFAULT_CONFIDENCE
        return parsed.coerceIn(0.0f, 1.0f)
    }

    private fun buildFactId(nowMs: Long, index: Int, key: String): String {
        val slug = key.lowercase().replace(NON_ALNUM_REGEX, "_").trim('_').ifEmpty { "fact" }
        return "mem_${nowMs}_${index}_$slug"
    }

    private companion object {
        const val DEFAULT_CONFIDENCE = 0.8f
        val FENCE_REGEX = Regex("```(?:json)?\\s*([\\s\\S]*?)\\s*```", RegexOption.IGNORE_CASE)
        val NON_ALNUM_REGEX = Regex("[^a-z0-9]+")
    }
}
