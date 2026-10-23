package com.omer.esl

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Sees WhatsApp notifications. Enable under Settings > Notification access. */
class InboxListener : NotificationListenerService() {
    private val incoming = listOf("incoming voice call", "incoming video call", "incoming call",
        "שיחה נכנסת", "שיחת וידאו נכנסת")
    private val missedWords = listOf("missed voice call", "missed video call", "missed call",
        "שיחה שלא נענתה", "שיחת וידאו שלא נענתה")

    override fun onListenerConnected() {
        Inbox.init(this)
        for (s in activeNotifications ?: emptyArray()) handle(s)
        if (Inbox.hasContent()) Pipeline.schedule(this, Kind.CHAT)
    }

    override fun onNotificationPosted(s: StatusBarNotification) {
        handle(s)?.let { Pipeline.schedule(this, it) }
    }

    override fun onNotificationRemoved(s: StatusBarNotification) {
        if (!s.packageName.startsWith("com.whatsapp")) return
        when {
            Inbox.clearRingingKey(s.key) -> Pipeline.schedule(this, Kind.CALL)   // call ended or answered
            Inbox.removeChat(s.key) -> Pipeline.schedule(this, Kind.CHAT)        // chat read or dismissed
        }
    }

    @Suppress("DEPRECATION")
    private fun handle(s: StatusBarNotification): Kind? {
        if (!s.packageName.startsWith("com.whatsapp")) return null
        val n = s.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return null
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val low = text.lowercase()
        val now = System.currentTimeMillis()
        when {
            missedWords.any { low.contains(it) } -> { Inbox.addMissed(Missed(title, "whatsapp", now)); return Kind.CALL }
            incoming.any { low.contains(it) } -> { Inbox.ring(Ringing(title, "whatsapp", s.key, now)); return Kind.CALL }
            n.category == Notification.CATEGORY_CALL -> return null     // ongoing call
        }
        val cfg = Config.load(this)
        if (!cfg.matches(title, text)) return null
        val count = (n.extras.getParcelableArray(Notification.EXTRA_MESSAGES)?.size ?: 1).coerceAtLeast(1)
        Inbox.setChat(s.key, Chat(title, count, if (cfg.showText) text else "", s.postTime))
        return Kind.CHAT
    }
}
