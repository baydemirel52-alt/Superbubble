package com.selim.superbubble

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.StatusBarNotification
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

sealed class ParsedNotification {
    data class RemoteReplyConfirmation(
        val chatKey: String,
        val sbnKey: String,
        val confirmedTexts: List<String>
    ) : ParsedNotification()

    data class IncomingCall(
        val chatKey: String,
        val sbnKey: String,
        val pkgName: String,
        val sender: String,
        val appName: String,
        val callDescription: String,
        val avatar: Bitmap?,
        val contentIntent: PendingIntent?,
        val callIntent: PendingIntent?
    ) : ParsedNotification()

    data class IncomingMessages(
        val chatKey: String,
        val sbnKey: String,
        val shortcutId: String?,
        val whatsappJid: String?,
        val pkgName: String,
        val sender: String,
        val appName: String,
        val messages: List<ChatMessage>,
        val avatar: Bitmap?,
        val replyAction: Notification.Action?,
        val contentIntent: PendingIntent?,
        val needsWhatsAppMediaFallback: Boolean,
        val postTimeSec: Long
    ) : ParsedNotification()
}

object NotificationParser {

    fun cleanConversationTitle(convTitle: String?, rawTitle: String): String {
        val base = if (!convTitle.isNullOrBlank()) {
            convTitle.trim()
        } else {
            rawTitle.trim()
        }
        return base
            .replace(Regex("\\s*\\(\\d+\\s*mesaj\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\(\\d+\\s*new messages\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\(\\d+\\s*messages\\)", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    fun buildChatKey(pkg: String, shortcutId: String?, tag: String?, cleanTitle: String): String {
        val uniquePart = when {
            !shortcutId.isNullOrBlank() -> shortcutId.trim()
            !tag.isNullOrBlank() -> tag.trim()
            else -> cleanTitle.lowercase(Locale.ROOT)
        }
        return "${pkg}_$uniquePart"
    }

    fun buildMessageId(timestamp: Long, sender: String, isMe: Boolean, text: String): String {
        val normSender = sender.trim().lowercase(Locale.ROOT)
        return "${timestamp}_${normSender}_${if (isMe) 1 else 0}_${text.hashCode()}"
    }

    fun isOngoingCallNotification(category: String?, flags: Int, hasFullScreenIntent: Boolean): Boolean {
        if (category != Notification.CATEGORY_CALL) return false
        val isOngoing = (flags and Notification.FLAG_ONGOING_EVENT) != 0
        return isOngoing || hasFullScreenIntent
    }

    fun stableNotificationTime(notifWhen: Long, postTime: Long): Long {
        return if (notifWhen > 0L) notifWhen else postTime
    }

    /**
     * Bubble'dan gönderdiğimiz mesajı (anında ekrana eklenen, zamanı bizim saatimiz) ile
     * WhatsApp'ın sonradan bildirimde listelediği AYNI mesajı eşleştirir.
     * Zamanlar birebir tutmaz, o yüzden "aynı metin + 30 sn içinde" kuralı kullanılır.
     */
    fun isSameOwnMessage(textA: String, timeA: Long, textB: String, timeB: Long): Boolean {
        return textA == textB && kotlin.math.abs(timeA - timeB) <= 30_000L
    }

    /**
     * Uygulama ekranında görünen mesajlardan, bubble'ın henüz bilmediği YENİ olanları bulur.
     * known: bubble'daki mesajlar (eskiden yeniye), screen: ekrandaki mesajlar (yukarıdan aşağıya).
     * Mantık: bubble'ın son N mesajının sonundan başlayan en uzun diziyi ekranda ararız,
     * eşleşmenin SONRASINDAKİLER yenidir. Eşleşme yoksa hiçbir şey eklenmez (yanlış sıraya eklemektense eklememek).
     */
    fun findNewScreenMessages(
        known: List<Pair<String, Boolean>>,
        screen: List<Pair<String, Boolean>>
    ): List<Pair<String, Boolean>> {
        if (known.isEmpty() || screen.isEmpty()) return emptyList()
        val tail = known.takeLast(40)
        val maxLen = minOf(tail.size, screen.size)
        for (len in maxLen downTo 1) {
            val suffix = tail.subList(tail.size - len, tail.size)
            var start = screen.size - len
            while (start >= 0) {
                var same = true
                for (k in 0 until len) {
                    if (screen[start + k] != suffix[k]) {
                        same = false
                        break
                    }
                }
                if (same) return screen.subList(start + len, screen.size)
                start--
            }
        }
        return emptyList()
    }

    fun isSelfSender(sender: String?, selfDisplayName: String?): Boolean {
        if (sender.isNullOrBlank()) return false
        val s = sender.trim()
        return s.equals("Siz", ignoreCase = true) ||
                s.equals("Sen", ignoreCase = true) ||
                s.equals("You", ignoreCase = true) ||
                (!selfDisplayName.isNullOrBlank() && s.equals(selfDisplayName.trim(), ignoreCase = true))
    }

    fun extractWhatsAppJid(shortcutId: String?, tag: String?): String? {
        val candidates = listOfNotNull(shortcutId, tag)
        for (c in candidates) {
            if (c.contains("@s.whatsapp.net") || c.contains("@g.us")) {
                return c.trim()
            }
        }
        return null
    }

    fun resolveAppName(pkg: String): String {
        return when {
            pkg.contains("instagram") -> "Instagram"
            pkg.contains("telegram") -> "Telegram"
            pkg == "com.whatsapp.w4b" -> "WA Business"
            else -> "WhatsApp"
        }
    }

    fun formatTime(timestampMs: Long): String {
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestampMs))
    }

    @Suppress("DEPRECATION")
    fun parse(context: Context, sbn: StatusBarNotification, repository: ChatRepository): ParsedNotification? {
        val pkg = sbn.packageName
        val notif = sbn.notification
        if ((notif.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return null

        val extras = notif.extras
        val convTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
        val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return null
        val cleanTitle = cleanConversationTitle(convTitle, rawTitle)

        val shortcutId = if (Build.VERSION.SDK_INT >= 26) notif.shortcutId else null
        val chatKey = buildChatKey(pkg, shortcutId, sbn.tag, cleanTitle)
        val whatsappJid = if (pkg.contains("whatsapp")) extractWhatsAppJid(shortcutId, sbn.tag) else null
        val appName = resolveAppName(pkg)

        // 1. RemoteInput onay bildirimi mi? (Çift tik / İletildi onayı)
        val remoteHistory = extras.getCharSequenceArray(Notification.EXTRA_REMOTE_INPUT_HISTORY)
        if (!remoteHistory.isNullOrEmpty()) {
            val confirmed = remoteHistory.mapNotNull { it?.toString()?.trim() }.filter { it.isNotEmpty() }
            return ParsedNotification.RemoteReplyConfirmation(
                chatKey = chatKey,
                sbnKey = sbn.key,
                confirmedTexts = confirmed
            )
        }

        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()
            ?: ""

        val avatar = extractAvatar(context, notif)

        // 2. Gerçek Gelen Arama Kontrolü (Sadece CATEGORY_CALL + Ongoing/FullScreen)
        if (isOngoingCallNotification(notif.category, notif.flags, notif.fullScreenIntent != null)) {
            val targetCallIntent = findAcceptCallIntent(notif) ?: notif.fullScreenIntent ?: notif.contentIntent
            val callDesc = if (text.isNotEmpty()) "📞 $text" else "📞 Gelen Arama..."
            return ParsedNotification.IncomingCall(
                chatKey = chatKey,
                sbnKey = sbn.key,
                pkgName = pkg,
                sender = cleanTitle,
                appName = appName,
                callDescription = callDesc,
                avatar = avatar,
                contentIntent = notif.contentIntent,
                callIntent = targetCallIntent
            )
        }

        // 3. Sistem/Özet Bildirimlerini ve Kendi Mesajlarımızı Filtrele
        val selfName = extras.getCharSequence(Notification.EXTRA_SELF_DISPLAY_NAME)?.toString()?.trim()
        // Not: Eskiden metinde "WhatsApp Web" geçen HER mesaj atılıyordu (biri "whatsapp web açılmıyor" yazsa kaybolurdu).
        // Artık sadece sistemin kendi bildirimleri (başlığı/metni baştan eşleşenler) elenir.
        if (text.isEmpty() ||
            text.startsWith("Yeni mesajlar kontrol ediliyor", ignoreCase = true) ||
            text.startsWith("Checking for new messages", ignoreCase = true) ||
            cleanTitle.equals("WhatsApp Web", ignoreCase = true) ||
            cleanTitle.equals("WhatsApp", ignoreCase = true) ||
            cleanTitle.equals("Instagram", ignoreCase = true) ||
            cleanTitle.equals("Telegram", ignoreCase = true) ||
            isSelfSender(cleanTitle, selfName)
        ) {
            return null
        }

        if (text.startsWith("Siz:") || text.startsWith("Sen:") || text.startsWith("You:")) {
            return null
        }

        val isGroup = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false) || !convTitle.isNullOrBlank()
        var notifPhotoBmp: Bitmap? = extractPhotoFromNotification(context, extras)
        val extractedBatch = mutableListOf<ChatMessage>()

        val historicBundles = extras.getParcelableArray(Notification.EXTRA_HISTORIC_MESSAGES)
        val msgBundles = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        val allBundles = mutableListOf<Parcelable>()
        if (historicBundles != null) allBundles.addAll(historicBundles)
        if (msgBundles != null) allBundles.addAll(msgBundles)

        if (allBundles.isNotEmpty()) {
            val lastBundle = allBundles.last() as? Bundle
            val lastSender = lastBundle?.getCharSequence("sender")?.toString()?.trim()
            if (isSelfSender(lastSender, selfName)) {
                return ParsedNotification.RemoteReplyConfirmation(chatKey, sbn.key, emptyList())
            }

            for (i in allBundles.indices) {
                val b = allBundles[i] as? Bundle ?: continue
                val bSender = b.getCharSequence("sender")?.toString()?.trim()
                val bText = b.getCharSequence("text")?.toString()?.trim() ?: continue
                val bTime = b.getLong("time", 0L).takeIf { it > 0L } ?: (stableNotificationTime(notif.`when`, sbn.postTime) + i)
                val isFromMe = isSelfSender(bSender, selfName)

                var itemImagePath: String? = null
                var itemAudio: String? = null
                val mime = b.getString("type") ?: ""
                val u = b.getParcelable<Parcelable>("uri") as? Uri
                if (u != null) {
                    if (mime.startsWith("image/")) {
                        val bmp = repository.loadBitmapFromUri(context, u)
                        if (bmp != null) {
                            itemImagePath = repository.saveBitmapToFile(context, bmp)
                            if (notifPhotoBmp == null) notifPhotoBmp = bmp
                        }
                    } else if (mime.startsWith("audio/")) {
                        itemAudio = u.toString()
                    }
                }

                val groupSender = if (!isFromMe && bSender != null && !bSender.equals(cleanTitle, ignoreCase = true)) {
                    bSender
                } else null

                val msgId = buildMessageId(bTime, bSender ?: cleanTitle, isFromMe, bText)
                extractedBatch.add(
                    ChatMessage(
                        id = msgId,
                        chatKey = chatKey,
                        senderName = groupSender,
                        text = bText,
                        isMe = isFromMe,
                        time = formatTime(bTime),
                        timestamp = bTime,
                        imagePath = itemImagePath,
                        audioUri = itemAudio,
                        isDelivered = isFromMe
                    )
                )
            }
        }

        val savedNotifPhotoPath = if (notifPhotoBmp != null) {
            repository.saveBitmapToFile(context, notifPhotoBmp)
        } else null

        if (extractedBatch.isEmpty()) {
            var groupSender: String? = null
            var actualText = text
            if (isGroup && rawTitle.contains(":")) {
                groupSender = rawTitle.substringAfter(":").trim().takeIf { it.isNotEmpty() }
            } else if (!convTitle.isNullOrBlank() && !rawTitle.equals(cleanTitle, ignoreCase = true)) {
                groupSender = rawTitle.trim()
            } else if (text.contains(": ") && isGroup) {
                val candidate = text.substringBefore(": ").trim()
                if (candidate.length in 2..30) {
                    groupSender = candidate
                    actualText = text.substringAfter(": ").trim()
                }
            }

            // postTime bildirim her güncellendiğinde değişir -> aynı mesaj tekrar tekrar eklenirdi.
            // Notification.when çoğu uygulamada mesajın kendi zamanıdır ve güncellemede sabit kalır.
            val msgTime = stableNotificationTime(notif.`when`, sbn.postTime)
            val msgId = buildMessageId(msgTime, groupSender ?: cleanTitle, false, actualText)
            extractedBatch.add(
                ChatMessage(
                    id = msgId,
                    chatKey = chatKey,
                    senderName = groupSender,
                    text = actualText,
                    isMe = false,
                    time = formatTime(msgTime),
                    timestamp = msgTime,
                    imagePath = savedNotifPhotoPath,
                    audioUri = null,
                    isDelivered = false
                )
            )
        } else if (savedNotifPhotoPath != null && extractedBatch.last().imagePath == null) {
            extractedBatch[extractedBatch.lastIndex] = extractedBatch.last().copy(imagePath = savedNotifPhotoPath)
        }

        val lastMsgText = extractedBatch.last().text
        val needsWaFallback = pkg.contains("whatsapp") &&
                extractedBatch.last().imagePath == null &&
                isPhotoMessageText(lastMsgText)

        // Instagram veya yanıt butonu olmayan bildirimleri ATMIYORUZ (replyAction null olabilir)
        val replyAction = findReplyAction(notif)

        return ParsedNotification.IncomingMessages(
            chatKey = chatKey,
            sbnKey = sbn.key,
            shortcutId = shortcutId,
            whatsappJid = whatsappJid,
            pkgName = pkg,
            sender = cleanTitle,
            appName = appName,
            messages = extractedBatch,
            avatar = avatar,
            replyAction = replyAction,
            contentIntent = notif.contentIntent,
            needsWhatsAppMediaFallback = needsWaFallback,
            postTimeSec = sbn.postTime / 1000
        )
    }

    fun isPhotoMessageText(txt: String): Boolean {
        val lower = txt.lowercase(Locale.getDefault())
        return lower.contains("📷") || lower.contains("fotoğraf") || lower.contains("photo")
    }

    private fun findReplyAction(notif: Notification): Notification.Action? {
        notif.actions?.forEach { action ->
            if (!action.remoteInputs.isNullOrEmpty()) return action
        }
        return null
    }

    private fun findAcceptCallIntent(notif: Notification): PendingIntent? {
        notif.actions?.forEach { act ->
            val t = act.title?.toString()?.lowercase(Locale.getDefault()) ?: ""
            if (t.contains("cevapla") || t.contains("yanıtla") || t.contains("kabul") ||
                t.contains("answer") || t.contains("accept")
            ) {
                return act.actionIntent
            }
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun extractPhotoFromNotification(context: Context, extras: Bundle): Bitmap? {
        try {
            val picObj = extras.get(Notification.EXTRA_PICTURE)
            if (picObj is Bitmap) return picObj
            if (Build.VERSION.SDK_INT >= 23 && picObj is Icon) {
                iconToBitmap(context, picObj)?.let { return it }
            }
            val picIconObj = extras.get("android.pictureIcon")
            if (Build.VERSION.SDK_INT >= 23 && picIconObj is Icon) {
                iconToBitmap(context, picIconObj)?.let { return it }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun iconToBitmap(context: Context, icon: Icon): Bitmap? {
        return try {
            val d = icon.loadDrawable(context) ?: return null
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

    private fun extractAvatar(context: Context, notif: Notification): Bitmap? {
        return try {
            val icon = notif.getLargeIcon() ?: return null
            val drawable = icon.loadDrawable(context) ?: return null
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
}