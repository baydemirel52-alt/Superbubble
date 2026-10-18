package com.selim.superbubble

import android.app.Notification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationParserTest {

    @Test
    fun `ayni metinli mesajlar farkli zamanlarda farkli id uretir`() {
        val id1 = NotificationParser.buildMessageId(1700000001000L, "Mertcan", false, "tamam")
        val id2 = NotificationParser.buildMessageId(1700000005000L, "Mertcan", false, "tamam")
        val idFromMe = NotificationParser.buildMessageId(1700000001000L, "Mertcan", true, "tamam")

        assertNotEquals(id1, id2)
        assertNotEquals(id1, idFromMe)
    }

    @Test
    fun `arama kontrolu sadece aktif CATEGORY_CALL bildirimlerini kabul eder`() {
        // Normal mesajda "seni arıyorum" yazması aramayı tetiklememeli
        assertFalse(
            NotificationParser.isOngoingCallNotification(
                category = Notification.CATEGORY_MESSAGE,
                flags = 0,
                hasFullScreenIntent = false
            )
        )

        // Cevapsız arama bildirimi (ongoing değil ve fullScreenIntent yok) takılı kalmamalı
        assertFalse(
            NotificationParser.isOngoingCallNotification(
                category = Notification.CATEGORY_CALL,
                flags = 0,
                hasFullScreenIntent = false
            )
        )

        // Gerçek çalan arama (CATEGORY_CALL + FLAG_ONGOING_EVENT)
        assertTrue(
            NotificationParser.isOngoingCallNotification(
                category = Notification.CATEGORY_CALL,
                flags = Notification.FLAG_ONGOING_EVENT,
                hasFullScreenIntent = false
            )
        )
    }

    @Test
    fun `grup basligi ve iki nokta iceren basliklar bozulmaz`() {
        val title = NotificationParser.cleanConversationTitle("Oyun Grubu: PS5 (3 mesaj)", "Oyun Grubu: PS5: Ali")
        assertEquals("Oyun Grubu: PS5", title)

        val keyWithShortcut = NotificationParser.buildChatKey(
            pkg = "com.whatsapp",
            shortcutId = "905551112233@s.whatsapp.net",
            tag = null,
            cleanTitle = "Ali"
        )
        assertEquals("com.whatsapp_905551112233@s.whatsapp.net", keyWithShortcut)
    }

    @Test
    fun `kendi mesajlarimiz dogru tespit edilir`() {
        assertTrue(NotificationParser.isSelfSender("Siz", null))
        assertTrue(NotificationParser.isSelfSender("Sen", null))
        assertTrue(NotificationParser.isSelfSender("You", null))
        assertTrue(NotificationParser.isSelfSender("Selim", "Selim"))
        assertFalse(NotificationParser.isSelfSender("Mehmet", "Selim"))
    }
}