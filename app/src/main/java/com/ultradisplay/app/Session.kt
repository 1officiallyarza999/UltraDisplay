package com.ultradisplay.app

import android.os.SystemClock
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList

/** Connection belongs to the process, not an Activity; released on USB detach or stop. */
object Session {
    @Volatile var wire: Wire? = null
    @Volatile var role: String = "Disconnected"
    @Volatile var status: String = "Plug in USB-C cable"
    @Volatile var rttMs: Double = 0.0
    val listeners = CopyOnWriteArrayList<() -> Unit>()
    fun update(value: String) { status = value; listeners.forEach { it.invoke() } }
    fun attach(w: Wire, newRole: String) {
        wire?.close(); wire = w; role = newRole; update("USB connected ($role)")
    }
    fun detach() { wire?.close(); wire = null; role = "Disconnected"; update("USB disconnected") }
    fun senderLoop() { // runs only on phone, reads touch + latency probes
        val current = wire ?: return
        try {
            while (wire === current) {
                val packet = current.receive()
                when (packet.type) {
                    Wire.TOUCH -> RemoteTouchService.onRemotePacket(packet.bytes)
                    Wire.PING -> current.send(Wire.Packet(Wire.PONG, packet.timestampUs, 0, 0, byteArrayOf()))
                    Wire.STATS -> CaptureService.requestKeyframe()
                }
            }
        } catch (_: Exception) { if (wire === current) detach() }
    }
}
