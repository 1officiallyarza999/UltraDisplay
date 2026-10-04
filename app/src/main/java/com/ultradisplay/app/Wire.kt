package com.ultradisplay.app

import java.io.*
import java.util.concurrent.atomic.AtomicBoolean

/** Framed packet channel over USB. One writer lock keeps packets from interleaving. */
class Wire(
    private val input: InputStream,
    private val output: OutputStream,
    private val onClose: () -> Unit = {}
) : Closeable {
    private val inStream = DataInputStream(BufferedInputStream(input, 128 * 1024))
    private val outStream = DataOutputStream(BufferedOutputStream(output, 128 * 1024))
    private val alive = AtomicBoolean(true)
    val isAlive: Boolean get() = alive.get()

    data class Packet(val type: Int, val timestampUs: Long, val width: Int, val height: Int, val bytes: ByteArray)

    companion object {
        const val MAGIC = 0x554C5452
        const val CONFIG = 1; const val VIDEO = 2; const val TOUCH = 3; const val PING = 4; const val PONG = 5
        const val STATS = 6; const val HELLO = 7; const val STREAM_END = 8
        const val TOUCH2 = 9; const val KEY = 10; const val SENDER_INFO = 11; const val AUDIO = 12
        const val KEY2 = 13; const val MOTION2 = 14; const val BITRATE = 15
        const val MAX_PACKET = 2_000_000
        fun packConfig(sps: ByteArray, pps: ByteArray): ByteArray = ByteArrayOutputStream().also { b ->
            DataOutputStream(b).use { it.writeInt(sps.size); it.write(sps); it.writeInt(pps.size); it.write(pps) }
        }.toByteArray()
        fun unpackConfig(bytes: ByteArray): Pair<ByteArray, ByteArray> {
            val i = DataInputStream(ByteArrayInputStream(bytes))
            val n = i.readInt(); require(n in 1..4096); val sps = ByteArray(n); i.readFully(sps)
            val m = i.readInt(); require(m in 1..4096); val pps = ByteArray(m); i.readFully(pps)
            return sps to pps
        }
    }

    @Synchronized fun send(p: Packet) {
        if (!alive.get()) return
        require(p.bytes.size <= MAX_PACKET)
        outStream.writeInt(MAGIC); outStream.writeInt(p.type); outStream.writeLong(p.timestampUs)
        outStream.writeInt(p.width); outStream.writeInt(p.height); outStream.writeInt(p.bytes.size)
        outStream.write(p.bytes); outStream.flush()
    }

    fun receive(): Packet {
        val magic = inStream.readInt()
        if (magic != MAGIC) throw IOException("Invalid USB stream (magic %08x)".format(magic))
        val t = inStream.readInt(); val ts = inStream.readLong(); val w = inStream.readInt(); val h = inStream.readInt()
        val len = inStream.readInt(); if (len !in 0..MAX_PACKET) throw IOException("Invalid packet length $len")
        val bytes = ByteArray(len); inStream.readFully(bytes)
        return Packet(t, ts, w, h, bytes)
    }

    override fun close() {
        if (alive.getAndSet(false)) {
            try { input.close() } catch (_: Exception) {}
            try { output.close() } catch (_: Exception) {}
            try { onClose() } catch (_: Exception) {}
        }
    }
}
