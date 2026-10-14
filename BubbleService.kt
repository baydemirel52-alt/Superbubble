package com.selim.superbubble

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.provider.ContactsContract
import android.provider.MediaStore
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import android.util.TypedValue
import android.view.*
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.core.content.ContextCompat
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
        val TARGET_PACKAGES = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "com.instagram.android"
        )

        // Gerçek Mesaj Durum Kodları
        const val STATUS_SENDING = 0   // 🕒 (Gönderiliyor)
        const val STATUS_SENT = 1      // ✓ Tek Gri Tik (Sunucuya Gönderildi - Karşı tarafın interneti kapalı olabilir)
        const val STATUS_DELIVERED = 2 // ✓✓ Çift Gri Tik (Karşı cihaza İletildi)
        const val STATUS_READ = 3      // ✓✓ Çift Mavi Tik / Görüldü (Karşı taraf okudu)

        var instance: BubbleService? = null

        var pendingInstaCallVideo: Boolean? = null
        var pendingInstaCallTimestamp: Long = 0L

        // Canlı Tik ve Çevrimiçi/Son Görülme Yoklaması
        var pendingQuickSync: Boolean = false
        var pendingQuickSyncTimestamp: Long = 0L

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
                    sbnKey = null,
                    pkgName = "com.whatsapp",
                    sender = "Test Sohbeti",
                    messagesBatch = listOf(
                        ChatMessage(
                            text = "Kanka sahte çift tik kaldırıldı! Üstteki 🔄 tuşuyla gerçek tik ve son görülme yoklaması yapabilirsin.",
                            isMe = false,
                            time = srv.nowTime(),
                            status = STATUS_READ
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

    data class ChatMessage(
        val text: String,
        val isMe: Boolean,
        val time: String,
        var imageBase64: String? = null,
        val audioUri: String? = null,
        var status: Int = STATUS_SENT
    )

    data class ChatSession(
        val key: String,
        var sbnKey: String? = null,
        var pkgName: String = "com.whatsapp",
        var sender: String,
        val appName: String,
        val messages: MutableList<ChatMessage> = mutableListOf(),
        var avatar: Bitmap? = null,
        var replyAction: Notification.Action? = null,
        var contentIntent: PendingIntent? = null,
        var callIntent: PendingIntent? = null,
        var isIncomingCall: Boolean = false,
        var presenceStatus: String? = null, // "çevrimiçi", "son görülme...", "yazıyor...", "Şu anda aktif"
        var unreadCount: Int = 0
    )

    private lateinit var windowManager: WindowManager
    val mainHandler = Handler(Looper.getMainLooper())
    private val activeSessions = LinkedHashMap<String, ChatSession>()
    private var activeChatKey: String? = null

    private val recentSentTexts = LinkedHashMap<String, Long>()
    private var mediaPlayer: MediaPlayer? = null

    // 1. Küçük Yüzen Balon
    private var bubbleView: FrameLayout? = null
    private var bubbleAvatarContainer: FrameLayout? = null
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
    private var incomingCallBanner: TextView? = null
    private var headerAvatarImg: ImageView? = null
    private var headerInitialsTxt: TextView? = null
    private var headerNameTxt: TextView? = null
    private var headerSubTxt: TextView? = null
    private var messagesContainer: LinearLayout? = null
    private var messagesScroll: ScrollView? = null
    private var extraToolPanel: FrameLayout? = null
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

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        mainHandler.post {
            val metrics = resources.displayMetrics
            if (bubbleView != null) {
                bubbleParams.x = metrics.widthPixels - dpToPx(74)
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
        val pkg = sbn.packageName
        if (!TARGET_PACKAGES.contains(pkg)) return
        mainHandler.post {
            activeSessions.values.forEach { sess ->
                if (sess.sbnKey == sbn.key && sess.isIncomingCall) {
                    sess.isIncomingCall = false
                    sess.callIntent = null
                    refreshBubbleAppearance(sess)
                    if (isExpanded) refreshExpandedUI()
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val pkg = sbn.packageName
        if (!TARGET_PACKAGES.contains(pkg)) return

        val notif = sbn.notification
        if ((notif.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val extras = notif.extras
        val convTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim()
        val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: return

        val cleanTitle = (convTitle ?: rawTitle)
            .replace(Regex("\\s*\\(\\d+\\s*mesaj\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\(\\d+\\s*new messages\\)", RegexOption.IGNORE_CASE), "")
            .substringBefore(":")
            .trim()

        var text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()
            ?: ""

        val appName = if (pkg.contains("instagram")) "Instagram" else "WhatsApp"
        val normalizedKey = "${pkg}_${cleanTitle.lowercase(Locale.getDefault())}"
        val avatar = extractAvatar(notif)

        // ==================== 1. GELEN ARAMA KONTROLÜ ====================
        val isCallNotif = notif.category == Notification.CATEGORY_CALL ||
                notif.fullScreenIntent != null ||
                isIncomingCallText(text) ||
                isIncomingCallText(rawTitle)

        if (isCallNotif) {
            val targetCallIntent = notif.contentIntent ?: notif.fullScreenIntent ?: findAcceptCallIntent(notif)
            val callDesc = if (text.isNotEmpty()) "📞 $text" else "📞 Gelen Arama..."
            mainHandler.post {
                onNewMessage(
                    chatKey = normalizedKey,
                    sbnKey = sbn.key,
                    pkgName = pkg,
                    sender = cleanTitle,
                    messagesBatch = listOf(ChatMessage(callDesc, false, nowTime())),
                    appName = appName,
                    avatar = avatar,
                    replyAction = null,
                    contentIntent = notif.contentIntent,
                    callIntent = targetCallIntent,
                    isIncomingCall = true
                )
            }
            return
        }

        // ==================== 2. GÖNDERİLEN MESAJ YANSIMASI (SAHTE ÇİFT TİK YAPMAZ!) ====================
        val remoteHistory = extras.getCharSequenceArray(Notification.EXTRA_REMOTE_INPUT_HISTORY)
        if (!remoteHistory.isNullOrEmpty()) {
            // Bu sadece bizim yazdığımız cevabın bildirim yansımasıdır; karşı tarafa iletildiği anlamına GELMEZ!
            // O yüzden STATUS_DELIVERED yapmıyoruz, sadece üstteki yansıma bildirimini temizliyoruz.
            scheduleCancelNotification(sbn.key, 350)
            return
        }

        val selfName = extras.getCharSequence(Notification.EXTRA_SELF_DISPLAY_NAME)?.toString()?.trim()

        if (text.isEmpty() ||
            text.contains("Yeni mesajlar kontrol ediliyor", ignoreCase = true) ||
            text.contains("WhatsApp Web", ignoreCase = true) ||
            cleanTitle.equals("WhatsApp", ignoreCase = true) ||
            cleanTitle.equals("Instagram", ignoreCase = true) ||
            cleanTitle.equals("Siz", ignoreCase = true) ||
            cleanTitle.equals("Sen", ignoreCase = true) ||
            cleanTitle.equals("You", ignoreCase = true) ||
            (selfName != null && cleanTitle.equals(selfName, ignoreCase = true))
        ) return

        var photoBitmap: Bitmap? = extractPhotoFromNotification(notif, extras)
        var audioUriStr: String? = null

        val extractedBatch = mutableListOf<ChatMessage>()
        val historicBundles = extras.getParcelableArray(Notification.EXTRA_HISTORIC_MESSAGES)
        val msgBundles = extras.getParcelableArray(Notification.EXTRA_MESSAGES)

        val allBundles = mutableListOf<Parcelable>()
        if (historicBundles != null) allBundles.addAll(historicBundles)
        if (msgBundles != null) allBundles.addAll(msgBundles)

        if (allBundles.isNotEmpty()) {
            val lastBundle = allBundles.last() as? Bundle
            val lastSender = lastBundle?.getCharSequence("sender")?.toString()?.trim()
            if (lastSender != null && (
                        lastSender.equals("Siz", true) ||
                        lastSender.equals("Sen", true) ||
                        lastSender.equals("You", true) ||
                        (selfName != null && lastSender.equals(selfName, true))
                    )
            ) {
                scheduleCancelNotification(sbn.key, 350)
                return
            }

            for (i in allBundles.indices) {
                val b = allBundles[i] as? Bundle ?: continue
                val bSender = b.getCharSequence("sender")?.toString()?.trim()
                val bText = b.getCharSequence("text")?.toString()?.trim() ?: continue
                val isFromMe = bSender != null && (
                        bSender.equals("Siz", true) ||
                        bSender.equals("Sen", true) ||
                        bSender.equals("You", true) ||
                        (selfName != null && bSender.equals(selfName, true))
                        )

                var itemBmp: Bitmap? = null
                var itemAudio: String? = null
                val mime = b.getString("type") ?: ""
                val u = b.getParcelable<Parcelable>("uri") as? Uri
                if (u != null) {
                    if (mime.startsWith("image/")) {
                        itemBmp = loadBitmapFromUri(u)
                        if (photoBitmap == null) photoBitmap = itemBmp
                    } else if (mime.startsWith("audio/")) {
                        itemAudio = u.toString()
                        if (audioUriStr == null) audioUriStr = itemAudio
                    }
                }

                extractedBatch.add(
                    ChatMessage(
                        text = bText,
                        isMe = isFromMe,
                        time = nowTime(),
                        imageBase64 = bitmapToBase64(itemBmp),
                        audioUri = itemAudio,
                        status = if (isFromMe) STATUS_SENT else STATUS_READ
                    )
                )
            }
            if (extractedBatch.isNotEmpty()) {
                text = extractedBatch.last().text
            }
        }

        if (text.startsWith("Siz:") || text.startsWith("Sen:") || text.startsWith("You:")) {
            scheduleCancelNotification(sbn.key, 350)
            return
        }

        val now = System.currentTimeMillis()
        recentSentTexts.entries.removeIf { now - it.value > 15000 }
        if (recentSentTexts.containsKey(text)) {
            scheduleCancelNotification(sbn.key, 350)
            return
        }

        if (photoBitmap == null && isPhotoMessageText(text)) {
            photoBitmap = fetchLatestReceivedPhoto(40)
        }

        val replyAction = findReplyAction(notif) ?: return

        if (extractedBatch.isEmpty()) {
            extractedBatch.add(
                ChatMessage(
                    text = text,
                    isMe = false,
                    time = nowTime(),
                    imageBase64 = bitmapToBase64(photoBitmap),
                    audioUri = audioUriStr,
                    status = STATUS_READ
                )
            )
        } else if (photoBitmap != null && extractedBatch.last().imageBase64 == null) {
            extractedBatch.last().imageBase64 = bitmapToBase64(photoBitmap)
        }

        mainHandler.post {
            onNewMessage(
                chatKey = normalizedKey,
                sbnKey = sbn.key,
                pkgName = pkg,
                sender = cleanTitle,
                messagesBatch = extractedBatch,
                appName = appName,
                avatar = avatar,
                replyAction = replyAction,
                contentIntent = notif.contentIntent,
                callIntent = null,
                isIncomingCall = false
            )

            if (photoBitmap == null && isPhotoMessageText(text)) {
                mainHandler.postDelayed({
                    val delayedBmp = fetchLatestReceivedPhoto(25)
                    if (delayedBmp != null) {
                        val sess = activeSessions[normalizedKey]
                        val last = sess?.messages?.lastOrNull()
                        if (last != null && !last.isMe && last.imageBase64 == null) {
                            last.imageBase64 = bitmapToBase64(delayedBmp)
                            saveMessagesToDisk(normalizedKey, sess.messages)
                            if (isExpanded && activeChatKey == normalizedKey) {
                                refreshExpandedUI()
                            }
                        }
                    }
                }, 1200)
            }
        }
    }

    // ==================== GERÇEK TİK VE ÇEVRİMİÇİ / SON GÖRÜLME EŞİTLEME ====================
    fun syncFromAppScreen(
        pkgName: String,
        title: String,
        presenceText: String?,
        screenMessages: List<Triple<String, Boolean, Int>>
    ) {
        val cleanTitle = title.trim()
        if (cleanTitle.isEmpty() || cleanTitle.equals("WhatsApp", true) || cleanTitle.equals("Instagram", true)) return
        val appName = if (pkgName.contains("instagram")) "Instagram" else "WhatsApp"
        val chatKey = "${pkgName}_${cleanTitle.lowercase(Locale.getDefault())}"

        mainHandler.post {
            // Eğer başka bir ön-isimle ("test_messenger" dışındaki boş kafa) açılmışsa birleştir
            val session = activeSessions.getOrPut(chatKey) {
                ChatSession(
                    key = chatKey,
                    pkgName = pkgName,
                    sender = cleanTitle,
                    appName = appName,
                    messages = loadSavedMessages(chatKey)
                )
            }

            var changed = false
            if (!presenceText.isNullOrBlank() && session.presenceStatus != presenceText) {
                session.presenceStatus = presenceText
                changed = true
            }

            for ((msgText, isMe, realTickStatus) in screenMessages) {
                val existing = session.messages.findLast { it.text == msgText && it.isMe == isMe }
                if (existing != null) {
                    if (isMe && existing.status != realTickStatus) {
                        existing.status = realTickStatus
                        changed = true
                    }
                } else {
                    session.messages.add(
                        ChatMessage(
                            text = msgText,
                            isMe = isMe,
                            time = nowTime(),
                            status = realTickStatus
                        )
                    )
                    changed = true
                }
            }

            if (changed) {
                saveMessagesToDisk(chatKey, session.messages)
                if (isExpanded && activeChatKey == chatKey) {
                    refreshExpandedUI()
                }
            }
        }
    }

    fun reopenAfterQuickSync() {
        mainHandler.postDelayed({
            if (!isExpanded && activeSessions.isNotEmpty()) {
                openExpandedChat()
                Toast.makeText(this, "✅ Tikler ve Son Görülme güncellendi!", Toast.LENGTH_SHORT).show()
            }
        }, 350)
    }

    private fun triggerQuickLiveSync() {
        if (activeChatKey == "test_messenger") {
            val active = activeSessions[activeChatKey] ?: return
            active.presenceStatus = "çevrimiçi"
            active.messages.forEach { if (it.isMe) it.status = STATUS_READ }
            refreshExpandedUI()
            Toast.makeText(this, "Test: Durum 'çevrimiçi' ve Mavi Tik yapıldı!", Toast.LENGTH_SHORT).show()
            return
        }

        pendingQuickSync = true
        pendingQuickSyncTimestamp = System.currentTimeMillis()
        Toast.makeText(this, "🔄 Gerçek tik ve son görülme yoklanıyor...", Toast.LENGTH_SHORT).show()
        openOriginalApp()
    }

    private fun isIncomingCallText(txt: String): Boolean {
        val lower = txt.lowercase(Locale.getDefault())
        return lower.contains("sesli arama") || lower.contains("görüntülü arama") ||
                lower.contains("gelen arama") || lower.contains("arıyor") ||
                lower.contains("voice call") || lower.contains("video call") ||
                lower.contains("incoming call")
    }

    private fun findAcceptCallIntent(notif: Notification): PendingIntent? {
        notif.actions?.forEach { act ->
            val t = act.title?.toString()?.lowercase(Locale.getDefault()) ?: ""
            if (t.contains("cevapla") || t.contains("yanıtla") || t.contains("kabul") || t.contains("answer") || t.contains("accept")) {
                return act.actionIntent
            }
        }
        return notif.actions?.lastOrNull()?.actionIntent
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
            } else {
                null
            }
            pi.send(this, 0, null, null, null, null, optionsBundle)
            true
        } catch (_: Exception) {
            false
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
        val stripped = senderName.replace(Regex("[^\\p{L}\\p{N}\\s]"), "").trim()
        val candidates = listOf(senderName.trim(), stripped).filter { it.isNotEmpty() }.distinct()

        for (queryName in candidates) {
            try {
                contentResolver.query(
                    ContactsContract.Data.CONTENT_URI,
                    arrayOf(ContactsContract.Data._ID),
                    "${ContactsContract.Data.MIMETYPE} = ? AND ${ContactsContract.Data.DISPLAY_NAME} LIKE ?",
                    arrayOf(mimeType, "%$queryName%"),
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        return cursor.getLong(0)
                    }
                }
            } catch (_: Exception) {}
        }
        return null
    }

    private fun createMessengerVectorIcon(isVideo: Boolean, onClick: () -> Unit): ImageView {
        val sizePx = dpToPx(34)
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0084FF")
            style = Paint.Style.FILL
        }

        val w = sizePx.toFloat()
        if (isVideo) {
            val bodyRect = RectF(w * 0.16f, w * 0.28f, w * 0.64f, w * 0.72f)
            canvas.drawRoundRect(bodyRect, w * 0.10f, w * 0.10f, paint)
            val lensPath = Path().apply {
                moveTo(w * 0.66f, w * 0.43f)
                lineTo(w * 0.86f, w * 0.30f)
                lineTo(w * 0.86f, w * 0.70f)
                lineTo(w * 0.66f, w * 0.57f)
                close()
            }
            canvas.drawPath(lensPath, paint)
        } else {
            val phonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#0084FF")
                style = Paint.Style.STROKE
                strokeWidth = w * 0.12f
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            val path = Path().apply {
                moveTo(w * 0.26f, w * 0.24f)
                lineTo(w * 0.38f, w * 0.24f)
                lineTo(w * 0.44f, w * 0.38f)
                lineTo(w * 0.35f, w * 0.46f)
                quadTo(w * 0.44f, w * 0.60f, w * 0.54f, w * 0.65f)
                lineTo(w * 0.62f, w * 0.56f)
                lineTo(w * 0.76f, w * 0.62f)
                lineTo(w * 0.76f, w * 0.74f)
                quadTo(w * 0.42f, w * 0.76f, w * 0.24f, w * 0.26f)
            }
            canvas.drawPath(path, phonePaint)
        }

        return ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                rightMargin = dpToPx(5)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#263951"))
            }
            setImageBitmap(bmp)
            setOnClickListener { onClick() }
        }
    }

    @Suppress("DEPRECATION")
    private fun extractPhotoFromNotification(notif: Notification, extras: Bundle): Bitmap? {
        try {
            val picObj = extras.get(Notification.EXTRA_PICTURE)
            if (picObj is Bitmap) return picObj
            if (Build.VERSION.SDK_INT >= 23 && picObj is Icon) {
                iconToBitmap(picObj)?.let { return it }
            }

            val picIconObj = extras.get("android.pictureIcon")
            if (Build.VERSION.SDK_INT >= 23 && picIconObj is Icon) {
                iconToBitmap(picIconObj)?.let { return it }
            }

            val bigLarge = extras.get(Notification.EXTRA_LARGE_ICON_BIG)
            if (bigLarge is Bitmap) return bigLarge
            if (Build.VERSION.SDK_INT >= 23 && bigLarge is Icon) {
                iconToBitmap(bigLarge)?.let { return it }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun iconToBitmap(icon: Icon): Bitmap? {
        return try {
            val d = icon.loadDrawable(this) ?: return null
            val w = max(1, if (d.intrinsicWidth > 0) d.intrinsicWidth else 300)
            val h = max(1, if (d.intrinsicHeight > 0) d.intrinsicHeight else 300)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            d.setBounds(0, 0, canvas.width, canvas.height)
            d.draw(canvas)
            bmp
        } catch (_: Exception) {
            null
        }
    }

    private fun hasPhotoPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun fetchLatestReceivedPhoto(maxAgeSeconds: Long): Bitmap? {
        if (!hasPhotoPermission()) return null
        return try {
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATE_ADDED
            )
            val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                    val id = cursor.getLong(idCol)
                    val dateAdded = cursor.getLong(dateCol)
                    val nowSec = System.currentTimeMillis() / 1000
                    if (nowSec - dateAdded <= maxAgeSeconds) {
                        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                        return loadBitmapFromUri(uri)
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun getRecentGalleryPhotos(limit: Int = 10): List<Uri> {
        val list = mutableListOf<Uri>()
        if (!hasPhotoPermission()) return list
        try {
            val projection = arrayOf(MediaStore.Images.Media._ID)
            val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                while (cursor.moveToNext() && list.size < limit) {
                    val id = cursor.getLong(idCol)
                    list.add(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id))
                }
            }
        } catch (_: Exception) {}
        return list
    }

    private fun scheduleCancelNotification(sbnKey: String?, delayMs: Long = 250) {
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
            val maxDim = 460
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
                        audioUri = obj.optString("aud", "").ifEmpty { null },
                        status = obj.optInt("st", STATUS_SENT)
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
            val slice = if (messages.size > 80) messages.takeLast(80) else messages
            for (m in slice) {
                val obj = JSONObject()
                obj.put("text", m.text)
                obj.put("isMe", m.isMe)
                obj.put("time", m.time)
                obj.put("st", m.status)
                if (m.imageBase64 != null) obj.put("img", m.imageBase64)
                if (m.audioUri != null) obj.put("aud", m.audioUri)
                arr.put(obj)
            }
            prefs.edit().putString(chatKey, arr.toString()).apply()
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

    fun nowTime(): String {
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
    }

    private fun onNewMessage(
        chatKey: String,
        sbnKey: String?,
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
        val session = activeSessions.getOrPut(chatKey) {
            val history = loadSavedMessages(chatKey)
            ChatSession(
                key = chatKey,
                pkgName = pkgName,
                sender = sender,
                appName = appName,
                messages = history
            )
        }

        session.sender = sender
        session.pkgName = pkgName
        if (sbnKey != null) session.sbnKey = sbnKey
        if (avatar != null) session.avatar = avatar
        if (replyAction != null) session.replyAction = replyAction
        if (contentIntent != null) session.contentIntent = contentIntent
        session.callIntent = callIntent
        session.isIncomingCall = isIncomingCall

        var addedNew = false
        var hasIncomingFromOther = false

        for (incoming in messagesBatch) {
            if (!incoming.isMe) hasIncomingFromOther = true
            val existing = session.messages.lastOrNull { it.text == incoming.text && it.isMe == incoming.isMe }
            if (existing != null) {
                if (incoming.imageBase64 != null && existing.imageBase64 == null) {
                    existing.imageBase64 = incoming.imageBase64
                    addedNew = true
                }
            } else {
                session.messages.add(incoming)
                addedNew = true
            }
        }

        // Karşı taraf o an yeni bir mesaj gönderdiyse o an çevrimiçidir
        if (hasIncomingFromOther) {
            session.presenceStatus = if (appName == "Instagram") "Şu anda aktif" else "çevrimiçi"
        }

        if (addedNew) {
            saveMessagesToDisk(chatKey, session.messages)
            if (!isExpanded || activeChatKey != chatKey) {
                session.unreadCount++
            } else if (!isIncomingCall) {
                scheduleCancelNotification(session.sbnKey, 200)
            }
        }

        activeChatKey = if (isIncomingCall) chatKey else (activeChatKey ?: chatKey)

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

        bubbleAvatarContainer = FrameLayout(this).apply {
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
                        bubbleParams.y = max(dpToPx(24), min(metrics.heightPixels - dpToPx(80), startY + dy))
                        windowManager.updateViewLayout(bubbleView, bubbleParams)
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

        bubbleAvatarContainer?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(if (session.isIncomingCall) "#00C853" else "#0084FF"))
            setStroke(dpToPx(if (session.isIncomingCall) 3 else 2), if (session.isIncomingCall) Color.parseColor("#00E676") else Color.WHITE)
        }

        val isInsta = session.appName == "Instagram"
        bubbleAppBadge?.text = if (session.isIncomingCall) "📞" else if (isInsta) "IG" else "W"
        bubbleAppBadge?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(if (session.isIncomingCall) "#00C853" else if (isInsta) "#E1306C" else "#25D366"))
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

    // ==================== 2. AÇIK MESSENGER SOHBET PENCERESİ (KLAVYE TAŞMASI DÜZELTİLDİ) ====================
    private fun openExpandedChat() {
        if (isExpanded || activeSessions.isEmpty()) return
        isExpanded = true

        bubbleView?.visibility = View.GONE

        val metrics = resources.displayMetrics
        val isLandscape = metrics.widthPixels > metrics.heightPixels

        expandedParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            PixelFormat.TRANSLUCENT
        ).apply {
            dimAmount = 0.55f
