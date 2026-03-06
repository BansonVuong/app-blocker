package com.appblocker

import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class SnapchatDetector(
    private val detectionIntervalMs: Long,
    private val headerMaxYProvider: () -> Int
) {
    private var lastDetectionMs: Long = 0
    private var lastTab: SnapchatTab = SnapchatTab.UNKNOWN

    fun detect(
        eventType: Int,
        nowMs: Long,
        root: AccessibilityNodeInfo?
    ): SnapchatTab {
        val shouldUpdate = eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            eventType == AccessibilityEvent.TYPE_VIEW_CLICKED
        if (!shouldUpdate) return lastTab
        if (nowMs - lastDetectionMs < detectionIntervalMs) {
            return lastTab
        }

        lastDetectionMs = nowMs
        val rootNode = root ?: return lastTab
        val headerMaxY = headerMaxYProvider()
        var foundStoriesHeader = false
        var foundChatHeader = false
        var selectedStories = false
        var selectedChat = false
        var foundChatUi = false
        var foundStoriesContent = false
        var foundMemories = false

        val queue: ArrayDeque<AccessibilityNodeInfo> = ArrayDeque()
        queue.add(rootNode)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val viewId = node.viewIdResourceName ?: ""
            if (viewId == "com.snapchat.android:id/spotlight_container") {
                lastTab = SnapchatTab.SPOTLIGHT
                return lastTab
            }

            if (!foundChatUi && isChatUiIndicator(viewId)) {
                foundChatUi = true
            }
            if (!foundStoriesContent && isStoriesContentIndicator(viewId, node)) {
                foundStoriesContent = true
            }
            if (!foundMemories && isMemoriesIndicator(viewId, node)) {
                foundMemories = true
            }

            val text = node.text?.toString()?.trim()
            if (!text.isNullOrEmpty()) {
                if (text.equals("Spotlight", ignoreCase = false) && node.isSelected) {
                    lastTab = SnapchatTab.SPOTLIGHT
                    return lastTab
                }
                if (text.equals("Following", ignoreCase = false) && node.isSelected) {
                    lastTab = SnapchatTab.SPOTLIGHT
                    return lastTab
                }
                if (text == "Stories") {
                    if (node.isSelected) {
                        selectedStories = true
                    } else {
                        val bounds = Rect()
                        node.getBoundsInScreen(bounds)
                        if (bounds.top <= headerMaxY) {
                            foundStoriesHeader = true
                        }
                    }
                }
                if (text == "Chat") {
                    if (node.isSelected) {
                        selectedChat = true
                    } else {
                        val bounds = Rect()
                        node.getBoundsInScreen(bounds)
                        if (bounds.top <= headerMaxY) {
                            foundChatHeader = true
                        }
                    }
                }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }

        lastTab = when {
            foundMemories -> SnapchatTab.UNKNOWN
            foundChatUi -> SnapchatTab.CHAT
            selectedStories -> SnapchatTab.STORIES
            selectedChat -> SnapchatTab.CHAT
            foundStoriesHeader && !foundChatHeader && foundStoriesContent -> SnapchatTab.STORIES
            foundChatHeader && !foundStoriesHeader -> SnapchatTab.CHAT
            else -> SnapchatTab.UNKNOWN
        }
        return lastTab
    }

    private fun isChatUiIndicator(viewId: String): Boolean {
        if (viewId.isEmpty()) return false
        return viewId.contains("ff_item") ||
            viewId.contains("list-picker-pill") ||
            viewId.contains("feed_chat_button") ||
            viewId.contains("feed_pinned_convo_button")
    }

    private fun isStoriesContentIndicator(
        viewId: String,
        node: AccessibilityNodeInfo
    ): Boolean {
        if (viewId.contains("friend_card_frame")) return true
        val text = node.text?.toString()?.trim() ?: return false
        return text == "friend_story_circle_thumbnail"
    }

    private fun isMemoriesIndicator(
        viewId: String,
        node: AccessibilityNodeInfo
    ): Boolean {
        if (viewId.contains("memories_grid")) return true
        val text = node.text?.toString()?.trim() ?: return false
        return text == "Memories"
    }
}

class InstagramDetector(
    private val detectionIntervalMs: Long
) {
    private var lastDetectionMs: Long = 0
    private var lastTab: InstagramTab = InstagramTab.UNKNOWN

    fun detect(
        eventType: Int,
        nowMs: Long,
        root: AccessibilityNodeInfo?
    ): InstagramTab {
        val shouldUpdate = eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            eventType == AccessibilityEvent.TYPE_VIEW_CLICKED
        if (!shouldUpdate) return lastTab
        if (nowMs - lastDetectionMs < detectionIntervalMs) {
            return lastTab
        }

        lastDetectionMs = nowMs
        val rootNode = root ?: return lastTab
        var selectedReels = false
        var foundClipsTab = false

        val queue: ArrayDeque<AccessibilityNodeInfo> = ArrayDeque()
        queue.add(rootNode)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val viewId = node.viewIdResourceName.orEmpty()
            val contentDesc = node.contentDescription?.toString()?.trim().orEmpty()
            val text = node.text?.toString()?.trim().orEmpty()

            if (viewId == "com.instagram.android:id/clips_tab") {
                foundClipsTab = true
                selectedReels = node.isSelected || node.isChecked
                if (selectedReels) break
            }

            if (!foundClipsTab &&
                (node.isSelected || node.isChecked) &&
                (contentDesc.equals("Reels", ignoreCase = true) ||
                    text.equals("Reels", ignoreCase = true))
            ) {
                selectedReels = true
                break
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }

        lastTab = if (selectedReels) InstagramTab.REELS else InstagramTab.UNKNOWN
        return lastTab
    }
}

class YouTubeDetector(
    private val detectionIntervalMs: Long
) {
    private var lastDetectionMs: Long = 0
    private var lastTab: YouTubeTab = YouTubeTab.UNKNOWN

    fun detect(
        eventType: Int,
        nowMs: Long,
        root: AccessibilityNodeInfo?
    ): YouTubeTab {
        val shouldUpdate = eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            eventType == AccessibilityEvent.TYPE_VIEW_CLICKED
        if (!shouldUpdate) return lastTab
        if (nowMs - lastDetectionMs < detectionIntervalMs) {
            return lastTab
        }

        lastDetectionMs = nowMs
        val rootNode = root ?: return lastTab
        var selectedShorts = false

        val queue: ArrayDeque<AccessibilityNodeInfo> = ArrayDeque()
        queue.add(rootNode)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val viewId = node.viewIdResourceName.orEmpty()
            val contentDesc = node.contentDescription?.toString()?.trim().orEmpty()
            val text = node.text?.toString()?.trim().orEmpty()

            val isShortsLabel = contentDesc.equals("Shorts", ignoreCase = true) ||
                text.equals("Shorts", ignoreCase = true)
            val isShortsViewId = viewId.contains("shorts", ignoreCase = true)

            if ((isShortsLabel || isShortsViewId) && (node.isSelected || node.isChecked)) {
                selectedShorts = true
                break
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }

        lastTab = if (selectedShorts) YouTubeTab.SHORTS else YouTubeTab.UNKNOWN
        return lastTab
    }
}

class BrowserIncognitoDetector(
    private val detectionIntervalMs: Long
) {
    private var lastDetectionMs: Long = 0
    private var lastIsIncognito: Boolean = false
    private var lastIncognitoDetectionMs: Long = 0

    fun detect(
        eventType: Int,
        nowMs: Long,
        root: AccessibilityNodeInfo?
    ): Boolean {
        val shouldUpdate = eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            eventType == AccessibilityEvent.TYPE_VIEW_CLICKED
        if (!shouldUpdate) return lastIsIncognito
        if (nowMs - lastDetectionMs < detectionIntervalMs) {
            return lastIsIncognito
        }

        lastDetectionMs = nowMs
        val rootNode = root ?: return lastIsIncognito
        val rootPackage = rootNode.packageName?.toString().orEmpty()
        var foundNonIncognitoMarker = false
        val queue: ArrayDeque<AccessibilityNodeInfo> = ArrayDeque()
        queue.add(rootNode)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val viewId = node.viewIdResourceName?.lowercase()
            val text = node.text?.toString()?.lowercase()
            val description = node.contentDescription?.toString()?.lowercase()

            if (viewId != null && isIncognitoIndicator(viewId)) {
                lastIsIncognito = true
                lastIncognitoDetectionMs = nowMs
                return true
            }
            if (text != null && isIncognitoIndicator(text)) {
                lastIsIncognito = true
                lastIncognitoDetectionMs = nowMs
                return true
            }
            if (description != null && isIncognitoIndicator(description)) {
                lastIsIncognito = true
                lastIncognitoDetectionMs = nowMs
                return true
            }
            if ((viewId != null && isNonIncognitoIndicator(viewId)) ||
                (text != null && isNonIncognitoIndicator(text)) ||
                (description != null && isNonIncognitoIndicator(description))
            ) {
                foundNonIncognitoMarker = true
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }

        if (!foundNonIncognitoMarker &&
            isFirefoxPackage(rootPackage) &&
            lastIsIncognito &&
            (nowMs - lastIncognitoDetectionMs) <= 5_000L
        ) {
            return true
        }

        lastIsIncognito = false
        return false
    }

    private fun isIncognitoIndicator(value: String): Boolean {
        if (value.contains("incognito")) return true
        if (value.contains("inprivate")) return true

        val compact = value.replace(Regex("[^a-z0-9]"), "")
        if (compact.contains("privatebrowsing")) return true
        if (compact.contains("privatetab")) return true
        if (compact.contains("privatemode")) return true
        if (compact.contains("secretmode")) return true

        if (value.contains("secret mode")) return true
        if (value.contains("private browsing")) return true
        if (value.contains("private tab")) return true
        if (value.contains("private tabs open")) return true
        if (value.contains("private mode")) return true
        return false
    }

    private fun isNonIncognitoIndicator(value: String): Boolean {
        return value.contains("tabs open:") &&
            !value.contains("private") &&
            !value.contains("incognito") &&
            !value.contains("inprivate")
    }

    private fun isFirefoxPackage(packageName: String): Boolean {
        return packageName == "org.mozilla.firefox" ||
            packageName == "org.mozilla.firefox_beta" ||
            packageName == "org.mozilla.fenix"
    }
}

enum class SnapchatTab {
    STORIES,
    SPOTLIGHT,
    CHAT,
    UNKNOWN
}

enum class InstagramTab {
    REELS,
    UNKNOWN
}

enum class YouTubeTab {
    SHORTS,
    UNKNOWN
}
