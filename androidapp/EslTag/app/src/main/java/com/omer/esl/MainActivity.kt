package com.omer.esl

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.le.ScanFilter
import android.companion.*
import android.content.Intent
import android.content.IntentSender
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.*
import androidx.work.*
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.TimeUnit

@Suppress("DEPRECATION")
@SuppressLint("NewApi", "MissingPermission")
class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var info: TextView
    private lateinit var preview: ImageView
    private lateinit var logView: TextView
    private val cdm by lazy { getSystemService(CompanionDeviceManager::class.java) }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Runner.ensureScripts(this)
        requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 48, 24, 24) }
        info = TextView(this).also { root.addView(it) }
        preview = ImageView(this).apply { adjustViewBounds = true }.also { root.addView(it) }
        fun btn(t: String, f: () -> Unit) = root.addView(Button(this).apply { text = t; setOnClickListener { f() } })
        btn("Run now: render + upload") { go(true) }
        btn("Render only (preview)") { go(false) }
        btn("Edit main.py") { startActivity(Intent(this, EditorActivity::class.java)) }
        btn("Pair tag (enables automatic daily upload)") { pair() }
        btn("Reset main.py to default (keeps .bak)") { Runner.ensureScripts(this, true); refresh() }
        btn("Refresh") { refresh() }
        logView = TextView(this).apply { textSize = 11f; setTextIsSelectable(true) }.also { root.addView(it) }
        setContentView(ScrollView(this).apply { addView(root) })

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "esl-daily", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<DailyWorker>(6, TimeUnit.HOURS).build())
    }

    override fun onResume() { super.onResume(); observePresence(); refresh() }

    private fun associated() = cdm.associations.any { it.equals(Runner.MAC, true) }

    private fun observePresence() {
        try { if (associated()) cdm.startObservingDevicePresence(Runner.MAC) }
        catch (e: Exception) { EslLog.add(this, "observe failed: ${e.message}") }
    }

    private fun go(upload: Boolean) {
        scope.launch { info.text = "running..."; Runner.run(applicationContext, upload); refresh() }
    }

    private fun refresh() {
        info.text = "Tag ${Runner.MAC}\npaired: ${associated()}   last upload: ${Runner.lastOk(this)}\n" +
            "scripts: ${Runner.scriptsDir(this)}"
        File(Runner.scriptsDir(this), "preview.png").takeIf { it.exists() }
            ?.let { preview.setImageBitmap(BitmapFactory.decodeFile(it.path)) }
        logView.text = EslLog.read(this)
    }

    private fun pair() {
        val filter = BluetoothLeDeviceFilter.Builder()
            .setScanFilter(ScanFilter.Builder().setDeviceAddress(Runner.MAC).build()).build()
        val req = AssociationRequest.Builder().addDeviceFilter(filter).setSingleDevice(true).build()
        cdm.associate(req, object : CompanionDeviceManager.Callback() {
            override fun onDeviceFound(c: IntentSender) { startIntentSenderForResult(c, 42, null, 0, 0, 0) }
            override fun onAssociationPending(c: IntentSender) { startIntentSenderForResult(c, 42, null, 0, 0, 0) }
            override fun onFailure(e: CharSequence?) {
                Toast.makeText(this@MainActivity, "Pairing failed: $e (tag must be advertising nearby)", Toast.LENGTH_LONG).show()
            }
        }, null)
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 42 && res == RESULT_OK) { observePresence(); refresh() }
    }
}
