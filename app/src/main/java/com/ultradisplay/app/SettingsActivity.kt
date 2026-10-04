package com.ultradisplay.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** All options on one Liquid Glass page. Sender-only options are hidden on the tablet. */
class SettingsActivity : Activity() {
    private lateinit var prefs: Prefs
    private var shizuku: ShizukuCard? = null
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { CrashReporter.guard("settings tick") { shizuku?.update() }; handler.postDelayed(this, 700) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.of(this)
        build()
        handler.post(tick)
    }

    override fun onResume() { super.onResume(); ShizukuBridge.refresh() }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }

    private fun card(title: String, col: LinearLayout): LinearLayout {
        col.addView(sectionLabel(title), lp(top = 24))
        val c = glassCard(24f, 14)
        col.addView(c, lp(top = 10))
        return c
    }

    private fun build() {
        val (root, col) = glassScreen()
        val sender = Session.mode == Session.Mode.SEND

        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(label(tr("הגדרות", "Settings"), 30f, true), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(glassButton(tr("סגור", "Close"), 18f, 14f).apply { setOnClickListener { finish() } })
        col.addView(head)

        // Language
        col.addView(sectionLabel(tr("שפה", "Language")), lp(top = 24))
        val langTabs = mutableListOf<Pair<TextView, GlassDrawable>>()
        val langs = listOf("auto", "he", "en")
        col.addView(segmented(listOf(tr("לפי המכשיר", "Device"), "עברית", "English"), langTabs) { i ->
            prefs.lang = langs[i]; Lang.choice = langs[i]; build()
        }, lp(top = 10))
        paintTabs(langTabs, langs.indexOf(prefs.lang).coerceAtLeast(0))

        // Connection
        val conn = card(tr("חיבור", "Connection"), col)
        conn.addView(toggleRow(tr("חיבור אוטומטי ברקע", "Automatic background connection"),
            tr("מחכה לכבל גם כשהאפליקציה סגורה, עולה אחרי הפעלה מחדש ומתחבר מחדש לבד.", "Waits for the cable even when closed, starts after reboot and reconnects by itself."),
            prefs.autoConnect) { v -> prefs.autoConnect = v })
        if (sender) {
            conn.addView(divider())
            conn.addView(toggleRow(tr("התחל שידור כשהטאבלט מתחבר", "Start streaming when the tablet connects"),
                tr("במסך טאבלט זה מתחיל לבד. בשיקוף מסך תופיע בקשת האישור של אנדרואיד.", "Tablet screen starts by itself. Mirroring shows Android's share-screen prompt."),
                prefs.autoStream) { v -> prefs.autoStream = v })
        }

        if (sender) {
            // Quality
            col.addView(sectionLabel(tr("איכות שידור", "Stream quality")), lp(top = 24))
            val qTabs = mutableListOf<Pair<TextView, GlassDrawable>>()
            col.addView(segmented((0..2).map { CaptureService.presetName(it) }, qTabs) { i ->
                prefs.preset = i; paintTabs(qTabs, i)
            }, lp(top = 10))
            paintTabs(qTabs, prefs.preset)
            val q = glassCard(24f, 14)
            q.addView(toggleRow(tr("איכות אוטומטית", "Automatic quality"),
                tr("אם הטאבלט מפספס פריימים, קצב הנתונים יורד לבד ועולה בחזרה כשהכל יציב.", "If the tablet drops frames the bitrate lowers itself and comes back when stable."),
                prefs.adaptive) { v -> prefs.adaptive = v })
            q.addView(divider())
            q.addView(toggleRow(tr("התאם את הטלפון לרזולוציית הטאבלט", "Match the phone to the tablet's resolution"),
                tr("בזמן שיקוף הטלפון עובר לאותה רזולוציה ויחס מסך של הטאבלט — התמונה ממלאת את כל הטאבלט בלי פסים ובלי חיתוך. חוזר לרגיל כשהשידור נעצר. דורש Shizuku.",
                   "While mirroring, the phone switches to the tablet's resolution and aspect ratio — the picture fills the whole tablet with no bars and no cropping. Back to normal when streaming stops. Needs Shizuku."),
                prefs.matchTablet) { v -> prefs.matchTablet = v }.also { if (!sender) it.visibility = View.GONE })
            if (prefs.restoreCmd != null && !Session.streaming) {
                q.addView(glassButton(tr("החזר את רזולוציית הטלפון עכשיו", "Restore the phone's resolution now"), 18f, 14f).apply {
                    setOnClickListener { Thread { DisplayMatch.restore(this@SettingsActivity) }.start(); visibility = View.GONE }
                }, lp(top = 6))
            }
            q.addView(divider())
            q.addView(toggleRow(tr("פרופיל חכם לפי אפליקציה", "Smart per-app profile"),
                tr("משחק → פריסט משחק, אפליקציית וידאו → אולטרה. דורש Shizuku.", "Game → Game preset, video app → Ultra. Needs Shizuku."),
                prefs.smartProfile) { v -> prefs.smartProfile = v })
            col.addView(q, lp(top = 12))

            // Audio
            col.addView(sectionLabel(tr("פלט שמע", "Audio output")), lp(top = 24))
            val aTabs = mutableListOf<Pair<TextView, GlassDrawable>>()
            col.addView(segmented(listOf(tr("טאבלט", "Tablet"), tr("שניהם", "Both"), tr("טלפון", "Phone")), aTabs) { i ->
                prefs.audio = i; AudioForwarder.output = AudioForwarder.Output.values()[i]; paintTabs(aTabs, i)
                if (Session.streaming) CaptureService.restartAudio()
            }, lp(top = 10))
            paintTabs(aTabs, prefs.audio)
        }

        // Controls
        val ctl = card(tr("שליטה", "Controls"), col)
        ctl.addView(toggleRow(tr("מקלדת, עכבר ושלט מהטאבלט", "Keyboard, mouse & gamepad from the tablet"),
            tr("אביזרים שמחוברים לטאבלט שולטים בטלפון. דורש Shizuku בטלפון. תמיכה בשלט תלויה במשחק.", "Accessories connected to the tablet control the phone. Needs Shizuku on the phone. Gamepad support depends on the game."),
            prefs.forwardInput) { v -> prefs.forwardInput = v })

        if (sender) {
            col.addView(sectionLabel("Shizuku"), lp(top = 24))
            shizuku = ShizukuCard(this, withSteps = !ShizukuBridge.ready).also { col.addView(it.view, lp(top = 10)) }
        }

        // About
        val about = card(tr("עוד", "More"), col)
        val errors = ErrorLog.entries()
        about.addView(glassButton(tr("יומן שגיאות", "Error log") + if (errors.isNotEmpty()) " (${errors.size})" else "", 18f, 15f).apply {
            setOnClickListener { startActivity(Intent(this@SettingsActivity, ErrorLogActivity::class.java)) }
        })
        about.addView(glassButton(tr("הצג שוב את מדריך ההתחלה", "Show the setup guide again"), 18f, 15f).apply {
            setOnClickListener { startActivity(Intent(this@SettingsActivity, OnboardingActivity::class.java)) }
        }, lp(top = 8))
        about.addView(label("UltraDisplay v${BuildConfig.VERSION_NAME}", 12f, false, Glass.TEXT_3).apply {
            textAlignment = android.view.View.TEXT_ALIGNMENT_CENTER
        }, lp(top = 12))

        setContentView(root)
    }
}
