package com.ultradisplay.app

import android.os.Build
import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/** Process-wide connection state. Survives Activity recreation; released on USB detach. */
object Session {
    enum class Mode { SEND, RECEIVE }
    enum class Link { IDLE, SEARCHING, CONNECTING, CONNECTED, ERROR }

    @Volatile var wire: Wire? = null
    @Volatile var mode: Mode = Mode.SEND
    @Volatile var link: Link = Link.IDLE
    @Volatile var usbRole: String = ""
    @Volatile var status: String = "ממתין לחיבור כבל"
    @Volatile var peerMode: Mode? = null
    @Volatile var peerName: String = ""
    @Volatile var rttMs: Double = 0.0
    @Volatile var streaming: Boolean = false
    @Volatile var streamInfo: String = ""

    /** Last stream configuration received (receiver side), so a viewer opened later can start decoding. */
    @Volatile var lastConfig: Wire.Packet? = null
    /** Set by the viewer while it is decoding. */
    @Volatile var videoSink: ((Wire.Packet) -> Unit)? = null

    private val logLines = ArrayDeque<String>()
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

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
            if (newLink == Link.ERROR) log("⚠ $newStatus")
        }
    }

    fun attach(w: Wire, role: String) {
        wire?.close()
        wire = w; usbRole = role; peerMode = null; peerName = ""; lastConfig = null; rttMs = 0.0
        set(Link.CONNECTED, "מחובר בכבל ($role)")
        log("חיבור נוצר — המכשיר הזה הוא $role")
        thread(name = "usb-reader") { readLoop(w) }
        thread(name = "usb-ping") { pingLoop(w) }
        sendHello()
    }

    fun detach(reason: String) {
        val w = wire ?: return
        wire = null
        w.close()
        peerMode = null; peerName = ""; lastConfig = null
        set(Link.SEARCHING, reason)
        log(reason)
    }

    fun sendHello() {
        val w = wire ?: return
        val name = Build.MODEL.toByteArray(Charsets.UTF_8)
        val payload = ByteArray(1 + name.size)
        payload[0] = if (mode == Mode.SEND) 0 else 1
        System.arraycopy(name, 0, payload, 1, name.size)
        thread(name = "usb-hello") { try { w.send(Wire.Packet(Wire.HELLO, 0, 0, 0, payload)) } catch (_: Exception) {} }
    }

    fun sendAsync(p: Wire.Packet) {
        val w = wire ?: return
        thread(name = "usb-send") { try { w.send(p) } catch (_: Exception) {} }
    }

    private fun readLoop(w: Wire) {
        try {
            while (wire === w) {
                val p = w.receive()
                when (p.type) {
                    Wire.HELLO -> {
                        peerMode = if (p.bytes.isNotEmpty() && p.bytes[0].toInt() == 0) Mode.SEND else Mode.RECEIVE
                        peerName = if (p.bytes.size > 1) String(p.bytes, 1, p.bytes.size - 1, Charsets.UTF_8) else "מכשיר"
                        log("המכשיר השני: $peerName (${if (peerMode == Mode.SEND) "משדר" else "מציג"})")
                    }
                    Wire.PING -> w.send(Wire.Packet(Wire.PONG, p.timestampUs, 0, 0, byteArrayOf()))
                    Wire.PONG -> rttMs = (SystemClock.elapsedRealtimeNanos() - p.timestampUs) / 1e6
                    Wire.TOUCH -> RemoteTouchService.onRemotePacket(p.bytes)
                    Wire.STATS -> CaptureService.requestKeyframe()
                    Wire.CONFIG -> {
                        lastConfig = p
                        log("התקבל שידור ${p.width}×${p.height}")
                        videoSink?.invoke(p)
                    }
                    Wire.VIDEO -> videoSink?.invoke(p)
                    Wire.STREAM_END -> { lastConfig = null; log("המכשיר השני עצר את השידור") }
                }
            }
        } catch (e: Exception) {
            if (wire === w) detach("החיבור נקטע: ${e.message ?: e.javaClass.simpleName}")
        }
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
