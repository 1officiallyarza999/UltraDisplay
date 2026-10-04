package com.ultradisplay.app

import android.app.Activity
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import kotlin.concurrent.thread

/**
 * Home screen shown on the tablet-sized virtual display. Runs on the phone, so every app it opens
 * runs on the phone's processor and is rendered at tablet size.
 */
class LauncherActivity : Activity() {
    private data class App(val label: String, val component: ComponentName, val icon: Drawable)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ShizukuBridge.init(this)
        window.statusBarColor = Color.TRANSPARENT

        val root = FrameLayout(this)
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL
        root.addView(LiquidBackground(this), FrameLayout.LayoutParams(-1, -1))

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(36), dpi(28), dpi(36), dpi(12)) }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(label("UltraDisplay", 26f, true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(label("רץ על המעבד של ${Build.MODEL}", 13f, false, Glass.TEXT_2))
        col.addView(header)

        val grid = GridView(this).apply {
            numColumns = GridView.AUTO_FIT
            columnWidth = dpi(112)
            stretchMode = GridView.STRETCH_SPACING_UNIFORM
            verticalSpacing = dpi(14)
            horizontalSpacing = dpi(14)
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            clipToPadding = false
            setPadding(0, dpi(20), 0, dpi(20))
        }
        col.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(col, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        val loading = label("טוען אפליקציות…", 15f, false, Glass.TEXT_2).apply { gravity = Gravity.CENTER }
        root.addView(loading, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        thread {
            val pm = packageManager
            val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .filter { it.activityInfo.packageName != packageName }
                .map { ri: ResolveInfo -> App(ri.loadLabel(pm).toString(), ComponentName(ri.activityInfo.packageName, ri.activityInfo.name), ri.loadIcon(pm)) }
                .sortedBy { it.label.lowercase() }
            runOnUiThread {
                root.removeView(loading)
                grid.adapter = Adapter(apps)
                grid.setOnItemClickListener { _, _, pos, _ -> open(apps[pos]) }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun currentDisplayId(): Int =
        if (Build.VERSION.SDK_INT >= 30) display?.displayId ?: 0
        else windowManager.defaultDisplay.displayId

    private fun open(app: App) {
        val displayId = currentDisplayId()
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(app.component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle())
            Session.log("נפתח במסך הטאבלט: ${app.label}")
        } catch (e: Exception) {
            // Fall back to shell privileges (also moves an app that is already running on the phone).
            thread {
                val out = ShizukuBridge.launch(app.component, displayId, clearTask = true)
                Session.log("נפתח דרך Shizuku: ${app.label} ${if (out.contains("Error")) "— $out" else ""}")
            }
        }
    }

    private inner class Adapter(private val apps: List<App>) : BaseAdapter() {
        override fun getCount() = apps.size
        override fun getItem(position: Int) = apps[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val tile = (convertView as? LinearLayout) ?: glassCard(24f, 12).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                addView(ImageView(context).apply { id = android.R.id.icon }, LinearLayout.LayoutParams(dpi(56), dpi(56)))
                addView(label("", 13f).apply {
                    id = android.R.id.text1; gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER
                    maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(8) })
                minimumHeight = dpi(118)
            }
            val app = apps[position]
            tile.findViewById<ImageView>(android.R.id.icon).setImageDrawable(app.icon)
            tile.findViewById<TextView>(android.R.id.text1).text = app.label
            return tile
        }
    }
}
