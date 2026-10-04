package com.ultradisplay.app

import android.app.*
import android.content.*
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.usb.UsbManager
import android.media.projection.MediaProjectionManager
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import java.nio.ByteBuffer
import kotlin.concurrent.thread
import kotlin.math.*

class MainActivity : Activity() {
    private lateinit var link: UsbLink
    private lateinit var status: TextView
    private lateinit var info: TextView
    private lateinit var root: FrameLayout
    private var decoder: TabletDecoder? = null
    private var surface: SurfaceView? = null
    private var tabMode = false
    private var downX = 0f; private var downY = 0f; private var downAt = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val pulse = object : Runnable {
        override fun run() {
            if (::status.isInitialized) {
                status.text = Session.status
                info.text = if (tabMode) "USB RTT: ${"%.1f".format(Session.rttMs)}ms  |  Decoded: ${decoder?.decoded ?: 0}  |  Dropped: ${decoder?.dropped ?: 0}\nLocal decode queue: ${"%.1f".format(decoder?.lastQueueDelayMs ?: 0.0)}ms"
                    else "USB role: ${Session.role}  |  Screen sharing requires Android approval"
            }
            handler.postDelayed(this, 350)
        }
    }
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when(intent.action) {
                UsbLink.PERMISSION -> if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    if (tabMode) link.attachTablet() else link.attachPhone()
                } else Session.update("USB permission denied")
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> if (tabMode) handler.postDelayed({ link.attachTablet() }, 650)
                UsbManager.ACTION_USB_ACCESSORY_ATTACHED -> if (!tabMode) handler.postDelayed({ link.attachPhone() }, 250)
                UsbManager.ACTION_USB_DEVICE_DETACHED, UsbManager.ACTION_USB_ACCESSORY_DETACHED -> {
                    Session.detach(); decoder?.stop(); decoder = null
                }
            }
        }
    }
    companion object { const val REQUEST_CAPTURE = 91 }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(8, 12, 25)
        window.navigationBarColor = Color.rgb(8, 12, 25)
        link = UsbLink(this) { w, role ->
            Session.attach(w, role)
            if (role == "PHONE") thread(name="phone-input") { Session.senderLoop() }
            else thread(name="tablet-input") { tabletLoop(w) }
        }
        val filter = IntentFilter().apply {
            addAction(UsbLink.PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED); addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(usbReceiver, filter)
        showHome()
        handler.post(pulse)
    }
    private fun bg(): GradientDrawable = GradientDrawable(GradientDrawable.Orientation.TL_BR,
        intArrayOf(Color.rgb(7,11,24), Color.rgb(18,20,44), Color.rgb(9,28,38)))
    private fun label(text: String, size: Float, bold: Boolean = false, color: Int = Color.WHITE): TextView = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setPadding(0, 12, 0, 12)
    }
    private fun button(text: String, color: Int, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text; isAllCaps = false; textSize = 17f
        setTextColor(Color.WHITE)
        background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(color, Color.rgb(26,118,143))).apply {
            cornerRadius = 28f
        }
        setPadding(18, 16, 18, 16); setOnClickListener { onClick() }
    }
    private fun showHome() {
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(36, 45, 36, 25); background = bg() }
        scroll.addView(col)
        col.addView(label("ULTRA / DISPLAY", 29f, true))
        col.addView(label("WIRED • LOW LATENCY • PRIVATE", 12f, false, Color.CYAN))
        col.addView(label("Connect your S25 Ultra to Galaxy Tab A8 with one USB-C cable.", 16f))
        status = label(Session.status, 17f, true, Color.rgb(121,239,211)); col.addView(status)
        info = label("Select your device role", 13f); col.addView(info)
        col.addView(button("①  TAB A8  ·  RECEIVE USB", Color.rgb(27,88,200)) { startTablet() })
        col.addView(label("The tablet is the USB host. After connecting, grant USB access on the tablet.", 12f, false, Color.LTGRAY))
        col.addView(button("②  S25 ULTRA  ·  SEND USB", Color.rgb(103,55,191)) { startPhone() })
        col.addView(label("Start the tablet first; approve the phone's accessory prompt if it appears.", 12f, false, Color.LTGRAY))
        col.addView(button("ULTRA MAX  ·  START 60 FPS", Color.rgb(20,161,112)) { beginUltraMax() })
        col.addView(label("Target: 1920×1200 / H.264 hardware / 14 Mbps. Actual performance depends on both devices.", 12f, false, Color.LTGRAY))
        col.addView(button("ENABLE PHONE TOUCH CONTROL", Color.rgb(65,84,106)) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        col.addView(button("STOP SCREEN CAPTURE", Color.rgb(124,54,64)) {
            startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
        })
        setContentView(scroll)
    }
    private fun startTablet() {
        tabMode = true
        Session.update("Tab A8 receiver • waiting for USB")
        showViewer()
        link.attachTablet()
    }
    private fun startPhone() {
        tabMode = false
        Session.update("S25 source • waiting for tablet USB host")
        link.attachPhone()
    }
    private fun beginUltraMax() {
        if (Session.role != "PHONE") {
            Session.update("On S25: connect USB first, then press ULTRA MAX")
            return
        }
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE)
    }
    @Deprecated("For compatibility with minSdk 26; MediaProjection consent is requested on each session")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CAPTURE && resultCode == RESULT_OK && data != null) {
            val intent = Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_START)
                .putExtra(CaptureService.EXTRA_RESULT, resultCode).putExtra(CaptureService.EXTRA_DATA, data)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        } else if (requestCode == REQUEST_CAPTURE) Session.update("Screen sharing permission denied")
    }
    @Suppress("ClickableViewAccessibility")
    private fun showViewer() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val view = SurfaceView(this)
        surface = view
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        val overlay = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(22, 10, 22, 10)
            setBackgroundColor(0xCC081020.toInt()) }
        status = label(Session.status, 13f, true, Color.rgb(105,250,200))
        info = label("Waiting for video…", 11f)
        overlay.addView(status); overlay.addView(info)
        overlay.addView(button("RECONNECT USB", Color.rgb(30,80,130)) { link.attachTablet() })
        overlay.addView(button("HIDE CONTROLS", Color.rgb(35,43,57)) { overlay.visibility = View.GONE })
        root.addView(overlay, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        // Three-finger tap reveals controls without sending a remote gesture.
        var multi = false
        view.setOnTouchListener { v, ev ->
            if (ev.pointerCount >= 3) { overlay.visibility = View.VISIBLE; multi = true; return@setOnTouchListener true }
            if (multi) { if (ev.actionMasked == MotionEvent.ACTION_UP) multi = false; return@setOnTouchListener true }
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = ev.x / v.width; downY = ev.y / v.height; downAt = SystemClock.uptimeMillis() }
                MotionEvent.ACTION_UP -> {
                    val buf = ByteBuffer.allocate(20).putFloat(downX).putFloat(downY)
                        .putFloat(ev.x / v.width).putFloat(ev.y / v.height)
                        .putInt((SystemClock.uptimeMillis() - downAt).toInt().coerceIn(65, 1200))
                    thread(name="remote-touch") { try { Session.wire?.send(Wire.Packet(Wire.TOUCH, 0, 0, 0, buf.array())) } catch (_: Exception) {} }
                    true
                }
            }
            true
        }
        setContentView(root)
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }
    private fun tabletLoop(w: Wire) {
        while (surface?.holder?.surface?.isValid != true && Session.wire === w) Thread.sleep(40)
        if (Session.wire !== w) return
        val target = surface?.holder?.surface ?: return
        val d = TabletDecoder(target)
        decoder = d
        thread(name="usb-ping") {
            while (Session.wire === w) {
                try {
                    val t = SystemClock.elapsedRealtimeNanos()
                    w.send(Wire.Packet(Wire.PING, t, 0, 0, byteArrayOf()))
                    Thread.sleep(1000)
                } catch (_: Exception) { break }
            }
        }
        try {
            while (Session.wire === w) {
                val p = w.receive()
                when (p.type) {
                    Wire.CONFIG -> d.setup(p)
                    Wire.VIDEO -> d.enqueue(p)
                    Wire.PONG -> Session.rttMs = (SystemClock.elapsedRealtimeNanos() - p.timestampUs) / 1e6
                }
            }
        } catch (e: Exception) { Session.update("USB stream ended: ${e.message}") }
        finally { d.stop(); if (Session.wire === w) Session.detach() }
    }
    override fun onResume() {
        super.onResume()
        if (::link.isInitialized) handler.postDelayed({ if (tabMode) link.attachTablet() else link.attachPhone() }, 600)
    }
    override fun onDestroy() {
        handler.removeCallbacks(pulse)
        unregisterReceiver(usbReceiver)
        decoder?.stop(); link.close(); Session.detach()
        super.onDestroy()
    }
}
