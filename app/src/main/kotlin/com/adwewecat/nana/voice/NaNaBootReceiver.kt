package com.adwewecat.nana.voice

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.adwewecat.nana.R

/**
 * Android 15/16 compliant boot receiver.
 * Android 15+ prohibits launching a `microphone` Foreground Service directly from `BOOT_COMPLETED`,
 * so this posts a 1-tap notification allowing the user to revive background "Na Na" listening
 * straight from the lock screen / shade without opening the app UI.
 */
class NaNaBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }
        if (!BuiltInMicAudioInput.hasRecordAudioPermission(context)) {
            return
        }

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val channel = NotificationChannel(
            BOOT_CHANNEL_ID,
            "Khởi động lại Na Na",
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        manager.createNotificationChannel(channel)

        val startServiceIntent = Intent(context, NaNaVoiceService::class.java)
            .setAction(NaNaVoiceService.ACTION_START)
        val pendingStart = PendingIntent.getForegroundService(
            context,
            BOOT_REQUEST_CODE,
            startServiceIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, BOOT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText("Bấm 1 chạm để bật lại Na Na lắng nghe chạy ngầm")
            .setAutoCancel(true)
            .setContentIntent(pendingStart)
            .build()

        runCatching { manager.notify(BOOT_NOTIFICATION_ID, notification) }
    }

    private companion object {
        const val BOOT_CHANNEL_ID = "nana_boot_revive_channel"
        const val BOOT_NOTIFICATION_ID = 1002
        const val BOOT_REQUEST_CODE = 21
    }
}
