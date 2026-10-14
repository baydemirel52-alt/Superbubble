package com.selim.superbubble

import android.animation.ValueAnimator
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import android.util.TypedValue
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class BubbleService : NotificationListenerService() {

    companion object {
        private val TARGET_PACKAGES = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "com.instagram.android"
        )

        private var instance: BubbleService? = null

        fun spawnTestBubble(context: Context) {
            val srv = instance
            if (srv == null) {
                Toast.makeText(
                    context,
                    "Kanka önce 2. İzin olan Bildirim Yakalama iznini açmalısın!",
                    Toast.LENGTH_LONG
                ).show()
                return
            }
            srv.mainHandler.post {
                // Test için örnek bir resimli ve normal mesaj oluştur
                val sampleBmp = Bitmap.createBitmap(300, 180, Bitmap.Config.ARGB_8888).apply {
                    val c = Canvas(this)
                    c.drawColor(Color.parseColor("#1E3A8A"))
                    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.WHITE
                        textSize = 28f
                        typeface = Typeface.DEFAULT_BOLD
                    }
                    c.drawText("📸 Örnek Fotoğraf", 35f, 100f, p)
                }

                srv.onNewMessage(
                    chatKey = "test_messenger",
                    sbnKey = null,
                    sender = "Test Sohbeti",
                    message = "Kanka geçmiş kaydı, fotoğraf gösterme ve bildirim silme eklendi! Fotoğrafa tıklayıp büyütebilirsin.",
                    appName = "WhatsApp",
                    avatar = null,
                    photoBitmap = sampleBmp,
                    audioUri = null,
                    replyAction = null,
                    contentIntent = null
                )
                Toast.makeText(context, "Balon hazır kanka!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    data class ChatMessage(
        val text: String,
        val isMe: Boolean,
        val time: String,
        val imageBase64: String? = null,
        val audioUri: String? = null
    )

    data class ChatSession(
        val key: String,
        var sbnKey: String? = null,
        var sender: String,
        val appName: String,
        val messages: MutableList<ChatMessage> = mutableListOf(),
        var avatar: Bitmap? = null,
        var replyAction: Notification.Action? = null,
        var contentIntent: PendingIntent? = null,
        var unreadCount: Int = 0
    )

    private lateinit var windowManager: WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val activeSessions = LinkedHashMap<String, ChatSession>()
    private var activeChatKey: String? = null

    // Kendi gönderdiğimiz mesajların bildirim yansımasını engellemek için kayıt listesi
    private val recentSentTexts = LinkedHashMap<String, Long>()
    private var mediaPlayer: MediaPlayer? = null

    // 1. Küçük Yüzen Balon
    private var bubbleView: FrameLayout? = null
    private var bubbleAvatarImg: ImageView? = null
    private var bubbleInitialsTxt: TextView? = null
    private var bubbleAppBadge: TextView? = null
    private var bubbleUnreadBadge: TextView? = null
    private lateinit var bubbleParams: WindowManager.LayoutParams

    // 2. Silme (X) Alanı
    private var dismissBarView: FrameLayout? = null
    private lateinit var dismissParams: WindowManager.LayoutParams

    // 3. Açık Messenger Sohbet Penceresi
    private var expandedOverlayView: FrameLayout? = null
    private var topHeadsContainer: LinearLayout? = null
    private var headerAvatarImg: ImageView? = null
    private var headerInitialsTxt: TextView? = null
    private var headerNameTxt: TextView? = null
    private var headerSubTxt: TextView? = null
    private var messagesContainer: LinearLayout? = null
    private var messagesScroll: ScrollView? = null
    private var replyEdit: EditText? = null
    private var fullScreenImageOverlay: FrameLayout? = null
    private lateinit var expandedParams: WindowManager.LayoutParams

    private var isExpanded = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAudio()
        destroyAllOverlays()
        instance = null
    }

    @Suppress("DEPRECATION")
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val pkg = sbn.packageName
        if (!TARGET_PACKAGES.contains(pkg)) return

        val notif = sbn.notification
        if ((notif.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val extras = notif.extras

        // Kendi gönderdiğimiz yanıtın bildirim güncellemesi mi? (Çift balon hatasını %100 önler)
        val remoteHistory = extras.getCharSequenceArray(Notification.EXTRA_REMOTE_INPUT_HISTORY)
        if (!remoteHistory.isNullOrEmpty()) {
            scheduleCancelNotification(sbn.key, 500)
            return
        }

        val selfName = extras.getCharSequence(Notification.EXTRA_SELF_DISPLAY_NAME)?.toString()?.trim()
        val convTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim()
        val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: return

        // "(2 mesaj)" gibi ekleri temizle ki aynı kişi için ayrı balon açılmasın
        val cleanTitle = (convTitle ?: rawTitle)
            .replace(Regex("\\s*\\(\\d+\\s*mesaj\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\(\\d+\\s*new messages\\)", RegexOption.IGNORE_CASE), "")
            .substringBefore(":")
            .trim()

        var text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()
            ?: return

        // Sistem mesajlarını ve kendi adımızı taşıyan yansımaları filtrele
        if (text.contains("Yeni mesajlar kontrol ediliyor", ignoreCase = true) ||
            text.contains("WhatsApp Web", ignoreCase = true) ||
            cleanTitle.equals("WhatsApp", ignoreCase = true) ||
            cleanTitle.equals("Instagram", ignoreCase = true) ||
            cleanTitle.equals("Siz", ignoreCase = true) ||
            cleanTitle.equals("Sen", ignoreCase = true) ||
            cleanTitle.equals("You", ignoreCase = true) ||
            (selfName != null && cleanTitle.equals(selfName, ignoreCase = true))
        ) return

        // MessagingStyle içindeki son mesajı ve medyayı kontrol et
        var photoBitmap: Bitmap? = extras.get(Notification.EXTRA_PICTURE) as? Bitmap
        var audioUriStr: String? = null

        val msgBundles = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (!msgBundles.isNullOrEmpty()) {
            val lastBundle = msgBundles.last() as? Bundle
            if (lastBundle != null) {
                val senderPerson = lastBundle.getCharSequence("sender")?.toString()?.trim()
                val msgText = lastBundle.getCharSequence("text")?.toString()?.trim()
                if (!msgText.isNullOrEmpty()) text = msgText

                // Eğer son mesajı gönderen biz isek (balondan yanıtladıysak) yoksay ve üst bildirimi sil
                if (senderPerson != null && (
                            senderPerson.equals("Siz", true) ||
                            senderPerson.equals("Sen", true) ||
                            senderPerson.equals("You", true) ||
                            (selfName != null && senderPerson.equals(selfName, true))
                        )
                ) {
                    scheduleCancelNotification(sbn.key, 500)
                    return
                }

                // Bildirim içindeki fotoğraf veya ses URI'sini yakala
                val mimeType = lastBundle.getString("type") ?: ""
                val dataUri = lastBundle.getParcelable<Parcelable>("uri") as? Uri
                if (dataUri != null) {
                    if (mimeType.startsWith("image/") && photoBitmap == null) {
                        photoBitmap = loadBitmapFromUri(dataUri)
                    } else if (mimeType.startsWith("audio/")) {
                        audioUriStr = dataUri.toString()
                    }
                }
            }
        }

        if (text.startsWith("Siz:") || text.startsWith("Sen:") || text.startsWith("You:")) {
            scheduleCancelNotification(sbn.key, 500)
            return
        }

        // Son 15 saniye içinde bizim balondan gönderdiğimiz metinle birebir aynıysa (yansıma ise) yoksay
        val now = System.currentTimeMillis()
        recentSentTexts.entries.removeIf { now - it.value > 15000 }
        if (recentSentTexts.containsKey(text)) {
            scheduleCancelNotification(sbn.key, 500)
            return
        }

        val replyAction = findReplyAction(notif) ?: return
        val avatar = extractAvatar(notif)
        val appName = if (pkg.contains("instagram")) "Instagram" else "WhatsApp"
        val normalizedKey = "${pkg}_${cleanTitle.lowercase(Locale.getDefault())}"

        mainHandler.post {
            onNewMessage(
                chatKey = normalizedKey,
                sbnKey = sbn.key,
                sender = cleanTitle,
                message = text,
                appName = appName,
                avatar = avatar,
                photoBitmap = photoBitmap,
                audioUri = audioUriStr,
                replyAction = replyAction,
                contentIntent = notif.contentIntent
            )
        }
    }

    private fun scheduleCancelNotification(sbnKey: String?, delayMs: Long = 300) {
        if (sbnKey == null) return
        mainHandler.postDelayed({
            try {
                cancelNotification(sbnKey)
            } catch (_: Exception) {}
        }, delayMs)
    }

    private fun loadBitmapFromUri(uri: Uri): Bitmap? {
        return try {
            contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun bitmapToBase64(bmp: Bitmap?): String? {
        if (bmp == null) return null
        return try {
            val maxDim = 480
            val ratio = min(maxDim.toFloat() / bmp.width, maxDim.toFloat() / bmp.height)
            val scaled = if (ratio < 1f) {
                Bitmap.createScaledBitmap(
                    bmp,
                    max(1, (bmp.width * ratio).toInt()),
                    max(1, (bmp.height * ratio).toInt()),
                    true
                )
            } else bmp

            val baos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 75, baos)
            Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

    private fun base64ToBitmap(b64: String?): Bitmap? {
        if (b64.isNullOrEmpty()) return null
        return try {
            val bytes = Base64.decode(b64, Base64.NO_WRAP)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) {
            null
        }
    }

    // ==================== KALICI SOHBET GEÇMİŞİ (SHARED PREFERENCES) ====================
    private fun loadSavedMessages(chatKey: String): MutableList<ChatMessage> {
        val list = mutableListOf<ChatMessage>()
        try {
            val prefs = getSharedPreferences("superbubble_history_db", Context.MODE_PRIVATE)
            val rawJson = prefs.getString(chatKey, null) ?: return list
            val arr = JSONArray(rawJson)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    ChatMessage(
                        text = obj.optString("text", ""),
                        isMe = obj.optBoolean("isMe", false),
                        time = obj.optString("time", ""),
                        imageBase64 = obj.optString("img", "").ifEmpty { null },
                        audioUri = obj.optString("aud", "").ifEmpty { null }
                    )
                )
            }
        } catch (_: Exception) {}
        return list
    }

    private fun saveMessagesToDisk(chatKey: String, messages: List<ChatMessage>) {
        try {
            val prefs = getSharedPreferences("superbubble_history_db", Context.MODE_PRIVATE)
            val arr = JSONArray()
            // Son 45 mesajı kalıcı olarak sakla
            val slice = if (messages.size > 45) messages.takeLast(45) else messages
            for (m in slice) {
                val obj = JSONObject()
                obj.put("text", m.text)
                obj.put("isMe", m.isMe)
                obj.put("time", m.time)
                if (m.imageBase64 != null) obj.put("img", m.imageBase64)
                if (m.audioUri != null) obj.put("aud", m.audioUri)
                arr.put(obj)
            }
            prefs.edit().putString(chatKey, arr.toString()).apply()
        } catch (_: Exception) {}
    }

    private fun clearSavedHistory(chatKey: String) {
        try {
            val prefs = getSharedPreferences("superbubble_history_db", Context.MODE_PRIVATE)
            prefs.edit().remove(chatKey).apply()
        } catch (_: Exception) {}
    }

    private fun findReplyAction(notif: Notification): Notification.Action? {
        notif.actions?.forEach { action ->
            if (!action.remoteInputs.isNullOrEmpty()) return action
        }
        return null
    }

    private fun extractAvatar(notif: Notification): Bitmap? {
        return try {
            val icon = notif.getLargeIcon() ?: return null
            val drawable = icon.loadDrawable(this) ?: return null
            val bmp = Bitmap.createBitmap(140, 140, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            getCircularBitmap(bmp)
        } catch (_: Exception) {
            null
        }
    }

    private fun getCircularBitmap(bitmap: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = Rect(0, 0, bitmap.width, bitmap.height)
        canvas.drawCircle(bitmap.width / 2f, bitmap.height / 2f, bitmap.width / 2f, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(bitmap, rect, rect, paint)
        return output
    }

    private fun nowTime(): String {
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
    }

    private fun onNewMessage(
        chatKey: String,
        sbnKey: String?,
        sender: String,
        message: String,
        appName: String,
        avatar: Bitmap?,
        photoBitmap: Bitmap?,
        audioUri: String?,
        replyAction: Notification.Action?,
        contentIntent: PendingIntent?
    ) {
        val session = activeSessions.getOrPut(chatKey) {
            // Pencere daha önce kapatılmış olsa bile eski geçmişi diskten geri yükle!
            val history = loadSavedMessages(chatKey)
            ChatSession(
                key = chatKey,
                sender = sender,
                appName = appName,
                messages = history
            )
        }

        session.sender = sender
        if (sbnKey != null) session.sbnKey = sbnKey
        if (avatar != null) session.avatar = avatar
        if (replyAction != null) session.replyAction = replyAction
        if (contentIntent != null) session.contentIntent = contentIntent

        val imgB64 = bitmapToBase64(photoBitmap)
        val lastMsg = session.messages.lastOrNull()

        if (lastMsg == null || lastMsg.isMe || lastMsg.text != message || (imgB64 != null && lastMsg.imageBase64 == null)) {
            session.messages.add(
                ChatMessage(
                    text = message,
                    isMe = false,
                    time = nowTime(),
                    imageBase64 = imgB64,
                    audioUri = audioUri
                )
            )
            saveMessagesToDisk(chatKey, session.messages)

            if (!isExpanded || activeChatKey != chatKey) {
                session.unreadCount++
            } else {
                // Sohbet zaten açıksa üstteki bildirimi anında temizle
                scheduleCancelNotification(session.sbnKey, 200)
            }
        }

        activeChatKey = activeChatKey ?: chatKey

        ensureBubbleCreated()
        refreshBubbleAppearance(session)

        if (isExpanded) {
            refreshExpandedUI()
        }
    }

    // ==================== 1. YÜZEN BALON ====================
    private fun ensureBubbleCreated() {
        if (bubbleView != null) return
        val metrics = resources.displayMetrics

        bubbleParams = WindowManager.LayoutParams(
            dpToPx(68),
            dpToPx(68),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = metrics.widthPixels - dpToPx(76)
            y = metrics.heightPixels / 4
        }

        bubbleView = FrameLayout(this)

        val avatarContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(dpToPx(60), dpToPx(60), Gravity.CENTER)
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
            layoutParams = FrameLayout.LayoutParams(dpToPx(56), dpToPx(56), Gravity.CENTER)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }

        avatarContainer.addView(bubbleInitialsTxt)
        avatarContainer.addView(bubbleAvatarImg)

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

        bubbleView?.addView(avatarContainer)
        bubbleView?.addView(bubbleAppBadge)
        bubbleView?.addView(bubbleUnreadBadge)

        createDismissBar()
        setupBubbleTouchPhysics()

        windowManager.addView(bubbleView, bubbleParams)
    }

    private fun createDismissBar() {
        if (dismissBarView != null) return
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
            text = "✕  Balonu Gizle (Geçmiş Silinmez)"
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
        windowManager.addView(dismissBarView, dismissParams)
    }

    private fun setupBubbleTouchPhysics() {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var isDragging = false

        bubbleView?.setOnTouchListener { _, event ->
            val metrics = resources.displayMetrics
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
                        bubbleParams.x = max(0, min(metrics.widthPixels - dpToPx(68), startX + dx))
                        bubbleParams.y = max(dpToPx(36), min(metrics.heightPixels - dpToPx(90), startY + dy))
                        windowManager.updateViewLayout(bubbleView, bubbleParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    dismissBarView?.visibility = View.GONE
                    if (!isDragging) {
                        openExpandedChat()
                    } else {
                        if (event.rawY > metrics.heightPixels * 0.82f) {
                            // Sadece ekrandaki balonu kapat, diskteki geçmişi SİLME!
                            activeSessions.clear()
                            destroyAllOverlays()
                        } else {
                            val targetX = if (bubbleParams.x + dpToPx(34) < metrics.widthPixels / 2) {
                                dpToPx(8)
                            } else {
                                metrics.widthPixels - dpToPx(76)
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

        val isInsta = session.appName == "Instagram"
        bubbleAppBadge?.text = if (isInsta) "IG" else "W"
        bubbleAppBadge?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(if (isInsta) "#E1306C" else "#25D366"))
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

    // ==================== 2. AÇIK MESSENGER SOHBET PENCERESİ ====================
    private fun openExpandedChat() {
        if (isExpanded || activeSessions.isEmpty()) return
        isExpanded = true

        bubbleView?.visibility = View.GONE

        val metrics = resources.displayMetrics
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

        expandedOverlayView = object : FrameLayout(this) {
            override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
                if (event?.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    if (fullScreenImageOverlay?.visibility == View.VISIBLE) {
                        fullScreenImageOverlay?.visibility = View.GONE
                        return true
                    }
                    collapseChat()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            setOnClickListener { collapseChat() }
        }

        val mainColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            ).apply {
                topMargin = dpToPx(36)
                leftMargin = dpToPx(12)
                rightMargin = dpToPx(12)
            }
            isClickable = true
        }

        // --- ÜST BÖLÜM ---
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(62)
            ).apply { bottomMargin = dpToPx(8) }
        }

        topHeadsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val minimizePill = TextView(this).apply {
            text = "Küçült ⬇"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(dpToPx(14), dpToPx(8), dpToPx(14), dpToPx(8))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#3A3B3C"))
                cornerRadius = dpToPx(18).toFloat()
            }
            setOnClickListener { collapseChat() }
        }

        val closeAllPill = TextView(this).apply {
            text = "✕"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dpToPx(38), dpToPx(38)).apply {
                leftMargin = dpToPx(8)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#FF3B30"))
            }
            setOnClickListener {
                // Sadece ekrandaki pencereyi kapatır, geçmişi silmez!
                activeSessions.clear()
                destroyAllOverlays()
            }
        }

        topBar.addView(topHeadsContainer)
        topBar.addView(minimizePill)
        topBar.addView(closeAllPill)

        // --- ORTA BÖLÜM: SOHBET KARTI ---
        val cardHeight = (metrics.heightPixels * 0.46f).toInt()
        val chatCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                cardHeight
            )
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#18191A"))
                cornerRadius = dpToPx(22).toFloat()
                setStroke(dpToPx(1), Color.parseColor("#3E4042"))
            }
            elevation = dpToPx(12).toFloat()
        }

        val cardHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(14), dpToPx(10), dpToPx(14), dpToPx(10))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#242526"))
                cornerRadii = floatArrayOf(
                    dpToPx(22).toFloat(), dpToPx(22).toFloat(),
                    dpToPx(22).toFloat(), dpToPx(22).toFloat(),
                    0f, 0f, 0f, 0f
                )
            }
        }

        val headerAvatarBox = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(40), dpToPx(40))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#0084FF"))
            }
        }

        headerInitialsTxt = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            gravity = Gravity.CENTER
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }

        headerAvatarImg = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(dpToPx(40), dpToPx(40))
            scaleType = ImageView.ScaleType.CENTER_CROP
        }

        headerAvatarBox.addView(headerInitialsTxt)
        headerAvatarBox.addView(headerAvatarImg)

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dpToPx(10)
            }
        }

        headerNameTxt = TextView(this).apply {
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            maxLines = 1
        }

        headerSubTxt = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#00A884"))
        }

        titleCol.addView(headerNameTxt)
        titleCol.addView(headerSubTxt)

        // Geçmişi Temizle Butonu
        val clearHistoryBtn = TextView(this).apply {
            text = "🗑️"
            textSize = 14f
            setPadding(dpToPx(8), dpToPx(6), dpToPx(8), dpToPx(6))
            setOnClickListener {
                val active = activeSessions[activeChatKey] ?: return@setOnClickListener
                active.messages.clear()
                clearSavedHistory(active.key)
                refreshExpandedUI()
                Toast.makeText(this@BubbleService, "Bu sohbetin geçmişi temizlendi", Toast.LENGTH_SHORT).show()
            }
        }

        val openAppBtn = TextView(this).apply {
            text = "Uygulama ↗"
            textSize = 11.5f
            setTextColor(Color.parseColor("#4599FF"))
            setPadding(dpToPx(10), dpToPx(6), dpToPx(10), dpToPx(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#263951"))
                cornerRadius = dpToPx(12).toFloat()
            }
            setOnClickListener {
                openOriginalApp()
            }
        }

        cardHeader.addView(headerAvatarBox)
        cardHeader.addView(titleCol)
        cardHeader.addView(clearHistoryBtn)
        cardHeader.addView(openAppBtn)

        messagesScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8))
        }

        messagesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }
        messagesScroll?.addView(messagesContainer)

        val inputBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(10))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#242526"))
                cornerRadii = floatArrayOf(
                    0f, 0f, 0f, 0f,
                    dpToPx(22).toFloat(), dpToPx(22).toFloat(),
                    dpToPx(22).toFloat(), dpToPx(22).toFloat()
                )
            }
        }

        replyEdit = EditText(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(44), 1f)
            hint = "Mesaj yaz..."
            setHintTextColor(Color.parseColor("#8A8D91"))
            setTextColor(Color.WHITE)
            textSize = 14.5f
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEND
            setPadding(dpToPx(16), 0, dpToPx(16), 0)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#3A3B3C"))
                cornerRadius = dpToPx(22).toFloat()
            }
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    sendMessageNow()
                    true
                } else false
            }
        }

        val sendCircleBtn = TextView(this).apply {
            text = "➤"
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dpToPx(44), dpToPx(44)).apply {
                leftMargin = dpToPx(8)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#0084FF"))
            }
            setOnClickListener { sendMessageNow() }
        }

        inputBar.addView(replyEdit)
        inputBar.addView(sendCircleBtn)

        chatCard.addView(cardHeader)
        chatCard.addView(messagesScroll)
        chatCard.addView(inputBar)

        mainColumn.addView(topBar)
        mainColumn.addView(chatCard)

        // Fotoğrafa tıklanınca tam ekran büyüten katman
        fullScreenImageOverlay = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#EE000000"))
            visibility = View.GONE
            setOnClickListener { visibility = View.GONE }
        }

        expandedOverlayView?.addView(mainColumn)
        expandedOverlayView?.addView(fullScreenImageOverlay)

        windowManager.addView(expandedOverlayView, expandedParams)

        refreshExpandedUI()
    }

    private fun showFullScreenPhoto(bmp: Bitmap) {
        fullScreenImageOverlay?.removeAllViews()
        val img = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            ).apply {
                val m = dpToPx(20)
                setMargins(m, m, m, m)
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(bmp)
        }
        val closeHint = TextView(this).apply {
            text = "✕ Kapatmak için dokun"
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply { topMargin = dpToPx(45) }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#AA333333"))
                cornerRadius = dpToPx(20).toFloat()
            }
        }
        fullScreenImageOverlay?.addView(img)
        fullScreenImageOverlay?.addView(closeHint)
        fullScreenImageOverlay?.visibility = View.VISIBLE
    }

    private fun openOriginalApp() {
        val active = activeSessions[activeChatKey]
        try {
            if (active?.contentIntent != null) {
                active.contentIntent?.send()
                collapseChat()
            } else {
                Toast.makeText(this, "Test balonudur kanka.", Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) {
            Toast.makeText(this, "Uygulama açılamadı.", Toast.LENGTH_SHORT).show()
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

    private fun isVoiceMessageText(txt: String): Boolean {
        val lower = txt.lowercase(Locale.getDefault())
        return lower.contains("🎤") || lower.contains("sesli mesaj") || lower.contains("voice message") || lower.contains("ses gönderdi")
    }

    private fun isReelOrVideoText(txt: String): Boolean {
        val lower = txt.lowercase(Locale.getDefault())
        return lower.contains("reel") || lower.contains("비디오") || lower.contains("video gönderdi") ||
                lower.contains("instagram.com/reel") || lower.contains("bir gönderi paylaştı")
    }

    private fun refreshExpandedUI() {
        if (!isExpanded || expandedOverlayView == null) return

        val active = activeSessions[activeChatKey] ?: activeSessions.values.firstOrNull() ?: return
        activeChatKey = active.key
        active.unreadCount = 0

        // Balonu açıp okuduğumuz an üstteki sistem bildirimini ve '1' simgesini otomatik sil!
        scheduleCancelNotification(active.sbnKey, 150)

        // Üstteki yuvarlak sohbet kafaları
        topHeadsContainer?.removeAllViews()
        activeSessions.values.forEach { sess ->
            val isSelected = sess.key == activeChatKey
            val headFrame = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(52), dpToPx(52)).apply {
                    rightMargin = dpToPx(8)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#0084FF"))
                    setStroke(dpToPx(if (isSelected) 3 else 1), if (isSelected) Color.WHITE else Color.GRAY)
                }
                setOnClickListener {
                    if (sess.key == activeChatKey) {
                        collapseChat()
                    } else {
                        activeChatKey = sess.key
                        sess.unreadCount = 0
                        refreshExpandedUI()
                    }
                }
            }

            if (sess.avatar != null) {
                val img = ImageView(this).apply {
                    layoutParams = FrameLayout.LayoutParams(dpToPx(48), dpToPx(48), Gravity.CENTER)
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
                    textSize = 17f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                }
                headFrame.addView(txt)
            }

            topHeadsContainer?.addView(headFrame)
        }

        if (active.avatar != null) {
            headerAvatarImg?.setImageBitmap(active.avatar)
            headerAvatarImg?.visibility = View.VISIBLE
            headerInitialsTxt?.visibility = View.GONE
        } else {
            headerAvatarImg?.visibility = View.GONE
            headerInitialsTxt?.visibility = View.VISIBLE
            headerInitialsTxt?.text = active.sender.take(1).uppercase()
        }

        headerNameTxt?.text = active.sender
        headerSubTxt?.text = "${active.appName} • Geçmiş Kayıtlı"
        headerSubTxt?.setTextColor(
            Color.parseColor(if (active.appName == "Instagram") "#E1306C" else "#25D366")
        )

        // Mesajları Çiz
        messagesContainer?.removeAllViews()
        active.messages.forEach { msg ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dpToPx(6) }
                gravity = if (msg.isMe) Gravity.END else Gravity.START
            }

            val bubbleBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(if (msg.isMe) "#0084FF" else "#303030"))
                    cornerRadius = dpToPx(18).toFloat()
                }
            }

            // 1. Eğer Fotoğraf Varsa Balonun İçinde Göster
            val bmp = base64ToBitmap(msg.imageBase64)
            if (bmp != null) {
                val photoView = ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dpToPx(200), dpToPx(140)).apply {
                        bottomMargin = dpToPx(6)
                    }
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageBitmap(bmp)
                    setOnClickListener { showFullScreenPhoto(bmp) }
                }
                bubbleBox.addView(photoView)
            }

            // 2. Mesaj Metni
            val msgTxt = TextView(this).apply {
                text = msg.text
                textSize = 14f
                setTextColor(Color.WHITE)
                maxWidth = dpToPx(235)
            }
            bubbleBox.addView(msgTxt)

            // 3. Ses Kaydı veya Instagram Reels ise Özel Oynatma Butonu Ekle
            if (msg.audioUri != null || isVoiceMessageText(msg.text)) {
                val audioBtn = TextView(this).apply {
                    text = if (msg.audioUri != null) "▶️ Ses Kaydını Dinle" else "🎤 Ses Kaydını Aç ↗"
                    textSize = 12.5f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dpToPx(6) }
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#00A884"))
                        cornerRadius = dpToPx(14).toFloat()
                    }
                    setOnClickListener {
                        if (msg.audioUri != null) playOrToggleAudio(msg.audioUri) else openOriginalApp()
                    }
                }
                bubbleBox.addView(audioBtn)
            } else if (isReelOrVideoText(msg.text)) {
                val reelBtn = TextView(this).apply {
                    text = "🎬 Reels / Videoyu İzle ↗"
                    textSize = 12.5f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dpToPx(6) }
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#E1306C"))
                        cornerRadius = dpToPx(14).toFloat()
                    }
                    setOnClickListener { openOriginalApp() }
                }
                bubbleBox.addView(reelBtn)
            }

            val timeTxt = TextView(this).apply {
                text = msg.time
                textSize = 10f
                setTextColor(Color.parseColor("#8A8D91"))
                setPadding(dpToPx(6), dpToPx(2), dpToPx(6), 0)
            }

            row.addView(bubbleBox)
            row.addView(timeTxt)
            messagesContainer?.addView(row)
        }

        messagesScroll?.post { messagesScroll?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun sendMessageNow() {
        val text = replyEdit?.text?.toString()?.trim() ?: return
        if (text.isEmpty()) return

        val active = activeSessions[activeChatKey] ?: return
        val action = active.replyAction

        // Kendi gönderdiğimiz mesajı kaydet ki bildirim yansıması çift balon açmasın
        recentSentTexts[text] = System.currentTimeMillis()

        if (action == null) {
            active.messages.add(ChatMessage(text = text, isMe = true, time = nowTime()))
            saveMessagesToDisk(active.key, active.messages)
            replyEdit?.setText("")
            refreshExpandedUI()
            return
        }

        try {
            val intent = Intent()
            val bundle = Bundle()
            for (remoteInput in action.remoteInputs) {
                bundle.putCharSequence(remoteInput.resultKey, text)
            }
            RemoteInput.addResultsToIntent(action.remoteInputs, intent, bundle)
            action.actionIntent.send(this, 0, intent)

            active.messages.add(ChatMessage(text = text, isMe = true, time = nowTime()))
            saveMessagesToDisk(active.key, active.messages)
            replyEdit?.setText("")
            refreshExpandedUI()

            // Cevap gönderildikten hemen sonra üstteki sistem bildirimini otomatik kapat
            scheduleCancelNotification(active.sbnKey, 450)
        } catch (_: PendingIntent.CanceledException) {
            Toast.makeText(
                this,
                "Bildirim süresi dolmuş, sağ üstten 'Uygulama ↗' tuşuna basabilirsin kanka.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun collapseChat() {
        if (!isExpanded) return
        isExpanded = false
        stopAudio()

        try {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(replyEdit?.windowToken, 0)
        } catch (_: Exception) {}

        expandedOverlayView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        expandedOverlayView = null
        fullScreenImageOverlay = null

        bubbleView?.visibility = View.VISIBLE
        activeSessions[activeChatKey]?.let { refreshBubbleAppearance(it) }
    }

    private fun destroyAllOverlays() {
        isExpanded = false
        stopAudio()
        expandedOverlayView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        expandedOverlayView = null
        fullScreenImageOverlay = null

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
