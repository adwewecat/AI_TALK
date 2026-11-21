package com.adwewecat.nana.core.ai

import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Zero-key online Vietnamese female TTS client (`tl=vi`, MP3 output) providing authentic
 * Vietnamese pronunciation on China ROM devices (such as OriginOS 6) that lack `vi-VN` system TTS.
 */
class VietnameseWebTtsClient(
    private val httpClient: OkHttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL,
) {
    /**
     * Sanitizes, chunks, and synthesizes [text] into sequential Vietnamese MP3 audio buffers.
     * Returns an empty list if offline or if any chunk fails so callers can gracefully fall back.
     */
    suspend fun synthesizeMp3Chunks(text: String): List<ByteArray> = withContext(Dispatchers.IO) {
        val cleaned = TtsTextSanitizer.sanitizeForSpeech(text)
        val chunks = TtsTextSanitizer.chunkForWebTts(cleaned, MAX_CHUNK_CHARS)
        if (chunks.isEmpty()) return@withContext emptyList()

        val result = ArrayList<ByteArray>(chunks.size)
        for (chunk in chunks) {
            val mp3Bytes = fetchSingleChunk(chunk) ?: return@withContext emptyList()
            result += mp3Bytes
        }
        result
    }

    private suspend fun fetchSingleChunk(chunk: String): ByteArray? {
        val encoded = URLEncoder.encode(chunk, StandardCharsets.UTF_8.name())
        val url = "${baseUrl.trimEnd('/')}/translate_tts?ie=UTF-8&client=tw-ob&tl=vi&q=$encoded"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()

        return try {
            executeCancellable(request)
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            null
        }
    }

    private suspend fun executeCancellable(request: Request): ByteArray? =
        suspendCancellableCoroutine { cont ->
            val call = httpClient.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (cont.isActive) {
                            cont.resumeWithException(e)
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        response.use { res ->
                            val bytes = if (res.isSuccessful) {
                                res.body?.bytes()?.takeIf { it.isNotEmpty() }
                            } else {
                                null
                            }
                            if (cont.isActive) {
                                cont.resume(bytes)
                            }
                        }
                    }
                },
            )
        }

    companion object {
        const val DEFAULT_BASE_URL = "https://translate.googleapis.com"
        private const val MAX_CHUNK_CHARS = 180
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36"
    }
}
