package com.ultradisplay.app

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import android.os.SystemClock
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import kotlin.concurrent.thread

/** Drop old encoded frames rather than building an unbounded queue. */
class TabletDecoder(private val surface: Surface) {
    private var decoder: MediaCodec? = null
    private val frames = ArrayBlockingQueue<Wire.Packet>(3)
    @Volatile private var running = true
    @Volatile var decoded = 0L
    @Volatile var dropped = 0L
    @Volatile var lastQueueDelayMs = 0.0
    private var lastKeyframeRequestNs = 0L
    private val q = java.util.concurrent.ConcurrentHashMap<Long, Long>()
    init { thread(name="ultra-decoder") { loop() } }
    fun setup(packet: Wire.Packet) {
        val (sps, pps) = Wire.unpackConfig(packet.bytes)
        frames.clear()
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, packet.width, packet.height).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(sps)); setByteBuffer("csd-1", ByteBuffer.wrap(pps))
            if (android.os.Build.VERSION.SDK_INT >= 26) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try { codec.configure(fmt, surface, null, 0); codec.start() }
        catch (e: Exception) { codec.release(); throw e }
        synchronized(this) { decoder?.stop(); decoder?.release(); decoder = codec }
        Session.update("Receiving video • ${packet.width}×${packet.height}")
    }
    fun enqueue(packet: Wire.Packet) {
        if (!frames.offer(packet)) {
            frames.poll(); dropped++; frames.offer(packet)
            // H.264 reference frames cannot safely be dropped indefinitely; request recovery IDR.
            val now = SystemClock.elapsedRealtimeNanos()
            if (now - lastKeyframeRequestNs > 600_000_000L) {
                lastKeyframeRequestNs = now
                try { Session.wire?.send(Wire.Packet(Wire.STATS, 0, 0, 0, byteArrayOf())) } catch (_: Exception) {}
            }
        }
        q[packet.timestampUs] = SystemClock.elapsedRealtimeNanos()
        if (q.size > 100) q.clear()
    }
    private fun loop() {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val packet = try { frames.poll(30, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { break }
            val codec = synchronized(this) { decoder }
            if (codec == null) continue
            try {
                if (packet != null) {
                    val idx = codec.dequeueInputBuffer(5000)
                    if (idx >= 0) {
                        codec.getInputBuffer(idx)?.apply { clear(); put(packet.bytes) }
                        codec.queueInputBuffer(idx, 0, packet.bytes.size, packet.timestampUs, 0)
                    } else { dropped++ }
                }
                var out = codec.dequeueOutputBuffer(info, 1000)
                while (out >= 0) {
                    codec.releaseOutputBuffer(out, true)
                    decoded++
                    // This measures local queue + decode, not true phone-to-tablet glass-to-glass latency.
                    q.remove(info.presentationTimeUs)?.let { lastQueueDelayMs = (SystemClock.elapsedRealtimeNanos()-it)/1e6 }
                    out = codec.dequeueOutputBuffer(info, 0)
                }
            } catch (e: Exception) { Session.update("Decoder: ${e.message}") }
        }
    }
    fun stop() { running = false; synchronized(this) { try { decoder?.stop(); decoder?.release() } catch (_: Exception) {} } }
}
