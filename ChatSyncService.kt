package com.selim.superbubble

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

class ChatSyncService : AccessibilityService() {

    private var lastSyncTime = 0L

    private data class ScrapedMsg(
        val text: String,
        val isMe: Boolean,
        val topY: Int
    )

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        if (!SettingsManager.ALL_SUPPORTED_PACKAGES.contains(pkg)) return

        val root = rootInActiveWindow ?: return
        val metrics = resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels

        // Instagram Arama Tıklaması (Sadece üst bar alanında ve tam eşleşen butonlarda)
        if (pkg.contains("instagram") && BubbleService.pendingInstaCallVideo != null) {
            val elapsed = System.currentTimeMillis() - BubbleService.pendingInstaCallTimestamp
            if (elapsed < 7000) {
                val wantVideo = BubbleService.pendingInstaCallVideo == true
                if (tryClickInstagramCallButton(root, wantVideo, screenHeight)) {
                    BubbleService.pendingInstaCallVideo = null
                    return
                }
            } else {
                BubbleService.pendingInstaCallVideo = null
            }
        }

        val now = System.currentTimeMillis()
        if (now - lastSyncTime < 900) return
        lastSyncTime = now

        try {
            val titleNode = findHeaderTitle(root, pkg) ?: return
            val chatTitle = titleNode.trim()
            if (chatTitle.isEmpty()) return

            val scraped = mutableListOf<ScrapedMsg>()
            collectMessagesFromNode(root, pkg, screenWidth, scraped)

            if (scraped.isNotEmpty()) {
                // Ekranın üstünden altına doğru (Y ekseni) sırala ki mesaj sırası bozulmasın
                scraped.sortBy { it.topY }
                val orderedPairs = scraped.map { Pair(it.text, it.isMe) }
                BubbleService.instance?.syncFromAppScreen(pkg, chatTitle, orderedPairs)
            }
        } catch (_: Exception) {}
    }

    private fun collectMessagesFromNode(
        root: AccessibilityNodeInfo,
        pkg: String,
        screenWidth: Int,
        outList: MutableList<ScrapedMsg>
    ) {
        val idList = when {
            pkg.contains("whatsapp") -> listOf("com.whatsapp:id/message_text", "com.whatsapp.w4b:id/message_text")
            pkg.contains("instagram") -> listOf("com.instagram.android:id/direct_text_message_text_view")
            else -> emptyList()
        }

        for (id in idList) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id) ?: continue
            for (node in nodes) {
                val txt = node.text?.toString()?.trim() ?: continue
                if (txt.isEmpty()) continue
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (rect.width() <= 0 || rect.height() <= 0) continue

                // Sağ boşluk sol boşluktan daha küçükse mesaj sağa yaslıdır (Benim mesajım)
                val leftMargin = rect.left
                val rightMargin = screenWidth - rect.right
                val isMe = rightMargin < leftMargin && leftMargin > (screenWidth * 0.12f)
                outList.add(ScrapedMsg(txt, isMe, rect.top))
            }
        }
    }

    private fun tryClickInstagramCallButton(
        root: AccessibilityNodeInfo,
        isVideo: Boolean,
        screenHeight: Int
    ): Boolean {
        val exactTargets = if (isVideo) {
            setOf("görüntülü arama", "video call", "görüntülü sohbet başlat")
        } else {
            setOf("sesli arama", "audio call", "voice call")
        }

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val desc = node.contentDescription?.toString()?.trim()?.lowercase(Locale.getDefault()) ?: ""
            if (desc.isNotEmpty() && exactTargets.any { desc == it || desc.startsWith(it) }) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                // Sadece üst başlık (Header) barındaki arama ikonlarına tıkla
                if (rect.top >= 0 && rect.bottom <= screenHeight * 0.22f) {
                    if (node.isClickable) {
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        return true
                    } else if (node.parent?.isClickable == true) {
                        node.parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        return true
                    }
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return false
    }

    private fun findHeaderTitle(root: AccessibilityNodeInfo, pkg: String): String? {
        val idCandidates = if (pkg.contains("whatsapp")) {
            listOf("com.whatsapp:id/conversation_contact_name", "com.whatsapp.w4b:id/conversation_contact_name")
        } else {
            listOf(
                "com.instagram.android:id/thread_title",
                "com.instagram.android:id/header_title"
            )
        }

        for (id in idCandidates) {
            val list = root.findAccessibilityNodeInfosByViewId(id)
            if (!list.isNullOrEmpty()) {
                val txt = list[0].text?.toString()?.trim()
                if (!txt.isNullOrEmpty()) return txt
            }
        }
        return null
    }

    override fun onInterrupt() {}
}