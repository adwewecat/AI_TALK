package com.adwewecat.nana.voice

import android.content.Context
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Extracts the bundled offline Vietnamese Vosk model from APK `assets/vosk-model-vn/`
 * into `filesDir/vosk-model-vn/` on first launch so hands-free `"Na Na"` wake-word works offline.
 */
object VoskModelInstaller {

    private const val ASSET_DIR_NAME = VoskWakeWordEngine.VOSK_MODEL_DIR_NAME
    private const val READY_MARKER = ".vosk_ready"
    private val installMutex = Mutex()

    /** Returns true when a valid Vosk model directory exists in app-private storage. */
    fun isInstalled(context: Context): Boolean {
        val dir = VoskWakeWordEngine.modelDir(context.applicationContext)
        if (!dir.isDirectory) return false
        val hasCoreModelFiles = File(dir, "am").isDirectory && File(dir, "conf").isDirectory
        return File(dir, READY_MARKER).exists() || hasCoreModelFiles
    }

    /** Returns true if the APK includes pre-bundled Vosk model assets. */
    fun hasBundledAssets(context: Context): Boolean = runCatching {
        context.applicationContext.assets.list(ASSET_DIR_NAME)?.isNotEmpty() == true
    }.getOrDefault(false)

    /**
     * Ensures the offline Vietnamese Vosk model is extracted into `filesDir/vosk-model-vn/`.
     * Safe to invoke concurrently from Application startup and [NaNaVoiceService].
     */
    suspend fun ensureInstalled(context: Context): Boolean = installMutex.withLock {
        val appContext = context.applicationContext
        if (isInstalled(appContext)) {
            return@withLock true
        }
        if (!hasBundledAssets(appContext)) {
            return@withLock false
        }

        withContext(Dispatchers.IO) {
            val targetDir = VoskWakeWordEngine.modelDir(appContext)
            val stagingDir = File(appContext.filesDir, "${ASSET_DIR_NAME}_staging")
            try {
                if (stagingDir.exists()) {
                    stagingDir.deleteRecursively()
                }
                stagingDir.mkdirs()
                copyAssetTree(appContext, ASSET_DIR_NAME, stagingDir)
                File(stagingDir, READY_MARKER).writeText("ok")

                if (targetDir.exists()) {
                    targetDir.deleteRecursively()
                }
                if (!stagingDir.renameTo(targetDir)) {
                    targetDir.mkdirs()
                    stagingDir.copyRecursively(targetDir, overwrite = true)
                    stagingDir.deleteRecursively()
                }
                isInstalled(appContext)
            } catch (_: IOException) {
                runCatching { stagingDir.deleteRecursively() }
                false
            } catch (_: RuntimeException) {
                runCatching { stagingDir.deleteRecursively() }
                false
            }
        }
    }

    private fun copyAssetTree(context: Context, assetPath: String, destDir: File) {
        val entries = context.assets.list(assetPath).orEmpty()
        if (entries.isEmpty()) {
            // Leaf asset file
            destDir.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                destDir.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            return
        }
        destDir.mkdirs()
        for (name in entries) {
            val childAsset = "$assetPath/$name"
            val childEntries = context.assets.list(childAsset).orEmpty()
            val childTarget = File(destDir, name)
            if (childEntries.isNotEmpty()) {
                copyAssetTree(context, childAsset, childTarget)
            } else {
                context.assets.open(childAsset).use { input ->
                    childTarget.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }
}
