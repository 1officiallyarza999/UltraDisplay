package com.ultradisplay.app

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.view.Display
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Runs only on the sending device. Two ways to produce the picture:
 *  • Mirror  — MediaProjection copy of the phone screen (needs the system "share screen" consent).
 *  • Tablet  — a separate virtual display sized to the tablet, created through Shizuku. Apps run on
 *              the phone's processor but lay themselves out for the tablet.
 */
class CaptureService : Service() {
    data class Preset(val label: String, val longSide: Int, val fps: Int, val bitrate: Int)

    companion object {
        const val ACTION_START = "ultra.START"; const val ACTION_START_TABLET = "ultra.START_TABLET"; const val ACTION_STOP = "ultra.STOP"
        const val EXTRA_RESULT = "resultCode"; const val EXTRA_DATA = "resultData"; const val EXTRA_QUALITY = "quality"
        const val CHANNEL = "ultradisplay_capture"
        val PRESETS = listOf(
            Preset("משחק", 1280, 60, 8_000_000),
            Preset("מאוזן", 1600, 60, 12_000_000),
            Preset("אולטרה", 1920, 60, 16_000_000)
        )
        @Volatile private var liveCodec: MediaCodec? = null
        @Volatile private var liveConfig: Wire.Packet? = null
        @Volatile var virtualMode = false

        /** Receiver asked for a fresh IDR (it opened late or dropped frames): resend config + keyframe. */
        fun requestKeyframe() {
            liveConfig?.let { cfg -> try { Session.wire?.send(cfg) } catch (_: Exception) {} }
            try { liveCodec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
            catch (_: Exception) {}
        }
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    @Volatile private var running = false
    private var quality = 0
    private var landscape = false
    private val generation = AtomicInteger(0)
    private val displayManager get() = getSystemService(DISPLAY_SERVICE) as DisplayManager

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel(CHANNEL, "שיתוף מסך", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notification(text: String): Notification {
        val stopIntent = PendingIntent.getService(this, 1, Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle("UltraDisplay משדר")
            .setContentText(text).setOngoing(true)
            .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_media_pause), "עצור", stopIntent).build())
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_START_TABLET -> if (!running) startTablet(intent)
            ACTION_START -> if (!running) startMirror(intent)
        }
        return START_NOT_STICKY
    }

    // ───────────── Mirror mode ─────────────

    private fun startMirror(intent: Intent) {
        if (Build.VERSION.SDK_INT >= 29) startForeground(7, notification("המסך משותף דרך הכבל"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(7, notification("המסך משותף דרך הכבל"))
        val result = intent.getIntExtra(EXTRA_RESULT, Activity.RESULT_CANCELED)
        @Suppress("DEPRECATION")
        val data = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            else intent.getParcelableExtra(EXTRA_DATA) as? Intent
        quality = intent.getIntExtra(EXTRA_QUALITY, 0).coerceIn(0, PRESETS.size - 1)
        if (result != Activity.RESULT_OK || data == null || Session.wire == null) {
            Session.set(Session.Link.ERROR, "אין חיבור או שלא אושר שיתוף מסך"); stopSelf(); return
        }
        try {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val proj = manager.getMediaProjection(result, data) ?: throw IllegalStateException("MediaProjection לא זמין")
            projection = proj
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { Session.log("שיתוף המסך הסתיים"); stopSelf() }
            }, Handler(Looper.getMainLooper()))

            val m = realMetrics()
            val (codec, cfg) = openEncoder(m.widthPixels, m.heightPixels, quality)
            val input = codec.createInputSurface()
            codec.start()
            display = proj.createVirtualDisplay("UltraDisplay", cfg.first, cfg.second, m.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, input, null, Handler(Looper.getMainLooper()))
            virtualMode = false
            TouchInjector.targetDisplay = 0
            running = true; Session.streaming = true
            startLoop(codec, input, cfg, m.widthPixels > m.heightPixels)
            displayManager.registerDisplayListener(rotationListener, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            Session.set(Session.Link.ERROR, "שגיאת שידור: ${e.message}"); stopSelf()
        }
    }

    // ───────────── Tablet (virtual display) mode ─────────────

    private fun startTablet(intent: Intent) {
        if (Build.VERSION.SDK_INT >= 29) startForeground(7, notification("מסך טאבלט פעיל"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(7, notification("מסך טאבלט פעיל"))
        quality = intent.getIntExtra(EXTRA_QUALITY, 0).coerceIn(0, PRESETS.size - 1)
        val shell = ShizukuBridge.service
        if (shell == null || Session.wire == null || Session.peerW <= 0) {
            Session.set(Session.Link.ERROR, "מסך טאבלט דורש חיבור ו-Shizuku פעיל"); stopSelf(); return
        }
        try {
            val pw = Session.peerW; val ph = Session.peerH
            val (codec, cfg) = openEncoder(pw, ph, quality)
            val dpi = max(120, (Session.peerDpi.coerceAtLeast(160) * cfg.first.toDouble() / pw).roundToInt())
            val input = codec.createInputSurface()
            codec.start()
            val displayId = shell.createDisplay(input, cfg.first, cfg.second, dpi)
            TouchInjector.targetW = cfg.first; TouchInjector.targetH = cfg.second; TouchInjector.targetDisplay = displayId
            virtualMode = true
            running = true; Session.streaming = true
            Session.log("נוצר מסך טאבלט #$displayId · ${cfg.first}×${cfg.second} · ${dpi}dpi")
            startLoop(codec, input, cfg, true)
            thread { ShizukuBridge.launchHome(displayId) }
        } catch (e: Exception) {
            Session.set(Session.Link.ERROR, "יצירת מסך טאבלט נכשלה: ${e.message}"); stopSelf()
        }
    }

    // ───────────── Encoder ─────────────

    private fun realMetrics(): DisplayMetrics {
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        displayManager.getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(m)
        return m
    }

    private fun startLoop(codec: MediaCodec, input: Surface, cfg: Triple<Int, Int, Preset>, isLandscape: Boolean) {
        val gen = generation.incrementAndGet()
        liveCodec = codec; liveConfig = null; landscape = isLandscape
        Session.streamInfo = "${cfg.first}×${cfg.second} · ${cfg.third.fps}fps · ${cfg.third.bitrate / 1_000_000}Mbps"
        Session.log("משדר ${Session.streamInfo}${if (virtualMode) " · מסך טאבלט" else ""}")
        thread(name = "ultra-encoder-$gen") {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
            encodeLoop(codec, input, cfg.first, cfg.second, gen)
        }
    }

    /** When the phone rotates (mirror mode), rebuild the stream in the new orientation. */
    private val rotationListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY || !running || virtualMode) return
            val m = realMetrics()
            val nowLandscape = m.widthPixels > m.heightPixels
            if (nowLandscape == landscape) return
            val vd = display ?: return
            try {
                val (codec, cfg) = openEncoder(m.widthPixels, m.heightPixels, quality)
                val input = codec.createInputSurface()
                codec.start()
                vd.resize(cfg.first, cfg.second, m.densityDpi)
                vd.surface = input
                Session.log(if (nowLandscape) "הטלפון סובב לרוחב" else "הטלפון סובב לאורך")
                startLoop(codec, input, cfg, nowLandscape)
            } catch (e: Exception) { Session.log("שגיאה בסיבוב: ${e.message}") }
        }
    }

    private fun even16(v: Int) = max(16, (v / 16) * 16)

    /**
     * Try the chosen preset with every low-latency option, then without the optional ones,
     * then progressively lighter presets, until the hardware encoder accepts one.
     */
    private fun openEncoder(dw: Int, dh: Int, quality: Int): Pair<MediaCodec, Triple<Int, Int, Preset>> {
        val chosen = PRESETS[quality]
        val presets = mutableListOf(chosen)
        for (side in listOf(1600, 1280, 960)) if (side < chosen.longSide) presets += chosen.copy(longSide = side, bitrate = minOf(chosen.bitrate, side * 7000))
        presets += Preset("גיבוי", 960, 30, 4_000_000)
        var lastError: Exception? = null
        for (p in presets) for (tuned in listOf(true, false)) {
            val scale = minOf(1.0, p.longSide.toDouble() / max(dw, dh))
            val w = even16((dw * scale).roundToInt()); val h = even16((dh * scale).roundToInt())
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, p.bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, p.fps)
                // Rare keyframes: a big IDR every couple of seconds is a visible hitch. The receiver asks for one when needed.
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10)
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
                if (tuned) {
                    setInteger(MediaFormat.KEY_PRIORITY, 0)
                    setInteger(MediaFormat.KEY_LATENCY, 1)
                    setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                    setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
                    setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                }
            }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            try {
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                if (p !== chosen) Session.log("המקודד לא תמך ב-${chosen.label}; עבר ל-${w}×${h}@${p.fps}")
                if (!tuned) Session.log("המקודד ללא כוונון השהיה נמוכה")
                return codec to Triple(w, h, p)
            } catch (e: Exception) { codec.release(); lastError = e }
        }
        throw IllegalStateException("המקודד סירב לכל ההגדרות: ${lastError?.message}")
    }

    private fun encodeLoop(codec: MediaCodec, input: Surface, width: Int, height: Int, gen: Int) {
        val info = MediaCodec.BufferInfo()
        val current = { running && generation.get() == gen && Session.wire != null }
        var frames = 0; var mark = SystemClock.uptimeMillis()
        try {
            while (current()) {
                val index = codec.dequeueOutputBuffer(info, 10_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val fmt = codec.outputFormat
                    val sps = fmt.getByteBuffer("csd-0")?.let { b -> ByteArray(b.remaining()).also { b.get(it) } }
                    val pps = fmt.getByteBuffer("csd-1")?.let { b -> ByteArray(b.remaining()).also { b.get(it) } }
                    if (sps != null && pps != null) {
                        val cfg = Wire.Packet(Wire.CONFIG, 0, width, height, Wire.packConfig(sps, pps))
                        liveConfig = cfg
                        Session.wire?.send(cfg)
                    }
                } else if (index >= 0) {
                    val buf = codec.getOutputBuffer(index)
                    if (buf != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && generation.get() == gen) {
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        val bytes = ByteArray(info.size); buf.get(bytes)
                        Session.wire?.send(Wire.Packet(Wire.VIDEO, info.presentationTimeUs, width, height, bytes))
                        frames++
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
                val now = SystemClock.uptimeMillis()
                if (now - mark >= 1000) {
                    val fps = (frames * 1000L / (now - mark)).toInt(); frames = 0; mark = now
                    val payload = ByteBuffer.allocate(6).putInt(fps).put((if (virtualMode) 1 else 0).toByte())
                        .put((if (ShizukuBridge.ready) 1 else 0).toByte()).array()
                    Session.wire?.send(Wire.Packet(Wire.SENDER_INFO, 0, 0, 0, payload))
                    Session.senderFps = fps
                }
            }
        } catch (e: Exception) { if (generation.get() == gen) Session.log("השידור נעצר: ${e.message}") }
        finally {
            try { codec.stop() } catch (_: Exception) {}
            try { codec.release() } catch (_: Exception) {}
            try { input.release() } catch (_: Exception) {}
            // Only the newest encoder ends the session; a replaced one (after rotation) just retires.
            if (generation.get() == gen) Handler(Looper.getMainLooper()).post { stopSelf() }
        }
    }

    override fun onDestroy() {
        running = false
        generation.incrementAndGet()
        Session.streaming = false
        liveCodec = null; liveConfig = null
        try { displayManager.unregisterDisplayListener(rotationListener) } catch (_: Exception) {}
        Session.sendAsync(Wire.Packet(Wire.STREAM_END, 0, 0, 0, byteArrayOf()))
        if (virtualMode) {
            try { ShizukuBridge.service?.releaseDisplay() } catch (_: Exception) {}
            TouchInjector.targetDisplay = 0
            virtualMode = false
        }
        try { display?.release() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        Session.log("שידור הופסק")
        super.onDestroy()
    }
}
