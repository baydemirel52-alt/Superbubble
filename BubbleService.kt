package com.selim.superbubble

import android.Manifest
import android.animation.ValueAnimator
import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class BubbleService : NotificationListenerService() {

    companion object {
        var instance: BubbleService? = null

        var pendingInstaCallVideo: Boolean? = null
        var pendingInstaCallTimestamp: Long = 0L

        fun spawnTestBubble(context: Context) {
            if (!Settings.canDrawOverlays(context)) {
                Toast.makeText(context, "Kanka önce 1. İzin olan Ekran Üstü Balon iznini açmalısın!", Toast.LENGTH_LONG).show()
                return
            }
            val srv = instance
            if (srv == null) {
                Toast.makeText(context, "Kanka önce 2. İzin olan Bildirim Yakalama iznini açmalısın!", Toast.LENGTH_LONG).show()
                return
            }
            val now = System.currentTimeMillis()
            srv.mainHandler.post {
                srv.onNewMessage(
                    chatKey = "test_messenger",
                    sbnKey = null,
                    shortcutId = null,
                    whatsappJid = null,
                    pkgName = "com.whatsapp",
                    sender = "Test Sohbeti",
                    messagesBatch = listOf(
                        ChatMessage(
                            id = NotificationParser.buildMessageId(now, "Test Sohbeti", false, "test"),
                            chatKey = "test_messenger",
                            senderName = null,
                            text = "Kanka Room DB, RecyclerView, Telegram ve gerçek zaman damgalı mesaj sistemi hazır! (Bu test balonunda canlı bildirim olmadığı için yanıt kutusu kapalıdır).",
                            isMe = false,
                            time = NotificationParser.formatTime(now),
                            timestamp = now
                        )
                    ),
                    appName = "WhatsApp",
                    avatar = null,
                    replyAction = null,
                    contentIntent = null,
                    callIntent = null,
                    isIncomingCall = false
                )
                Toast.makeText(context, "Balon hazır kanka!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    data class ChatSession(
        val key: String,
        var sbnKey: String? = null,
        var shortcutId: String? = null,
        var whatsappJid: String? = null,
        var pkgName: String = "com.whatsapp",
        var sender: String,
        val appName: String,
        val messages: MutableList<ChatMessage> = mutableListOf(),
        var avatar: Bitmap? = null,
        var replyAction: Notification.Action? = null,
        var contentIntent: PendingIntent? = null,
        var callIntent: PendingIntent? = null,
        var isIncomingCall: Boolean = false,
        var unreadCount: Int = 0
    )

    private lateinit var windowManager: WindowManager
    lateinit var repository: ChatRepository
    val mainHandler = Handler(Looper.getMainLooper())
    private val activeSessions = LinkedHashMap<String, ChatSession>()
    private var activeChatKey: String? = null

    private var mediaPlayer: MediaPlayer? = null

    // Küçük Yüzen Balon
    private var bubbleView: FrameLayout? = null
    private var bubbleAvatarContainer: FrameLayout? = null
    private var bubbleAvatarImg: ImageView? = null
    private var bubbleInitialsTxt: TextView? = null
    private var bubbleAppBadge: TextView? = null
    private var bubbleUnreadBadge: TextView? = null
    private lateinit var bubbleParams: WindowManager.LayoutParams

    // Silme (X) Alanı
    private var dismissBarView: FrameLayout? = null
    private lateinit var dismissParams: WindowManager.LayoutParams

    // Açık Sohbet Penceresi
    private var chatWindowHolder: ChatWindowHolder? = null
    private lateinit var expandedParams: WindowManager.LayoutParams
    private var isExpanded = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        repository = ChatRepository(this)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        // Samsung S22 Ultra'da servis düşerse otomatik yeniden bağlan
        try {
            requestRebind(ComponentName(this, BubbleService::class.java))
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAudio()
        destroyAllOverlays()
        instance = null
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        mainHandler.post {
            val metrics = resources.displayMetrics
            val bSize = SettingsManager.getBubbleSizeDp(this)
            if (bubbleView != null) {
                bubbleParams.x = metrics.widthPixels - dpToPx(bSize + 8)
                bubbleParams.y = metrics.heightPixels / 4
                try {
                    windowManager.updateViewLayout(bubbleView, bubbleParams)
                } catch (_: Exception) {}
            }
            if (isExpanded) {
                val currentKey = activeChatKey
                collapseChat()
                activeChatKey = currentKey
                openExpandedChat()
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        mainHandler.post {
            activeSessions.values.forEach { sess ->
                if (sess.sbnKey == sbn.key && sess.isIncomingCall) {
                    sess.isIncomingCall = false
                    sess.callIntent = null
                    refreshBubbleAppearance(sess)
                    if (isExpanded) refreshExpandedUI(forceScrollBottom = false)
                }
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val pkg = sbn.packageName
        if (!SettingsManager.ALL_SUPPORTED_PACKAGES.contains(pkg)) return

        repository.ioExecutor.execute {
            // Arka plan thread'inde yakalanmayan tek bir hata bile uygulamayı çökertir, o yüzden her şey try/catch içinde.
            try {
            val parsed = NotificationParser.parse(this, sbn, repository) ?: return@execute
            when (parsed) {
                is ParsedNotification.RemoteReplyConfirmation -> {
                    if (parsed.confirmedTexts.isNotEmpty()) {
                        repository.markDeliveredAsync(parsed.chatKey, parsed.confirmedTexts) {
                            mainHandler.post {
                                val sess = activeSessions[parsed.chatKey]
                                if (sess != null) {
                                    for (i in sess.messages.indices) {
                                        val m = sess.messages[i]
                                        if (m.isMe && parsed.confirmedTexts.contains(m.text)) {
                                            sess.messages[i] = m.copy(isDelivered = true)
                                        }
                                    }
                                    if (isExpanded && activeChatKey == parsed.chatKey) {
                                        refreshExpandedUI(forceScrollBottom = false)
                                    }
                                }
                            }
                        }
                    }
                    scheduleCancelNotification(parsed.sbnKey, 350)
                }

                is ParsedNotification.IncomingCall -> {
                    if (!SettingsManager.shouldShowNotification(this, parsed.pkgName, parsed.sender)) return@execute
                    val now = System.currentTimeMillis()
                    val callMsg = ChatMessage(
                        id = NotificationParser.buildMessageId(now, parsed.sender, false, parsed.callDescription),
                        chatKey = parsed.chatKey,
                        senderName = null,
                        text = parsed.callDescription,
                        isMe = false,
                        time = NotificationParser.formatTime(now),
                        timestamp = now
                    )
                    mainHandler.post {
                        onNewMessage(
                            chatKey = parsed.chatKey,
                            sbnKey = parsed.sbnKey,
                            shortcutId = null,
                            whatsappJid = null,
                            pkgName = parsed.pkgName,
                            sender = parsed.sender,
                            messagesBatch = listOf(callMsg),
                            appName = parsed.appName,
                            avatar = parsed.avatar,
                            replyAction = null,
                            contentIntent = parsed.contentIntent,
                            callIntent = parsed.callIntent,
                            isIncomingCall = true
                        )
                    }
                }

                is ParsedNotification.IncomingMessages -> {
                    if (!SettingsManager.shouldShowNotification(this, parsed.pkgName, parsed.sender)) return@execute
                    mainHandler.post {
                        onNewMessage(
                            chatKey = parsed.chatKey,
                            sbnKey = parsed.sbnKey,
                            shortcutId = parsed.shortcutId,
                            whatsappJid = parsed.whatsappJid,
                            pkgName = parsed.pkgName,
                            sender = parsed.sender,
                            messagesBatch = parsed.messages,
                            appName = parsed.appName,
                            avatar = parsed.avatar,
                            replyAction = parsed.replyAction,
                            contentIntent = parsed.contentIntent,
                            callIntent = null,
                            isIncomingCall = false
                        )

                        if (parsed.needsWhatsAppMediaFallback) {
                            mainHandler.postDelayed({
                                repository.ioExecutor.execute {
                                    val waPhotoPath = repository.fetchLatestWhatsAppPhoto(this, parsed.postTimeSec)
                                    if (waPhotoPath != null) {
                                        mainHandler.post {
                                            val sess = activeSessions[parsed.chatKey]
                                            val lastIdx = sess?.messages?.lastIndex ?: -1
                                            if (sess != null && lastIdx >= 0) {
                                                val last = sess.messages[lastIdx]
                                                if (!last.isMe && last.imagePath == null) {
                                                    val updated = last.copy(imagePath = waPhotoPath)
                                                    sess.messages[lastIdx] = updated
                                                    repository.saveMessagesAsync(parsed.chatKey, listOf(updated))
                                                    if (isExpanded && activeChatKey == parsed.chatKey) {
                                                        refreshExpandedUI(forceScrollBottom = false)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }, 1100)
                        }
                    }
                }
            }
            } catch (e: Exception) {
                Log.e("SuperBubble", "Bildirim islenirken hata", e)
            }
        }
    }

    private fun onNewMessage(
        chatKey: String,
        sbnKey: String?,
        shortcutId: String?,
        whatsappJid: String?,
        pkgName: String,
        sender: String,
        messagesBatch: List<ChatMessage>,
        appName: String,
        avatar: Bitmap?,
        replyAction: Notification.Action?,
        contentIntent: PendingIntent?,
        callIntent: PendingIntent?,
        isIncomingCall: Boolean
    ) {
        if (!Settings.canDrawOverlays(this)) return

        val existingSession = activeSessions[chatKey]
        if (existingSession == null) {
            val newSession = ChatSession(
                key = chatKey,
                sbnKey = sbnKey,
                shortcutId = shortcutId,
                whatsappJid = whatsappJid,
                pkgName = pkgName,
                sender = sender,
                appName = appName,
                avatar = avatar,
                replyAction = replyAction,
                contentIntent = contentIntent,
                callIntent = callIntent,
                isIncomingCall = isIncomingCall
            )
            activeSessions[chatKey] = newSession
            repository.loadMessagesAsync(chatKey) { savedHistory ->
                mainHandler.post {
                    val existingIds = newSession.messages.map { it.id }.toHashSet()
                    val merged = mutableListOf<ChatMessage>()
                    savedHistory.forEach { if (!existingIds.contains(it.id)) merged.add(it) }
                    merged.addAll(newSession.messages)
                    merged.sortBy { it.timestamp }
                    newSession.messages.clear()
                    newSession.messages.addAll(merged)
                    appendAndDisplayBatch(newSession, messagesBatch, isIncomingCall)
                }
            }
        } else {
            existingSession.sender = sender
            existingSession.pkgName = pkgName
            if (sbnKey != null) existingSession.sbnKey = sbnKey
            if (shortcutId != null) existingSession.shortcutId = shortcutId
            if (whatsappJid != null) existingSession.whatsappJid = whatsappJid
            if (avatar != null) existingSession.avatar = avatar
            if (replyAction != null) existingSession.replyAction = replyAction
            if (contentIntent != null) existingSession.contentIntent = contentIntent
            existingSession.callIntent = callIntent
            existingSession.isIncomingCall = isIncomingCall

            appendAndDisplayBatch(existingSession, messagesBatch, isIncomingCall)
        }
    }

    private fun appendAndDisplayBatch(
        session: ChatSession,
        messagesBatch: List<ChatMessage>,
        isIncomingCall: Boolean
    ) {
        val newlyAdded = mutableListOf<ChatMessage>()
        val claimedOwnIds = HashSet<String>()
        for (incoming in messagesBatch) {
            val idx = session.messages.indexOfFirst { it.id == incoming.id }
            if (idx >= 0) {
                val old = session.messages[idx]
                if (incoming.imagePath != null && old.imagePath == null) {
                    val updated = old.copy(imagePath = incoming.imagePath)
                    session.messages[idx] = updated
                    newlyAdded.add(updated)
                }
            } else {
                // Balondan yazdığımız mesaj anında ekrana eklenir (zamanı bizim saatimiz). WhatsApp o mesajı sonradan
                // bildirimde kendi zamanıyla listeleyince id tutmaz ve mesaj iki kere görünürdü. Aynı metin + 30 sn
                // içindeyse bunu aynı mesaj sayıyoruz.
                val ownTwinIdx = if (incoming.isMe) {
                    session.messages.indexOfFirst {
                        it.isMe && it.id !in claimedOwnIds &&
                            NotificationParser.isSameOwnMessage(it.text, it.timestamp, incoming.text, incoming.timestamp)
                    }
                } else {
                    -1
                }
                if (ownTwinIdx >= 0) {
                    claimedOwnIds.add(session.messages[ownTwinIdx].id)
                } else {
                    session.messages.add(incoming)
                    newlyAdded.add(incoming)
                }
            }
        }

        if (newlyAdded.isNotEmpty()) {
            repository.saveMessagesAsync(session.key, newlyAdded)
            if (!isExpanded || activeChatKey != session.key) {
                session.unreadCount++
            } else if (!isIncomingCall) {
                scheduleCancelNotification(session.sbnKey, 200)
            }
        }

        // Balon kapalıyken veya arama geldiğinde activeChatKey doğrudan son yazan kişiye geçer!
        if (!isExpanded || isIncomingCall || activeChatKey == null) {
            activeChatKey = session.key
        }

        ensureBubbleCreated()
        val bubbleTarget = activeSessions[activeChatKey] ?: session
        refreshBubbleAppearance(bubbleTarget)

        if (isExpanded) {
            refreshExpandedUI(forceScrollBottom = false)
        }
    }

    // ChatSyncService sadece aktif olan bir sohbet varsa eşitler, kafalara rastgele sohbet eklemez
    fun syncFromAppScreen(
        pkgName: String,
        title: String,
        screenMessages: List<Pair<String, Boolean>>
    ) {
        val cleanTitle = title.trim()
        if (cleanTitle.isEmpty()) return

        mainHandler.post {
            val session = activeSessions.values.firstOrNull {
                it.pkgName == pkgName && it.sender.equals(cleanTitle, ignoreCase = true)
            } ?: return@post

            // Ekrandaki TÜM görünen mesajları eklemek yerine, sadece bubble'ın bildiği son mesajlardan SONRA gelenleri ekle.
            // (Eskiden ekrandaki eski mesajlar sona, "şimdi" saatiyle eklenip sırayı bozuyordu.)
            val known = session.messages.map { Pair(it.text, it.isMe) }
            val fresh = NotificationParser.findNewScreenMessages(known, screenMessages)
            if (fresh.isEmpty()) return@post

            val newlySynced = mutableListOf<ChatMessage>()
            val baseTime = max(System.currentTimeMillis(), (session.messages.lastOrNull()?.timestamp ?: 0L) + 1L)
            for (i in fresh.indices) {
                val (msgText, isMe) = fresh[i]
                val ts = baseTime + i
                val msg = ChatMessage(
                    id = NotificationParser.buildMessageId(ts, cleanTitle, isMe, msgText),
                    chatKey = session.key,
                    senderName = null,
                    text = msgText,
                    isMe = isMe,
                    time = NotificationParser.formatTime(ts),
                    timestamp = ts,
                    isDelivered = isMe
                )
                session.messages.add(msg)
                newlySynced.add(msg)
            }

            if (newlySynced.isNotEmpty()) {
                repository.saveMessagesAsync(session.key, newlySynced)
                if (isExpanded && activeChatKey == session.key) {
                    refreshExpandedUI(forceScrollBottom = false)
                }
            }
        }
    }

    // ==================== 1. YÜZEN BALON ====================
    private fun ensureBubbleCreated() {
        if (bubbleView != null || !Settings.canDrawOverlays(this)) return
        val metrics = resources.displayMetrics
        val bSize = SettingsManager.getBubbleSizeDp(this)
        val innerSize = bSize - 8

        bubbleParams = WindowManager.LayoutParams(
            dpToPx(bSize),
            dpToPx(bSize),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = metrics.widthPixels - dpToPx(bSize + 8)
            y = metrics.heightPixels / 4
        }

        bubbleView = FrameLayout(this)

        bubbleAvatarContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(dpToPx(innerSize), dpToPx(innerSize), Gravity.CENTER)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#0084FF"))
                setStroke(dpToPx(2), Color.WHITE)
            }
            elevation = dpToPx(6).toFloat()
        }

        bubbleInitialsTxt = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            gravity = Gravity.CENTER
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }

        bubbleAvatarImg = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(dpToPx(innerSize - 4), dpToPx(innerSize - 4), Gravity.CENTER)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }

        bubbleAvatarContainer?.addView(bubbleInitialsTxt)
        bubbleAvatarContainer?.addView(bubbleAvatarImg)

        bubbleAppBadge = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(dpToPx(22), dpToPx(22), Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = dpToPx(2)
                bottomMargin = dpToPx(2)
            }
            gravity = Gravity.CENTER
            textSize = 9f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            elevation = dpToPx(8).toFloat()
        }

        bubbleUnreadBadge = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(dpToPx(22), dpToPx(22), Gravity.TOP or Gravity.END).apply {
                rightMargin = dpToPx(2)
                topMargin = dpToPx(2)
            }
            gravity = Gravity.CENTER
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#FF3B30"))
                setStroke(dpToPx(1), Color.WHITE)
            }
            elevation = dpToPx(8).toFloat()
            visibility = View.GONE
        }

        bubbleView?.addView(bubbleAvatarContainer)
        bubbleView?.addView(bubbleAppBadge)
        bubbleView?.addView(bubbleUnreadBadge)

        createDismissBar()
        setupBubbleTouchPhysics()

        try {
            windowManager.addView(bubbleView, bubbleParams)
        } catch (_: Exception) {
            bubbleView = null
        }
    }

    private fun createDismissBar() {
        if (dismissBarView != null || !Settings.canDrawOverlays(this)) return
        dismissParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            dpToPx(90),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }

        dismissBarView = FrameLayout(this).apply {
            visibility = View.GONE
        }

        val pill = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                dpToPx(48),
                Gravity.CENTER
            )
            text = "✕  Balonu Gizle"
            textSize = 13.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dpToPx(20), 0, dpToPx(20), 0)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#DDFF3B30"))
                cornerRadius = dpToPx(24).toFloat()
                setStroke(dpToPx(2), Color.WHITE)
            }
        }

        dismissBarView?.addView(pill)
        try {
            windowManager.addView(dismissBarView, dismissParams)
        } catch (_: Exception) {
            dismissBarView = null
        }
    }

    private fun setupBubbleTouchPhysics() {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var isDragging = false

        bubbleView?.setOnTouchListener { _, event ->
            val metrics = resources.displayMetrics
            val bSize = SettingsManager.getBubbleSizeDp(this)
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = bubbleParams.x
                    startY = bubbleParams.y
                    touchX = event.rawX
                    touchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > 12 || abs(dy) > 12) {
                        isDragging = true
                        dismissBarView?.visibility = View.VISIBLE
                    }
                    if (isDragging) {
                        bubbleParams.x = max(0, min(metrics.widthPixels - dpToPx(bSize), startX + dx))
                        bubbleParams.y = max(dpToPx(24), min(metrics.heightPixels - dpToPx(bSize + 12), startY + dy))
                        try {
                            windowManager.updateViewLayout(bubbleView, bubbleParams)
                        } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    dismissBarView?.visibility = View.GONE
                    if (!isDragging) {
                        val callingSession = activeSessions.values.firstOrNull { it.isIncomingCall && it.callIntent != null }
                        if (callingSession != null) {
                            if (sendPendingIntentSafely(callingSession.callIntent)) {
                                callingSession.isIncomingCall = false
                                refreshBubbleAppearance(callingSession)
                                return@setOnTouchListener true
                            }
                        }
                        openExpandedChat()
                    } else {
                        if (event.rawY > metrics.heightPixels * 0.82f) {
                            activeSessions.clear()
                            destroyAllOverlays()
                        } else {
                            val targetX = if (bubbleParams.x + dpToPx(bSize / 2) < metrics.widthPixels / 2) {
                                dpToPx(8)
                            } else {
                                metrics.widthPixels - dpToPx(bSize + 8)
                            }
                            animateBubbleToEdge(bubbleParams.x, targetX)
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun animateBubbleToEdge(fromX: Int, toX: Int) {
        ValueAnimator.ofInt(fromX, toX).apply {
            duration = 200
            addUpdateListener { anim ->
                if (bubbleView != null) {
                    bubbleParams.x = anim.animatedValue as Int
                    try {
                        windowManager.updateViewLayout(bubbleView, bubbleParams)
                    } catch (_: Exception) {}
                }
            }
            start()
        }
    }

    private fun refreshBubbleAppearance(session: ChatSession) {
        if (session.avatar != null) {
            bubbleAvatarImg?.setImageBitmap(session.avatar)
            bubbleAvatarImg?.visibility = View.VISIBLE
            bubbleInitialsTxt?.visibility = View.GONE
        } else {
            bubbleAvatarImg?.visibility = View.GONE
            bubbleInitialsTxt?.visibility = View.VISIBLE
            bubbleInitialsTxt?.text = session.sender.take(1).uppercase()
        }

        bubbleAvatarContainer?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(if (session.isIncomingCall) "#00C853" else "#0084FF"))
            setStroke(
                dpToPx(if (session.isIncomingCall) 3 else 2),
                if (session.isIncomingCall) Color.parseColor("#00E676") else Color.WHITE
            )
        }

        val badgeColor = when {
            session.isIncomingCall -> "#00C853"
            session.appName == "Instagram" -> "#E1306C"
            session.appName == "Telegram" -> "#24A1DE"
            else -> "#25D366"
        }
        val badgeText = when {
            session.isIncomingCall -> "📞"
            session.appName == "Instagram" -> "IG"
            session.appName == "Telegram" -> "TG"
            else -> "W"
        }

        bubbleAppBadge?.text = badgeText
        bubbleAppBadge?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(badgeColor))
            setStroke(dpToPx(1), Color.WHITE)
        }

        val totalUnread = activeSessions.values.sumOf { it.unreadCount }
        if (totalUnread > 0 && !isExpanded) {
            bubbleUnreadBadge?.text = totalUnread.toString()
            bubbleUnreadBadge?.visibility = View.VISIBLE
        } else {
            bubbleUnreadBadge?.visibility = View.GONE
        }
    }

    // ==================== 2. AÇIK SOHBET PENCERESİ ====================
    private fun openExpandedChat() {
        if (isExpanded || activeSessions.isEmpty() || !Settings.canDrawOverlays(this)) return
        isExpanded = true

        bubbleView?.visibility = View.GONE

        expandedParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            PixelFormat.TRANSLUCENT
        ).apply {
            dimAmount = 0.55f
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        val holder = ChatWindowHolder(this, repository)
        val view = holder.build(
            onCollapse = { collapseChat() },
            onCloseAll = {
                activeSessions.clear()
                destroyAllOverlays()
            },
            onClearHistory = {
                val active = activeSessions[activeChatKey] ?: return@build
                active.messages.clear()
                repository.clearChatAsync(this, active.key)
                refreshExpandedUI(forceScrollBottom = true)
                Toast.makeText(this, "Konuşma geçmişi temizlendi", Toast.LENGTH_SHORT).show()
            },
            onMuteToggle = {
                val active = activeSessions[activeChatKey] ?: return@build
                val muted = SettingsManager.toggleMuteSender(this, active.sender)
                Toast.makeText(
                    this,
                    if (muted) "${active.sender} sessize alındı 🔕" else "${active.sender} sessizden çıkarıldı 🔔",
                    Toast.LENGTH_SHORT
                ).show()
                refreshExpandedUI(forceScrollBottom = false)
            },
            onVoiceCall = { initiateCall(isVideo = false) },
            onVideoCall = { initiateCall(isVideo = true) },
            onOpenApp = { openOriginalApp() },
            onSendMessage = { sendMessageNow() },
            onAnswerIncomingCall = {
                val active = activeSessions[activeChatKey]
                if (!sendPendingIntentSafely(active?.callIntent) && !sendPendingIntentSafely(active?.contentIntent)) {
                    openOriginalApp()
                } else {
                    active?.isIncomingCall = false
                    collapseChat()
                }
            },
            onPlayAudio = { uri ->
                if (uri != null) playOrToggleAudio(uri) else openOriginalApp()
            },
            onSharePhoto = { uri -> sharePhotoToActiveApp(uri) },
            onStartVoiceDictation = { startVoiceDictation() }
        )

        chatWindowHolder = holder
        try {
            windowManager.addView(view, expandedParams)
            refreshExpandedUI(forceScrollBottom = true)
        } catch (_: Exception) {
            isExpanded = false
            chatWindowHolder = null
        }
    }

    private fun refreshExpandedUI(forceScrollBottom: Boolean) {
        val holder = chatWindowHolder ?: return
        if (!isExpanded || holder.rootOverlay == null) return

        val active = activeSessions[activeChatKey] ?: activeSessions.values.firstOrNull() ?: return
        activeChatKey = active.key
        active.unreadCount = 0

        if (!active.isIncomingCall) {
            scheduleCancelNotification(active.sbnKey, 150)
        }

        val isLandscape = resources.displayMetrics.widthPixels > resources.displayMetrics.heightPixels
        val headDim = if (isLandscape) dpToPx(40) else dpToPx(48)

        holder.topHeadsContainer?.removeAllViews()
        activeSessions.values.forEach { sess ->
            val isSelected = sess.key == activeChatKey
            val headFrame = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(headDim, headDim).apply {
                    rightMargin = dpToPx(8)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor(if (sess.isIncomingCall) "#00C853" else "#0084FF"))
                    setStroke(dpToPx(if (isSelected) 3 else 1), if (isSelected) Color.WHITE else Color.GRAY)
                }
                setOnClickListener {
                    if (sess.key == activeChatKey) {
                        collapseChat()
                    } else {
                        activeChatKey = sess.key
                        sess.unreadCount = 0
                        refreshExpandedUI(forceScrollBottom = true)
                    }
                }
            }

            if (sess.avatar != null) {
                val img = ImageView(this).apply {
                    layoutParams = FrameLayout.LayoutParams(headDim - dpToPx(4), headDim - dpToPx(4), Gravity.CENTER)
                    setImageBitmap(sess.avatar)
                }
                headFrame.addView(img)
            } else {
                val txt = TextView(this).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    gravity = Gravity.CENTER
                    text = sess.sender.take(1).uppercase()
                    textSize = 15f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                }
                headFrame.addView(txt)
            }

            holder.topHeadsContainer?.addView(headFrame)
        }

        if (active.avatar != null) {
            holder.headerAvatarImg?.setImageBitmap(active.avatar)
            holder.headerAvatarImg?.visibility = View.VISIBLE
            holder.headerInitialsTxt?.visibility = View.GONE
        } else {
            holder.headerAvatarImg?.visibility = View.GONE
            holder.headerInitialsTxt?.visibility = View.VISIBLE
            holder.headerInitialsTxt?.text = active.sender.take(1).uppercase()
        }

        val isMuted = SettingsManager.isSenderMuted(this, active.sender)
        holder.muteBtn?.text = if (isMuted) "🔔" else "🔕"

        holder.headerNameTxt?.text = active.sender
        holder.headerSubTxt?.text = when {
            active.isIncomingCall -> "📞 Gelen Arama..."
            active.replyAction == null -> "${active.appName} • Salt Okunur"
            else -> active.appName
        }

        val subColor = when (active.appName) {
            "Instagram" -> "#E1306C"
            "Telegram" -> "#24A1DE"
            else -> "#25D366"
        }
        holder.headerSubTxt?.setTextColor(Color.parseColor(subColor))
        holder.incomingCallBanner?.visibility = if (active.isIncomingCall) View.VISIBLE else View.GONE

        // Cevap aksiyonu olmayan sohbetlerde mesaj kutusunu kilitle (Sahte mesaj gitmesini önler)
        holder.updateInputEnabled(active.replyAction != null)
        holder.submitMessages(active.messages, forceScrollBottom)
    }

    private fun sendMessageNow() {
        val holder = chatWindowHolder ?: return
        val active = activeSessions[activeChatKey] ?: return
        val action = active.replyAction

        if (action == null) {
            Toast.makeText(
                this,
                "Kanka bu bildirimde hızlı yanıt aksiyonu yok! Sağ üstten '↗' ile uygulamayı açabilirsin.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val text = holder.replyEdit?.text?.toString()?.trim() ?: return
        if (text.isEmpty()) return

        holder.extraToolPanel?.visibility = View.GONE

        try {
            val intent = Intent()
            val bundle = Bundle()
            for (remoteInput in action.remoteInputs) {
                bundle.putCharSequence(remoteInput.resultKey, text)
            }
            RemoteInput.addResultsToIntent(action.remoteInputs, intent, bundle)

            val optionsBundle = if (Build.VERSION.SDK_INT >= 34) {
                ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    )
                    .toBundle()
            } else null

            action.actionIntent.send(this, 0, intent, null, null, null, optionsBundle)

            val now = System.currentTimeMillis()
            val sentMsg = ChatMessage(
                id = NotificationParser.buildMessageId(now, "Me", true, text),
                chatKey = active.key,
                senderName = null,
                text = text,
                isMe = true,
                time = NotificationParser.formatTime(now),
                timestamp = now,
                isDelivered = false
            )
            active.messages.add(sentMsg)
            repository.saveMessagesAsync(active.key, listOf(sentMsg))
            holder.replyEdit?.setText("")
            refreshExpandedUI(forceScrollBottom = true)

            scheduleCancelNotification(active.sbnKey, 450)
        } catch (_: PendingIntent.CanceledException) {
            Toast.makeText(
                this,
                "Bildirim süresi dolmuş, sağ üstten '↗' tuşuna basıp uygulamaya geç kanka.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun sharePhotoToActiveApp(uri: Uri) {
        val active = activeSessions[activeChatKey] ?: return
        try {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                setPackage(active.pkgName)
                // Doğrudan aktif sohbete göndermek için JID ve ShortcutId ekle
                if (!active.whatsappJid.isNullOrBlank()) {
                    putExtra("jid", active.whatsappJid)
                }
                if (Build.VERSION.SDK_INT >= 29 && !active.shortcutId.isNullOrBlank()) {
                    putExtra(Intent.EXTRA_SHORTCUT_ID, active.shortcutId)
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            collapseChat()
            startActivity(shareIntent)
        } catch (_: Exception) {
            openOriginalApp()
        }
    }

    private fun initiateCall(isVideo: Boolean) {
        val active = activeSessions[activeChatKey] ?: return

        if (active.isIncomingCall && active.callIntent != null) {
            if (sendPendingIntentSafely(active.callIntent)) {
                collapseChat()
                return
            }
        }

        if (active.pkgName.contains("whatsapp") &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        ) {
            val mimeType = if (isVideo) {
                "vnd.android.cursor.item/vnd.com.whatsapp.video.call"
            } else {
                "vnd.android.cursor.item/vnd.com.whatsapp.voip.call"
            }

            val dataId = findWhatsAppCallDataId(active.sender, mimeType)
            if (dataId != null) {
                try {
                    val callIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(Uri.parse("content://com.android.contacts/data/$dataId"), mimeType)
                        setPackage(active.pkgName)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    collapseChat()
                    startActivity(callIntent)
                    return
                } catch (_: Exception) {}
            }
        }

        if (active.pkgName.contains("instagram")) {
            pendingInstaCallVideo = isVideo
            pendingInstaCallTimestamp = System.currentTimeMillis()
            Toast.makeText(
                this,
                if (isVideo) "Instagram Görüntülü Arama başlatılıyor..." else "Instagram Sesli Arama başlatılıyor...",
                Toast.LENGTH_SHORT
            ).show()
        } else {
            Toast.makeText(
                this,
                if (isVideo) "Görüntülü arama için sohbete geçiliyor..." else "Sesli arama için sohbete geçiliyor...",
                Toast.LENGTH_SHORT
            ).show()
        }

        openOriginalApp()
    }

    private fun findWhatsAppCallDataId(senderName: String, mimeType: String): Long? {
        val clean = senderName.trim()
        if (clean.isEmpty()) return null

        // 1. Önce birebir (Exact) İsim Eşleşmesi Ara (Yanlış "Ali"yi aramamak için)
        try {
            contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data._ID),
                "${ContactsContract.Data.MIMETYPE} = ? AND LOWER(${ContactsContract.Data.DISPLAY_NAME}) = LOWER(?)",
                arrayOf(mimeType, clean),
                null
            )?.use { cursor ->
                if (cursor.count == 1 && cursor.moveToFirst()) {
                    return cursor.getLong(0)
                }
            }
        } catch (_: Exception) {}

        // 2. Emoji arındırılmış tam eşleşme kontrolü
        val stripped = clean.replace(Regex("[^\\p{L}\\p{N}\\s]"), "").trim()
        if (stripped.length >= 3 && stripped != clean) {
            try {
                contentResolver.query(
                    ContactsContract.Data.CONTENT_URI,
                    arrayOf(ContactsContract.Data._ID),
                    "${ContactsContract.Data.MIMETYPE} = ? AND LOWER(${ContactsContract.Data.DISPLAY_NAME}) = LOWER(?)",
                    arrayOf(mimeType, stripped),
                    null
                )?.use { cursor ->
                    if (cursor.count == 1 && cursor.moveToFirst()) {
                        return cursor.getLong(0)
                    }
                }
            } catch (_: Exception) {}
        }
        return null
    }

    private fun startVoiceDictation() {
        val edit = chatWindowHolder?.replyEdit ?: return
        if (!edit.isEnabled) return
        edit.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
        Toast.makeText(
            this,
            "Klavyenin üstündeki mikrofon 🎙️ simgesine basıp konuş kanka!",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun openOriginalApp() {
        val active = activeSessions[activeChatKey] ?: return
        collapseChat()

        if (active.isIncomingCall && sendPendingIntentSafely(active.callIntent)) {
            return
        }

        if (sendPendingIntentSafely(active.contentIntent)) {
            return
        }

        try {
            if (active.pkgName.contains("instagram")) {
                val dmIntent = Intent(Intent.ACTION_VIEW, Uri.parse("instagram://direct-inbox")).apply {
                    setPackage(active.pkgName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(dmIntent)
                return
            }
        } catch (_: Exception) {}

        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(active.pkgName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (launchIntent != null) {
                startActivity(launchIntent)
            }
        } catch (_: Exception) {
            Toast.makeText(this, "Uygulama açılamadı kanka.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendPendingIntentSafely(pi: PendingIntent?): Boolean {
        if (pi == null) return false
        return try {
            val optionsBundle = if (Build.VERSION.SDK_INT >= 34) {
                ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    )
                    .toBundle()
            } else null
            pi.send(this, 0, null, null, null, null, optionsBundle)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun playOrToggleAudio(uriStr: String) {
        try {
            if (mediaPlayer?.isPlaying == true) {
                stopAudio()
                Toast.makeText(this, "Ses durduruldu", Toast.LENGTH_SHORT).show()
                return
            }
            stopAudio()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(this@BubbleService, Uri.parse(uriStr))
                prepare()
                start()
                setOnCompletionListener { stopAudio() }
            }
            Toast.makeText(this, "🔊 Ses kaydı çalıyor...", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            openOriginalApp()
        }
    }

    private fun stopAudio() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
    }

    private fun scheduleCancelNotification(sbnKey: String?, delayMs: Long = 250) {
        if (sbnKey == null) return
        mainHandler.postDelayed({
            try {
                cancelNotification(sbnKey)
            } catch (_: Exception) {}
        }, delayMs)
    }

    private fun collapseChat() {
        if (!isExpanded) return
        isExpanded = false
        stopAudio()

        try {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(chatWindowHolder?.replyEdit?.windowToken, 0)
        } catch (_: Exception) {}

        chatWindowHolder?.rootOverlay?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        chatWindowHolder = null

        bubbleView?.visibility = View.VISIBLE
        activeSessions[activeChatKey]?.let { refreshBubbleAppearance(it) }
    }

    private fun destroyAllOverlays() {
        isExpanded = false
        stopAudio()
        chatWindowHolder?.rootOverlay?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        chatWindowHolder = null

        bubbleView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        bubbleView = null

        dismissBarView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        dismissBarView = null
        activeChatKey = null
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }
}