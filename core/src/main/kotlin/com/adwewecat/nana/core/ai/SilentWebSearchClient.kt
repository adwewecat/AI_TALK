package com.adwewecat.nana.core.ai

import java.io.IOException
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Single snippet returned by [SilentWebSearchClient] for LLM voice synthesis. */
data class SearchSnippet(
    val title: String,
    val snippet: String,
    val publishedAt: String? = null,
)

/**
 * Zero-key silent background web search client for voice-first answers (e.g. gold prices,
 * Mi Hong/SJC, weather, exchange rates, Vietnamese news) without opening any browser UI.
 * Queries Bing RSS and Google News Vietnam RSS in parallel, falling back to DuckDuckGo HTML.
 */
class SilentWebSearchClient(
    private val httpClient: OkHttpClient,
    private val bingBaseUrl: String = "https://www.bing.com",
    private val googleNewsBaseUrl: String = "https://news.google.com",
    private val duckDuckGoBaseUrl: String = "https://html.duckduckgo.com",
) {

    /** Fetches top up-to-date Vietnamese/global snippets silently over HTTP. */
    suspend fun search(query: String, maxResults: Int = 8): List<SearchSnippet> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val encoded = URLEncoder.encode(trimmed, Charsets.UTF_8.name())

        return coroutineScope {
            val bingDeferred = async(Dispatchers.IO) {
                fetchText("${bingBaseUrl.trimEnd('/')}/search?q=$encoded&format=rss&mkt=vi-VN")
                    ?.let(::parseRssItems)
                    .orEmpty()
            }
            val newsDeferred = async(Dispatchers.IO) {
                fetchText("${googleNewsBaseUrl.trimEnd('/')}/rss/search?q=$encoded&hl=vi&gl=VN&ceid=VN:vi")
                    ?.let(::parseRssItems)
                    .orEmpty()
            }

            val combined = LinkedHashMap<String, SearchSnippet>()
            (newsDeferred.await().take(5) + bingDeferred.await().take(5)).forEach { item ->
                val key = item.title.lowercase().take(60)
                if (key.isNotBlank() && !combined.containsKey(key)) {
                    combined[key] = item
                }
            }

            if (combined.isEmpty()) {
                val ddgItems = withContext(Dispatchers.IO) {
                    fetchText("${duckDuckGoBaseUrl.trimEnd('/')}/html/?q=$encoded")
                        ?.let(::parseDuckDuckGoSnippets)
                        .orEmpty()
                }
                ddgItems.forEach { item ->
                    val key = item.snippet.lowercase().take(60)
                    if (key.isNotBlank() && !combined.containsKey(key)) {
                        combined[key] = item
                    }
                }
            }

            combined.values.take(maxResults)
        }
    }

    /** Formats snippets into a compact Vietnamese context block for Gemini to read aloud naturally. */
    fun formatForPrompt(query: String, snippets: List<SearchSnippet>): String {
        if (snippets.isEmpty()) {
            return "Không tìm thấy dữ liệu trực tuyến mới cho \"$query\"."
        }
        return buildString {
            append("Kết quả tìm kiếm trực tuyến mới nhất cho \"").append(query).append("\":\n")
            snippets.forEachIndexed { idx, item ->
                append(idx + 1).append(". ")
                if (item.title.isNotBlank()) {
                    append(item.title)
                }
                if (!item.publishedAt.isNullOrBlank()) {
                    append(" [").append(item.publishedAt).append("]")
                }
                if (item.snippet.isNotBlank() && item.snippet != item.title) {
                    append(" - ").append(item.snippet)
                }
                append('\n')
            }
        }.trim()
    }

    private fun fetchText(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
            )
            .header("Accept-Language", "vi-VN,vi;q=0.9,en;q=0.8")
            .get()
            .build()
        return try {
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.string()?.take(250_000)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    internal fun parseRssItems(xml: String): List<SearchSnippet> {
        val items = mutableListOf<SearchSnippet>()
        for (match in ITEM_REGEX.findAll(xml)) {
            val block = match.groupValues[1]
            val title = cleanXmlText(extractTag(block, "title"))
            val desc = cleanXmlText(extractTag(block, "description"))
            val pubDate = cleanXmlText(extractTag(block, "pubDate")).takeIf { it.isNotBlank() }
            if (title.isNotBlank() || desc.isNotBlank()) {
                items.add(
                    SearchSnippet(
                        title = title,
                        snippet = desc.take(360),
                        publishedAt = pubDate,
                    ),
                )
            }
            if (items.size >= 8) break
        }
        return items
    }

    internal fun parseDuckDuckGoSnippets(html: String): List<SearchSnippet> =
        DDG_SNIPPET_REGEX.findAll(html)
            .map { match ->
                val text = cleanXmlText(match.groupValues[1])
                SearchSnippet(title = "", snippet = text.take(360))
            }
            .filter { it.snippet.isNotBlank() }
            .take(6)
            .toList()

    private fun extractTag(block: String, tag: String): String {
        val regex = Regex("<$tag[^>]*>(.*?)</$tag>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        return regex.find(block)?.groupValues?.getOrNull(1).orEmpty()
    }

    private fun cleanXmlText(raw: String): String {
        if (raw.isBlank()) return ""
        val withoutCdata = raw
            .replace("<![CDATA[", "")
            .replace("]]>", "")
        val decodedOnce = decodeHtmlEntities(withoutCdata)
        val withoutTags = HTML_TAG_REGEX.replace(decodedOnce, " ")
        return WHITESPACE_REGEX.replace(decodeHtmlEntities(withoutTags), " ").trim()
    }

    private fun decodeHtmlEntities(text: String): String = text
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&nbsp;", " ")

    private companion object {
        val ITEM_REGEX = Regex("<item\\b[^>]*>(.*?)</item>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val DDG_SNIPPET_REGEX = Regex(
            """class="result__snippet"[^>]*>(.*?)</a>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        val HTML_TAG_REGEX = Regex("<[^>]+>")
        val WHITESPACE_REGEX = Regex("\\s+")
    }
}
