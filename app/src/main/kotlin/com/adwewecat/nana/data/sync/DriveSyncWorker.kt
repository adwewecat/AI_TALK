package com.adwewecat.nana.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.adwewecat.nana.core.sync.CallRecordingRetentionPolicy
import com.adwewecat.nana.core.sync.GoogleDriveClient
import com.adwewecat.nana.core.sync.SyncOutcome
import com.adwewecat.nana.core.sync.WifiNetworkChecker
import com.adwewecat.nana.core.sync.WifiOnlySyncCoordinator
import com.adwewecat.nana.data.auth.GoogleDriveAuthManager
import com.adwewecat.nana.data.local.EncryptedApiKeyVault
import com.adwewecat.nana.data.local.NaNaDatabase
import com.adwewecat.nana.data.local.RoomSyncStorageGateway
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/** Dependencies required by [DriveSyncWorker] during a sync execution. */
data class DriveSyncDependencies(
    val database: NaNaDatabase,
    val vault: EncryptedApiKeyVault,
    val authManager: GoogleDriveAuthManager,
    val wifiChecker: WifiNetworkChecker,
    val driveClient: GoogleDriveClient,
    val clockMs: () -> Long = { System.currentTimeMillis() },
)

/** Implemented by `Application` (or test factories) to supply shared [DriveSyncDependencies]. */
fun interface DriveSyncDependenciesProvider {
    fun provideDriveSyncDependencies(): DriveSyncDependencies
}

/**
 * WorkManager worker running [WifiOnlySyncCoordinator] strictly over unmetered Wi-Fi,
 * cancelling immediately via a live [ConnectivityManager.NetworkCallback] if Wi-Fi drops,
 * flagging `needsReauth` on 401/expired tokens without wiping local data, and running
 * post-sync staged recording retention cleanup.
 */
class DriveSyncWorker : CoroutineWorker {

    private val customProvider: DriveSyncDependenciesProvider?

    constructor(
        appContext: Context,
        params: WorkerParameters,
    ) : super(appContext, params) {
        this.customProvider = appContext.applicationContext as? DriveSyncDependenciesProvider
    }

    constructor(
        appContext: Context,
        params: WorkerParameters,
        dependenciesProvider: DriveSyncDependenciesProvider,
    ) : super(appContext, params) {
        this.customProvider = dependenciesProvider
    }

    override suspend fun doWork(): Result {
        val ownsAuthManager = customProvider == null
        val deps = customProvider?.provideDriveSyncDependencies() ?: createDefaultDependencies(applicationContext)
        try {
            if (!deps.wifiChecker.isUnmeteredWifiConnected()) {
                return Result.retry()
            }

            val authStatus = deps.authManager.authStateFlow.value
            if (authStatus.needsReauth) {
                return Result.failure()
            }

            val accessToken = deps.authManager.getFreshAccessToken()
            if (accessToken.isNullOrBlank()) {
                val updatedStatus = deps.authManager.authStateFlow.value
                return when {
                    updatedStatus.needsReauth -> Result.failure()
                    !updatedStatus.isSignedIn -> Result.success()
                    else -> Result.retry()
                }
            }

            val storage = RoomSyncStorageGateway(deps.database)
            storage.resetStaleUploadingRecordings()

            val outcome = runSyncWithLiveWifiGuard(deps, storage, accessToken)
                ?: return Result.retry()

            return when (outcome) {
                SyncOutcome.COMPLETED -> {
                    cleanupExpiredStagedRecordings(deps, storage)
                    Result.success()
                }
                SyncOutcome.PAUSED_NO_WIFI -> Result.retry()
                SyncOutcome.RETRYABLE_ERROR -> Result.retry()
                SyncOutcome.NEEDS_REAUTH -> {
                    deps.authManager.markNeedsReauth()
                    Result.failure()
                }
            }
        } finally {
            if (ownsAuthManager) {
                deps.authManager.dispose()
            }
        }
    }

    private suspend fun runSyncWithLiveWifiGuard(
        deps: DriveSyncDependencies,
        storage: RoomSyncStorageGateway,
        accessToken: String,
    ): SyncOutcome? = coroutineScope {
        val wifiDropped = AtomicBoolean(false)
        val coordinator = WifiOnlySyncCoordinator(
            wifiChecker = deps.wifiChecker,
            driveClient = deps.driveClient,
            storage = storage,
            clockMs = deps.clockMs,
        )

        val syncJob = async { coordinator.syncNow(accessToken) }
        val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

        val callback = object : ConnectivityManager.NetworkCallback() {
            private fun cancelIfWifiLost() {
                if (wifiDropped.compareAndSet(false, true)) {
                    syncJob.cancel(CancellationException("Unmetered Wi-Fi disconnected during Drive sync"))
                }
            }

            override fun onAvailable(network: Network) {
                // Android 10+ warns that capabilities may be null inside onAvailable before
                // onCapabilitiesChanged fires; only cancel here if capabilities are populated
                // and explicitly non-unmetered Wi-Fi.
                val caps = cm?.getNetworkCapabilities(network) ?: return
                if (!AndroidWifiNetworkChecker.isUnmeteredWifi(caps)) {
                    cancelIfWifiLost()
                }
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                if (!AndroidWifiNetworkChecker.isUnmeteredWifi(networkCapabilities) ||
                    !deps.wifiChecker.isUnmeteredWifiConnected()
                ) {
                    cancelIfWifiLost()
                }
            }

            override fun onLost(network: Network) {
                if (!deps.wifiChecker.isUnmeteredWifiConnected()) {
                    cancelIfWifiLost()
                }
            }

            override fun onUnavailable() {
                cancelIfWifiLost()
            }
        }

        val registered = runCatching {
            cm?.registerDefaultNetworkCallback(callback)
        }.isSuccess

        try {
            syncJob.await()
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) {
                withContext(NonCancellable) {
                    storage.resetStaleUploadingRecordings()
                }
                throw e
            }
            if (wifiDropped.get()) {
                withContext(NonCancellable) {
                    storage.resetStaleUploadingRecordings()
                }
                null
            } else {
                throw e
            }
        } finally {
            if (registered) {
                runCatching { cm?.unregisterNetworkCallback(callback) }
            }
        }
    }

    private suspend fun cleanupExpiredStagedRecordings(
        deps: DriveSyncDependencies,
        storage: RoomSyncStorageGateway,
    ) {
        val settings = deps.vault.loadSettings()
        val policy = CallRecordingRetentionPolicy(
            retentionDaysAfterVerifiedSync = settings.recordingRetentionDays.coerceAtLeast(0),
            keepForever = settings.keepRecordingsForever,
        )
        val eligible = policy.selectStagedFilesEligibleForCleanup(
            records = storage.getAllRecordings(),
            nowMs = deps.clockMs(),
        )
        if (eligible.isEmpty()) return

        withContext(Dispatchers.IO) {
            for (record in eligible) {
                val stagedPath = record.stagedPath?.takeIf { it.isNotBlank() } ?: continue
                val file = File(stagedPath)
                val deletedOrAbsent = !file.exists() || file.delete()
                if (deletedOrAbsent) {
                    storage.clearRecordingStagedPath(record.id)
                }
            }
        }
    }

    private companion object {
        fun createDefaultDependencies(appContext: Context): DriveSyncDependencies {
            val db = NaNaDatabase.getInstance(appContext)
            val vault = EncryptedApiKeyVault.create(appContext)
            val authManager = GoogleDriveAuthManager(appContext, vault)
            val wifiChecker = AndroidWifiNetworkChecker(appContext)
            val driveClient = GoogleDriveClient(OkHttpClient())
            return DriveSyncDependencies(
                database = db,
                vault = vault,
                authManager = authManager,
                wifiChecker = wifiChecker,
                driveClient = driveClient,
            )
        }
    }
}
