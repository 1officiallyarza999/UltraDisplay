package com.ultradisplay.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** First-run guide: welcome → this device's role → Shizuku (phone) → cable tips. */
class OnboardingActivity : Activity() {
    private lateinit var prefs: Prefs
    private var step = 0
    private var shizuku: ShizukuCard? = null
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { CrashReporter.guard("guide tick") { shizuku?.update() }; handler.postDelayed(this, 700) } }

    private val sender get() = Session.mode == Session.Mode.SEND
    private val steps get() = if (sender) 4 else 3

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.of(this)
        Lang.load(this)
        ShizukuBridge.init(this)
        if (prefs.mode == null) {
            Session.mode = if (resources.configuration.smallestScreenWidthDp >= 600) Session.Mode.RECEIVE else Session.Mode.SEND
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        show()
        handler.post(tick)
    }

    override fun onResume() { super.onResume(); ShizukuBridge.refresh() }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() { if (step > 0) { step--; show() } else super.onBackPressed() }

    private fun show() {
        shizuku = null
        val (root, col) = glassScreen(560)

        // Progress dots
        val dots = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        repeat(steps) { i ->
            dots.addView(View(this).apply {
                background = GradientDrawable().apply { cornerRadius = dp(4f); setColor(if (i == step) Glass.ACCENT else 0x40FFFFFF) }
            }, LinearLayout.LayoutParams(dpi(if (i == step) 26 else 8), dpi(8)).apply { marginStart = dpi(4); marginEnd = dpi(4) })
        }
        col.addView(dots, lp(top = 8))

        when (stepKind()) {
            "welcome" -> welcome(col)
            "role" -> role(col)
            "shizuku" -> shizukuStep(col)
            else -> cable(col)
        }

        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (step > 0) nav.addView(glassButton(tr("חזור", "Back"), 24f, 16f).apply { setOnClickListener { step--; show() } },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dpi(12) })
        val last = step == steps - 1
        nav.addView(glassButton(if (last) tr("סיום", "Done") else tr("המשך", "Continue"), 24f, 17f, Glass.ACCENT_TINT).apply {
            minHeight = dpi(58)
            setOnClickListener { if (last) finishGuide() else { step++; show() } }
        }, LinearLayout.LayoutParams(0, -2, 2f))
        col.addView(nav, lp(top = 28))
        if (stepKind() == "shizuku") col.addView(label(tr("אפשר לדלג ולהשלים מאוחר יותר בהגדרות.", "You can skip this and finish later in Settings."), 12f, false, Glass.TEXT_3).apply {
            textAlignment = View.TEXT_ALIGNMENT_CENTER
        }, lp(top = 10))
        setContentView(root)
    }

    private fun stepKind(): String = when (step) {
        0 -> "welcome"; 1 -> "role"
        2 -> if (sender) "shizuku" else "cable"
        else -> "cable"
    }

    private fun title(col: LinearLayout, t: String, sub: String) {
        col.addView(label(t, 28f, true).apply { letterSpacing = -0.02f }, lp(top = 28))
        col.addView(label(sub, 15f, false, Glass.TEXT_2), lp(top = 8))
    }

    private fun welcome(col: LinearLayout) {
        col.addView(ImageView(this).apply { setImageResource(R.mipmap.ic_launcher) },
            LinearLayout.LayoutParams(dpi(96), dpi(96)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dpi(36) })
        title(col, tr("ברוך הבא ל-UltraDisplay", "Welcome to UltraDisplay"),
            tr("הטלפון רץ, הטאבלט מציג. מחברים כבל אחד, והמסך, המגע והשמע של הטלפון עוברים לטאבלט — בלי אינטרנט.",
               "Your phone runs it, your tablet shows it. One cable carries the phone's screen, touch and sound to the tablet — no internet."))
        val c = glassCard(24f, 18)
        listOf(
            tr("🎮  מגע מלא בזמן אמת למשחקים", "🎮  Real-time multi-touch for games"),
            tr("🖥  מסך טאבלט נפרד שרץ על הטלפון", "🖥  A separate tablet screen running on the phone"),
            tr("🔊  שמע בטאבלט, בטלפון או בשניהם", "🔊  Sound on the tablet, the phone or both"),
            tr("⚡  מתחבר לבד כשמחברים כבל", "⚡  Connects by itself when you plug in")
        ).forEachIndexed { i, s -> c.addView(label(s, 15f), lp(top = if (i == 0) 0 else 12)) }
        col.addView(c, lp(top = 22))
    }

    private fun role(col: LinearLayout) {
        title(col, tr("מה המכשיר הזה?", "What is this device?"),
            tr("בחרנו לפי גודל המסך. אפשר לשנות בכל רגע.", "We picked based on screen size. You can change it any time."))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun card(t: String, d: String, m: Session.Mode): View {
            val c = glassCard(24f, 18).apply { isClickable = true; pressable(); minimumHeight = dpi(150) }
            (c.background as GlassDrawable).glassTint = if (Session.mode == m) Glass.ACCENT_TINT else 0
            c.addView(label(t, 21f, true))
            c.addView(label(d, 13.5f, false, Glass.TEXT_2), lp(top = 8))
            c.setOnClickListener { Session.mode = m; prefs.mode = m.name; show() }
            return c
        }
        row.addView(card(tr("משדר", "Sender"), tr("הטלפון. כאן רצות האפליקציות והמשחקים.", "The phone. Apps and games run here."), Session.Mode.SEND), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(card(tr("מציג", "Display"), tr("הטאבלט. מציג את המסך ומעביר את המגע.", "The tablet. Shows the screen and sends back touch."), Session.Mode.RECEIVE),
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dpi(12) })
        col.addView(row, lp(top = 22))
        prefs.mode = Session.mode.name
    }

    private fun shizukuStep(col: LinearLayout) {
        title(col, tr("שדרוג למגע מלא", "Unlock full touch"),
            tr("Shizuku היא אפליקציה חינמית שנותנת ל-UltraDisplay את ההרשאה למגע של כמה אצבעות, מקלדת ושלט, מסך טאבלט ושמע בטאבלט בלבד. מגדירים פעם אחת.",
               "Shizuku is a free app that gives UltraDisplay permission for multi-finger touch, keyboard & gamepad, the tablet screen and tablet-only audio. One-time setup."))
        shizuku = ShizukuCard(this, withSteps = true).also { col.addView(it.view, lp(top = 22)) }
    }

    private fun cable(col: LinearLayout) {
        title(col, tr("מחברים כבל", "Plug in the cable"),
            tr("כבל USB‑C לשני הצדדים שתומך בהעברת נתונים. UltraDisplay צריך להיות מותקן בשני המכשירים.",
               "A USB‑C to USB‑C cable that supports data. UltraDisplay must be installed on both devices."))
        val c = glassCard(24f, 18)
        listOf(
            tr("כשמופיע \"לפתוח את UltraDisplay?\" סמן \"השתמש תמיד\" ואשר — בשני המכשירים. ככה בפעם הבאה הכל מתחבר לבד.",
               "When asked \"Open UltraDisplay?\" tick \"Always\" and confirm — on both devices. Next time everything connects by itself."),
            tr("לא מתחבר? משוך את שורת ההתראות, הקש על התראת ה-USB ובחר \"העברת קבצים\".",
               "Not connecting? Pull down notifications, tap the USB notification and choose \"File transfer\"."),
            tr("במסך הטאבלט, הפס הקטן למעלה פותח את פאנל הבקרה.", "On the tablet, the small handle at the top opens the control panel.")
        ).forEachIndexed { i, s ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            line.addView(label("${i + 1}", 14f, true, Glass.ACCENT), LinearLayout.LayoutParams(dpi(22), -2))
            line.addView(label(s, 14f, false, Glass.TEXT_2), LinearLayout.LayoutParams(0, -2, 1f))
            c.addView(line, lp(top = if (i == 0) 0 else 12))
        }
        col.addView(c, lp(top = 22))
    }

    private fun finishGuide() {
        prefs.onboarded = true
        prefs.mode = Session.mode.name
        LinkService.start(this)
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}
