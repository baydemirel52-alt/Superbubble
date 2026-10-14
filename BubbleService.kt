package com.selim.superbubble

import android.animation.ValueAnimator
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.TypedValue
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
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
                srv.onNewMessage(
                    chatKey = "test_messenger",
                    sender = "Test Sohbeti",
                    message = "Kanka yeni Messenger tasarımı hazır! Buradan mesaj yazıp ➤ tuşuyla gönderebilirsin.",
                    appName = "WhatsApp",
                    avatar = null,
                    replyAction = null,
                    contentIntent = null
                )
                Toast.makeText(context, "Balon ekranın sağ kenarında açıldı!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    data class ChatMessage(
        val text: String,
        val isMe: Boolean,
        val time: String
    )

    data class ChatSession(
        val key: String,
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
    private val sessions = LinkedHashMap<String, ChatSession>()
    private var activeChatKey: String? = null

    // 1. Küçük Yüzen Balon Penceresi
    private var bubbleView: FrameLayout? = null
    private var bubbleAvatarImg: ImageView? = null
    private var bubbleInitialsTxt: TextView? = null
    private var bubbleAppBadge: TextView? = null
    private var bubbleUnreadBadge: TextView? = null
    private lateinit var bubbleParams: WindowManager.LayoutParams

    // 2. Sürüklerken Altta Çıkan Silme (X) Alanı
    private var dismissBarView: FrameLayout? = null
    private lateinit var dismissParams: WindowManager.LayoutParams

    // 3. Açık Messenger Sohbet Penceresi (Ayrı Pencere - Asla Ekran Dışına Taşmaz)
    private var expandedOverlayView: FrameLayout? = null
    private var topHeadsContainer: LinearLayout? = null
    private var headerAvatarImg: ImageView? = null
    private var headerInitialsTxt: TextView? = null
    private var headerNameTxt: TextView? = null
    private var headerSubTxt: TextView? = null
    private var messagesContainer: LinearLayout? = null
    private var messagesScroll: ScrollView? = null
    private var replyEdit: EditText? = null
    private lateinit var expandedParams: WindowManager.LayoutParams

    private var isExpanded = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onDestroy() {
        super.onDestroy()
        destroyAllOverlays()
        instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val pkg = sbn.packageName
        if (!TARGET_PACKAGES.contains(pkg)) return

        val notif = sbn.notification
        if ((notif.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val extras = notif.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: return
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: return

        if (text.contains("Yeni mesajlar kontrol ediliyor", ignoreCase = true) ||
            text.contains("WhatsApp Web", ignoreCase = true) ||
            title.equals("WhatsApp", ignoreCase = true) ||
            title.equals("Instagram", ignoreCase = true)
        ) return

        // Kendi gönderdiğimiz mesaj bildirimlerini tekrar karşıdan gelmiş gibi ekleme
        if (text.startsWith("Siz:") || text.startsWith("Sen:") || text.startsWith("You:")) return

        val replyAction = findReplyAction(notif) ?: return
        val avatar = extractAvatar(notif)
        val appName = if (pkg.contains("instagram")) "Instagram" else "WhatsApp"
        val chatKey = "${pkg}_$title"

        mainHandler.post {
            onNewMessage(chatKey, title, text, appName, avatar, replyAction, notif.contentIntent)
        }
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
        sender: String,
        message: String,
        appName: String,
        avatar: Bitmap?,
        replyAction: Notification.Action?,
        contentIntent: PendingIntent?
    ) {
        val session = sessions.getOrPut(chatKey) {
            ChatSession(key = chatKey, sender = sender, appName = appName)
        }

        val lastMsg = session.messages.lastOrNull()
        if (lastMsg == null || lastMsg.isMe || lastMsg.text != message) {
            session.messages.add(ChatMessage(text = message, isMe = false, time = nowTime()))
            if (!isExpanded || activeChatKey != chatKey) {
                session.unreadCount++
            }
        }

        if (avatar != null) session.avatar = avatar
        if (replyAction != null) session.replyAction = replyAction
        if (contentIntent != null) session.contentIntent = contentIntent

        activeChatKey = activeChatKey ?: chatKey

        ensureBubbleCreated()
        refreshBubbleAppearance(session)

        if (isExpanded) {
            refreshExpandedUI()
        }
    }

    // ==================== 1. YÜZEN BALON OLUŞTURMA ====================
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

        // Yuvarlak profil çerçevesi
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

        // Sağ alt Uygulama Rozeti (W veya IG)
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

        // Sağ üst Okunmamış Mesaj Sayısı Rozeti
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
            text = "✕  Balonu Kapatmak İçin Buraya Bırak"
            textSize = 14f
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
                            sessions.clear()
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

        val totalUnread = sessions.values.sumOf { it.unreadCount }
        if (totalUnread > 0 && !isExpanded) {
            bubbleUnreadBadge?.text = totalUnread.toString()
            bubbleUnreadBadge?.visibility = View.VISIBLE
        } else {
            bubbleUnreadBadge?.visibility = View.GONE
        }
    }

    // ==================== 2. AÇIK MESSENGER SOHBET PENCERESİ ====================
    private fun openExpandedChat() {
        if (isExpanded || sessions.isEmpty()) return
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

        // Geri tuşunu (Back) yakalayan özel kapsayıcı
        expandedOverlayView = object : FrameLayout(this) {
            override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
                if (event?.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    collapseChat()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            // Boşluğa tıklanırsa sohbeti küçült
            setOnClickListener { collapseChat() }
        }

        val mainColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            ).apply {
                topMargin = dpToPx(38)
                leftMargin = dpToPx(12)
                rightMargin = dpToPx(12)
            }
            // Karta tıklanınca arka plan tıklamasını tetiklemesin
            isClickable = true
        }

        // --- ÜST BÖLÜM: MESSENGER BALON DİZİLİMİ VE KÜÇÜLT/KAPAT BUTONLARI ---
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(64)
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
                sessions.clear()
                destroyAllOverlays()
            }
        }

        topBar.addView(topHeadsContainer)
        topBar.addView(minimizePill)
        topBar.addView(closeAllPill)

        // --- ORTA BÖLÜM: MESSENGER SOHBET KARTI (Üst Yarıda Durur, Klavye Kapatmaz) ---
        val cardHeight = (metrics.heightPixels * 0.45f).toInt()
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

        // Kart Üst Başlığı (Kişi Bilgisi + Uygulamada Aç + Sohbeti Kapat)
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
            textSize = 11.5f
            setTextColor(Color.parseColor("#00A884"))
        }

        titleCol.addView(headerNameTxt)
        titleCol.addView(headerSubTxt)

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
                val active = sessions[activeChatKey]
                try {
                    active?.contentIntent?.send()
                    collapseChat()
                } catch (_: Exception) {
                    Toast.makeText(this@BubbleService, "Bu test balonudur kanka.", Toast.LENGTH_SHORT).show()
                }
            }
        }

        cardHeader.addView(headerAvatarBox)
        cardHeader.addView(titleCol)
        cardHeader.addView(openAppBtn)

        // Mesaj Geçmişi Alanı
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

        // Alt Mesaj Yazma ve Gönder (➤) Çubuğu
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
        expandedOverlayView?.addView(mainColumn)

        windowManager.addView(expandedOverlayView, expandedParams)

        activeChatKey?.let { sessions[it]?.unreadCount = 0 }
        refreshExpandedUI()
    }

    private fun refreshExpandedUI() {
        if (!isExpanded || expandedOverlayView == null) return

        val active = sessions[activeChatKey] ?: sessions.values.firstOrNull() ?: return
        activeChatKey = active.key
        active.unreadCount = 0

        // Üstteki yuvarlak sohbet kafalarını diz
        topHeadsContainer?.removeAllViews()
        sessions.values.forEach { sess ->
            val isSelected = sess.key == activeChatKey
            val headFrame = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(54), dpToPx(54)).apply {
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
                    layoutParams = FrameLayout.LayoutParams(dpToPx(50), dpToPx(50), Gravity.CENTER)
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
                    textSize = 18f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                }
                headFrame.addView(txt)
            }

            topHeadsContainer?.addView(headFrame)
        }

        // Başlık bilgilerini güncelle
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
        headerSubTxt?.text = "${active.appName} • Sohbet Balonu Aktif"
        headerSubTxt?.setTextColor(
            Color.parseColor(if (active.appName == "Instagram") "#E1306C" else "#25D366")
        )

        // Mesaj balonlarını Messenger stilinde çiz
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

            val bubble = TextView(this).apply {
                text = msg.text
                textSize = 14f
                setTextColor(Color.WHITE)
                maxWidth = dpToPx(240)
                setPadding(dpToPx(14), dpToPx(9), dpToPx(14), dpToPx(9))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(if (msg.isMe) "#0084FF" else "#303030"))
                    cornerRadius = dpToPx(18).toFloat()
                }
            }

            val timeTxt = TextView(this).apply {
                text = msg.time
                textSize = 10f
                setTextColor(Color.parseColor("#8A8D91"))
                setPadding(dpToPx(6), dpToPx(2), dpToPx(6), 0)
            }

            row.addView(bubble)
            row.addView(timeTxt)
            messagesContainer?.addView(row)
        }

        messagesScroll?.post { messagesScroll?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun sendMessageNow() {
        val text = replyEdit?.text?.toString()?.trim() ?: return
        if (text.isEmpty()) return

        val active = sessions[activeChatKey] ?: return
        val action = active.replyAction

        if (action == null) {
            // Test balonuysa anında ekrana ekle ve otomatik test cevabı döndür
            active.messages.add(ChatMessage(text = text, isMe = true, time = nowTime()))
            replyEdit?.setText("")
            refreshExpandedUI()

            mainHandler.postDelayed({
                active.messages.add(
                    ChatMessage(
                        text = "Mesajın ulaştı kanka! Gördüğün gibi artık hem ➤ Gönder butonu sabit hem de dışarı dokununca veya geri tuşuyla anında kapanıyor 👍",
                        isMe = false,
                        time = nowTime()
                    )
                )
                refreshExpandedUI()
            }, 900)
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
            replyEdit?.setText("")
            refreshExpandedUI()
        } catch (_: PendingIntent.CanceledException) {
            Toast.makeText(
                this,
                "Bildirim bağlantısı yenileniyor, sağ üstten 'Uygulama ↗' tuşuna basabilirsin kanka.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun collapseChat() {
        if (!isExpanded) return
        isExpanded = false

        try {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(replyEdit?.windowToken, 0)
        } catch (_: Exception) {}

        expandedOverlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
        }
        expandedOverlayView = null

        bubbleView?.visibility = View.VISIBLE
        sessions[activeChatKey]?.let { refreshBubbleAppearance(it) }
    }

    private fun destroyAllOverlays() {
        isExpanded = false
        expandedOverlayView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        expandedOverlayView = null

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
