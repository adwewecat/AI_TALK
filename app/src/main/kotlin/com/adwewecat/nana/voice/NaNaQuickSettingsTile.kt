package com.adwewecat.nana.voice

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import com.adwewecat.nana.R

/**
 * Quick Settings tile providing a 1-tap toggle for [NaNaVoiceService].
 */
class NaNaQuickSettingsTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshTileState(NaNaVoiceService.isRunning.value)
    }

    override fun onClick() {
        super.onClick()
        if (NaNaVoiceService.isRunning.value) {
            startService(Intent(this, NaNaVoiceService::class.java).setAction(NaNaVoiceService.ACTION_STOP))
            refreshTileState(active = false)
            return
        }

        if (!BuiltInMicAudioInput.hasRecordAudioPermission(this)) {
            openAppForPermission()
            refreshTileState(active = false)
            return
        }

        ContextCompat.startForegroundService(
            this,
            Intent(this, NaNaVoiceService::class.java).setAction(NaNaVoiceService.ACTION_START),
        )
        refreshTileState(active = true)
    }

    private fun refreshTileState(active: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_nana_label)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(
                if (active) R.string.tile_nana_active else R.string.tile_nana_inactive,
            )
        }
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openAppForPermission() {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: return
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(launchIntent)
        }
    }
}
