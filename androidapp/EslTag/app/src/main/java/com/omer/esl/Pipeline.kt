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
            Kind.CHAT -> {
                val delayS = if (!Inbox.hasContent()) cfg.weatherReturnS else cfg.chatDelayS
                maxOf(delayS * 1000L, lastUpload + cfg.chatMinGapS * 1000L - now)
            }
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
            val cfg = Config.load(ctx)

            // 1. Check Night Quiet Hours (23:00 - 07:00)
            if (cfg.isNightHours()) {
                EslStats.incPreventedNight(ctx)
                EslLog.add(ctx, "night quiet hours (${cfg.nightStartHour}:00-${cfg.nightEndHour}:00), skipped BLE upload")
                return
            }

            // 2. Check if user is actively using the phone (screen ON & unlocked)
            if (EslStats.isUserOnPhone(ctx)) {
                EslStats.incPreventedScreenOn(ctx)
                EslLog.add(ctx, "phone in active use, skipped BLE upload (saved battery)")
                return
            }

            val fp = Inbox.fingerprint()
            if (fp == lastFp) {
                EslStats.incPreventedDuplicate(ctx)
                EslLog.add(ctx, "inbox unchanged, skip")
                return
            }

            Inbox.persist(ctx)
            if (Runner.run(ctx, true, markDaily = false)) {
                lastFp = fp
                lastUpload = System.currentTimeMillis()
                EslStats.incDisplayChanges(ctx)
            }
        } finally {
            var again: Kind? = null
            synchronized(this) { uploading = false; again = dirty; dirty = null }
            again?.let { schedule(ctx, it) }
        }
    }
}
