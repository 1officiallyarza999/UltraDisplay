package com.ultradisplay.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout

/**
 * Shown the instant the app crashes. Runs in its own ":crash" process, so it still works when the
 * main process is dead or keeps crashing on start.
 */
class CrashActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Lang.load(this)
        val report = CrashReporter.pending(this) ?: ""
        val code = CrashReporter.pendingCode(this) ?: "?"
        val (root, col) = glassScreen(560)

        col.addView(label(tr("UltraDisplay נתקלה בשגיאה", "UltraDisplay hit an error"), 28f, true), lp(top = 30))
        col.addView(label(tr("האפליקציה נעצרה. הפרטים נשמרו — שלח אותם ואתקן את זה.", "The app stopped. The details were saved — send them and it gets fixed."),
            15f, false, Glass.TEXT_2), lp(top = 8))

        val c = glassCard(24f, 18)
        (c.background as? GlassDrawable)?.glassTint = Glass.RED_TINT
        c.addView(label(tr("קוד קריסה", "Crash code"), 12f, false, Glass.TEXT_3))
        c.addView(label(code, 22f, true, Glass.ACCENT).apply { typeface = Typeface.MONOSPACE; textDirection = View.TEXT_DIRECTION_LTR }, lp(top = 4))
        val firstError = report.lineSequence().firstOrNull { it.contains("Exception") || it.contains("Error:") } ?: ""
        c.addView(label(firstError.trim(), 12f, false, Glass.TEXT_2).apply { typeface = Typeface.MONOSPACE; textDirection = View.TEXT_DIRECTION_LTR; maxLines = 4 }, lp(top = 8))
        col.addView(c, lp(top = 22))

        col.addView(glassButton(tr("שלח דיווח (GitHub)", "Send report (GitHub)"), 26f, 17f, Glass.ACCENT_TINT).apply {
            minHeight = dpi(58)
            setOnClickListener { ErrorLog.reportOnGitHub(this@CrashActivity, ErrorLog.entries().filter { it.kind == ErrorLog.Kind.CRASH }.take(1)) }
        }, lp(top = 22))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(glassButton(tr("העתק פרטים", "Copy details"), 22f, 15f).apply {
            setOnClickListener {
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("UltraDisplay crash", report))
                text = tr("הועתק ✓", "Copied ✓")
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(glassButton(tr("שתף", "Share"), 22f, 15f).apply {
            setOnClickListener { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report), null)) }
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dpi(10) })
        col.addView(row, lp(top = 12))
        col.addView(glassButton(tr("פתח את UltraDisplay מחדש", "Open UltraDisplay again"), 22f, 15f).apply {
            setOnClickListener {
                startActivity(Intent(this@CrashActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                finish()
            }
        }, lp(top = 12))
        col.addView(label(tr("פרטים מלאים", "Full details"), 13f, true, Glass.TEXT_3), lp(top = 24))
        col.addView(label(report, 10.5f, false, Glass.TEXT_2).apply { typeface = Typeface.MONOSPACE; textDirection = View.TEXT_DIRECTION_LTR }, lp(top = 6))
        setContentView(root)
    }
}
