package com.ultradisplay.app

import android.content.Context
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * While mirroring, switch the phone's own display to the tablet's resolution and aspect ratio
 * (the same as `adb shell wm size` / `wm density`). Apps then lay out for the tablet, so the
 * picture fills it exactly — no black bars and nothing cropped. Restored when streaming stops,
 * and again on the next start if the app was killed mid-stream.
 */
object DisplayMatch {
    @Volatile var active = false; private set

    private fun ints(text: String, label: String): Pair<Int, Int>? =
        Regex("$label size: (\\d+)x(\\d+)").find(text)?.destructured?.let { (a, b) -> a.toInt() to b.toInt() }

    private fun density(text: String, label: String): Int? =
        Regex("$label density: (\\d+)").find(text)?.groupValues?.get(1)?.toInt()

    /** Returns true when the phone now matches the tablet. */
    fun apply(ctx: Context): Boolean {
        val prefs = Prefs.of(ctx)
        if (!prefs.matchTablet) return false
        val shell = ShizukuBridge.service ?: return false
        val tw = Session.peerW; val th = Session.peerH // tablet, landscape: long × short
        if (tw <= 0 || th <= 0) return false
        return try {
            val sizeOut = shell.exec("wm size"); val densOut = shell.exec("wm density")
            val phys = ints(sizeOut, "Physical") ?: return false
            val over = ints(sizeOut, "Override")
            val physD = density(densOut, "Physical") ?: return false
            val overD = density(densOut, "Override")
            // Remember exactly what to put back (Samsung often runs an FHD+ override by default).
            if (prefs.restoreCmd == null) {
                prefs.restoreCmd = (over?.let { "wm size ${it.first}x${it.second}" } ?: "wm size reset") + "; " +
                    (overD?.let { "wm density $it" } ?: "wm density reset")
            }
            val cur = over ?: phys
            val curD = overD ?: physD
            // The phone's natural orientation is portrait, so the size is short × long.
            val shortSide = th; val longSide = tw
            // Keep the UI the same physical size: scale density with the change in width.
            val newD = (curD * shortSide.toDouble() / min(cur.first, cur.second)).roundToInt().coerceIn(120, 800)
            shell.exec("wm size ${shortSide}x$longSide; wm density $newD")
            Thread.sleep(450)
            active = true
            Session.log(tr("הטלפון הותאם לרזולוציית הטאבלט: ", "Phone matched to the tablet: ") + "${longSide}×$shortSide")
            true
        } catch (e: Exception) {
            ErrorLog.record(ErrorLog.Kind.ERROR, "Display match failed", e); false
        }
    }

    /** Put the phone's resolution and density back as they were. Safe to call any time. */
    fun restore(ctx: Context) {
        val prefs = Prefs.of(ctx)
        val cmd = prefs.restoreCmd ?: return
        val shell = ShizukuBridge.service ?: return
        try {
            shell.exec(cmd)
            prefs.restoreCmd = null
            active = false
            Session.log(tr("רזולוציית הטלפון הוחזרה", "Phone resolution restored"))
        } catch (e: Exception) { ErrorLog.record(ErrorLog.Kind.ERROR, "Display restore failed", e) }
    }
}
