package com.ultradisplay.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.usb.UsbManager
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.*
import android.widget.*
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity() {
    companion object {
        const val REQUEST_CAPTURE = 91
        const val ACTION_AUTO_START = "ultra.AUTO_START"
        @Volatile var viewerOpen = false; private set
    }

    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())
    private var onViewer = false
    private var dismissedConfig: Wire.Packet? = null
    private var pendingAutoStart = false

    // Home
    private var dotView: View? = null
    private var statusTitle: TextView? = null
    private var statusDetail: TextView? = null
    private var sendGlass: GlassDrawable? = null
    private var recvGlass: GlassDrawable? = null
    private var sendCheck: TextView? = null
    private var recvCheck: TextView? = null
    private var senderSection: View? = null
    private val displayTabs = mutableListOf<Pair<TextView, GlassDrawable>>()
    private var shizuku: ShizukuCard? = null
    private var primaryBtn: TextView? = null
    private var touchBtn: TextView? = null
    private var helpCard: View? = null
    private var logView: TextView? = null

    // Viewer
    private var decoder: TabletDecoder? = null
    private var surfaceView: SurfaceView? = null
    private var viewerRoot: FrameLayout? = null
    private var hud: View? = null
    private var hudText: TextView? = null
    private var handle: View? = null
    private var hudHideAt = 0L
    private var videoW = 0; private var videoH = 0
    private var fpsMark = 0L; private var fpsFrames = 0L; private var fps = 0.0
    private val adaptive = AdaptiveQuality()

    private val tick = object : Runnable {
        override fun run() {
            CrashReporter.guard("ui tick") { if (onViewer) updateViewer() else { updateHome(); maybeOpenViewer(); maybeAutoStart() } }
            handler.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.of(this)
        Lang.load(this)
        Session.init(this); TouchInjector.init(this); ShizukuBridge.init(this)
        Session.mode = when (prefs.mode) {
            "SEND" -> Session.Mode.SEND; "RECEIVE" -> Session.Mode.RECEIVE
            else -> if (resources.configuration.smallestScreenWidthDp >= 600) Session.Mode.RECEIVE else Session.Mode.SEND
        }
        AudioForwarder.output = AudioForwarder.Output.values()[prefs.audio.coerceIn(0, 2)]
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC

        if (!prefs.onboarded) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish(); return
        }
        LinkService.start(this)
        handleIntent(intent)
        showHome()
        handler.post(tick)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** USB attach intents (from the manifest filters) and the service's auto-start request. */
    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            UsbManager.ACTION_USB_ACCESSORY_ATTACHED, UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                Session.log(tr("זוהה חיבור USB", "USB connection detected"))
                handler.postDelayed({ LinkService.connectNow(this) }, 250)
            }
            ACTION_AUTO_START -> pendingAutoStart = true
        }
    }

    override fun onResume() {
        super.onResume()
        Lang.load(this)
        ShizukuBridge.refresh()
        LinkService.connectNow(this)
        if (!onViewer && ::prefs.isInitialized && prefs.onboarded) showHome()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (onViewer) handler.post { fitSurface() } else showHome()
    }

    // ───────────────────────────── Home ─────────────────────────────

    private fun showHome() {
        onViewer = false; viewerOpen = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        showSystemBars()
        displayTabs.clear()
        val (root, col) = glassScreen()

        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(ImageView(this).apply { setImageResource(R.mipmap.ic_launcher) }, LinearLayout.LayoutParams(dpi(44), dpi(44)))
        head.addView(label("UltraDisplay", 30f, true).apply { letterSpacing = -0.02f },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dpi(12) })
        head.addView(glassButton("⚙", 18f, 18f).apply {
            setPadding(dpi(14), dpi(10), dpi(14), dpi(10))
            contentDescription = tr("הגדרות", "Settings")
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        })
        col.addView(head)
        col.addView(label(tr("המסך של הטלפון על הטאבלט · בכבל אחד", "Your phone's screen on your tablet · one cable"), 14f, false, Glass.TEXT_2), lp(top = 8))

        // A crash from the previous run: show what happened so it can be sent and fixed.
        CrashReporter.pending(this)?.let { report ->
            val code = CrashReporter.pendingCode(this) ?: "?"
            val c = glassCard(24f, 16)
            (c.background as? GlassDrawable)?.glassTint = Glass.RED_TINT
            c.addView(label(tr("האפליקציה נסגרה בפעם הקודמת", "The app closed unexpectedly last time"), 16f, true))
            c.addView(label(tr("קוד קריסה", "Crash code"), 12f, false, Glass.TEXT_3), lp(top = 8))
            c.addView(label(code, 17f, true, Glass.ACCENT).apply { typeface = Typeface.MONOSPACE; textDirection = View.TEXT_DIRECTION_LTR }, lp(top = 2))
            c.addView(label(tr("לחץ \"דווח\" כדי לשלוח לי את הפרטים המלאים, או העתק את הקוד ושלח בצ'אט.",
                "Tap Report to send me the full details, or copy the code and send it in chat."), 13f, false, Glass.TEXT_2), lp(top = 8))
            val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            btns.addView(glassButton(tr("דווח", "Report"), 18f, 14f, Glass.ACCENT_TINT).apply {
                setOnClickListener { ErrorLog.reportOnGitHub(this@MainActivity, ErrorLog.entries().filter { it.kind == ErrorLog.Kind.CRASH }.take(1)) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            btns.addView(glassButton(tr("העתק", "Copy"), 18f, 14f).apply {
                setOnClickListener {
                    (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                        .setPrimaryClip(android.content.ClipData.newPlainText("UltraDisplay crash", report))
                    text = tr("הועתק ✓", "Copied ✓")
                }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dpi(8) })
            btns.addView(glassButton(tr("יומן", "Log"), 18f, 14f).apply {
                setOnClickListener { startActivity(Intent(this@MainActivity, ErrorLogActivity::class.java)) }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dpi(8) })
            btns.addView(glassButton("✕", 18f, 14f).apply {
                setOnClickListener { CrashReporter.clear(this@MainActivity); showHome() }
            }, LinearLayout.LayoutParams(dpi(52), -2).apply { marginStart = dpi(8) })
            c.addView(btns, lp(top = 12))
            col.addView(c, lp(top = 18))
        }

        // Status
        val status = glassCard(28f, 20)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        dotView = dot(Glass.GREY, 12); row.addView(dotView)
        statusTitle = label("", 21f, true)
        row.addView(statusTitle, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dpi(12) })
        status.addView(row)
        statusDetail = label("", 14f, false, Glass.TEXT_2)
        status.addView(statusDetail, lp(top = 10))
        col.addView(status, lp(top = 20))

        // Role
        col.addView(sectionLabel(tr("התפקיד של המכשיר הזה", "This device is the")), lp(top = 24))
        val roles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val s = roleCard(tr("משדר", "Sender"), tr("המסך שלו יוצג במכשיר השני", "Its screen shows on the other device"))
        val r = roleCard(tr("מציג", "Display"), tr("יציג את המסך של המכשיר השני", "Shows the other device's screen"))
        sendGlass = s.second; sendCheck = s.third; recvGlass = r.second; recvCheck = r.third
        s.first.setOnClickListener { setMode(Session.Mode.SEND) }
        r.first.setOnClickListener { setMode(Session.Mode.RECEIVE) }
        roles.addView(s.first, LinearLayout.LayoutParams(0, -2, 1f))
        roles.addView(r.first, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dpi(12) })
        col.addView(roles, lp(top = 10))

        // Sender: display type + Shizuku (only when not ready)
        val sender = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sender.addView(sectionLabel(tr("סוג תצוגה", "Display type")))
        sender.addView(segmented(listOf(tr("שיקוף מסך", "Mirror"), tr("מסך טאבלט", "Tablet screen")), displayTabs) { i ->
            prefs.tabletMode = i == 1; updateHome()
        }, lp(top = 10))
        sender.addView(label(tr("שיקוף: מה שעל הטלפון מופיע בטאבלט — הכי טוב למשחקים. מסך טאבלט: מסך נפרד במידות הטאבלט שרץ על הטלפון.",
            "Mirror: what's on the phone appears on the tablet — best for games. Tablet screen: a separate tablet-sized screen running on the phone."),
            12f, false, Glass.TEXT_3), lp(top = 8))
        shizuku = ShizukuCard(this).also { sender.addView(it.view, lp(top = 16)) }
        senderSection = sender
        col.addView(sender, lp(top = 22))

        primaryBtn = glassButton("", 30f, 19f, Glass.ACCENT_TINT).apply { minHeight = dpi(64); setOnClickListener { onPrimary() } }
        col.addView(primaryBtn, lp(top = 24))

        val secondary = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        secondary.addView(glassButton(tr("חבר מחדש", "Reconnect"), 22f, 15f).apply { setOnClickListener { reconnect() } }, LinearLayout.LayoutParams(0, -2, 1f))
        touchBtn = glassButton("", 22f, 15f).apply {
            setOnClickListener { startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        secondary.addView(touchBtn, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dpi(12) })
        col.addView(secondary, lp(top = 12))

        // Help until connected
        val help = glassCard(26f, 20)
        help.addView(label(tr("לא מתחבר?", "Not connecting?"), 17f, true))
        listOf(
            tr("ודא ש-UltraDisplay פתוח בשני המכשירים ושאחד \"משדר\" והשני \"מציג\".", "Make sure UltraDisplay is open on both devices, one as Sender and one as Display."),
            tr("השתמש בכבל USB‑C שתומך בנתונים, ואשר כל בקשת גישה ל-USB.", "Use a USB‑C cable that supports data and allow every USB access request."),
            tr("משוך את שורת ההתראות, הקש על התראת ה-USB ובחר \"העברת קבצים\".", "Pull down notifications, tap the USB notification and choose \"File transfer\".")
        ).forEachIndexed { i, t ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            line.addView(label("${i + 1}", 14f, true, Glass.ACCENT), LinearLayout.LayoutParams(dpi(22), -2))
            line.addView(label(t, 14f, false, Glass.TEXT_2), LinearLayout.LayoutParams(0, -2, 1f))
            help.addView(line, lp(top = 10))
        }
        helpCard = help
        col.addView(help, lp(top = 22))

        // Diagnostics
        val diag = glassCard(22f, 16)
        diag.addView(label(tr("פרטי חיבור", "Connection details"), 15f, true))
        diag.addView(label(tr("אם משהו לא עובד — צלם את זה ושלח לי", "If something isn't working, send a screenshot of this"), 12f, false, Glass.TEXT_3), lp(top = 4))
        logView = label("", 11.5f, false, Glass.TEXT_2).apply { typeface = Typeface.MONOSPACE; textDirection = View.TEXT_DIRECTION_ANY_RTL }
        diag.addView(logView, lp(top = 10))
        col.addView(diag, lp(top = 16))

        setContentView(root)
        updateHome()
    }

    private fun roleCard(title: String, sub: String): Triple<View, GlassDrawable, TextView> {
        val card = glassCard(24f, 16).apply { isClickable = true; pressable(); minimumHeight = dpi(112) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(label(title, 19f, true), LinearLayout.LayoutParams(0, -2, 1f))
        val check = label("✓", 13f, true, Color.BLACK).apply {
            gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Glass.ACCENT) }
        }
        top.addView(check, LinearLayout.LayoutParams(dpi(22), dpi(22)))
        card.addView(top)
        card.addView(label(sub, 13f, false, Glass.TEXT_2), lp(top = 8))
        return Triple(card, card.background as GlassDrawable, check)
    }

    private fun setMode(m: Session.Mode) {
        if (Session.mode == m) return
        Session.mode = m; prefs.mode = m.name
        if (m == Session.Mode.RECEIVE && Session.streaming) stopCapture()
        Session.sendHello()
        updateHome()
    }

    private fun updateHome() {
        val connected = Session.wire != null
        val conflict = connected && Session.peerMode != null && Session.peerMode == Session.mode
        val (color, title) = when {
            conflict -> Glass.AMBER to tr("שני המכשירים באותו תפקיד", "Both devices have the same role")
            Session.streaming -> Glass.GREEN to tr("משדר עכשיו", "Streaming now")
            connected -> Glass.GREEN to (if (Session.peerName.isNotEmpty()) tr("מחובר ל-", "Connected to ") + Session.peerName else tr("מחובר", "Connected"))
            Session.link == Session.Link.CONNECTING -> Glass.AMBER to tr("מתחבר…", "Connecting…")
            Session.link == Session.Link.ERROR -> Glass.RED to tr("החיבור נכשל", "Connection failed")
            else -> Glass.GREY to tr("ממתין לכבל", "Waiting for cable")
        }
        (dotView?.background as? GradientDrawable)?.setColor(color)
        statusTitle?.text = title
        statusDetail?.text = when {
            conflict -> tr("בחר \"מציג\" בטאבלט ו\"משדר\" בטלפון.", "Choose Display on the tablet and Sender on the phone.")
            Session.streaming -> "${Session.streamInfo} · ${tr("כבל", "cable")} ${"%.1f".format(Session.rttMs)}ms" +
                if (Session.adaptivePercent < 100) " · ${tr("איכות", "quality")} ${Session.adaptivePercent}%" else ""
            connected -> "${Session.usbRole} · ${tr("כבל", "cable")} ${"%.1f".format(Session.rttMs)}ms"
            else -> Session.status
        }

        val send = Session.mode == Session.Mode.SEND
        sendGlass?.glassTint = if (send) Glass.ACCENT_TINT else 0
        recvGlass?.glassTint = if (!send) Glass.ACCENT_TINT else 0
        sendCheck?.visibility = if (send) View.VISIBLE else View.INVISIBLE
        recvCheck?.visibility = if (!send) View.VISIBLE else View.INVISIBLE
        senderSection?.visibility = if (send) View.VISIBLE else View.GONE
        paintTabs(displayTabs, if (prefs.tabletMode) 1 else 0)
        shizuku?.update()
        shizuku?.view?.visibility = if (ShizukuBridge.ready) View.GONE else View.VISIBLE

        primaryBtn?.let { b ->
            val (text, tint, enabled) = when {
                send && Session.streaming -> Triple(tr("עצור שידור", "Stop streaming"), Glass.RED_TINT, true)
                send && connected && prefs.tabletMode -> Triple(tr("הפעל מסך טאבלט", "Start tablet screen"), Glass.ACCENT_TINT, !conflict && ShizukuBridge.ready)
                send && connected -> Triple(tr("התחל שידור", "Start streaming"), Glass.ACCENT_TINT, !conflict)
                !send && connected -> Triple(if (Session.lastConfig != null) tr("פתח תצוגה", "Open viewer") else tr("ממתין שהטלפון יתחיל לשדר", "Waiting for the phone to stream"), Glass.ACCENT_TINT, true)
                else -> Triple(tr("ממתין לחיבור…", "Waiting for connection…"), 0, false)
            }
            b.text = text
            (b.background as? GlassDrawable)?.glassTint = tint
            b.alpha = if (enabled) 1f else 0.55f
        }
        touchBtn?.visibility = if (send && !ShizukuBridge.ready) View.VISIBLE else View.GONE
        touchBtn?.text = if (RemoteTouchService.isEnabled(this)) tr("הקשות פשוטות ✓", "Simple taps ✓") else tr("הפעל הקשות פשוטות", "Enable simple taps")
        helpCard?.visibility = if (connected) View.GONE else View.VISIBLE
        logView?.text = Session.logText(12)
    }

    private fun onPrimary() {
        val send = Session.mode == Session.Mode.SEND
        when {
            send && Session.streaming -> stopCapture()
            send && Session.wire != null -> beginCapture()
            !send && Session.wire != null -> { dismissedConfig = null; showViewer() }
            else -> { LinkService.reset(); LinkService.connectNow(this) }
        }
    }

    private fun reconnect() {
        if (Session.streaming) stopCapture()
        Session.detach(tr("מתחבר מחדש…", "Reconnecting…"))
        LinkService.reset(); LinkService.connectNow(this)
    }

    /** The background service asked the phone to start; mirroring still needs the user's approval in the system dialog. */
    private fun maybeAutoStart() {
        if (!pendingAutoStart) return
        if (Session.mode != Session.Mode.SEND || Session.streaming || Session.wire == null) { pendingAutoStart = false; return }
        pendingAutoStart = false
        beginCapture()
    }

    private fun beginCapture() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 5)
        if (needsAudioPermission()) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 6)
        if (prefs.tabletMode) {
            when {
                !ShizukuBridge.ready -> Session.set(Session.Link.ERROR, tr("מסך טאבלט דורש Shizuku פעיל", "Tablet screen needs Shizuku running"))
                Session.peerW <= 0 -> Session.set(Session.Link.ERROR, tr("עדכן את UltraDisplay גם בטאבלט", "Update UltraDisplay on the tablet too"))
                else -> startForegroundService(Intent(this, CaptureService::class.java)
                    .setAction(CaptureService.ACTION_START_TABLET).putExtra(CaptureService.EXTRA_QUALITY, prefs.preset))
            }
            return
        }
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE)
    }

    private fun needsAudioPermission(): Boolean {
        val out = AudioForwarder.output
        val viaCapture = out == AudioForwarder.Output.BOTH || (out == AudioForwarder.Output.TABLET && !ShizukuBridge.ready)
        return viaCapture && !prefs.tabletMode && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 6 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && Session.streaming) CaptureService.restartAudio()
    }

    private fun stopCapture() { startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP)) }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) return
        if (resultCode == RESULT_OK && data != null) {
            startForegroundService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_START)
                .putExtra(CaptureService.EXTRA_RESULT, resultCode).putExtra(CaptureService.EXTRA_DATA, data)
                .putExtra(CaptureService.EXTRA_QUALITY, prefs.preset))
        } else Session.log(tr("שיתוף המסך לא אושר", "Screen sharing was not approved"))
    }

    private fun maybeOpenViewer() {
        val cfg = Session.lastConfig ?: return
        if (Session.mode == Session.Mode.RECEIVE && cfg !== dismissedConfig) showViewer()
    }

    // ───────────────────────────── Viewer ─────────────────────────────

    private fun showViewer() {
        onViewer = true; viewerOpen = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        adaptive.reset()
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.layoutDirection = Lang.direction
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
        sv.setOnGenericMotionListener { v, ev -> onViewerGeneric(v, ev) }
        sv.isFocusable = true; sv.isFocusableInTouchMode = true

        val h = glassCard(24f, 14).apply { alpha = 0.97f }
        hudText = label("", 13f, false, Glass.TEXT)
        h.addView(hudText)
        fun btn(t: String, a: () -> Unit) = glassButton(t, 18f, 14f).apply { setOnClickListener { a() } }
        val fill = btn(if (prefs.fill) tr("התאם למסך", "Fit") else tr("מלא מסך", "Fill")) {}
        fill.setOnClickListener {
            prefs.fill = !prefs.fill
            fill.text = if (prefs.fill) tr("התאם למסך", "Fit") else tr("מלא מסך", "Fill")
            fitSurface(); showHud()
        }
        val mute = btn(if (AudioSink.muted) tr("בטל השתקה", "Unmute") else tr("השתק", "Mute")) {}
        mute.setOnClickListener {
            AudioSink.muted = !AudioSink.muted
            mute.text = if (AudioSink.muted) tr("בטל השתקה", "Unmute") else tr("השתק", "Mute"); showHud()
        }
        val rows = listOf(
            listOf(fill, btn(tr("חבר מחדש", "Reconnect")) { reconnect() }, btn(tr("הסתר", "Hide")) { hideHud() }, btn(tr("סגור", "Close")) { closeViewer() }),
            listOf(btn(tr("חזור", "Back")) { sendKey(KeyEvent.KEYCODE_BACK) }, btn(tr("בית", "Home")) { sendKey(KeyEvent.KEYCODE_HOME) }, mute)
        )
        rows.forEach { r ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            r.forEachIndexed { i, b -> line.addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = dpi(8) }) }
            h.addView(line, lp(top = 8))
        }
        h.addView(label(tr("הפס הקטן למעלה פותח את הפאנל הזה", "The small handle at the top opens this panel"), 11f, false, Glass.TEXT_3), lp(top = 8))
        hud = h
        root.addView(h, FrameLayout.LayoutParams(min(resources.displayMetrics.widthPixels - dpi(32), dpi(500)), -2,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dpi(16) })

        val pill = FrameLayout(this).apply {
            isClickable = true
            setOnClickListener { showHud() }
            addView(View(context).apply { background = GradientDrawable().apply { cornerRadius = dp(3f); setColor(0x66FFFFFF) } },
                FrameLayout.LayoutParams(dpi(54), dpi(5), Gravity.CENTER))
        }
        handle = pill
        root.addView(pill, FrameLayout.LayoutParams(dpi(120), dpi(28), Gravity.TOP or Gravity.CENTER_HORIZONTAL))

        setContentView(root)
        sv.requestFocus()
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
                    try { d.setup(p) } catch (e: Exception) { Session.log(tr("שגיאת מפענח: ", "Decoder error: ") + e.message) }
                    handler.post { videoW = p.width; videoH = p.height; matchOrientation(); fitSurface() }
                }
                Wire.VIDEO -> d.enqueue(p)
            }
        }
        Session.lastConfig?.let { cfg -> Thread { Session.videoSink?.invoke(cfg) }.start() }
        Session.sendAsync(Wire.Packet(Wire.STATS, 0, 0, 0, byteArrayOf()))
    }

    private fun stopDecoder() {
        Session.videoSink = null
        decoder?.stop(); decoder = null
    }

    private fun matchOrientation() {
        if (videoW <= 0 || videoH <= 0) return
        val want = if (videoH > videoW) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        if (requestedOrientation != want) requestedOrientation = want
    }

    private fun closeViewer() {
        dismissedConfig = Session.lastConfig
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        stopDecoder()
        showHome()
    }

    private fun fitSurface() {
        val root = viewerRoot ?: return; val sv = surfaceView ?: return
        val rw = root.width; val rh = root.height
        if (rw == 0 || rh == 0) { handler.postDelayed({ fitSurface() }, 50); return }
        if (videoW <= 0 || videoH <= 0) return
        val fit = min(rw.toFloat() / videoW, rh.toFloat() / videoH)
        val fill = max(rw.toFloat() / videoW, rh.toFloat() / videoH)
        val scale = if (prefs.fill) fill else fit
        sv.layoutParams = FrameLayout.LayoutParams((videoW * scale).toInt(), (videoH * scale).toInt(), Gravity.CENTER)
    }

    private fun updateViewer() {
        val d = decoder
        val now = SystemClock.uptimeMillis()
        if (d != null && now - fpsMark >= 1000) {
            fps = (d.decoded - fpsFrames) * 1000.0 / (now - fpsMark); fpsFrames = d.decoded; fpsMark = now
            adaptive.onSecond(fps, Session.senderFps, d.dropped, d.lastQueueDelayMs)
        }
        val state = when {
            Session.wire == null -> "● " + tr("לא מחובר — ", "Not connected — ") + Session.status
            (d?.decoded ?: 0L) == 0L -> "● " + tr("ממתין לתמונה מהמכשיר השני…", "Waiting for the picture…")
            else -> "● " + tr("מציג", "Showing") + " · ${videoW}×${videoH}"
        }
        val touchMode = (if (Session.senderShizuku) tr("מגע מלא", "Full touch") else tr("הקשות בלבד (אין Shizuku)", "Taps only (no Shizuku)")) +
            " · " + tr("שמע: ", "Audio: ") + when {
                AudioSink.muted -> tr("מושתק", "muted"); AudioSink.playing -> tr("פעיל", "on"); else -> tr("לא מתקבל", "none") }
        hudText?.text = "$state${if (Session.senderVirtual) " · " + tr("מסך טאבלט", "tablet screen") else ""}\n" +
            "${tr("מתקבל", "In")} ${"%.0f".format(fps)}fps · ${tr("נשלח", "Out")} ${Session.senderFps}fps · ${tr("כבל", "Cable")} ${"%.1f".format(Session.rttMs)}ms · " +
            "${tr("פענוח", "Decode")} ${"%.1f".format(d?.lastQueueDelayMs ?: 0.0)}ms · ${tr("איכות", "Quality")} ${adaptive.percent}%\n$touchMode"
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

    /** Real-time multi-touch: action(1) actionIndex(1) count(1) then count × [id(1) x(4) y(4)], normalised. */
    private fun onViewerTouch(v: View, ev: MotionEvent): Boolean {
        val action = ev.actionMasked
        if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_MOVE &&
            action != MotionEvent.ACTION_POINTER_DOWN && action != MotionEvent.ACTION_POINTER_UP && action != MotionEvent.ACTION_CANCEL) return true
        val w = v.width.coerceAtLeast(1).toFloat(); val h = v.height.coerceAtLeast(1).toFloat()
        val count = min(ev.pointerCount, 10)
        val buf = ByteBuffer.allocate(3 + count * 9)
        buf.put((if (action == MotionEvent.ACTION_CANCEL) MotionEvent.ACTION_UP else action).toByte())
        buf.put(ev.actionIndex.toByte()); buf.put(count.toByte())
        for (i in 0 until count) { buf.put(ev.getPointerId(i).toByte()); buf.putFloat(ev.getX(i) / w); buf.putFloat(ev.getY(i) / h) }
        Session.sendAsync(Wire.Packet(Wire.TOUCH2, 0, 0, 0, buf.array()))
        return true
    }

    /** Mouse wheel and gamepad sticks/triggers. */
    private fun onViewerGeneric(v: View, ev: MotionEvent): Boolean {
        if (!prefs.forwardInput || Session.wire == null) return false
        val isScroll = ev.actionMasked == MotionEvent.ACTION_SCROLL
        val isJoystick = ev.isFromSource(InputDevice.SOURCE_JOYSTICK) && ev.actionMasked == MotionEvent.ACTION_MOVE
        if (!isScroll && !isJoystick) return false
        val axes = if (isScroll) intArrayOf(MotionEvent.AXIS_VSCROLL, MotionEvent.AXIS_HSCROLL)
            else intArrayOf(MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ,
                MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y, MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_RTRIGGER,
                MotionEvent.AXIS_GAS, MotionEvent.AXIS_BRAKE)
        val buf = ByteBuffer.allocate(17 + axes.size * 5)
        buf.putInt(ev.source).putInt(ev.actionMasked)
        buf.putFloat(if (isScroll) ev.x / v.width.coerceAtLeast(1) else 0f).putFloat(if (isScroll) ev.y / v.height.coerceAtLeast(1) else 0f)
        buf.put(axes.size.toByte())
        axes.forEach { a -> buf.put(a.toByte()); buf.putFloat(ev.getAxisValue(a)) }
        Session.sendAsync(Wire.Packet(Wire.MOTION2, 0, 0, 0, buf.array()))
        return true
    }

    /** Keyboards and gamepad buttons connected to the tablet are sent to the phone while the viewer is open. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!onViewer || !prefs.forwardInput || Session.wire == null) return super.dispatchKeyEvent(event)
        val code = event.keyCode
        // The tablet keeps its own volume and back-to-home behaviour.
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_MUTE) return super.dispatchKeyEvent(event)
        val external = event.device?.let { !it.isVirtual } ?: false
        if (code == KeyEvent.KEYCODE_BACK && !external) return super.dispatchKeyEvent(event)
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return true
        val buf = ByteBuffer.allocate(21).put(event.action.toByte()).putInt(event.source).putInt(code)
            .putInt(event.metaState).putInt(event.repeatCount).putInt(event.scanCode)
        Session.sendAsync(Wire.Packet(Wire.KEY2, 0, 0, 0, buf.array()))
        return true
    }

    private fun sendKey(code: Int) {
        Session.sendAsync(Wire.Packet(Wire.KEY, 0, 0, 0, ByteBuffer.allocate(4).putInt(code).array()))
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() { if (onViewer) closeViewer() else super.onBackPressed() }

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

    private fun showSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) window.insetsController?.show(WindowInsets.Type.systemBars())
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopDecoder()
        viewerOpen = false
        // The connection lives in LinkService. Stop it only when the user turned automatic connection off.
        if (isFinishing && ::prefs.isInitialized && prefs.onboarded && !prefs.autoConnect) LinkService.start(this, LinkService.ACTION_STOP)
        super.onDestroy()
    }
}

/**
 * Receiver-side quality controller. Two bad seconds in a row (dropped frames, falling behind the
 * sender, or slow decoding) step the bitrate down; eight good seconds step it back up.
 */
class AdaptiveQuality {
    private val levels = intArrayOf(100, 75, 55, 40)
    private var level = 0
    private var bad = 0; private var good = 0
    private var lastDropped = 0L
    val percent: Int get() = levels[level]

    fun reset() { level = 0; bad = 0; good = 0; lastDropped = 0 }

    fun onSecond(recvFps: Double, sentFps: Int, dropped: Long, decodeMs: Double) {
        val newDrops = dropped - lastDropped; lastDropped = dropped
        if (sentFps <= 0) return
        val struggling = newDrops > 0 || (sentFps >= 20 && recvFps < sentFps * 0.85) || decodeMs > 45
        if (struggling) { bad++; good = 0 } else { good++; bad = 0 }
        val before = level
        if (bad >= 2 && level < levels.size - 1) { level++; bad = 0 }
        if (good >= 8 && level > 0) { level--; good = 0 }
        if (level != before) Session.sendAsync(Wire.Packet(Wire.BITRATE, 0, 0, 0, ByteBuffer.allocate(4).putInt(percent).array()))
    }
}
