package com.ultradisplay.app

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.view.Surface
import java.nio.ByteBuffer

/**
 * Low-latency hardware H.264 decoder in asynchronous mode: every input buffer is filled the moment
 * a frame arrives and every decoded frame is rendered the moment it is ready — nothing waits for
 * the next frame. At most a couple of frames are ever queued; older ones are dropped.
 */
class TabletDecoder(private val surface: Surface) {
    private val worker = HandlerThread("ultra-decoder", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
    private val handler = Handler(worker.looper)
    private val lock = Any()
    private var codec: MediaCodec? = null
    private val freeInputs = ArrayDeque<Int>()
    private val pending = ArrayDeque<Wire.Packet>()
    private val arrivedAt = HashMap<Long, Long>()
    @Volatile var decoded = 0L
    @Volatile var dropped = 0L
    @Volatile var lastQueueDelayMs = 0.0
    private var lastKeyframeRequestNs = 0L

    companion object {
        /** Turned off for the rest of the session if a decoder ever rejects the rewritten SPS. */
        @Volatile var spsFix = true
    }
    @Volatile private var setupAt = 0L
    @Volatile private var fixedUsed = false
    private var origSps: ByteArray? = null
    private var fixedSps: ByteArray? = null

    fun setup(packet: Wire.Packet) {
        val (rawSps, pps) = Wire.unpackConfig(packet.bytes)
        val sps = if (spsFix) SpsFixer.fix(rawSps) else rawSps
        fixedUsed = !sps.contentEquals(rawSps)
        origSps = rawSps; fixedSps = sps
        if (fixedUsed) Session.log(tr("מפענח: הוגדר ללא המתנה לפריימים", "Decoder: frame buffering disabled (low-latency SPS)"))
        setupAt = SystemClock.elapsedRealtime()
        shutdown(synchronized(lock) { detachCodec() })
        val c = try { create(packet, sps, pps, lowLatency = true) }
            catch (e: Exception) { Session.log("מפענח: מצב השהיה נמוכה לא נתמך, עובר למצב רגיל"); create(packet, sps, pps, lowLatency = false) }
        synchronized(lock) { codec = c }
        Session.log("מפענח הופעל ${packet.width}×${packet.height}")
    }

    private fun create(packet: Wire.Packet, sps: ByteArray, pps: ByteArray, lowLatency: Boolean): MediaCodec {
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, packet.width, packet.height).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(sps)); setByteBuffer("csd-1", ByteBuffer.wrap(pps))
            if (lowLatency) {
                // Vendor switches for decoders that don't honour the standard key (unknown keys are ignored).
                for (k in arrayOf("vendor.qti-ext-dec-low-latency.enable", "vendor.qti-ext-dec-picture-order.enable",
                        "vendor.low-latency.enable", "vendor.rtc-ext-dec-low-latency.enable", "vdec-lowlatency", "low-latency"))
                    setInteger(k, 1)
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
            }
        }
        val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            c.setCallback(callback, handler)
            c.configure(fmt, surface, null, 0)
            c.start()
        } catch (e: Exception) { c.release(); throw e }
        return c
    }

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(c: MediaCodec, index: Int) {
            synchronized(lock) {
                if (c !== codec) return
                val p = pending.removeFirstOrNull()
                if (p == null) freeInputs.addLast(index) else feed(c, index, p)
            }
        }

        override fun onOutputBufferAvailable(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            try { c.releaseOutputBuffer(index, true) } catch (_: Exception) { return }
            decoded++
            synchronized(lock) { arrivedAt.remove(info.presentationTimeUs) }?.let {
                lastQueueDelayMs = (SystemClock.elapsedRealtimeNanos() - it) / 1e6
            }
        }

        override fun onError(c: MediaCodec, e: MediaCodec.CodecException) {
            if (fixedUsed && spsFix && SystemClock.elapsedRealtime() - setupAt < 5000) {
                // The decoder didn't like the rewritten header: fall back to the original and ask for a fresh start.
                spsFix = false
                Session.log(tr("מפענח: חוזר לכותרת המקורית", "Decoder: falling back to the original SPS"))
            }
            Session.log("מפענח: ${e.diagnosticInfo}")
            ErrorLog.record(ErrorLog.Kind.ERROR, "Decoder: ${e.diagnosticInfo}", e)
            requestKeyframe(force = true)
        }

        override fun onOutputFormatChanged(c: MediaCodec, format: MediaFormat) {}
    }

    /** Some encoders repeat the SPS in front of keyframes: swap in the low-latency version there too. */
    private fun patchInlineSps(b: ByteArray): ByteArray {
        val o = origSps; val f = fixedSps
        if (!fixedUsed || o == null || f == null || b.size < o.size || (b[4].toInt() and 0x1f) != 7) return b
        for (i in o.indices) if (b[i] != o[i]) return b
        return f + b.copyOfRange(o.size, b.size)
    }

    private fun feed(c: MediaCodec, index: Int, p: Wire.Packet) {
        try {
            val buf = c.getInputBuffer(index) ?: return
            val data = if (p.bytes.size > 5) patchInlineSps(p.bytes) else p.bytes
            buf.clear(); buf.put(data)
            c.queueInputBuffer(index, 0, data.size, p.timestampUs, 0)
        } catch (_: Exception) {}
    }

    fun enqueue(packet: Wire.Packet) {
        synchronized(lock) {
            val c = codec ?: return
            arrivedAt[packet.timestampUs] = SystemClock.elapsedRealtimeNanos()
            if (arrivedAt.size > 120) arrivedAt.clear()
            val index = freeInputs.removeFirstOrNull()
            if (index != null) { feed(c, index, packet); return }
            if (pending.size >= 2) { pending.removeFirst(); dropped++; requestKeyframe(force = false) }
            pending.addLast(packet)
        }
    }

    /** Dropped reference frames corrupt the picture until the next IDR, so ask the sender for one. */
    private fun requestKeyframe(force: Boolean) {
        val now = SystemClock.elapsedRealtimeNanos()
        if (!force && now - lastKeyframeRequestNs < 500_000_000L) return
        lastKeyframeRequestNs = now
        Session.sendAsync(Wire.Packet(Wire.STATS, 0, 0, 0, byteArrayOf()))
    }

    /** Must be called holding [lock]; returns the old codec so it can be stopped outside the lock. */
    private fun detachCodec(): MediaCodec? {
        val c = codec
        codec = null
        freeInputs.clear(); pending.clear(); arrivedAt.clear()
        return c
    }

    private fun shutdown(c: MediaCodec?) {
        if (c == null) return
        try { c.stop() } catch (_: Exception) {}
        try { c.release() } catch (_: Exception) {}
    }

    fun stop() {
        shutdown(synchronized(lock) { detachCodec() })
        worker.quitSafely()
    }
}
