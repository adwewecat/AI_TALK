package com.adwewecat.nana.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Passive wake-word detector interface running while the voice service is idle.
 */
interface WakeWordEngine {
    fun start(onWakeDetected: () -> Unit, onMicLevel: (Float) -> Unit)

    fun stop()

    fun isModelAvailable(): Boolean
}

/**
 * Shared microphone capture helpers enforcing [AudioDeviceInfo.TYPE_BUILTIN_MIC] preference
 * so connected Bluetooth headsets remain in high-quality stereo A2DP playback mode.
 */
internal object BuiltInMicAudioInput {

    const val SAMPLE_RATE_HZ = 16_000
    private const val MIN_BUFFER_SAMPLES = 6_400

    fun hasRecordAudioPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun findBuiltInMic(context: Context): AudioDeviceInfo? {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
        return audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
    }

    @SuppressLint("MissingPermission")
    fun openBuiltInMicAudioRecord(
        context: Context,
        sampleRateHz: Int = SAMPLE_RATE_HZ,
        bufferSamples: Int = MIN_BUFFER_SAMPLES,
    ): AudioRecord? {
        if (!hasRecordAudioPermission(context)) {
            return null
        }

        val minBufBytes = AudioRecord.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val safeMinBuf = if (minBufBytes > 0) minBufBytes else bufferSamples * 2
        val targetBufferBytes = maxOf(safeMinBuf, bufferSamples * 2)

        val record = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRateHz)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(targetBufferBytes)
                .build()
        } catch (_: IllegalArgumentException) {
            return null
        } catch (_: UnsupportedOperationException) {
            return null
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return null
        }

        // Route capture through phone's built-in mic to avoid triggering Bluetooth HFP/SCO.
        findBuiltInMic(context)?.let { builtInMic ->
            record.setPreferredDevice(builtInMic)
        }
        return record
    }

    fun computeNormalizedMicLevel(rms: Double): Float =
        (rms / 4_000.0).toFloat().coerceIn(0f, 1f)

    fun shortsToLittleEndianPcmBytes(samples: ShortArray, count: Int): ByteArray {
        val safeCount = count.coerceIn(0, samples.size)
        val buffer = ByteBuffer.allocate(safeCount * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until safeCount) {
            buffer.putShort(samples[i])
        }
        return buffer.array()
    }
}
