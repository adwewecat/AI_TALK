package com.adwewecat.nana.ui.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.adwewecat.nana.actions.NaNaAccessibilityService
import com.adwewecat.nana.actions.NaNaNotificationListener
import com.adwewecat.nana.core.model.ApiKeyEntry
import com.adwewecat.nana.core.model.KeyStatus
import com.adwewecat.nana.core.model.ProviderType
import com.adwewecat.nana.core.model.QuotaState
import com.adwewecat.nana.core.model.detectGeminiProvider
import com.adwewecat.nana.data.auth.DriveAuthStatus
import com.adwewecat.nana.data.local.AppSettings
import com.adwewecat.nana.di.AppContainer
import com.adwewecat.nana.ui.theme.NaNaPalette
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** Masks API keys before display or diagnostic rendering (`<= 10` chars -> `"***"`). */
object ApiKeyMasker {
    fun mask(rawKey: String): String {
        val trimmed = rawKey.trim()
        if (trimmed.length <= 10) {
            return "***"
        }
        return "${trimmed.take(6)}...${trimmed.takeLast(4)}"
    }
}

private data class KeyPreset(
    val title: String,
    val defaultLabel: String,
    val provider: ProviderType,
    val defaultModelId: String,
    val priority: Int,
    val isFreeTier: Boolean,
    val isTtsOnly: Boolean,
)

private val KEY_PRESETS = listOf(
    KeyPreset("Gemini Pro #1", "Gemini Pro #1", ProviderType.GEMINI_VERTEX_EXPRESS, "gemini-3.8-flash", 1, false, false),
    KeyPreset("Gemini Pro #2", "Gemini Pro #2", ProviderType.GEMINI_VERTEX_EXPRESS, "gemini-3.8-flash", 2, false, false),
    KeyPreset("Gemini 4 Neon", "Gemini 4 Neon", ProviderType.GEMINI_VERTEX_EXPRESS, "gemini-4-neon", 1, false, false),
    KeyPreset("Claude", "Claude", ProviderType.CLAUDE, "claude-sonnet-4-5", 3, false, false),
    KeyPreset("ChatGPT", "ChatGPT", ProviderType.OPENAI, "gpt-4o-mini", 4, false, false),
    KeyPreset("Free Key", "Em gái Free Key", ProviderType.FREE_GEMINI, "gemini-3.6-flash", 1, true, false),
    KeyPreset("Key TTS riêng", "TTS Riêng", ProviderType.GEMINI_AI_STUDIO, "gemini-3.8-flash-tts", 1, false, true),
)

/**
 * Tab 4 ("Cài đặt"): Encrypted API Key Vault, Persona & Voice settings,
 * Google Drive & Call Recording setup, and 1-tap OriginOS 6 (iQOO) permission checklist.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    apiKeys: List<ApiKeyEntry>,
    settings: AppSettings,
    quotaState: QuotaState,
    authStatus: DriveAuthStatus,
    isSyncing: Boolean,
    resumeTick: Int,
    onSaveKey: (ApiKeyEntry) -> Unit,
    onDeleteKey: (String) -> Unit,
    onTestKey: suspend (ApiKeyEntry) -> String,
    onReviveNaNa: () -> Unit,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    onSignInDrive: () -> Unit,
    onRestoreDrive: () -> Unit,
    onSignOutDrive: () -> Unit,
    onPickRecordingsFolder: () -> Unit,
    onRequestMicPermission: () -> Unit,
    onRequestPhoneAndContactsPermission: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var editingKey by remember { mutableStateOf<ApiKeyEntry?>(null) }
    var presetForNewKey by remember { mutableStateOf<KeyPreset?>(null) }
    var showAddKeyDialog by remember { mutableStateOf(false) }
    var keyTestResults by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var testingKeyId by remember { mutableStateOf<String?>(null) }

    val micGranted = remember(resumeTick) {
        hasPermission(context, Manifest.permission.RECORD_AUDIO)
    }
    val phoneGranted = remember(resumeTick) {
        hasPermission(context, Manifest.permission.READ_PHONE_STATE) &&
            hasPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) &&
            hasPermission(context, Manifest.permission.CALL_PHONE) &&
            hasPermission(context, Manifest.permission.READ_CONTACTS)
    }
    val notificationAccessEnabled = remember(resumeTick) {
        NaNaNotificationListener.isEnabled(context)
    }
    val accessibilityEnabled = remember(resumeTick) {
        NaNaAccessibilityService.isServiceEnabled(context)
    }
    val batteryUnrestricted = remember(resumeTick) {
        OriginOsSetupHelper.isIgnoringBatteryOptimizations(context)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            Text(
                text = "Cài đặt",
                style = MaterialTheme.typography.headlineSmall,
            )
        }

        // Section 1: Encrypted API Key Vault
        item {
            SettingsSectionCard(
                title = "Kho API Key (Mã hóa trên máy)",
                icon = Icons.Rounded.Key,
            ) {
                Text(
                    text = "API key được mã hóa bằng Android Keystore trên máy. Khóa AQ. tự động nhận diện cả Google AI Studio lẫn Vertex Express.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(10.dp))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    KEY_PRESETS.forEach { preset ->
                        OutlinedButton(
                            onClick = {
                                presetForNewKey = preset
                                editingKey = null
                                showAddKeyDialog = true
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(preset.title, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                if (apiKeys.isEmpty()) {
                    Text(
                        text = "Chưa có API key nào. Hãy bấm một mẫu ở trên để thêm Gemini Pro #1 / #2 hoặc Free Key.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NaNaPalette.AmberCooldown,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        apiKeys.forEach { entry ->
                            ApiKeyCard(
                                entry = entry,
                                isTesting = testingKeyId == entry.id,
                                testResult = keyTestResults[entry.id],
                                onTest = {
                                    testingKeyId = entry.id
                                    scope.launch {
                                        val msg = onTestKey(entry)
                                        keyTestResults = keyTestResults + (entry.id to msg)
                                        testingKeyId = null
                                    }
                                },
                                onEdit = {
                                    editingKey = entry
                                    presetForNewKey = null
                                    showAddKeyDialog = true
                                },
                                onDelete = { onDeleteKey(entry.id) },
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (quotaState == QuotaState.ACTIVE_NANA) {
                            "Trạng thái: Na Na khỏe mạnh"
                        } else {
                            "Trạng thái: ${quotaState.name}"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (quotaState == QuotaState.ACTIVE_NANA) {
                            NaNaPalette.MintHealthy
                        } else {
                            NaNaPalette.AmberCooldown
                        },
                    )
                    Button(onClick = onReviveNaNa) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Hồi sức Na Na ngay")
                    }
                }
            }
        }

        // Section 2: Persona & Voice
        item {
            SettingsSectionCard(
                title = "Tính cách & Giọng nói",
                icon = Icons.Rounded.RecordVoiceOver,
            ) {
                Text(
                    text = "Giọng Gemini Neural TTS",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Aoede", "Leda", "Kore").forEach { voiceName ->
                        FilterChip(
                            selected = settings.preferredGeminiVoice == voiceName,
                            onClick = { onUpdateSettings { it.copy(preferredGeminiVoice = voiceName) } },
                            label = { Text(voiceName) },
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Thời gian chờ im lặng: ${settings.silenceTimeoutSec} giây",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = settings.silenceTimeoutSec.toFloat(),
                    onValueChange = { value ->
                        onUpdateSettings { it.copy(silenceTimeoutSec = value.roundToInt().coerceIn(5, 60)) }
                    },
                    valueRange = 5f..60f,
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

                SettingSwitchRow(
                    title = "Cho phép chuyển sang Em gái khi hết quota",
                    subtitle = "Khi tất cả key chính hết hạn mức, Na Na sẽ hỏi ý kiến anh trước khi gọi Em gái dùng Free key.",
                    checked = settings.emGaiHandoffEnabled,
                    onCheckedChange = { enabled ->
                        onUpdateSettings { it.copy(emGaiHandoffEnabled = enabled) }
                    },
                )

                SettingSwitchRow(
                    title = "Giữ CPU thức khi tắt màn hình",
                    subtitle = "Tắt mặc định để tiết kiệm pin. Bật nếu muốn gọi \"Na Na\" ngay cả khi màn hình tắt lâu.",
                    checked = settings.screenOffWakeLock,
                    onCheckedChange = { enabled ->
                        onUpdateSettings { it.copy(screenOffWakeLock = enabled) }
                    },
                )
            }
        }

        // Section 3: Google Drive & Call Recording
        item {
            SettingsSectionCard(
                title = "Google Drive & Ghi âm",
                icon = Icons.Rounded.CloudDone,
            ) {
                Surface(
                    color = NaNaPalette.MintContainer,
                    shape = RoundedCornerShape(50),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Wifi,
                            contentDescription = null,
                            tint = NaNaPalette.MintHealthy,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Strict Wi-Fi-Only · Không bao giờ dùng 4G/5G",
                            style = MaterialTheme.typography.labelMedium,
                            color = NaNaPalette.MintHealthy,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = when {
                        !authStatus.isOAuthConfigured ->
                            "OAuth chưa cấu hình (thiếu GOOGLE_OAUTH_CLIENT_ID khi build APK)."
                        authStatus.needsReauth ->
                            "Phiên đăng nhập Google Drive đã hết hạn (${authStatus.email ?: "cần xác thực lại"})."
                        authStatus.isSignedIn ->
                            "Đã kết nối Google Drive: ${authStatus.email ?: "Đã xác thực"}"
                        else ->
                            "Chưa đăng nhập Google Drive"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (authStatus.needsReauth) NaNaPalette.CoralError else MaterialTheme.colorScheme.onSurface,
                )

                Spacer(modifier = Modifier.height(8.dp))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Button(onClick = onSignInDrive) {
                        Text(if (authStatus.isSignedIn) "Đổi tài khoản" else "Đăng nhập Google Drive")
                    }
                    OutlinedButton(
                        onClick = onRestoreDrive,
                        enabled = authStatus.isSignedIn && !isSyncing,
                    ) {
                        Text("Khôi phục từ Drive")
                    }
                    if (authStatus.isSignedIn || authStatus.needsReauth) {
                        OutlinedButton(onClick = onSignOutDrive) {
                            Text("Đăng xuất")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                Spacer(modifier = Modifier.height(10.dp))

                OutlinedButton(
                    onClick = onPickRecordingsFolder,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (settings.originOsRecordingFolderUri.isNullOrBlank()) {
                            "Chọn thư mục OriginOS Record/Call"
                        } else {
                            "Đổi thư mục Record/Call"
                        },
                    )
                }
                if (!settings.originOsRecordingFolderUri.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = settings.originOsRecordingFolderUri.orEmpty(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                SettingSwitchRow(
                    title = "Ghi âm dự phòng trong app (.m4a)",
                    subtitle = "Tự ghi âm trong app khi không dùng ghi âm gốc OriginOS.",
                    checked = settings.inAppCallRecorderFallback,
                    onCheckedChange = { enabled ->
                        onUpdateSettings { it.copy(inAppCallRecorderFallback = enabled) }
                    },
                )

                SettingSwitchRow(
                    title = "Tóm tắt cuộc gọi bằng AI",
                    subtitle = "Hiển thị nút tóm tắt nội dung cuộc gọi trong tab Ghi âm.",
                    checked = settings.aiCallSummaryEnabled,
                    onCheckedChange = { enabled ->
                        onUpdateSettings { it.copy(aiCallSummaryEnabled = enabled) }
                    },
                )

                SettingSwitchRow(
                    title = "Giữ file ghi âm trên máy vĩnh viễn",
                    subtitle = "Nếu tắt, app sẽ tự dọn file tạm trên máy sau khi xác nhận đã lên Drive an toàn.",
                    checked = settings.keepRecordingsForever,
                    onCheckedChange = { enabled ->
                        onUpdateSettings { it.copy(keepRecordingsForever = enabled) }
                    },
                )

                if (!settings.keepRecordingsForever) {
                    Text(
                        text = "Xóa file tạm trên máy sau khi đã đồng bộ Drive: ${settings.recordingRetentionDays} ngày",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Slider(
                        value = settings.recordingRetentionDays.coerceIn(1, 90).toFloat(),
                        onValueChange = { days ->
                            onUpdateSettings { it.copy(recordingRetentionDays = days.roundToInt().coerceIn(1, 90)) }
                        },
                        valueRange = 1f..90f,
                    )
                }
            }
        }

        // Section 4: OriginOS 6 (iQOO) 1-tap Checklist
        item {
            SettingsSectionCard(
                title = "Checklist OriginOS 6 (iQOO)",
                icon = Icons.Rounded.Security,
            ) {
                Text(
                    text = "Cấp đủ các quyền bên dưới để Na Na chạy nền ổn định trên OriginOS 6, nghe/tắt SIM & Zalo/Messenger, và tự phát YouTube.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(10.dp))

                ChecklistActionRow(
                    title = "Micro & Thông báo",
                    subtitle = "Thu âm giọng nói và hiện điều khiển chạy nền",
                    granted = micGranted,
                    icon = Icons.Rounded.Mic,
                    onClick = onRequestMicPermission,
                )
                ChecklistActionRow(
                    title = "Điện thoại & Danh bạ",
                    subtitle = "Gọi theo tên danh bạ, bắt máy SIM & quét sau cuộc gọi",
                    granted = phoneGranted,
                    icon = Icons.Rounded.PhoneAndroid,
                    onClick = onRequestPhoneAndContactsPermission,
                )
                ChecklistActionRow(
                    title = "Quyền truy cập thông báo (SIM + Zalo/Messenger)",
                    subtitle = "Nghe hoặc từ chối cuộc gọi không cần đổi trình quay số mặc định",
                    granted = notificationAccessEnabled,
                    icon = Icons.Rounded.NotificationsActive,
                    onClick = { OriginOsSetupHelper.launchSafely(context, OriginOsSetupHelper.notificationListenerIntent()) },
                )
                ChecklistActionRow(
                    title = "Trợ năng (YouTube Auto-Play & nút cuộc gọi)",
                    subtitle = "Tự bấm phát video đầu tiên khi yêu cầu mở nhạc YouTube",
                    granted = accessibilityEnabled,
                    icon = Icons.Rounded.AccessibilityNew,
                    onClick = { OriginOsSetupHelper.launchSafely(context, OriginOsSetupHelper.accessibilitySettingsIntent()) },
                )
                ChecklistActionRow(
                    title = "Bỏ qua tối ưu pin (Không hạn chế)",
                    subtitle = "Tránh bị OriginOS đóng dịch vụ giọng nói khi tắt màn hình",
                    granted = batteryUnrestricted,
                    icon = Icons.Rounded.BatteryChargingFull,
                    onClick = { OriginOsSetupHelper.launchSafely(context, OriginOsSetupHelper.batteryOptimizationIntent(context)) },
                )
                ChecklistActionRow(
                    title = "Tự khởi chạy & Chạy nền cao (iQOO)",
                    subtitle = "Mở trình quản lý quyền/pin iQOO để bật Autostart & High background power",
                    granted = null,
                    icon = Icons.Rounded.Tune,
                    onClick = { OriginOsSetupHelper.launchSafely(context, OriginOsSetupHelper.autostartIntent(context)) },
                )
                ChecklistActionRow(
                    title = "Giọng đọc tiếng Việt hệ thống (TTS offline)",
                    subtitle = "Mặc định Na Na dùng giọng nữ tiếng Việt online; mở mục này để chọn Google TTS nếu muốn nghe cả khi mất mạng",
                    granted = null,
                    icon = Icons.Rounded.RecordVoiceOver,
                    onClick = { OriginOsSetupHelper.launchSafely(context, OriginOsSetupHelper.ttsSettingsIntent(context)) },
                )
            }
        }
    }

    if (showAddKeyDialog) {
        ApiKeyEditorDialog(
            existing = editingKey,
            preset = presetForNewKey,
            nextPriority = (apiKeys.maxOfOrNull { it.priority } ?: 0) + 1,
            onDismiss = { showAddKeyDialog = false },
            onSave = { saved ->
                onSaveKey(saved)
                showAddKeyDialog = false
            },
        )
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    icon: ImageVector,
    content: @Composable () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = NaNaPalette.SakuraPink,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun ApiKeyCard(
    entry: ApiKeyEntry,
    isTesting: Boolean,
    testResult: String?,
    onTest: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val (statusLabel, statusBg, statusFg) = when (entry.status) {
        KeyStatus.HEALTHY -> Triple("Khỏe mạnh", NaNaPalette.MintContainer, NaNaPalette.MintHealthy)
        KeyStatus.COOLDOWN -> {
            val mins = ((entry.cooldownUntilMs - System.currentTimeMillis()).coerceAtLeast(0L) / 60_000L) + 1L
            Triple("Cooldown ~${mins}p", NaNaPalette.AmberContainer, NaNaPalette.AmberCooldown)
        }
        KeyStatus.INVALID -> Triple("Key lỗi", NaNaPalette.CoralContainer, NaNaPalette.CoralError)
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = NaNaPalette.DeepPlum),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.label,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${AppContainer.providerDisplayName(entry.provider)} · ${entry.modelId}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Key: ${ApiKeyMasker.mask(entry.apiKey)} · Ưu tiên #${entry.priority}",
                        style = MaterialTheme.typography.labelMedium,
                        color = NaNaPalette.LavenderViolet,
                    )
                }

                Row {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Rounded.Edit, contentDescription = "Sửa key", tint = NaNaPalette.LavenderViolet)
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Rounded.Delete, contentDescription = "Xóa key", tint = NaNaPalette.CoralError)
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusChip(text = statusLabel, bg = statusBg, fg = statusFg)
                    if (entry.isFreeTier) {
                        StatusChip("Free Tier", NaNaPalette.LavenderContainer, NaNaPalette.LavenderViolet)
                    }
                    if (entry.isTtsOnly) {
                        StatusChip("Chỉ TTS", NaNaPalette.SakuraContainer, NaNaPalette.SakuraPink)
                    }
                    if (!entry.ttsSupported) {
                        StatusChip("TTS tắt", NaNaPalette.AmberContainer, NaNaPalette.AmberCooldown)
                    }
                }

                TextButton(
                    onClick = onTest,
                    enabled = !isTesting,
                ) {
                    Text(if (isTesting) "Đang kiểm tra..." else "Kiểm tra key")
                }
            }

            testResult?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (msg.startsWith("Hợp lệ")) NaNaPalette.MintHealthy else NaNaPalette.AmberCooldown,
                )
            }
        }
    }
}

@Composable
private fun StatusChip(text: String, bg: Color, fg: Color) {
    Surface(color = bg, shape = RoundedCornerShape(50)) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = fg,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ChecklistActionRow(
    title: String,
    subtitle: String,
    granted: Boolean?,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (granted == true) NaNaPalette.MintHealthy else NaNaPalette.SakuraPink,
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(text = title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        if (granted == true) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = "Đã bật",
                    tint = NaNaPalette.MintHealthy,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Đã bật", style = MaterialTheme.typography.labelMedium, color = NaNaPalette.MintHealthy)
            }
        } else {
            OutlinedButton(
                onClick = onClick,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(if (granted == null) "Mở" else "Cấp quyền")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ApiKeyEditorDialog(
    existing: ApiKeyEntry?,
    preset: KeyPreset?,
    nextPriority: Int,
    onDismiss: () -> Unit,
    onSave: (ApiKeyEntry) -> Unit,
) {
    var label by remember { mutableStateOf(existing?.label ?: preset?.defaultLabel ?: "Gemini Pro") }
    var provider by remember { mutableStateOf(existing?.provider ?: preset?.provider ?: ProviderType.GEMINI_VERTEX_EXPRESS) }
    var rawKey by remember { mutableStateOf(existing?.apiKey.orEmpty()) }
    var modelId by remember { mutableStateOf(existing?.modelId ?: preset?.defaultModelId ?: ModelCatalog.defaultModelFor(provider)) }
    var priority by remember { mutableIntStateOf(existing?.priority ?: preset?.priority ?: nextPriority) }
    var isFreeTier by remember { mutableStateOf(existing?.isFreeTier ?: preset?.isFreeTier ?: false) }
    var isTtsOnly by remember { mutableStateOf(existing?.isTtsOnly ?: preset?.isTtsOnly ?: false) }
    var showRawKey by remember { mutableStateOf(false) }

    val effectiveProvider = if (
        (provider == ProviderType.GEMINI_VERTEX_EXPRESS || provider == ProviderType.GEMINI_AI_STUDIO) &&
        rawKey.trim().isNotEmpty()
    ) {
        detectGeminiProvider(rawKey)
    } else {
        provider
    }
    val suggestedModels = remember(effectiveProvider, isTtsOnly) {
        ModelCatalog.suggestedModelsFor(effectiveProvider, isTtsOnly)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Thêm API Key mới" else "Sửa API Key") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    listOf(
                        ProviderType.GEMINI_VERTEX_EXPRESS to "Gemini Pro",
                        ProviderType.CLAUDE to "Claude",
                        ProviderType.OPENAI to "ChatGPT",
                        ProviderType.FREE_GEMINI to "Gemini Free",
                        ProviderType.FREE_OPENAI_COMPAT to "Free Compat",
                    ).forEach { (type, title) ->
                        FilterChip(
                            selected = provider == type ||
                                (type == ProviderType.GEMINI_VERTEX_EXPRESS && provider == ProviderType.GEMINI_AI_STUDIO),
                            onClick = {
                                provider = type
                                isFreeTier = type == ProviderType.FREE_GEMINI ||
                                    type == ProviderType.FREE_OPENAI_COMPAT
                                if (existing == null) {
                                    modelId = ModelCatalog.defaultModelFor(type, isTtsOnly)
                                }
                            },
                            label = { Text(title) },
                        )
                    }
                }

                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Tên gợi nhớ") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = rawKey,
                    onValueChange = { rawKey = it },
                    label = { Text("API Key") },
                    singleLine = true,
                    visualTransformation = if (showRawKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showRawKey = !showRawKey }) {
                            Icon(
                                imageVector = if (showRawKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = if (showRawKey) "Ẩn key" else "Hiện key",
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    text = "Nhận diện tự động: ${AppContainer.providerDisplayName(effectiveProvider)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = NaNaPalette.LavenderViolet,
                )

                OutlinedTextField(
                    value = modelId,
                    onValueChange = { modelId = it },
                    label = { Text("Model ID (có thể nhập bất kỳ model mới nào)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (suggestedModels.isNotEmpty()) {
                    Text(
                        text = "Chọn nhanh model:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        suggestedModels.forEach { option ->
                            FilterChip(
                                selected = modelId.trim().equals(option.modelId, ignoreCase = true),
                                onClick = { modelId = option.modelId },
                                label = { Text(option.label) },
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Độ ưu tiên: #$priority", style = MaterialTheme.typography.bodyMedium)
                    Row {
                        TextButton(onClick = { priority = (priority - 1).coerceAtLeast(1) }) { Text("-") }
                        TextButton(onClick = { priority = (priority + 1).coerceAtMost(20) }) { Text("+") }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = isFreeTier,
                        onCheckedChange = { isFreeTier = it },
                    )
                    Text("Dành cho Em gái (Free tier)", style = MaterialTheme.typography.bodyMedium)
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = isTtsOnly,
                        onCheckedChange = { checked ->
                            isTtsOnly = checked
                            if (existing == null) {
                                modelId = ModelCatalog.defaultModelFor(effectiveProvider, checked)
                            }
                        },
                    )
                    Text("Chỉ dùng đọc TTS", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val saved = ApiKeyEntry(
                        id = existing?.id ?: "key_${UUID.randomUUID().toString().take(8)}",
                        label = label.trim().ifEmpty { AppContainer.providerDisplayName(effectiveProvider) },
                        provider = effectiveProvider,
                        apiKey = rawKey.trim(),
                        modelId = modelId.trim().ifEmpty { ModelCatalog.defaultModelFor(effectiveProvider, isTtsOnly) },
                        priority = priority,
                        isFreeTier = isFreeTier,
                        isTtsOnly = isTtsOnly,
                        enabled = true,
                        status = KeyStatus.HEALTHY,
                        cooldownUntilMs = 0L,
                        ttsSupported = true,
                    )
                    onSave(saved)
                },
                enabled = rawKey.isNotBlank() && modelId.isNotBlank(),
            ) {
                Text("Lưu")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Hủy")
            }
        },
    )
}

private fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
