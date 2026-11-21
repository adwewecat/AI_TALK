package com.adwewecat.nana.voice

import android.content.Context
import android.media.AudioRecord
import com.adwewecat.nana.core.voice.EnergyVad
import com.adwewecat.nana.core.voice.FastCommandParser
import com.adwewecat.nana.core.voice.VadEvent
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Offline wake-word detector backed by Energy-VAD gating and a constrained Vietnamese Vosk grammar.
 * Captures exclusively via [BuiltInMicAudioInput] (`TYPE_BUILTIN_MIC`) so Bluetooth audio stays in A2DP.
 */
class VoskWakeWordEngine(
    private val context: Context,
) : WakeWordEngine {

    private val appContext = context.applicationContext
    private val running = AtomicBoolean(false)

    @Volatile
    private var workerThread: Thread? = null

    override fun isModelAvailable(): Boolean = isModelDirAvailable(modelDir(appContext))

    override fun start(onWakeDetected: () -> Unit, onMicLevel: (Float) -> Unit) {
        if (!running.compareAndSet(false, true)) {
            return
        }
        if (!isModelAvailable() || !BuiltInMicAudioInput.hasRecordAudioPermission(appContext)) {
            running.set(false)
            return
        }

        val thread = Thread(
            {
                runWakeLoop(onWakeDetected, onMicLevel)
            },
            "NaNa-VoskWake",
        ).apply {
            isDaemon = true
        }
        workerThread = thread
        thread.start()
    }

    override fun stop() {
        running.set(false)
        val thread = workerThread
        workerThread = null
        if (thread != null && thread !== Thread.currentThread()) {
            thread.interrupt()
            runCatching { thread.join(STOP_JOIN_TIMEOUT_MS) }
        }
    }

    private fun runWakeLoop(
        onWakeDetected: () -> Unit,
        onMicLevel: (Float) -> Unit,
    ) {
        var model: Model? = null
        var recognizer: Recognizer? = null
        var audioRecord: AudioRecord? = null

        try {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            model = Model(modelDir(appContext).absolutePath)
            recognizer = Recognizer(model, BuiltInMicAudioInput.SAMPLE_RATE_HZ.toFloat(), WAKE_GRAMMAR_JSON)
            audioRecord = BuiltInMicAudioInput.openBuiltInMicAudioRecord(appContext) ?: return

            audioRecord.startRecording()
            if (audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                return
            }

            val vad = EnergyVad(
                speechRmsThreshold = 550.0,
                silenceDurationMs = 900L,
                maxUtteranceMs = 4_000L,
            )
            val buffer = ShortArray(FRAME_SAMPLES)
            var preRollFrame: ShortArray? = null
            var lastWakeAtMs = 0L

            while (running.get() && !Thread.currentThread().isInterrupted) {
                val readCount = audioRecord.read(buffer, 0, buffer.size)
                if (readCount <= 0) {
                    continue
                }

                val rms = vad.computeRms(buffer, readCount)
                onMicLevel(BuiltInMicAudioInput.computeNormalizedMicLevel(rms))

                val frame = if (readCount == buffer.size) buffer.copyOf() else buffer.copyOf(readCount)
                val frameDurationMs = (readCount * 1_000L) / BuiltInMicAudioInput.SAMPLE_RATE_HZ
                val vadEvent = vad.feedFrame(frame, frameDurationMs)

                if (vadEvent == VadEvent.IDLE) {
                    preRollFrame = frame
                    continue
                }

                if (vadEvent == VadEvent.SPEECH_STARTED && preRollFrame != null) {
                    recognizer.acceptWaveForm(preRollFrame, preRollFrame.size)
                    preRollFrame = null
                }

                val isFinalized = recognizer.acceptWaveForm(frame, readCount)
                val transcript = extractTranscript(
                    if (isFinalized) recognizer.result else recognizer.partialResult,
                )

                val wakeDetected = if (transcript != null && FastCommandParser.matchesWakeWord(transcript)) {
                    true
                } else if (vadEvent == VadEvent.UTTERANCE_COMPLETE) {
                    val finalText = extractTranscript(recognizer.finalResult)
                    recognizer.reset()
                    finalText != null && FastCommandParser.matchesWakeWord(finalText)
                } else {
                    false
                }

                if (wakeDetected) {
                    val nowMs = System.currentTimeMillis()
                    if (nowMs - lastWakeAtMs >= WAKE_COOLDOWN_MS) {
                        lastWakeAtMs = nowMs
                        recognizer.reset()
                        vad.reset()
                        preRollFrame = null
                        onWakeDetected()
                    }
                }
            }
        } catch (_: IOException) {
            // Gracefully degrade when model files cannot be loaded.
        } catch (_: RuntimeException) {
            // Gracefully degrade if audio hardware or recognizer stops unexpectedly.
        } catch (_: UnsatisfiedLinkError) {
            // Gracefully degrade on JVM environments without native Vosk libraries.
        } finally {
            onMicLevel(0f)
            runCatching {
                audioRecord?.let { rec ->
                    if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        rec.stop()
                    }
                    rec.release()
                }
            }
            runCatching { recognizer?.close() }
            runCatching { model?.close() }
            running.set(false)
        }
    }

    companion object {
        const val VOSK_MODEL_DIR_NAME = "vosk-model-vn"
        private const val FRAME_SAMPLES = 1_600
        private const val STOP_JOIN_TIMEOUT_MS = 300L
        private const val WAKE_COOLDOWN_MS = 1_500L
        private const val WAKE_GRAMMAR_JSON = """["na na", "nana", "na nà", "na ná", "[unk]"]"""
        private const val CONSENT_GRAMMAR_JSON =
            """["ok", "ô kê", "ừ", "có", "được", "đồng ý", "không", "thôi", "[unk]"]"""

        private val json = Json { ignoreUnknownKeys = true }

        fun modelDir(context: Context): File = File(context.filesDir, VOSK_MODEL_DIR_NAME)

        private fun isModelDirAvailable(dir: File): Boolean =
            dir.isDirectory && dir.listFiles()?.isNotEmpty() == true

        /**
         * Best-effort offline recognition of short Vietnamese consent replies from 16kHz PCM
         * when the Vosk model is installed locally, avoiding any paid network calls.
         */
        fun recognizeConsentFromPcm(context: Context, pcmBytes: ByteArray): String? {
            val dir = modelDir(context.applicationContext)
            if (!isModelDirAvailable(dir) || pcmBytes.isEmpty()) {
                return null
            }
            return try {
                LibVosk.setLogLevel(LogLevel.WARNINGS)
                Model(dir.absolutePath).use { model ->
                    Recognizer(model, BuiltInMicAudioInput.SAMPLE_RATE_HZ.toFloat(), CONSENT_GRAMMAR_JSON).use { rec ->
                        rec.acceptWaveForm(pcmBytes, pcmBytes.size)
                        extractTranscript(rec.finalResult)
                    }
                }
            } catch (_: IOException) {
                null
            } catch (_: RuntimeException) {
                null
            } catch (_: UnsatisfiedLinkError) {
                null
            }
        }

        internal fun extractTranscript(rawJson: String?): String? {
            if (rawJson.isNullOrBlank()) return null
            val obj = try {
                json.parseToJsonElement(rawJson) as? JsonObject
            } catch (_: SerializationException) {
                return null
            } ?: return null

            val candidate = (obj["text"] as? JsonPrimitive)?.contentOrNull
                ?: (obj["partial"] as? JsonPrimitive)?.contentOrNull
            return candidate?.trim()?.takeIf { it.isNotEmpty() && it != "[unk]" }
        }
    }
}
