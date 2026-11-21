package com.adwewecat.nana.core.voice

import com.adwewecat.nana.core.model.DeviceCommand
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCoreTest {

    @Test
    fun matchesWakeWordAcceptsVietnameseVariantsAndRejectsOtherSpeech() {
        assertEquals("day la tieng viet", FastCommandParser.normalizeVietnamese("  ĐÂY LÀ   tiếng Việt  "))

        assertTrue(FastCommandParser.matchesWakeWord("Na Na"))
        assertTrue(FastCommandParser.matchesWakeWord("nana"))
        assertTrue(FastCommandParser.matchesWakeWord("Na Na ơi"))
        assertTrue(FastCommandParser.matchesWakeWord("nana oi"))
        assertTrue(FastCommandParser.matchesWakeWord("Nà Ná ơi!"))
        assertTrue(FastCommandParser.matchesWakeWord("Ơi Na Na ơi giúp anh với"))

        assertFalse(FastCommandParser.matchesWakeWord("banana"))
        assertFalse(FastCommandParser.matchesWakeWord("nana123"))
        assertFalse(FastCommandParser.matchesWakeWord("nhana"))
        assertFalse(FastCommandParser.matchesWakeWord("hôm nay trời đẹp quá"))
        assertFalse(FastCommandParser.matchesWakeWord("   "))
    }

    @Test
    fun parseLocalCommandHandlesCallYouTubeFlashlightAndStopPhrases() {
        assertEquals(DeviceCommand.AnswerIncomingCall, FastCommandParser.parseLocalCommand("bật máy"))
        assertEquals(DeviceCommand.AnswerIncomingCall, FastCommandParser.parseLocalCommand("nghe may"))
        assertEquals(DeviceCommand.AnswerIncomingCall, FastCommandParser.parseLocalCommand("Nhận cuộc gọi"))

        assertEquals(DeviceCommand.EndCall, FastCommandParser.parseLocalCommand("cúp máy"))
        assertEquals(DeviceCommand.EndCall, FastCommandParser.parseLocalCommand("Tắt cuộc gọi"))
        assertEquals(DeviceCommand.EndCall, FastCommandParser.parseLocalCommand("tu choi cuoc goi"))

        assertEquals(DeviceCommand.ToggleSpeaker(true), FastCommandParser.parseLocalCommand("Bật loa ngoài"))
        assertEquals(DeviceCommand.ToggleSpeaker(false), FastCommandParser.parseLocalCommand("tat loa ngoai"))

        assertEquals(
            DeviceCommand.OpenYouTubeSearch("Sơn Tùng M-TP", autoPlay = true),
            FastCommandParser.parseLocalCommand("  Mở YouTube tìm Sơn Tùng M-TP  "),
        )
        assertEquals(
            DeviceCommand.OpenYouTubeSearch("Nhac Trinh", autoPlay = true),
            FastCommandParser.parseLocalCommand("mo youtube phat Nhac Trinh"),
        )
        assertEquals(
            DeviceCommand.OpenYouTubeSearch("Lạc Trôi", autoPlay = true),
            FastCommandParser.parseLocalCommand("Na Na ơi bật nhạc Lạc Trôi"),
        )
        assertEquals(
            DeviceCommand.OpenYouTubeSearch("Tạm biệt mùa hè", autoPlay = true),
            FastCommandParser.parseLocalCommand("phat nhac Tạm biệt mùa hè"),
        )

        assertEquals(DeviceCommand.ToggleFlashlight(true), FastCommandParser.parseLocalCommand("Bật đèn pin"))
        assertEquals(DeviceCommand.ToggleFlashlight(false), FastCommandParser.parseLocalCommand("tat den pin"))

        assertEquals(DeviceCommand.StopSession, FastCommandParser.parseLocalCommand("tạm biệt"))
        assertEquals(DeviceCommand.StopSession, FastCommandParser.parseLocalCommand("nghỉ nhé"))
        assertEquals(DeviceCommand.StopSession, FastCommandParser.parseLocalCommand("thôi nha"))
        assertEquals(DeviceCommand.StopSession, FastCommandParser.parseLocalCommand("dừng lại"))
        assertEquals(DeviceCommand.StopSession, FastCommandParser.parseLocalCommand("im di"))
    }

    @Test
    fun parseLocalCommandReturnsNullForOrdinaryConversation() {
        assertNull(FastCommandParser.parseLocalCommand("Hôm nay thời tiết thế nào"))
        assertNull(FastCommandParser.parseLocalCommand("Na Na ơi kể chuyện cho mình nghe đi"))
        assertNull(FastCommandParser.parseLocalCommand("Cảm ơn em nhé"))
        assertNull(FastCommandParser.parseLocalCommand("bật nhạc"))
        assertNull(FastCommandParser.parseLocalCommand("mở youtube tìm   "))
        assertNull(FastCommandParser.parseLocalCommand(""))
    }

    @Test
    fun energyVadDetectsSpeechStartSilenceEndAndMaxUtteranceCap() {
        val vad = EnergyVad(
            speechRmsThreshold = 650.0,
            silenceDurationMs = 1_400L,
            maxUtteranceMs = 3_000L,
        )
        val quietFrame = ShortArray(160) { 100 }
        val loudFrame = ShortArray(160) { 1_200 }

        assertEquals(0.0, vad.computeRms(ShortArray(0)), 0.001)
        assertEquals(100.0, vad.computeRms(quietFrame), 0.001)
        assertEquals(1_200.0, vad.computeRms(loudFrame), 0.001)

        // Silence before speech remains idle.
        assertEquals(VadEvent.IDLE, vad.feedFrame(quietFrame, 200L))

        // Speech starts, tolerates a brief pause, then ends on sustained silence.
        assertEquals(VadEvent.SPEECH_STARTED, vad.feedFrame(loudFrame, 100L))
        assertEquals(VadEvent.SPEECH_ACTIVE, vad.feedFrame(quietFrame, 500L))
        assertEquals(VadEvent.SPEECH_ACTIVE, vad.feedFrame(loudFrame, 100L))
        assertEquals(VadEvent.SPEECH_ACTIVE, vad.feedFrame(quietFrame, 700L))
        assertEquals(VadEvent.UTTERANCE_COMPLETE, vad.feedFrame(quietFrame, 700L))
        assertEquals(VadEvent.IDLE, vad.feedFrame(quietFrame, 100L))

        // Continuous speech hits maxUtteranceMs cap.
        assertEquals(VadEvent.SPEECH_STARTED, vad.feedFrame(loudFrame, 1_000L))
        assertEquals(VadEvent.SPEECH_ACTIVE, vad.feedFrame(loudFrame, 1_000L))
        assertEquals(VadEvent.UTTERANCE_COMPLETE, vad.feedFrame(loudFrame, 1_000L))
        assertEquals(VadEvent.IDLE, vad.feedFrame(quietFrame, 100L))

        // Reset clears active state back to IDLE.
        assertEquals(VadEvent.SPEECH_STARTED, vad.feedFrame(loudFrame, 200L))
        vad.reset()
        assertEquals(VadEvent.IDLE, vad.feedFrame(quietFrame, 100L))
    }

    @Test
    fun pcmWavEncoderWritesValidRiffWaveHeaderAndPayload() {
        val pcmBytes = byteArrayOf(0x01, 0x02, 0x7F, 0x80.toByte(), 0x10, 0x20)
        val wav = PcmWavEncoder.encodeMono16Bit(pcmBytes, sampleRateHz = 16_000)

        assertEquals(44 + pcmBytes.size, wav.size)

        val header = ByteBuffer.wrap(wav, 0, 44).order(ByteOrder.LITTLE_ENDIAN)
        val riff = ByteArray(4).also { header.get(it) }
        val chunkSize = header.int
        val wave = ByteArray(4).also { header.get(it) }
        val fmt = ByteArray(4).also { header.get(it) }
        val subchunk1Size = header.int
        val audioFormat = header.short.toInt()
        val numChannels = header.short.toInt()
        val sampleRate = header.int
        val byteRate = header.int
        val blockAlign = header.short.toInt()
        val bitsPerSample = header.short.toInt()
        val data = ByteArray(4).also { header.get(it) }
        val dataSize = header.int

        assertEquals("RIFF", String(riff, Charsets.US_ASCII))
        assertEquals(36 + pcmBytes.size, chunkSize)
        assertEquals("WAVE", String(wave, Charsets.US_ASCII))
        assertEquals("fmt ", String(fmt, Charsets.US_ASCII))
        assertEquals(16, subchunk1Size)
        assertEquals(1, audioFormat)
        assertEquals(1, numChannels)
        assertEquals(16_000, sampleRate)
        assertEquals(32_000, byteRate)
        assertEquals(2, blockAlign)
        assertEquals(16, bitsPerSample)
        assertEquals("data", String(data, Charsets.US_ASCII))
        assertEquals(pcmBytes.size, dataSize)

        assertArrayEquals(pcmBytes, wav.copyOfRange(44, wav.size))
    }
}
