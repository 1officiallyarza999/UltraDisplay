package com.ultradisplay.app

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Glass card that shows Shizuku's state and the one button that moves the user to the next step. */
class ShizukuCard(private val activity: Activity, withSteps: Boolean = false) {
    val view: LinearLayout = activity.glassCard(24f, 16)
    private val dot: View
    private val text: TextView
    private val button: TextView

    init {
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        dot = activity.dot(Glass.GREY, 10)
        row.addView(dot)
        row.addView(activity.label("Shizuku", 16f, true), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = activity.dpi(10) })
        view.addView(row)
        text = activity.label("", 13f, false, Glass.TEXT_2)
        view.addView(text, activity.lp(top = 8))
        if (withSteps) {
            listOf(
                tr("התקן את Shizuku מחנות Play.", "Install Shizuku from the Play Store."),
                tr("הגדרות ← אודות הטלפון ← פרטי תוכנה ← הקש 7 פעמים על \"מספר Build\".", "Settings → About phone → Software information → tap \"Build number\" 7 times."),
                tr("באפשרויות מפתח הפעל \"ניפוי באגים אלחוטי\" (צריך Wi‑Fi).", "In Developer options turn on \"Wireless debugging\" (needs Wi‑Fi)."),
                tr("ב-Shizuku: \"צימוד\", הזן את הקוד מההתראה, ואז \"התחל\".", "In Shizuku: \"Pairing\", enter the code from the notification, then \"Start\"."),
                tr("חזור לכאן ואשר גישה.", "Come back here and allow access.")
            ).forEachIndexed { i, s ->
                val line = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
                line.addView(activity.label("${i + 1}", 13f, true, Glass.ACCENT), LinearLayout.LayoutParams(activity.dpi(20), -2))
                line.addView(activity.label(s, 13f, false, Glass.TEXT_2), LinearLayout.LayoutParams(0, -2, 1f))
                view.addView(line, activity.lp(top = 8))
            }
        }
        button = activity.glassButton("", 18f, 14f, Glass.ACCENT_TINT).apply { setOnClickListener { act() } }
        view.addView(button, activity.lp(top = 12))
        update()
    }

    private fun act() {
        when (ShizukuBridge.state) {
            ShizukuBridge.State.NOT_INSTALLED, ShizukuBridge.State.NOT_RUNNING -> ShizukuBridge.openShizuku(activity)
            ShizukuBridge.State.NO_PERMISSION -> ShizukuBridge.requestPermission()
            else -> ShizukuBridge.refresh()
        }
    }

    fun update() {
        val (color, msg, action) = when (ShizukuBridge.state) {
            ShizukuBridge.State.READY -> Triple(Glass.GREEN,
                tr("פעיל ✓ מגע מלא, מקלדת ושלט, מסך טאבלט, שמע לטאבלט ופרופיל חכם.", "Active ✓ Full touch, keyboard & gamepad, tablet screen, tablet audio and smart profile."), "")
            ShizukuBridge.State.CONNECTING -> Triple(Glass.AMBER, tr("מתחבר ל-Shizuku…", "Connecting to Shizuku…"), "")
            ShizukuBridge.State.NO_PERMISSION -> Triple(Glass.AMBER,
                tr("Shizuku פועל. צריך לאשר ל-UltraDisplay גישה.", "Shizuku is running. Allow UltraDisplay to use it."), tr("אשר גישה", "Allow access"))
            ShizukuBridge.State.NOT_RUNNING -> Triple(Glass.AMBER,
                tr("Shizuku מותקן אבל לא מופעל. פתח אותו ולחץ \"התחל\".", "Shizuku is installed but not started. Open it and tap \"Start\"."), tr("פתח את Shizuku", "Open Shizuku"))
            ShizukuBridge.State.NOT_INSTALLED -> Triple(Glass.GREY,
                tr("נדרש למגע מלא במשחקים, למסך טאבלט ולשמע בטאבלט בלבד. בלעדיו יש הקשות פשוטות.", "Needed for full game touch, tablet screen and tablet-only audio. Without it you get simple taps."), tr("התקן Shizuku", "Install Shizuku"))
            ShizukuBridge.State.ERROR -> Triple(Glass.RED, ShizukuBridge.lastError.ifBlank { tr("שגיאה", "Error") }, tr("נסה שוב", "Try again"))
        }
        (dot.background as? GradientDrawable)?.setColor(color)
        text.text = msg
        button.text = action
        button.visibility = if (action.isEmpty()) View.GONE else View.VISIBLE
    }
}
