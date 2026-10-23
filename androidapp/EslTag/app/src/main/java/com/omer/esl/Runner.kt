package com.omer.esl

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

object EslLog {
    private fun file(ctx: Context) = File(ctx.filesDir, "esl.log")
    @Synchronized fun add(ctx: Context, msg: String) {
        val f = file(ctx)
        f.appendText(SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date()) + " " + msg + "\n")
        if (f.length() > 30_000) f.writeText(f.readText().takeLast(15_000))
        android.util.Log.i("ESL", msg)
    }
    fun read(ctx: Context): String = file(ctx).takeIf { it.exists() }?.readText()?.takeLast(6000) ?: ""
}

object Runner {
    const val MAC = "14:20:10:19:39:37"
    private val busy = AtomicBoolean(false)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("esl", Context.MODE_PRIVATE)
    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    fun lastOk(ctx: Context): String = prefs(ctx).getString("last_ok_day", "never") ?: "never"

    fun scriptsDir(ctx: Context): File = File(ctx.getExternalFilesDir(null), "scripts").apply { mkdirs() }

    fun ensureScripts(ctx: Context, overwrite: Boolean = false) {
        val dst = File(scriptsDir(ctx), "main.py")
        if (dst.exists() && !overwrite) return
        if (dst.exists()) dst.copyTo(File(dst.parentFile, "main.py.bak"), true)
        ctx.assets.open("scripts/main.py").use { i -> dst.outputStream().use { i.copyTo(it) } }
    }

    private fun render(ctx: Context): ByteArray {
        ensureScripts(ctx)
        if (!Python.isStarted()) Python.start(AndroidPlatform(ctx))
        return Python.getInstance().getModule("esl_lib")
            .callAttr("run_scripts", scriptsDir(ctx).path).toJava(ByteArray::class.java)
    }

    /** Render main.py and (optionally) upload to the tag. Returns true on success. */
    suspend fun run(ctx: Context, upload: Boolean = true): Boolean {
        if (!busy.compareAndSet(false, true)) { EslLog.add(ctx, "already running"); return false }
        try {
            EslLog.add(ctx, "rendering")
            val payload = withContext(Dispatchers.IO) { render(ctx) }
            EslLog.add(ctx, "payload ${payload.size} B")
            if (!upload) return true
            EslBle(ctx, MAC).upload(payload) { EslLog.add(ctx, it) }
            prefs(ctx).edit().putString("last_ok_day", today()).apply()
            EslLog.add(ctx, "upload OK")
            return true
        } catch (e: Exception) {
            EslLog.add(ctx, "FAILED: " + (e.message ?: e.toString()).takeLast(1500))
            return false
        } finally { busy.set(false) }
    }

    /** Once-a-day rule: upload if there has been no successful upload today (local date). */
    suspend fun runIfDue(ctx: Context) {
        if (prefs(ctx).getString("last_ok_day", "") != today()) run(ctx) else EslLog.add(ctx, "already done today")
    }
}
