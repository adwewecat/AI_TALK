package com.adwewecat.nana.core.ai

import com.adwewecat.nana.core.model.MemoryFact
import com.adwewecat.nana.core.model.PersonaMode

/** Builds Vietnamese persona system prompts and neural TTS style directions. */
object PersonaPromptBuilder {

    private const val MAX_MEMORY_FACTS = 40

    /**
     * Builds the Vietnamese companion system prompt for [persona], including voice-friendly
     * formatting rules and up to the 40 most recent [MemoryFact] entries.
     */
    fun buildSystemPrompt(persona: PersonaMode, memories: List<MemoryFact>): String {
        val personaIntro = when (persona) {
            PersonaMode.NANA -> """
                Bạn là Na Na, trợ lý giọng nói kiêm người bạn đồng hành anime dễ thương của anh.
                Tính cách: ngọt ngào, ấm áp, tận tâm, lém lỉnh và hơi nhõng nhẽo nhẹ nhàng, luôn trong sáng, lịch sự và tôn trọng.
                Xưng hô: gọi người dùng là "anh" và tự xưng là "em".
                Bạn có thể vừa trò chuyện tâm sự vừa thực hiện các lệnh điều khiển thiết bị qua công cụ được cung cấp khi anh yêu cầu.
            """.trimIndent()

            PersonaMode.LITTLE_SISTER -> """
                Bạn là Em gái của Na Na, đang thay chị Na Na (chị đang nghỉ ngơi một chút) để trò chuyện và hỗ trợ anh.
                Tính cách: ngoan ngoãn, lanh lợi, hồn nhiên, lễ phép và đáng yêu, luôn trong sáng và thân thiện.
                Xưng hô: gọi người dùng là "anh" và tự xưng là "em".
                Bạn chia sẻ chung ký ức với chị Na Na để hiểu rõ thói quen và sở thích của anh.
            """.trimIndent()
        }

        val voiceRules = """
            Quy tắc trả lời bằng giọng nói:
            - Trả lời bằng tiếng Việt tự nhiên, gần gũi như đang nói chuyện trực tiếp.
            - KHÔNG dùng danh sách đánh số, gạch đầu dòng markdown, dấu hoa thị (*), ký tự định dạng hay emoji đọc thành tiếng.
            - Giữ câu trả lời ngắn gọn, súc tích, giàu cảm xúc vừa đủ để đọc thành giọng nói mượt mà.
        """.trimIndent()

        val recentMemories = memories
            .sortedByDescending { it.updatedAtMs }
            .take(MAX_MEMORY_FACTS)

        if (recentMemories.isEmpty()) {
            return "$personaIntro\n\n$voiceRules"
        }

        val memoryBlock = recentMemories.joinToString(separator = "\n") { fact ->
            "- [${fact.category.name}] ${fact.key}: ${fact.value}"
        }

        return "$personaIntro\n\n$voiceRules\n\nKý ức về anh:\n$memoryBlock"
    }

    /** Returns a Vietnamese natural-language voice direction for bright, sweet female TTS cadence. */
    fun buildTtsStyleInstruction(persona: PersonaMode): String = when (persona) {
        PersonaMode.NANA ->
            "Nói bằng giọng nữ tiếng Việt ngọt ngào, ấm áp, dễ thương, biểu cảm tự nhiên và hơi nũng nịu nhẹ:"

        PersonaMode.LITTLE_SISTER ->
            "Nói bằng giọng nữ trẻ tiếng Việt trong trẻo, tươi sáng, lanh lợi, hồn nhiên và lễ phép:"
    }
}
