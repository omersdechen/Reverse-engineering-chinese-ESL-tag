package com.omer.esl

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
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

    /** Copies bundled scripts into the editable folder if missing or outdated. */
    fun ensureScripts(ctx: Context, resetMain: Boolean = false) {
        val dir = scriptsDir(ctx)
        for (n in listOf("main.py", "weather.py", "config.json")) {
            val dst = File(dir, n)
            val isOutdated = resetMain || n in listOf("main.py", "weather.py") ||
                (n == "config.json" && dst.exists() && dst.readText().contains("\"show_text\": false"))
            if (dst.exists() && !isOutdated) continue
            if (dst.exists()) dst.copyTo(File(dir, "$n.bak"), true)
            ctx.assets.open("scripts/$n").use { i -> dst.outputStream().use { i.copyTo(it) } }
        }
    }

    private fun render(ctx: Context): ByteArray {
        ensureScripts(ctx)
        if (!Python.isStarted()) Python.start(AndroidPlatform(ctx))
        return Python.getInstance().getModule("esl_lib")
            .callAttr("run_scripts", scriptsDir(ctx).path).toJava(ByteArray::class.java)
    }

    fun showPreviewNotification(ctx: Context) {
        try {
            val previewFile = File(scriptsDir(ctx), "preview.png")
            if (!previewFile.exists()) return
            val bitmap = BitmapFactory.decodeFile(previewFile.path) ?: return

            val channelId = "esl_preview"
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    channelId,
                    "ESL Tag Previews",
                    NotificationManager.IMPORTANCE_DEFAULT
                )
                nm.createNotificationChannel(channel)
            }

            val builder = NotificationCompat.Builder(ctx, channelId)
                .setSmallIcon(android.R.drawable.ic_menu_gallery)
                .setContentTitle("ESL Tag Preview")
                .setContentText("New tag layout rendered")
                .setStyle(NotificationCompat.BigPictureStyle().bigPicture(bitmap))
                .setAutoCancel(true)

            nm.notify(1001, builder.build())
        } catch (e: Exception) {
            EslLog.add(ctx, "preview notification error: ${e.message}")
        }
    }

    /** Render main.py and (optionally) upload to the tag. Returns true on success. */
    suspend fun run(ctx: Context, upload: Boolean = true, markDaily: Boolean = true): Boolean {
        if (!busy.compareAndSet(false, true)) { EslLog.add(ctx, "already running"); return false }
        try {
            EslLog.add(ctx, "rendering")
            val t0 = System.currentTimeMillis()
            val payload = withContext(Dispatchers.IO) { render(ctx) }
            val renderMs = System.currentTimeMillis() - t0
            EslStats.addRenderTime(ctx, renderMs)
            EslLog.add(ctx, "payload ${payload.size} B (${renderMs}ms)")
            showPreviewNotification(ctx)
            if (!upload) return true
            EslBle(ctx, MAC).upload(payload) { EslLog.add(ctx, it) }
            if (markDaily) prefs(ctx).edit().putString("last_ok_day", today()).apply()
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
