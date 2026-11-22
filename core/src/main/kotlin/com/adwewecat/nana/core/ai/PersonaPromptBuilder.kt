package com.adwewecat.nana.core.ai

import com.adwewecat.nana.core.model.MemoryFact
import com.adwewecat.nana.core.model.PersonaMode
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Builds Vietnamese persona system prompts and neural TTS style directions. */
object PersonaPromptBuilder {

    private const val MAX_MEMORY_FACTS = 40

    /** Canonical Vietnam timezone (GMT+7 / UTC+7, no DST). */
    val VIETNAM_ZONE_ID: ZoneId = ZoneId.of("Asia/Ho_Chi_Minh")

    private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /**
     * Formats [nowMs] in Vietnam time (`Asia/Ho_Chi_Minh`, UTC+7) using natural Vietnamese labels
     * so time/date answers never drift to UTC or China ROM default (`UTC+8`).
     */
    fun formatVietnamDateTime(nowMs: Long): String {
        val zoned = Instant.ofEpochMilli(nowMs).atZone(VIETNAM_ZONE_ID)
        val dayOfWeekVi = when (zoned.dayOfWeek) {
            DayOfWeek.MONDAY -> "Thứ Hai"
            DayOfWeek.TUESDAY -> "Thứ Ba"
            DayOfWeek.WEDNESDAY -> "Thứ Tư"
            DayOfWeek.THURSDAY -> "Thứ Năm"
            DayOfWeek.FRIDAY -> "Thứ Sáu"
            DayOfWeek.SATURDAY -> "Thứ Bảy"
            DayOfWeek.SUNDAY -> "Chủ Nhật"
        }
        val periodVi = when (zoned.hour) {
            in 0..4 -> "rạng sáng"
            in 5..10 -> "sáng"
            in 11..13 -> "trưa"
            in 14..17 -> "chiều"
            in 18..21 -> "tối"
            else -> "đêm"
        }
        return "$dayOfWeekVi, ngày ${DATE_FORMATTER.format(zoned)}, ${TIME_FORMATTER.format(zoned)} $periodVi (UTC+7)"
    }

    /**
     * Builds the Vietnamese companion system prompt for [persona], including live Vietnam UTC+7
     * date/time context, voice-friendly formatting rules, and up to the 40 most recent [MemoryFact] entries.
     */
    fun buildSystemPrompt(
        persona: PersonaMode,
        memories: List<MemoryFact>,
        nowMs: Long = System.currentTimeMillis(),
    ): String {
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

        val currentVietnamTime = formatVietnamDateTime(nowMs)
        val timeContext = """
            Thời gian hiện tại (Giờ Việt Nam, múi giờ UTC+7 / Asia/Ho_Chi_Minh):
            - Hiện tại là: $currentVietnamTime.
            - Khi anh hỏi bây giờ mấy giờ, hôm nay thứ mấy, ngày mấy, hoặc nhờ đặt báo thức tương đối, LUÔN trả lời và tính toán chính xác theo đúng giờ Việt Nam (UTC+7) nêu trên, tuyệt đối không dùng múi giờ khác.
        """.trimIndent()

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
            return "$personaIntro\n\n$timeContext\n\n$voiceRules"
        }

        val memoryBlock = recentMemories.joinToString(separator = "\n") { fact ->
            "- [${fact.category.name}] ${fact.key}: ${fact.value}"
        }

        return "$personaIntro\n\n$timeContext\n\n$voiceRules\n\nKý ức về anh:\n$memoryBlock"
    }

    /** Returns a Vietnamese natural-language voice direction for bright, sweet female TTS cadence. */
    fun buildTtsStyleInstruction(persona: PersonaMode): String = when (persona) {
        PersonaMode.NANA ->
            "Nói bằng giọng nữ tiếng Việt ngọt ngào, ấm áp, dễ thương, biểu cảm tự nhiên và hơi nũng nịu nhẹ:"

        PersonaMode.LITTLE_SISTER ->
            "Nói bằng giọng nữ trẻ tiếng Việt trong trẻo, tươi sáng, lanh lợi, hồn nhiên và lễ phép:"
    }
}
