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

            val vad = EnergyVad(
                speechRmsThreshold = 140.0,
                silenceDurationMs = 900L,
                maxUtteranceMs = 4_000L,
            )
            val buffer = ShortArray(FRAME_SAMPLES)
            var preRollFrame: ShortArray? = null
            var lastWakeAtMs = 0L

            // Self-healing loop: if another app (phone call, Zalo, camera) temporarily claims the mic,
            // release AudioRecord and retry every 2.5s so hands-free "Na Na" recovers automatically.
            while (running.get() && !Thread.currentThread().isInterrupted) {
                audioRecord = BuiltInMicAudioInput.openBuiltInMicAudioRecord(appContext)
                val rec = audioRecord
                if (rec == null) {
                    sleepQuietly(MIC_RETRY_DELAY_MS)
                    continue
                }

                try {
                    rec.startRecording()
                    if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        releaseAudioRecord(rec)
                        audioRecord = null
                        sleepQuietly(MIC_RETRY_DELAY_MS)
                        continue
                    }

                    var consecutiveReadErrors = 0
                    while (running.get() && !Thread.currentThread().isInterrupted) {
                        val readCount = rec.read(buffer, 0, buffer.size)
                        if (readCount < 0) {
                            // AudioRecord invalidated by phone call / audio focus loss -> reopen cleanly.
                            break
                        }
                        if (readCount == 0) {
                            consecutiveReadErrors++
                            if (consecutiveReadErrors >= MAX_EMPTY_READS_BEFORE_REOPEN) {
                                break
                            }
                            sleepQuietly(40L)
                            continue
                        }
                        consecutiveReadErrors = 0

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
                } catch (_: RuntimeException) {
                    // Recover after transient audio hardware interruption.
                } finally {
                    onMicLevel(0f)
                    releaseAudioRecord(rec)
                    audioRecord = null
                    vad.reset()
                    preRollFrame = null
                }

                if (running.get() && !Thread.currentThread().isInterrupted) {
                    sleepQuietly(MIC_RETRY_DELAY_MS)
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
            audioRecord?.let(::releaseAudioRecord)
            runCatching { recognizer?.close() }
            runCatching { model?.close() }
            running.set(false)
        }
    }

    private fun releaseAudioRecord(rec: AudioRecord) {
        runCatching {
            if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                rec.stop()
            }
            rec.release()
        }
    }

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        const val VOSK_MODEL_DIR_NAME = "vosk-model-vn"
        private const val FRAME_SAMPLES = 1_600
        private const val STOP_JOIN_TIMEOUT_MS = 300L
        private const val WAKE_COOLDOWN_MS = 1_500L
        private const val MIC_RETRY_DELAY_MS = 2_500L
        private const val MAX_EMPTY_READS_BEFORE_REOPEN = 25
        private const val WAKE_GRAMMAR_JSON =
            """["na na", "nana", "na na ơi", "nana ơi", "na nà", "na ná", "nà na", "ná na", "ơi na na", "[unk]"]"""
        private const val CONSENT_GRAMMAR_JSON =
            """["ok", "ô kê", "ừ", "có", "được", "đồng ý", "không", "thôi", "[unk]"]"""
        private const val LOCAL_COMMAND_GRAMMAR_JSON =
            """["bắt máy", "nghe máy", "cúp máy", "tắt cuộc gọi", "bật loa ngoài", "tắt loa ngoài", "bật đèn pin", "tắt đèn pin", "tạm biệt", "nghỉ nhé", "dừng lại", "[unk]"]"""

        private val json = Json { ignoreUnknownKeys = true }

        fun modelDir(context: Context): File = File(context.filesDir, VOSK_MODEL_DIR_NAME)

        private fun isModelDirAvailable(dir: File): Boolean =
            dir.isDirectory && dir.listFiles()?.isNotEmpty() == true

        /**
         * Best-effort offline recognition of short Vietnamese consent replies from 16kHz PCM
         * when the Vosk model is installed locally, avoiding any paid network calls.
         */
        fun recognizeConsentFromPcm(context: Context, pcmBytes: ByteArray): String? =
            recognizeWithGrammar(context, pcmBytes, CONSENT_GRAMMAR_JSON)

        /**
         * Best-effort offline recognition of narrow Vietnamese device-control commands from 16kHz PCM
         * when the Vosk model is installed locally, keeping general conversation on raw audio.
         */
        fun recognizeLocalCommandFromPcm(context: Context, pcmBytes: ByteArray): String? =
            recognizeWithGrammar(context, pcmBytes, LOCAL_COMMAND_GRAMMAR_JSON)

        private fun recognizeWithGrammar(
            context: Context,
            pcmBytes: ByteArray,
            grammarJson: String,
        ): String? {
            val dir = modelDir(context.applicationContext)
            if (!isModelDirAvailable(dir) || pcmBytes.isEmpty()) {
                return null
            }
            return try {
                LibVosk.setLogLevel(LogLevel.WARNINGS)
                Model(dir.absolutePath).use { model ->
                    Recognizer(model, BuiltInMicAudioInput.SAMPLE_RATE_HZ.toFloat(), grammarJson).use { rec ->
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
