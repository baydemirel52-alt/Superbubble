package com.selim.superbubble

import android.animation.ValueAnimator
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.TypedValue
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import kotlin.math.abs

class BubbleService : NotificationListenerService() {

    companion object {
        // Sadece WhatsApp ve Instagram'ı dinle
        private val TARGET_PACKAGES = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "com.instagram.android"
        )

        private var instance: BubbleService? = null

        fun spawnTestBubble(context: Context) {
            instance?.mainHandler?.post {
                instance?.onNewMessage(
                    chatKey = "test_key",
                    sender = "Test Sohbeti (S22 Ultra)",
                    message = "Kanka balon sistemi tıkır tıkır çalışıyor!",
                    appName = "WhatsApp",
                    avatar = null,
                    replyAction = null
                )
            } ?: Toast.makeText(context, "Önce Bildirim İznini açmalısın kanka!", Toast.LENGTH_LONG).show()
        }
    }

    data class ChatSession(
        val key: String,
        var sender: String,
        val appName: String,
        val messages: MutableList<String> = mutableListOf(),
        var avatar: Bitmap? = null,
        var replyAction: Notification.Action? = null,
        var unreadCount: Int = 0
    )

    private lateinit var windowManager: WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sessions = LinkedHashMap<String, ChatSession>()
    private var activeChatKey: String? = null

    // Görsel Elemanlar
    private var rootView: LinearLayout? = null
    private var bubbleAvatarView: ImageView? = null
    private var badgeTextView: TextView? = null
    private var chatBoxLayout: LinearLayout? = null
    private var messagesContainer: LinearLayout? = null
    private var scrollView: ScrollView? = null
    private var replyInput: EditText? = null
    private var headerTitle: TextView? = null
    private var senderTabsLayout: LinearLayout? = null

    private lateinit var windowParams: WindowManager.LayoutParams
    private var isExpanded = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onDestroy() {
        super.onDestroy()
        removeBubbleView()
        instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val pkg = sbn.packageName
        if (!TARGET_PACKAGES.contains(pkg)) return

        val notif = sbn.notification
        // Grup özeti bildirimlerini ("2 sohbetten 5 mesaj" vb.) yoksay
        if ((notif.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val extras = notif.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: return

        // Sistem mesajlarını filtrele
        if (text.contains("Yeni mesajlar kontrol ediliyor", ignoreCase = true) ||
            text.contains("WhatsApp Web", ignoreCase = true)
        ) return

        // Yanıtlama (RemoteInput) aksiyonunu bul
        val replyAction = findReplyAction(notif)
        // Sadece yanıtlanabilir gerçek mesaj bildirimlerini al (Aramaları vs. ele)
        if (replyAction == null) return

        val avatar = extractAvatar(notif)
        val appName = if (pkg.contains("instagram")) "Instagram" else "WhatsApp"
        val chatKey = "${pkg}_$title"

        mainHandler.post {
            onNewMessage(chatKey, title, text, appName, avatar, replyAction)
        }
    }

    private fun findReplyAction(notif: Notification): Notification.Action? {
        notif.actions?.forEach { action ->
            if (action.remoteInputs != null && action.remoteInputs.isNotEmpty()) {
                return action
            }
        }
        return null
    }

    private fun extractAvatar(notif: Notification): Bitmap? {
        try {
            val largeIcon = notif.getLargeIcon()
            if (largeIcon != null) {
                val drawable = largeIcon.loadDrawable(this) ?: return null
                val bitmap = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                return getCircularBitmap(bitmap)
            }
        } catch (_: Exception) {}
        return null
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

    private fun onNewMessage(
        chatKey: String,
        sender: String,
        message: String,
        appName: String,
        avatar: Bitmap?,
        replyAction: Notification.Action?
    ) {
        val session = sessions.getOrPut(chatKey) {
            ChatSession(key = chatKey, sender = sender, appName = appName)
        }

        // Aynı mesaj üst üste tetiklendiyse tekrar ekleme
        if (session.messages.lastOrNull() != "$sender: $message") {
            session.messages.add("$sender: $message")
            if (!isExpanded || activeChatKey != chatKey) {
                session.unreadCount++
            }
        }

        if (avatar != null) session.avatar = avatar
        if (replyAction != null) session.replyAction = replyAction

        activeChatKey = activeChatKey ?: chatKey

        if (rootView == null) {
            createBubbleOverlay()
        }
        updateUI(latestSession = session)
    }

    private fun createBubbleOverlay() {
        val metrics = resources.displayMetrics

        windowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = metrics.widthPixels - dpToPx(75)
            y = metrics.heightPixels / 4
        }

        rootView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }

        // --- 1. YUVARLAK BALON KISMI ---
        val bubbleFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(64), dpToPx(64))
        }

        bubbleAvatarView = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(dpToPx(60), dpToPx(60), Gravity.CENTER)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#25D366"))
                setStroke(dpToPx(2), Color.WHITE)
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
        }

        badgeTextView = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(dpToPx(22), dpToPx(22), Gravity.TOP or Gravity.END)
            gravity = Gravity.CENTER
            textSize = 11f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.RED)
            }
            visibility = View.GONE
        }

        bubbleFrame.addView(bubbleAvatarView)
        bubbleFrame.addView(badgeTextView)

        // --- 2. AÇILIR SOHBET PENCERESİ KISMI ---
        chatBoxLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(dpToPx(320), dpToPx(380)).apply {
                topMargin = dpToPx(8)
            }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E1E24"))
                cornerRadius = dpToPx(18).toFloat()
                setStroke(dpToPx(1), Color.parseColor("#3A3A44"))
            }
            setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12))
            visibility = View.GONE
        }

        // Üst Sekmeler (Birden fazla kişi yazdıysa aralarında geçiş yapma)
        senderTabsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Başlık ve Kapatma Butonu
        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dpToPx(4), 0, dpToPx(8))
        }

        headerTitle = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
        }

        val closeChatBtn = TextView(this).apply {
            text = "✕"
            textSize = 18f
            setTextColor(Color.LTGRAY)
            setPadding(dpToPx(8), 0, dpToPx(4), 0)
            setOnClickListener {
                activeChatKey?.let { key -> sessions.remove(key) }
                if (sessions.isEmpty()) {
                    removeBubbleView()
                } else {
                    activeChatKey = sessions.keys.first()
                    updateUI(sessions[activeChatKey]!!)
                }
            }
        }

        headerLayout.addView(headerTitle)
        headerLayout.addView(closeChatBtn)

        // Mesaj Listesi
        scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        messagesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollView?.addView(messagesContainer)

        // Alt Mesaj Yazma ve Gönderme Alanı
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dpToPx(8), 0, 0)
        }

        replyInput = EditText(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(42), 1f)
            hint = "Yanıt yaz..."
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(dpToPx(12), 0, dpToPx(12), 0)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2C2C35"))
                cornerRadius = dpToPx(20).toFloat()
            }
        }

        val sendBtn = Button(this).apply {
            text = "Gönder"
            textSize = 12f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dpToPx(40)
            ).apply { leftMargin = dpToPx(6) }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#00A884"))
                cornerRadius = dpToPx(20).toFloat()
            }
            setOnClickListener { sendDirectReply() }
        }

        inputRow.addView(replyInput)
        inputRow.addView(sendBtn)

        chatBoxLayout?.addView(senderTabsLayout)
        chatBoxLayout?.addView(headerLayout)
        chatBoxLayout?.addView(scrollView)
        chatBoxLayout?.addView(inputRow)

        rootView?.addView(bubbleFrame)
        rootView?.addView(chatBoxLayout)

        setupTouchPhysics(bubbleFrame)
        windowManager.addView(rootView, windowParams)
    }

    private fun setupTouchPhysics(bubbleFrame: View) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        bubbleFrame.setOnTouchListener { _, event ->
            val metrics = resources.displayMetrics
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = windowParams.x
                    initialY = windowParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > 15 || abs(dy) > 15) isDragging = true

                    if (isDragging && !isExpanded) {
                        windowParams.x = initialX + dx
                        windowParams.y = initialY + dy
                        windowManager.updateViewLayout(rootView, windowParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        toggleExpand()
                    } else if (!isExpanded) {
                        // Ekranın en altına sürüklediyse balonu kapat
                        if (windowParams.y > metrics.heightPixels * 0.82) {
                            removeBubbleView()
                            sessions.clear()
                        } else {
                            // Mıknatıs gibi en yakın kenara yapıştır
                            val targetX = if (windowParams.x + dpToPx(32) < metrics.widthPixels / 2) {
                                dpToPx(6)
                            } else {
                                metrics.widthPixels - dpToPx(70)
                            }
                            animateToEdge(windowParams.x, targetX)
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun animateToEdge(startX: Int, endX: Int) {
        ValueAnimator.ofInt(startX, endX).apply {
            duration = 220
            addUpdateListener { anim ->
                if (rootView != null) {
                    windowParams.x = anim.animatedValue as Int
                    windowManager.updateViewLayout(rootView, windowParams)
                }
            }
            start()
        }
    }

    private fun toggleExpand() {
        isExpanded = !isExpanded
        if (isExpanded) {
            chatBoxLayout?.visibility = View.VISIBLE
            // Klavyenin açılabilmesi için FLAG_NOT_FOCUSABLE bayrağını kaldırıyoruz
            windowParams.flags = windowParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            windowManager.updateViewLayout(rootView, windowParams)

            activeChatKey?.let { key ->
                sessions[key]?.unreadCount = 0
                updateUI(sessions[key]!!)
            }
        } else {
            // Klavyeyi gizle ve ekran odağını normal kullanıma geri ver
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(replyInput?.windowToken, 0)

            chatBoxLayout?.visibility = View.GONE
            windowParams.flags = windowParams.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            windowManager.updateViewLayout(rootView, windowParams)
            updateBadge()
        }
    }

    private fun sendDirectReply() {
        val text = replyInput?.text?.toString()?.trim() ?: return
        if (text.isEmpty()) return

        val currentSession = sessions[activeChatKey] ?: return
        val action = currentSession.replyAction

        if (action == null) {
            // Test balonuysa sadece ekrana yaz
            currentSession.messages.add("Sen: $text")
            replyInput?.setText("")
            updateUI(currentSession)
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

            currentSession.messages.add("Sen: $text")
            replyInput?.setText("")
            updateUI(currentSession)
        } catch (e: PendingIntent.CanceledException) {
            Toast.makeText(this, "Bildirim süresi dolmuş, uygulama içinden yanıtla kanka.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateUI(latestSession: ChatSession) {
        // Avatarı güncelle
        if (latestSession.avatar != null) {
            bubbleAvatarView?.setImageBitmap(latestSession.avatar)
        }

        updateBadge()

        // Açık olan sohbeti güncelle
        val activeSession = sessions[activeChatKey] ?: latestSession
        headerTitle?.text = "${activeSession.sender} (${activeSession.appName})"

        // Üstteki kişi sekmelerini (Tab) güncelle
        senderTabsLayout?.removeAllViews()
        if (sessions.size > 1) {
            sessions.values.forEach { sess ->
                val tab = TextView(this).apply {
                    text = if (sess.unreadCount > 0) "${sess.sender.take(8)} (${sess.unreadCount})" else sess.sender.take(8)
                    textSize = 11f
                    setTextColor(Color.WHITE)
                    setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4))
                    background = GradientDrawable().apply {
                        setColor(if (sess.key == activeChatKey) Color.parseColor("#00A884") else Color.parseColor("#33333D"))
                        cornerRadius = dpToPx(10).toFloat()
                    }
                    setOnClickListener {
                        activeChatKey = sess.key
                        sess.unreadCount = 0
                        updateUI(sess)
                    }
                }
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = dpToPx(6) }
                senderTabsLayout?.addView(tab, lp)
            }
        }

        // Mesajları ekrana diz
        messagesContainer?.removeAllViews()
        activeSession.messages.forEach { msg ->
            val isMe = msg.startsWith("Sen:")
            val msgView = TextView(this).apply {
                text = msg
                textSize = 13.5f
                setTextColor(Color.WHITE)
                setPadding(dpToPx(10), dpToPx(6), dpToPx(10), dpToPx(6))
                background = GradientDrawable().apply {
                    setColor(if (isMe) Color.parseColor("#005C4B") else Color.parseColor("#2C2C35"))
                    cornerRadius = dpToPx(12).toFloat()
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(6)
                gravity = if (isMe) Gravity.END else Gravity.START
            }
            messagesContainer?.addView(msgView, lp)
        }

        scrollView?.post { scrollView?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun updateBadge() {
        val totalUnread = sessions.values.sumOf { it.unreadCount }
        if (totalUnread > 0 && !isExpanded) {
            badgeTextView?.text = totalUnread.toString()
            badgeTextView?.visibility = View.VISIBLE
        } else {
            badgeTextView?.visibility = View.GONE
        }
    }

    private fun removeBubbleView() {
        rootView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        rootView = null
        isExpanded = false
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
