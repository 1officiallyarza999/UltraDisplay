package com.ultradisplay.app

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.content.res.Configuration
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
import kotlin.math.min

class MainActivity : Activity() {
    companion object { const val REQUEST_CAPTURE = 91 }

    private lateinit var link: UsbLink
    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private var onViewer = false
    private var dismissedConfig: Wire.Packet? = null
    private var lastPoll = 0L
    private var quality = 2

    // Home screen views
    private var dotView: View? = null
    private var statusTitle: TextView? = null
    private var statusDetail: TextView? = null
    private var sendGlass: GlassDrawable? = null
    private var recvGlass: GlassDrawable? = null
    private var sendCheck: TextView? = null
    private var recvCheck: TextView? = null
    private var qualitySection: View? = null
    private val qualityTabs = mutableListOf<Pair<TextView, GlassDrawable>>()
    private var primaryBtn: TextView? = null
    private var touchBtn: TextView? = null
    private var helpCard: View? = null
    private var logView: TextView? = null

    // Viewer views
    private var decoder: TabletDecoder? = null
    private var surfaceView: SurfaceView? = null
    private var viewerRoot: FrameLayout? = null
    private var hud: View? = null
    private var hudText: TextView? = null
    private var hudHideAt = 0L
    private var videoW = 0; private var videoH = 0
    private var fpsMark = 0L; private var fpsFrames = 0L; private var fps = 0.0
    private var downX = 0f; private var downY = 0f; private var downAt = 0L; private var multiTouch = false

    private val tick = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            if (Session.wire == null && now - lastPoll > 1500) { lastPoll = now; link.connect() }
            if (onViewer) updateViewer() else { updateHome(); maybeOpenViewer() }
            handler.postDelayed(this, 250)
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                UsbLink.PERMISSION -> link.onPermissionResult(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> { Session.log("זוהה התקן USB"); handler.postDelayed({ link.connect() }, 400) }
                UsbManager.ACTION_USB_DEVICE_DETACHED, UsbManager.ACTION_USB_ACCESSORY_DETACHED ->
                    if (Session.wire != null) Session.detach("הכבל נותק")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("ultra", MODE_PRIVATE)
        val bigScreen = resources.configuration.smallestScreenWidthDp >= 600
        Session.mode = when (prefs.getString("mode", null)) {
            "SEND" -> Session.Mode.SEND; "RECEIVE" -> Session.Mode.RECEIVE
            else -> if (bigScreen) Session.Mode.RECEIVE else Session.Mode.SEND
        }
        quality = prefs.getInt("quality", 2)
        link = UsbLink(applicationContext)

        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

        val filter = IntentFilter().apply {
            addAction(UsbLink.PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(usbReceiver, filter)

        Session.log("UltraDisplay נפתח · ${Build.MODEL} · Android ${Build.VERSION.RELEASE}")
        handleUsbIntent(intent)
        showHome()
        handler.post(tick)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUsbIntent(intent)
    }

    /** USB_ACCESSORY_ATTACHED is only delivered to activities, never as a broadcast. */
    private fun handleUsbIntent(intent: Intent?) {
        when (intent?.action) {
            UsbManager.ACTION_USB_ACCESSORY_ATTACHED -> { Session.log("המכשיר השני פתח חיבור UltraDisplay"); handler.postDelayed({ link.connect() }, 200) }
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> { Session.log("זוהה מכשיר במצב AOA"); handler.postDelayed({ link.connect() }, 200) }
        }
    }

    override fun onResume() { super.onResume(); link.connect() }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (onViewer) handler.post { fitSurface() } else showHome()
    }

    // ───────────────────────────── Home ─────────────────────────────

    private fun showHome() {
        onViewer = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        showSystemBars()
        qualityTabs.clear()

        val root = FrameLayout(this)
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL
        root.addView(LiquidBackground(this), FrameLayout.LayoutParams(-1, -1))

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; clipToPadding = false; overScrollMode = View.OVER_SCROLL_NEVER }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val width = min(resources.displayMetrics.widthPixels, dpi(600))
        scroll.addView(col, FrameLayout.LayoutParams(width, -2, Gravity.CENTER_HORIZONTAL))
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        root.setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION")
            col.setPadding(dpi(18), insets.systemWindowInsetTop + dpi(18), dpi(18), insets.systemWindowInsetBottom + dpi(28))
            insets
        }

        // Header
        col.addView(label("UltraDisplay", 32f, true).apply { letterSpacing = -0.02f })
        col.addView(label("המסך של הטלפון על הטאבלט · בכבל אחד", 14f, false, Glass.TEXT_2), lp(top = 6))

        // Status
        val status = glassCard(28f, 20)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        dotView = dot(Glass.GREY, 12)
        row.addView(dotView)
        statusTitle = label("", 21f, true)
        row.addView(statusTitle, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dpi(12) })
        status.addView(row)
        statusDetail = label("", 14f, false, Glass.TEXT_2)
        status.addView(statusDetail, lp(top = 10))
        col.addView(status, lp(top = 22))

        // Role
        col.addView(sectionLabel("התפקיד של המכשיר הזה"), lp(top = 26))
        val roles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val (sendCard, sg, sc) = roleCard("משדר", "המסך שלו יוצג במכשיר השני")
        val (recvCard, rg, rc) = roleCard("מציג", "יציג את המסך של המכשיר השני")
        sendGlass = sg; recvGlass = rg; sendCheck = sc; recvCheck = rc
        sendCard.setOnClickListener { setMode(Session.Mode.SEND) }
        recvCard.setOnClickListener { setMode(Session.Mode.RECEIVE) }
        roles.addView(sendCard, LinearLayout.LayoutParams(0, -2, 1f))
        roles.addView(recvCard, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dpi(12) })
        col.addView(roles, lp(top = 10))

        // Quality (sender only)
        val qSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        qSection.addView(sectionLabel("איכות שידור"))
        val seg = glassCard(22f, 5).apply { orientation = LinearLayout.HORIZONTAL }
        CaptureService.PRESETS.forEachIndexed { i, p ->
            val g = GlassDrawable(this, dp(18f))
            val tab = label(p.label, 15f, true).apply {
                gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER
                setPadding(0, dpi(12), 0, dpi(12)); isClickable = true
                setOnClickListener { quality = i; prefs.edit().putInt("quality", i).apply(); updateHome() }
                pressable()
            }
            qualityTabs += tab to g
            seg.addView(tab, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = dpi(4) })
        }
        qSection.addView(seg, lp(top = 10))
        qSection.addView(label("אולטרה = 1920 פיקסלים · 60fps · 14Mbps. אם המכשיר לא עומד בזה, האיכות יורדת אוטומטית.", 12f, false, Glass.TEXT_3), lp(top = 8))
        qualitySection = qSection
        col.addView(qSection, lp(top = 22))

        // Primary action
        primaryBtn = glassButton("", 30f, 19f, Glass.ACCENT_TINT).apply {
            minHeight = dpi(64)
            setOnClickListener { onPrimary() }
        }
        col.addView(primaryBtn, lp(top = 24))

        // Secondary actions
        val secondary = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val reconnect = glassButton("חבר מחדש", 22f, 15f).apply { setOnClickListener { reconnect() } }
        touchBtn = glassButton("", 22f, 15f).apply { setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } }
        secondary.addView(reconnect, LinearLayout.LayoutParams(0, -2, 1f))
        secondary.addView(touchBtn, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dpi(12) })
        col.addView(secondary, lp(top = 12))

        // Setup help (shown until connected)
        val help = glassCard(26f, 20)
        help.addView(label("איך מתחברים", 17f, true))
        listOf(
            "פתח את UltraDisplay בשני המכשירים.",
            "בחר \"משדר\" בטלפון ו\"מציג\" בטאבלט.",
            "חבר כבל USB‑C שתומך בהעברת נתונים (לא כבל טעינה בלבד).",
            "אשר כל חלון \"לאפשר גישה ל-USB\" שמופיע — בשני המכשירים.",
            "לא מתחבר תוך כמה שניות? משוך את שורת ההתראות, לחץ על התראת ה-USB ובחר \"העברת קבצים\". אם יש \"USB נשלט על ידי\" — החלף את הבחירה ונסה שוב."
        ).forEachIndexed { i, s ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            line.addView(label("${i + 1}", 14f, true, Glass.ACCENT), LinearLayout.LayoutParams(dpi(22), -2))
            line.addView(label(s, 14f, false, Glass.TEXT_2), LinearLayout.LayoutParams(0, -2, 1f))
            help.addView(line, lp(top = 10))
        }
        helpCard = help
        col.addView(help, lp(top = 24))

        // Diagnostics
        val diag = glassCard(22f, 16)
        diag.addView(label("פרטי חיבור", 15f, true))
        diag.addView(label("אם משהו לא עובד — צלם את המסך הזה ושלח לי", 12f, false, Glass.TEXT_3), lp(top = 4))
        logView = label("", 11.5f, false, Glass.TEXT_2).apply {
            typeface = Typeface.MONOSPACE
            textDirection = View.TEXT_DIRECTION_ANY_RTL
        }
        diag.addView(logView, lp(top = 10))
        col.addView(diag, lp(top = 16))

        col.addView(label("UltraDisplay v0.2", 11f, false, Glass.TEXT_3).apply { textAlignment = View.TEXT_ALIGNMENT_CENTER }, lp(top = 18))

        setContentView(root)
        updateHome()
    }

    private fun sectionLabel(text: String) = label(text, 13f, true, Glass.TEXT_3).apply { letterSpacing = 0.04f }

    private data class RoleViews(val card: View, val glass: GlassDrawable, val check: TextView)

    private fun roleCard(title: String, sub: String): RoleViews {
        val card = glassCard(24f, 16).apply { isClickable = true; pressable(); minimumHeight = dpi(118) }
        val glass = card.background as GlassDrawable
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(label(title, 19f, true), LinearLayout.LayoutParams(0, -2, 1f))
        val check = label("✓", 13f, true, Color.BLACK).apply {
            gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Glass.ACCENT) }
        }
        top.addView(check, LinearLayout.LayoutParams(dpi(22), dpi(22)))
        card.addView(top)
        card.addView(label(sub, 13f, false, Glass.TEXT_2), lp(top = 8))
        return RoleViews(card, glass, check)
    }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(top) }

    private fun setMode(m: Session.Mode) {
        if (Session.mode == m) return
        Session.mode = m
        prefs.edit().putString("mode", m.name).apply()
        Session.log("תפקיד: ${if (m == Session.Mode.SEND) "משדר" else "מציג"}")
        if (m == Session.Mode.RECEIVE && Session.streaming) stopCapture()
        Session.sendHello()
        updateHome()
    }

    private fun updateHome() {
        val connected = Session.wire != null
        val conflict = connected && Session.peerMode != null && Session.peerMode == Session.mode
        val (color, title) = when {
            conflict -> Glass.AMBER to "שני המכשירים באותו תפקיד"
            Session.streaming -> Glass.GREEN to "משדר עכשיו"
            connected -> Glass.GREEN to if (Session.peerName.isNotEmpty()) "מחובר ל-${Session.peerName}" else "מחובר"
            Session.link == Session.Link.CONNECTING -> Glass.AMBER to "מתחבר…"
            Session.link == Session.Link.ERROR -> Glass.RED to "החיבור נכשל"
            else -> Glass.GREY to "ממתין לכבל"
        }
        (dotView?.background as? GradientDrawable)?.setColor(color)
        statusTitle?.text = title
        statusDetail?.text = when {
            conflict -> if (Session.mode == Session.Mode.SEND) "גם המכשיר השני מוגדר \"משדר\". בחר \"מציג\" באחד מהם." else "גם המכשיר השני מוגדר \"מציג\". בחר \"משדר\" בטלפון."
            Session.streaming -> "${Session.streamInfo} · השהיית כבל ${"%.1f".format(Session.rttMs)}ms"
            connected -> "${Session.usbRole} · השהיית כבל ${"%.1f".format(Session.rttMs)}ms"
            else -> Session.status
        }

        val send = Session.mode == Session.Mode.SEND
        sendGlass?.tint = if (send) Glass.ACCENT_TINT else 0
        recvGlass?.tint = if (!send) Glass.ACCENT_TINT else 0
        sendCheck?.visibility = if (send) View.VISIBLE else View.INVISIBLE
        recvCheck?.visibility = if (!send) View.VISIBLE else View.INVISIBLE
        qualitySection?.visibility = if (send) View.VISIBLE else View.GONE
        qualityTabs.forEachIndexed { i, (tab, g) ->
            tab.background = if (i == quality) g.also { it.tint = Glass.ACCENT_TINT } else null
            tab.setTextColor(if (i == quality) Glass.TEXT else Glass.TEXT_2)
        }

        primaryBtn?.let { b ->
            val (text, tint, enabled) = when {
                send && Session.streaming -> Triple("עצור שידור", Glass.RED_TINT, true)
                send && connected -> Triple("התחל שידור", Glass.ACCENT_TINT, !conflict)
                !send && connected -> Triple(if (Session.lastConfig != null) "פתח תצוגה" else "ממתין שהטלפון יתחיל לשדר", Glass.ACCENT_TINT, true)
                else -> Triple("ממתין לחיבור…", 0, false)
            }
            b.text = text
            (b.background as? GlassDrawable)?.tint = tint
            b.alpha = if (enabled) 1f else 0.55f
        }
        touchBtn?.visibility = if (send) View.VISIBLE else View.GONE
        touchBtn?.text = if (RemoteTouchService.isEnabled(this)) "שליטה במגע ✓" else "הפעל שליטה במגע"
        helpCard?.visibility = if (connected) View.GONE else View.VISIBLE
        logView?.text = Session.logText(12)
    }

    private fun onPrimary() {
        val send = Session.mode == Session.Mode.SEND
        when {
            send && Session.streaming -> stopCapture()
            send && Session.wire != null -> beginCapture()
            !send && Session.wire != null -> { dismissedConfig = null; showViewer() }
            else -> { link.reset(); link.connect() }
        }
    }

    private fun reconnect() {
        Session.log("חיבור מחדש ביוזמת המשתמש")
        if (Session.streaming) stopCapture()
        Session.detach("מתחבר מחדש…")
        Session.set(Session.Link.SEARCHING, "מחפש את המכשיר השני…")
        link.reset(); link.connect()
    }

    private fun beginCapture() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 5)
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE)
    }

    private fun stopCapture() { startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP)) }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) return
        if (resultCode == RESULT_OK && data != null) {
            val intent = Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_START)
                .putExtra(CaptureService.EXTRA_RESULT, resultCode).putExtra(CaptureService.EXTRA_DATA, data)
                .putExtra(CaptureService.EXTRA_QUALITY, quality)
            startForegroundService(intent)
        } else Session.log("שיתוף המסך לא אושר")
    }

    private fun maybeOpenViewer() {
        val cfg = Session.lastConfig ?: return
        if (Session.mode == Session.Mode.RECEIVE && cfg !== dismissedConfig) showViewer()
    }

    // ───────────────────────────── Viewer ─────────────────────────────

    private fun showViewer() {
        onViewer = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL
        viewerRoot = root
        val sv = SurfaceView(this)
        surfaceView = sv
        root.addView(sv, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        sv.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { startDecoder(holder.surface) }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) { stopDecoder() }
        })
        sv.setOnTouchListener { v, ev -> onViewerTouch(v, ev) }

        val h = glassCard(24f, 14).apply { alpha = 0.97f }
        hudText = label("", 13f, false, Glass.TEXT)
        h.addView(hudText)
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val close = glassButton("סגור תצוגה", 18f, 14f).apply { setOnClickListener { closeViewer() } }
        val again = glassButton("חבר מחדש", 18f, 14f).apply { setOnClickListener { reconnect() } }
        val hide = glassButton("הסתר", 18f, 14f).apply { setOnClickListener { hideHud() } }
        buttons.addView(close, LinearLayout.LayoutParams(0, -2, 1f))
        buttons.addView(again, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dpi(8) })
        buttons.addView(hide, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dpi(8) })
        h.addView(buttons, lp(top = 10))
        h.addView(label("נגיעה בשלוש אצבעות מציגה את הפאנל הזה", 11f, false, Glass.TEXT_3), lp(top = 8))
        hud = h
        root.addView(h, FrameLayout.LayoutParams(min(resources.displayMetrics.widthPixels - dpi(32), dpi(460)), -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dpi(16) })

        setContentView(root)
        hideSystemBars()
        showHud()
        fpsMark = SystemClock.uptimeMillis(); fpsFrames = 0
        if (Session.lastConfig != null) Session.lastConfig?.let { videoW = it.width; videoH = it.height }
        handler.post { fitSurface() }
    }

    private fun startDecoder(surface: Surface) {
        val d = TabletDecoder(surface)
        decoder = d
        Session.videoSink = { p ->
            when (p.type) {
                Wire.CONFIG -> {
                    try { d.setup(p) } catch (e: Exception) { Session.log("שגיאת מפענח: ${e.message}") }
                    handler.post { videoW = p.width; videoH = p.height; fitSurface() }
                }
                Wire.VIDEO -> d.enqueue(p)
            }
        }
        Session.lastConfig?.let { cfg -> Thread { Session.videoSink?.invoke(cfg) }.start() }
        // Ask the sender for config + keyframe so the picture starts immediately.
        Session.sendAsync(Wire.Packet(Wire.STATS, 0, 0, 0, byteArrayOf()))
    }

    private fun stopDecoder() {
        Session.videoSink = null
        decoder?.stop(); decoder = null
    }

    private fun closeViewer() {
        dismissedConfig = Session.lastConfig
        stopDecoder()
        showHome()
    }

    private fun fitSurface() {
        val root = viewerRoot ?: return; val sv = surfaceView ?: return
        val rw = root.width; val rh = root.height
        if (rw == 0 || rh == 0) { handler.postDelayed({ fitSurface() }, 50); return }
        if (videoW <= 0 || videoH <= 0) return
        val scale = min(rw.toFloat() / videoW, rh.toFloat() / videoH)
        sv.layoutParams = FrameLayout.LayoutParams((videoW * scale).toInt(), (videoH * scale).toInt(), Gravity.CENTER)
    }

    private fun updateViewer() {
        val d = decoder
        val now = SystemClock.uptimeMillis()
        if (d != null && now - fpsMark >= 1000) {
            fps = (d.decoded - fpsFrames) * 1000.0 / (now - fpsMark); fpsFrames = d.decoded; fpsMark = now
        }
        val state = when {
            Session.wire == null -> "● לא מחובר — ${Session.status}"
            (d?.decoded ?: 0L) == 0L -> "● ממתין לתמונה מהמכשיר השני…"
            else -> "● מציג · ${videoW}×${videoH}"
        }
        hudText?.text = "$state\n${"%.0f".format(fps)}fps · השהיית כבל ${"%.1f".format(Session.rttMs)}ms · תור ${"%.1f".format(d?.lastQueueDelayMs ?: 0.0)}ms · נפלו ${d?.dropped ?: 0}"
        val h = hud ?: return
        val healthy = Session.wire != null && (d?.decoded ?: 0L) > 0
        if (!healthy && h.visibility != View.VISIBLE) showHud()
        if (healthy && h.visibility == View.VISIBLE && hudHideAt in 1 until now) hideHud()
    }

    private fun showHud() {
        val h = hud ?: return
        h.visibility = View.VISIBLE
        h.animate().alpha(1f).translationY(0f).setDuration(220).start()
        hudHideAt = SystemClock.uptimeMillis() + 4000
    }

    private fun hideHud() {
        val h = hud ?: return
        hudHideAt = 0
        h.animate().alpha(0f).translationY(-dp(20f)).setDuration(220).withEndAction { h.visibility = View.GONE }.start()
    }

    private fun onViewerTouch(v: View, ev: MotionEvent): Boolean {
        if (ev.pointerCount >= 3) { showHud(); multiTouch = true; return true }
        if (multiTouch) { if (ev.actionMasked == MotionEvent.ACTION_UP) multiTouch = false; return true }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = ev.x / v.width; downY = ev.y / v.height; downAt = SystemClock.uptimeMillis() }
            MotionEvent.ACTION_UP -> {
                val buf = ByteBuffer.allocate(20).putFloat(downX).putFloat(downY)
                    .putFloat(ev.x / v.width).putFloat(ev.y / v.height)
                    .putInt((SystemClock.uptimeMillis() - downAt).toInt().coerceIn(50, 1200))
                Session.sendAsync(Wire.Packet(Wire.TOUCH, 0, 0, 0, buf.array()))
            }
        }
        return true
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (onViewer) closeViewer() else super.onBackPressed()
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    @Suppress("DEPRECATION")
    private fun showSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) window.insetsController?.show(WindowInsets.Type.systemBars())
        else window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        stopDecoder()
        if (isFinishing) {
            if (Session.streaming) stopCapture()
            Session.detach("האפליקציה נסגרה")
            link.close()
        }
        super.onDestroy()
    }
}
