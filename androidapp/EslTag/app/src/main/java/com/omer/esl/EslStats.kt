package com.omer.esl

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.app.KeyguardManager
import android.os.PowerManager

object EslStats {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("esl_stats", Context.MODE_PRIVATE)

    fun isUserOnPhone(ctx: Context): Boolean {
        return try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            pm.isInteractive && !km.isKeyguardLocked
        } catch (_: Exception) {
            false
        }
    }

    fun incMessages(ctx: Context) {
        val p = prefs(ctx)
        p.edit().putInt("msg_count", p.getInt("msg_count", 0) + 1).apply()
    }

    fun incDisplayChanges(ctx: Context) {
        val p = prefs(ctx)
        p.edit().putInt("display_changes", p.getInt("display_changes", 0) + 1).apply()
    }

    fun incPreventedScreenOn(ctx: Context) {
        val p = prefs(ctx)
        p.edit().putInt("prevented_screen_on", p.getInt("prevented_screen_on", 0) + 1).apply()
    }

    fun incPreventedDuplicate(ctx: Context) {
        val p = prefs(ctx)
        p.edit().putInt("prevented_duplicate", p.getInt("prevented_duplicate", 0) + 1).apply()
    }

    fun getBatteryInfo(ctx: Context): String {
        return try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus: Intent? = ctx.registerReceiver(null, filter)
            val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val pct = if (level >= 0 && scale > 0) (level * 100 / scale.toFloat()).toInt() else -1

            val status: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL

            val stateStr = if (isCharging) "Charging ⚡" else "Discharging 🔋"
            if (pct >= 0) "$pct% ($stateStr)" else "Unknown"
        } catch (_: Exception) {
            "Unknown"
        }
    }

    fun incPreventedNight(ctx: Context) {
        val p = prefs(ctx)
        p.edit().putInt("prevented_night", p.getInt("prevented_night", 0) + 1).apply()
    }

    fun getSummary(ctx: Context): String {
        val p = prefs(ctx)
        val msgs = p.getInt("msg_count", 0)
        val updates = p.getInt("display_changes", 0)
        val prevScreenOn = p.getInt("prevented_screen_on", 0)
        val prevDup = p.getInt("prevented_duplicate", 0)
        val prevNight = p.getInt("prevented_night", 0)
        val bat = getBatteryInfo(ctx)

        return "📊 Statistics & Battery:\n" +
                "• Phone Battery: $bat\n" +
                "• Messages Received: $msgs\n" +
                "• Tag Displays Updated: $updates\n" +
                "• Updates Saved (User Active on Phone): $prevScreenOn\n" +
                "• Updates Saved (Night Quiet Mode): $prevNight\n" +
                "• Updates Saved (Unchanged Content): $prevDup"
    }
}
