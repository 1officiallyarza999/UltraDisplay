package com.ultradisplay.app

import android.content.Context
import android.view.View
import java.util.Locale

/** Two-language UI (Hebrew / English). Follows the device language unless the user picked one. */
object Lang {
    @Volatile var choice = "auto" // "auto" | "he" | "en"
    val hebrew: Boolean get() = when (choice) {
        "he" -> true; "en" -> false
        else -> Locale.getDefault().language.let { it == "iw" || it == "he" }
    }
    val direction: Int get() = if (hebrew) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
    fun load(ctx: Context) { choice = Prefs.of(ctx).lang }
}

fun tr(he: String, en: String): String = if (Lang.hebrew) he else en
