package com.omer.esl

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.IOException
import java.util.UUID

/** One-shot XTE upload to the tag. Mirrors build_stream()/upload() from esl_upload_good.py. */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
class EslBle(private val ctx: Context, private val mac: String) {
    companion object {
        val WRITE_UUID: UUID = UUID.fromString("00002760-08c2-11e1-9073-0e8ac72e0001")
        val NOTIFY_UUID: UUID = UUID.fromString("00002760-08c2-11e1-9073-0e8ac72e0002")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private fun sum(p: ByteArray) = p.sumOf { it.toInt() and 0xFF }
        private fun u32(n: Int) = byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte())

        fun ctrl(cmd: Int, p: ByteArray): ByteArray =
            byteArrayOf(0x58, 0x54, 0x45, cmd.toByte(), (6 + p.size).toByte(), (sum(p) and 0xFF).toByte()) + p

        fun frames(payload: ByteArray): List<ByteArray> {
            val out = ArrayList<ByteArray>()
            out += ctrl(0x01, byteArrayOf(0x01) + u32(payload.size))
            val chunk = 1211
            val n = (payload.size + chunk - 1) / chunk
            for (i in 0 until n) {
                val p = payload.copyOfRange(i * chunk, minOf((i + 1) * chunk, payload.size))
                val chk = (n + i + sum(p)) and 0xFF
                val len = p.size + 9
                out += byteArrayOf(0x58, 0x54, 0x45, 0x02, (len shr 8).toByte(), len.toByte(),
                    chk.toByte(), n.toByte(), i.toByte()) + p
            }
            out += ctrl(0x01, byteArrayOf(0x04, 0x00))
            return out
        }
    }

    private var connectD = CompletableDeferred<Unit>()
    private var mtuD = CompletableDeferred<Int>()
    private var svcD = CompletableDeferred<Unit>()
    private var descD = CompletableDeferred<Unit>()
    private var writeD = CompletableDeferred<Unit>()
    private var acks = Channel<ByteArray>(Channel.UNLIMITED)

    private fun failAll(e: Exception) {
        connectD.completeExceptionally(e); mtuD.completeExceptionally(e); svcD.completeExceptionally(e)
        descD.completeExceptionally(e); writeD.completeExceptionally(e)
    }

    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) connectD.complete(Unit)
            else failAll(IOException("disconnected, status=$status"))
        }
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) { mtuD.complete(mtu) }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) svcD.complete(Unit) else svcD.completeExceptionally(IOException("discover status=$status"))
        }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) { descD.complete(Unit) }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) { writeD.complete(Unit) }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) { acks.trySend(value) }
        @Deprecated("API < 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) acks.trySend(c.value)
        }
    }

    private suspend fun <T> CompletableDeferred<T>.wait(ms: Long, what: String): T =
        withTimeoutOrNull(ms) { await() } ?: throw IOException("timeout: $what")

    private fun find(g: BluetoothGatt, u: UUID): BluetoothGattCharacteristic =
        g.services.flatMap { it.characteristics }.firstOrNull { it.uuid == u }
            ?: throw IOException("characteristic $u not found")

    private suspend fun write(g: BluetoothGatt, ch: BluetoothGattCharacteristic, data: ByteArray) {
        repeat(25) {
            writeD = CompletableDeferred()
            val ok = if (Build.VERSION.SDK_INT >= 33)
                g.writeCharacteristic(ch, data, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothStatusCodes.SUCCESS
            else {
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                ch.value = data
                g.writeCharacteristic(ch)
            }
            if (ok) { withTimeoutOrNull(1000) { writeD.await() }; return }
            delay(20)                                   // stack busy: retry
        }
        throw IOException("write rejected")
    }

    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02x".format(it) }

    suspend fun upload(payload: ByteArray, log: (String) -> Unit) {
        var last: Exception? = null
        repeat(3) { n ->
            try { attempt(payload, log); return }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { last = e; log("attempt ${n + 1} failed: ${e.message}"); delay(2000) }
        }
        throw last!!
    }

    private suspend fun attempt(payload: ByteArray, log: (String) -> Unit) {
        connectD = CompletableDeferred(); mtuD = CompletableDeferred(); svcD = CompletableDeferred()
        descD = CompletableDeferred(); writeD = CompletableDeferred(); acks = Channel(Channel.UNLIMITED)
        val dev = ctx.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice(mac)
        val g = dev.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE)
        try {
            connectD.wait(15_000, "connect"); log("connected")
            g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            g.requestMtu(247)
            val mtu = withTimeoutOrNull(5000) { mtuD.await() } ?: 23
            val slice = (mtu - 3).coerceIn(20, 244)
            g.discoverServices(); svcD.wait(10_000, "service discovery")
            val wch = find(g, WRITE_UUID); val nch = find(g, NOTIFY_UUID)
            g.setCharacteristicNotification(nch, true)
            val cccd = nch.getDescriptor(CCCD)
            if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            else { cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(cccd) }
            descD.wait(5000, "enable notify")
            log("mtu $mtu, sending ${payload.size} B")
            for (f in EslBle.frames(payload)) {
                var o = 0
                while (o < f.size) { write(g, wch, f.copyOfRange(o, minOf(o + slice, f.size))); o += slice }
                delay(100)
            }
            var acked = false
            withTimeoutOrNull(8000) {
                while (!acked) {
                    val n = acks.receive(); log("notify " + hex(n))
                    if (n.size > 6 && n[3].toInt() == 0x04 && n[6].toInt() == 0x04) acked = true
                }
            }
            log(if (acked) "ACK received" else "no ACK (assuming ok)")
            delay(1500)
        } finally {
            g.disconnect(); g.close()
        }
    }
}
