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
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.roundToInt

/** Runs only on the sending device, after the user grants fresh screen-capture permission. */
class CaptureService : Service() {
    data class Preset(val label: String, val longSide: Int, val fps: Int, val bitrate: Int)

    companion object {
        const val ACTION_START = "ultra.START"; const val ACTION_STOP = "ultra.STOP"
        const val EXTRA_RESULT = "resultCode"; const val EXTRA_DATA = "resultData"; const val EXTRA_QUALITY = "quality"
        const val CHANNEL = "ultradisplay_capture"
        val PRESETS = listOf(
            Preset("מהיר", 1280, 60, 6_000_000),
            Preset("מאוזן", 1600, 60, 10_000_000),
            Preset("אולטרה", 1920, 60, 14_000_000)
        )
        @Volatile private var liveCodec: MediaCodec? = null
        @Volatile private var liveConfig: Wire.Packet? = null

        /** Receiver asked for a fresh IDR (it opened late or dropped frames): resend config + keyframe. */
        fun requestKeyframe() {
            liveConfig?.let { cfg -> try { Session.wire?.send(cfg) } catch (_: Exception) {} }
            try { liveCodec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
            catch (_: Exception) {}
        }
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    private var surface: Surface? = null
    @Volatile private var running = false

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel(CHANNEL, "שיתוף מסך", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (running) return START_NOT_STICKY
        val stopIntent = PendingIntent.getService(this, 1, Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle("UltraDisplay משדר")
            .setContentText("המסך משותף דרך הכבל").setOngoing(true)
            .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_media_pause), "עצור", stopIntent).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(7, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(7, notification)

        val result = intent?.getIntExtra(EXTRA_RESULT, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val data = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            else intent?.getParcelableExtra(EXTRA_DATA) as? Intent
        val quality = intent?.getIntExtra(EXTRA_QUALITY, 2)?.coerceIn(0, PRESETS.size - 1) ?: 2
        if (result != Activity.RESULT_OK || data == null || Session.wire == null) {
            Session.set(Session.Link.ERROR, "אין חיבור או שלא אושר שיתוף מסך"); stopSelf(); return START_NOT_STICKY
        }
        try {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val proj = manager.getMediaProjection(result, data) ?: throw IllegalStateException("MediaProjection לא זמין")
            projection = proj
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { Session.log("שיתוף המסך הסתיים"); stopSelf() }
            }, Handler(Looper.getMainLooper()))

            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            (getSystemService(DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(metrics)

            val (codec, cfg) = openEncoder(metrics.widthPixels, metrics.heightPixels, quality)
            encoder = codec; liveCodec = codec
            surface = codec.createInputSurface()
            codec.start()
            display = proj.createVirtualDisplay("UltraDisplay", cfg.first, cfg.second, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, Handler(Looper.getMainLooper()))
            running = true
            Session.streaming = true
            Session.streamInfo = "${cfg.first}×${cfg.second} · ${cfg.third.fps}fps · ${cfg.third.bitrate / 1_000_000}Mbps"
            Session.log("משדר ${Session.streamInfo}")
            thread(name = "ultra-encoder") { encodeLoop(codec, cfg.first, cfg.second) }
        } catch (e: Exception) {
            Session.set(Session.Link.ERROR, "שגיאת שידור: ${e.message}"); stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun even16(v: Int) = max(16, (v / 16) * 16)

    /** Try the chosen preset, then progressively lighter ones, until the hardware encoder accepts one. */
    private fun openEncoder(dw: Int, dh: Int, quality: Int): Pair<MediaCodec, Triple<Int, Int, Preset>> {
        val chosen = PRESETS[quality]
        val attempts = mutableListOf(chosen)
        for (side in listOf(1600, 1280, 960)) if (side < chosen.longSide) attempts += chosen.copy(longSide = side, bitrate = minOf(chosen.bitrate, side * 6000))
        attempts += Preset("גיבוי", 960, 30, 4_000_000)
        var lastError: Exception? = null
        for (p in attempts) {
            val scale = minOf(1.0, p.longSide.toDouble() / max(dw, dh))
            val w = even16((dw * scale).roundToInt()); val h = even16((dh * scale).roundToInt())
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, p.bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, p.fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                setInteger(MediaFormat.KEY_LATENCY, 1)
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
            }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            try {
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                if (p !== chosen) Session.log("המקודד לא תמך ב-${chosen.label}; עבר ל-${w}×${h}@${p.fps}")
                return codec to Triple(w, h, p)
            } catch (e: Exception) { codec.release(); lastError = e }
        }
        throw IllegalStateException("המקודד סירב לכל ההגדרות: ${lastError?.message}")
    }

    private fun encodeLoop(codec: MediaCodec, width: Int, height: Int) {
        val info = MediaCodec.BufferInfo()
        try {
            while (running && Session.wire != null) {
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
                    if (buf != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        val bytes = ByteArray(info.size); buf.get(bytes)
                        Session.wire?.send(Wire.Packet(Wire.VIDEO, info.presentationTimeUs, width, height, bytes))
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } catch (e: Exception) { Session.log("השידור נעצר: ${e.message}") }
        finally { Handler(Looper.getMainLooper()).post { stopSelf() } }
    }

    override fun onDestroy() {
        running = false
        Session.streaming = false
        liveCodec = null; liveConfig = null
        Session.sendAsync(Wire.Packet(Wire.STREAM_END, 0, 0, 0, byteArrayOf()))
        try { display?.release() } catch (_: Exception) {}
        try { encoder?.stop() } catch (_: Exception) {}
        try { encoder?.release() } catch (_: Exception) {}
        try { surface?.release() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        Session.log("שידור הופסק")
        super.onDestroy()
    }
}
