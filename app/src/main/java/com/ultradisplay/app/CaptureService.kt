package com.ultradisplay.app

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.Surface
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/** Only runs on the PHONE, after the user grants fresh MediaProjection permission. */
class CaptureService : Service() {
    companion object {
        const val ACTION_START = "ultra.START"; const val ACTION_STOP = "ultra.STOP"
        const val EXTRA_RESULT = "resultCode"; const val EXTRA_DATA = "resultData"
        const val CHANNEL = "ultradisplay_capture"
        @Volatile private var liveCodec: MediaCodec? = null
        fun requestKeyframe() {
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
            NotificationChannel(CHANNEL, "Screen sharing", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle("UltraDisplay")
            .setContentText("S25 Ultra is sharing screen over USB").setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(7, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(7, notification)
        val result = intent?.getIntExtra(EXTRA_RESULT, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val data = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            else intent?.getParcelableExtra(EXTRA_DATA) as? Intent
        if (result != Activity.RESULT_OK || data == null || Session.wire == null) {
            Session.update("Connect USB and approve capture on S25"); stopSelf(); return START_NOT_STICKY
        }
        try {
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(result, data)
            projection!!.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { Session.update("Screen permission ended"); stopSelf() }
            }, Handler(Looper.getMainLooper()))
            // Tab A8 native aspect 16:10. The S25 display capture is letterboxed by Android when needed.
            val width = 1920; val height = 1200
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 14_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 60)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                if (Build.VERSION.SDK_INT >= 26) setInteger(MediaFormat.KEY_LATENCY, 0)
            }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            try { codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE) }
            catch (e: Exception) {
                codec.release()
                throw IllegalStateException("1920×1200@60 unavailable on encoder: ${e.message}. Profile fallback not yet implemented")
            }
            encoder = codec
            liveCodec = codec
            surface = codec.createInputSurface()
            codec.start()
            display = projection!!.createVirtualDisplay("UltraDisplay", width, height,
                resources.displayMetrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface, null, Handler(Looper.getMainLooper()))
            running = true
            Session.update("ULTRA MAX • 1920×1200 • 60fps target • 14Mbps")
            thread(name="ultra-encoder") { encodeLoop(codec, width, height) }
        } catch (e: Exception) { Session.update("Capture error: ${e.message}"); stopSelf() }
        return START_NOT_STICKY
    }
    private fun encodeLoop(codec: MediaCodec, width: Int, height: Int) {
        val info = MediaCodec.BufferInfo()
        var lastConfig: ByteArray? = null
        try {
            while (running && Session.wire != null) {
                val index = codec.dequeueOutputBuffer(info, 9000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val fmt = codec.outputFormat
                    val sps = fmt.getByteBuffer("csd-0")?.let { b -> ByteArray(b.remaining()).also { b.get(it) } }
                    val pps = fmt.getByteBuffer("csd-1")?.let { b -> ByteArray(b.remaining()).also { b.get(it) } }
                    if (sps != null && pps != null) {
                        lastConfig = Wire.packConfig(sps, pps)
                        Session.wire?.send(Wire.Packet(Wire.CONFIG, 0, width, height, lastConfig))
                    }
                } else if (index >= 0) {
                    val buf = codec.getOutputBuffer(index)
                    if (buf != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        val bytes = ByteArray(info.size); buf.get(bytes)
                        // Timestamp is encoder presentation time; not comparable between different device clocks.
                        Session.wire?.send(Wire.Packet(Wire.VIDEO, info.presentationTimeUs, width, height, bytes))
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } catch (e: Exception) { Session.update("Encoder stopped: ${e.message}") }
        finally { stopSelf() }
    }
    override fun onDestroy() {
        running = false
        try { display?.release() } catch (_: Exception) {}
        try { surface?.release() } catch (_: Exception) {}
        try { encoder?.stop() } catch (_: Exception) {}
        try { encoder?.release() } catch (_: Exception) {}
        liveCodec = null
        try { projection?.stop() } catch (_: Exception) {}
        super.onDestroy()
    }
}
