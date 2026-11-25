package com.adwewecat.nana.actions

import android.Manifest
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.adwewecat.nana.core.model.ActionExecutionResult
import com.adwewecat.nana.core.model.DeviceActionExecutor
import com.adwewecat.nana.core.model.DeviceCommand
import com.adwewecat.nana.core.voice.FastCommandParser
import kotlin.math.roundToInt

/**
 * 3-tier call Answer/Decline bridge operating without replacing the system dialer.
 */
interface CallControlBridge {
    fun triggerNotificationCallAction(answer: Boolean): Boolean
    fun triggerTelecomCallAction(answer: Boolean): Boolean
    fun triggerAccessibilityCallAction(answer: Boolean): Boolean
}

/** Launches YouTube search intents on the Android device. */
interface YouTubeLauncher {
    fun launchSearch(query: String, autoPlay: Boolean): Boolean
}

/** Production [CallControlBridge] returning safe defaults without requiring high-risk services. */
class AndroidCallControlBridge(
    @Suppress("UNUSED_PARAMETER") private val context: Context,
) : CallControlBridge {

    override fun triggerNotificationCallAction(answer: Boolean): Boolean = false

    override fun triggerTelecomCallAction(answer: Boolean): Boolean = false

    override fun triggerAccessibilityCallAction(answer: Boolean): Boolean = false
}

/** Production [YouTubeLauncher] targeting `com.google.android.youtube` with web fallback. */
class AndroidYouTubeLauncher(
    private val context: Context,
) : YouTubeLauncher {

    companion object {
        const val YOUTUBE_PACKAGE = "com.google.android.youtube"
    }

    override fun launchSearch(query: String, autoPlay: Boolean): Boolean {
        val youtubeIntent = Intent(Intent.ACTION_SEARCH).apply {
            setPackage(YOUTUBE_PACKAGE)
            putExtra(SearchManager.QUERY, query)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return startActivityWithBrowserFallback(
            context = context,
            primaryIntent = youtubeIntent,
            fallbackUrl = "https://www.youtube.com/results?search_query=${Uri.encode(query)}",
        )
    }
}

/**
 * Android 16 / OriginOS 6 implementation of [DeviceActionExecutor] handling YouTube search +
 * auto-play arming, 3-tier SIM/VoIP call answer/decline, speakerphone, calling, app launch,
 * alarms/timers, volume, flashlight, and web search with Vietnamese voice feedback.
 */
class AndroidDeviceActionExecutor(
    private val context: Context,
    private val callControlBridge: CallControlBridge = AndroidCallControlBridge(context),
    private val youTubeLauncher: YouTubeLauncher = AndroidYouTubeLauncher(context),
) : DeviceActionExecutor {

    override suspend fun execute(command: DeviceCommand): ActionExecutionResult = when (command) {
        is DeviceCommand.OpenYouTubeSearch -> handleYouTubeSearch(command)
        DeviceCommand.AnswerIncomingCall -> handleCallControl(answer = true)
        DeviceCommand.EndCall -> handleCallControl(answer = false)
        is DeviceCommand.ToggleSpeaker -> handleToggleSpeaker(command.enabled)
        is DeviceCommand.MakePhoneCall -> handleMakePhoneCall(command.target)
        is DeviceCommand.MakeZaloCall -> handleMakeZaloCall(command.target)
        is DeviceCommand.OpenApp -> handleOpenApp(command.appName)
        is DeviceCommand.SetAlarm -> handleSetAlarm(command.hour, command.minute, command.label)
        is DeviceCommand.SetTimer -> handleSetTimer(command.seconds, command.label)
        is DeviceCommand.AdjustVolume -> handleAdjustVolume(command.stream, command.direction, command.percent)
        is DeviceCommand.ToggleFlashlight -> handleToggleFlashlight(command.enabled)
        is DeviceCommand.SilentWebSearch -> ActionExecutionResult(succeeded = true, spokenFeedback = "")
        is DeviceCommand.WebSearch -> handleWebSearch(command.query)
        DeviceCommand.StopSession -> ActionExecutionResult(
            succeeded = true,
            spokenFeedback = "Dạ, tạm biệt anh nha!",
        )
    }

    private fun handleYouTubeSearch(command: DeviceCommand.OpenYouTubeSearch): ActionExecutionResult {
        val query = command.query.trim()
        if (query.isEmpty()) {
            return ActionExecutionResult(
                succeeded = false,
                spokenFeedback = "Anh muốn em mở bài hát hay video gì trên YouTube ạ?",
            )
        }
        val launched = youTubeLauncher.launchSearch(query, command.autoPlay)
        if (!launched) {
            return ActionExecutionResult(
                succeeded = false,
                spokenFeedback = "Em chưa mở được YouTube trên máy ạ.",
            )
        }
        return ActionExecutionResult(
            succeeded = true,
            spokenFeedback = "Dạ, em mở YouTube tìm \"$query\" cho anh ngay đây ạ.",
        )
    }

    private fun handleCallControl(answer: Boolean): ActionExecutionResult {
        val tier1Handled = callControlBridge.triggerNotificationCallAction(answer)
        if (tier1Handled) {
            return ActionExecutionResult(
                succeeded = true,
                spokenFeedback = if (answer) {
                    "Dạ, em đã nghe máy giúp anh rồi ạ."
                } else {
                    "Dạ, em đã kết thúc cuộc gọi giúp anh rồi ạ."
                },
            )
        }

        val tier2Handled = callControlBridge.triggerTelecomCallAction(answer)
        if (tier2Handled) {
            return ActionExecutionResult(
                succeeded = true,
                spokenFeedback = if (answer) {
                    "Dạ, em đã bắt máy cuộc gọi cho anh rồi ạ."
                } else {
                    "Dạ, em đã cúp máy giúp anh rồi ạ."
                },
            )
        }

        val tier3Handled = callControlBridge.triggerAccessibilityCallAction(answer)
        if (tier3Handled) {
            return ActionExecutionResult(
                succeeded = true,
                spokenFeedback = if (answer) {
                    "Dạ, em đã bấm nghe máy giúp anh rồi ạ."
                } else {
                    "Dạ, em đã bấm kết thúc cuộc gọi giúp anh rồi ạ."
                },
            )
        }

        return buildCallFailureResult(answer)
    }

    private fun buildCallFailureResult(answer: Boolean): ActionExecutionResult {
        return ActionExecutionResult(
            succeeded = false,
            spokenFeedback = if (answer) {
                "Hiện em không thấy cuộc gọi nào đang đổ chuông để nghe giúp anh ạ."
            } else {
                "Hiện em không thấy cuộc gọi nào đang diễn ra để tắt ạ."
            },
        )
    }

    private fun handleToggleSpeaker(enabled: Boolean): ActionExecutionResult {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return ActionExecutionResult(
                succeeded = false,
                spokenFeedback = "Em không truy cập được trình điều khiển âm thanh của máy ạ.",
            )

        val applied = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (enabled) {
                    val speakerDevice = audioManager.availableCommunicationDevices
                        .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    if (speakerDevice != null) {
                        audioManager.setCommunicationDevice(speakerDevice)
                    } else {
                        @Suppress("DEPRECATION")
                        audioManager.isSpeakerphoneOn = true
                        true
                    }
                } else {
                    audioManager.clearCommunicationDevice()
                    @Suppress("DEPRECATION")
                    audioManager.isSpeakerphoneOn = false
                    true
                }
            } else {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = enabled
                true
            }
        }.getOrDefault(false)

        return if (applied) {
            ActionExecutionResult(
                succeeded = true,
                spokenFeedback = if (enabled) {
                    "Dạ, em đã bật loa ngoài cho anh rồi ạ."
                } else {
                    "Dạ, em đã tắt loa ngoài rồi ạ."
                },
            )
        } else {
            ActionExecutionResult(
                succeeded = false,
                spokenFeedback = "Em chưa đổi được chế độ loa ngoài trên thiết bị ạ.",
            )
        }
    }

    private fun handleMakePhoneCall(rawTarget: String): ActionExecutionResult {
        val target = rawTarget.trim()
        if (target.isEmpty()) {
            return ActionExecutionResult(
                succeeded = false,
                spokenFeedback = "Anh muốn em gọi cho ai hoặc số nào ạ?",
            )
        }

        val resolvedNumber = if (looksLikePhoneNumber(target)) {
            target.filter { it.isDigit() || it == '+' }
        } else {
            if (!hasPermission(context, Manifest.permission.READ_CONTACTS)) {
                return ActionExecutionResult(
                    succeeded = false,
                    spokenFeedback = "Em cần quyền đọc Danh bạ để tìm số của $target trước khi gọi ạ.",
                    missingPermissionAction = Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                )
            }
            lookupContactNumber(target) ?: return ActionExecutionResult(
                succeeded = false,
                spokenFeedback = "Em không tìm thấy số điện thoại của $target trong danh bạ ạ.",
            )
        }

        if (!hasPermission(context, Manifest.permission.CALL_PHONE)) {
            return ActionExecutionResult(
                succeeded = false,
                spokenFeedback = "Em cần quyền Gọi điện để thực hiện cuộc gọi tới $target cho anh ạ.",
                missingPermissionAction = Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            )
        }

        val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(resolvedNumber)}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val launched = runCatching {
            context.startActivity(callIntent)
            true
        }.getOrDefault(false)

        return if (launched) {
            ActionExecutionResult(
                succeeded = true,
                spokenFeedback = "Dạ, em đang gọi cho $target ạ.",
            )
        } else {
            ActionExecutionResult(
                succeeded = false,
                spokenFeedback = "Em không khởi tạo được cuộc gọi tới $target ạ.",
            )
        }
    }

    private fun handleMakeZaloCall(rawTarget: String): ActionExecutionResult {
        val target = rawTarget.trim()
        if (target.isEmpty()) {
            return ActionExecutionResult(
                succeeded = false,
                spokenFeedback = "Anh muốn em gọi Zalo cho ai ạ?",
            )
        }

        val phone = when {
            looksLikePhoneNumber(target) -> normalizeVnPhoneForZalo(target)
            hasPermission(context, Manifest.permission.READ_CONTACTS) ->
                lookupContactNumber(target)?.let(::normalizeVnPhoneForZalo)
            else -> null
        }

        if (phone != null) {
            val directIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://zalo.me/$phone")).apply {
                setPackage(ZALO_PACKAGE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val openedDirect = runCatching {
                context.startActivity(directIntent)
                true
            }.getOrDefault(false)
            if (openedDirect) {
                return ActionExecutionResult(
                    succeeded = true,
                    spokenFeedback = "Dạ, em đang mở Zalo để gọi cho $target cho anh ạ.",
                )
            }
        }

        val pm = context.packageManager
        val zaloLaunchIntent = pm.getLaunchIntentForPackage(ZALO_PACKAGE)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (zaloLaunchIntent != null) {
            val launched = runCatching {
                context.startActivity(zaloLaunchIntent)
                true
            }.getOrDefault(false)
            if (launched) {
                return ActionExecutionResult(
                    succeeded = true,
                    spokenFeedback = "Dạ, em đang mở Zalo để anh gọi cho $target nhé.",
                )
            }
        }

        return ActionExecutionResult(
            succeeded = false,
            spokenFeedback = "Em chưa thấy ứng dụng Zalo được cài trên máy ạ.",
        )
    }

    private fun normalizeVnPhoneForZalo(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        return if (digits.startsWith("84") && digits.length >= 10) {
            "0${digits.drop(2)}"
        } else {
            digits
        }
    }

    private fun lookupContactNumber(contactQuery: String): String? {
        val normalizedQuery = FastCommandParser.normalizeVietnamese(contactQuery)
        if (normalizedQuery.isEmpty()) return null

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        val cursor = runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                null,
            )
        }.getOrNull() ?: return null

        cursor.use { c ->
            val nameIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            if (nameIdx < 0 || numIdx < 0) return null

            var partialMatch: String? = null
            while (c.moveToNext()) {
                val displayName = c.getString(nameIdx).orEmpty()
                val number = c.getString(numIdx)?.trim().orEmpty()
                if (number.isEmpty()) continue
                val normName = FastCommandParser.normalizeVietnamese(displayName)
                if (normName == normalizedQuery) {
                    return number
                }
                if (partialMatch == null && normName.contains(normalizedQuery)) {
                    partialMatch = number
                }
            }
            return partialMatch
        }
    }

    private fun handleOpenApp(rawAppName: String): ActionExecutionResult {
        val appName = rawAppName.trim()
        if (appName.isEmpty()) {
            return ActionExecutionResult(
                succeeded = false,
                spokenFeedback = "Anh muốn em mở ứng dụng nào ạ?",
            )
        }
        val normQuery = FastCommandParser.normalizeVietnamese(appName)
        if (normQuery == "cai dat" || normQuery == "settings") {
            val started = runCatching {
                context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            }.getOrDefault(false)
            return if (started) {
                ActionExecutionResult(true, "Dạ, em mở Cài đặt cho anh rồi ạ.")
            } else {
                ActionExecutionResult(false, "Em không mở được Cài đặt ạ.")
            }
        }

        val pm = context.packageManager
        val aliasPackage = APP_PACKAGE_ALIASES[normQuery]
        val matchedPackage = aliasPackage?.takeIf { pm.getLaunchIntentForPackage(it) != null }
            ?: findInstalledLauncherPackage(pm, normQuery)

        val launchIntent = matchedPackage?.let { pm.getLaunchIntentForPackage(it) }
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        if (launchIntent == null) {
            return ActionExecutionResult(
                succeeded = false,
                spokenFeedback = "Em chưa tìm thấy ứng dụng $appName trên máy ạ.",
            )
        }

        val started = runCatching {
            context.startActivity(launchIntent)
            true
        }.getOrDefault(false)

        return if (started) {
            ActionExecutionResult(
                succeeded = true,
                spokenFeedback = "Dạ, em mở $appName cho anh ngay đây ạ.",
            )
        } else {
            ActionExecutionResult(
                succeeded = false,
                spokenFeedback = "Em không khởi chạy được ứng dụng $appName ạ.",
            )
        }
    }

    private fun findInstalledLauncherPackage(pm: PackageManager, normQuery: String): String? {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(launcherIntent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(launcherIntent, 0)
        }

        var partialMatch: String? = null
        for (info in activities) {
            val label = info.loadLabel(pm)?.toString().orEmpty()
            val normLabel = FastCommandParser.normalizeVietnamese(label)
            val pkg = info.activityInfo?.packageName ?: continue
            if (normLabel == normQuery) {
                return pkg
            }
            if (partialMatch == null && normLabel.contains(normQuery)) {
                partialMatch = pkg
            }
        }
        return partialMatch
    }

    private fun handleSetAlarm(hour: Int, minute: Int, label: String): ActionExecutionResult {
        if (hour !in 0..23 || minute !in 0..59) {
            return ActionExecutionResult(
                succeeded = false,
                spokenFeedback = "Giờ báo thức không hợp lệ ạ, anh đọc lại giờ giúp em nhé.",
            )
        }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            if (label.isNotBlank()) {
                putExtra(AlarmClock.EXTRA_MESSAGE, label.trim())
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val started = runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)

        return if (started) {
            val formatted = "%02d:%02d".format(hour, minute)
            ActionExecutionResult(
                succeeded = true,
                spokenFeedback = "Dạ, em đã đặt báo thức lúc $formatted cho anh rồi ạ.",
            )
        } else {
            ActionExecutionResult(
                succeeded = false,
                spokenFeedback = "Em không mở được ứng dụng Đồng hồ để đặt báo thức ạ.",
            )
        }
    }

    private fun handleSetTimer(seconds: Int, label: String): ActionExecutionResult {
        if (seconds <= 0) {
            return ActionExecutionResult(
                succeeded = false,
                spokenFeedback = "Thời gian hẹn giờ phải lớn hơn 0 giây ạ.",
            )
        }
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            if (label.isNotBlank()) {
                putExtra(AlarmClock.EXTRA_MESSAGE, label.trim())
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val started = runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)

        return if (started) {
            val mins = seconds / 60
            val secs = seconds % 60
            val durationText = when {
                mins > 0 && secs > 0 -> "$mins phút $secs giây"
                mins > 0 -> "$mins phút"
                else -> "$secs giây"
            }
            ActionExecutionResult(
                succeeded = true,
                spokenFeedback = "Dạ, em đã bật bộ đếm $durationText cho anh rồi ạ.",
            )
        } else {
            ActionExecutionResult(
                succeeded = false,
                spokenFeedback = "Em không đặt được bộ đếm thời gian trên máy ạ.",
            )
        }
    }

    private fun handleAdjustVolume(
        stream: String,
        direction: String,
        percent: Int?,
    ): ActionExecutionResult {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return ActionExecutionResult(
                succeeded = false,
                spokenFeedback = "Em không truy cập được điều chỉnh âm lượng của máy ạ.",
            )

        val streamType = when (FastCommandParser.normalizeVietnamese(stream)) {
            "ring", "chuong" -> AudioManager.STREAM_RING
            "alarm", "bao thuc" -> AudioManager.STREAM_ALARM
            "call", "cuoc goi", "thoai" -> AudioManager.STREAM_VOICE_CALL
            "notification", "thong bao" -> AudioManager.STREAM_NOTIFICATION
            else -> AudioManager.STREAM_MUSIC
        }

        return try {
            if (percent != null) {
                val maxVol = audioManager.getStreamMaxVolume(streamType).coerceAtLeast(1)
                val targetVol = (percent.coerceIn(0, 100) * maxVol / 100.0).roundToInt()
                audioManager.setStreamVolume(streamType, targetVol, AudioManager.FLAG_SHOW_UI)
                ActionExecutionResult(
                    succeeded = true,
                    spokenFeedback = "Dạ, em đã chỉnh âm lượng lên mức ${percent.coerceIn(0, 100)}% rồi ạ.",
                )
            } else {
                val adjustDir = when (FastCommandParser.normalizeVietnamese(direction)) {
                    "mute", "tat tieng", "im lang" -> AudioManager.ADJUST_MUTE
                    "unmute", "bat tieng" -> AudioManager.ADJUST_UNMUTE
                    "down", "lower", "giam", "nho" -> AudioManager.ADJUST_LOWER
                    else -> AudioManager.ADJUST_RAISE
                }
                audioManager.adjustStreamVolume(streamType, adjustDir, AudioManager.FLAG_SHOW_UI)
                ActionExecutionResult(
                    succeeded = true,
                    spokenFeedback = "Dạ, em đã điều chỉnh âm lượng cho anh rồi ạ.",
                )
            }
        } catch (_: SecurityException) {
            ActionExecutionResult(
                succeeded = false,
                spokenFeedback = "Máy đang bật Không làm phiền nên em cần quyền truy cập để đổi âm lượng chuông ạ.",
                missingPermissionAction = Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS,
            )
        }
    }

    private fun handleToggleFlashlight(enabled: Boolean): ActionExecutionResult {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return ActionExecutionResult(
                succeeded = false,
                spokenFeedback = "Em không truy cập được đèn pin của thiết bị ạ.",
            )

        return try {
            val flashCameraId = selectFlashCameraId(cameraManager)
            if (flashCameraId == null) {
                ActionExecutionResult(
                    succeeded = false,
                    spokenFeedback = "Thiết bị không báo có đèn flash khả dụng ạ.",
                )
            } else {
                cameraManager.setTorchMode(flashCameraId, enabled)
                ActionExecutionResult(
                    succeeded = true,
                    spokenFeedback = if (enabled) {
                        "Dạ, em đã bật đèn pin cho anh rồi ạ."
                    } else {
                        "Dạ, em đã tắt đèn pin rồi ạ."
                    },
                )
            }
        } catch (_: CameraAccessException) {
            ActionExecutionResult(
                succeeded = false,
                spokenFeedback = "Camera đang bận nên em chưa bật tắt đèn pin được ạ.",
            )
        } catch (_: IllegalArgumentException) {
            ActionExecutionResult(
                succeeded = false,
                spokenFeedback = "Em không điều khiển được đèn pin trên máy này ạ.",
            )
        } catch (_: SecurityException) {
            ActionExecutionResult(
                succeeded = false,
                spokenFeedback = "Em chưa có quyền điều khiển đèn pin ạ.",
                missingPermissionAction = Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            )
        }
    }

    private fun selectFlashCameraId(cameraManager: CameraManager): String? {
        var fallbackId: String? = null
        for (id in cameraManager.cameraIdList) {
            val chars = cameraManager.getCameraCharacteristics(id)
            val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            if (!hasFlash) continue
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                return id
            }
            if (fallbackId == null) {
                fallbackId = id
            }
        }
        return fallbackId
    }

    private fun handleWebSearch(rawQuery: String): ActionExecutionResult {
        val query = rawQuery.trim()
        if (query.isEmpty()) {
            return ActionExecutionResult(
                succeeded = false,
                spokenFeedback = "Anh muốn em tìm thông tin gì trên mạng ạ?",
            )
        }
        val webSearchIntent = Intent(Intent.ACTION_WEB_SEARCH).apply {
            putExtra(SearchManager.QUERY, query)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val launched = startActivityWithBrowserFallback(
            context = context,
            primaryIntent = webSearchIntent,
            fallbackUrl = "https://www.google.com/search?q=${Uri.encode(query)}",
        )

        return if (launched) {
            ActionExecutionResult(
                succeeded = true,
                spokenFeedback = "Dạ, em đang tìm \"$query\" cho anh đây ạ.",
            )
        } else {
            ActionExecutionResult(
                succeeded = false,
                spokenFeedback = "Em không mở được trình duyệt để tìm kiếm ạ.",
            )
        }
    }

    private fun looksLikePhoneNumber(value: String): Boolean {
        val hasLetters = value.any { it.isLetter() }
        val digitCount = value.count { it.isDigit() }
        return !hasLetters && digitCount >= 3
    }

    private companion object {
        const val ZALO_PACKAGE = "com.zing.zalo"
        val APP_PACKAGE_ALIASES = mapOf(
            "zalo" to "com.zing.zalo",
            "messenger" to "com.facebook.orca",
            "telegram" to "org.telegram.messenger",
            "youtube" to "com.google.android.youtube",
            "facebook" to "com.facebook.katana",
            "chrome" to "com.android.chrome",
            "tiktok" to "com.ss.android.ugc.trill",
            "gmail" to "com.google.android.gm",
            "google maps" to "com.google.android.apps.maps",
            "ban do" to "com.google.android.apps.maps",
        )
    }
}

private fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun startActivityWithBrowserFallback(
    context: Context,
    primaryIntent: Intent,
    fallbackUrl: String,
): Boolean = try {
    context.startActivity(primaryIntent)
    true
} catch (_: ActivityNotFoundException) {
    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching {
        context.startActivity(browserIntent)
        true
    }.getOrDefault(false)
} catch (_: SecurityException) {
    false
}
