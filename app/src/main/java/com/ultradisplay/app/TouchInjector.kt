package com.ultradisplay.app

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import java.nio.ByteBuffer

/**
 * Sending device: turns real-time touch packets from the tablet into touchscreen events.
 * With Shizuku: true multi-touch injection (down / move / up, up to 10 fingers) — what games need.
 * Without it: falls back to Accessibility taps and swipes on finger-up.
 */
object TouchInjector {
    /** 0 = the phone's own screen (mirror mode); otherwise the tablet-sized virtual display. */
    @Volatile var targetDisplay = 0
    @Volatile var targetW = 0
    @Volatile var targetH = 0

    private var app: Context? = null
    private var screenW = 1440; private var screenH = 3120
    private var downTime = 0L
    private val props = Array(MAX) { MotionEvent.PointerProperties() }
    private val coords = Array(MAX) { MotionEvent.PointerCoords() }
    private var fbX = 0f; private var fbY = 0f; private var fbAt = 0L

    private const val MAX = 10

    fun init(context: Context) { app = context.applicationContext }

    private fun refreshScreen() {
        val ctx = app ?: return
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        (ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(m)
        screenW = m.widthPixels; screenH = m.heightPixels
    }

    /** Payload: action(1) actionIndex(1) count(1) then count × [id(1) x(4) y(4)], x/y normalised 0..1. */
    fun onTouch(bytes: ByteArray) {
        if (bytes.size < 3) return
        val b = ByteBuffer.wrap(bytes)
        val action = b.get().toInt()
        val index = b.get().toInt()
        val count = b.get().toInt().coerceIn(1, MAX)
        if (bytes.size < 3 + count * 9) return
        if (action == MotionEvent.ACTION_DOWN && targetDisplay == 0) refreshScreen()
        val w = if (targetDisplay != 0) targetW else screenW
        val h = if (targetDisplay != 0) targetH else screenH
        var nx0 = 0f; var ny0 = 0f
        for (i in 0 until count) {
            val id = b.get().toInt()
            val x = b.float.coerceIn(0f, 1f); val y = b.float.coerceIn(0f, 1f)
            if (i == 0) { nx0 = x; ny0 = y }
            props[i].clear(); props[i].id = id; props[i].toolType = MotionEvent.TOOL_TYPE_FINGER
            coords[i].clear(); coords[i].x = x * w; coords[i].y = y * h; coords[i].pressure = 1f; coords[i].size = 1f
        }

        val shell = ShizukuBridge.service
        if (shell == null) { fallback(action, nx0, ny0); return }

        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downTime = now
        val full = if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP)
            action or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT) else action
        val ev = MotionEvent.obtain(downTime, now, full, count, props, coords, 0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { shell.injectTouch(ev, targetDisplay) } catch (_: Exception) {} finally { ev.recycle() }
    }

    /** KEY2: action(1) source(4) keyCode(4) meta(4) repeat(4) scanCode(4) — keyboards and gamepad buttons. */
    fun onKey2(bytes: ByteArray) {
        val shell = ShizukuBridge.service ?: return
        if (bytes.size < 21) return
        val b = ByteBuffer.wrap(bytes)
        val action = b.get().toInt(); val source = b.int; val code = b.int; val meta = b.int; val repeat = b.int; val scan = b.int
        val now = SystemClock.uptimeMillis()
        val ev = KeyEvent(now, now, action, code, repeat, meta, android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, scan, 0, source)
        try { shell.injectKeyEvent(ev, targetDisplay) } catch (_: Exception) {}
    }

    /**
     * MOTION2: source(4) action(4) x(4f) y(4f) count(1) then count × [axis(1) value(4f)].
     * Mouse wheel (x/y normalised to the screen) and gamepad sticks/triggers.
     */
    fun onMotion2(bytes: ByteArray) {
        val shell = ShizukuBridge.service ?: return
        if (bytes.size < 17) return
        val b = ByteBuffer.wrap(bytes)
        val source = b.int; val action = b.int; val nx = b.float; val ny = b.float
        val n = (b.get().toInt() and 0xff).coerceAtMost((bytes.size - 17) / 5)
        if (targetDisplay == 0) refreshScreen()
        val w = if (targetDisplay != 0) targetW else screenW
        val h = if (targetDisplay != 0) targetH else screenH
        val mouse = source and InputDevice.SOURCE_MOUSE == InputDevice.SOURCE_MOUSE
        val p = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = if (mouse) MotionEvent.TOOL_TYPE_MOUSE else MotionEvent.TOOL_TYPE_UNKNOWN })
        val c = arrayOf(MotionEvent.PointerCoords().apply { x = nx * w; y = ny * h })
        repeat(n) { val axis = b.get().toInt() and 0xff; c[0].setAxisValue(axis, b.float) }
        val now = SystemClock.uptimeMillis()
        val ev = MotionEvent.obtain(now, now, action, 1, p, c, 0, 0, 1f, 1f, 0, 0, source, 0)
        try { shell.injectTouch(ev, targetDisplay) } catch (_: Exception) {} finally { ev.recycle() }
    }

    fun onKey(keyCode: Int) {
        val shell = ShizukuBridge.service ?: return
        if (keyCode == KeyEvent.KEYCODE_HOME && targetDisplay != 0) {
            Thread { ShizukuBridge.launchHome(targetDisplay) }.start(); return
        }
        try { shell.injectKey(keyCode, targetDisplay) } catch (_: Exception) {}
    }

    /** Accessibility fallback: single-finger tap or swipe, delivered when the finger lifts. */
    private fun fallback(action: Int, x: Float, y: Float) {
        when (action) {
            MotionEvent.ACTION_DOWN -> { fbX = x; fbY = y; fbAt = SystemClock.uptimeMillis() }
            MotionEvent.ACTION_UP -> {
                val p = ByteBuffer.allocate(20).putFloat(fbX).putFloat(fbY).putFloat(x).putFloat(y)
                    .putInt((SystemClock.uptimeMillis() - fbAt).toInt().coerceIn(40, 1200))
                RemoteTouchService.onRemotePacket(p.array())
            }
        }
    }
}
