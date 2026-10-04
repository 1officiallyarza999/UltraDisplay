package com.ultradisplay.app

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.SystemClock
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Hardware H.264 decoder onto a Surface. Drops old encoded frames rather than building latency. */
class TabletDecoder(private val surface: Surface) {
    private var decoder: MediaCodec? = null
    private val frames = ArrayBlockingQueue<Wire.Packet>(4)
    @Volatile private var running = true
    @Volatile var decoded = 0L
    @Volatile var dropped = 0L
    @Volatile var lastQueueDelayMs = 0.0
    private var lastKeyframeRequestNs = 0L
    private val queuedAt = java.util.concurrent.ConcurrentHashMap<Long, Long>()

    init { thread(name = "ultra-decoder") { loop() } }

    fun setup(packet: Wire.Packet) {
        val (sps, pps) = Wire.unpackConfig(packet.bytes)
        frames.clear()
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, packet.width, packet.height).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(sps)); setByteBuffer("csd-1", ByteBuffer.wrap(pps))
            setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
        }
        val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try { codec.configure(fmt, surface, null, 0); codec.start() }
        catch (e: Exception) {
            codec.release()
            // Some decoders reject the low-latency keys: retry without them.
            val plain = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, packet.width, packet.height).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(sps)); setByteBuffer("csd-1", ByteBuffer.wrap(pps))
            }
            val retry = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            try { retry.configure(plain, surface, null, 0); retry.start() } catch (e2: Exception) { retry.release(); throw e2 }
            swap(retry)
            Session.log("מפענח הופעל (מצב רגיל)")
            return
        }
        swap(codec)
        Session.log("מפענח הופעל ${packet.width}×${packet.height}")
    }

    private fun swap(codec: MediaCodec) {
        synchronized(this) {
            try { decoder?.stop(); decoder?.release() } catch (_: Exception) {}
            decoder = codec
        }
    }

    fun enqueue(packet: Wire.Packet) {
        if (!frames.offer(packet)) {
            frames.poll(); dropped++; frames.offer(packet)
            // Dropped reference frames corrupt the picture until the next IDR, so ask for one.
            val now = SystemClock.elapsedRealtimeNanos()
            if (now - lastKeyframeRequestNs > 600_000_000L) {
                lastKeyframeRequestNs = now
                Session.sendAsync(Wire.Packet(Wire.STATS, 0, 0, 0, byteArrayOf()))
            }
        }
        queuedAt[packet.timestampUs] = SystemClock.elapsedRealtimeNanos()
        if (queuedAt.size > 120) queuedAt.clear()
    }

    private fun loop() {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val packet = try { frames.poll(20, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { break }
            val codec = synchronized(this) { decoder } ?: continue
            try {
                if (packet != null) {
                    val idx = codec.dequeueInputBuffer(5000)
                    if (idx >= 0) {
                        codec.getInputBuffer(idx)?.apply { clear(); put(packet.bytes) }
                        codec.queueInputBuffer(idx, 0, packet.bytes.size, packet.timestampUs, 0)
                    } else dropped++
                }
                var out = codec.dequeueOutputBuffer(info, 1000)
                while (out >= 0) {
                    codec.releaseOutputBuffer(out, true)
                    decoded++
                    queuedAt.remove(info.presentationTimeUs)?.let { lastQueueDelayMs = (SystemClock.elapsedRealtimeNanos() - it) / 1e6 }
                    out = codec.dequeueOutputBuffer(info, 0)
                }
            } catch (e: IllegalStateException) {
                // Codec was swapped or stopped underneath us; continue with the current one.
            } catch (e: Exception) { Session.log("מפענח: ${e.message}") }
        }
    }

    fun stop() {
        running = false
        synchronized(this) { try { decoder?.stop(); decoder?.release() } catch (_: Exception) {}; decoder = null }
    }
}
