package com.adwewecat.nana.actions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.adwewecat.nana.data.local.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Observes telephony call state transitions (`TelephonyCallback` on API 31+, `PhoneStateListener`
 * fallback on API 26–30) and schedules a settle-delay OriginOS SAF recording scan whenever a call
 * finishes (`CALL_STATE_OFFHOOK -> CALL_STATE_IDLE`).
 */
class CallStateMonitor(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val settingsProvider: () -> AppSettings = { AppSettings() },
    private val recordingWatcher: OriginOsCallRecordingWatcher? = null,
    private val inAppCallRecorder: InAppCallRecorder? = null,
    private val inAppIngestionCoordinator: CallRecordingIngestionCoordinator? = null,
    private val settleDelayMs: Long = DEFAULT_SETTLE_DELAY_MS,
    private val onIncomingCallRinging: ((phoneNumber: String?) -> Unit)? = null,
) {

    @Volatile
    private var lastCallState: Int = TelephonyManager.CALL_STATE_IDLE

    @Volatile
    private var registered = false

    private var telephonyCallback: TelephonyCallback? = null

    @Suppress("DEPRECATION")
    private var legacyPhoneStateListener: android.telephony.PhoneStateListener? = null
    private var pendingScanJob: Job? = null

    /** Registers call-state listening if `READ_PHONE_STATE` is granted. */
    fun start(): Boolean {
        if (registered) {
            return true
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return false

        lastCallState = runCatching {
            @Suppress("DEPRECATION")
            telephonyManager.callState
        }.getOrDefault(TelephonyManager.CALL_STATE_IDLE)

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val callback = Api31CallStateCallback { state ->
                    onCallStateObserved(state)
                }
                telephonyManager.registerTelephonyCallback(
                    ContextCompat.getMainExecutor(context),
                    callback,
                )
                telephonyCallback = callback
            } else {
                registerLegacyListener(telephonyManager)
            }
            registered = true
            true
        } catch (_: SecurityException) {
            false
        }
    }

    /** Unregisters call-state callbacks and cancels any pending post-call scan. */
    fun stop() {
        pendingScanJob?.cancel()
        pendingScanJob = null
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        if (telephonyManager != null && registered) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                telephonyCallback?.let { cb ->
                    runCatching { telephonyManager.unregisterTelephonyCallback(cb) }
                }
            } else {
                unregisterLegacyListener(telephonyManager)
            }
        }
        telephonyCallback = null
        legacyPhoneStateListener = null
        registered = false
    }

    @Suppress("DEPRECATION")
    private fun registerLegacyListener(telephonyManager: TelephonyManager) {
        val listener = object : android.telephony.PhoneStateListener() {
            @Deprecated("Deprecated in Java")
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                onCallStateObserved(state, phoneNumber)
            }
        }
        telephonyManager.listen(listener, android.telephony.PhoneStateListener.LISTEN_CALL_STATE)
        legacyPhoneStateListener = listener
    }

    @Suppress("DEPRECATION")
    private fun unregisterLegacyListener(telephonyManager: TelephonyManager) {
        legacyPhoneStateListener?.let { listener ->
            runCatching {
                telephonyManager.listen(listener, android.telephony.PhoneStateListener.LISTEN_NONE)
            }
        }
    }

    /** Handles a telephony state transition and triggers post-call recording ingestion when a call ends. */
    fun onCallStateObserved(newState: Int, phoneNumber: String? = null) {
        val previousState = lastCallState
        lastCallState = newState

        when (newState) {
            TelephonyManager.CALL_STATE_RINGING -> {
                onIncomingCallRinging?.invoke(phoneNumber)
            }

            TelephonyManager.CALL_STATE_OFFHOOK -> {
                val settings = settingsProvider()
                if (settings.inAppCallRecorderFallback) {
                    inAppCallRecorder?.startRecording(phoneNumber = phoneNumber.orEmpty())
                }
            }

            TelephonyManager.CALL_STATE_IDLE -> {
                if (previousState == TelephonyManager.CALL_STATE_OFFHOOK) {
                    schedulePostCallScanAndFallbackIngest()
                }
            }
        }
    }

    private fun schedulePostCallScanAndFallbackIngest() {
        pendingScanJob?.cancel()
        pendingScanJob = scope.launch {
            val recorder = inAppCallRecorder
            val coordinator = inAppIngestionCoordinator
            if (recorder != null && recorder.isRecording()) {
                if (coordinator != null) {
                    recorder.stopAndIngest(coordinator)
                } else {
                    recorder.stopRecording()
                }
            }

            if (settleDelayMs > 0L) {
                delay(settleDelayMs)
            }

            val folderUriString = settingsProvider().originOsRecordingFolderUri?.takeIf { it.isNotBlank() }
                ?: return@launch
            recordingWatcher?.scanAndIngest(Uri.parse(folderUriString))
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private class Api31CallStateCallback(
        private val onState: (Int) -> Unit,
    ) : TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) {
            onState(state)
        }
    }

    companion object {
        const val DEFAULT_SETTLE_DELAY_MS = 2_000L
    }
}
