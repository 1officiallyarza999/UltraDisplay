package com.ultradisplay.app

import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import java.lang.reflect.Method

/**
 * Shizuku user service. Shizuku starts this class in its own process with shell (adb) identity,
 * the same privilege level scrcpy uses. It can create trusted virtual displays and inject input.
 */
class ShellService : IUltraShell.Stub {
    private val context: Context?

    constructor() : super() { context = null }
    constructor(context: Context) : super() { this.context = context }

    private var display: VirtualDisplay? = null

    private val inputManager: Any by lazy {
        if (Build.VERSION.SDK_INT >= 34)
            Class.forName("android.hardware.input.InputManagerGlobal").getDeclaredMethod("getInstance").invoke(null)!!
        else
            Class.forName("android.hardware.input.InputManager").getDeclaredMethod("getInstance").invoke(null)!!
    }
    private val injectMethod: Method by lazy {
        inputManager.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
    }
    private val setDisplayId: Method by lazy {
        InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
    }

    override fun destroy() {
        releaseDisplay()
        System.exit(0)
    }

    override fun uid(): Int = Process.myUid()

    override fun createDisplay(surface: Surface, width: Int, height: Int, dpi: Int): Int {
        releaseDisplay()
        val ctx = context ?: throw IllegalStateException("Shizuku is too old: update Shizuku to v13 or newer")
        val ctor = DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
        ctor.isAccessible = true
        val dm = ctor.newInstance(ShellContext(ctx))

        val base = PUBLIC or PRESENTATION or OWN_CONTENT_ONLY or SUPPORTS_TOUCH or DESTROY_CONTENT_ON_REMOVAL
        var full = base
        if (Build.VERSION.SDK_INT >= 33) full = full or TRUSTED or OWN_DISPLAY_GROUP or ALWAYS_UNLOCKED or TOUCH_FEEDBACK_DISABLED
        if (Build.VERSION.SDK_INT >= 34) full = full or OWN_FOCUS or DEVICE_DISPLAY_GROUP

        val vd = try {
            dm.createVirtualDisplay("UltraDisplay Tab", width, height, dpi, surface, full)
        } catch (e: SecurityException) {
            dm.createVirtualDisplay("UltraDisplay Tab", width, height, dpi, surface, base)
        } ?: throw IllegalStateException("createVirtualDisplay returned null")
        display = vd
        return vd.display.displayId
    }

    override fun releaseDisplay() {
        try { display?.release() } catch (_: Exception) {}
        display = null
    }

    override fun injectTouch(event: MotionEvent, displayId: Int) {
        try {
            setDisplayId.invoke(event, displayId)
            injectMethod.invoke(inputManager, event, INJECT_ASYNC)
        } catch (_: Exception) {
        } finally { event.recycle() }
    }

    override fun injectKey(keyCode: Int, displayId: Int) {
        val now = SystemClock.uptimeMillis()
        for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val ev = KeyEvent(now, now, action, keyCode, 0, 0, KeyCharacterMapVirtual, 0, 0, InputDevice.SOURCE_KEYBOARD)
            try {
                setDisplayId.invoke(ev, displayId)
                injectMethod.invoke(inputManager, ev, INJECT_ASYNC)
            } catch (_: Exception) {}
        }
    }

    override fun exec(command: String): String = try {
        val p = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        out.take(4000)
    } catch (e: Exception) { "error: ${e.message}" }

    /** Presents as com.android.shell so the system accepts the shell uid as the display owner. */
    private class ShellContext(base: Context) : ContextWrapper(base) {
        override fun getPackageName(): String = SHELL
        override fun getOpPackageName(): String = SHELL
        override fun getApplicationContext(): Context = this
        override fun getAttributionSource(): AttributionSource =
            if (Build.VERSION.SDK_INT >= 31) AttributionSource.Builder(Process.myUid()).setPackageName(SHELL).build()
            else super.getAttributionSource()
    }

    companion object {
        private const val SHELL = "com.android.shell"
        private const val INJECT_ASYNC = 0
        private const val KeyCharacterMapVirtual = -1
        // DisplayManager.VIRTUAL_DISPLAY_FLAG_* (several are hidden in the public SDK)
        private const val PUBLIC = 1
        private const val PRESENTATION = 1 shl 1
        private const val OWN_CONTENT_ONLY = 1 shl 3
        private const val SUPPORTS_TOUCH = 1 shl 6
        private const val DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
        private const val TRUSTED = 1 shl 10
        private const val OWN_DISPLAY_GROUP = 1 shl 11
        private const val ALWAYS_UNLOCKED = 1 shl 12
        private const val TOUCH_FEEDBACK_DISABLED = 1 shl 13
        private const val OWN_FOCUS = 1 shl 14
        private const val DEVICE_DISPLAY_GROUP = 1 shl 15
    }
}
