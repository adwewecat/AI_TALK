package com.adwewecat.nana.core.sync

import java.io.IOException

/** Platform check verifying an active, unmetered Wi-Fi connection before Drive I/O. */
interface WifiNetworkChecker {
    fun isUnmeteredWifiConnected(): Boolean
}

/** Raised when unmetered Wi-Fi is unavailable before or during a Google Drive transfer. */
class WifiDroppedException(message: String = "Wi-Fi disconnected") : IOException(message)

/** Raised when Google Drive rejects the OAuth access token with HTTP 401. */
class DriveUnauthorizedException(message: String = "Google Drive OAuth unauthorized") : IOException(message)

/** Raised when a Google Drive resumable upload session URI expires or is missing (HTTP 404/410). */
internal class ResumableSessionExpiredException(
    message: String = "Google Drive resumable upload session expired",
) : IOException(message)

internal fun ensureUnmeteredWifi(wifiChecker: WifiNetworkChecker?) {
    if (wifiChecker != null && !wifiChecker.isUnmeteredWifiConnected()) {
        throw WifiDroppedException()
    }
}
