package com.adwewecat.nana.actions

import android.app.Application
import android.app.SearchManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.AlarmClock
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.adwewecat.nana.core.model.DeviceCommand
import com.adwewecat.nana.core.model.RecordingSource
import com.adwewecat.nana.core.model.SyncStatus
import com.adwewecat.nana.data.local.AppSettings
import com.adwewecat.nana.data.local.NaNaDatabase
import com.adwewecat.nana.voice.SyncTrigger
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeviceActionsAndCallRecordingTest {

    private lateinit var context: Context
    private lateinit var db: NaNaDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = NaNaDatabase.createInMemory(context)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test(timeout = 15_000)
    fun ingestionSkipsZeroByteAndDeduplicatesAlreadyIndexedRecordings() = runTest {
        val store = RoomRecordingStore(db.callRecordingDao())
        val syncTrigger = CountingSyncTrigger()
        val coordinator = CallRecordingIngestionCoordinator(
            store = store,
            syncTrigger = syncTrigger,
            clockMs = { 1_793_500_000_000L },
        )

        val zeroByteCandidate = DiscoveredRecording(
            sourceUri = "content://com.android.externalstorage.documents/document/primary%3ARecord%2FCall%2Fempty.m4a",
            fileName = "empty.m4a",
            sizeBytes = 0L,
            mimeType = "audio/mp4",
            lastModifiedMs = 1_793_499_990_000L,
            contactHint = "Empty",
        )
        val validCandidate = DiscoveredRecording(
            sourceUri = "content://com.android.externalstorage.documents/document/primary%3ARecord%2FCall%2Fcall_0901234567.m4a",
            fileName = "call_0901234567.m4a",
            sizeBytes = 4_096L,
            mimeType = "audio/mp4",
            lastModifiedMs = 1_793_499_995_000L,
            contactHint = "Anh Nam",
        )

        val copiedUris = mutableListOf<String>()
        val firstIngested = coordinator.ingestNewRecordings(
            candidates = listOf(zeroByteCandidate, validCandidate, validCandidate),
        ) { candidate ->
            copiedUris.add(candidate.sourceUri)
            "/data/user/0/com.adwewecat.nana/files/call_recordings_staging/${candidate.fileName}"
        }

        assertEquals(1, firstIngested)
        assertEquals(listOf(validCandidate.sourceUri), copiedUris)
        assertEquals(1, syncTrigger.count)

        val stored = db.callRecordingDao().getAll()
        assertEquals(1, stored.size)
        assertEquals(validCandidate.sourceUri, stored.single().sourceUri)
        assertEquals(4_096L, stored.single().fileSizeBytes)
        assertEquals(SyncStatus.PENDING.name, stored.single().syncStatus)
        assertEquals(RecordingSource.ORIGINOS_NATIVE.name, stored.single().source)
        assertEquals("Anh Nam", stored.single().contactName)

        // Second pass with same (sourceUri, sizeBytes) must skip without re-copying or re-inserting.
        val secondIngested = coordinator.ingestNewRecordings(
            candidates = listOf(zeroByteCandidate, validCandidate),
        ) { candidate ->
            copiedUris.add(candidate.sourceUri)
            "/data/user/0/com.adwewecat.nana/files/call_recordings_staging/${candidate.fileName}"
        }

        assertEquals(0, secondIngested)
        assertEquals(1, copiedUris.size)
        assertEquals(1, db.callRecordingDao().getAll().size)
        assertEquals(1, syncTrigger.count)

        // If the same sourceUri finalizes at a larger size, (sourceUri, newSize) is treated as distinct.
        val finalizedCandidate = validCandidate.copy(sizeBytes = 8_192L)
        val thirdIngested = coordinator.ingestNewRecordings(listOf(finalizedCandidate)) { candidate ->
            copiedUris.add(candidate.sourceUri)
            "/data/user/0/com.adwewecat.nana/files/call_recordings_staging/final_${candidate.fileName}"
        }
        assertEquals(1, thirdIngested)
        assertEquals(2, db.callRecordingDao().getAll().size)
        assertEquals(2, syncTrigger.count)
    }

    @Test(timeout = 15_000)
    fun makeZaloCallOpensDirectLinkOrPackageIntent() = runTest {
        val executor = AndroidDeviceActionExecutor(context = context)
        val shadowApp = shadowOf(context as Application)

        val directResult = executor.execute(DeviceCommand.MakeZaloCall(target = "0901234567"))
        assertTrue(directResult.succeeded)
        val startedIntent = shadowApp.nextStartedActivity
        assertNotNull(startedIntent)
        assertEquals(Intent.ACTION_VIEW, startedIntent.action)
        assertEquals("https://zalo.me/0901234567", startedIntent.dataString)
    }

    @Test(timeout = 15_000)
    fun answerCallUsesThreeTierFallbackWithoutRequiringDefaultDialer() = runTest {
        val calls = mutableListOf<String>()

        // Tier 1 succeeds -> stops at Tier 1.
        val tier1Executor = AndroidDeviceActionExecutor(
            context = context,
            callControlBridge = recordingBridge(calls, tier1 = true, tier2 = true, tier3 = true),
        )
        val tier1Result = tier1Executor.execute(DeviceCommand.AnswerIncomingCall)
        assertTrue(tier1Result.succeeded)
        assertEquals(listOf("tier1:answer"), calls)

        // Tier 1 fails, Tier 2 succeeds -> falls back to Tier 2 and stops.
        calls.clear()
        val tier2Executor = AndroidDeviceActionExecutor(
            context = context,
            callControlBridge = recordingBridge(calls, tier1 = false, tier2 = true, tier3 = true),
        )
        val tier2Result = tier2Executor.execute(DeviceCommand.AnswerIncomingCall)
        assertTrue(tier2Result.succeeded)
        assertEquals(listOf("tier1:answer", "tier2:answer"), calls)

        // Tier 1 & Tier 2 fail, Tier 3 succeeds -> falls back to Tier 3.
        calls.clear()
        val tier3Executor = AndroidDeviceActionExecutor(
            context = context,
            callControlBridge = recordingBridge(calls, tier1 = false, tier2 = false, tier3 = true),
        )
        val tier3Result = tier3Executor.execute(DeviceCommand.AnswerIncomingCall)
        assertTrue(tier3Result.succeeded)
        assertEquals(listOf("tier1:answer", "tier2:answer", "tier3:answer"), calls)

        // All 3 fail -> returns failure with Vietnamese guidance.
        calls.clear()
        val failExecutor = AndroidDeviceActionExecutor(
            context = context,
            callControlBridge = recordingBridge(calls, tier1 = false, tier2 = false, tier3 = false),
        )
        val failResult = failExecutor.execute(DeviceCommand.AnswerIncomingCall)
        assertFalse(failResult.succeeded)
        assertEquals(listOf("tier1:answer", "tier2:answer", "tier3:answer"), calls)
        assertTrue(failResult.spokenFeedback.isNotBlank())

        // Verify EndCall also follows Tier 1 -> Tier 2 -> Tier 3 order.
        calls.clear()
        val endExecutor = AndroidDeviceActionExecutor(
            context = context,
            callControlBridge = recordingBridge(calls, tier1 = false, tier2 = false, tier3 = true),
        )
        val endResult = endExecutor.execute(DeviceCommand.EndCall)
        assertTrue(endResult.succeeded)
        assertEquals(listOf("tier1:decline", "tier2:decline", "tier3:decline"), calls)

        // Verify AndroidManifest declares zero InCallService, ROLE_DIALER, AccessibilityService, or NotificationListener components.
        val pm = context.packageManager
        val pkgInfo = pm.getPackageInfo(
            context.packageName,
            android.content.pm.PackageManager.GET_SERVICES or android.content.pm.PackageManager.GET_PERMISSIONS,
        )
        val serviceNames = pkgInfo.services.orEmpty().map { it.name }
        assertTrue(serviceNames.none { it.contains("InCall", ignoreCase = true) })
        assertTrue(serviceNames.none { it.contains("Accessibility", ignoreCase = true) })
        assertTrue(serviceNames.none { it.contains("NotificationListener", ignoreCase = true) })
        val perms = pkgInfo.requestedPermissions.orEmpty().toList()
        assertTrue(perms.none { it.contains("DIALER", ignoreCase = true) })
        assertTrue(perms.none { it == "android.permission.BIND_ACCESSIBILITY_SERVICE" })
        assertTrue(perms.none { it == "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" })
        assertTrue(perms.none { it == "android.permission.QUERY_ALL_PACKAGES" })
        assertTrue(perms.none { it == "android.permission.ANSWER_PHONE_CALLS" })
    }

    @Test(timeout = 15_000)
    fun openYouTubeSearchLaunchesYouTubeIntent() = runTest {
        val executor = AndroidDeviceActionExecutor(context = context)
        val result = executor.execute(
            DeviceCommand.OpenYouTubeSearch(query = "Lệ Lưu Ly", autoPlay = true),
        )

        assertTrue(result.succeeded)

        val startedIntent = shadowOf(context as Application).nextStartedActivity
        assertNotNull(startedIntent)
        assertEquals("com.google.android.youtube", startedIntent.`package`)
        assertEquals(Intent.ACTION_SEARCH, startedIntent.action)
        assertEquals("Lệ Lưu Ly", startedIntent.getStringExtra(SearchManager.QUERY))

        val searchOnlyResult = executor.execute(
            DeviceCommand.OpenYouTubeSearch(query = "Nơi này có anh", autoPlay = false),
        )
        assertTrue(searchOnlyResult.succeeded)
    }

    @Test(timeout = 15_000)
    fun deviceExecutorHandlesAlarmTimerWebSearchAndMissingCallPermissions() = runTest {
        val executor = AndroidDeviceActionExecutor(context = context)
        val shadowApp = shadowOf(context as Application)

        val alarmResult = executor.execute(DeviceCommand.SetAlarm(hour = 7, minute = 30, label = "Đi làm"))
        assertTrue(alarmResult.succeeded)
        val alarmIntent = shadowApp.nextStartedActivity
        assertEquals(AlarmClock.ACTION_SET_ALARM, alarmIntent.action)
        assertEquals(7, alarmIntent.getIntExtra(AlarmClock.EXTRA_HOUR, -1))
        assertEquals(30, alarmIntent.getIntExtra(AlarmClock.EXTRA_MINUTES, -1))

        val timerResult = executor.execute(DeviceCommand.SetTimer(seconds = 90, label = "Pha trà"))
        assertTrue(timerResult.succeeded)
        val timerIntent = shadowApp.nextStartedActivity
        assertEquals(AlarmClock.ACTION_SET_TIMER, timerIntent.action)
        assertEquals(90, timerIntent.getIntExtra(AlarmClock.EXTRA_LENGTH, -1))

        val searchResult = executor.execute(DeviceCommand.WebSearch(query = "thời tiết Hà Nội"))
        assertTrue(searchResult.succeeded)
        val searchIntent = shadowApp.nextStartedActivity
        assertEquals(Intent.ACTION_WEB_SEARCH, searchIntent.action)

        // Missing CALL_PHONE permission yields graceful Vietnamese guidance + App settings route.
        val callResult = executor.execute(DeviceCommand.MakePhoneCall(target = "0901234567"))
        assertFalse(callResult.succeeded)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, callResult.missingPermissionAction)
    }

    @Test(timeout = 15_000)
    fun callStateMonitorTriggersScanAfterOffhookToIdle() = runTest {
        var scannedUri: String? = null
        val store = RoomRecordingStore(db.callRecordingDao())
        val coordinator = CallRecordingIngestionCoordinator(store, CountingSyncTrigger())
        val watcher = object : OriginOsCallRecordingWatcher(context, coordinator) {
            override suspend fun scanAndIngest(treeUri: android.net.Uri): Int {
                scannedUri = treeUri.toString()
                return 0
            }
        }
        val monitor = CallStateMonitor(
            context = context,
            scope = this,
            settingsProvider = {
                AppSettings(originOsRecordingFolderUri = "content://com.android.externalstorage.documents/tree/primary%3ARecord%2FCall")
            },
            recordingWatcher = watcher,
            settleDelayMs = 0L,
        )

        monitor.onCallStateObserved(android.telephony.TelephonyManager.CALL_STATE_OFFHOOK)
        monitor.onCallStateObserved(android.telephony.TelephonyManager.CALL_STATE_IDLE)
        testScheduler.advanceUntilIdle()

        assertEquals(
            "content://com.android.externalstorage.documents/tree/primary%3ARecord%2FCall",
            scannedUri,
        )
    }

    @Test(timeout = 15_000)
    fun originOsWatcherScansSafTreeSkipsZeroAndMismatchedFilesAndStagesValidAudio() = runTest {
        val provider = Robolectric.setupContentProvider(FakeSafProvider::class.java, "com.test.safdocs")
        val treeUri = DocumentsContract.buildTreeDocumentUri("com.test.safdocs", "Record/Call")
        val emptyDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, "Record/Call/empty.m4a")
        val growingDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, "Record/Call/growing.m4a")
        val validDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, "Record/Call/Record_0909888777.m4a")

        provider.rows = listOf(
            arrayOf("Record/Call/empty.m4a", "empty.m4a", "audio/mp4", 0L, 1000L),
            arrayOf("Record/Call/growing.m4a", "growing.m4a", "audio/mp4", 512L, 2000L),
            arrayOf("Record/Call/Record_0909888777.m4a", "Record_0909888777.m4a", "audio/mp4", 16L, 3000L),
        )
        val shadowResolver = shadowOf(context.contentResolver)
        shadowResolver.registerInputStream(emptyDocUri, ByteArrayInputStream(ByteArray(0)))
        // Reported size 512 but actual stream only 4 bytes -> rejected as unstable/incomplete.
        shadowResolver.registerInputStream(growingDocUri, ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)))
        shadowResolver.registerInputStream(validDocUri, ByteArrayInputStream(ByteArray(16) { 7 }))

        val store = RoomRecordingStore(db.callRecordingDao())
        val syncTrigger = CountingSyncTrigger()
        val watcher = OriginOsCallRecordingWatcher(
            context = context,
            coordinator = CallRecordingIngestionCoordinator(store, syncTrigger),
        )

        val stagedCount = watcher.scanAndIngest(treeUri)
        assertEquals(1, stagedCount)
        assertEquals(1, syncTrigger.count)

        val records = db.callRecordingDao().getAll()
        assertEquals(1, records.size)
        val stagedFile = File(records.single().stagedPath!!)
        assertTrue(stagedFile.exists())
        assertEquals(16L, stagedFile.length())
        assertEquals("0909888777", records.single().phoneNumber)

        // Clean up staged test artifacts and verify InAppCallRecorder fails safely without RECORD_AUDIO.
        watcher.stagingDirectory().deleteRecursively()
        val fallbackRecorder = InAppCallRecorder(context)
        assertFalse(fallbackRecorder.startRecording("0909888777"))
        assertNull(fallbackRecorder.stopRecording())
    }

    private class FakeSafProvider : ContentProvider() {
        var rows: List<Array<Any>> = emptyList()

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            val cols = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            )
            return MatrixCursor(cols).apply {
                rows.forEach { addRow(it) }
            }
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }

    private fun recordingBridge(
        calls: MutableList<String>,
        tier1: Boolean,
        tier2: Boolean,
        tier3: Boolean,
    ): CallControlBridge = object : CallControlBridge {
        override fun triggerNotificationCallAction(answer: Boolean): Boolean {
            calls.add("tier1:${if (answer) "answer" else "decline"}")
            return tier1
        }

        override fun triggerTelecomCallAction(answer: Boolean): Boolean {
            calls.add("tier2:${if (answer) "answer" else "decline"}")
            return tier2
        }

        override fun triggerAccessibilityCallAction(answer: Boolean): Boolean {
            calls.add("tier3:${if (answer) "answer" else "decline"}")
            return tier3
        }
    }

    private class CountingSyncTrigger : SyncTrigger {
        var count = 0
        override fun enqueueWifiSync() {
            count++
        }
    }
}
