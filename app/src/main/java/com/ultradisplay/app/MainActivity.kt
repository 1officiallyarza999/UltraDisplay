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
    private var quality = 0
    private var tabletMode = false

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
    private var senderSection: View? = null
    private val displayTabs = mutableListOf<Pair<TextView, GlassDrawable>>()
    private var shizukuDot: View? = null
    private var shizukuTitle: TextView? = null
    private var shizukuText: TextView? = null
    private var shizukuBtn: TextView? = null
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
    private var fillMode = false
    private var fillBtn: TextView? = null
    private var fpsMark = 0L; private var fpsFrames = 0L; private var fps = 0.0
    private var handle: View? = null

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
        quality = prefs.getInt("preset", 0)
        tabletMode = prefs.getBoolean("tablet", false)
        Session.init(this); TouchInjector.init(this); ShizukuBridge.init(this)
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

    override fun onResume() { super.onResume(); ShizukuBridge.refresh(); link.connect() }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (onViewer) handler.post { fitSurface() } else showHome()
    }

    // ───────────────────────────── Home ─────────────────────────────

    private fun showHome() {
        onViewer = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        showSystemBars()
        qualityTabs.clear(); displayTabs.clear()

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

        // Sender-only settings
        val sender = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        sender.addView(sectionLabel("סוג תצוגה"))
        sender.addView(segmented(listOf("שיקוף מסך", "מסך טאבלט"), displayTabs) { i ->
            tabletMode = i == 1; prefs.edit().putBoolean("tablet", tabletMode).apply(); updateHome()
        }, lp(top = 10))
        sender.addView(label("שיקוף: מה שעל הטלפון מופיע בטאבלט — הכי טוב למשחקים.\nמסך טאבלט: מסך נפרד במידות הטאבלט, האפליקציות רצות על הטלפון ונפרסות לכל הטאבלט. הטלפון נשאר חופשי.", 12f, false, Glass.TEXT_3), lp(top = 8))

        // Shizuku status
        val shz = glassCard(24f, 16)
        val shzRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        shizukuDot = dot(Glass.GREY, 10)
        shzRow.addView(shizukuDot)
        shizukuTitle = label("Shizuku", 16f, true)
        shzRow.addView(shizukuTitle, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dpi(10) })
        shz.addView(shzRow)
        shizukuText = label("", 13f, false, Glass.TEXT_2)
        shz.addView(shizukuText, lp(top = 8))
        shizukuBtn = glassButton("", 18f, 14f, Glass.ACCENT_TINT).apply { setOnClickListener { onShizukuAction() } }
        shz.addView(shizukuBtn, lp(top = 12))
        sender.addView(shz, lp(top = 16))

        sender.addView(sectionLabel("איכות שידור"), lp(top = 22))
        sender.addView(segmented(CaptureService.PRESETS.map { it.label }, qualityTabs) { i ->
            quality = i; prefs.edit().putInt("preset", i).apply(); updateHome()
        }, lp(top = 10))
        sender.addView(label("משחק = 1280 · 60fps · השהיה הכי נמוכה (מומלץ ל-COD).  אולטרה = 1920 · 60fps · הכי חד. אם המכשיר לא עומד בזה, האיכות יורדת אוטומטית.", 12f, false, Glass.TEXT_3), lp(top = 8))
        senderSection = sender
        col.addView(sender, lp(top = 22))

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

        col.addView(label("UltraDisplay v${BuildConfig.VERSION_NAME}", 11f, false, Glass.TEXT_3).apply { textAlignment = View.TEXT_ALIGNMENT_CENTER }, lp(top = 18))

        setContentView(root)
        updateHome()
    }

    private fun sectionLabel(text: String) = label(text, 13f, true, Glass.TEXT_3).apply { letterSpacing = 0.04f }

    private fun segmented(items: List<String>, into: MutableList<Pair<TextView, GlassDrawable>>, onPick: (Int) -> Unit): View {
        val seg = glassCard(22f, 5).apply { orientation = LinearLayout.HORIZONTAL }
        items.forEachIndexed { i, text ->
            val g = GlassDrawable(this, dp(18f))
            val tab = label(text, 15f, true).apply {
                gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER
                setPadding(0, dpi(12), 0, dpi(12)); isClickable = true
                setOnClickListener { onPick(i) }
                pressable()
            }
            into += tab to g
            seg.addView(tab, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = dpi(4) })
        }
        return seg
    }

    private fun paintTabs(tabs: List<Pair<TextView, GlassDrawable>>, selected: Int) {
        tabs.forEachIndexed { i, (tab, g) ->
            tab.background = if (i == selected) g.also { it.glassTint = Glass.ACCENT_TINT } else null
            tab.setTextColor(if (i == selected) Glass.TEXT else Glass.TEXT_2)
        }
    }

    private fun onShizukuAction() {
        when (ShizukuBridge.state) {
            ShizukuBridge.State.NOT_INSTALLED, ShizukuBridge.State.NOT_RUNNING -> ShizukuBridge.openShizuku(this)
            ShizukuBridge.State.NO_PERMISSION -> ShizukuBridge.requestPermission()
            else -> ShizukuBridge.refresh()
        }
    }

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
        sendGlass?.glassTint = if (send) Glass.ACCENT_TINT else 0
        recvGlass?.glassTint = if (!send) Glass.ACCENT_TINT else 0
        sendCheck?.visibility = if (send) View.VISIBLE else View.INVISIBLE
        recvCheck?.visibility = if (!send) View.VISIBLE else View.INVISIBLE
        senderSection?.visibility = if (send) View.VISIBLE else View.GONE
        paintTabs(qualityTabs, quality)
        paintTabs(displayTabs, if (tabletMode) 1 else 0)

        val shz = ShizukuBridge.state
        val (shzColor, shzText, shzAction) = when (shz) {
            ShizukuBridge.State.READY -> Triple(Glass.GREEN, "פעיל ✓ מגע מלא בזמן אמת (כמה אצבעות, החזקה, גרירה) ומסך טאבלט זמינים.", "")
            ShizukuBridge.State.CONNECTING -> Triple(Glass.AMBER, "מתחבר ל-Shizuku…", "")
            ShizukuBridge.State.NO_PERMISSION -> Triple(Glass.AMBER, "Shizuku פועל. צריך לאשר ל-UltraDisplay גישה.", "אשר גישה")
            ShizukuBridge.State.NOT_RUNNING -> Triple(Glass.AMBER, "Shizuku מותקן אבל לא מופעל. פתח אותו ולחץ \"התחל\" דרך ניפוי באגים אלחוטי.", "פתח את Shizuku")
            ShizukuBridge.State.NOT_INSTALLED -> Triple(Glass.GREY, "נדרש למגע מלא במשחקים ולמסך טאבלט. בלעדיו יש רק הקשות פשוטות.", "התקן Shizuku")
            ShizukuBridge.State.ERROR -> Triple(Glass.RED, ShizukuBridge.lastError.ifBlank { "שגיאה" }, "נסה שוב")
        }
        (shizukuDot?.background as? GradientDrawable)?.setColor(shzColor)
        shizukuText?.text = shzText
        shizukuBtn?.text = shzAction
        shizukuBtn?.visibility = if (shzAction.isEmpty()) View.GONE else View.VISIBLE

        primaryBtn?.let { b ->
            val (text, tint, enabled) = when {
                send && Session.streaming -> Triple("עצור שידור", Glass.RED_TINT, true)
                send && connected && tabletMode -> Triple("הפעל מסך טאבלט", Glass.ACCENT_TINT, !conflict && ShizukuBridge.ready)
                send && connected -> Triple("התחל שידור", Glass.ACCENT_TINT, !conflict)
                !send && connected -> Triple(if (Session.lastConfig != null) "פתח תצוגה" else "ממתין שהטלפון יתחיל לשדר", Glass.ACCENT_TINT, true)
                else -> Triple("ממתין לחיבור…", 0, false)
            }
            b.text = text
            (b.background as? GlassDrawable)?.glassTint = tint
            b.alpha = if (enabled) 1f else 0.55f
        }
        touchBtn?.visibility = if (send && !ShizukuBridge.ready) View.VISIBLE else View.GONE
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
        if (tabletMode) {
            when {
                !ShizukuBridge.ready -> Session.set(Session.Link.ERROR, "מסך טאבלט דורש Shizuku פעיל — ראה הכרטיס למעלה")
                Session.peerW <= 0 -> Session.set(Session.Link.ERROR, "הטאבלט לא שלח את מידות המסך — עדכן את UltraDisplay גם בו")
                else -> startForegroundService(Intent(this, CaptureService::class.java)
                    .setAction(CaptureService.ACTION_START_TABLET).putExtra(CaptureService.EXTRA_QUALITY, quality))
            }
            return
        }
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
        val back = glassButton("חזור", 18f, 14f).apply { setOnClickListener { sendKey(KeyEvent.KEYCODE_BACK) } }
        val home = glassButton("בית", 18f, 14f).apply { setOnClickListener { sendKey(KeyEvent.KEYCODE_HOME) } }
        fillMode = prefs.getBoolean("fill", false)
        fillBtn = glassButton(if (fillMode) "התאם למסך" else "מלא מסך", 18f, 14f).apply {
            setOnClickListener {
                fillMode = !fillMode; prefs.edit().putBoolean("fill", fillMode).apply()
                text = if (fillMode) "התאם למסך" else "מלא מסך"
                fitSurface(); showHud()
            }
        }
        buttons.addView(fillBtn, LinearLayout.LayoutParams(0, -2, 1f))
        buttons.addView(again, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dpi(8) })
        buttons.addView(hide, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dpi(8) })
        buttons.addView(close, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dpi(8) })
        h.addView(buttons, lp(top = 10))
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        nav.addView(back, LinearLayout.LayoutParams(0, -2, 1f))
        nav.addView(home, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dpi(8) })
        h.addView(nav, lp(top = 8))
        h.addView(label("הפס הקטן למעלה פותח את הפאנל הזה · חזור/בית פועלים עם Shizuku", 11f, false, Glass.TEXT_3), lp(top = 8))
        hud = h
        root.addView(h, FrameLayout.LayoutParams(min(resources.displayMetrics.widthPixels - dpi(32), dpi(480)), -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dpi(16) })

        // A small glass handle at the top edge opens the panel. Every other touch goes to the phone.
        val pill = FrameLayout(this).apply {
            isClickable = true
            setOnClickListener { showHud() }
            addView(View(context).apply {
                background = GradientDrawable().apply { cornerRadius = dp(3f); setColor(0x66FFFFFF) }
            }, FrameLayout.LayoutParams(dpi(54), dpi(5), Gravity.CENTER))
        }
        handle = pill
        root.addView(pill, FrameLayout.LayoutParams(dpi(120), dpi(28), Gravity.TOP or Gravity.CENTER_HORIZONTAL))

        setContentView(root)
        hideSystemBars()
        showHud()
        fpsMark = SystemClock.uptimeMillis(); fpsFrames = 0
        Session.lastConfig?.let { videoW = it.width; videoH = it.height }
        matchOrientation()
        handler.post { fitSurface() }
    }

    private fun startDecoder(surface: Surface) {
        val d = TabletDecoder(surface)
        decoder = d
        Session.videoSink = { p ->
            when (p.type) {
                Wire.CONFIG -> {
                    try { d.setup(p) } catch (e: Exception) { Session.log("שגיאת מפענח: ${e.message}") }
                    handler.post { videoW = p.width; videoH = p.height; matchOrientation(); fitSurface() }
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

    /** Turn the tablet to the stream's orientation so a portrait phone fills it instead of a narrow strip. */
    private fun matchOrientation() {
        if (videoW <= 0 || videoH <= 0) return
        val want = if (videoH > videoW) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        if (requestedOrientation != want) requestedOrientation = want
    }

    private fun closeViewer() {
        dismissedConfig = Session.lastConfig
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        stopDecoder()
        showHome()
    }

    private fun fitSurface() {
        val root = viewerRoot ?: return; val sv = surfaceView ?: return
        val rw = root.width; val rh = root.height
        if (rw == 0 || rh == 0) { handler.postDelayed({ fitSurface() }, 50); return }
        if (videoW <= 0 || videoH <= 0) return
        val fit = min(rw.toFloat() / videoW, rh.toFloat() / videoH)
        val fill = kotlin.math.max(rw.toFloat() / videoW, rh.toFloat() / videoH)
        val scale = if (fillMode) fill else fit
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
        val touchMode = if (Session.senderShizuku) "מגע מלא" else "הקשות בלבד (אין Shizuku)"
        hudText?.text = "$state${if (Session.senderVirtual) " · מסך טאבלט" else ""}\n" +
            "מתקבל ${"%.0f".format(fps)}fps · נשלח ${Session.senderFps}fps · כבל ${"%.1f".format(Session.rttMs)}ms · פענוח ${"%.1f".format(d?.lastQueueDelayMs ?: 0.0)}ms · נפלו ${d?.dropped ?: 0}\n$touchMode"
        val h = hud ?: return
        val healthy = Session.wire != null && (d?.decoded ?: 0L) > 0
        if (!healthy && h.visibility != View.VISIBLE) showHud()
        if (healthy && h.visibility == View.VISIBLE && hudHideAt in 1 until now) hideHud()
    }

    private fun showHud() {
        val h = hud ?: return
        handle?.visibility = View.GONE
        h.visibility = View.VISIBLE
        h.animate().alpha(1f).translationY(0f).setDuration(220).start()
        hudHideAt = SystemClock.uptimeMillis() + 4000
    }

    private fun hideHud() {
        val h = hud ?: return
        hudHideAt = 0
        h.animate().alpha(0f).translationY(-dp(20f)).setDuration(220).withEndAction { h.visibility = View.GONE; handle?.visibility = View.VISIBLE }.start()
    }

    /**
     * Streams every touch event (all fingers, down / move / up) to the phone in real time.
     * Payload: action(1) actionIndex(1) count(1) then count × [id(1) x(4) y(4)], normalised to the video.
     */
    private fun onViewerTouch(v: View, ev: MotionEvent): Boolean {
        val action = ev.actionMasked
        if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_MOVE &&
            action != MotionEvent.ACTION_POINTER_DOWN && action != MotionEvent.ACTION_POINTER_UP && action != MotionEvent.ACTION_CANCEL) return true
        val w = v.width.coerceAtLeast(1).toFloat(); val h = v.height.coerceAtLeast(1).toFloat()
        val count = min(ev.pointerCount, 10)
        val buf = ByteBuffer.allocate(3 + count * 9)
        buf.put((if (action == MotionEvent.ACTION_CANCEL) MotionEvent.ACTION_UP else action).toByte())
        buf.put(ev.actionIndex.toByte()); buf.put(count.toByte())
        for (i in 0 until count) {
            buf.put(ev.getPointerId(i).toByte()); buf.putFloat(ev.getX(i) / w); buf.putFloat(ev.getY(i) / h)
        }
        Session.sendAsync(Wire.Packet(Wire.TOUCH2, 0, 0, 0, buf.array()))
        return true
    }

    private fun sendKey(code: Int) {
        Session.sendAsync(Wire.Packet(Wire.KEY, 0, 0, 0, ByteBuffer.allocate(4).putInt(code).array()))
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
