package com.adwewecat.nana

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.adwewecat.nana.core.sync.SyncOutcome
import com.adwewecat.nana.di.AppContainer
import com.adwewecat.nana.ui.NaNaApp
import com.adwewecat.nana.ui.theme.NaNaTheme
import com.adwewecat.nana.voice.NaNaVoiceService
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/**
 * Main single-activity host for Na Na's Compose UI, runtime permission launchers,
 * SAF `OpenDocumentTree` picker (`Record/Call`), AppAuth Google Drive OAuth flow,
 * and [NaNaVoiceService] control.
 */
class MainActivity : ComponentActivity() {

    private val container: AppContainer
        get() = (application as NaNaApplication).container

    private val snackbarMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    private var resumeTick by mutableIntStateOf(0)
    private var pendingVoiceAction: String? = null

    private val runtimePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        resumeTick++
        if (results[Manifest.permission.READ_PHONE_STATE] == true ||
            hasPermission(Manifest.permission.READ_PHONE_STATE)
        ) {
            runCatching { container.callStateMonitor.start() }
        }

        val action = pendingVoiceAction
        pendingVoiceAction = null
        if (action != null) {
            if (hasPermission(Manifest.permission.RECORD_AUDIO)) {
                dispatchVoiceService(action)
            } else {
                snackbarMessages.tryEmit("Cần quyền Micro để bật giọng nói Na Na.")
            }
        }
    }

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        container.callRecordingWatcher.persistTreePermission(uri)
        container.updateSettings { it.copy(originOsRecordingFolderUri = uri.toString()) }
        lifecycleScope.launch {
            val count = container.scanOriginOsRecordings()
            snackbarMessages.tryEmit(
                if (count > 0) {
                    "Đã liên kết thư mục ghi âm và quét được $count file mới."
                } else {
                    "Đã lưu thư mục ghi âm Record/Call."
                },
            )
        }
    }

    private val driveAuthLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (data == null) {
            snackbarMessages.tryEmit("Đã hủy đăng nhập Google Drive.")
            return@registerForActivityResult
        }
        lifecycleScope.launch {
            val ok = container.authManager.handleAuthorizationIntent(data)
            if (ok) {
                snackbarMessages.tryEmit("Đăng nhập Google Drive thành công!")
                container.syncScheduler.enqueueSync()
            } else {
                snackbarMessages.tryEmit("Đăng nhập Google Drive chưa hoàn tất. Vui lòng thử lại.")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NaNaTheme {
                NaNaApp(
                    container = container,
                    snackbarMessages = snackbarMessages,
                    resumeTick = resumeTick,
                    onToggleVoiceService = ::toggleVoiceService,
                    onPushToTalk = ::triggerPushToTalk,
                    onSignInDrive = ::launchDriveSignIn,
                    onPickRecordingsFolder = { folderPickerLauncher.launch(null) },
                    onRequestMicPermission = ::requestMicrophonePermissions,
                    onRequestPhoneAndContactsPermission = ::requestPhoneAndContactsPermissions,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
        if (hasPermission(Manifest.permission.READ_PHONE_STATE)) {
            runCatching { container.callStateMonitor.start() }
        }
    }

    private fun toggleVoiceService(enable: Boolean) {
        if (enable) {
            container.updateSettings { it.copy(wakeWordEnabled = true) }
            startVoiceActionWithPermission(NaNaVoiceService.ACTION_START)
        } else {
            runCatching {
                startService(
                    Intent(this, NaNaVoiceService::class.java)
                        .setAction(NaNaVoiceService.ACTION_STOP),
                )
            }
        }
    }

    private fun triggerPushToTalk() {
        startVoiceActionWithPermission(NaNaVoiceService.ACTION_PUSH_TO_TALK)
    }

    private fun startVoiceActionWithPermission(action: String) {
        if (hasPermission(Manifest.permission.RECORD_AUDIO)) {
            dispatchVoiceService(action)
            return
        }
        pendingVoiceAction = action
        requestMicrophonePermissions()
    }

    private fun dispatchVoiceService(action: String) {
        runCatching {
            ContextCompat.startForegroundService(
                this,
                Intent(this, NaNaVoiceService::class.java).setAction(action),
            )
        }.onFailure {
            snackbarMessages.tryEmit("Không thể khởi động dịch vụ giọng nói: ${it.message ?: "lỗi hệ thống"}")
        }
    }

    private fun requestMicrophonePermissions() {
        val perms = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        runtimePermissionLauncher.launch(perms.toTypedArray())
    }

    private fun requestPhoneAndContactsPermissions() {
        val perms = buildList {
            add(Manifest.permission.READ_PHONE_STATE)
            add(Manifest.permission.ANSWER_PHONE_CALLS)
            add(Manifest.permission.CALL_PHONE)
            add(Manifest.permission.READ_CONTACTS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.READ_MEDIA_AUDIO)
            }
        }
        runtimePermissionLauncher.launch(perms.toTypedArray())
    }

    private fun launchDriveSignIn() {
        val authIntent = container.authManager.buildAuthIntent()
        if (authIntent == null) {
            snackbarMessages.tryEmit(
                "Chưa cấu hình GOOGLE_OAUTH_CLIENT_ID. Xem hướng dẫn trong SETUP_ANDROID.md.",
            )
            return
        }
        driveAuthLauncher.launch(authIntent)
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}

internal fun syncOutcomeMessage(outcome: SyncOutcome): String = when (outcome) {
    SyncOutcome.COMPLETED -> "Đồng bộ Google Drive hoàn tất."
    SyncOutcome.PAUSED_NO_WIFI -> "Đang tạm dừng: cần kết nối Wi-Fi không giới hạn."
    SyncOutcome.NEEDS_REAUTH -> "Phiên Google Drive đã hết hạn, vui lòng đăng nhập lại."
    SyncOutcome.RETRYABLE_ERROR -> "Lỗi mạng tạm thời, sẽ thử lại khi Wi-Fi ổn định."
}
