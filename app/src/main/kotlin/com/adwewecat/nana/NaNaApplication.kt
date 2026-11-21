package com.adwewecat.nana

import android.app.Application
import android.os.Build
import com.adwewecat.nana.data.sync.DriveSyncDependencies
import com.adwewecat.nana.data.sync.DriveSyncDependenciesProvider
import com.adwewecat.nana.di.AppContainer
import com.adwewecat.nana.voice.NaNaVoiceRuntime
import com.adwewecat.nana.voice.NaNaVoiceRuntimeProvider

/**
 * Application entry point holding the shared [AppContainer] and supplying dependencies
 * to background components ([com.adwewecat.nana.data.sync.DriveSyncWorker] and
 * [com.adwewecat.nana.voice.NaNaVoiceService]).
 */
class NaNaApplication :
    Application(),
    DriveSyncDependenciesProvider,
    NaNaVoiceRuntimeProvider {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        // Robolectric unit tests manage WorkManager and Telephony state explicitly.
        if (!"robolectric".equals(Build.FINGERPRINT, ignoreCase = true)) {
            runCatching { container.syncScheduler.schedulePeriodicWifiSync() }
            runCatching { container.callStateMonitor.start() }
        }
    }

    override fun provideDriveSyncDependencies(): DriveSyncDependencies =
        container.provideDriveSyncDependencies()

    override fun provideVoiceRuntime(): NaNaVoiceRuntime =
        container.provideVoiceRuntime()
}
