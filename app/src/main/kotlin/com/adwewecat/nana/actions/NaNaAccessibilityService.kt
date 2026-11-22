package com.adwewecat.nana.actions

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
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
        } else if (packageName == ZALO_PACKAGE && isZaloAutoCallArmed()) {
            handleZaloAutoCallOnRoot(rootInActiveWindow)
        }
    }

    override fun onInterrupt() = Unit

    /**
     * Drives Zalo UI automation within a 15-second armed window:
     * 1. If opened to Zalo home (`!directChatOpened`), taps the search bar, types the contact name, and taps the matching row.
     * 2. Inside the chat screen, taps the voice call button (`Gọi thoại` / `Call`) and disarms.
     */
    fun handleZaloAutoCallOnRoot(
        root: AccessibilityNodeInfo?,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (!isZaloAutoCallArmed(nowMs)) {
            disarmZaloAutoCall()
            return false
        }
        val target = armedZaloTarget ?: return false
        if (root == null || root.packageName?.toString() != ZALO_PACKAGE) {
            return false
        }

        // 1. If a voice-call button is already visible (e.g. inside the person's chat window), tap it and finish.
        val voiceCallNode = findZaloVoiceCallNode(root)
        if (voiceCallNode != null) {
            val clickable = findClickableAncestor(voiceCallNode)
            if (clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                disarmZaloAutoCall()
                return true
            }
        }

        val normTarget = FastCommandParser.normalizeVietnamese(target)
        if (normTarget.isEmpty()) return false

        // 2. If a search EditText is visible, inject the target name and tap the matching contact result.
        val searchInput = findFirstEditText(root)
        if (searchInput != null) {
            if (!zaloTextTyped) {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, target)
                }
                searchInput.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                if (searchInput.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                    zaloTextTyped = true
                    return true
                }
            }
            val contactMatch = findZaloContactResultNode(root, normTarget, searchInput)
            if (contactMatch != null) {
                val clickable = findClickableAncestor(contactMatch)
                if (clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    zaloChatSelected = true
                    return true
                }
            }
        }

        // 3. Otherwise tap Zalo's top search bar to open the search input.
        if (!zaloSearchOpened && !zaloChatSelected) {
            val searchBar = findZaloSearchEntryNode(root)
            if (searchBar != null) {
                val clickable = findClickableAncestor(searchBar)
                if (clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    zaloSearchOpened = true
                    return true
                }
            }
        }
        return false
    }

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

    private fun findFirstEditText(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        findNodeBreadthFirst(root) { node ->
            node.className?.toString()?.endsWith("EditText") == true && node.isVisibleToUser
        }

    private fun findZaloSearchEntryNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        findNodeBreadthFirst(root) { node ->
            val labels = listOfNotNull(
                node.text?.toString(),
                node.contentDescription?.toString(),
                node.viewIdResourceName,
            )
            labels.any { raw ->
                val norm = FastCommandParser.normalizeVietnamese(raw)
                norm.contains("tim kiem") || norm.contains("search")
            } && findClickableAncestor(node) != null
        }

    private fun findZaloContactResultNode(
        root: AccessibilityNodeInfo,
        normTarget: String,
        searchInput: AccessibilityNodeInfo,
    ): AccessibilityNodeInfo? =
        findNodeBreadthFirst(root) { node ->
            if (node == searchInput || node.className?.toString()?.endsWith("EditText") == true) {
                false
            } else {
                val labels = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
                labels.any { raw ->
                    val norm = FastCommandParser.normalizeVietnamese(raw)
                    norm.isNotEmpty() && (norm == normTarget || norm.contains(normTarget))
                } && findClickableAncestor(node) != null
            }
        }

    private fun findZaloVoiceCallNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        findNodeBreadthFirst(root) { node ->
            val labels = listOfNotNull(
                node.contentDescription?.toString(),
                node.text?.toString(),
                node.viewIdResourceName,
            )
            labels.any { raw ->
                val norm = FastCommandParser.normalizeVietnamese(raw)
                val isVoiceCall = norm == "goi thoai" ||
                    norm == "goi dien" ||
                    norm == "audio call" ||
                    norm == "voice call" ||
                    norm.contains("menu_call") ||
                    norm.contains("btn_call") ||
                    norm.contains("action_call") ||
                    (norm == "goi" && !norm.contains("video"))
                isVoiceCall && !norm.contains("video")
            } && findClickableAncestor(node) != null
        }

    private fun findNodeBreadthFirst(
        root: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        val visited = IdentityHashMap<AccessibilityNodeInfo, Boolean>()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        while (queue.isNotEmpty()) {
            val (node, depth) = queue.removeFirst()
            if (visited.put(node, true) != null || depth > MAX_TREE_DEPTH) continue
            if (predicate(node)) return node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { child -> queue.add(child to depth + 1) }
            }
        }
        return null
    }

    companion object {
        const val YOUTUBE_PACKAGE = "com.google.android.youtube"
        const val ZALO_PACKAGE = "com.zing.zalo"
        const val YOUTUBE_ARM_WINDOW_MS = 10_000L
        const val ZALO_ARM_WINDOW_MS = 15_000L

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

        @Volatile
        private var armedZaloTarget: String? = null

        @Volatile
        private var armedZaloUntilMs: Long = 0L

        @Volatile
        private var zaloSearchOpened: Boolean = false

        @Volatile
        private var zaloTextTyped: Boolean = false

        @Volatile
        private var zaloChatSelected: Boolean = false

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

        /** Arms Zalo contact search & voice-call tap for [target] during the next 15 seconds. */
        fun armZaloAutoCall(
            target: String,
            directChatOpened: Boolean = false,
            nowMs: Long = System.currentTimeMillis(),
        ) {
            val trimmed = target.trim()
            if (trimmed.isEmpty()) return
            armedZaloTarget = trimmed
            armedZaloUntilMs = nowMs + ZALO_ARM_WINDOW_MS
            zaloSearchOpened = directChatOpened
            zaloTextTyped = directChatOpened
            zaloChatSelected = directChatOpened
        }

        /** Disarms any pending Zalo auto-call trigger. */
        fun disarmZaloAutoCall() {
            armedZaloTarget = null
            armedZaloUntilMs = 0L
            zaloSearchOpened = false
            zaloTextTyped = false
            zaloChatSelected = false
        }

        /** Returns true while a Zalo auto-call target is armed and within its 15-second window. */
        fun isZaloAutoCallArmed(nowMs: Long = System.currentTimeMillis()): Boolean =
            armedZaloTarget != null && nowMs <= armedZaloUntilMs

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
