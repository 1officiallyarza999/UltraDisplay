package com.ultradisplay.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Error log screen: every crash and error with its code; copy, share or report on GitHub. */
class ErrorLogActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        build()
    }

    private fun build() {
        val (root, col) = glassScreen()
        val entries = ErrorLog.entries()

        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(label(tr("יומן שגיאות", "Error log"), 30f, true), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(glassButton(tr("סגור", "Close"), 18f, 14f).apply { setOnClickListener { finish() } })
        col.addView(head)
        col.addView(label(tr("כל קריסה ושגיאה נשמרת כאן עם קוד. שלח לי את הקוד או את הדיווח המלא, וזה מספיק כדי למצוא את המקום המדויק בקוד.",
            "Every crash and error is saved here with a code. Send me the code or the full report — that's enough to find the exact spot in the code."),
            14f, false, Glass.TEXT_2), lp(top = 8))

        val crashes = entries.count { it.kind == ErrorLog.Kind.CRASH }
        val peer = entries.count { it.kind == ErrorLog.Kind.PEER_CRASH }
        val summary = glassCard(24f, 16)
        summary.addView(label(if (entries.isEmpty()) tr("אין שגיאות ✓", "No errors ✓")
            else tr("$crashes קריסות · ${entries.size - crashes - peer} שגיאות · $peer מהמכשיר השני",
                    "$crashes crashes · ${entries.size - crashes - peer} errors · $peer from the other device"), 16f, true))
        summary.addView(label(ErrorLog.device(), 12f, false, Glass.TEXT_3), lp(top = 6))
        col.addView(summary, lp(top = 18))

        if (entries.isNotEmpty()) {
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            actions.addView(glassButton(tr("דווח ב-GitHub", "Report on GitHub"), 20f, 14f, Glass.ACCENT_TINT).apply {
                setOnClickListener { ErrorLog.reportOnGitHub(this@ErrorLogActivity, entries) }
            }, LinearLayout.LayoutParams(0, -2, 1.4f))
            actions.addView(glassButton(tr("העתק הכל", "Copy all"), 20f, 14f).apply {
                setOnClickListener { copy(ErrorLog.format(entries)); text = tr("הועתק ✓", "Copied ✓") }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dpi(8) })
            actions.addView(glassButton(tr("שתף", "Share"), 20f, 14f).apply {
                setOnClickListener { share(ErrorLog.format(entries)) }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dpi(8) })
            col.addView(actions, lp(top = 12))
        }

        entries.take(60).forEach { e -> col.addView(entryCard(e), lp(top = 12)) }

        if (entries.isNotEmpty()) {
            col.addView(glassButton(tr("נקה את היומן", "Clear the log"), 20f, 14f, Glass.RED_TINT).apply {
                setOnClickListener { ErrorLog.clear(); CrashReporter.clear(this@ErrorLogActivity); build() }
            }, lp(top = 20))
        }
        setContentView(root)
    }

    private fun entryCard(e: ErrorLog.Entry): View {
        val c = glassCard(22f, 14)
        val (badge, tint) = when (e.kind) {
            ErrorLog.Kind.CRASH -> tr("קריסה", "Crash") to Glass.RED_TINT
            ErrorLog.Kind.PEER_CRASH -> tr("קריסה במכשיר השני", "Other device crash") to Glass.RED_TINT
            ErrorLog.Kind.ERROR -> tr("שגיאה", "Error") to 0
        }
        (c.background as? GlassDrawable)?.glassTint = tint
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(label(badge, 14f, true), LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(label(e.time, 11.5f, false, Glass.TEXT_3))
        c.addView(top)
        c.addView(label(e.code, 15f, true, Glass.ACCENT).apply { typeface = Typeface.MONOSPACE; textDirection = View.TEXT_DIRECTION_LTR }, lp(top = 6))
        c.addView(label(e.title, 13f, false, Glass.TEXT_2).apply { textDirection = View.TEXT_DIRECTION_ANY_RTL; maxLines = 3 }, lp(top = 4))
        val detail = label(e.detail.trim(), 11f, false, Glass.TEXT_2).apply {
            typeface = Typeface.MONOSPACE; textDirection = View.TEXT_DIRECTION_LTR; visibility = View.GONE
        }
        c.addView(detail, lp(top = 8))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (e.detail.isNotBlank()) row.addView(glassButton(tr("פרטים", "Details"), 16f, 13f).apply {
            setOnClickListener {
                val open = detail.visibility != View.VISIBLE
                detail.visibility = if (open) View.VISIBLE else View.GONE
                text = if (open) tr("הסתר", "Hide") else tr("פרטים", "Details")
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(glassButton(tr("העתק קוד", "Copy code"), 16f, 13f).apply {
            setOnClickListener { copy("${e.code}\n${e.title}"); text = tr("הועתק ✓", "Copied ✓") }
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { if (e.detail.isNotBlank()) marginStart = dpi(8) })
        row.addView(glassButton("GitHub", 16f, 13f).apply {
            setOnClickListener { ErrorLog.reportOnGitHub(this@ErrorLogActivity, listOf(e)) }
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dpi(8) })
        c.addView(row, lp(top = 10))
        return c
    }

    private fun copy(text: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("UltraDisplay", text))
    }

    private fun share(text: String) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "UltraDisplay error log").putExtra(Intent.EXTRA_TEXT, text), null))
    }
}
