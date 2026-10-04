package com.ultradisplay.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Opt-in via Android Accessibility settings. Only dispatches taps/drags; never reads screen content. */
class RemoteTouchService : AccessibilityService() {
    companion object {
        @Volatile private var instance: RemoteTouchService? = null

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val me = ComponentName(context, RemoteTouchService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }

        fun onRemotePacket(bytes: ByteArray) {
            val service = instance ?: return
            if (bytes.size != 20) return
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val x0 = b.float.coerceIn(0f, 1f); val y0 = b.float.coerceIn(0f, 1f)
            val x1 = b.float.coerceIn(0f, 1f); val y1 = b.float.coerceIn(0f, 1f)
            val duration = b.int.toLong().coerceIn(40, 1500)
            Handler(Looper.getMainLooper()).post { service.perform(x0, y0, x1, y1, duration) }
        }
    }

    override fun onServiceConnected() { super.onServiceConnected(); instance = this; Session.log("שליטה במגע הופעלה") }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { instance = null; super.onDestroy() }

    private fun perform(x0: Float, y0: Float, x1: Float, y1: Float, duration: Long) {
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(m)
        val path = Path().apply {
            moveTo(x0 * m.widthPixels, y0 * m.heightPixels)
            if (kotlin.math.abs(x0 - x1) > .008f || kotlin.math.abs(y0 - y1) > .008f) lineTo(x1 * m.widthPixels, y1 * m.heightPixels)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }
}
