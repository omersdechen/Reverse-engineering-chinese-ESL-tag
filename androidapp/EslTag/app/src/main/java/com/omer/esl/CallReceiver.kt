package com.omer.esl

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager

/** Phone-call state. Needs READ_PHONE_STATE + READ_CALL_LOG (the latter delivers the caller number). */
class CallReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(ctx: Context, i: Intent) {
        if (i.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val app = ctx.applicationContext
        when (i.getStringExtra(TelephonyManager.EXTRA_STATE)) {
            TelephonyManager.EXTRA_STATE_RINGING -> Inbox.onRing(app, i.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER))
            TelephonyManager.EXTRA_STATE_OFFHOOK -> Inbox.onOffhook()
            TelephonyManager.EXTRA_STATE_IDLE -> Inbox.onIdle(app)
        }
    }
}
