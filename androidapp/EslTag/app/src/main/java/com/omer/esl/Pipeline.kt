package com.omer.esl

import android.content.Context
import kotlinx.coroutines.*

enum class Kind { CALL, CALL_WAIT, CHAT }   // most urgent first

/** Turns inbox events into tag uploads: calls at once, chats debounced and rate-limited. */
object Pipeline {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var waiting: Job? = null
    private var dueAt = 0L
    private var uploading = false
    private var dirty: Kind? = null
    private var lastUpload = 0L
    private var lastFp: String? = null

    @Synchronized fun schedule(ctx: Context, kind: Kind) {
        val app = ctx.applicationContext
        if (uploading) {                                  // never interrupt a running BLE upload
            if (dirty == null || kind.ordinal < dirty!!.ordinal) dirty = kind
            return
        }
        val cfg = Config.load(app)
        val now = System.currentTimeMillis()
        val wait = when (kind) {
            Kind.CALL -> 0L
            Kind.CALL_WAIT -> 1200L
            Kind.CHAT -> maxOf(cfg.chatDelayS * 1000L, lastUpload + cfg.chatMinGapS * 1000L - now)
        }
        val due = now + wait
        if (waiting?.isActive == true && dueAt <= due) return   // an earlier upload is already queued
        waiting?.cancel()
        dueAt = due
        waiting = scope.launch { delay(wait); upload(app) }
    }

    private suspend fun upload(ctx: Context) {
        synchronized(this) { uploading = true; dirty = null }
        try {
            val fp = Inbox.fingerprint()
            if (fp == lastFp) { EslLog.add(ctx, "inbox unchanged, skip"); return }
            Inbox.persist(ctx)
            if (Runner.run(ctx, true, markDaily = false)) { lastFp = fp; lastUpload = System.currentTimeMillis() }
        } finally {
            var again: Kind? = null
            synchronized(this) { uploading = false; again = dirty; dirty = null }
            again?.let { schedule(ctx, it) }
        }
    }
}
