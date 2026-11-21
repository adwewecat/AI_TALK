package com.adwewecat.nana.ui.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Resolves OriginOS 6 / iQOO / Vivo settings intents with graceful fallbacks to standard
 * Android settings pages so checklist shortcuts never throw `ActivityNotFoundException`.
 */
object OriginOsSetupHelper {

    private val VIVO_AUTOSTART_COMPONENTS = listOf(
        ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
        ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManagerActivity"),
        ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.PurviewTabActivity"),
        ComponentName("com.vivo.abe", "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity"),
    )

    /** Returns the Vivo/iQOO background autostart activity or falls back to [appDetailsIntent]. */
    fun autostartIntent(context: Context): Intent {
        for (component in VIVO_AUTOSTART_COMPONENTS) {
            val candidate = Intent().setComponent(component)
            if (canResolve(context, candidate)) {
                return candidate
            }
        }
        return appDetailsIntent(context)
    }

    /** Returns the Android battery optimization settings intent or falls back to [appDetailsIntent]. */
    fun batteryOptimizationIntent(context: Context): Intent {
        val batterySettings = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        if (canResolve(context, batterySettings)) {
            return batterySettings
        }
        return appDetailsIntent(context)
    }

    /** Returns the Android Notification Listener settings intent. */
    fun notificationListenerIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

    /** Returns the Android Accessibility settings intent. */
    fun accessibilitySettingsIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    /** Returns the Android App Info settings intent scoped to this app package. */
    fun appDetailsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        )

    /** Returns true when battery optimizations are disabled ("Unrestricted") for this app. */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return runCatching { pm.isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(false)
    }

    /** Starts [intent] safely, falling back to [appDetailsIntent] if the activity cannot open. */
    fun launchSafely(context: Context, intent: Intent): Boolean {
        val launchedPrimary = runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
        if (launchedPrimary) return true
        return runCatching {
            context.startActivity(appDetailsIntent(context))
            true
        }.getOrDefault(false)
    }

    private fun canResolve(context: Context, intent: Intent): Boolean =
        runCatching {
            context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null
        }.getOrDefault(false)
}
