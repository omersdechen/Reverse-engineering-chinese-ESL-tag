package com.omer.esl

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Chat(val who: String, val count: Int, val last: String, val ts: Long)
data class Missed(val who: String, val src: String, val ts: Long)
data class Ringing(val who: String, val src: String, val key: String?, val ts: Long)

/** In-memory state, mirrored to scripts/inbox.json for main.py. */
object Inbox {
    private val chats = LinkedHashMap<String, Chat>()
    private val missed = ArrayList<Missed>()
    private var ringing: Ringing? = null
    private var answered = false
    private var inited = false

    @Synchronized fun init(ctx: Context) {
        if (inited) return
        inited = true
        try {
            val a = JSONObject(File(Runner.scriptsDir(ctx), "inbox.json").readText()).optJSONArray("missed") ?: return
            for (i in 0 until a.length()) a.getJSONObject(i).let { missed.add(Missed(it.getString("who"), it.getString("src"), it.getLong("ts"))) }
        } catch (_: Exception) {}
    }

    @Synchronized fun hasContent() = chats.isNotEmpty() || missed.isNotEmpty() || ringing != null
    @Synchronized fun setChat(key: String, c: Chat) {
        chats.remove(key)
        chats[c.who] = c
    }
    @Synchronized fun removeChat(key: String, who: String? = null): Boolean {
        var removed = chats.remove(key) != null
        if (who != null && chats.remove(who) != null) removed = true
        val matches = chats.entries.filter { (k, v) ->
            k == key || v.who == who || (who != null && (v.who.contains(who, ignoreCase = true) || who.contains(v.who, ignoreCase = true)))
        }.map { it.key }
        for (k in matches) {
            chats.remove(k)
            removed = true
        }
        return removed
    }

    @Synchronized fun addMissed(m: Missed) {
        if (missed.any { it.who == m.who && Math.abs(it.ts - m.ts) < 60_000 }) return
        missed.add(0, m); while (missed.size > 8) missed.removeAt(missed.size - 1)
    }

    @Synchronized fun removeMissed(who: String? = null): Boolean {
        if (who.isNullOrBlank()) {
            val had = missed.isNotEmpty()
            missed.clear()
            return had
        }
        return missed.removeAll { m ->
            m.who.contains(who, ignoreCase = true) || who.contains(m.who, ignoreCase = true)
        }
    }

    @Synchronized fun clear() { chats.clear(); missed.clear(); ringing = null; answered = false }

    @Synchronized fun ring(r: Ringing) { ringing = r; answered = false }
    @Synchronized fun clearRingingKey(key: String): Boolean {
        if (ringing?.key != key) return false
        ringing = null; return true
    }

    @Synchronized private fun json(withTs: Boolean): String = JSONObject().apply {
        put("ringing", ringing?.let { JSONObject().put("who", it.who).put("src", it.src).put("ts", it.ts) } ?: JSONObject.NULL)
        put("chats", JSONArray(chats.values.sortedByDescending { it.ts }.map {
            JSONObject().put("who", it.who).put("count", it.count).put("last", it.last).put("ts", it.ts) }))
        put("missed", JSONArray(missed.map { JSONObject().put("who", it.who).put("src", it.src).put("ts", it.ts) }))
        if (withTs) put("updated", System.currentTimeMillis())
    }.toString()

    fun fingerprint() = json(false)
    fun persist(ctx: Context) { File(Runner.scriptsDir(ctx), "inbox.json").writeText(json(true)) }

    // ---- phone calls (CallReceiver) ----
    fun onRing(ctx: Context, number: String?) {
        init(ctx)
        val who = if (number.isNullOrBlank()) "Unknown" else lookup(ctx, number)
        ring(Ringing(who, "phone", null, System.currentTimeMillis()))
        Pipeline.schedule(ctx, if (number.isNullOrBlank()) Kind.CALL_WAIT else Kind.CALL)  // number often arrives in a 2nd broadcast
    }

    fun onOffhook() = synchronized(this) { if (ringing != null) answered = true }

    fun onIdle(ctx: Context) {
        init(ctx)
        val r = synchronized(this) {
            val r = ringing
            if (r == null || r.src != "phone") return
            if (!answered) addMissed(Missed(r.who, "phone", System.currentTimeMillis()))
            ringing = null; answered = false; r
        }
        Pipeline.schedule(ctx, Kind.CALL)
    }

    private fun lookup(ctx: Context, number: String): String = try {
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: number
    } catch (e: Exception) { number }
}
