package com.adwewecat.nana.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Schedules one-time and periodic Google Drive sync jobs strictly constrained to unmetered Wi-Fi.
 */
class DriveSyncScheduler(
    context: Context,
) {
    private val appContext = context.applicationContext

    /** Builds a one-time [DriveSyncWorker] request requiring [NetworkType.UNMETERED]. */
    fun buildOneTimeSyncRequest(): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<DriveSyncWorker>()
            .setConstraints(unmeteredConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
            .build()

    /** Enqueues a unique one-time Wi-Fi sync request using [ExistingWorkPolicy.KEEP]. */
    fun enqueueSync() {
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            UNIQUE_ONE_TIME_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            buildOneTimeSyncRequest(),
        )
    }

    /** Builds a 30-minute periodic [DriveSyncWorker] request requiring [NetworkType.UNMETERED]. */
    fun buildPeriodicSyncRequest(): PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<DriveSyncWorker>(30L, TimeUnit.MINUTES)
            .setConstraints(unmeteredConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5L, TimeUnit.MINUTES)
            .build()

    /** Registers the periodic 30-minute unmetered Wi-Fi Drive backup job. */
    fun schedulePeriodicWifiSync() {
        WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            buildPeriodicSyncRequest(),
        )
    }

    private fun unmeteredConstraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.UNMETERED)
        .build()

    companion object {
        const val UNIQUE_ONE_TIME_WORK_NAME = "nana_wifi_sync"
        const val UNIQUE_PERIODIC_WORK_NAME = "nana_wifi_periodic_sync"
    }
}
