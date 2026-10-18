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

    @Test
    fun `ekranda sadece balonun bilmedigi yeni mesajlar bulunur`() {
        val known = listOf("selam" to false, "nasilsin" to false, "iyiyim" to true)
        val screen = listOf(
            "cok eski mesaj" to false,
            "selam" to false,
            "nasilsin" to false,
            "iyiyim" to true,
            "super" to false,
            "haha" to true
        )
        assertEquals(
            listOf("super" to false, "haha" to true),
            NotificationParser.findNewScreenMessages(known, screen)
        )
    }

    @Test
    fun `ekran ayniysa veya hic eslesme yoksa hicbir sey eklenmez`() {
        val known = listOf("selam" to false, "tamam" to true)
        val none = emptyList<Pair<String, Boolean>>()
        assertEquals(none, NotificationParser.findNewScreenMessages(known, known))
        assertEquals(none, NotificationParser.findNewScreenMessages(known, listOf("baska" to false)))
        assertEquals(none, NotificationParser.findNewScreenMessages(emptyList(), known))
    }

    @Test
    fun `ayni metinli yeni mesaj kaybolmaz`() {
        val known = listOf("selam" to false, "tamam" to false)
        val screen = listOf("selam" to false, "tamam" to false, "tamam" to false)
        assertEquals(
            listOf("tamam" to false),
            NotificationParser.findNewScreenMessages(known, screen)
        )
    }

    @Test
    fun `balondan yazilan mesaj bildirimde sonradan gelirse ayni mesaj sayilir`() {
        assertTrue(NotificationParser.isSameOwnMessage("ok", 1_000L, "ok", 9_000L))
        assertFalse(NotificationParser.isSameOwnMessage("ok", 1_000L, "ok", 40_000L))
        assertFalse(NotificationParser.isSameOwnMessage("ok", 1_000L, "tamam", 2_000L))
    }
}