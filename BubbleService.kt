package com.selim.superbubble

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
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

        var instance: BubbleService? = null

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
                        ChatMessage("Kanka üstteki sesli ve görüntülü arama logolarına bak!", false, srv.nowTime())
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
        val audioUri: String? = null
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

    // Arama kapandığında arama durumunu sıfırla
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

        // ==================== 1. GELEN SESLİ / GÖRÜNTÜLÜ ARAMA KONTROLÜ ====================
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

        // ==================== 2. NORMAL MESAJ & YANSIMA KONTROLÜ ====================
        val remoteHistory = extras.getCharSequenceArray(Notification.EXTRA_REMOTE_INPUT_HISTORY)
        if (!remoteHistory.isNullOrEmpty()) {
            scheduleCancelNotification(sbn.key, 400)
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

        // Bildirim yığınındaki TÜM geçmiş ve yeni mesajları çek (Sadece son mesajı değil!)
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
                scheduleCancelNotification(sbn.key, 400)
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
                        audioUri = itemAudio
                    )
                )
            }
            if (extractedBatch.isNotEmpty()) {
                text = extractedBatch.last().text
            }
        }

        if (text.startsWith("Siz:") || text.startsWith("Sen:") || text.startsWith("You:")) {
            scheduleCancelNotification(sbn.key, 400)
            return
        }

        val now = System.currentTimeMillis()
        recentSentTexts.entries.removeIf { now - it.value > 15000 }
        if (recentSentTexts.containsKey(text)) {
            scheduleCancelNotification(sbn.key, 400)
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
                    audioUri = audioUriStr
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

    // WhatsApp/Instagram uygulama içi sohbet ekranından okunan mesajları balon geçmişiyle eşitler
    fun syncFromAppScreen(pkgName: String, title: String, screenMessages: List<Pair<String, Boolean>>) {
        val cleanTitle = title.trim()
        if (cleanTitle.isEmpty() || cleanTitle.equals("WhatsApp", true) || cleanTitle.equals("Instagram", true)) return
        val appName = if (pkgName.contains("instagram")) "Instagram" else "WhatsApp"
        val chatKey = "${pkgName}_${cleanTitle.lowercase(Locale.getDefault())}"

        mainHandler.post {
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
            for ((msgText, isMe) in screenMessages) {
                val exists = session.messages.any { it.text == msgText && it.isMe == isMe }
                if (!exists) {
                    session.messages.add(ChatMessage(text = msgText, isMe = isMe, time = nowTime()))
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

    // ==================== DOĞRUDAN WHATSAPP SESLİ / GÖRÜNTÜLÜ ARAMA BAŞLATMA ====================
    private fun initiateCall(isVideo: Boolean) {
        val active = activeSessions[activeChatKey] ?: return

        // Eğer o an gelen bir arama varsa direkt aramanın içine sok
        if (active.isIncomingCall && active.callIntent != null) {
            try {
                active.callIntent?.send()
                collapseChat()
                return
            } catch (_: Exception) {}
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

        // Instagram veya rehberde eşleşmeyen numara ise direkt sohbet ekranına at
        Toast.makeText(
            this,
            if (isVideo) "Görüntülü arama için sohbete geçiliyor..." else "Sesli arama için sohbete geçiliyor...",
            Toast.LENGTH_SHORT
        ).show()
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

    // ==================== MESSENGER TARZI VEKTÖREL ARAMA LOGOLARI ====================
    private fun createMessengerVectorIcon(isVideo: Boolean, onClick: () -> Unit): ImageView {
        val sizePx = dpToPx(36)
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0084FF")
            style = Paint.Style.FILL
        }

        val w = sizePx.toFloat()
        if (isVideo) {
            // Messenger Görüntülü Arama Kamera Logosu
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
            // Messenger Sesli Arama Ahize Logosu
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
                rightMargin = dpToPx(6)
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
            val slice = if (messages.size > 80) messages.takeLast(80) else messages
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
        for (incoming in messagesBatch) {
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
                        // Eğer aktif bir gelen arama varsa balona tıklayınca direkt aramanın içine at!
                        val callingSession = activeSessions.values.firstOrNull { it.isIncomingCall && it.callIntent != null }
                        if (callingSession != null) {
                            try {
                                callingSession.callIntent?.send()
                                callingSession.isIncomingCall = false
                                refreshBubbleAppearance(callingSession)
                                return@setOnTouchListener true
                            } catch (_: Exception) {}
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

        // Gelen arama varsa çerçeveyi yeşil yap ve telefon ikonu göster
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

    // ==================== 2. AÇIK MESSENGER SOHBET PENCERESİ ====================
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
                    if (extraToolPanel?.visibility == View.VISIBLE) {
                        extraToolPanel?.visibility = View.GONE
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

        val cardWidth = if (isLandscape) {
            min(dpToPx(440), (metrics.widthPixels * 0.65f).toInt())
        } else {
            WindowManager.LayoutParams.MATCH_PARENT
        }

        val cardHeight = if (isLandscape) {
            (metrics.heightPixels * 0.74f).toInt()
        } else {
            (metrics.heightPixels * 0.50f).toInt()
        }

        val mainColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                cardWidth,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply {
                topMargin = if (isLandscape) dpToPx(8) else dpToPx(34)
                if (!isLandscape) {
                    leftMargin = dpToPx(12)
                    rightMargin = dpToPx(12)
                }
            }
            isClickable = true
        }

        // --- ÜST BÖLÜM ---
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                if (isLandscape) dpToPx(46) else dpToPx(58)
            ).apply { bottomMargin = dpToPx(6) }
        }

        topHeadsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val minimizePill = TextView(this).apply {
            text = "Küçült ⬇"
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#3A3B3C"))
                cornerRadius = dpToPx(18).toFloat()
            }
            setOnClickListener { collapseChat() }
        }

        val closeAllPill = TextView(this).apply {
            text = "✕"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            val btnSize = if (isLandscape) dpToPx(34) else dpToPx(38)
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                leftMargin = dpToPx(8)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#FF3B30"))
            }
            setOnClickListener {
                activeSessions.clear()
                destroyAllOverlays()
            }
        }

        topBar.addView(topHeadsContainer)
        topBar.addView(minimizePill)
        topBar.addView(closeAllPill)

        // --- ORTA BÖLÜM: SOHBET KARTI ---
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

        val vPad = if (isLandscape) dpToPx(6) else dpToPx(10)
        val cardHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(12), vPad, dpToPx(12), vPad)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#242526"))
                cornerRadii = floatArrayOf(
                    dpToPx(22).toFloat(), dpToPx(22).toFloat(),
                    dpToPx(22).toFloat(), dpToPx(22).toFloat(),
                    0f, 0f, 0f, 0f
                )
            }
        }

        val avSize = if (isLandscape) dpToPx(34) else dpToPx(40)
        val headerAvatarBox = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(avSize, avSize)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#0084FF"))
            }
            // Profile tıklayınca orijinal uygulamayı aç
            setOnClickListener { openOriginalApp() }
        }

        headerInitialsTxt = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            gravity = Gravity.CENTER
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }

        headerAvatarImg = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(avSize, avSize)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }

        headerAvatarBox.addView(headerInitialsTxt)
        headerAvatarBox.addView(headerAvatarImg)

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dpToPx(10)
            }
            setOnClickListener { openOriginalApp() }
        }

        headerNameTxt = TextView(this).apply {
            textSize = if (isLandscape) 14f else 15f
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

        // Messenger Tarzı Sesli Arama ve Görüntülü Arama Vektör Logoları
        val voiceCallIcon = createMessengerVectorIcon(isVideo = false) {
            initiateCall(isVideo = false)
        }

        val videoCallIcon = createMessengerVectorIcon(isVideo = true) {
            initiateCall(isVideo = true)
        }

        val openAppBtn = TextView(this).apply {
            text = "↗"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#4599FF"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dpToPx(34), dpToPx(34))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#263951"))
            }
            setOnClickListener { openOriginalApp() }
        }

        cardHeader.addView(headerAvatarBox)
        cardHeader.addView(titleCol)
        cardHeader.addView(voiceCallIcon)
        cardHeader.addView(videoCallIcon)
        cardHeader.addView(openAppBtn)

        // Gelen Arama Olduğunda Çıkan Yeşil Katılma Çubuğu
        incomingCallBanner = TextView(this).apply {
            text = "📞 GELEN ARAMA VAR • Katılmak İçin Dokun"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8))
            setBackgroundColor(Color.parseColor("#00C853"))
            visibility = View.GONE
            setOnClickListener {
                val active = activeSessions[activeChatKey]
                try {
                    active?.callIntent?.send() ?: active?.contentIntent?.send()
                    active?.isIncomingCall = false
                    collapseChat()
                } catch (_: Exception) {
                    openOriginalApp()
                }
            }
        }

        // Mesaj Listesi
        messagesScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6))
        }

        messagesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }
        messagesScroll?.addView(messagesContainer)

        extraToolPanel = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setBackgroundColor(Color.parseColor("#202124"))
            visibility = View.GONE
        }

        // --- ALT ÇUBUK ---
        val inputBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(8), vPad, dpToPx(8), vPad)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#242526"))
                cornerRadii = floatArrayOf(
                    0f, 0f, 0f, 0f,
                    dpToPx(22).toFloat(), dpToPx(22).toFloat(),
                    dpToPx(22).toFloat(), dpToPx(22).toFloat()
                )
            }
        }

        val emojiBtn = createActionIconButton("😀") { toggleEmojiPanel() }
        val photoBtn = createActionIconButton("📷") { togglePhotoPickerPanel() }

        replyEdit = EditText(this).apply {
            val h = if (isLandscape) dpToPx(38) else dpToPx(42)
            layoutParams = LinearLayout.LayoutParams(0, h, 1f).apply {
                leftMargin = dpToPx(4)
                rightMargin = dpToPx(4)
            }
            hint = "Mesaj yaz..."
            setHintTextColor(Color.parseColor("#8A8D91"))
            setTextColor(Color.WHITE)
            textSize = 14f
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEND
            setPadding(dpToPx(14), 0, dpToPx(14), 0)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#3A3B3C"))
                cornerRadius = dpToPx(21).toFloat()
            }
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    sendMessageNow()
                    true
                } else false
            }
        }

        val voiceBtn = createActionIconButton("🎤") { toggleVoicePanel() }

        val sendCircleBtn = TextView(this).apply {
            text = "➤"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            val s = if (isLandscape) dpToPx(38) else dpToPx(42)
            layoutParams = LinearLayout.LayoutParams(s, s).apply {
                leftMargin = dpToPx(4)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#0084FF"))
            }
            setOnClickListener { sendMessageNow() }
        }

        inputBar.addView(emojiBtn)
        inputBar.addView(photoBtn)
        inputBar.addView(replyEdit)
        inputBar.addView(voiceBtn)
        inputBar.addView(sendCircleBtn)

        chatCard.addView(cardHeader)
        chatCard.addView(incomingCallBanner)
        chatCard.addView(messagesScroll)
        chatCard.addView(extraToolPanel)
        chatCard.addView(inputBar)

        mainColumn.addView(topBar)
        mainColumn.addView(chatCard)

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

    private fun createActionIconButton(symbol: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = symbol
            textSize = 17f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dpToPx(36), dpToPx(36)).apply {
                rightMargin = dpToPx(2)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#3A3B3C"))
            }
            setOnClickListener { onClick() }
        }
    }

    private fun toggleEmojiPanel() {
        val panel = extraToolPanel ?: return
        if (panel.visibility == View.VISIBLE && panel.tag == "EMOJI") {
            panel.visibility = View.GONE
            return
        }
        panel.removeAllViews()
        panel.tag = "EMOJI"

        val emojis = listOf(
            "😂", "❤️", "👍", "🔥", "😍", "🙏", "😭", "😎",
            "🤔", "👏", "🎉", "💯", "🥰", "🤣", "😅", "👌",
            "💪", "🙌", "😘", "👀", "😡", "😱", "🌹", "✨"
        )

        val scroll = HorizontalScrollView(this).apply {
            setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8))
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        emojis.forEach { emo ->
            val tv = TextView(this).apply {
                text = emo
                textSize = 22f
                setPadding(dpToPx(10), dpToPx(4), dpToPx(10), dpToPx(4))
                setOnClickListener { replyEdit?.append(emo) }
            }
            row.addView(tv)
        }

        scroll.addView(row)
        panel.addView(scroll)
        panel.visibility = View.VISIBLE
    }

    private fun togglePhotoPickerPanel() {
        val panel = extraToolPanel ?: return
        if (panel.visibility == View.VISIBLE && panel.tag == "PHOTO") {
            panel.visibility = View.GONE
            return
        }
        panel.removeAllViews()
        panel.tag = "PHOTO"

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8))
        }

        val recentUris = getRecentGalleryPhotos(10)
        if (recentUris.isEmpty()) {
            val info = TextView(this).apply {
                text = "Galeriden fotoğraf seçmek için SuperBubble ana ekranından 3. İzin butonuna bas kanka."
                textSize = 12f
                setTextColor(Color.LTGRAY)
            }
            container.addView(info)
        } else {
            val hint = TextView(this).apply {
                text = "📷 Göndermek istediğin fotoğrafa dokun:"
                textSize = 11.5f
                setTextColor(Color.LTGRAY)
                setPadding(0, 0, 0, dpToPx(6))
            }
            container.addView(hint)

            val hScroll = HorizontalScrollView(this)
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

            recentUris.forEach { uri ->
                val bmp = loadBitmapFromUri(uri)
                if (bmp != null) {
                    val img = ImageView(this).apply {
                        layoutParams = LinearLayout.LayoutParams(dpToPx(68), dpToPx(68)).apply {
                            rightMargin = dpToPx(8)
                        }
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setImageBitmap(bmp)
                        setOnClickListener { sharePhotoToActiveApp(uri) }
                    }
                    row.addView(img)
                }
            }
            hScroll.addView(row)
            container.addView(hScroll)
        }

        panel.addView(container)
        panel.visibility = View.VISIBLE
    }

    private fun sharePhotoToActiveApp(uri: Uri) {
        val active = activeSessions[activeChatKey] ?: return
        try {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                setPackage(active.pkgName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            collapseChat()
            startActivity(shareIntent)
        } catch (_: Exception) {
            openOriginalApp()
        }
    }

    private fun toggleVoicePanel() {
        val panel = extraToolPanel ?: return
        if (panel.visibility == View.VISIBLE && panel.tag == "VOICE") {
            panel.visibility = View.GONE
            return
        }
        panel.removeAllViews()
        panel.tag = "VOICE"

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8))
        }

        val voiceToTextBtn = TextView(this).apply {
            text = "🎙️ Konuşarak Yazdır"
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dpToPx(6)
            }
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0084FF"))
                cornerRadius = dpToPx(14).toFloat()
            }
            setOnClickListener { startVoiceDictation() }
        }

        val directVoiceNoteBtn = TextView(this).apply {
            text = "🔊 Ses Kaydı At ↗"
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#00A884"))
                cornerRadius = dpToPx(14).toFloat()
            }
            setOnClickListener { openOriginalApp() }
        }

        row.addView(voiceToTextBtn)
        row.addView(directVoiceNoteBtn)
        panel.addView(row)
        panel.visibility = View.VISIBLE
    }

    private fun startVoiceDictation() {
        replyEdit?.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(replyEdit, InputMethodManager.SHOW_IMPLICIT)
        Toast.makeText(
            this,
            "Klavyenin üstündeki mikrofon 🎙️ simgesine basıp konuş kanka!",
            Toast.LENGTH_SHORT
        ).show()
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
            ).apply { topMargin = dpToPx(30) }
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
            if (active?.isIncomingCall == true && active.callIntent != null) {
                active.callIntent?.send()
                collapseChat()
                return
            }
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

    private fun isPhotoMessageText(txt: String): Boolean {
        val lower = txt.lowercase(Locale.getDefault())
        return lower.contains("📷") || lower.contains("fotoğraf") || lower.contains("photo") || lower.contains("resim") || lower.contains("görsel")
    }

    private fun isVoiceMessageText(txt: String): Boolean {
        val lower = txt.lowercase(Locale.getDefault())
        return lower.contains("🎤") || lower.contains("sesli mesaj") || lower.contains("voice message") || lower.contains("ses gönderdi")
    }

    private fun isReelOrVideoText(txt: String): Boolean {
        val lower = txt.lowercase(Locale.getDefault())
        return lower.contains("reel") || lower.contains("video gönderdi") ||
                lower.contains("instagram.com/reel") || lower.contains("bir gönderi paylaştı")
    }

    private fun refreshExpandedUI() {
        if (!isExpanded || expandedOverlayView == null) return

        val active = activeSessions[activeChatKey] ?: activeSessions.values.firstOrNull() ?: return
        activeChatKey = active.key
        active.unreadCount = 0

        if (!active.isIncomingCall) {
            scheduleCancelNotification(active.sbnKey, 150)
        }

        val isLandscape = resources.displayMetrics.widthPixels > resources.displayMetrics.heightPixels
        val headDim = if (isLandscape) dpToPx(42) else dpToPx(52)

        topHeadsContainer?.removeAllViews()
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
                        refreshExpandedUI()
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
        // "Geçmiş Kayıtlı" yazısı kaldırıldı, sadece uygulama adı veya arama durumu yazar
        headerSubTxt?.text = if (active.isIncomingCall) "📞 Gelen Arama..." else active.appName
        headerSubTxt?.setTextColor(
            Color.parseColor(if (active.appName == "Instagram") "#E1306C" else "#25D366")
        )

        incomingCallBanner?.visibility = if (active.isIncomingCall) View.VISIBLE else View.GONE

        messagesContainer?.removeAllViews()
        active.messages.forEach { msg ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dpToPx(5) }
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

            val bmp = base64ToBitmap(msg.imageBase64)
            if (bmp != null) {
                val photoView = ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dpToPx(200), dpToPx(135)).apply {
                        bottomMargin = dpToPx(6)
                    }
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageBitmap(bmp)
                    setOnClickListener { showFullScreenPhoto(bmp) }
                }
                bubbleBox.addView(photoView)
            } else if (!msg.isMe && isPhotoMessageText(msg.text)) {
                val openPhotoBtn = TextView(this).apply {
                    text = "📷 Fotoğrafı Gör ↗"
                    textSize = 12.5f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dpToPx(6) }
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#0084FF"))
                        cornerRadius = dpToPx(14).toFloat()
                    }
                    setOnClickListener { openOriginalApp() }
                }
                bubbleBox.addView(openPhotoBtn)
            }

            val msgTxt = TextView(this).apply {
                text = msg.text
                textSize = 14f
                setTextColor(Color.WHITE)
                maxWidth = dpToPx(240)
            }
            bubbleBox.addView(msgTxt)

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

        recentSentTexts[text] = System.currentTimeMillis()
        extraToolPanel?.visibility = View.GONE

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

            scheduleCancelNotification(active.sbnKey, 450)
        } catch (_: PendingIntent.CanceledException) {
            Toast.makeText(
                this,
                "Bildirim süresi dolmuş, sağ üstten '↗' tuşuna basabilirsin kanka.",
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
        extraToolPanel = null

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
        extraToolPanel = null

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

// ==================== UYGULAMA İÇİ SOHBET GEÇMİŞİNİ EŞİTLEYEN ERİŞİLEBİLİRLİK SERVİSİ ====================
class ChatSyncService : AccessibilityService() {

    private var lastSyncTime = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        if (!BubbleService.TARGET_PACKAGES.contains(pkg)) return

        val now = System.currentTimeMillis()
        if (now - lastSyncTime < 900) return
        lastSyncTime = now

        val root = rootInActiveWindow ?: return
        try {
            val screenWidth = resources.displayMetrics.widthPixels

            // 1. WhatsApp / Instagram üst başlığındaki kişi adını bul
            val titleNode = findHeaderTitle(root, pkg) ?: return
            val chatTitle = titleNode.trim()
            if (chatTitle.isEmpty()) return

            // 2. Ekrandaki görünür mesaj balonlarını (Konumu sağdaysa bizim, soldaysa karşı tarafın) topla
            val messages = mutableListOf<Pair<String, Boolean>>()
            collectMessagesFromNode(root, pkg, screenWidth, messages)

            if (messages.isNotEmpty()) {
                BubbleService.instance?.syncFromAppScreen(pkg, chatTitle, messages)
            }
        } catch (_: Exception) {}
    }

    private fun findHeaderTitle(root: AccessibilityNodeInfo, pkg: String): String? {
        val idCandidates = if (pkg.contains("whatsapp")) {
            listOf("com.whatsapp:id/conversation_contact_name", "com.whatsapp.w4b:id/conversation_contact_name")
        } else {
            listOf("com.instagram.android:id/thread_title", "com.instagram.android:id/action_bar_title")
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

    private fun collectMessagesFromNode(
        root: AccessibilityNodeInfo,
        pkg: String,
        screenWidth: Int,
        outList: MutableList<Pair<String, Boolean>>
    ) {
        if (pkg.contains("whatsapp")) {
            val idList = listOf("com.whatsapp:id/message_text", "com.whatsapp.w4b:id/message_text")
            for (id in idList) {
                val nodes = root.findAccessibilityNodeInfosByViewId(id) ?: continue
                for (node in nodes) {
                    val txt = node.text?.toString()?.trim() ?: continue
                    if (txt.isEmpty()) continue
                    val rect = Rect()
                    node.getBoundsInScreen(rect)
                    // Mesaj kutusu ekranın sağ yarısına yakınsa gönderen biziz (isMe = true)
                    val isMe = rect.centerX() > (screenWidth * 0.50f) || rect.left > (screenWidth * 0.22f)
                    outList.add(Pair(txt, isMe))
                }
            }
        }
    }

    override fun onInterrupt() {}
}
