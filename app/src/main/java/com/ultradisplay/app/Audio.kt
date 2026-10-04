package com.ultradisplay.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.media.projection.MediaProjection
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import java.io.FileInputStream
import java.io.InputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** Raw PCM over the cable: 48 kHz, stereo, 16-bit = ~1.5 Mbps, far below USB 2.0 capacity. */
private const val RATE = 48_000
private const val CHUNK = 1920 // 10 ms

/** Sending device: captures the phone's audio and streams it to the tablet. */
object AudioForwarder {
    enum class Output { TABLET, BOTH, PHONE }

    @Volatile var output = Output.TABLET
    /** Human-readable description of where audio is currently going, for the UI. */
    @Volatile var active = ""; private set

    @Volatile private var generation = 0
    private var record: AudioRecord? = null
    private var pipe: ParcelFileDescriptor? = null
    private var usingShell = false

    @Synchronized fun start(context: Context, projection: MediaProjection?) {
        stop()
        if (output == Output.PHONE) { active = "בטלפון בלבד"; return }
        val gen = generation

        // Tablet only: capture the whole output through Shizuku. Android then silences the phone itself.
        val shell = ShizukuBridge.service
        if (output == Output.TABLET && shell != null) {
            try {
                val p = shell.startAudio(RATE)
                pipe = p; usingShell = true
                pump(FileInputStream(p.fileDescriptor), gen)
                active = "בטאבלט בלבד"
                Session.log("שמע עובר לטאבלט (הטלפון מושתק)")
                return
            } catch (e: Exception) { Session.log("שמע דרך Shizuku נכשל: ${e.message}") }
        }

        // Both devices: Android's playback capture (needs the screen-share session and the audio permission).
        if (projection != null && Build.VERSION.SDK_INT >= 29) {
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                active = "צריך הרשאת שמע"; Session.log("העברת שמע דורשת הרשאת הקלטת שמע"); return
            }
            try {
                val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()
                val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
                val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
                @Suppress("MissingPermission")
                val rec = AudioRecord.Builder().setAudioPlaybackCaptureConfig(config).setAudioFormat(format)
                    .setBufferSizeInBytes(maxOf(min, CHUNK * 4)).build()
                if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); throw IllegalStateException("AudioRecord init failed") }
                rec.startRecording()
                record = rec
                pump(object : InputStream() {
                    override fun read(): Int = -1
                    override fun read(b: ByteArray, off: Int, len: Int): Int = rec.read(b, off, len)
                }, gen)
                active = if (output == Output.BOTH) "בשני המכשירים" else "בשני המכשירים (להשתקת הטלפון צריך Shizuku)"
                Session.log("שמע עובר לטאבלט ${if (output == Output.BOTH) "וגם נשאר בטלפון" else "(הטלפון לא מושתק — אין Shizuku)"}")
                return
            } catch (e: Exception) { Session.log("לכידת שמע נכשלה: ${e.message}") }
        }

        active = "לא זמין"
        Session.log(if (projection == null) "שמע במסך טאבלט דורש Shizuku" else "העברת שמע לא זמינה במכשיר הזה")
    }

    private fun pump(input: InputStream, gen: Int) {
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buf = ByteArray(CHUNK)
            try {
                while (generation == gen && Session.wire != null) {
                    val n = input.read(buf, 0, buf.size)
                    if (n < 0) break
                    if (n > 0) Session.wire?.send(Wire.Packet(Wire.AUDIO, 0, RATE, 2, buf.copyOf(n)))
                }
            } catch (_: Exception) {}
        }, "audio-forward").start()
    }

    @Synchronized fun stop() {
        generation++
        record?.let { r -> try { r.stop() } catch (_: Exception) {}; try { r.release() } catch (_: Exception) {} }
        record = null
        if (usingShell) { try { ShizukuBridge.service?.stopAudio() } catch (_: Exception) {} }
        usingShell = false
        try { pipe?.close() } catch (_: Exception) {}
        pipe = null
        active = ""
    }
}

/** Receiving device: plays the PCM stream with a small, self-trimming buffer to stay in sync with the picture. */
object AudioSink {
    @Volatile var muted = false
        set(value) { field = value; try { track?.setVolume(if (value) 0f else 1f) } catch (_: Exception) {} }
    @Volatile var lastPacketAt = 0L; private set

    private val queue = ArrayBlockingQueue<ByteArray>(24)
    @Volatile private var track: AudioTrack? = null
    @Volatile private var running = false

    val playing: Boolean get() = SystemClock.uptimeMillis() - lastPacketAt < 1500

    fun onPacket(bytes: ByteArray) {
        lastPacketAt = SystemClock.uptimeMillis()
        if (!running) start()
        if (!queue.offer(bytes)) { queue.clear(); queue.offer(bytes) }
    }

    @Synchronized private fun start() {
        if (running) return
        val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(maxOf(min, CHUNK * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        if (muted) t.setVolume(0f)
        t.play()
        track = t
        running = true
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            while (running) {
                val b = try { queue.poll(200, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { break } ?: continue
                // More than ~60 ms waiting means we fell behind the picture: skip ahead.
                if (queue.size > 6) repeat(queue.size - 2) { queue.poll() }
                try { t.write(b, 0, b.size) } catch (_: Exception) { break }
            }
            try { t.stop() } catch (_: Exception) {}
            t.release()
        }, "audio-play").start()
        Session.log("מנגן שמע מהטלפון")
    }

    @Synchronized fun stop() {
        running = false
        track = null
        queue.clear()
    }
}
