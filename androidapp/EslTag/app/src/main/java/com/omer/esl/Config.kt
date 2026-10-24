package com.omer.esl

import android.content.Context
import android.provider.ContactsContract
import org.json.JSONObject
import java.io.File

/** Read from scripts/config.json on every use, so edits apply without a rebuild. */
data class Config(
    val people: List<String>,
    val showText: Boolean,
    val chatDelayS: Int,
    val chatMinGapS: Int,
    val weatherReturnS: Int,
    val chatTimeoutM: Int,
    val showCalls: Boolean,
    val showMessages: Boolean,
    val showMissed: Boolean,
    val showFavorites: Boolean,
    val compactWeather: Boolean,
    val nameMaxLen: Int,
    val nightQuietMode: Boolean,
    val nightStartHour: Int,
    val nightEndHour: Int
) {

    fun isNightHours(): Boolean {
        if (!nightQuietMode) return false
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return if (nightStartHour > nightEndHour) {
            hour >= nightStartHour || hour < nightEndHour
        } else {
            hour in nightStartHour until nightEndHour
        }
    }

    companion object {
        fun clean(s: String): String {
            return s.replace(Regex("[\\u200B-\\u200D\\u200E\\u200F\\u202A-\\u202E\\uFEFF]"), "").trim()
        }

        fun getStarredContacts(ctx: Context): Set<String> {
            val starred = mutableSetOf<String>()
            try {
                val cursor = ctx.contentResolver.query(
                    ContactsContract.Contacts.CONTENT_URI,
                    arrayOf(ContactsContract.Contacts.DISPLAY_NAME),
                    "${ContactsContract.Contacts.STARRED} = 1",
                    null,
                    null
                )
                cursor?.use {
                    val idx = it.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                    while (it.moveToNext()) {
                        if (idx >= 0) {
                            val name = it.getString(idx)
                            if (!name.isNullOrBlank()) starred.add(clean(name).lowercase())
                        }
                    }
                }
            } catch (_: Exception) {}
            return starred
        }

        fun load(ctx: Context): Config = try {
            val j = JSONObject(File(Runner.scriptsDir(ctx), "config.json").readText())
            val a = j.optJSONArray("people")
            Config(
                people = if (a == null) emptyList() else (0 until a.length()).map { clean(a.getString(it)) },
                showText = j.optBoolean("show_text", true),
                chatDelayS = j.optInt("chat_delay_s", 2),
                chatMinGapS = j.optInt("chat_min_gap_s", 10),
                weatherReturnS = j.optInt("weather_return_s", 60),
                chatTimeoutM = j.optInt("chat_timeout_m", 30),
                showCalls = j.optBoolean("show_calls", true),
                showMessages = j.optBoolean("show_messages", true),
                showMissed = j.optBoolean("show_missed", true),
                showFavorites = j.optBoolean("show_favorites", true),
                compactWeather = j.optBoolean("compact_weather", true),
                nameMaxLen = j.optInt("name_max_len", 8),
                nightQuietMode = j.optBoolean("night_quiet_mode", true),
                nightStartHour = j.optInt("night_start_hour", 23),
                nightEndHour = j.optInt("night_end_hour", 7)
            )
        } catch (e: Exception) { Config(emptyList(), true, 2, 10, 60, 30, true, true, true, true, true, 8, true, 23, 7) }
    }

    /** 1:1 chat: title is the contact. Group: title is the group, text starts with "Sender:". */
    fun matches(ctx: Context, title: String, text: String): Boolean {
        if (people.isEmpty() || people.any { it == "*" }) return true

        val cTitle = clean(title).lowercase()
        val cText = clean(text).lowercase()

        // 1. Check config people list (Hebrew & English supported)
        val matchesConfig = people.any { p ->
            val cleanP = p.lowercase()
            cleanP.isNotBlank() && (cTitle.contains(cleanP) || cText.contains(cleanP))
        }
        if (matchesConfig) return true

        // 2. Check Favorite / Starred contacts from phone if enabled
        if (showFavorites) {
            val starred = getStarredContacts(ctx)
            return starred.any { st ->
                st.isNotBlank() && (cTitle.contains(st) || cText.contains(st))
            }
        }

        return false
    }
}
