package com.ultradisplay.app

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/** Process-wide connection state. Survives Activity recreation; released on USB detach. */
object Session {
    enum class Mode { SEND, RECEIVE }
    enum class Link { IDLE, SEARCHING, CONNECTING, CONNECTED, ERROR }

    @Volatile var wire: Wire? = null
    @Volatile var mode: Mode = Mode.SEND
    @Volatile var link: Link = Link.IDLE
    @Volatile var usbRole: String = ""
    @Volatile var status: String = tr("ממתין לחיבור כבל", "Waiting for cable")
    @Volatile var rttMs: Double = 0.0
    @Volatile var streaming: Boolean = false
    @Volatile var streamInfo: String = ""

    // What the other device told us in HELLO
    @Volatile var peerMode: Mode? = null
    @Volatile var peerName: String = ""
    @Volatile var peerW = 0; @Volatile var peerH = 0; @Volatile var peerDpi = 0

    // Sender diagnostics, as reported to the receiver once a second
    @Volatile var senderFps = 0
    @Volatile var senderVirtual = false
    @Volatile var senderShizuku = false
    @Volatile var adaptivePercent = 100

    /** Last stream configuration received (receiver side), so a viewer opened later can start decoding. */
    @Volatile var lastConfig: Wire.Packet? = null
    /** Set by the viewer while it is decoding. */
    @Volatile var videoSink: ((Wire.Packet) -> Unit)? = null

    private var app: Context? = null
    private val control = Executors.newSingleThreadExecutor { r -> Thread(r, "usb-control").apply { priority = Thread.MAX_PRIORITY } }
    private val logLines = ArrayDeque<String>()
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun init(context: Context) { app = context.applicationContext }

    fun log(message: String) {
        synchronized(logLines) {
            logLines.addLast("${clock.format(Date())}  $message")
            while (logLines.size > 60) logLines.removeFirst()
        }
    }

    fun logText(lines: Int): String = synchronized(logLines) { logLines.toList().takeLast(lines).joinToString("\n") }

    fun set(newLink: Link, newStatus: String) {
        if (newStatus != status || newLink != link) {
            link = newLink; status = newStatus
            if (newLink == Link.ERROR) { log("⚠ $newStatus"); ErrorLog.record(ErrorLog.Kind.ERROR, newStatus) }
        }
    }

    fun attach(w: Wire, role: String) {
        wire?.close()
        wire = w; usbRole = role; peerMode = null; peerName = ""; lastConfig = null; rttMs = 0.0
        set(Link.CONNECTED, tr("מחובר בכבל ($role)", "Connected by cable ($role)"))
        log("חיבור נוצר — המכשיר הזה הוא $role")
        thread(name = "usb-reader") { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY); readLoop(w) }
        thread(name = "usb-ping") { pingLoop(w) }
        sendHello()
        app?.let { CrashReporter.sendToPeer(it) }
    }

    fun detach(reason: String) {
        val w = wire ?: return
        wire = null
        w.close()
        peerMode = null; peerName = ""; lastConfig = null; senderFps = 0
        AudioSink.stop()
        set(Link.SEARCHING, reason)
        log(reason)
    }

    /** HELLO: mode(1) screenW(4) screenH(4) dpi(4) name(utf8). Screen size is reported landscape. */
    fun sendHello() {
        val m = DisplayMetrics()
        app?.let {
            @Suppress("DEPRECATION")
            (it.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(m)
        }
        val name = Build.MODEL.toByteArray(Charsets.UTF_8)
        val payload = ByteBuffer.allocate(13 + name.size)
            .put((if (mode == Mode.SEND) 0 else 1).toByte())
            .putInt(maxOf(m.widthPixels, m.heightPixels)).putInt(minOf(m.widthPixels, m.heightPixels)).putInt(m.densityDpi)
            .put(name).array()
        sendAsync(Wire.Packet(Wire.HELLO, 0, 0, 0, payload))
    }

    /** Ordered, non-blocking send for control traffic (touch, keys, hello). */
    fun sendAsync(p: Wire.Packet) {
        val w = wire ?: return
        control.execute { try { w.send(p) } catch (_: Exception) {} }
    }

    private fun readLoop(w: Wire) {
        try {
            while (wire === w) {
                val p = w.receive()
                when (p.type) {
                    Wire.HELLO -> onHello(p.bytes)
                    Wire.PING -> w.send(Wire.Packet(Wire.PONG, p.timestampUs, 0, 0, byteArrayOf()))
                    Wire.PONG -> rttMs = (SystemClock.elapsedRealtimeNanos() - p.timestampUs) / 1e6
                    Wire.TOUCH2 -> TouchInjector.onTouch(p.bytes)
                    Wire.TOUCH -> RemoteTouchService.onRemotePacket(p.bytes)
                    Wire.KEY -> if (p.bytes.size >= 4) TouchInjector.onKey(ByteBuffer.wrap(p.bytes).int)
                    Wire.CRASH_REPORT -> {
                        val text = String(p.bytes, Charsets.UTF_8)
                        val code = text.lineSequence().firstOrNull()?.removePrefix("CODE ") ?: "?"
                        log(tr("⚠ המכשיר השני קרס בפעם הקודמת: ", "⚠ The other device crashed last time: ") + code)
                        ErrorLog.record(ErrorLog.Kind.PEER_CRASH, "${peerName.ifBlank { "peer" }}: $code", null, text)
                    }
                    Wire.KEY2 -> TouchInjector.onKey2(p.bytes)
                    Wire.MOTION2 -> TouchInjector.onMotion2(p.bytes)
                    Wire.BITRATE -> if (p.bytes.size >= 4) CaptureService.setBitratePercent(ByteBuffer.wrap(p.bytes).int)
                    Wire.STATS -> CaptureService.requestKeyframe()
                    Wire.SENDER_INFO -> if (p.bytes.size >= 6) {
                        val b = ByteBuffer.wrap(p.bytes)
                        senderFps = b.int; senderVirtual = b.get().toInt() == 1; senderShizuku = b.get().toInt() == 1
                    }
                    Wire.CONFIG -> {
                        lastConfig = p
                        log("התקבל שידור ${p.width}×${p.height}")
                        videoSink?.invoke(p)
                    }
                    Wire.VIDEO -> videoSink?.invoke(p)
                    Wire.AUDIO -> if (mode == Mode.RECEIVE) AudioSink.onPacket(p.bytes)
                    Wire.STREAM_END -> { lastConfig = null; senderFps = 0; AudioSink.stop(); log("המכשיר השני עצר את השידור") }
                }
            }
        } catch (e: Exception) {
            if (wire === w) detach("החיבור נקטע: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun onHello(bytes: ByteArray) {
        if (bytes.size < 13) return
        val b = ByteBuffer.wrap(bytes)
        peerMode = if (b.get().toInt() == 0) Mode.SEND else Mode.RECEIVE
        peerW = b.int; peerH = b.int; peerDpi = b.int
        peerName = String(bytes, 13, bytes.size - 13, Charsets.UTF_8).ifBlank { "מכשיר" }
        log("המכשיר השני: $peerName · ${peerW}×${peerH} (${if (peerMode == Mode.SEND) "משדר" else "מציג"})")
    }

    private fun pingLoop(w: Wire) {
        while (wire === w) {
            try {
                w.send(Wire.Packet(Wire.PING, SystemClock.elapsedRealtimeNanos(), 0, 0, byteArrayOf()))
                Thread.sleep(700)
            } catch (_: Exception) { break }
        }
    }
}
