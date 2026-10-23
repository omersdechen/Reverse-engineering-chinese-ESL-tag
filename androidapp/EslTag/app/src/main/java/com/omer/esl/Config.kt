package com.omer.esl

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Read from scripts/config.json on every use, so edits apply without a rebuild. */
data class Config(val people: List<String>, val showText: Boolean, val chatDelayS: Int, val chatMinGapS: Int) {
    /** 1:1 chat: title is the contact. Group: title is the group, text starts with "Sender:". */
    fun matches(title: String, text: String) = people.any { p ->
        p.isNotBlank() && (title.contains(p, true) || text.startsWith("$p:", true))
    }

    companion object {
        fun load(ctx: Context): Config = try {
            val j = JSONObject(File(Runner.scriptsDir(ctx), "config.json").readText())
            val a = j.optJSONArray("people")
            Config(
                people = if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) },
                showText = j.optBoolean("show_text", false),
                chatDelayS = j.optInt("chat_delay_s", 20),
                chatMinGapS = j.optInt("chat_min_gap_s", 120))
        } catch (e: Exception) { Config(emptyList(), false, 20, 120) }
    }
}
