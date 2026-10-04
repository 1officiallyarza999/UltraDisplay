package com.ultradisplay.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.content.Context
import android.view.accessibility.AccessibilityEvent
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Explicitly opt-in via Android Accessibility settings. Does not request view content. */
class RemoteTouchService : AccessibilityService() {
    companion object {
        @Volatile private var instance: RemoteTouchService? = null
        fun onRemotePacket(bytes: ByteArray) {
            val service = instance ?: return
            if (bytes.size != 20) return
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val x0 = buffer.float.coerceIn(0f, 1f); val y0 = buffer.float.coerceIn(0f, 1f)
            val x1 = buffer.float.coerceIn(0f, 1f); val y1 = buffer.float.coerceIn(0f, 1f)
            val duration = buffer.int.toLong().coerceIn(50, 1500)
            Handler(Looper.getMainLooper()).post { service.perform(x0, y0, x1, y1, duration) }
        }
    }
    override fun onServiceConnected() { super.onServiceConnected(); instance = this; Session.update("Remote touch permission enabled") }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { instance = null; super.onDestroy() }
    private fun perform(x0: Float, y0: Float, x1: Float, y1: Float, duration: Long) {
        @Suppress("DEPRECATION") val display = resources.displayMetrics
        val path = Path().apply {
            moveTo(x0 * display.widthPixels, y0 * display.heightPixels)
            if (kotlin.math.abs(x0-x1) > .008f || kotlin.math.abs(y0-y1) > .008f)
                lineTo(x1 * display.widthPixels, y1 * display.heightPixels)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }
}
