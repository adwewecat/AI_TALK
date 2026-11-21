package com.adwewecat.nana.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import androidx.core.content.ContextCompat

/**
 * Optional `ROLE_ASSISTANT` hook allowing Na Na to be invoked from the system assistant gesture.
 */
class NaNaVoiceInteractionService : VoiceInteractionService() {

    override fun onReady() {
        super.onReady()
        // Never auto-start the microphone foreground service on system boot or ready callback.
    }
}

/**
 * Minimal session host paired with [NaNaVoiceInteractionService] that starts a Push-to-Talk
 * turn on [NaNaVoiceService] when the user triggers the system assistant gesture.
 */
class NaNaVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession =
        NaNaVoiceInteractionSession(this)
}

private class NaNaVoiceInteractionSession(
    private val serviceContext: Context,
) : VoiceInteractionSession(serviceContext) {

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        if (BuiltInMicAudioInput.hasRecordAudioPermission(serviceContext)) {
            ContextCompat.startForegroundService(
                serviceContext,
                Intent(serviceContext, NaNaVoiceService::class.java)
                    .setAction(NaNaVoiceService.ACTION_PUSH_TO_TALK),
            )
        } else {
            serviceContext.packageManager.getLaunchIntentForPackage(serviceContext.packageName)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                serviceContext.startActivity(it)
            }
        }
        finish()
    }
}

/**
 * Minimal [RecognitionService] stub referenced by `voice_interaction_service.xml` so Android's
 * system assistant settings accept the component cleanly without resetting the default assistant.
 */
class NaNaRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) = Unit

    override fun onCancel(listener: Callback?) = Unit

    override fun onStopListening(listener: Callback?) = Unit
}
