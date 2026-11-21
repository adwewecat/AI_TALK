package com.adwewecat.nana.core.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Encodes raw mono 16-bit signed little-endian PCM bytes into a standard RIFF/WAVE payload. */
object PcmWavEncoder {

    private const val HEADER_SIZE_BYTES = 44
    private const val PCM_FORMAT: Short = 1
    private const val MONO_CHANNELS: Short = 1
    private const val BITS_PER_SAMPLE: Short = 16

    /** Prepends a 44-byte little-endian RIFF/WAVE header for mono 16-bit PCM to [pcmBytes]. */
    fun encodeMono16Bit(pcmBytes: ByteArray, sampleRateHz: Int = 16_000): ByteArray {
        require(sampleRateHz > 0) { "sampleRateHz must be positive, was $sampleRateHz" }

        val blockAlign = (MONO_CHANNELS * (BITS_PER_SAMPLE / 8)).toShort()
        val byteRate = sampleRateHz * blockAlign
        val dataSize = pcmBytes.size
        val chunkSize = 36 + dataSize

        val buffer = ByteBuffer.allocate(HEADER_SIZE_BYTES + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(chunkSize)
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16)
        buffer.putShort(PCM_FORMAT)
        buffer.putShort(MONO_CHANNELS)
        buffer.putInt(sampleRateHz)
        buffer.putInt(byteRate)
        buffer.putShort(blockAlign)
        buffer.putShort(BITS_PER_SAMPLE)
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(dataSize)
        buffer.put(pcmBytes)
        return buffer.array()
    }
}
