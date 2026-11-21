package com.adwewecat.nana.actions

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.ConcurrentHashMap

/**
 * Tier 1 call-control service that observes incoming/ongoing call notifications from
 * OriginOS/Android SIM dialers plus Zalo, Messenger, and Telegram, firing their native
 * Answer/Decline [PendingIntent]s without replacing the system dialer.
 */
class NaNaNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        activeListener = this
        val active = runCatching { activeNotifications }.getOrNull().orEmpty()
        for (sbn in active) {
            trackIfCallNotification(sbn)
        }
    }

    override fun onListenerDisconnected() {
        if (activeListener === this) {
            activeListener = null
        }
        trackedCalls.clear()
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        if (activeListener === this) {
            activeListener = null
        }
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        trackIfCallNotification(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        trackedCalls.remove(notificationKey(sbn))
    }

    private fun trackIfCallNotification(sbn: StatusBarNotification) {
        val packageName = sbn.packageName ?: return
        val key = notificationKey(sbn)
        if (packageName !in IncomingCallNotificationMatcher.SUPPORTED_CALL_PACKAGES) {
            return
        }
        val notification = sbn.notification
        if (isCallNotification(packageName, notification)) {
            trackedCalls[key] = TrackedCallNotification(
                key = key,
                packageName = packageName,
                postTime = sbn.postTime,
                notification = notification,
            )
        } else {
            trackedCalls.remove(key)
        }
    }

    private data class TrackedCallNotification(
        val key: String,
        val packageName: String,
        val postTime: Long,
        val notification: Notification,
    )

    companion object {
        @Volatile
        private var activeListener: NaNaNotificationListener? = null
        private val trackedCalls = ConcurrentHashMap<String, TrackedCallNotification>()

        /**
         * Attempts to trigger Answer ([wantAnswer] = true) or Decline/End ([wantAnswer] = false)
         * on the most recent active SIM/VoIP call notification.
         */
        fun triggerActiveCallAction(wantAnswer: Boolean): Boolean {
            val candidates = collectCandidateNotifications()
            for (entry in candidates) {
                if (fireMatchedIntent(entry.packageName, entry.notification, wantAnswer)) {
                    return true
                }
            }
            return false
        }

        /** Returns true when Notification Listener access is granted to [NaNaNotificationListener]. */
        fun isEnabled(context: Context): Boolean {
            val component = ComponentName(context, NaNaNotificationListener::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                if (nm != null && runCatching { nm.isNotificationListenerAccessGranted(component) }.getOrDefault(false)) {
                    return true
                }
            }
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners",
            ).orEmpty()
            val flattened = component.flattenToString()
            val shortFlattened = component.flattenToShortString()
            return enabled.split(':').any { entry ->
                entry == flattened || entry == shortFlattened
            }
        }

        internal fun clearTrackedCallsForTest() {
            trackedCalls.clear()
        }

        private fun collectCandidateNotifications(): List<TrackedCallNotification> {
            val merged = LinkedHashMap<String, TrackedCallNotification>()
            trackedCalls.values
                .sortedByDescending { it.postTime }
                .forEach { merged[it.key] = it }

            val live = runCatching { activeListener?.activeNotifications }.getOrNull().orEmpty()
            live.sortedByDescending { it.postTime }.forEach { sbn ->
                val pkg = sbn.packageName ?: return@forEach
                if (pkg in IncomingCallNotificationMatcher.SUPPORTED_CALL_PACKAGES &&
                    isCallNotification(pkg, sbn.notification)
                ) {
                    val key = notificationKey(sbn)
                    merged.putIfAbsent(
                        key,
                        TrackedCallNotification(
                            key = key,
                            packageName = pkg,
                            postTime = sbn.postTime,
                            notification = sbn.notification,
                        ),
                    )
                }
            }
            return merged.values.sortedByDescending { it.postTime }
        }

        private fun fireMatchedIntent(
            packageName: String,
            notification: Notification,
            wantAnswer: Boolean,
        ): Boolean {
            val actionCandidates = extractNonReplyActions(notification)
            if (actionCandidates.isNotEmpty()) {
                val titles = actionCandidates.map { it.title.toString() }
                val matchIdx = IncomingCallNotificationMatcher.matchAction(packageName, titles, wantAnswer)
                if (matchIdx != null) {
                    val pendingIntent = actionCandidates[matchIdx].actionIntent
                    if (pendingIntent != null && sendSafely(pendingIntent)) {
                        return true
                    }
                }
            }

            val callStyleIntent = extractCallStylePendingIntent(notification.extras, wantAnswer)
            return callStyleIntent != null && sendSafely(callStyleIntent)
        }

        private fun isCallNotification(packageName: String, notification: Notification): Boolean {
            if (notification.category == Notification.CATEGORY_CALL) {
                return true
            }
            val template = notification.extras?.getString(Notification.EXTRA_TEMPLATE).orEmpty()
            if (template.contains("CallStyle", ignoreCase = true)) {
                return true
            }
            val actions = extractNonReplyActions(notification)
            if (actions.isEmpty()) {
                return false
            }
            val titles = actions.map { it.title.toString() }
            val hasAnswer = IncomingCallNotificationMatcher.matchAction(packageName, titles, wantAnswer = true) != null
            val hasDecline = IncomingCallNotificationMatcher.matchAction(packageName, titles, wantAnswer = false) != null
            return (hasAnswer && hasDecline) ||
                ((notification.flags and Notification.FLAG_ONGOING_EVENT) != 0 && (hasAnswer || hasDecline)) ||
                (notification.fullScreenIntent != null && (hasAnswer || hasDecline))
        }

        private fun extractNonReplyActions(notification: Notification): List<Notification.Action> =
            notification.actions.orEmpty().filter { action ->
                val hasRemoteInput = !action.remoteInputs.isNullOrEmpty()
                !hasRemoteInput && !action.title.isNullOrBlank()
            }

        private fun extractCallStylePendingIntent(extras: Bundle?, wantAnswer: Boolean): PendingIntent? {
            if (extras == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                return null
            }
            val keys = if (wantAnswer) {
                listOf(Notification.EXTRA_ANSWER_INTENT)
            } else {
                listOf(Notification.EXTRA_DECLINE_INTENT, Notification.EXTRA_HANG_UP_INTENT)
            }
            for (key in keys) {
                val intent = getPendingIntentCompat(extras, key)
                if (intent != null) {
                    return intent
                }
            }
            return null
        }

        private fun getPendingIntentCompat(extras: Bundle, key: String): PendingIntent? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                extras.getParcelable(key, PendingIntent::class.java)
            } else {
                @Suppress("DEPRECATION")
                extras.getParcelable(key) as? PendingIntent
            }

        private fun sendSafely(pendingIntent: PendingIntent): Boolean =
            try {
                pendingIntent.send()
                true
            } catch (_: PendingIntent.CanceledException) {
                false
            } catch (_: SecurityException) {
                false
            }

        private fun notificationKey(sbn: StatusBarNotification): String =
            sbn.key ?: "${sbn.packageName}:${sbn.id}:${sbn.tag.orEmpty()}"
    }
}
