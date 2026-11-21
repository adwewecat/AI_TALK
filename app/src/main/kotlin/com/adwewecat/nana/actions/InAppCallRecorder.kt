package com.adwewecat.nana.actions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import java.io.File
import java.util.UUID

/**
 * Fallback in-app `.m4a` call recorder using [MediaRecorder] and saving staged files under
 * `files/call_recordings_staging/`. Intended for devices or VoIP calls where OriginOS native
 * call recording is unavailable.
 */
class InAppCallRecorder(
    private val context: Context,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {

    private var recorder: MediaRecorder? = null
    private var activeFile: File? = null
    private var startedAtMs: Long = 0L
    private var activeContactHint: String = ""

    /** Returns true while an in-app fallback recording session is active. */
    @Synchronized
    fun isRecording(): Boolean = recorder != null

    /** Starts `.m4a` fallback recording into `files/call_recordings_staging/` if `RECORD_AUDIO` is granted. */
    @Synchronized
    fun startRecording(phoneNumber: String = "", contactName: String = ""): Boolean {
        if (recorder != null) {
            return true
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }

        val stagingDir = File(context.filesDir, OriginOsCallRecordingWatcher.STAGING_DIR_NAME).apply {
            mkdirs()
        }
        val now = clockMs()
        val outputFile = File(stagingDir, "inapp_${now}_${UUID.randomUUID().toString().take(6)}.m4a")
        val mediaRecorder = createMediaRecorder()

        return try {
            mediaRecorder.apply {
                setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(64_000)
                setAudioSamplingRate(16_000)
                setOutputFile(outputFile.absolutePath)
                prepare()
                start()
            }
            recorder = mediaRecorder
            activeFile = outputFile
            startedAtMs = now
            activeContactHint = contactName.ifBlank { phoneNumber }
            true
        } catch (_: Throwable) {
            runCatching { mediaRecorder.release() }
            outputFile.delete()
            recorder = null
            activeFile = null
            false
        }
    }

    /**
     * Stops active recording and returns a [DiscoveredRecording] pointing to the `.m4a` file,
     * deleting and returning `null` if the captured file is 0 bytes.
     */
    @Synchronized
    fun stopRecording(): DiscoveredRecording? {
        val activeRecorder = recorder ?: return null
        val file = activeFile
        val startedMs = startedAtMs
        val contactHint = activeContactHint
        recorder = null
        activeFile = null
        activeContactHint = ""

        runCatching { activeRecorder.stop() }
        runCatching { activeRecorder.reset() }
        runCatching { activeRecorder.release() }

        if (file == null || !file.exists()) {
            return null
        }
        val length = file.length()
        if (length <= 0L) {
            file.delete()
            return null
        }
        return DiscoveredRecording(
            sourceUri = Uri.fromFile(file).toString(),
            fileName = file.name,
            sizeBytes = length,
            mimeType = "audio/mp4",
            lastModifiedMs = startedMs.takeIf { it > 0L } ?: clockMs(),
            contactHint = contactHint,
        )
    }

    /** Stops recording (if active) and submits any non-empty artifact to [coordinator]. */
    suspend fun stopAndIngest(coordinator: CallRecordingIngestionCoordinator): Int {
        val captured = stopRecording() ?: return 0
        val stagedPath = Uri.parse(captured.sourceUri).path ?: return 0
        return coordinator.ingestNewRecordings(listOf(captured)) { stagedPath }
    }

    private fun createMediaRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
}
