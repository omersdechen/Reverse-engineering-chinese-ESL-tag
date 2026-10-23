package com.omer.esl

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Read from scripts/config.json on every use, so edits apply without a rebuild. */
data class Config(val people: List<String>, val showText: Boolean, val chatDelayS: Int, val chatMinGapS: Int) {
    /** 1:1 chat: title is the contact. Group: title is the group, text starts with "Sender:". */
    fun matches(title: String, text: String): Boolean {
        if (people.isEmpty() || people.any { it.trim() == "*" }) return true
        return people.any { p ->
            val clean = p.trim()
            clean.isNotBlank() && (title.contains(clean, ignoreCase = true) || text.contains(clean, ignoreCase = true))
        }
    }

    companion object {
        fun load(ctx: Context): Config = try {
            val j = JSONObject(File(Runner.scriptsDir(ctx), "config.json").readText())
            val a = j.optJSONArray("people")
            Config(
                people = if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) },
                showText = j.optBoolean("show_text", true),
                chatDelayS = j.optInt("chat_delay_s", 2),
                chatMinGapS = j.optInt("chat_min_gap_s", 5))
        } catch (e: Exception) { Config(emptyList(), true, 2, 5) }
    }
}
