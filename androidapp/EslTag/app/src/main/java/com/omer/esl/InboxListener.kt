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

    private fun isSupportedPackage(pkg: String): Boolean {
        return pkg.startsWith("com.whatsapp") ||
            pkg == "com.samsung.android.app.reminder" ||
            pkg == "com.samsung.android.reminder" ||
            pkg.contains("reminder", ignoreCase = true)
    }

    override fun onListenerConnected() {
        Inbox.init(this)
        for (s in activeNotifications ?: emptyArray()) handle(s)
        if (Inbox.hasContent()) Pipeline.schedule(this, Kind.CHAT)
    }

    override fun onNotificationPosted(s: StatusBarNotification) {
        handle(s)?.let { Pipeline.schedule(this, it) }
    }

    override fun onNotificationRemoved(s: StatusBarNotification) {
        handleRemoved(s, REASON_CANCEL)
    }

    override fun onNotificationRemoved(s: StatusBarNotification, rankingMap: RankingMap, reason: Int) {
        handleRemoved(s, reason)
    }

    private fun handleRemoved(s: StatusBarNotification, reason: Int) {
        if (!isSupportedPackage(s.packageName)) return
        
        // If app internally cancelled/replaced the notification (REASON_APP_CANCEL = 8),
        // keep the item in the inbox so it isn't deleted before upload!
        if (reason == REASON_APP_CANCEL || reason == 12 /* REASON_GROUP_SUMMARY_CANCELED */) {
            return
        }

        val title = s.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: s.notification.extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()

        var changed = false
        if (Inbox.clearRingingKey(s.key)) changed = true
        if (Inbox.removeChat(s.key, title)) changed = true
        if (Inbox.removeMissed(title)) changed = true

        if (changed) {
            Pipeline.schedule(this, Kind.CHAT)
        }
    }

    @Suppress("DEPRECATION")
    private fun handle(s: StatusBarNotification): Kind? {
        if (!isSupportedPackage(s.packageName)) return null
        val n = s.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        val rawTitle = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: n.extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            ?: s.notification.tickerText?.toString()
            ?: return null

        val isReminder = s.packageName.contains("reminder", ignoreCase = true)
        val title = if (isReminder) "Reminder: $rawTitle" else rawTitle

        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: ""
        val cfg = Config.load(this)
        val low = text.lowercase()
        val now = System.currentTimeMillis()
        when {
            missedWords.any { low.contains(it) } -> {
                if (!cfg.showMissed) return null
                Inbox.addMissed(Missed(title, "whatsapp", now))
                return Kind.CALL
            }
            incoming.any { low.contains(it) } -> {
                if (!cfg.showCalls) return null
                Inbox.ring(Ringing(title, "whatsapp", s.key, now))
                return Kind.CALL
            }
            n.category == Notification.CATEGORY_CALL -> return null     // ongoing call
        }
        if (!cfg.showMessages) return null
        if (!cfg.matches(this, title, text)) return null
        val count = (n.extras.getParcelableArray(Notification.EXTRA_MESSAGES)?.size ?: 1).coerceAtLeast(1)
        Inbox.setChat(s.key, Chat(title, count, if (cfg.showText) text else "", s.postTime))
        EslStats.incMessages(this)
        return Kind.CHAT
    }
}
