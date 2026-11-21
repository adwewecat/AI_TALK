package com.adwewecat.nana.actions

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.adwewecat.nana.core.voice.FastCommandParser
import java.util.IdentityHashMap

/**
 * Accessibility companion service performing two tightly scoped automations:
 * 1. Auto-tapping the first non-ad YouTube search result within a 10-second armed window.
 * 2. Tier 3 call Answer/Decline UI button tap fallback when Notification & Telecom tiers cannot act.
 */
class NaNaAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeInstance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (activeInstance === this) {
            activeInstance = null
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (activeInstance === this) {
            activeInstance = null
        }
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val packageName = event.packageName?.toString() ?: return
        if (packageName == YOUTUBE_PACKAGE && isYouTubeAutoPlayArmed()) {
            handleYouTubeAutoPlayOnRoot(rootInActiveWindow)
        }
    }

    override fun onInterrupt() = Unit

    /** Attempts to tap the first non-ad YouTube video node on [root] if still within the 10s arm window. */
    fun handleYouTubeAutoPlayOnRoot(
        root: AccessibilityNodeInfo?,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (!isYouTubeAutoPlayArmed(nowMs)) {
            disarmYouTubeAutoPlay()
            return false
        }
        if (root == null || root.packageName?.toString() != YOUTUBE_PACKAGE) {
            return false
        }
        val candidate = findFirstNonAdYouTubeResult(root) ?: return false
        val clicked = candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (clicked) {
            disarmYouTubeAutoPlay()
        }
        return clicked
    }

    /** Finds the first clickable non-ad video result card inside a YouTube accessibility tree. */
    fun findFirstNonAdYouTubeResult(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val candidates = ArrayList<AccessibilityNodeInfo>()
        collectYouTubeCandidates(
            node = root,
            depth = 0,
            visited = IdentityHashMap(),
            out = candidates,
        )
        return candidates.firstOrNull()
    }

    /** Executes Tier 3 call button tap against the currently visible dialer or VoIP call window. */
    fun performCallButtonTap(wantAnswer: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val pkg = root.packageName?.toString() ?: return false
        if (pkg !in CALL_WINDOW_PACKAGES) {
            return false
        }
        val target = findCallActionNode(root, wantAnswer) ?: return false
        val clickable = findClickableAncestor(target) ?: return false
        return clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /** Searches [root] for an Answer or Decline button matching supported Vietnamese/English labels. */
    fun findCallActionNode(
        root: AccessibilityNodeInfo,
        wantAnswer: Boolean,
    ): AccessibilityNodeInfo? {
        val visited = IdentityHashMap<AccessibilityNodeInfo, Boolean>()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)

        while (queue.isNotEmpty()) {
            val (node, depth) = queue.removeFirst()
            if (visited.put(node, true) != null || depth > MAX_TREE_DEPTH) {
                continue
            }
            val labelTexts = listOfNotNull(
                node.text?.toString(),
                node.contentDescription?.toString(),
            )
            if (labelTexts.any { IncomingCallNotificationMatcher.matchesLabel(it, wantAnswer) }) {
                if (findClickableAncestor(node) != null) {
                    return node
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { child ->
                    queue.add(child to depth + 1)
                }
            }
        }
        return null
    }

    private fun collectYouTubeCandidates(
        node: AccessibilityNodeInfo,
        depth: Int,
        visited: IdentityHashMap<AccessibilityNodeInfo, Boolean>,
        out: MutableList<AccessibilityNodeInfo>,
    ) {
        if (visited.put(node, true) != null || depth > MAX_TREE_DEPTH) {
            return
        }
        if (isChromeOrInputNode(node) || isOwnAdMarker(node)) {
            return
        }
        if (node.isClickable && !node.isScrollable && node.isVisibleToUser) {
            if (!isAdNodeOrSubtree(node, depth = 0, visited = IdentityHashMap()) &&
                looksLikeVideoResult(node)
            ) {
                out.add(node)
            }
            return
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                collectYouTubeCandidates(child, depth + 1, visited, out)
            }
        }
    }

    private fun isOwnAdMarker(node: AccessibilityNodeInfo): Boolean {
        val ownLabels = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
        return ownLabels.any { raw ->
            val normalized = FastCommandParser.normalizeVietnamese(raw)
            if (normalized.isEmpty()) {
                false
            } else {
                val padded = " $normalized "
                AD_MARKERS.any { marker -> padded.contains(" $marker ") }
            }
        }
    }

    private fun isChromeOrInputNode(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString().orEmpty()
        if (className.endsWith("EditText")) {
            return true
        }
        val viewId = node.viewIdResourceName?.lowercase().orEmpty()
        if (CHROME_VIEW_ID_TOKENS.any { token -> viewId.contains(token) }) {
            return true
        }
        val ownLabels = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
        return ownLabels.any { raw ->
            val norm = FastCommandParser.normalizeVietnamese(raw)
            norm in CHROME_EXACT_LABELS
        }
    }

    private fun looksLikeVideoResult(node: AccessibilityNodeInfo): Boolean {
        val summary = collectSubtreeLabels(node, depth = 0, visited = IdentityHashMap())
        if (summary.isEmpty()) {
            return false
        }
        val combined = summary.joinToString(" ")
        val normalized = FastCommandParser.normalizeVietnamese(combined)
        if (normalized.isEmpty() || normalized in CHROME_EXACT_LABELS) {
            return false
        }
        return normalized.contains(' ') || normalized.length >= 6
    }

    private fun isAdNodeOrSubtree(
        node: AccessibilityNodeInfo,
        depth: Int,
        visited: IdentityHashMap<AccessibilityNodeInfo, Boolean>,
    ): Boolean {
        if (visited.put(node, true) != null || depth > MAX_AD_CHECK_DEPTH) {
            return false
        }
        val ownLabels = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
        for (raw in ownLabels) {
            val normalized = FastCommandParser.normalizeVietnamese(raw)
            if (normalized.isNotEmpty()) {
                val padded = " $normalized "
                if (AD_MARKERS.any { marker -> padded.contains(" $marker ") }) {
                    return true
                }
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (isAdNodeOrSubtree(child, depth + 1, visited)) {
                return true
            }
        }
        return false
    }

    private fun collectSubtreeLabels(
        node: AccessibilityNodeInfo,
        depth: Int,
        visited: IdentityHashMap<AccessibilityNodeInfo, Boolean>,
    ): List<String> {
        if (visited.put(node, true) != null || depth > 4) {
            return emptyList()
        }
        val labels = ArrayList<String>()
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { labels.add(it) }
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { labels.add(it) }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                labels.addAll(collectSubtreeLabels(child, depth + 1, visited))
            }
        }
        return labels
    }

    private fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (current != null && hops < 6) {
            if (current.isClickable) {
                return current
            }
            current = current.parent
            hops++
        }
        return null
    }

    companion object {
        const val YOUTUBE_PACKAGE = "com.google.android.youtube"
        const val YOUTUBE_ARM_WINDOW_MS = 10_000L

        private const val MAX_TREE_DEPTH = 30
        private const val MAX_AD_CHECK_DEPTH = 5

        private val CALL_WINDOW_PACKAGES =
            IncomingCallNotificationMatcher.SUPPORTED_CALL_PACKAGES + setOf(
                "com.android.systemui",
                "com.vivo.incallui",
            )

        private val AD_MARKERS = listOf(
            "ad",
            "ads",
            "sponsored",
            "quang cao",
            "tai tro",
            "visit advertiser",
            "shop now",
        )

        private val CHROME_VIEW_ID_TOKENS = listOf(
            "search",
            "toolbar",
            "voice_search",
            "chip",
            "filter",
            "bottom_bar",
            "pivot_bar",
        )

        private val CHROME_EXACT_LABELS = setOf(
            "all",
            "tat ca",
            "shorts",
            "videos",
            "video",
            "unwatched",
            "chua xem",
            "watched",
            "da xem",
            "filters",
            "bo loc",
            "search",
            "tim kiem",
            "back",
            "quay lai",
            "home",
            "trang chu",
            "subscriptions",
            "kenh dang ky",
            "you",
            "ban",
            "more",
            "them",
            "share",
            "chia se",
            "clear",
            "xoa",
        )

        @Volatile
        private var activeInstance: NaNaAccessibilityService? = null

        @Volatile
        private var armedYouTubeQuery: String? = null

        @Volatile
        private var armedUntilMs: Long = 0L

        /** Arms auto-play for [query] during the next 10 seconds when YouTube search results open. */
        fun armYouTubeAutoPlay(query: String, nowMs: Long = System.currentTimeMillis()) {
            val trimmed = query.trim()
            if (trimmed.isEmpty()) return
            armedYouTubeQuery = trimmed
            armedUntilMs = nowMs + YOUTUBE_ARM_WINDOW_MS
        }

        /** Disarms any pending YouTube auto-play trigger. */
        fun disarmYouTubeAutoPlay() {
            armedYouTubeQuery = null
            armedUntilMs = 0L
        }

        /** Returns true while a YouTube search query is armed and within its 10-second window. */
        fun isYouTubeAutoPlayArmed(nowMs: Long = System.currentTimeMillis()): Boolean =
            armedYouTubeQuery != null && nowMs <= armedUntilMs

        /** Triggers Tier 3 Accessibility Answer/Decline button tap on the active call screen. */
        fun triggerCallAction(wantAnswer: Boolean): Boolean =
            activeInstance?.performCallButtonTap(wantAnswer) ?: false

        /** Returns true when [NaNaAccessibilityService] is enabled in system Accessibility settings. */
        fun isServiceEnabled(context: Context): Boolean {
            val expected = ComponentName(context, NaNaAccessibilityService::class.java)
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ).orEmpty()
            val flattened = expected.flattenToString()
            val shortFlattened = expected.flattenToShortString()
            return enabledServices.split(':').any { entry ->
                entry.equals(flattened, ignoreCase = true) ||
                    entry.equals(shortFlattened, ignoreCase = true)
            }
        }
    }
}
